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
        BLog.v("animeschedule detail $route subOverride=${root.opt("subEpisodeOverride")} " +
            "dubOverride=${root.opt("dubEpisodeOverride")} " +
            "genericOverride=${root.opt("episodeOverride")} " +
            "episodes=${root.opt("episodes")} " +
            "premier=${root.opt("premier")}")
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
    val titleFallback = o.optString("title").takeIf { it.isNotBlank() } ?: return null

    // names carries locale variants. English drives relevance
    // matching in sortChronological — without it, "attack on titan"
    // never tier-0 matches "Shingeki no Kyojin" and everything
    // sorts by year/date randomly.
    val names = o.optJSONObject("names")
    val english = names?.optString("en")?.takeIf { it.isNotBlank() && it != "null" }
    val romajiFromNames = names?.optString("romaji")
        ?.takeIf { it.isNotBlank() && it != "null" }
    val primary = romajiFromNames ?: titleFallback

    val imgRoute = o.optString("imageVersionRoute").takeIf { it.isNotBlank() }
    val cover = imgRoute?.let { "$IMG_BASE$it" }

    val year = o.optInt("year", 0).takeIf { it > 0 }

    // Episode counts. AnimeSchedule exposes:
    //   subEpisodeOverride / dubEpisodeOverride — real counts per
    //   audio track when the site has explicit data (e.g. One Piece
    //   Sub 1100+ / Dub 1000+)
    //   episodeOverride — generic fallback
    // For ongoing series, any of these gives the real current count.
    // Each field is an object: {overrideDate, overrideEpisode, episodesAired}.
    // Real count is nested under overrideEpisode.
    val subOverride = o.optJSONObject("subEpisodeOverride")
        ?.optInt("overrideEpisode", 0)?.takeIf { it > 0 }
    val dubOverride = o.optJSONObject("dubEpisodeOverride")
        ?.optInt("overrideEpisode", 0)?.takeIf { it > 0 }
    val genericOverride = o.optJSONObject("episodeOverride")
        ?.optInt("overrideEpisode", 0)?.takeIf { it > 0 }
    // Prefer the highest available count. One Piece: sub=0 (unset),
    // generic=1180, dub=1156. generic wins → 1180.
    val episodes = listOfNotNull(subOverride, genericOverride, dubOverride).maxOrNull()
        ?: o.optInt("episodes", 0).takeIf { it > 0 }

    // Defensive format mapping. AnimeSchedule sends variants like
    // "TV Series", "TV Short" — match by substring.
    val rawType = o.optJSONArray("mediaTypes")?.optJSONObject(0)?.optString("name")
    val format = if (rawType == null) null else {
        val l = rawType.lowercase()
        when {
            l.contains("tv short") -> "TV_SHORT"
            l.contains("tv") -> "TV"
            l.contains("movie") -> "MOVIE"
            l.contains("ova") -> "OVA"
            l.contains("ona") -> "ONA"
            l.contains("special") -> "SPECIAL"
            l.contains("music") -> "MUSIC"
            else -> null
        }
    }

    val statusRaw = o.optString("status").takeIf { it.isNotBlank() }
    val status = when (statusRaw?.lowercase()) {
        "ongoing", "airing" -> "RELEASING"
        "finished" -> "FINISHED"
        "upcoming" -> "NOT_YET_RELEASED"
        else -> null
    }

    // Score. AnimeSchedule exposes stats.rating (0-100) and
    // stats.averageScore (0-100). Either works — divide by 10 to
    // convert to the 0-10 scale AniListApi.Entry uses internally.
    val stats = o.optJSONObject("stats")
    val score = stats?.optDouble("rating", 0.0)?.takeIf { it > 0 }
        ?: stats?.optDouble("averageScore", 0.0)?.takeIf { it > 0 }
        ?: stats?.optDouble("score", 0.0)?.takeIf { it > 0 }
    val averageScore = score?.let { (it / 10.0 * 10).toInt().coerceIn(1, 100) }

    val description = o.optString("description").takeIf { it.isNotBlank() && it != "null" }

    val genres = o.optJSONArray("genres")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
        }
    }?.takeIf { it.isNotEmpty() }

    return AniListApi.Entry(
        id = route.hashCode(),
        idMal = null,
        title = AniListApi.Title(romaji = primary, english = english, native = null),
        format = format,
        episodes = episodes,
        seasonYear = year,
        startDate = null,
        description = description,
        coverImage = cover,
        averageScore = averageScore,
        status = status,
        genres = genres,
        country = null,
        relations = emptyList(),
        source = "animeschedule",
        sourceId = route
    )
    }
}
