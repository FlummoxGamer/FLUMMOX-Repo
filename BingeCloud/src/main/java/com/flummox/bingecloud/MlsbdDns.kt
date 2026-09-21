package com.flummox.bingecloud

// ═══════════════════════════════════════════════════════════════
// ── MLSBD DNS resolver (DISABLED — utility, no active callers) ──
//
// Custom okhttp3.Dns that detects known-poisoned system DNS
// responses for mlsbd.co and falls back to Google DoH, then to
// hardcoded Cloudflare edges. Currently unreferenced.
//
// To reuse for a different host:
//   1. Replace UNREACHABLE_1 / UNREACHABLE_2 with the poisoned IPs
//      observed on your network (test: nslookup <host> vs a DoH
//      resolver).
//   2. Replace VERIFIED_EDGES with real Cloudflare anycast
//      addresses for that host.
//   3. Change `hostname.contains("mlsbd.co")` to match the new host.
//   4. Attach via OkHttpClient.Builder().dns(MlsbdDns).
// ═══════════════════════════════════════════════════════════════

import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

// ═══════════════════════════════════════════════════════════════
// ── MLSBD DNS resolver ──
// Some ISPs return unreachable addresses for mlsbd.co. This
// resolver validates the system response, and if it's flagged
// as unreachable, resolves the host through a DNS-over-HTTPS
// lookup instead. Falls back to verified Cloudflare edge
// addresses if DoH also fails.
// ═══════════════════════════════════════════════════════════════

object MlsbdDns : Dns {

    // Addresses observed to be non-responsive for mlsbd.co on
    // certain networks.
    private const val UNREACHABLE_1 = "13.127.247.216"
    private const val UNREACHABLE_2 = "202.56.230.30"

    // Verified Cloudflare edge addresses for mlsbd.co.
    private val VERIFIED_EDGES = listOf(
        "104.26.14.75",
        "104.26.15.75",
        "172.67.72.192"
    )

    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        cache[hostname]?.let { return it }

        val system = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (_: Exception) {
            emptyList()
        }

        val unusable = system.any {
            it.hostAddress == UNREACHABLE_1 || it.hostAddress == UNREACHABLE_2
        }

        if (system.isNotEmpty() && !unusable) {
            cache[hostname] = system
            return system
        }

        if (unusable) {
            BCLog.d("[MLSBD DNS] unusable address for $hostname — trying DoH")
        }

        val doh = dohLookup(hostname)
        if (doh.isNotEmpty()) {
            cache[hostname] = doh
            return doh
        }

        if (hostname.contains("mlsbd.co", ignoreCase = true)) {
            val hard = VERIFIED_EDGES.mapNotNull {
                try { InetAddress.getByName(it) } catch (_: Exception) { null }
            }
            if (hard.isNotEmpty()) {
                cache[hostname] = hard
                return hard
            }
        }

        if (system.isNotEmpty()) {
            cache[hostname] = system
            return system
        }
        throw java.net.UnknownHostException(hostname)
    }

    private fun dohLookup(host: String): List<InetAddress> {
        return try {
            val url = java.net.URL("https://dns.google/resolve?name=$host&type=A")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(body)
            val answers = root.optJSONArray("Answer") ?: return emptyList()
            val out = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val a = answers.optJSONObject(i) ?: continue
                if (a.optInt("type") != 1) continue
                val ip = a.optString("data").trim()
                if (ip.isEmpty()) continue
                try { out.add(InetAddress.getByName(ip)) } catch (_: Exception) {}
            }
            BCLog.d("[MLSBD DNS] $host → ${out.size} via DoH")
            out
        } catch (e: Exception) {
            BCLog.d("[MLSBD DNS] DoH failed for $host: ${e.message}")
            emptyList()
        }
    }
}
