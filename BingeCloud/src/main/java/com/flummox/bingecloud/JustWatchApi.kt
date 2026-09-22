package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════
// ── JustWatch GraphQL client ──
// Public endpoint used by justwatch.com/in. Reverse-engineered
// schema. country=IN, language=en. Filters by provider package
// slug or by original language.
// ═══════════════════════════════════════════════════════════════

private const val JW_ENDPOINT = "https://apis.justwatch.com/graphql"
private val JW_MEDIA = "application/json; charset=utf-8".toMediaType()

private val JW_QUERY = """
query GetPopularTitles(${'$'}popularTitlesFilter: TitleFilter, ${'$'}country: Country!, ${'$'}language: Language!, ${'$'}first: Int!) {
  popularTitles(country: ${'$'}country, filter: ${'$'}popularTitlesFilter, first: ${'$'}first, sortBy: POPULAR) {
    edges {
      node {
        id
        objectType
        content(country: ${'$'}country, language: ${'$'}language) {
          title
          originalReleaseYear
          shortDescription
          posterUrl(profile: S718)
          genres {
            shortName
        }
          externalIds {
            tmdbId
          }
        }
      }
    }
  }
}
""".trimIndent()

suspend fun jwDiscoverByProvider(
    slug: String,
    objectType: String? = null,
    limit: Int = 30
): List<AioMeta> {
    val filter = JSONObject().apply {
        put("packages", JSONArray().put(slug))
    }
    return jwFetch(filter, objectType, limit)
}

suspend fun jwDiscoverByLanguage(
    lang: String,
    objectType: String? = null,
    limit: Int = 30
): List<AioMeta> {
    val filter = JSONObject().apply {
        put("originalLanguages", JSONArray().put(lang))
    }
    return jwFetch(filter, objectType, limit)
}

private suspend fun jwFetch(
    filter: JSONObject,
    objectType: String?,
    limit: Int
): List<AioMeta> {
    return try {
        val body = JSONObject().apply {
            put("query", JW_QUERY)
            put("variables", JSONObject().apply {
                put("popularTitlesFilter", filter)
                put("country", "IN")
                put("language", "en")
                put("first", limit)
            })
        }.toString()

        val res = app.post(
            JW_ENDPOINT,
            requestBody = body.toRequestBody(JW_MEDIA),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        )

        val root = JSONObject(res.text)
        val edges = root.optJSONObject("data")
            ?.optJSONObject("popularTitles")
            ?.optJSONArray("edges")
            ?: run {
                val err = root.optJSONArray("errors")
                if (err != null && err.length() > 0) {
                BCLog.e("[JW] graphql error: ${err.optJSONObject(0)?.toString()?.take(600)}")
                }
                return emptyList()
            }

        val out = mutableListOf<AioMeta>()
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            val objType = node.optString("objectType", "").uppercase()
            if (objectType != null && objType != objectType) continue

            val content = node.optJSONObject("content") ?: continue
            val title = content.optString("title").takeIf { it.isNotBlank() } ?: continue
            val year = content.optInt("originalReleaseYear", 0).takeIf { it > 0 }

            val tmdbId = content.optJSONObject("externalIds")
                ?.optString("tmdbId")
                ?.takeIf { it.isNotBlank() && it != "null" }
           // Skip anything without a TMDB ID. JustWatch-only IDs
           // (jw:xxx) can't be resolved by the metadata backend, and
           // produce the "inside page error". Better to drop them.
           val finalId = tmdbId?.let { "tmdb:$it" } ?: continue

           val posterRaw = content.optString("posterUrl").takeIf { it.isNotBlank() }
           val poster = posterRaw?.let {
               when {
                   it.startsWith("http") -> it
                   it.startsWith("//") -> "https:$it"
                   it.startsWith("/") -> "https://images.justwatch.com$it"
                   else -> "https://images.justwatch.com/$it"
               }
           }

val genresArr = content.optJSONArray("genres")
val genres = mutableListOf<String>()
if (genresArr != null) {
    for (j in 0 until genresArr.length()) {
        val g = genresArr.optJSONObject(j)?.optString("shortName")?.takeIf { it.isNotBlank() }
        if (g != null) genres.add(g)
    }
}
            val type = when (objType) {
                "MOVIE" -> "movie"
                "SHOW" -> "series"
                else -> "movie"
            }

            out.add(
                AioMeta(
                    id = finalId,
                    name = title,
                    type = type,
                    description = content.optString("shortDescription").takeIf { it.isNotBlank() },
                    poster = poster,
                    genres = genres.takeIf { it.isNotEmpty() },
                    releaseInfo = year?.toString(),
                    year = year?.toString()
                 )
             )
        }

        BCLog.d("[JW] ${objectType ?: "ALL"} filter=${filter.keys().asSequence().joinToString(",")} → ${out.size}")
        out
    } catch (e: Exception) {
        BCLog.e("[JW] fetch failed: ${e.message}")
        emptyList()
    }
}
