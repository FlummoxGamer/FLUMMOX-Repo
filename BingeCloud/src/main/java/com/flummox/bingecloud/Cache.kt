package com.flummox.bingecloud

import android.util.LruCache

object BCCache {

    private data class Entry(val ts: Long, val body: String)
    private data class MirrorEntry(val ts: Long, val mirrors: List<ScrapedMirror>)

    private val map = LruCache<String, Entry>(512)
    private val mirrorMap = LruCache<String, MirrorEntry>(16)
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
        synchronized(lock) {
            map.put(key, Entry(System.currentTimeMillis(), body))
        }
    }

    fun getMirrors(key: String, ttlMs: Long = 30 * 60 * 1000L): List<ScrapedMirror>? = synchronized(lock) {
        val e = mirrorMap.get(key) ?: return null
        if (System.currentTimeMillis() - e.ts > ttlMs) {
            mirrorMap.remove(key)
            return null
        }
        e.mirrors
    }

    fun putMirrors(key: String, mirrors: List<ScrapedMirror>) {
        synchronized(lock) {
            mirrorMap.put(key, MirrorEntry(System.currentTimeMillis(), mirrors))
        }
    }

    fun clear() {
        synchronized(lock) {
            map.evictAll()
            mirrorMap.evictAll()
        }
    }
}
