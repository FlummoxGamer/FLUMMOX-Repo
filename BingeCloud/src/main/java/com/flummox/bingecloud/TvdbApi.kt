package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

// ═══════════════════════════════════════════════════════════════
// ── TVDB v4 client ──
// Auth cached 25 days. Country + genre filter for language rows.
// English titles preferred, native in brackets.
// ═══════════════════════════════════════════════════════════════

private const val TVDB_ENDPOINT = "https://api4.thetvdb.com/v4"
private val TVDB_JSON = "application/json; charset=utf-8".toMediaType()

private val COUNTRY_ISO3 = mapOf(
    "hi" to "ind", "bn" to "bgd", "ko" to "kor",
    "ta" to "ind", "te" to "ind", "ja" to "jpn",
    "zh" to "chn", "ml" to "ind", "kn" to "ind"
)

private val LANG_ISO3 = mapOf(
    "hi" to "hin", "bn" to "ben", "ko" to "kor",
    "ta" to "tam", "te" to "tel", "ja" to "jpn",
    "zh" to "zho", "ml" to "mal", "kn" to "kan"
)

private const val ENRICH_CAP = 30

object TvdbAuth {
    private var memoryToken: String? = null
    private var memoryExpiry: Long = 0L

    suspend fun getToken(): String? {
        val now = System.currentTimeMillis()
        memoryToken?.let { if (memoryExpiry > now) return it }

        val stored = Settings.getTvdbToken()
        val storedExp = Settings.getTvdbTokenExp()
        if (!stored.isNullOrBlank() && storedExp > now) {
            memoryToken = stored
            memoryExpiry = storedExp
            return stored
        }
        return login()
    }

    private suspend fun login(): String? {
        val key = BuildConfig.TVDB_API_KEY
        if (key.isBlank()) {
            BCLog.e("[TVDB] no API key in BuildConfig")
            return null
        }
        return try {
            val body = JSONObject().apply { put("apikey", key) }.toString()
            val res = app.post(
                "$TVDB_ENDPOINT/login",
                requestBody = body.toRequestBody(TVDB_JSON),
                headers = mapOf("Content-Type" to "application/json")
            )
            val token = JSONObject(res.text).optJSONObject("data")
                ?.optString("token")?.takeIf { it.isNotBlank() }
            if (token != null) {
                val exp = System.currentTimeMillis() + 25L * 24 * 60 * 60 * 1000
                memoryToken = token
                memoryExpiry = exp
                Settings.saveTvdbToken(token, exp)
                BCLog.d("[TVDB] auth ok")
            } else {
                BCLog.e("[TVDB] login no token: ${res.text.take(200)}")
            }
            token
        } catch (e: Exception) {
            BCLog.e("[TVDB] login failed: ${e.message}")
            null
        }
    }
}

private suspend fun tvdbFetch(
    type: String,
    country: String?,
    titleLang: String,
    token: String,
    genreId: Int? = null
): List<AioMeta> {
    val path = if (type == "movies") "movies" else "series"
    val countryParam = country?.let { "&country=$it" } ?: ""
    val genreParam = genreId?.let { "&genre=$it" } ?: ""
    val url = "$TVDB_ENDPOINT/$path/filter?sort=score&sortType=desc&page=0$countryParam&lang=$titleLang$genreParam"

    return try {
        val res = app.get(
            url,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/json"
            )
        )
        val data = JSONObject(res.text).optJSONArray("data") ?: return emptyList()

        val out = mutableListOf<AioMeta>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0).takeIf { it > 0 } ?: continue
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue

            val imageRaw = o.optString("image").takeIf { it.isNotBlank() && it != "null" }
            val poster = imageRaw?.let {
                when {
                    it.startsWith("http") -> it
                    it.startsWith("//") -> "https:$it"
                    else -> "https://artworks.thetvdb.com$it"
                }
            }

            val firstAired = o.optString("firstAired")
            val year = firstAired.take(4)
                .takeIf { it.length == 4 && it.all { c -> c.isDigit() } }

            out.add(
                AioMeta(
                    id = "tvdb:$id",
                    name = name,
                    type = if (type == "movies") "movie" else "series",
                    poster = poster,
                    releaseInfo = year,
                    year = year
                )
            )
        }
        out
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BCLog.e("[TVDB] fetch failed: ${e.message}")
        emptyList()
    }
}

