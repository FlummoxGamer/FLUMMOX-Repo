package com.flummox.bingeanime

import android.util.LruCache

object BCCache {

    private data class Entry(val ts: Long, val body: String)

    private val map = LruCache<String, Entry>(512)
    private val lock = Any()

    fun get(key: String, ttlMs: Long = 5 * 60 * 1000L): String? = synchronized(lock) {
        val e = map.get(key) ?: return null
        if (System.currentTimeMillis() - e.ts > ttlMs) {
            map.remove(key)
            return null
        }
        e.body
    }

    fun put(key: String, body: String) {
        synchronized(lock) { map.put(key, Entry(System.currentTimeMillis(), body)) }
    }

    fun clear() {
        synchronized(lock) { map.evictAll() }
    }
}
