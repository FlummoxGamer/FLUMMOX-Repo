package com.flummox.bingeanime

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ScrapedMirror(
    val quality: String,
    val mirror: String,
    val url: String,
    val source: String,
    val headers: Map<String, String>? = null,
    val captions: List<Pair<String, String>> = emptyList()
)

private const val PER_SOURCE_TIMEOUT_MS = 8000L

// Only these providers expose subtitle tracks in AniKage's API.
private val SUB_PROVIDERS = setOf("koto", "suge")

// Dropped — server is non-functional on AniKage's side.
private val DROPPED_SERVERS = setOf("dib")

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

private suspend fun lookupAniListRef(q: StreamQuery): AniListApi.Entry? {
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
// AniKage scraper
//
// Signature mirrors the progressive-emit pattern:
//   • onLink  — called from inside each parallel source coroutine
//               the moment its mirrors resolve. CloudStream renders
//               them immediately (instantLinkLoading).
//   • onSub   — same for verified subs.
//   • sem     — global concurrency cap (mirrors BingeCloud).
//
// Return value is the full mirror list, used by the caller to
// write the cache. Progressive emit is a side effect.
// ═══════════════════════════════════════════════════════════════
suspend fun anikageExtractRaw(
    q: StreamQuery,
    onLink: ((ScrapedMirror, Int) -> Unit)? = null,
    onSub: ((String, String) -> Unit)? = null,
    sem: Semaphore? = null
): List<ScrapedMirror> {
    val yr = q.year.toIntOrNull()
    BLog.d("anikage: searching '${q.title}' (${q.year})")

    var slug: String? = null
    var matchedTitle: String? = null

    val fast = trySearchAndPick(q.title, yr)
    if (fast?.sourceId != null) {
        slug = fast.sourceId
        matchedTitle = fast.title.english ?: fast.title.romaji ?: fast.title.native
        BLog.d("anikage: fast match '$matchedTitle' slug=$slug")
    } else {
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

    if (slug == null) { BLog.d("anikage: no title match"); return emptyList() }

    val ep = if (q.type == "movie") 1 else q.episode.takeIf { it > 0 } ?: 1
    BLog.d("anikage: using slug=$slug ('$matchedTitle') ep=$ep")

    val servers = AnikageApi.servers(slug, ep)
        .filter { it.id !in DROPPED_SERVERS }
    if (servers.isEmpty()) { BLog.d("anikage: no servers"); return emptyList() }

    val pairs = servers.flatMap { sv -> sv.subTypes.map { sv.id to it } }
    BLog.d("anikage: ${servers.size} servers → ${pairs.size} source fetches")

    val subSeen = ConcurrentHashMap.newKeySet<String>()
    val emittedCount = AtomicInteger(0)
    val collected = ConcurrentHashMap<String, ScrapedMirror>()
    val collectedLock = Any()

    coroutineScope {
        pairs.map { (pid, lang) ->
            async {
                val runnable = suspend {
                    try {
                        withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                            val bundle = AnikageApi.sources(slug, ep, pid, lang)
                                ?: return@withTimeoutOrNull

                            // Subs — koto/suge only, dedup by URL.
                            if (pid in SUB_PROVIDERS && onSub != null) {
                                bundle.subs.forEach { s ->
                                    val url = AnikageApi.subUrl(s.file)
                                    if (subSeen.add(url)) {
                                        try { onSub.invoke(url, s.label) } catch (_: Exception) {}
                                    }
                                }
                            }

                            // Mirrors — emit each as it arrives.
                            bundle.sources.forEach { s ->
                                if (!s.isM3U8) return@forEach
                                val m = ScrapedMirror(
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
                                    captions = emptyList() // subs already pushed via onSub
                                )
                                synchronized(collectedLock) {
                                    if (collected.putIfAbsent(m.url, m) == null) {
                                        val score = LinkScore.prelimScore(m)
                                        try { onLink?.invoke(m, score) } catch (_: Exception) {}
                                        emittedCount.incrementAndGet()
                                    }
                                }
                            }

                            // Embed fallback — only if opaque sources empty.
                            if (bundle.sources.isEmpty() && bundle.embeds.isNotEmpty()) {
                                bundle.embeds.forEach { eo ->
                                    val extracted = megaplayExtract(
                                        eo.url,
                                        "${eo.label} · ${lang.uppercase()}"
                                    )
                                    extracted.forEach { m ->
                                        synchronized(collectedLock) {
                                            if (collected.putIfAbsent(m.url, m) == null) {
                                                val score = LinkScore.prelimScore(m)
                                                try { onLink?.invoke(m, score) } catch (_: Exception) {}
                                                emittedCount.incrementAndGet()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        BLog.v("anikage $pid/$lang failed: ${e.message}")
                    }
                }
                if (sem != null) sem.withPermit { runnable() } else runnable()
            }
        }.awaitAll()
    }

    val result = synchronized(collectedLock) { collected.values.toList() }
    BLog.d("anikage: ${result.size} mirrors (${emittedCount.get()} emitted live)")
    return result
}

// ═══════════════════════════════════════════════════════════════
// Background variant — returns list, no emit. Used by prefetch.
// ═══════════════════════════════════════════════════════════════
suspend fun anikageExtractRaw(q: StreamQuery): List<ScrapedMirror> =
    anikageExtractRaw(q, onLink = null, onSub = null, sem = null)

// ═══════════════════════════════════════════════════════════════
// Dispatcher — background (prefetch).
// ═══════════════════════════════════════════════════════════════
suspend fun scrapeAllSources(q: StreamQuery): List<ScrapedMirror> {
    BLog.section("scrapeAllSources: ${q.title} (${q.year}) ${q.type} S${q.season}E${q.episode}")
    return coroutineScope {
        val jobs = mutableListOf<Deferred<List<ScrapedMirror>>>()

        if (BingeAnimeSettings.isSrcAnikage()) jobs.add(async {
            withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS * 3) {
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
