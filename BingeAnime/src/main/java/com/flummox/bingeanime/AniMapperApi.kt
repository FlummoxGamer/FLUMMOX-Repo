package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject

// AniMapper — aggregated anime metadata + streaming API. Free, no key.
// Rate limit: 60 req/min per IP.
//
// Two response shapes:
//   Search:  {results: [...]} or {data: [...]}
//   Detail:  {success: true, result: {id, titles, images, totalUnits, ...}}
//
// IDs are AniList IDs. Detail responses often include images.bannerUrl
// (landscape artwork) — the only landscape source in our stack.
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
    // Real trending = currently airing, high popularity, sorted by
    // recent activity. Filter to RELEASING + last 12 months of
    // premieres. No movies. Falls through to just-RELEASING if the
    // 12-month window is too thin.
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    return try {
        var url = "$BASE/search?sortBy=POPULARITY&sortOrder=DESC" +
            "&status=RELEASING&startYear=${year - 1}" +
            "&format=TV,ONA,TV_SHORT&page=1&limit=$limit"
        var res = app.get(url, headers = headers())
        if (res.code !in 200..299) return emptyList()
        var root = JSONObject(res.text)
        var parsed = parseResponse(root).filter { it.format != "MOVIE" }

        if (parsed.size < limit / 2) {
            BLog.d("animapper trending thin (${parsed.size}), dropping year filter")
            url = "$BASE/search?sortBy=POPULARITY&sortOrder=DESC" +
                "&status=RELEASING&format=TV,ONA,TV_SHORT&page=1&limit=$limit"
            res = app.get(url, headers = headers())
            if (res.code in 200..299) {
                root = JSONObject(res.text)
                parsed = parseResponse(root).filter { it.format != "MOVIE" }
            }
        }
        val filtered = parsed.take(limit)
        BCCache.put(ck, root.toString())
        BLog.v("animapper trending → ${filtered.size} currently-airing entries")
        filtered
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) {
        BLog.e("animapper trending failed: ${e.message}")
        emptyList()
    }
    }
        // Client-side safety net — drop any MOVIE entry the API ignored.
        val filtered = parsed.filter { it.format != "MOVIE" }.take(limit)
        BCCache.put(ck, root.toString())
        BLog.v("animapper trending → ${filtered.size} non-movie entries")
        filtered
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) {
        BLog.e("animapper trending failed: ${e.message}")
        emptyList()
    }
    }

    suspend fun donghua(limit: Int = 30, yearFloor: Int? = null): List<AniListApi.Entry> {
    // Separate cache key per floor. Year toggle is a rare action
    // so the extra cache slot is negligible.
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
        BLog.v("animapper donghua parsed ${parsed.size} entries (floor=$yearFloor)")
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
            if (res.code !in 200..299) return null
            val root = JSONObject(res.text)
            // Detail wraps the object in {success, result}. Unwrap.
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
            // search entries use {id, titles, images} directly (no wrapper)
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

        // Episode count from totalUnits (detail). Fall back to episodes
        // (search). Movies have totalUnits = 1.
        val totalUnits = o.optInt("totalUnits", 0).takeIf { it > 0 }
        val episodes = totalUnits
            ?: o.optInt("episodes", 0).takeIf { it > 0 }

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
