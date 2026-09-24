package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════
// ── AniList GraphQL client ──
// Anime-native metadata. Free, no auth, 90 req/min.
// Used for:
//   • title variants (romaji / english / native)
//   • split-cour relations (S1 → S2 → S3P1 → S3P2 chain)
//   • episode counts per cour
// All responses cached 24h in BCCache.
// ═══════════════════════════════════════════════════════════════

object AniListApi {
    private const val ENDPOINT = "https://graphql.anilist.co"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val CACHE_TTL = 24 * 60 * 60 * 1000L

    data class Title(val romaji: String?, val english: String?, val native: String?) {
        fun all(): List<String> = listOfNotNull(romaji, english, native)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    data class Entry(
    val id: Int,
    val idMal: Int?,
    val title: Title,
    val format: String?,       // TV, TV_SHORT, MOVIE, OVA, ONA, SPECIAL
    val episodes: Int?,
    val seasonYear: Int?,
    val startDate: String?,    // YYYY-MM-DD
    val description: String? = null,
    val coverImage: String? = null,
    val averageScore: Int? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    val relations: List<Relation> = emptyList()
)

    data class Relation(val type: String, val entry: Entry)

    // ── search ──
    // Full GraphQL search with relations preview on each hit.
    suspend fun searchAnime(query: String, year: Int? = null): List<Entry> {
        val ck = "anilist:s:${query.lowercase()}:${year ?: 0}"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseSearchResponse(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val mediaFields = """
          id
          idMal
          title { romaji english native }
          format
          episodes
          seasonYear
          startDate { year month day }
          description
          coverImage { extraLarge large }
          averageScore
          status
          genres
          relations {
            edges {
              relationType
              node {
                id
                idMal
                title { romaji english native }
                format
                episodes
                seasonYear
                startDate { year month day }
                description
                coverImage { extraLarge large }
                averageScore
                status
                genres
              }
            }
          }
""".trimIndent()

val q = if (year != null) """
    query (${'$'}search: String, ${'$'}year: Int) {
      Page(perPage: 10) {
        media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH, seasonYear: ${'$'}year) {
          $mediaFields
        }
      }
    }
""".trimIndent() else """
    query (${'$'}search: String) {
      Page(perPage: 10) {
        media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
          $mediaFields
        }
      }
    }
""".trimIndent()

        val vars = JSONObject().apply {
            put("search", query)
            if (year != null) put("year", year)
        }

        return try {
            val body = JSONObject().apply {
                put("query", q); put("variables", vars)
            }.toString()
            val res = app.post(
                ENDPOINT,
                requestBody = body.toRequestBody(JSON_MEDIA),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
            )
            val root = JSONObject(res.text)
            val media = root.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            val entries = mutableListOf<Entry>()
            if (media != null) {
                for (i in 0 until media.length()) {
                    parseEntry(media.optJSONObject(i))?.let { entries.add(it) }
                }
                BCCache.put(ck, root.toString())
           } else {
               val errs = root.optJSONArray("errors")
               if (errs != null && errs.length() > 0) {
               BCLog.e("AniList errors: ${errs.toString().take(500)}")
               }
           }
           BCLog.d("AniList: '$query' → ${entries.size} hits")
           entries
        } catch (e: Exception) {
            BCLog.e("AniList search failed: ${e.message}")
            emptyList()
        }
    }

    // ── single entry by ID ──
    suspend fun getEntry(anilistId: Int): Entry? {
        val ck = "anilist:e:$anilistId"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseEntry(JSONObject(cached).optJSONObject("data")?.optJSONObject("Media")) } catch (_: Exception) { null }
        }
        val q = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id
                idMal
                title { romaji english native }
                format
                episodes
                seasonYear
                startDate { year month day }
                description
                coverImage { extraLarge large }
                averageScore
                status
                genres
                relations {
                    edges {
                     relationType
                     node {
                     id
                     idMal
                     title { romaji english native }
                     format
                     episodes
                     seasonYear
                     startDate { year month day }
                     description
                     coverImage { extraLarge large }
                     averageScore
                     status
                     genres
                    }
                  }
                }
              }
            }
        """.trimIndent()

        return try {
            val body = JSONObject().apply {
                put("query", q)
                put("variables", JSONObject().put("id", anilistId))
            }.toString()
            val res = app.post(
                ENDPOINT,
                requestBody = body.toRequestBody(JSON_MEDIA),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
            )
            val root = JSONObject(res.text)
            val entry = parseEntry(root.optJSONObject("data")?.optJSONObject("Media"))
            if (entry != null) BCCache.put(ck, root.toString())
            entry
        } catch (e: Exception) {
            BCLog.e("AniList getEntry failed: ${e.message}")
            null
        }
    }

    // ── walk relations to full ordered chain ──
    // Returns entries ordered earliest → latest. S1, S2, S3P1, S3P2, S4...
    // Depth-capped at 20, loop-safe.
    suspend fun resolveChain(anilistId: Int): List<Entry> {
        val ck = "anilist:chain:$anilistId"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try {
                val arr = JSONArray(cached)
                (0 until arr.length()).mapNotNull { i -> parseEntry(arr.optJSONObject(i)) }
            } catch (_: Exception) { emptyList() }
        }

        // 1. Walk PREQUEL edges to find earliest entry
        val prequelVisited = mutableSetOf<Int>()
        var earliest = getEntry(anilistId) ?: return emptyList()
        prequelVisited.add(earliest.id)
        var guard = 0
        while (guard++ < 20) {
            val pre = earliest.relations.firstOrNull { it.type == "PREQUEL" }?.entry ?: break
            if (pre.id in prequelVisited) break
            val full = getEntry(pre.id) ?: pre
            prequelVisited.add(full.id)
            earliest = full
        }

        // 2. Walk SEQUEL edges forward to build the chain
        val chain = mutableListOf<Entry>()
        val visited = mutableSetOf<Int>()
        var current: Entry? = earliest
        guard = 0
        while (current != null && guard++ < 20) {
            if (!visited.add(current.id)) break
            chain.add(current)
            val seq = current.relations.firstOrNull { it.type == "SEQUEL" }?.entry ?: break
            current = if (seq.relations.isEmpty()) getEntry(seq.id) ?: seq else seq
        }

        // cache
        try {
            val arr = JSONArray()
            for (e in chain) arr.put(entryToJson(e))
            BCCache.put(ck, arr.toString())
        } catch (_: Exception) {}

        BCLog.d("AniList: chain from $anilistId → ${chain.size} entries: ${chain.map { it.title.romaji ?: it.title.english }}")
        return chain
    }

    // ── Part-N helpers ──
data class PartInfo(val partNum: Int, val seasonNum: Int?, val baseTitle: String)

private val RX_PART_N = Regex("""\bpart\s+(\d+)\b""", RegexOption.IGNORE_CASE)
private val RX_SEASON_N = Regex("""\bseason\s+(\d+)\b""", RegexOption.IGNORE_CASE)

fun parsePartInfo(title: String): PartInfo? {
    val partMatch = RX_PART_N.find(title) ?: return null
    val partNum = partMatch.groupValues[1].toIntOrNull() ?: return null
    if (partNum < 2) return null
    val seasonMatch = RX_SEASON_N.find(title)
    val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
    val baseTitle = title.replace(RX_PART_N, "")
        .replace(Regex("""\s+"""), " ").trim().trimEnd(':','-','–','—',' ')
    return PartInfo(partNum, seasonNum, baseTitle)
}

private fun normalizedBase(s: String): String =
    s.lowercase()
     .replace(RX_PART_N, "")
     .replace(Regex("""[^a-z0-9\s]"""), " ")
     .replace(Regex("""\s+"""), " ")
     .trim()

// Sum episodes of same-season prequels. For AoT S3P2 → S3P1's 12 eps.
// For Final Season Part 2 → Final Season Part 1's 16 eps.
suspend fun getPrequelOffset(title: String, year: Int?): Int? {
    var hits = try { searchAnime(title, year) } catch (_: Exception) { emptyList() }
    if (hits.isEmpty() && year != null) {
        hits = try { searchAnime(title, null) } catch (_: Exception) { emptyList() }
    }
    if (hits.isEmpty()) return null
    val target = hits.first()
    val targetBase = normalizedBase(target.title.romaji ?: target.title.english ?: return null)
    if (targetBase.isBlank()) return null

    val chain = resolveChain(target.id)
    if (chain.size < 2) return null
    val idx = chain.indexOfFirst { it.id == target.id }
    if (idx <= 0) return null

    var sum = 0
    for (i in 0 until idx) {
        val pre = chain[i]
        val preBase = normalizedBase(pre.title.romaji ?: pre.title.english ?: "")
        if (preBase == targetBase) sum += pre.episodes ?: 0
    }
    return if (sum > 0) sum else null
}

    // ── JSON parsing ──
    private fun parseSearchResponse(root: JSONObject): List<Entry> {
        val media = root.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
        return (0 until media.length()).mapNotNull { parseEntry(media.optJSONObject(it)) }
    }

    private fun parseEntry(o: JSONObject?): Entry? {
        if (o == null) return null
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val tl = o.optJSONObject("title")
        val title = Title(
            romaji = tl?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" },
            english = tl?.optString("english")?.takeIf { it.isNotBlank() && it != "null" },
            native = tl?.optString("native")?.takeIf { it.isNotBlank() && it != "null" }
        )
        val sd = o.optJSONObject("startDate")
        val startDate = if (sd != null) {
            val y = sd.optInt("year", 0)
            val m = sd.optInt("month", 0)
            val d = sd.optInt("day", 0)
            if (y > 0) "%04d-%02d-%02d".format(y, m.coerceAtLeast(1), d.coerceAtLeast(1)) else null
        } else null

        val relations = mutableListOf<Relation>()
        o.optJSONObject("relations")?.optJSONArray("edges")?.let { edges ->
            for (i in 0 until edges.length()) {
                val e = edges.optJSONObject(i) ?: continue
                val rt = e.optString("relationType").takeIf { it.isNotBlank() } ?: continue
                val node = parseEntry(e.optJSONObject("node")) ?: continue
                relations.add(Relation(rt, node))
            }
        }

        val coverImage = o.optJSONObject("coverImage")?.let { c ->
            c.optString("extraLarge").takeIf { it.isNotBlank() && it != "null" }
                ?: c.optString("large").takeIf { it.isNotBlank() && it != "null" }
        }
        val description = o.optString("description")
    .takeIf { it.isNotBlank() && it != "null" }
val averageScore = o.optInt("averageScore", 0).takeIf { it > 0 }
val status = o.optString("status").takeIf { it.isNotBlank() && it != "null" }
val genres = o.optJSONArray("genres")?.let { arr ->
    (0 until arr.length()).mapNotNull { i ->
        arr.optString(i).takeIf { it.isNotBlank() }
    }
}?.takeIf { it.isNotEmpty() }

 return Entry(
     id = id,
     idMal = o.optInt("idMal", 0).takeIf { it > 0 },
     title = title,
     format = o.optString("format")?.takeIf { it.isNotBlank() && it != "null" },
     episodes = o.optInt("episodes", 0).takeIf { it > 0 },
     seasonYear = o.optInt("seasonYear", 0).takeIf { it > 0 },
     startDate = startDate,
     description = description,
     coverImage = coverImage,
     averageScore = averageScore,
     status = status,
     genres = genres,
     relations = relations
  )
    }

    private fun entryToJson(e: Entry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("idMal", e.idMal ?: 0)
        put("title", JSONObject().apply {
            put("romaji", e.title.romaji ?: "")
            put("english", e.title.english ?: "")
            put("native", e.title.native ?: "")
        })
        put("format", e.format ?: "")
        put("episodes", e.episodes ?: 0)
        put("seasonYear", e.seasonYear ?: 0)
        put("startDate", e.startDate ?: "")
        put("description", e.description ?: "")
        put("coverImage", e.coverImage ?: "")
        put("averageScore", e.averageScore ?: 0)
        put("status", e.status ?: "")
        put("genres", JSONArray().apply {
            e.genres?.forEach { g -> this.put(g) }
        })
        // relations omitted in cache — not needed post-chain
            }
        }