suspend fun tvdbDiscover(
    type: String,
    langCode: String?,
    limit: Int = 30,
    genreIds: List<Int> = emptyList()
): List<AioMeta> {
    val token = TvdbAuth.getToken() ?: return emptyList()
    val country = langCode?.let { COUNTRY_ISO3[it] }
    val nativeLang = langCode?.let { LANG_ISO3[it] } ?: "eng"

    // If genre IDs specified, fan out across them and merge.
    // TVDB treats multiple genre params as AND, so we can't pass all at once.
    val english: List<AioMeta> = if (genreIds.isEmpty()) {
        tvdbFetch(type, country, "eng", token)
    } else {
        genreIds.flatMap { tvdbFetch(type, country, "eng", token, it) }
    }

    val nativeMap: Map<String, String> = if (nativeLang != "eng") {
        val native: List<AioMeta> = if (genreIds.isEmpty()) {
            tvdbFetch(type, country, nativeLang, token)
        } else {
            genreIds.flatMap { tvdbFetch(type, country, nativeLang, token, it) }
        }
        native.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val name = item.name ?: return@mapNotNull null
            id to name
        }.toMap()
    } else emptyMap()

    val seen = mutableSetOf<String>()
    val out = mutableListOf<AioMeta>()
    for (item in english) {
        val id = item.id ?: continue
        if (!seen.add(id)) continue
        if (out.size >= limit) break

        val native = nativeMap[id]
        val englishName = item.name ?: continue
        val displayName = if (native != null && native != englishName) {
            "$englishName ($native)"
        } else englishName

        out.add(item.copy(name = displayName))
    }

    val sample = out.take(3).mapNotNull { it.name?.substringBefore(" (") }
    BCLog.d("[TVDB] $type/${langCode ?: "all"} genres=${genreIds.size} → ${out.size} sample=${sample.joinToString(" | ")}")
    return out
}

suspend fun tvdbEnrich(items: List<AioMeta>): List<AioMeta> = coroutineScope {
    val toEnrich = items.take(ENRICH_CAP)
    val rest = items.drop(ENRICH_CAP)

    val enriched = toEnrich.map { item ->
        async {
            val name = item.name?.substringBefore(" (")?.trim()
            if (name.isNullOrBlank()) return@async item

            val searchType = if (item.type == "movie") "movie" else "series"
            val cacheKey = "tvdbEnrich:${searchType}:${name.lowercase()}"
            val cached = BCCache.get(cacheKey, 30 * 60 * 1000L)

            var resolvedId: String? = null
            var resolvedPoster: String? = item.poster

            if (cached != null) {
                val parts = cached.split("||", limit = 2)
                resolvedId = parts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "NONE" }
                if (resolvedPoster.isNullOrBlank()) {
                    resolvedPoster = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                }
            } else {
                // Aiometa first — primary metadata source
                try {
                    val hit = aioSearch(name, searchType).firstOrNull { it.id?.startsWith("tmdb:") == true }
                    if (hit != null) {
                        resolvedId = hit.id
                        if (resolvedPoster.isNullOrBlank()) resolvedPoster = hit.poster
                    }
                } catch (_: Exception) {}

                // TMDB backup if Aiometa missed
                if (resolvedId == null) {
                    try {
                        val key = BuildConfig.TMDB_API_KEY
                        if (key.isNotBlank()) {
                            val encoded = URLEncoder.encode(name, "UTF-8")
                            val tmdbType = if (item.type == "movie") "movie" else "tv"
                            val url = "https://api.themoviedb.org/3/search/$tmdbType?api_key=$key&query=$encoded&page=1"
                            val json = app.get(url).text
                            val parsed = tryParseJson<TmdbDiscoverResponse>(json)
                            val hit = parsed?.results?.firstOrNull()
                            if (hit?.id != null) {
                                resolvedId = "tmdb:${hit.id}"
                                if (resolvedPoster.isNullOrBlank()) {
                                    resolvedPoster = hit.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                BCCache.put(cacheKey, "${resolvedId ?: "NONE"}||${resolvedPoster ?: ""}")
            }

            if (resolvedId != null) {
                item.copy(id = resolvedId, poster = resolvedPoster ?: item.poster)
            } else {
                item
            }
        }
    }.awaitAll()

    enriched + rest
}
