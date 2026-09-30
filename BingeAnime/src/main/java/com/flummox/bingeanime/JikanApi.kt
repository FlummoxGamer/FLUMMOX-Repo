package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder

// Jikan — unofficial MyAnimeList API v4.
// No key required. Rate limit: 3 req/s, 60 req/min (unauthenticated).
// Used for search and home-row fallback when AniList is in cooldown.
// Exposes the full MAL database via website scraping, bypassing the
// official MAL API's filter/nsfw/gated-parameter bugs.
object JikanApi {
    private const val BASE = "https://api.jikan.moe/v4"
    private const val CACHE_TTL = 24 * 60 * 60 * 1000L

    // Jikan genre IDs (verified against /genres/anime).
    private val GENRE_IDS = mapOf(
        "Action" to 1,
        "Adventure" to 2,
        "Comedy" to 4,
        "Drama" to 8,
        "Fantasy" to 10,
        "Romance" to 22,
        "Sci-Fi" to 24,
        "Slice of Life" to 36,
        "Supernatural" to 37,
        "Mystery" to 7,
        "Sports" to 30,
        "Mecha" to 18,
        "Isekai" to 62,
        "School" to 23,
        "Historical" to 13
    )

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "BingeAnime/1.0 (CloudStream)"
    )

    // ── search ──
    suspend fun search(query: String, limit: Int = 20): List<AniListApi.Entry> {
        val ck = "jikan:search:$query:$limit"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseList(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/anime?q=${URLEncoder.encode(query, "UTF-8")}&limit=$limit&sfw=true"
        return try {
            val res = app.get(url, headers = headers())
            if (res.code == 429) {
                BLog.e("Jikan search 429 for '$query'")
                return emptyList()
            }
        val root = JSONObject(res.text)
        val dataArr = root.optJSONArray("data")
        if (dataArr == null || dataArr.length() == 0) {
            BLog.e("Jikan search empty — body: ${root.toString().take(400)}")
            return emptyList()
        }
        BCCache.put(ck, root.toString())
        parseList(root)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BLog.e("Jikan search failed: ${e.message}")
        emptyList()
    }
    }

    // ── top anime (home-row fallback) ──
    // filter: airing | upcoming | bypopularity | favorite
    // type:   tv | movie | ova | special | ona | music
    suspend fun topAnime(filter: String? = null, type: String? = null, limit: Int = 30): List<AniListApi.Entry> {
        val ck = "jikan:top:${filter ?: ""}:${type ?: ""}:$limit"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseList(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = buildString {
            append("$BASE/top/anime?limit=$limit&sfw=true")
            filter?.let { append("&filter=$it") }
            type?.let { append("&type=$it") }
        }
        return try {
            val res = app.get(url, headers = headers())
            if (res.code == 429) {
                BLog.e("Jikan top 429 (filter=$filter type=$type)")
                return emptyList()
            }
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseList(root)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("Jikan top failed: ${e.message}")
            emptyList()
        }
    }

    // ── genre/tag top ──
    suspend fun genreTop(rowName: String, limit: Int = 30): List<AniListApi.Entry> {
        val genreId = GENRE_IDS[rowName] ?: return emptyList()
        val ck = "jikan:genre:$genreId:$limit"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseList(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/anime?genres=$genreId&order_by=popularity&sort=desc&limit=$limit&sfw=true"
        return try {
            val res = app.get(url, headers = headers())
            if (res.code == 429) {
                BLog.e("Jikan 429 — genre '$rowName' skipped")
                return emptyList()
            }
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseList(root)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("Jikan genre '$rowName' failed: ${e.message}")
            emptyList()
        }
    }

     // ── detail ──
suspend fun detail(malId: Int): AniListApi.Entry? {
    val ck = "jikan:detail:$malId"
    BCCache.get(ck, CACHE_TTL)?.let { cached ->
        return try { parseEntry(JSONObject(cached).optJSONObject("data")) } catch (_: Exception) { null }
    }
    return try {
        val res = app.get("$BASE/anime/$malId/full", headers = headers())
        if (res.code == 429) {
            BLog.e("Jikan detail 429 for $malId")
            return null
        }
        val root = JSONObject(res.text)
        BCCache.put(ck, root.toString())
        parseEntry(root.optJSONObject("data"))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BLog.e("Jikan detail $malId failed: ${e.message}")
        null
    }
}
    // ── parse ──
    private fun parseList(root: JSONObject): List<AniListApi.Entry> {
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<AniListApi.Entry>()
        for (i in 0 until arr.length()) parseEntry(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }

    private fun parseEntry(o: JSONObject?): AniListApi.Entry? {
        if (o == null) return null
        val id = o.optInt("mal_id", 0).takeIf { it > 0 } ?: return null
        val mainTitle = o.optString("title").takeIf { it.isNotBlank() } ?: return null

        val english = o.optString("title_english").takeIf { it.isNotBlank() && it != "null" }
        val native = o.optString("title_japanese").takeIf { it.isNotBlank() && it != "null" }

        // start year — from aired.from first, year field as fallback
        val startYear = try {
            o.optJSONObject("aired")
                ?.optJSONObject("prop")
                ?.optJSONObject("from")
                ?.optInt("year", 0)
                ?.takeIf { it > 0 }
                ?: o.optInt("year", 0).takeIf { it > 0 }
        } catch (_: Exception) { null }

        val cover = o.optJSONObject("images")?.optJSONObject("jpg")
            ?.optString("large_image_url")?.takeIf { it.isNotBlank() }
            ?: o.optJSONObject("images")?.optJSONObject("jpg")
                ?.optString("image_url")?.takeIf { it.isNotBlank() }

        val score = o.optDouble("score", 0.0).takeIf { it > 0 }
        val averageScore = score?.let { (it * 10).toInt() }

        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }?.takeIf { it.isNotEmpty() }

        return AniListApi.Entry(
            id = id,
            idMal = id,
            title = AniListApi.Title(
                romaji = mainTitle,
                english = english,
                native = native
            ),
            format = mapType(o.optString("type")),
            episodes = o.optInt("episodes", 0).takeIf { it > 0 },
            seasonYear = startYear,
            startDate = null,
            description = o.optString("synopsis").takeIf { it.isNotBlank() && it != "null" },
            coverImage = cover,
            averageScore = averageScore,
            status = o.optString("status").takeIf { it.isNotBlank() },
            genres = genres,
            country = null,
            relations = emptyList(),
            source = "mal"
        )
    }

    private fun mapType(raw: String?): String? = when (raw) {
        "TV" -> "TV"
        "TV Special" -> "SPECIAL"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "Movie" -> "MOVIE"
        "Special" -> "SPECIAL"
        "Music" -> "MUSIC"
        "CM" -> "SPECIAL"
        "PV" -> "SPECIAL"
        else -> null
    }
}
