package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
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

data class AniKageScrape(
    val mirrors: List<ScrapedMirror>,
    val subs: List<Pair<String, String>>   // (label, remoteProxyUrl)
)

private const val PER_SOURCE_TIMEOUT_MS = 8000L

// AniKage hard-caps /sources at 15 req/min. Firing all servers in
// parallel at 6 servers × 2 langs = 11 hits instantly trips it.
// 4 is under half the budget and still parallel enough.
private const val ANIKAGE_CONCURRENCY = 4

// Only koto and suge return usable subtitle tracks.
private val SUB_PROVIDERS = setOf("koto", "suge")

// Priority order — koto and suge first, then kiwi/wave, then the rest.
private val SERVER_PRIORITY = listOf("koto", "suge", "kiwi", "wave")

// Dropped — non-functional on AniKage's side.
//   dib : returns dead mirrors / wrong content
//   megg: times out >8s on every title
//   zen : empty sources[] on every title
private val DROPPED_SERVERS = setOf("dib", "megg", "zen")

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

// ── sub handling ──
private fun isEnglish(label: String): Boolean =
    label.contains("english", ignoreCase = true)

private fun md5(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

// Downloads the sub body to app-private storage and returns a
// file:// URI.
//
// Why this is not a simple app.get():
//   og.bakayaro.live/stream/{opaque} serves subtitles with
//   Content-Encoding: zstd, which OkHttp cannot decompress. Reading
//   res.text yields garbage bytes and the WEBVTT check fails.
//   The browser (which supports zstd) follows the redirect to the
//   upstream CDN and gets a plain uncompressed VTT file. We have to
//   force the proxy to send us the identity encoding — if the server
//   ignores that, we fall back to emitting the raw URL so ExoPlayer
//   can try it with text/vtt mime inference (see emit path).
internal suspend fun fetchSubLocal(remoteUrl: String): String? {
    val ctx = BingeAnimeCtx.context ?: run {
        BLog.e("sub dl: no context"); return null
    }
    val dir = File(ctx.filesDir, "anikage_subs").apply { mkdirs() }
    val f = File(dir, "${md5(remoteUrl)}.vtt")
    if (f.exists() && f.length() > 0) {
        BLog.v("sub dl: cache hit ${f.length()}b")
        return SubServer.urlFor(f.name)
    }
    return try {
        val res = app.get(
            remoteUrl,
            headers = mapOf(
                "Referer" to AnikageApi.referer(),
                // Force identity encoding so the proxy serves raw VTT
                // instead of zstd-compressed bytes. OkHttp does not
                // support zstd; the browser does, which is why the
                // site's own player works.
                "Accept-Encoding" to "identity"
            )
        )
        val ce = res.headers["Content-Encoding"].orEmpty()
        val ct = res.headers["Content-Type"].orEmpty()
        BLog.d("sub dl: code=${res.code} ct=$ct ce=$ce len=${res.text.length}")
        if (res.code !in 200..299) { BLog.e("sub dl: bad code"); return null }
        val body = res.text
        if (ce.contains("zstd", ignoreCase = true)) {
            BLog.e("sub dl: server ignored identity, still zstd")
            return null
        }
        val hasVtt = body.contains("WEBVTT", ignoreCase = true)
        BLog.d("sub dl: hasVtt=$hasVtt head='${body.take(40).replace("\n", "\\n")}'")
        if (body.length < 20 || !hasVtt) return null
        f.writeText(body)
        BLog.d("sub dl: wrote ${f.length()}b → ${f.absolutePath}")
        SubServer.urlFor(f.name)
    } catch (e: Exception) {
        BLog.e("sub dl: exception ${e.message}")
        null
    }
}

// ═══════════════════════════════════════════════════════════════
// AniKage scraper — progressive emit + hard concurrency cap.
// ═══════════════════════════════════════════════════════════════
suspend fun anikageExtractRaw(
    q: StreamQuery,
    onLink: (suspend (ScrapedMirror, Int) -> Unit)? = null,
    onSub: (suspend (String, String) -> Unit)? = null
): AniKageScrape {
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

    if (slug == null) { BLog.d("anikage: no title match"); return AniKageScrape(emptyList(), emptyList()) }

    val ep = if (q.type == "movie") 1 else q.episode.takeIf { it > 0 } ?: 1
    BLog.d("anikage: using slug=$slug ('$matchedTitle') ep=$ep")

    val servers = AnikageApi.servers(slug, ep)
        .filter { it.id !in DROPPED_SERVERS }
    if (servers.isEmpty()) { BLog.d("anikage: no servers"); return AniKageScrape(emptyList(), emptyList()) }

    // Order servers by priority — koto/suge first so their responses
    // land first under the concurrency cap.
    val ordered = servers.sortedBy { sv ->
        val idx = SERVER_PRIORITY.indexOf(sv.id)
        if (idx < 0) Int.MAX_VALUE else idx
    }
    val pairs = ordered.flatMap { sv -> sv.subTypes.map { sv.id to it } }
    BLog.d("anikage: ${servers.size} servers → ${pairs.size} source fetches (cap=$ANIKAGE_CONCURRENCY)")

// Kiwi (Nero mirror) is slow and frequently half-broken on their side.
// Demote to fallback: fire only if every other server returns zero
// mirrors for this episode.
val primaryPairs = pairs.filter { it.first != "kiwi" }
val kiwiPairs = pairs.filter { it.first == "kiwi" }

val sem = Semaphore(ANIKAGE_CONCURRENCY)
    val collected = ConcurrentHashMap<String, ScrapedMirror>()
    val collectedSubs = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    val collectedLock = Any()
    val emittedCount = AtomicInteger(0)
    
suspend fun handleOne(pid: String, lang: String) {
    try {
        withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
            val bundle = sem.withPermit {
                AnikageApi.sources(slug, ep, pid, lang)
            } ?: return@withTimeoutOrNull

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
                    captions = emptyList()
                )
                val isNew = synchronized(collectedLock) {
                    collected.putIfAbsent(m.url, m) == null
                }
                if (isNew) {
                    val score = LinkScore.prelimScore(m)
                    try { onLink?.invoke(m, score) } catch (_: Exception) {}
                    emittedCount.incrementAndGet()
                }
            }

            if (pid in SUB_PROVIDERS && onSub != null) {
                bundle.subs
                .filter { BingeAnimeSettings.subLangMatches(it.label) }
                .forEach { s ->
            val proxyUrl = AnikageApi.subUrl(s.file)
            val local = fetchSubLocal(proxyUrl)
            val emitUrl = local ?: "$proxyUrl.vtt"
            BLog.d("sub emit: ${if (local != null) "local" else "raw"} $emitUrl [${s.label}]")
            try { onSub.invoke(emitUrl, s.label) } catch (_: Exception) {}
            collectedSubs.add(s.label to proxyUrl)
        }
            }

            if (bundle.sources.isEmpty() && bundle.embeds.isNotEmpty()) {
                bundle.embeds.forEach { eo ->
                    val extracted = megaplayExtract(
                        eo.url,
                        "${eo.label} · ${lang.uppercase()}"
                    )
                    extracted.forEach { m ->
                        val isNew = synchronized(collectedLock) {
                            collected.putIfAbsent(m.url, m) == null
                        }
                        if (isNew) {
                            val score = LinkScore.prelimScore(m)
                            try { onLink?.invoke(m, score) } catch (_: Exception) {}
                            emittedCount.incrementAndGet()
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

coroutineScope {
    primaryPairs.map { (pid, lang) ->
        async { handleOne(pid, lang) }
    }.awaitAll()
}

if (collected.isEmpty() && kiwiPairs.isNotEmpty()) {
    BLog.d("anikage: primary servers returned 0 mirrors — engaging kiwi fallback")
    coroutineScope {
        kiwiPairs.map { (pid, lang) ->
            async { handleOne(pid, lang) }
        }.awaitAll()
    }
}

    val result = synchronized(collectedLock) { collected.values.toList() }
    val subs = synchronized(collectedSubs) { collectedSubs.toList() }
    BLog.d("anikage: ${result.size} mirrors (${emittedCount.get()} emitted live), ${subs.size} subs")
    return AniKageScrape(result, subs)
}

// Background variant — no emit. Used by tap-through-dedup path.
suspend fun anikageExtractRaw(q: StreamQuery): AniKageScrape =
    anikageExtractRaw(q, onLink = null, onSub = null)

// ═══════════════════════════════════════════════════════════════
// Dispatcher
// ═══════════════════════════════════════════════════════════════
suspend fun scrapeAllSources(q: StreamQuery): List<ScrapedMirror> {
    BLog.section("scrapeAllSources: ${q.title} (${q.year}) ${q.type} S${q.season}E${q.episode}")
    return coroutineScope {
        val jobs = mutableListOf<Deferred<List<ScrapedMirror>>>()

        if (BingeAnimeSettings.isSrcAnikage()) jobs.add(async {
            withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS * 3) {
                try { anikageExtractRaw(q).mirrors }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { BLog.e("anikage: ${e.message}"); emptyList() }
            } ?: run { BLog.e("anikage: timeout"); emptyList() }
        })
        if (BingeAnimeSettings.isSrcAniwaves()) jobs.add(async {
            withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS * 3) {
                try { aniwavesExtractRaw(q).mirrors }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { BLog.e("aniwaves: ${e.message}"); emptyList() }
            } ?: run { BLog.e("aniwaves: timeout"); emptyList() }
        })
        
        if (jobs.isEmpty()) return@coroutineScope emptyList()
        val all = jobs.awaitAll().flatten()
        val perSource = all.groupingBy { it.source }.eachCount()
        BLog.d("scrapeAllSources: ${all.size} mirrors — " +
            perSource.entries.joinToString(" ") { "${it.key}=${it.value}" })
        all
    }
}
