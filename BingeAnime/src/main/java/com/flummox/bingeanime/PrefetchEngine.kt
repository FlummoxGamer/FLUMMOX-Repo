package com.flummox.bingeanime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// Video/sub prefetch engine.
// Home row prefetch (ShikimoriApi) is NOT gated by this — runs always.
// This only gates per-episode mirror scraping.
object PrefetchEngine {

    private const val DEBOUNCE_MS = 800L
    private const val HOME_GRACE_MS = 5000L
    private const val HISTORY_SIZE = 5

    private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight =
        ConcurrentHashMap<String, CompletableDeferred<AniKageScrape?>>()
    private val lock = Any()
    private val watchHistory = mutableMapOf<String, MutableList<Int>>()
    private var currentAnimeKey: String? = null
    private val sessionJobs = mutableListOf<Job>()

    @Volatile private var lastHomeRenderMs: Long = 0L

    fun markHomeRender() {
        lastHomeRenderMs = System.currentTimeMillis()
    }

    private fun isFromHome(): Boolean {
        val t = lastHomeRenderMs
        return t > 0 && System.currentTimeMillis() - t in 0 until HOME_GRACE_MS
    }

    fun beginSession(animeKey: String) {
        synchronized(lock) {
            if (currentAnimeKey != animeKey) {
                sessionJobs.forEach { runCatching { it.cancel() } }
                sessionJobs.clear()
                currentAnimeKey = animeKey
                BLog.v("prefetch: session → $animeKey")
            }
        }
    }

    fun recordPlay(q: StreamQuery) {
        val key = q.animeKey()
        synchronized(lock) {
            val l = watchHistory.getOrPut(key) { mutableListOf() }
            if (l.lastOrNull() != q.episode) {
                l.add(q.episode)
                while (l.size > HISTORY_SIZE) l.removeAt(0)
            }
            BLog.v("prefetch: play $key E${q.episode} hist=${l.joinToString(",")}")
        }
    }

// Warm ep 1 in background when detail opens. Skipped if the user
// just landed from home (they won't tap yet) or if prefetch is off.
fun warmOnLoad(q: StreamQuery, fetch: suspend (StreamQuery) -> AniKageScrape) {
    if (!BingeAnimeSettings.isPrefetchEnabled()) return
    if (isFromHome()) { BLog.v("prefetch: skipped (home grace)"); return }
    warm(listOf(q), fetch)
}

// Warm next episode after current plays.
fun warmAfterPlay(q: StreamQuery, fetch: suspend (StreamQuery) -> AniKageScrape) {
    if (!BingeAnimeSettings.isPrefetchEnabled()) return
    val plan = plan(q)
    if (plan.isEmpty()) return
    warm(plan, fetch)
}

    private fun plan(q: StreamQuery): List<StreamQuery> {
        val hist = synchronized(lock) {
            watchHistory[q.animeKey()]?.toList() ?: emptyList()
        }
        val sequential = hist.size >= 3 && hist.takeLast(3).let {
            it[1] == it[0] + 1 && it[2] == it[1] + 1
        }
        val nextEps = if (sequential) listOf(q.episode + 1, q.episode + 2)
                      else listOf(q.episode + 1)
        val total = q.totalEpisodes
        return nextEps
            .filter { it in 1..(if (total > 0) total else Int.MAX_VALUE) }
            .distinct()
            .map { q.copy(episode = it) }
    }

private fun warm(
    queries: List<StreamQuery>,
    fetch: suspend (StreamQuery) -> AniKageScrape
) {
    for (qq in queries) {
        val key = "aniwaves:${qq.cacheKey()}"
        if (BCCache.getBundle(key) != null) {
            BLog.v("prefetch: $key cached")
            continue
        }
        val job = SCOPE.launch {
            delay(DEBOUNCE_MS)
            obtain(key) { fetch(qq) }
        }
        synchronized(lock) { sessionJobs.add(job) }
    }
}

    // Single entry — loadLinks and prefetch both call this.
    // In-flight dedup via CompletableDeferred. Works in AniKageScrape
    // units so mirrors + subs share one atomic cache write.
    suspend fun obtain(
        key: String,
        work: suspend () -> AniKageScrape
    ): AniKageScrape? {
        BCCache.getBundle(key)?.let {
          return AniKageScrape(it.mirrors, it.subSources)
        }
        inFlight[key]?.let {
            BLog.v("prefetch: join in-flight $key")
            return it.await()
        }
        val def = CompletableDeferred<AniKageScrape?>()
        val prior = inFlight.putIfAbsent(key, def)
        if (prior != null) {
            BLog.v("prefetch: join race $key")
            return prior.await()
        }
        return try {
            val r = work()
            if (r.mirrors.isNotEmpty()) {
                BCCache.putBundle(key, MirrorBundle(r.mirrors, r.subs))
            }
            def.complete(r)
            r
        } catch (e: Throwable) {
            def.completeExceptionally(e)
            null
        } finally {
            inFlight.remove(key)
        }
    }
}

// ── cache keys ──
fun StreamQuery.cacheKey(): String =
    "scrape:${title.lowercase()}:${year}:${type}:${season}:${episode}"

fun StreamQuery.animeKey(): String =
    "anime:${title.lowercase()}:${year}:${type}"
