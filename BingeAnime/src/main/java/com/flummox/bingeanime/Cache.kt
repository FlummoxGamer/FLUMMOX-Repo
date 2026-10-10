package com.flummox.bingeanime

import android.util.LruCache

data class MirrorBundle(
    val mirrors: List<ScrapedMirror>,
    val subSources: List<Pair<String, String>>   // (label, remoteProxyUrl)
)

object BCCache {

    private data class Entry(val ts: Long, val body: String)
    private data class BundleEntry(val ts: Long, val bundle: MirrorBundle)

    private val map = LruCache<String, Entry>(512)
    private val bundleMap = LruCache<String, BundleEntry>(16)
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

    // ── mirror bundle: 30 min, 16 entries ──
    fun getBundle(key: String, ttlMs: Long = 30 * 60 * 1000L): MirrorBundle? =
        synchronized(lock) {
            val e = bundleMap.get(key) ?: return null
            if (System.currentTimeMillis() - e.ts > ttlMs) {
                bundleMap.remove(key)
                return null
            }
            e.bundle
        }

    fun putBundle(key: String, bundle: MirrorBundle) {
        synchronized(lock) {
            bundleMap.put(key, BundleEntry(System.currentTimeMillis(), bundle))
        }
    }

    // Thin wrappers — keep existing PrefetchEngine callers working unchanged
    fun getMirrors(key: String, ttlMs: Long = 30 * 60 * 1000L): List<ScrapedMirror>? =
        getBundle(key, ttlMs)?.mirrors

    fun putMirrors(key: String, mirrors: List<ScrapedMirror>) {
        val existing = getBundle(key)
        putBundle(key, MirrorBundle(mirrors, existing?.subSources ?: emptyList()))
    }

    fun clear() {
        synchronized(lock) {
            map.evictAll()
            bundleMap.evictAll()
        }
    }
}
