package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONObject

object MalApi {
    private const val BASE = "https://api.myanimelist.net/v2"
    private const val CACHE_TTL = 24 * 60 * 60 * 1000L

    private const val FIELDS = "id,title,main_picture,alternative_titles," +
        "start_date,synopsis,mean,num_episodes,media_type,status,genres,related_anime"

    private fun headers(): Map<String, String> = mapOf(
        "X-MAL-CLIENT-ID" to BuildConfig.MAL_CLIENT_ID,
        "Accept" to "application/json"
    )

    // ── search ──
    suspend fun search(query: String, limit: Int = 20): List<AniListApi.Entry> {
        val ck = "mal:search:$query:$limit"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseList(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/anime?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&limit=$limit&fields=$FIELDS&nsfw=false"
        return try {
            val res = app.get(url, headers = headers())
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseList(root)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("MAL search failed: ${e.message}")
            emptyList()
        }
    }

    // ── ranking (home rows) ──
    // type: all | airing | upcoming | tv | ova | movie | special | bypopularity | favorite
    suspend fun ranking(type: String, limit: Int = 30): List<AniListApi.Entry> {
        val ck = "mal:rank:$type:$limit"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseList(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/anime/ranking?ranking_type=$type&limit=$limit&fields=$FIELDS&nsfw=false"
        return try {
            val res = app.get(url, headers = headers())
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseList(root)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("MAL ranking '$type' failed: ${e.message}")
            emptyList()
        }
    }

    // ── detail ──
    suspend fun detail(malId: Int): AniListApi.Entry? {
        val ck = "mal:detail:$malId"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseEntry(JSONObject(cached)) } catch (_: Exception) { null }
        }
        return try {
            val res = app.get("$BASE/anime/$malId?fields=$FIELDS", headers = headers())
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseEntry(root)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("MAL detail $malId failed: ${e.message}")
            null
        }
    }

    // ── parse helpers ──
    private fun parseList(root: JSONObject): List<AniListApi.Entry> {
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<AniListApi.Entry>()
        for (i in 0 until arr.length()) {
            val node = arr.optJSONObject(i)?.optJSONObject("node") ?: continue
            parseEntry(node)?.let { out.add(it) }
        }
        return out
    }

    private fun parseEntry(o: JSONObject?): AniListApi.Entry? {
        if (o == null) return null
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null

        val mainTitle = o.optString("title").takeIf { it.isNotBlank() } ?: return null
        val alt = o.optJSONObject("alternative_titles")
        val english = alt?.optString("en")?.takeIf { it.isNotBlank() && it != "null" }
        val native = alt?.optString("ja")?.takeIf { it.isNotBlank() && it != "null" }
        val synonyms = alt?.optJSONArray("synonyms")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        }

        val startRaw = o.optString("start_date").takeIf { it.isNotBlank() && it != "null" }
        val startYear = startRaw?.take(4)?.toIntOrNull()

        val picture = o.optJSONObject("main_picture")
        val cover = picture?.optString("large")?.takeIf { it.isNotBlank() }
            ?: picture?.optString("medium")?.takeIf { it.isNotBlank() }

        val mean = o.optDouble("mean", 0.0).takeIf { it > 0 }
        val averageScore = mean?.let { (it * 10).toInt() }

        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }?.takeIf { it.isNotEmpty() }

        val relations = mutableListOf<AniListApi.Relation>()
        o.optJSONArray("related_anime")?.let { arr ->
            for (i in 0 until arr.length()) {
                val rel = arr.optJSONObject(i) ?: continue
                val relType = rel.optString("relation_type").takeIf { it.isNotBlank() } ?: continue
                val relNode = rel.optJSONObject("node") ?: continue
                val relId = relNode.optInt("id", 0).takeIf { it > 0 } ?: continue
                relations.add(AniListApi.Relation(
                    type = mapRelationType(relType),
                    entryId = relId,
                    entryTitle = AniListApi.Title(
                        romaji = relNode.optString("title").takeIf { it.isNotBlank() },
                        english = null,
                        native = null
                    ),
                    entryFormat = mapMediaType(relNode.optString("media_type")),
                    entryEpisodes = relNode.optInt("num_episodes", 0).takeIf { it > 0 },
                    entrySeasonYear = relNode.optString("start_date").takeIf { it.isNotBlank() }
                        ?.take(4)?.toIntOrNull(),
                    entryStartDate = relNode.optString("start_date").takeIf { it.isNotBlank() }
                ))
            }
        }

        // If MAL returned no English title, promote the first latin-only
        // synonym. Improves search display for shows with no official EN.
        val fallbackEnglish = if (english == null && synonyms != null) {
            synonyms.firstOrNull { it.matches(Regex("""^[A-Za-z0-9\s\-:!?'.,()&]+$""")) }
        } else english

        return AniListApi.Entry(
            id = id,
            idMal = id,
            title = AniListApi.Title(
                romaji = mainTitle,
                english = fallbackEnglish,
                native = native
            ),
            format = mapMediaType(o.optString("media_type")),
            episodes = o.optInt("num_episodes", 0).takeIf { it > 0 },
            seasonYear = startYear,
            startDate = startRaw,
            description = o.optString("synopsis").takeIf { it.isNotBlank() && it != "null" },
            coverImage = cover,
            averageScore = averageScore,
            status = o.optString("status").takeIf { it.isNotBlank() },
            genres = genres,
            country = null,
            relations = relations,
            source = "mal"
        )
    }

    private fun mapMediaType(raw: String?): String? = when (raw) {
        "TV", "tv" -> "TV"
        "TV Special", "tv_special" -> "SPECIAL"
        "OVA", "ova" -> "OVA"
        "ONA", "ona" -> "ONA"
        "Movie", "movie" -> "MOVIE"
        "Special", "special" -> "SPECIAL"
        "Music", "music" -> "MUSIC"
        "CM" -> "SPECIAL"
        "PV" -> "SPECIAL"
        else -> null
    }

    private fun mapRelationType(raw: String): String = when (raw.lowercase()) {
        "sequel" -> "SEQUEL"
        "prequel" -> "PREQUEL"
        "side_story" -> "SIDE_STORY"
        "spin_off" -> "SPIN_OFF"
        "alternative_version" -> "ALTERNATIVE"
        "parent_story" -> "PARENT"
        "summary" -> "SUMMARY"
        else -> raw.uppercase()
    }
}
