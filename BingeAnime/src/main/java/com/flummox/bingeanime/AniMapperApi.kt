package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject

// AniMapper — aggregated anime metadata + streaming API. Free, no key.
// Rate limit: 60 req/min per IP.
// AniMapper uses AniList as its metadata backend, so the entry shape
// closely mirrors AniList's. Used here only for the Donghua row, which
// no other API can filter.
object AniMapperApi {
    private const val BASE = "https://api.animapper.net/api/v1"
    private const val ROW_TTL = 6 * 60 * 60 * 1000L

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "BingeAnime/1.0"
    )

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
            // response may be {data: {...}} or {...}
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

    // AniMapper wraps results in different shapes depending on endpoint.
    // We try several known patterns.
    private fun parseResponse(root: JSONObject): List<AniListApi.Entry> {
        val arr: JSONArray? = root.optJSONArray("results")
            ?: root.optJSONArray("data")
            ?: root.optJSONObject("data")?.optJSONArray("results")
            ?: root.optJSONObject("data")?.optJSONArray("media")
        if (arr == null) {
            BLog.e("animapper parse — no results array. body: ${root.toString().take(400)}")
            return emptyList()
        }
        val out = mutableListOf<AniListApi.Entry>()
        for (i in 0 until arr.length()) parseEntry(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }

    private fun parseEntry(o: JSONObject?): AniListApi.Entry? {
        if (o == null) return null
        // Verbose: dump first entry so we can see the actual shape
        BLog.v("animapper raw entry: ${o.toString().take(400)}")

        // ID: could be "id", "anilistId", "anilist_id"
        val id = listOf("id", "anilistId", "anilist_id")
            .firstNotNullOfOrNull { key ->
                o.optInt(key, 0).takeIf { it > 0 }
            } ?: return null

        // Title: could be {title:{romaji,english,native}} or {title:"..."} or top-level
        val titleObj = o.optJSONObject("title")
        val romaji = titleObj?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("title").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("name").takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        val english = titleObj?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
        val native = titleObj?.optString("native")?.takeIf { it.isNotBlank() && it != "null" }

        val format = o.optString("format").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("type").takeIf { it.isNotBlank() && it != "null" }

        val episodes = o.optInt("episodes", 0).takeIf { it > 0 }
            ?: o.optInt("numEpisodes", 0).takeIf { it > 0 }

        val year = o.optInt("seasonYear", 0).takeIf { it > 0 }
            ?: o.optString("startDate").take(4).toIntOrNull()

        val cover = o.optJSONObject("coverImage")
            ?.optString("extraLarge")?.takeIf { it.isNotBlank() && it != "null" }
            ?: o.optJSONObject("coverImage")
                ?.optString("large")?.takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("image").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("cover").takeIf { it.isNotBlank() && it != "null" }

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
