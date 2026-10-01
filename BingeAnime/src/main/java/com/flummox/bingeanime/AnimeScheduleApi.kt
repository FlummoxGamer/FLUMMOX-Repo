package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder

// AnimeSchedule.net — calendar-first anime API with a public search
// endpoint. 120 req/min per IP. Bearer token auth via BuildConfig.
// Response includes anime with poster, year, episode count, genres,
// status, and cross-reference IDs (MAL, AniList, Kitsu).
//
// No per-episode endpoint exists. Episode titles are TBA on the site
// and not exposed at all via the API. We generate "Episode N" labels
// using the anime's episode count.
object AnimeScheduleApi {
    private const val BASE = "https://animeschedule.net/api/v3"
    private const val IMG_BASE = "https://img.animeschedule.net/production/assets/public/img/"
    private const val ROW_TTL = 6 * 60 * 60 * 1000L

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Authorization" to "Bearer ${BuildConfig.ANIMESCHEDULE_API_KEY}",
        "User-Agent" to "BingeAnime/1.0"
    )

    suspend fun search(query: String, limit: Int = 30): List<AniListApi.Entry> {
        val ck = "asched:search:$query:$limit"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            return try { parseList(JSONObject(cached), limit) } catch (_: Exception) { emptyList() }
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE/anime?q=$encoded&st=popularity&page=1"
        return try {
            val res = app.get(url, headers = headers())
            BLog.v("animeschedule search HTTP ${res.code} len=${res.text.length}")
            if (res.code == 429) { BLog.e("animeschedule 429"); return emptyList() }
            if (res.code == 401 || res.code == 403) {
                BLog.e("animeschedule auth failed (HTTP ${res.code}) — check ANIMESCHEDULE_API_KEY secret")
                return emptyList()
            }
            if (res.code !in 200..299) {
                BLog.e("animeschedule HTTP ${res.code}: ${res.text.take(200)}")
                return emptyList()
            }
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseList(root, limit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("animeschedule search failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun detail(route: String): AniListApi.Entry? {
    val ck = "asched:detail:$route"
    BCCache.get(ck, ROW_TTL)?.let { cached ->
        return try { parseEntry(JSONObject(cached)) } catch (_: Exception) { null }
    }
    return try {
        val res = app.get("$BASE/anime/$route", headers = headers())
        BLog.v("animeschedule detail $route HTTP ${res.code} len=${res.text.length}")
        if (res.code !in 200..299) return null
        val root = JSONObject(res.text)
        // DIAGNOSTIC — dump top-level field names + episode-related
        // fields so we can see the real episode count shape. One
        // title (one-piece) is enough. Remove after parser fix.
        val keys = root.keys().asSequence().joinToString(",")
        BLog.v("animeschedule detail $route keys=$keys")
        BLog.v("animeschedule detail $route episodes=${root.opt("episodes")} " +
            "episodeCount=${root.opt("episodeCount")} " +
            "episodesCount=${root.opt("episodesCount")} " +
            "totalEpisodes=${root.opt("totalEpisodes")} " +
            "airedEpisodes=${root.opt("airedEpisodes")} " +
            "numEpisodes=${root.opt("numEpisodes")}")
        BCCache.put(ck, root.toString())
        parseEntry(root)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BLog.e("animeschedule detail $route failed: ${e.message}")
        null
    }
    }

    private fun parseList(root: JSONObject, limit: Int): List<AniListApi.Entry> {
        val arr = root.optJSONArray("anime") ?: return emptyList()
        val out = mutableListOf<AniListApi.Entry>()
        for (i in 0 until minOf(arr.length(), limit)) {
            parseEntry(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    private fun parseEntry(o: JSONObject?): AniListApi.Entry? {
        if (o == null) return null
        val route = o.optString("route").takeIf { it.isNotBlank() } ?: return null
        val title = o.optString("title").takeIf { it.isNotBlank() } ?: return null

        val imgRoute = o.optString("imageVersionRoute").takeIf { it.isNotBlank() }
        val cover = imgRoute?.let { "$IMG_BASE$it" }

        val year = o.optInt("year", 0).takeIf { it > 0 }
        val episodes = o.optInt("episodes", 0).takeIf { it > 0 }

        val mediaTypes = o.optJSONArray("mediaTypes")
        val typeName = mediaTypes?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
        val format = when (typeName?.lowercase()) {
            "tv" -> "TV"
            "movie" -> "MOVIE"
            "ova" -> "OVA"
            "ona" -> "ONA"
            "special" -> "SPECIAL"
            "music" -> "MUSIC"
            "tv short" -> "TV_SHORT"
            else -> null
        }

        val statusRaw = o.optString("status").takeIf { it.isNotBlank() }
        val status = when (statusRaw?.lowercase()) {
            "ongoing", "airing" -> "RELEASING"
            "finished" -> "FINISHED"
            "upcoming" -> "NOT_YET_RELEASED"
            else -> null
        }

        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }?.takeIf { it.isNotEmpty() }

        return AniListApi.Entry(
            id = route.hashCode(),
            idMal = null,
            title = AniListApi.Title(romaji = title, english = null, native = null),
            format = format,
            episodes = episodes,
            seasonYear = year,
            startDate = null,
            description = null,
            coverImage = cover,
            averageScore = null,
            status = status,
            genres = genres,
            country = null,
            relations = emptyList(),
            source = "animeschedule",
            sourceId = route
        )
    }
}
