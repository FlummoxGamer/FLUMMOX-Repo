package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder

object ReanimeApi {
    const val BASE = "https://reanime.to"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private const val TTL_SEARCH = 30L * 60 * 1000
    private const val TTL_SERVERS = 6L * 60 * 60 * 1000

    private fun baseHeaders() = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$BASE/"
    )

    data class Hit(
        val slug: String,
        val anilistId: Int,
        val title: String,
        val altTitles: List<String>,
        val year: Int?,
        val poster: String?,
        val format: String?,
        val episodes: Int,
        val subbed: Int,
        val dubbed: Int
    )

    data class Srv(
        val name: String,
        val url: String,
        val dataType: String
    )

    suspend fun search(query: String): List<Hit> {
        val ck = "reanime:s:${query.lowercase()}"
        BCCache.get(ck, TTL_SEARCH)?.let { cached ->
            return try { parseSearch(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/api/v1/search?limit=25&q=${URLEncoder.encode(query, "UTF-8")}"
        return try {
            val res = app.get(url, headers = baseHeaders())
            if (res.code !in 200..299) return emptyList()
            BCCache.put(ck, res.text)
            parseSearch(JSONObject(res.text))
        } catch (e: Exception) {
            BLog.e("reanime search: ${e.message}")
            emptyList()
        }
    }

    private fun parseSearch(root: JSONObject): List<Hit> {
        val arr = root.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<Hit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slug = o.optString("anime_id").takeIf { it.isNotBlank() } ?: continue
            val alId = o.optInt("anilist_id", 0).takeIf { it > 0 } ?: continue
            val t = o.optJSONObject("title")
            val en = t?.optString("english")?.takeIf { it.isNotBlank() }
            val ro = t?.optString("romaji")?.takeIf { it.isNotBlank() }
            val na = t?.optString("native")?.takeIf { it.isNotBlank() }
            val primary = en ?: ro ?: na ?: continue
            val alt = listOfNotNull(en, ro, na).distinct()
            val cover = o.optJSONObject("cover_image")
                ?.optString("large")?.takeIf { it.startsWith("http") }
            out.add(Hit(
                slug = slug,
                anilistId = alId,
                title = primary,
                altTitles = alt,
                year = o.optInt("season_year", 0).takeIf { it > 0 },
                poster = cover,
                format = o.optString("format").takeIf { it.isNotBlank() },
                episodes = o.optInt("episodes", 0),
                subbed = o.optInt("subbed", 0),
                dubbed = o.optInt("dubbed", 0)
            ))
        }
        return out
    }

    suspend fun servers(anilistId: Int, ep: Int): List<Srv> {
        val ck = "reanime:srv:$anilistId:$ep"
        BCCache.get(ck, TTL_SERVERS)?.let { cached ->
            return try { parseServers(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        return try {
            val url = "$BASE/api/flix/$anilistId/$ep"
            val res = app.get(url, headers = baseHeaders())
            if (res.code !in 200..299) return emptyList()
            BCCache.put(ck, res.text)
            parseServers(JSONObject(res.text))
        } catch (e: Exception) {
            BLog.e("reanime servers: ${e.message}")
            emptyList()
        }
    }

    private fun parseServers(root: JSONObject): List<Srv> {
        if (!root.optBoolean("success", false)) return emptyList()
        val arr = root.optJSONArray("servers") ?: return emptyList()
        val out = mutableListOf<Srv>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("serverName").takeIf { it.isNotBlank() } ?: continue
            val url = o.optString("dataLink").takeIf { it.startsWith("http") } ?: continue
            val dt = o.optString("dataType").takeIf { it.isNotBlank() } ?: "sub"
            out.add(Srv(name, url, dt))
        }
        return out
    }

    fun pickBest(hits: List<Hit>, query: String, year: Int?): Hit? {
        if (hits.isEmpty()) return null

        fun norm(s: String) = s.lowercase()
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

        val qn = norm(query)

        val exact = hits.filter { h -> h.altTitles.any { norm(it) == qn } }
        if (exact.isNotEmpty()) {
            return exact.firstOrNull { year != null && it.year == year } ?: exact.first()
        }

        val contain = hits.filter { h ->
            h.altTitles.any { t ->
                val n = norm(t)
                n.isNotBlank() && (n.contains(qn) || qn.contains(n))
            }
        }
        if (contain.isNotEmpty()) {
            return contain.firstOrNull { year != null && it.year == year } ?: contain.first()
        }

        val qw = qn.split(" ").filter { it.isNotBlank() }.toSet()
        if (qw.isEmpty()) return null
        return hits.mapNotNull { h ->
            val best = h.altTitles.maxOfOrNull { t ->
                val tw = norm(t).split(" ").filter { it.isNotBlank() }.toSet()
                if (tw.isEmpty()) 0f else qw.intersect(tw).size.toFloat() / qw.size
            } ?: 0f
            if (best >= 0.7f) h to best else null
        }.maxByOrNull { it.second }?.first
    }
}
