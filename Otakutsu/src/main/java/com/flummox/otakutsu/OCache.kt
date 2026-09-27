package com.flummox.otakutsu

import android.util.LruCache

data class OtakutsuSource(
    val label: String,
    val server: String,
    val subType: String,
    val url: String
)

data class PrefetchCache(
    val sources: List<OtakutsuSource>,
    val cookieHeader: String
)

object OCache {
    private data class Entry(val ts: Long, val cache: PrefetchCache)

    private val map = LruCache<String, Entry>(8)
    private val lock = Any()

    fun getPrefetch(key: String, ttlMs: Long = 30 * 60 * 1000L): PrefetchCache? = synchronized(lock) {
        val e = map.get(key) ?: return null
        if (System.currentTimeMillis() - e.ts > ttlMs) {
            map.remove(key)
            return null
        }
        e.cache
    }

    fun putPrefetch(key: String, cache: PrefetchCache) {
        synchronized(lock) { map.put(key, Entry(System.currentTimeMillis(), cache)) }
    }

    fun hasPrefetch(key: String): Boolean = synchronized(lock) { map.get(key) != null }

    fun clear() { synchronized(lock) { map.evictAll() } }

    fun size(): Int = synchronized(lock) { map.size() }
}
