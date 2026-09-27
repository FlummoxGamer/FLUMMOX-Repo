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
    private const val TTL_VALID = 30 * 60 * 1000L
    private const val TTL_EMPTY = 60 * 1000L

    private data class Entry(val ts: Long, val cache: PrefetchCache)

    private val map = LruCache<String, Entry>(16)
    private val lock = Any()

    fun getPrefetch(key: String): PrefetchCache? = synchronized(lock) {
        val e = map.get(key) ?: return null
        val ttl = if (e.cache.sources.isEmpty()) TTL_EMPTY else TTL_VALID
        if (System.currentTimeMillis() - e.ts > ttl) {
            map.remove(key)
            return null
        }
        e.cache
    }

    fun putPrefetch(key: String, cache: PrefetchCache) {
        synchronized(lock) { map.put(key, Entry(System.currentTimeMillis(), cache)) }
    }

    fun hasPrefetch(key: String): Boolean = synchronized(lock) {
        val e = map.get(key) ?: return false
        val ttl = if (e.cache.sources.isEmpty()) TTL_EMPTY else TTL_VALID
        System.currentTimeMillis() - e.ts <= ttl
    }

    fun clear() { synchronized(lock) { map.evictAll() } }
    fun size(): Int = synchronized(lock) { map.size() }
}
