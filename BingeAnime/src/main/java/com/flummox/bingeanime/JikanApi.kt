package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONObject

// Jikan — unofficial MyAnimeList API v4.
// No key required. Rate limit: 3 req/s, 60 req/min (unauthenticated).
// Used only for home-row fallback when AniList is in cooldown AND the
// row is a genre or tag row — MAL's official ranking endpoint has no
// genre filter, Jikan does.
object JikanApi {
    private const val BASE = "https://api.jikan.moe/v4"
    private const val CACHE_TTL = 24 * 60 * 60 * 1000L

    // Jikan genre IDs (verified 2026-09 against /genres/anime).
    // Row names are matched exactly — anything not in this map returns
    // null and the row stays empty when AniList is down.
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
        // Tags — Jikan mixes these into the same ID space
        "Isekai" to 62,
        "School" to 23,
        "Historical" to 13
    )

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json"
    )

    // Fetch top-30 popularity-ordered anime for a genre/tag.
    // Returns an empty list if the row name isn't a recognized genre.
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

        // Jikan exposes title_english and title_japanese directly at top level
        val english = o.optString("title_english").takeIf { it.isNotBlank() && it != "null" }
        val native = o.optString("title_japanese").takeIf { it.isNotBlank() && it != "null" }

        val startRaw = o.optString("aired").takeIf { it.isNotBlank() }
        val startYear = try {
            JSONObject(startRaw ?: "{}").optJSONObject("from")?.optString("from")?.take(4)?.toIntOrNull()
        } catch (_: Exception) { null }
        // Fallback: some entries only expose "year"
        val yearFallback = o.optInt("year", 0).takeIf { it > 0 }
        val finalYear = startYear ?: yearFallback

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

        val episodes = o.optInt("episodes", 0).takeIf { it > 0 }
        val format = mapType(o.optString("type"))

        return AniListApi.Entry(
            id = id,
            idMal = id,
            title = AniListApi.Title(
                romaji = mainTitle,
                english = english,
                native = native
            ),
            format = format,
            episodes = episodes,
            seasonYear = finalYear,
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

    // Jikan type strings: TV, Movie, OVA, Special, ONA, Music, CM, PV, TV Special
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
