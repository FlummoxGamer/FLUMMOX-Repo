package com.flummox.bingeanime

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class ScrapedMirror(
    val quality: String,
    val mirror: String,
    val url: String,
    val source: String,
    val headers: Map<String, String>? = null,
    val captions: List<Pair<String, String>> = emptyList()
)

private const val PER_SOURCE_TIMEOUT_MS = 25000L

// ── title matcher ──
// "x" is a separator ("Hunter x Hunter", "Spy x Family"), not content.
private val STOP_WORDS = setOf(
    "the", "a", "an", "of", "and", "or", "in", "on", "at", "to",
    "season", "part", "cour", "episode", "ova", "ona", "special", "x"
)

private fun stripQualifiers(s: String): String {
    val cleaned = s.lowercase()
        .replace(Regex("""\b(season|s)\s*\d+\b"""), " ")
        .replace(Regex("""\bpart\s+\d+\b"""), " ")
        .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
        .replace(Regex("""[^a-z0-9 ]"""), " ")
    return cleaned.split(" ")
        .map { it.trim() }
        .filter { it.isNotBlank() && it !in STOP_WORDS }
        .joinToString(" ")
}

// Strict bidirectional score. Returns 0-100.
// Requires the query to be ≥70% covered AND candidate not to be
// much longer than the query (kills spinoff/side-story false matches).
private fun matchScore(query: String, candidate: String): Int {
    val sq = stripQualifiers(query)
    val sc = stripQualifiers(candidate)
    if (sq.isEmpty() || sc.isEmpty()) return 0
    if (sq == sc) return 100
    val tq = sq.split(" ").filter { it.isNotBlank() }.toSet()
    val tc = sc.split(" ").filter { it.isNotBlank() }.toSet()
    if (tq.isEmpty() || tc.isEmpty()) return 0
    if (tq.size == 1) return if (sc.startsWith(sq)) 30 else 0
    if (tc.size == 1) return if (sq.startsWith(sc)) 25 else 0
    val common = tq.intersect(tc).size
    if (common == 0) return 0
    val qInC = common.toFloat() / tq.size
    val cInQ = common.toFloat() / tc.size
    if (qInC < 0.7f || cInQ < 0.7f) return 0
    return (qInC * 100f).toInt()
}

private fun pickBest(
    hits: List<AniListApi.Entry>,
    query: String,
    year: Int?
): AniListApi.Entry? {
    if (hits.isEmpty()) return null
    data class Scored(val entry: AniListApi.Entry, val score: Int, val yearMatch: Boolean)
    val scored = hits.mapNotNull { h ->
        val variants = listOfNotNull(h.title.english, h.title.romaji, h.title.native)
        val best = variants.maxOfOrNull { matchScore(query, it) } ?: 0
        if (best == 0) null else Scored(h, best, year != null && h.seasonYear == year)
    }
    if (scored.isEmpty()) return null
    val yearMatches = scored.filter { it.yearMatch }
    val pool = if (yearMatches.isNotEmpty()) yearMatches else scored
    return pool.maxByOrNull { it.score }?.entry
}

