package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject

object AniMapperApi {
    private const val BASE = "https://api.animapper.net/api/v1"
    private const val ROW_TTL = 6 * 60 * 60 * 1000L

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "BingeAnime/1.0"
    )

    // Real trending. sortBy=UPDATED_AT returns shows with recent activity
    // (new episodes, metadata pings). Combined with status=RELEASING it
    // gives what users expect from a trending row: currently airing,
    // high-relevance right now. No format filter — movies, OVAs, and
    // specials are all trending-eligible.
    suspend fun trending(limit: Int = 30): List<AniListApi.Entry> {
        val ck = "animapper:trending:$limit"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            return try { parseResponse(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/search?sortBy=UPDATED_AT&sortOrder=DESC" +
            "&status=RELEASING&page=1&limit=$limit"
        return try {
            val res = app.get(url, headers = headers())
            BLog.v("animapper trending HTTP ${res.code} len=${res.text.length}")
            if (res.code !in 200..299) return emptyList()
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            val parsed = parseResponse(root).take(limit)
            BLog.v("animapper trending → ${parsed.size} recently-active entries")
            parsed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("animapper trending failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun donghua(limit: Int = 30, yearFloor: Int? = null): List<AniListApi.Entry> {
        val ck = "animapper:donghua:$limit:${yearFloor ?: 0}"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            return try { parseResponse(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = buildString {
            append("$BASE/search?countryOfOrigin=CN&sortBy=POPULARITY&sortOrder=DESC")
            if (yearFloor != null) append("&startYear=$yearFloor")
            append("&page=1&limit=$limit")
        }
        return try {
            val res = app.get(url, headers = headers())
            BLog.v("animapper donghua HTTP ${res.code} len=${res.text.length}")
            if (res.code == 429) { BLog.e("animapper donghua 429"); return emptyList() }
            if (res.code !in 200..299) return emptyList()
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseResponse(root)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("animapper donghua failed: ${e.message}")
            emptyList()
        }
    }

    // Search by exact title. Used by ShikimoriApi as a cover-image
    // fallback for entries whose Shikimori poster is null.
    suspend fun searchByTitle(title: String): AniListApi.Entry? {
        if (title.isBlank()) return null
        val ck = "animapper:title:$title"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            return try { parseEntry(JSONObject(cached)) } catch (_: Exception) { null }
        }
        val encoded = java.net.URLEncoder.encode(title, "UTF-8")
        val url = "$BASE/search?title=$encoded&mediaType=ANIME&page=1&limit=1"
        return try {
            val res = app.get(url, headers = headers())
            if (res.code !in 200..299) return null
            val root = JSONObject(res.text)
            val arr = root.optJSONArray("results")
                ?: root.optJSONArray("data")
                ?: root.optJSONObject("data")?.optJSONArray("results")
                ?: return null
            if (arr.length() == 0) return null
            val first = arr.optJSONObject(0)?.optJSONObject("result") ?: arr.optJSONObject(0)
            val entry = parseEntry(first) ?: return null
            BCCache.put(ck, first.toString())
            entry
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun detail(id: Int): AniListApi.Entry? {
        val ck = "animapper:detail:$id"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            return try { parseEntry(JSONObject(cached)) } catch (_: Exception) { null }
        }
        return try {
            val res = app.get("$BASE/metadata?id=$id", headers = headers())
            if (res.code !in 200..299) return null
            val root = JSONObject(res.text)
            val obj = root.optJSONObject("result") ?: root.optJSONObject("data") ?: root
            BCCache.put(ck, obj.toString())
            parseEntry(obj)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("animapper detail $id failed: ${e.message}")
            null
        }
    }

    private fun parseResponse(root: JSONObject): List<AniListApi.Entry> {
        val arr: JSONArray? = root.optJSONArray("results")
            ?: root.optJSONArray("data")
            ?: root.optJSONObject("data")?.optJSONArray("results")
            ?: root.optJSONObject("data")?.optJSONArray("media")
        if (arr == null) return emptyList()
        val out = mutableListOf<AniListApi.Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)?.optJSONObject("result") ?: arr.optJSONObject(i)
            parseEntry(o)?.let { out.add(it) }
        }
        return out
    }

    private fun parseEntry(o: JSONObject?): AniListApi.Entry? {
        if (o == null) return null
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val titles = o.optJSONObject("titles")
        val romaji = titles?.optString("ja-ro")?.takeIf { it.isNotBlank() && it != "null" }
            ?: titles?.optString("main")?.takeIf { it.isNotBlank() && it != "null" }
            ?: titles?.optString("user-preferred")?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        val english = titles?.optString("en")?.takeIf { it.isNotBlank() && it != "null" }
        val native = titles?.optString("ja")?.takeIf { it.isNotBlank() && it != "null" }

        val images = o.optJSONObject("images")
        val cover = images?.optString("coverXl")?.takeIf { it.isNotBlank() && it != "null" }
            ?: images?.optString("coverLg")?.takeIf { it.isNotBlank() && it != "null" }
            ?: images?.optString("coverMd")?.takeIf { it.isNotBlank() && it != "null" }
        val banner = images?.optString("bannerUrl")?.takeIf { it.isNotBlank() && it != "null" }

        val format = o.optString("format").takeIf { it.isNotBlank() && it != "null" }
        val status = o.optString("status").takeIf { it.isNotBlank() && it != "null" }

        val totalUnits = o.optInt("totalUnits", 0).takeIf { it > 0 }
        val episodes = totalUnits ?: o.optInt("episodes", 0).takeIf { it > 0 }

        val startDate = o.optString("startDate").takeIf { it.isNotBlank() && it != "null" }
        val year = startDate?.take(4)?.toIntOrNull()

        val score = o.optDouble("score", 0.0).takeIf { it > 0 }
            ?: o.optInt("averageScore", 0).takeIf { it > 0 }?.let { it / 10.0 }
        val averageScore = score?.let { (it * 10).toInt() }

        val description = o.optJSONObject("descriptions")
            ?.optString("en")?.takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("description").takeIf { it.isNotBlank() && it != "null" }

        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val v = arr.opt(i)
                when (v) {
                    is String -> v.takeIf { it.isNotBlank() }
                    is JSONObject -> v.optString("name").takeIf { it.isNotBlank() }
                    else -> null
                }
            }
        }?.takeIf { it.isNotEmpty() }

        return AniListApi.Entry(
            id = id,
            idMal = null,
            title = AniListApi.Title(romaji = romaji, english = english, native = native),
            format = format,
            episodes = episodes,
            seasonYear = year,
            startDate = startDate,
            description = description,
            coverImage = cover,
            bannerUrl = banner,
            averageScore = averageScore,
            status = status,
            genres = genres,
            country = o.optString("countryOfOrigin").takeIf { it.isNotBlank() && it != "null" },
            relations = emptyList(),
            source = "animapper"
        )
    }
}
