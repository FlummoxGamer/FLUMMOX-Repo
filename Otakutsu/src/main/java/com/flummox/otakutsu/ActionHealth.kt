package com.flummox.otakutsu

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

// Tracks Otakutsu action-ID health.
// Healthy = last fetchChain parsed at least 1 source.
// Stale = last fetchChain saw the stale-detection heuristic (RSC looks
// like a page render, no "sources" key).
// Recovery: next successful fetch flips back to healthy.
object ActionHealth {

    private const val K_LAST_OK = "otakutsu_action_last_ok"
    private const val K_LAST_STALE = "otakutsu_action_last_stale"

    fun markOk() {
        try { setKey(K_LAST_OK, System.currentTimeMillis()) } catch (_: Exception) {}
    }

    fun markStale() {
        try { setKey(K_LAST_STALE, System.currentTimeMillis()) } catch (_: Exception) {}
    }

    fun isHealthy(): Boolean {
        return try {
            val ok = getKey<Long>(K_LAST_OK) ?: 0L
            val stale = getKey<Long>(K_LAST_STALE) ?: 0L
            // Healthy if last OK is more recent than last stale,
            // or if we've never recorded either (fresh install).
            if (ok == 0L && stale == 0L) true else ok >= stale
        } catch (_: Exception) { true }
    }
}
