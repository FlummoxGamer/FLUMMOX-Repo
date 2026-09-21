package com.flummox.bingecore

import android.content.Context
import com.flummox.bingecloud.BCLog
import com.flummox.bingecloud.Settings
import com.flummox.bingecloud.cloudflareGet
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

object CloudflareShield {

    // ── MLSBD REVIVE ── uncomment to re-enable the MLSBD pill in Settings
    // val GROUPS: Map<String, List<String>> = mapOf(
    //     "MLSBD" to listOf("mlsbd.co")
    // )
    // Empty while MLSBD is disabled. Add entries here for any new
    // CF-protected source: "Name" to listOf("domain.com")
    val GROUPS: Map<String, List<String>> = emptyMap()

    private const val K_CF_EXPIRY_PREFIX = "bingecloud_cf_expiry_"

    fun getExpiry(domain: String): Long =
        getKey<Long>(K_CF_EXPIRY_PREFIX + domain) ?: 0L

    fun clearCookieAndExpiry(domain: String) {
        Settings.clearCookieForDomain(domain)
        setKey(K_CF_EXPIRY_PREFIX + domain, 0L)
    }

    enum class State { EMPTY, PARTIAL, PROTECTED, WORKING }

    data class GroupStatus(
        val state: State,
        val freshCount: Int,
        val totalCount: Int,
        val earliestExpiryMs: Long
    )

    fun statusOf(sourceName: String): GroupStatus {
        val domains = GROUPS[sourceName]
            ?: return GroupStatus(State.EMPTY, 0, 0, 0L)
        val now = System.currentTimeMillis()
        var fresh = 0
        var earliest = Long.MAX_VALUE
        for (d in domains) {
            val cookie = Settings.getCookieForDomain(d)
            val exp = getExpiry(d)
            val valid = !cookie.isNullOrBlank() && (exp == 0L || exp > now)
            if (valid) {
                fresh++
                if (exp in 1 until earliest) earliest = exp
            }
        }
        val state = when {
            fresh == 0 -> State.EMPTY
            fresh == domains.size -> State.PROTECTED
            else -> State.PARTIAL
        }
        return GroupStatus(
            state = state,
            freshCount = fresh,
            totalCount = domains.size,
            earliestExpiryMs = if (earliest == Long.MAX_VALUE) 0L else earliest
        )
    }

    suspend fun bypassGroup(
        ctx: Context,
        sourceName: String,
        onProgress: (current: Int, total: Int, domain: String) -> Unit
    ): Int {
        val domains = GROUPS[sourceName] ?: return 0
        var ok = 0
        for ((idx, d) in domains.withIndex()) {
            onProgress(idx + 1, domains.size, d)
            val url = "https://$d"
            try {
                val html = cloudflareGet(url, referer = url)
                if (html != null) {
                    ok++
                    val cookie = try {
                        android.webkit.CookieManager.getInstance().getCookie(url)
                    } catch (_: Exception) { null }
                    if (!cookie.isNullOrBlank()) {
                        Settings.saveCookieForDomain(d, cookie)
                        setKey(
                            K_CF_EXPIRY_PREFIX + d,
                            System.currentTimeMillis() + 24L * 60 * 60 * 1000
                        )
                    }
                    BCLog.d("[CF Shield] $d ok")
                } else {
                    BCLog.d("[CF Shield] $d returned null")
                }
            } catch (e: Exception) {
                BCLog.d("[CF Shield] $d failed: ${e.message}")
            }
        }
        return ok
    }
}
