package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject

// AniMapper — aggregated anime metadata + streaming API. Free, no key.
// Rate limit: 60 req/min per IP.
// Response shape (verified from live data):
//   {
//     "id": 185727,
//     "mediaType": "ANIME",
//     "titles": {
//       "ja-ro": "...", "en": "Spy x Sect", "main": "...",
//       "user-preferred": "...", "alt-anilist-0": "..."
//     },
//     "images": { "coverXl": "...", "coverLg": "...", "coverMd": "..." }
//   }
// IDs are AniList IDs.
object AniMapperApi {
    private const val BASE = "https://api.animapper.net/api/v1"
    private const val ROW_TTL = 6 * 60 * 60 * 1000L

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "BingeAnime/1.0"
    )

    suspend fun trending(limit: Int = 30): List<AniListApi.Entry> {
    val ck = "animapper:trending:$limit"
    BCCache.get(ck, ROW_TTL)?.let { cached ->
        return try { parseResponse(JSONObject(cached)) } catch (_: Exception) { emptyList() }
    }
    val url = "$BASE/search?sortBy=POPULARITY&sortOrder=DESC&page=1&limit=$limit"
    return try {
        val res = app.get(url, headers = headers())
        if (res.code !in 200..299) return emptyList()
        val root = JSONObject(res.text)
        BCCache.put(ck, root.toString())
        parseResponse(root)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) {
        BLog.e("animapper trending failed: ${e.message}")
        emptyList()
    }
}

suspend fun donghua(limit: Int = 30): List<AniListApi.Entry> {
        val ck = "animapper:donghua:$limit"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            BLog.v("animapper donghua cache hit")
            return try { parseResponse(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/search?countryOfOrigin=CN&sortBy=POPULARITY&sortOrder=DESC&page=1&limit=$limit"
        return try {
            val res = app.get(url, headers = headers())
            BLog.v("animapper donghua HTTP ${res.code} len=${res.text.length}")
            if (res.code == 429) {
                BLog.e("animapper donghua 429")
                return emptyList()
            }
            if (res.code !in 200..299) {
                BLog.e("animapper donghua HTTP ${res.code}: ${res.text.take(300)}")
                return emptyList()
            }
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            val parsed = parseResponse(root)
            BLog.v("animapper donghua parsed ${parsed.size} entries")
            parsed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("animapper donghua failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun detail(id: Int): AniListApi.Entry? {
        val ck = "animapper:detail:$id"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            return try { parseEntry(JSONObject(cached)) } catch (_: Exception) { null }
        }
        return try {
            val res = app.get("$BASE/metadata?id=$id", headers = headers())
            BLog.v("animapper detail $id HTTP ${res.code} len=${res.text.length}")
            if (res.code !in 200..299) return null
            val root = JSONObject(res.text)
            val obj = root.optJSONObject("data") ?: root
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
        if (arr == null) {
            BLog.e("animapper parse — no results array")
            return emptyList()
        }
        val out = mutableListOf<AniListApi.Entry>()
        for (i in 0 until arr.length()) parseEntry(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }

    private fun parseEntry(o: JSONObject?): AniListApi.Entry? {
        if (o == null) return null

        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null

        // titles object — prefer en, fall back to main / user-preferred / ja-ro.
        val titles = o.optJSONObject("titles")
        val romaji = titles?.optString("ja-ro")?.takeIf { it.isNotBlank() && it != "null" }
            ?: titles?.optString("main")?.takeIf { it.isNotBlank() && it != "null" }
            ?: titles?.optString("user-preferred")?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        val english = titles?.optString("en")?.takeIf { it.isNotBlank() && it != "null" }
        val native = titles?.optString("ja")?.takeIf { it.isNotBlank() && it != "null" }

        // images object — coverXl > coverLg > coverMd
        val images = o.optJSONObject("images")
        val cover = images?.optString("coverXl")?.takeIf { it.isNotBlank() && it != "null" }
            ?: images?.optString("coverLg")?.takeIf { it.isNotBlank() && it != "null" }
            ?: images?.optString("coverMd")?.takeIf { it.isNotBlank() && it != "null" }

        val format = o.optString("format").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("type").takeIf { it.isNotBlank() && it != "null" }

        val episodes = o.optInt("episodes", 0).takeIf { it > 0 }
            ?: o.optInt("numEpisodes", 0).takeIf { it > 0 }

        val year = o.optInt("seasonYear", 0).takeIf { it > 0 }
            ?: o.optString("startDate").take(4).toIntOrNull()

        val avgScore = o.optInt("averageScore", 0).takeIf { it > 0 }
            ?: o.optDouble("score", 0.0).takeIf { it > 0 }?.let { (it * 10).toInt() }

        val status = o.optString("status").takeIf { it.isNotBlank() && it != "null" }
        val description = o.optString("description").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("synopsis").takeIf { it.isNotBlank() && it != "null" }

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
            startDate = null,
            description = description,
            coverImage = cover,
            averageScore = avgScore,
            status = status,
            genres = genres,
            country = null,
            relations = emptyList(),
            source = "animapper"
        )
    }
}
