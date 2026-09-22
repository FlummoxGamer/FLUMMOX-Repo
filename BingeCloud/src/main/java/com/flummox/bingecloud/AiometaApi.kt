package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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

// ── endpoints ──
suspend fun aioFetchMeta(type: String, id: String): AioMeta? {
    return try {
        val url = "$AIOMETA_BASE/meta/$type/$id.json"
        val json = app.get(url).text
        tryParseJson<AioMetaResponse>(json)?.meta
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
            // ── if value looks like "key=val", split and encode the value side ──
            // ── if bare value, fall back to legacy genre= param ──
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
    } catch (e: Exception) {
        emptyList()
    }
}

// ── TMDB Discover (streaming-platform catalogs) ──
data class TmdbDiscoverItem(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
    val overview: String? = null
)

data class TmdbDiscoverResponse(
    val page: Int? = null,
    val results: List<TmdbDiscoverItem>? = null,
    val total_pages: Int? = null
)

private val INDIAN_ONLY_PROVIDERS = setOf(122, 220, 237, 232)

private suspend fun tmdbDiscover(
    tmdbType: String, providerId: Int, region: String, skip: Int
): List<AioMeta> {
    val key = BuildConfig.TMDB_API_KEY
    if (key.isBlank()) return emptyList()
    val page = (skip / 20).coerceAtLeast(0) + 1

    // Recency window: only titles released in the last 180 days.
    // Combined with popularity.desc, gives "new + popular on X"
    // instead of "all-time popular on X". Catalog refreshes as
    // new titles drop.
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
        val parsed = tryParseJson<TmdbDiscoverResponse>(json)
        parsed?.results?.mapNotNull { it.toAioMeta(tmdbType) } ?: emptyList()
    } catch (e: Exception) {
        BCLog.e("TMDB discover failed: ${e.message}")
        emptyList()
    }

    // Fallback: if the recency window returned nothing (sparse
    // Indian providers), retry without the date filter so the row
    // isn't empty.
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
        } catch (e: Exception) {
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

// Language-based TMDB discover. Used by Hindi / Bangla rows as a
// silent fallback if JustWatch returns nothing.
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
    } catch (e: Exception) {
        BCLog.e("TMDB lang-discover failed: ${e.message}")
        emptyList()
    }
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
