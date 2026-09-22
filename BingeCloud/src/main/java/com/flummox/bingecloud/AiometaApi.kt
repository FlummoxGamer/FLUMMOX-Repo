package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

const val AIOMETA_BASE = "https://aiometadata.elfhosted.com/stremio/9197a4a9-2f5b-4911-845e-8704c520bdf7"

// ── data models ──
data class AioCast(val name: String? = null, val character: String? = null, val photo: String? = null)

data class AioVideo(
    val id: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val released: String? = null,
    val runtime: String? = null,
    val available: Boolean? = null
)

data class AioAppExtras(
    val seasonPosters: List<String?>? = null,
    val certification: String? = null,
    val cast: List<AioCast>? = null
)

data class AioMeta(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val description: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val landscapePoster: String? = null,
    val genres: List<String>? = null,
    val imdbRating: String? = null,
    val releaseInfo: String? = null,
    val released: String? = null,
    val runtime: String? = null,
    val year: String? = null,
    val country: String? = null,
    val imdb_id: String? = null,
    val videos: List<AioVideo>? = null,
    val app_extras: AioAppExtras? = null
)

data class AioMetaResponse(val meta: AioMeta? = null)
data class AioCatalogResponse(val metas: List<AioMeta>? = null)

// ── Aiometa endpoints ──
suspend fun aioFetchMeta(type: String, id: String): AioMeta? {
    return try {
        val url = "$AIOMETA_BASE/meta/$type/$id.json"
        val json = app.get(url).text
        tryParseJson<AioMetaResponse>(json)?.meta
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

suspend fun aioFetchCatalog(
    type: String,
    catalogId: String,
    param: String? = null,
    skip: Int = 0
): List<AioMeta> {
    return try {
        val extras = StringBuilder()
        if (!param.isNullOrBlank()) {
            val eq = param.indexOf('=')
            if (eq > 0) {
                val key = param.substring(0, eq).trim()
                val value = param.substring(eq + 1).trim()
                extras.append(key).append("=")
                    .append(URLEncoder.encode(value, "UTF-8"))
                    .append("&")
            } else {
                extras.append("genre=").append(URLEncoder.encode(param, "UTF-8")).append("&")
            }
        }
        extras.append("skip=").append(skip)

        val url = "$AIOMETA_BASE/catalog/$type/$catalogId/$extras.json"
        val json = app.get(url).text
        tryParseJson<AioCatalogResponse>(json)?.metas ?: emptyList()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}

suspend fun aioSearch(query: String, type: String): List<AioMeta> {
    return try {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val catalogId = when (type) {
            "movie" -> "search.movie"
            "series" -> "search.series"
            else -> "search.$type"
        }
        val url = "$AIOMETA_BASE/catalog/$type/$catalogId/search=$encoded.json"
        val json = app.get(url).text
        tryParseJson<AioCatalogResponse>(json)?.metas ?: emptyList()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}

// ── TMDB models ──
data class TmdbDiscoverItem(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
    val overview: String? = null,
    val genre_ids: List<Int>? = null
)

data class TmdbDiscoverResponse(
    val page: Int? = null,
    val results: List<TmdbDiscoverItem>? = null,
    val total_pages: Int? = null
)

private val INDIAN_ONLY_PROVIDERS = setOf(122, 220, 237, 232)

// ── TMDB discover ──
private suspend fun tmdbDiscover(
    tmdbType: String,
    providerId: Int,
    region: String,
    skip: Int
): List<AioMeta> {
    val key = BuildConfig.TMDB_API_KEY
    if (key.isBlank()) return emptyList()
    val page = (skip / 20).coerceAtLeast(0) + 1

    val since = java.time.LocalDate.now().minusDays(180).toString()
    val dateParam = if (tmdbType == "movie") "primary_release_date.gte" else "first_air_date.gte"

    val url = "https://api.themoviedb.org/3/discover/$tmdbType" +
        "?api_key=$key" +
        "&with_watch_providers=$providerId" +
        "&watch_region=$region" +
        "&sort_by=popularity.desc" +
        "&$dateParam=$since" +
        "&vote_count.gte=10" +
        "&page=$page"

    val result = try {
        val json = app.get(url).text
        tryParseJson<TmdbDiscoverResponse>(json)?.results?.mapNotNull { it.toAioMeta(tmdbType) } ?: emptyList()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BCLog.e("TMDB discover failed: ${e.message}")
        emptyList()
    }

    if (result.isEmpty()) {
        val fallbackUrl = "https://api.themoviedb.org/3/discover/$tmdbType" +
            "?api_key=$key" +
            "&with_watch_providers=$providerId" +
            "&watch_region=$region" +
            "&sort_by=popularity.desc" +
            "&page=$page"
        return try {
            val json = app.get(fallbackUrl).text
            tryParseJson<TmdbDiscoverResponse>(json)?.results?.mapNotNull { it.toAioMeta(tmdbType) } ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BCLog.e("TMDB discover failed: ${e.message}")
            emptyList()
        }
    }
    return result
}

suspend fun tmdbDiscoverMerged(providerId: Int, skip: Int): List<AioMeta> {
    val regions = if (providerId in INDIAN_ONLY_PROVIDERS) listOf("IN") else listOf("US", "IN")
    val all = mutableListOf<AioMeta>()
    for (r in regions) {
        all += tmdbDiscover("movie", providerId, r, skip)
        all += tmdbDiscover("tv", providerId, r, skip)
    }
    return all.distinctBy { it.id }
}

// ── TMDB trending fallback ──
suspend fun tmdbTrendingDirect(tmdbType: String): List<AioMeta> {
    val key = BuildConfig.TMDB_API_KEY
    if (key.isBlank()) return emptyList()
    val type = if (tmdbType == "tv") "tv" else "movie"
    val url = "https://api.themoviedb.org/3/trending/$type/day?api_key=$key&language=en-US"
    return try {
        val json = app.get(url).text
        tryParseJson<TmdbDiscoverResponse>(json)?.results?.mapNotNull { it.toAioMeta(tmdbType) } ?: emptyList()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BCLog.e("TMDB trending direct failed: ${e.message}")
        emptyList()
    }
}

// ── TMDB full meta fallback for inside page ──
suspend fun tmdbDetailMeta(type: String, tmdbId: String): AioMeta? {
    val key = BuildConfig.TMDB_API_KEY
    if (key.isBlank()) return null
    val isSeries = type.contains("series", true) || type.contains("anime", true)
    val tmdbType = if (isSeries) "tv" else "movie"

    return try {
        val detailUrl = "https://api.themoviedb.org/3/$tmdbType/$tmdbId?api_key=$key&language=en-US"
        val obj = JSONObject(app.get(detailUrl).text)

        val name = obj.optString("title").ifBlank { obj.optString("name") }.takeIf { it.isNotBlank() }
            ?: return null
        val dateStr = obj.optString("release_date").ifBlank { obj.optString("first_air_date") }
        val yearStr = dateStr.take(4).takeIf { it.length == 4 }
        val rating = obj.optDouble("vote_average", 0.0).takeIf { it > 0 }?.toString()

        val genresArr = obj.optJSONArray("genres")
        val genres = mutableListOf<String>()
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                genresArr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
            }
        }

        var videos = emptyList<AioVideo>()
        if (isSeries) {
            val seasons = obj.optJSONArray("seasons")
            if (seasons != null) {
                val seasonNums = mutableListOf<Int>()
                for (i in 0 until seasons.length()) {
                    val s = seasons.optJSONObject(i) ?: continue
                    val sNum = s.optInt("season_number", 0)
                    if (sNum > 0) seasonNums.add(sNum)
                }

                val seasonResults: List<List<AioVideo>> = coroutineScope {
                    seasonNums.map { sNum ->
                        async {
                            try {
                                val seasonUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/$sNum?api_key=$key&language=en-US"
                                val seasonObj = JSONObject(app.get(seasonUrl).text)
                                val eps = seasonObj.optJSONArray("episodes") ?: return@async emptyList()
                                val out = mutableListOf<AioVideo>()
                                for (j in 0 until eps.length()) {
                                    val e = eps.optJSONObject(j) ?: continue
                                    val eNum = e.optInt("episode_number", 0)
                                    if (eNum <= 0) continue
                                    val stillPath = e.optString("still_path").takeIf { it.isNotBlank() && it != "null" }
                                    out.add(AioVideo(
                                        id = "tmdb:$tmdbId:$sNum:$eNum",
                                        title = e.optString("name").takeIf { it.isNotBlank() },
                                        season = sNum,
                                        episode = eNum,
                                        thumbnail = stillPath?.let { "https://image.tmdb.org/t/p/w300$it" },
                                        overview = e.optString("overview").takeIf { it.isNotBlank() },
                                        released = e.optString("air_date").takeIf { it.isNotBlank() },
                                        available = true
                                    ))
                                }
                                out
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                BCLog.e("TMDB season $sNum fetch failed: ${e.message}")
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
                videos = seasonResults.flatten()
            }
        }

        val poster = obj.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = obj.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
            ?.let { "https://image.tmdb.org/t/p/original$it" }

        BCLog.d("TMDB direct meta: $name (${videos.size} eps)")

        AioMeta(
            id = "tmdb:$tmdbId",
            name = name,
            type = if (isSeries) "series" else "movie",
            description = obj.optString("overview").takeIf { it.isNotBlank() },
            poster = poster,
            background = backdrop,
            genres = genres.takeIf { it.isNotEmpty() },
            imdbRating = rating,
            releaseInfo = yearStr,
            year = yearStr,
            imdb_id = obj.optString("imdb_id").takeIf { it.isNotBlank() && it != "null" },
            videos = videos.takeIf { it.isNotEmpty() }
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BCLog.e("TMDB direct meta failed: ${e.message}")
        null
    }
}

// ── TMDB language discover (fallback for Bangla) ──
suspend fun tmdbDiscoverByLanguage(tmdbType: String, lang: String, skip: Int): List<AioMeta> {
    val key = BuildConfig.TMDB_API_KEY
    if (key.isBlank()) return emptyList()
    val page = (skip / 20).coerceAtLeast(0) + 1
    val url = "https://api.themoviedb.org/3/discover/$tmdbType" +
        "?api_key=$key" +
        "&with_original_language=$lang" +
        "&sort_by=popularity.desc" +
        "&page=$page"
    return try {
        val json = app.get(url).text
        tryParseJson<TmdbDiscoverResponse>(json)?.results?.mapNotNull { it.toAioMeta(tmdbType) } ?: emptyList()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BCLog.e("TMDB lang-discover failed: ${e.message}")
        emptyList()
    }
}

// ── Clean Hindi Series — daily rotation ──
// Premium genres only. Soaps never carry these tags on TMDB.
//   9648=Mystery, 80=Crime, 10765=Sci-Fi&Fantasy,
//   10759=Action&Adventure, 10768=War&Politics.
// 60-item pool fetched once per day, date-shuffled, cached 24h.
suspend fun tmdbHindiSeriesClean(skip: Int = 0): List<AioMeta> {
    val key = BuildConfig.TMDB_API_KEY
    if (key.isBlank()) return emptyList()

    val today = java.time.LocalDate.now().toString()
    val cacheKey = "hindiPool:$today"

    val cached = BCCache.get(cacheKey, 24 * 60 * 60 * 1000L)
    val pool: List<AioMeta> = if (cached != null) {
        try {
            val arr = JSONArray(cached)
            val out = mutableListOf<AioMeta>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                out.add(
                    AioMeta(
                        id = id,
                        name = name,
                        type = "series",
                        poster = o.optString("poster").takeIf { it.isNotBlank() },
                        releaseInfo = o.optString("year").takeIf { it.isNotBlank() },
                        year = o.optString("year").takeIf { it.isNotBlank() }
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    } else {
        val fetched = fetchHindiPool(key)
        try {
            val arr = JSONArray()
            for (m in fetched) {
                arr.put(
                    JSONObject().apply {
                        put("id", m.id ?: "")
                        put("name", m.name ?: "")
                        put("poster", m.poster ?: "")
                        put("year", m.year ?: "")
                    }
                )
            }
            BCCache.put(cacheKey, arr.toString())
        } catch (_: Exception) {}
        fetched
    }

    val start = skip
    if (start >= pool.size) return emptyList()
    val end = (skip + 20).coerceAtMost(pool.size)
    return pool.subList(start, end)
}

private suspend fun fetchHindiPool(key: String): List<AioMeta> {
    val since = java.time.LocalDate.now().minusYears(3).toString()
    val pages = listOf(1, 2, 3)

    // Premium genres only. No provider filter — TMDB's provider data
    // for Indian platforms is too sparse. Recency window keeps it fresh.
    val all = mutableListOf<AioMeta>()
    coroutineScope {
        pages.map { p ->
            async {
                try {
                    val url = "https://api.themoviedb.org/3/discover/tv" +
                        "?api_key=$key" +
                        "&with_original_language=hi" +
                        "&with_genres=9648%7C80%7C10765%7C10759%7C10768" +
                        "&without_genres=10766,10764,10767,10763" +
                        "&first_air_date.gte=$since" +
                        "&sort_by=first_air_date.desc" +
                        "&vote_count.gte=5" +
                        "&page=$p"
                    val json = app.get(url).text
                    tryParseJson<TmdbDiscoverResponse>(json)?.results?.mapNotNull { it.toAioMeta("tv") }
                        ?: emptyList()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().forEach { all.addAll(it) }
    }
    val seed = java.time.LocalDate.now().toEpochDay()
    return all.distinctBy { it.id }.shuffled(java.util.Random(seed))
}

private fun TmdbDiscoverItem.toAioMeta(tmdbType: String): AioMeta? {
    val itemId = id ?: return null
    val itemName = title ?: name ?: return null
    val dateStr = release_date ?: first_air_date
    val yearStr = dateStr?.take(4)
    return AioMeta(
        id = "tmdb:$itemId",
        name = itemName,
        type = if (tmdbType == "tv") "series" else "movie",
        description = overview,
        poster = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" },
        background = backdrop_path?.let { "https://image.tmdb.org/t/p/original$it" },
        imdbRating = vote_average?.toString(),
        releaseInfo = yearStr,
        year = yearStr
    )
}