// ── AniList reference lookup (cached) ──
private suspend fun lookupAniListRef(q: StreamQuery): AniListApi.Entry? {
    val ck = "anikage:ref:${q.sourceUrl}:${q.title}:${q.year}"
    BCCache.get(ck, 6 * 60 * 60 * 1000L)?.let { _ ->
        // cache disabled for now; lookups are cheap
    }
    val alId = Regex("anilist:(\\d+)").find(q.sourceUrl)?.groupValues?.get(1)?.toIntOrNull()
    if (alId != null) {
        return try { AniListApi.getEntry(alId) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { BLog.v("anikage: AniList getEntry $alId failed: ${e.message}"); null }
    }
    return try {
        val hits = AniListApi.searchAnime(q.title, q.year.toIntOrNull())
        pickBest(hits, q.title, q.year.toIntOrNull())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BLog.v("anikage: AniList search failed: ${e.message}"); null
    }
}

private suspend fun trySearchAndPick(query: String, year: Int?): AniListApi.Entry? {
    val hits = try { AnikageApi.search(query) }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { BLog.v("anikage search '$query' failed: ${e.message}"); return null }
    if (hits.isEmpty()) return null
    BLog.v("anikage '$query' → ${hits.size} hits")
    return pickBest(hits, query, year)
}

// ═══════════════════════════════════════════════════════════════
// AniKage scraper — fast path first, AniList variants on miss.
// ═══════════════════════════════════════════════════════════════
suspend fun anikageExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    val yr = q.year.toIntOrNull()
    BLog.d("anikage: searching '${q.title}' (${q.year})")

    var slug: String? = null
    var matchedTitle: String? = null

    // Fast path: search with the query title directly.
    val fast = trySearchAndPick(q.title, yr)
    if (fast?.sourceId != null) {
        slug = fast.sourceId
        matchedTitle = fast.title.english ?: fast.title.romaji ?: fast.title.native
        BLog.d("anikage: fast match '$matchedTitle' slug=$slug")
    } else {
        // Slow path: pull AniList variants and retry.
        BLog.d("anikage: fast path missed, trying variants")
        val ref = lookupAniListRef(q)
        if (ref != null) {
            val variants = linkedSetOf<String>()
            listOfNotNull(ref.title.english, ref.title.romaji, ref.title.native)
                .map { it.trim() }
                .filter { it.isNotBlank() && it != q.title }
                .forEach { variants.add(it) }
            BLog.v("anikage variants: ${variants.joinToString(" | ")}")
            for (v in variants) {
                val h = trySearchAndPick(v, yr) ?: continue
                if (h.sourceId != null) {
                    slug = h.sourceId
                    matchedTitle = h.title.english ?: h.title.romaji ?: h.title.native
                    BLog.d("anikage: variant match '$v' → '$matchedTitle' slug=$slug")
                    break
                }
            }
        }
    }

    if (slug == null) {
        BLog.d("anikage: no title match")
        return emptyList()
    }

    val ep = if (q.type == "movie") 1 else q.episode.takeIf { it > 0 } ?: 1
    BLog.d("anikage: using slug=$slug ('$matchedTitle') ep=$ep")

    val servers = AnikageApi.servers(slug, ep)
    if (servers.isEmpty()) { BLog.d("anikage: no servers"); return emptyList() }

    val pairs = servers.flatMap { sv -> sv.subTypes.map { sv.id to it } }
    BLog.d("anikage: ${servers.size} servers → ${pairs.size} source fetches")

    val mirrors = coroutineScope {
        pairs.map { (pid, lang) ->
            async {
                try {
                    withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                        val bundle = AnikageApi.sources(slug, ep, pid, lang)
                            ?: return@withTimeoutOrNull emptyList()

                        val subs = bundle.subs.map { it.label to AnikageApi.subUrl(it.file) }
                        val out = mutableListOf<ScrapedMirror>()

                        // Primary: opaque slug via og.bakayaro proxy.
                        bundle.sources.forEach { s ->
                            if (!s.isM3U8) return@forEach
                            out.add(ScrapedMirror(
                                quality = s.quality,
                                mirror = "$pid · ${lang.uppercase()} · ${s.quality}",
                                url = AnikageApi.hlsUrl(s.slug),
                                source = "ANIKAGE",
                                headers = mapOf(
                                    "Referer" to AnikageApi.referer(),
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 " +
                                        "Mobile Safari/537.36"
                                ),
                                captions = subs
                            ))
                        }

                        // Embeds only as fallback — if opaque is populated,
                        // E-Koto / E-Wish / E-Zen point to the same content
                        // via megaplay. Firing them anyway doubles the
                        // work for zero new mirrors.
                        if (out.isEmpty() && bundle.embeds.isNotEmpty()) {
                            bundle.embeds.forEach { eo ->
                                val extracted = megaplayExtract(
                                    eo.url,
                                    "${eo.label} · ${lang.uppercase()}"
                                )
                                extracted.forEach { m ->
                                    out.add(m.copy(
                                        captions = if (m.captions.isEmpty()) subs else m.captions
                                    ))
                                }
                            }
                        }

                        out
                    } ?: emptyList()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BLog.v("anikage $pid/$lang failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    val seen = mutableSetOf<String>()
    val deduped = mirrors.filter { seen.add(it.url) }
    BLog.d("anikage: ${deduped.size} mirrors (${mirrors.size} pre-dedup)")
    return deduped
}

// ═══════════════════════════════════════════════════════════════
// Dispatcher
// ═══════════════════════════════════════════════════════════════
suspend fun scrapeAllSources(q: StreamQuery): List<ScrapedMirror> {
    BLog.section("scrapeAllSources: ${q.title} (${q.year}) ${q.type} S${q.season}E${q.episode}")
    return coroutineScope {
        val jobs = mutableListOf<Deferred<List<ScrapedMirror>>>()

        if (BingeAnimeSettings.isSrcAnikage()) jobs.add(async {
            withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                try { anikageExtractRaw(q) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { BLog.e("anikage: ${e.message}"); emptyList() }
            } ?: run { BLog.e("anikage: timeout"); emptyList() }
        })

        if (jobs.isEmpty()) return@coroutineScope emptyList()
        val all = jobs.awaitAll().flatten()
        val perSource = all.groupingBy { it.source }.eachCount()
        BLog.d("scrapeAllSources: ${all.size} mirrors — " +
            perSource.entries.joinToString(" ") { "${it.key}=${it.value}" })
        all
    }
}
