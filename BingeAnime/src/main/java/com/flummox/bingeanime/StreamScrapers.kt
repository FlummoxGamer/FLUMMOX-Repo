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

private val STOP_WORDS = setOf(
    "the", "a", "an", "of", "and", "or", "in", "on", "at", "to",
    "season", "part", "cour", "episode", "ova", "ona", "special"
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

fun titleMatches(a: String, b: String): Boolean {
    val sa = stripQualifiers(a)
    val sb = stripQualifiers(b)
    if (sa.isEmpty() || sb.isEmpty()) return false
    if (sa == sb) return true
    val ta = sa.split(" ").filter { it.isNotBlank() }.toSet()
    val tb = sb.split(" ").filter { it.isNotBlank() }.toSet()
    if (ta.isEmpty() || tb.isEmpty()) return false
    val common = ta.intersect(tb)
    if (common.isEmpty()) return false
    if (ta.size == 1) return sb.startsWith(sa)
    if (tb.size == 1) return sa.startsWith(sb)
    val qInC = common.size.toFloat() / ta.size
    val cInQ = common.size.toFloat() / tb.size
    return qInC >= 0.7f || cInQ >= 0.7f
}

// ═══════════════════════════════════════════════════════════════
// AniKage scraper
// ═══════════════════════════════════════════════════════════════
suspend fun anikageExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    BLog.d("anikage: searching '${q.title}' (${q.year})")

    // ── build candidate titles: query title first, then
    //    AniList romaji/english/native variants ──
    val candidates = linkedSetOf<String>()
    if (q.title.isNotBlank()) candidates.add(q.title)

    // Pull variants from AniList. Prefer direct ID from sourceUrl
    // (anilist:123), fall back to a title search.
    val alId = Regex("anilist:(\\d+)").find(q.sourceUrl)?.groupValues?.get(1)?.toIntOrNull()
    val refEntry: AniListApi.Entry? = try {
        if (alId != null) AniListApi.getEntry(alId)
        else AniListApi.searchAnime(q.title, q.year.toIntOrNull()).firstOrNull()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BLog.v("anikage: AniList lookup failed: ${e.message}"); null
    }
    refEntry?.let { e ->
        listOfNotNull(e.title.english, e.title.romaji, e.title.native)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { candidates.add(it) }
    }
    BLog.v("anikage candidates: ${candidates.joinToString(" | ")}")

    // ── try each candidate until we get a hit ──
    val yr = q.year.toIntOrNull()
    var slug: String? = null
    var matchedTitle: String? = null
    for (cand in candidates) {
        val hits = try { AnikageApi.search(cand) } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.v("anikage search '$cand' failed: ${e.message}"); emptyList()
        }
        if (hits.isEmpty()) continue
        BLog.v("anikage '$cand' → ${hits.size} hits, top: ${hits.first().title.english ?: hits.first().title.romaji}")

        val withYear = hits.firstOrNull { h ->
            val t = h.title.english ?: h.title.romaji ?: h.title.native ?: return@firstOrNull false
            titleMatches(cand, t) && yr != null && h.seasonYear == yr
        }
        val anyYear = hits.firstOrNull { h ->
            val t = h.title.english ?: h.title.romaji ?: h.title.native ?: return@firstOrNull false
            titleMatches(cand, t)
        }
        val best = withYear ?: anyYear
        if (best != null) {
            slug = best.sourceId
            matchedTitle = best.title.english ?: best.title.romaji ?: best.title.native
            BLog.d("anikage: matched '$cand' → slug=$slug (${if (withYear != null) "year-match" else "no-year"})")
            break
        }
    }

    if (slug == null) {
        BLog.d("anikage: no title match across ${candidates.size} candidates")
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

                        val opaqueUrls = out.map { it.url }.toSet()
                        bundle.embeds.forEach { eo ->
                            val alreadyCovered = opaqueUrls.any { it.contains(eo.key) }
                            if (alreadyCovered) return@forEach
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
