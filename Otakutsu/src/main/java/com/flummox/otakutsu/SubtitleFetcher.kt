package com.flummox.otakutsu

import android.util.LruCache
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

// Orchestrates AniKoto + AniZone subtitle fetch in parallel.
// Caches per (title, season, episode) for 7 days in memory.
object SubtitleFetcher {

    data class Sub(val label: String, val url: String)

    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    private data class Entry(val ts: Long, val subs: List<Sub>)
    private val cache = LruCache<String, Entry>(64)
    private val lock = Any()

    private fun key(title: String, season: Int, ep: Int) = "$title|$season|$ep"

    fun clear() {
        synchronized(lock) { cache.evictAll() }
    }

    suspend fun fetch(title: String, season: Int, ep: Int): List<Sub> = coroutineScope {
        val k = key(title, season, ep)
        synchronized(lock) {
            cache.get(k)?.let { e ->
                if (System.currentTimeMillis() - e.ts <= TTL_MS) return@coroutineScope e.subs
            }
        }

        OLog.v("subs: fetch '$title' S$season E$ep")

        val akDef = async {
            try {
                val raw = withTimeoutOrNull(6000) { AniKotoSubs.fetch(title, ep) }
                raw?.map { Sub("AniKoto · ${it.label}", it.url) } ?: emptyList()
            } catch (e: Exception) {
                OLog.v("subs: AniKoto err ${e.message}")
                emptyList()
            }
        }
        val azDef = async {
            try {
                val raw = withTimeoutOrNull(6000) { AniZoneSubs.fetch(title, ep) }
                raw?.map { Sub("AniZone · ${it.label}", it.url) } ?: emptyList()
            } catch (e: Exception) {
                OLog.v("subs: AniZone err ${e.message}")
                emptyList()
            }
        }

        val ak = akDef.await()
        val az = azDef.await()
        OLog.v("subs: AniKoto=${ak.size} AniZone=${az.size}")

        val merged = (ak + az).distinctBy { it.url }
        if (merged.isNotEmpty()) {
            synchronized(lock) {
                cache.put(k, Entry(System.currentTimeMillis(), merged))
            }
        }
        merged
    }
}
