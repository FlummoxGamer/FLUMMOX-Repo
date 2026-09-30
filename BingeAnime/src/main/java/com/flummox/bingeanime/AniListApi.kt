package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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
        val format: String?,
        val episodes: Int?,
        val seasonYear: Int?,
        val startDate: String?,
        val description: String? = null,
        val coverImage: String? = null,
        val averageScore: Int? = null,
        val status: String? = null,
        val genres: List<String>? = null,
        val country: String? = null,
        val relations: List<Relation> = emptyList()
    )

    data class Relation(
        val type: String,
        val entryId: Int,
        val entryTitle: Title,
        val entryFormat: String?,
        val entryEpisodes: Int?,
        val entrySeasonYear: Int?,
        val entryStartDate: String?
    )

    private val MEDIA_FIELDS = """
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
        countryOfOrigin
    """.trimIndent()

    private val MEDIA_FIELDS_WITH_RELATIONS = """
        $MEDIA_FIELDS
        relations {
          edges {
            relationType
            node {
              $MEDIA_FIELDS
            }
          }
        }
    """.trimIndent()

    // ── catalog rows ──
    suspend fun fetchCatalog(
        sort: String = "TRENDING_DESC",
        genre: String? = null,
        tag: String? = null,
        format: String? = null,
        country: String? = null,
        status: String? = null,
        year: Int? = null,
        page: Int = 1,
        perPage: Int = 30
    ): List<Entry> {
        val ck = "anilist:cat:$sort:$genre:$tag:$format:$country:$status:$year:$page:$perPage"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseList(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }

        val q = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort], ${'$'}genre: String, ${'$'}tag: String, ${'$'}format: MediaFormat, ${'$'}country: CountryCode, ${'$'}status: MediaStatus, ${'$'}year: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: ${'$'}sort, genre: ${'$'}genre, tag: ${'$'}tag, format: ${'$'}format, countryOfOrigin: ${'$'}country, status: ${'$'}status, seasonYear: ${'$'}year, isAdult: false) {
                  $MEDIA_FIELDS
                }
              }
            }
        """.trimIndent()

        // Every declared variable MUST be present in the payload.
        // AniList silently returns an empty data object if a declared
        // variable is missing — verified against the empty-home-rows
        // bug where 8 of 9 vars were being omitted when null.
        val vars = JSONObject().apply {
            put("page", page)
            put("perPage", perPage)
            put("sort", org.json.JSONArray().put(sort))
            put("genre", genre ?: JSONObject.NULL)
            put("tag", tag ?: JSONObject.NULL)
            put("format", format ?: JSONObject.NULL)
            put("country", country ?: JSONObject.NULL)
            put("status", status ?: JSONObject.NULL)
            put("year", year ?: JSONObject.NULL)
        }

        return try {
            val body = JSONObject().apply { put("query", q); put("variables", vars) }.toString()
            val res = app.post(ENDPOINT,
                requestBody = body.toRequestBody(JSON_MEDIA),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseList(root)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("AniList catalog failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseList(root: JSONObject): List<Entry> {
        val pageObj = root.optJSONObject("data")?.optJSONObject("Page")
        val arr = pageObj?.optJSONArray("media") ?: run {
            BLog.e("AniList parse empty — response: ${root.toString().take(400)}")
            return emptyList()
        }
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) parseEntry(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }

    // ── search ──
    suspend fun searchAnime(query: String, year: Int? = null): List<Entry> {
        val ck = "anilist:s:${query.lowercase()}:${year ?: 0}"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseList(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }

        val q = if (year != null) """
            query (${'$'}search: String, ${'$'}year: Int) {
              Page(perPage: 20) {
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH, seasonYear: ${'$'}year, isAdult: false) {
                  $MEDIA_FIELDS
                }
              }
            }
        """.trimIndent() else """
            query (${'$'}search: String) {
              Page(perPage: 20) {
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH, isAdult: false) {
                  $MEDIA_FIELDS
                }
              }
            }
        """.trimIndent()

        val vars = JSONObject().apply {
            put("search", query)
            if (year != null) put("year", year) else put("year", JSONObject.NULL)
        }

        return try {
            val body = JSONObject().apply { put("query", q); put("variables", vars) }.toString()
            val res = app.post(ENDPOINT,
                requestBody = body.toRequestBody(JSON_MEDIA),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseList(root)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("AniList search failed: ${e.message}")
            emptyList()
        }
    }

    // ── getEntry (with relations) ──
    suspend fun getEntry(id: Int): Entry? {
        val ck = "anilist:e:$id"
        BCCache.get(ck, CACHE_TTL)?.let { cached ->
            return try { parseEntry(JSONObject(cached).optJSONObject("data")?.optJSONObject("Media")) } catch (_: Exception) { null }
        }
        val q = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                $MEDIA_FIELDS_WITH_RELATIONS
              }
            }
        """.trimIndent()
        return try {
            val body = JSONObject().apply {
                put("query", q); put("variables", JSONObject().put("id", id))
            }.toString()
            val res = app.post(ENDPOINT,
                requestBody = body.toRequestBody(JSON_MEDIA),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
            val root = JSONObject(res.text)
            val entry = parseEntry(root.optJSONObject("data")?.optJSONObject("Media"))
            if (entry != null) BCCache.put(ck, root.toString())
            entry
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("AniList getEntry failed: ${e.message}")
            null
        }
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
            .replace(Regex("""\s+"""), " ").trim().trimEnd(':', '-', '–', '—', ' ')
        return PartInfo(partNum, seasonNum, baseTitle)
    }

    // ── display helpers ──
    private val RX_COUR_N = Regex("""\bcour\s+(\d+)\b""", RegexOption.IGNORE_CASE)
    private val RX_ROMAN_WORD = Regex("""\b(II|III|IV|V|VI|VII|VIII|IX|X)\b""", RegexOption.IGNORE_CASE)
    private val ROMAN_MAP = mapOf(
        "II" to 2, "III" to 3, "IV" to 4, "V" to 5,
        "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10
    )

    fun stripCourBranding(title: String): String {
        val m = RX_COUR_N.find(title) ?: return title
        val n = m.groupValues[1].toIntOrNull() ?: return title
        val replaced = if (n <= 1) title.replace(RX_COUR_N, "")
            else title.replace(RX_COUR_N, "Part $n")
        return replaced.replace(Regex("""\s{2,}"""), " ").trim()
    }

    fun baseTitleKey(title: String): String {
        var s = title.lowercase()
        s = s.substringBefore(':').substringBefore('–').substringBefore('—')
        s = RX_ROMAN_WORD.replace(s, "")
        s = RX_PART_N.replace(s, "")
        s = RX_SEASON_N.replace(s, "")
        s = RX_COUR_N.replace(s, "")
        s = s.replace(Regex("""[^a-z0-9 ]"""), " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s
    }

    fun convertRomanSeasons(title: String): String {
        val colonIdx = title.indexOf(':')
        val searchEnd = if (colonIdx > 0) colonIdx else title.length
        val head = title.substring(0, searchEnd)
        val m = RX_ROMAN_WORD.find(head) ?: return title
        val num = ROMAN_MAP[m.groupValues[1].uppercase()] ?: return title
        return head.replaceRange(m.range, "Season $num") + title.substring(searchEnd)
    }

    fun formatPriority(format: String?): Int = when (format) {
        "TV" -> 0
        "ONA" -> 1
        "MOVIE" -> 2
        "OVA" -> 3
        "SPECIAL" -> 4
        "TV_SHORT" -> 5
        else -> 99
    }

    private fun relevanceRank(e: Entry, query: String): Int {
        val q = query.lowercase().trim()
        if (q.isBlank()) return 3
        val titles = listOfNotNull(e.title.english, e.title.romaji, e.title.native)
            .map { it.lowercase().trim() }
        return when {
            titles.any { it == q } -> 0
            titles.any { it.startsWith(q) } -> 1
            titles.any { it.contains(q) } -> 2
            else -> 3
        }
    }

    private fun titleOf(e: Entry): String =
        e.title.english ?: e.title.romaji ?: e.title.native ?: ""

    private val SPINOFF_MARKERS = listOf(
        "junior high", "spin-off", "spinoff", "picture drama",
        "recap", "compilation", "no regrets", "vigilantes",
        "before the fall", "manner movie", "ova special"
    )

    private fun isSpinoff(e: Entry): Boolean {
        val t = titleOf(e).lowercase()
        return SPINOFF_MARKERS.any { t.contains(it) }
    }

    private fun effectiveFormatPriority(e: Entry): Int {
        if (isSpinoff(e)) return 100
        return formatPriority(e.format)
    }

    private fun seasonOrdinal(e: Entry): Int {
        val t = titleOf(e).lowercase()
        Regex("""\bseason\s+(\d+)\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val romans = listOf("ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x")
        for ((i, r) in romans.withIndex()) {
            if (Regex("""\b$r\b""").containsMatchIn(t)) return i + 2
        }
        if (t.contains("final season")) return 99
        return 1
    }

    private fun partOrdinal(e: Entry): Int {
        val t = titleOf(e).lowercase()
        Regex("""\bpart\s+(\d+)\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("""\bcour\s+(\d+)\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 1
    }

    fun sortChronological(entries: List<Entry>, query: String): List<Entry> {
        return entries.sortedWith(
            compareBy(
                { relevanceRank(it, query) },
                { effectiveFormatPriority(it) },
                { seasonOrdinal(it) },
                { partOrdinal(it) },
                { it.seasonYear ?: 9999 },
                { it.startDate ?: "9999-99-99" }
            )
        )
    }

    // ── parse ──
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
                val node = e.optJSONObject("node") ?: continue
                val nodeId = node.optInt("id", 0).takeIf { it > 0 } ?: continue
                val nodeTitleObj = node.optJSONObject("title")
                val nodeTitle = Title(
                    romaji = nodeTitleObj?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" },
                    english = nodeTitleObj?.optString("english")?.takeIf { it.isNotBlank() && it != "null" },
                    native = nodeTitleObj?.optString("native")?.takeIf { it.isNotBlank() && it != "null" }
                )
                val sdNode = node.optJSONObject("startDate")
                val nodeStart = if (sdNode != null) {
                    val y = sdNode.optInt("year", 0)
                    if (y > 0) "%04d-%02d-%02d".format(
                        y,
                        sdNode.optInt("month", 0).coerceAtLeast(1),
                        sdNode.optInt("day", 0).coerceAtLeast(1)
                    ) else null
                } else null
                relations.add(Relation(
                    type = rt,
                    entryId = nodeId,
                    entryTitle = nodeTitle,
                    entryFormat = node.optString("format").takeIf { it.isNotBlank() && it != "null" },
                    entryEpisodes = node.optInt("episodes", 0).takeIf { it > 0 },
                    entrySeasonYear = node.optInt("seasonYear", 0).takeIf { it > 0 },
                    entryStartDate = nodeStart
                ))
            }
        }

        val cover = o.optJSONObject("coverImage")?.let { c ->
            c.optString("extraLarge").takeIf { it.isNotBlank() && it != "null" }
                ?: c.optString("large").takeIf { it.isNotBlank() && it != "null" }
        }
        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        }?.takeIf { it.isNotEmpty() }

        return Entry(
            id = id,
            idMal = o.optInt("idMal", 0).takeIf { it > 0 },
            title = title,
            format = o.optString("format").takeIf { it.isNotBlank() && it != "null" },
            episodes = o.optInt("episodes", 0).takeIf { it > 0 },
            seasonYear = o.optInt("seasonYear", 0).takeIf { it > 0 },
            startDate = startDate,
            description = o.optString("description").takeIf { it.isNotBlank() && it != "null" },
            coverImage = cover,
            averageScore = o.optInt("averageScore", 0).takeIf { it > 0 },
            status = o.optString("status").takeIf { it.isNotBlank() && it != "null" },
            genres = genres,
            country = o.optString("countryOfOrigin").takeIf { it.isNotBlank() && it != "null" },
            relations = relations
        )
    }
}
