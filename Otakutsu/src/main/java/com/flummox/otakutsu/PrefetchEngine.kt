package com.flummox.otakutsu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// Otakutsu prefetch engine.
// Improvements over BingeCloud:
//   1. In-flight dedup — concurrent callers share one job (waits through debounce)
//   2. Session tracking — switching anime cancels pending jobs for old anime
//   3. Batch warm E1+E2 on series open
//   4. Binge-aware warming — sequential 1,2,3 history → warm 4,5
object PrefetchEngine {

    private const val DEBOUNCE_MS = 800L
    private const val HOME_GRACE_MS = 5000L
    private const val HISTORY_SIZE = 5

    private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<PrefetchCache?>>()
    private val lock = Any()
    private val watchHistory = mutableMapOf<String, MutableList<Int>>()
    private var currentAnimeId: String? = null
    private val sessionJobs = mutableListOf<Job>()

    @Volatile private var lastHomeRenderMs: Long = 0L

    fun markHomeRender() {
        lastHomeRenderMs = System.currentTimeMillis()
    }

    private fun isFromHome(): Boolean {
        val t = lastHomeRenderMs
        return t > 0 && System.currentTimeMillis() - t in 0 until HOME_GRACE_MS
    }

    fun beginSession(animeId: String) {
        synchronized(lock) {
            if (currentAnimeId != animeId) {
                sessionJobs.forEach { runCatching { it.cancel() } }
                sessionJobs.clear()
                currentAnimeId = animeId
                OLog.v("prefetch: session → $animeId")
            }
        }
    }

    fun recordPlay(animeId: String, ep: Int) {
        synchronized(lock) {
            val l = watchHistory.getOrPut(animeId) { mutableListOf() }
            if (l.lastOrNull() != ep) {
                l.add(ep)
                while (l.size > HISTORY_SIZE) l.removeAt(0)
            }
            OLog.v("prefetch: play $animeId E$ep hist=${l.joinToString(",")}")
        }
    }

    // Warm on series open. Batch E1+E2.
    fun warmLoad(animeId: String, fetch: suspend (String, Int) -> PrefetchCache?) {
        warm(animeId, listOf(1, 2), fetch)
    }

    // Warm after episode plays. Binge-aware next picks.
    fun warmAfterPlay(
        animeId: String,
        ep: Int,
        totalEps: Int,
        fetch: suspend (String, Int) -> PrefetchCache?
    ) {
        val plan = plan(animeId, ep, totalEps)
        if (plan.isEmpty()) return
        warm(animeId, plan, fetch)
    }

    private fun plan(animeId: String, ep: Int, totalEps: Int): List<Int> {
        val hist = synchronized(lock) { watchHistory[animeId]?.toList() ?: emptyList() }
        val sequential = hist.size >= 3 && hist.takeLast(3).let {
            it[1] == it[0] + 1 && it[2] == it[1] + 1
        }
        val candidates = if (sequential) {
            listOf(ep + 1, ep + 2)
        } else {
            listOf(ep + 1)
        }
        return candidates.filter { it in 1..totalEps }.distinct()
    }

    private fun warm(
        animeId: String,
        eps: List<Int>,
        fetch: suspend (String, Int) -> PrefetchCache?
    ) {
        if (!OSettings.isPrefetchEnabled()) return
        if (isFromHome()) {
            OLog.v("prefetch: skipped (home grace)")
            return
        }
        for (ep in eps) {
            val key = "$animeId:$ep"
            if (OCache.hasPrefetch(key)) {
                OLog.v("prefetch: $key cached")
                continue
            }
            val job = SCOPE.launch {
                delay(DEBOUNCE_MS)
                obtain(key) { fetch(animeId, ep) }
            }
            synchronized(lock) { sessionJobs.add(job) }
        }
    }

    // Single entry point. Prefetch and loadLinks both call this.
    // Registers in inFlight BEFORE any network — second caller joins.
    suspend fun obtain(key: String, work: suspend () -> PrefetchCache?): PrefetchCache? {
        OCache.getPrefetch(key)?.let { return it }
        inFlight[key]?.let {
            OLog.v("prefetch: join in-flight $key")
            return it.await()
        }
        val def = CompletableDeferred<PrefetchCache?>()
        val prior = inFlight.putIfAbsent(key, def)
        if (prior != null) {
            OLog.v("prefetch: join race $key")
            return prior.await()
        }
        return try {
            val r = work()
            if (r != null) OCache.putPrefetch(key, r)
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
