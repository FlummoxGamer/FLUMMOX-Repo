package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object AniListApi {
    private const val ENDPOINT = "https://graphql.anilist.co"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val CACHE_TTL = 6 * 60 * 60 * 1000L

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

// Flattened on purpose. R8 8.13.6's Kotlin-metadata rewriter crashes
// on mutually recursive data classes (Entry → List<Relation> →
// Entry → ...). This shape stores only the fields the chain walk
// needs, so the cycle is broken. Do not add an `entry: Entry` field
// back here — the CI will fail the same way.
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

        val vars = JSONObject().apply {
            put("page", page)
            put("perPage", perPage)
            put("sort", org.json.JSONArray().put(sort))
            if (genre != null) put("genre", genre)
            if (tag != null) put("tag", tag)
            if (format != null) put("format", format)
            if (country != null) put("country", country)
            if (status != null) put("status", status)
            if (year != null) put("year", year)
        }

        return try {
            val body = JSONObject().apply { put("query", q); put("variables", vars) }.toString()
            val res = app.post(ENDPOINT,
                requestBody = body.toRequestBody(JSON_MEDIA),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseList(root)
        } catch (e: Exception) {
            BLog.e("AniList catalog failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseList(root: JSONObject): List<Entry> {
        val arr = root.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
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
            if (year != null) put("year", year)
        }

        return try {
            val body = JSONObject().apply { put("query", q); put("variables", vars) }.toString()
            val res = app.post(ENDPOINT,
                requestBody = body.toRequestBody(JSON_MEDIA),
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
            val root = JSONObject(res.text)
            BCCache.put(ck, root.toString())
            parseList(root)
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
        } catch (e: Exception) {
            BLog.e("AniList getEntry failed: ${e.message}")
            null
        }
    }

    // ── Part-N helpers (used in later phases) ──
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

    // ── display helpers ──
    private val RX_COUR_N = Regex("""\bcour\s+(\d+)\b""", RegexOption.IGNORE_CASE)
    private val RX_ROMAN_WORD = Regex("""\b(II|III|IV|V|VI|VII|VIII|IX|X)\b""", RegexOption.IGNORE_CASE)
    private val ROMAN_MAP = mapOf(
        "II" to 2, "III" to 3, "IV" to 4, "V" to 5,
        "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10
    )
    // Strip "Cour N" branding from display titles.
    //   "X Cour 1" → "X"
    //   "X Cour 2" → "X Part 2"
    fun stripCourBranding(title: String): String {
        val m = RX_COUR_N.find(title) ?: return title
        val n = m.groupValues[1].toIntOrNull() ?: return title
        val replaced = if (n <= 1) title.replace(RX_COUR_N, "")
            else title.replace(RX_COUR_N, "Part $n")
        return replaced.replace(Regex("""\s{2,}"""), " ").trim()
    }

// Key used to group entries from the same franchise together.
// Strips season/part/cour markers, punctuation, casing.
// Franchise key for grouping. Two entries with the same key belong
// to the same franchise and get sorted together.
//
//   "Mushoku Tensei III: Isekai Ittara Honki Dasu Part 2"
//   "Mushoku Tensei: Jobless Reincarnation Cour 2"
//   "Mushoku Tensei II: Isekai Ittara Honki Dasu"
//   → all collapse to "mushoku tensei"
//
// Steps: lowercase → keep only text before first :/–/— → strip
// roman numerals → strip Season/Part/Cour markers → sanitize.
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

// Rewrite roman-numeral season markers to "Season N" for display.
// Only touches numerals that appear BEFORE the first colon/dash, so
// subtitles like "Code Geass: Lelouch of the Rebellion R2" are left
// alone. "Mushoku Tensei III: ..." → "Mushoku Tensei Season 3: ...".
fun convertRomanSeasons(title: String): String {
    val colonIdx = title.indexOf(':')
    val searchEnd = if (colonIdx > 0) colonIdx else title.length
    val head = title.substring(0, searchEnd)
    val m = RX_ROMAN_WORD.find(head) ?: return title
    val num = ROMAN_MAP[m.groupValues[1].uppercase()] ?: return title
    return head.replaceRange(m.range, "Season $num") + title.substring(searchEnd)
}

    // Format ordering: TV first, then ONA, Movie, OVA, Special, Short.
    // Matches user intent: "after [seasons], movies, ova, extras and all".
    fun formatPriority(format: String?): Int = when (format) {
        "TV" -> 0
        "ONA" -> 1
        "MOVIE" -> 2
        "OVA" -> 3
        "SPECIAL" -> 4
        "TV_SHORT" -> 5
        else -> 99
    }

// Chronological sort within a franchise:
// baseTitleKey → formatPriority → seasonYear → startDate
// Relevance tier vs the user query:
//   0 = exact title match
//   1 = title starts with query
//   2 = title contains query
//   3 = fuzzy / unrelated (AniList's SEARCH_MATCH picks these up)
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

// Sort order:
//   1. relevanceRank vs query
//   2. base-key first-occurrence index in AniList's returned list
//      (respects SEARCH_MATCH ordering; fixes "Attack on Skytree"
//       beating "Attack on Titan" for query "aot")
//   3. format priority within franchise (TV → ONA → Movie → OVA → Special)
//   4. seasonYear, then startDate (chronological within franchise)
private fun titleOf(e: Entry): String =
    e.title.english ?: e.title.romaji ?: e.title.native ?: ""

// Entries whose titles contain any of these are treated as
// side content, regardless of format. Keeps spinoffs from
// interleaving with main-series seasons.
private val SPINOFF_MARKERS = listOf(
    "junior high", "spin-off", "spinoff", "picture drama",
    "recap", "compilation", "no regrets", "vigilantes",
    "before the fall", "manner movie", "ova special"
)

private fun isSpinoff(e: Entry): Boolean {
    val t = titleOf(e).lowercase()
    return SPINOFF_MARKERS.any { t.contains(it) }
}

// TV → ONA → Movie → OVA → Special → Short → unknown.
// Spinoffs forced to 100 so they sort after every format tier.
private fun effectiveFormatPriority(e: Entry): Int {
    if (isSpinoff(e)) return 100
    return formatPriority(e.format)
}

// Season number from title. Season N → N. Roman II..X → 2..10.
// "Final Season" → 99 (last). No marker → 1.
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

// Part number from title. "Part N" or "Cour N" → N. Else 1.
private fun partOrdinal(e: Entry): Int {
    val t = titleOf(e).lowercase()
    Regex("""\bpart\s+(\d+)\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    Regex("""\bcour\s+(\d+)\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return 1
}

// Sort:
//   1. relevanceRank vs query
//   2. base-key first-occurrence index (respects SEARCH_MATCH order)
//   3. format priority (TV → ONA → Movie → OVA → Special → spinoff)
//   4. season number  (S1 < S2 < ... < Final)
//   5. part number    (S3P1 < S3P2)
//   6. year, then date (tie-break)
fun sortChronological(entries: List<Entry>, query: String): List<Entry> {
    val baseIndex = mutableMapOf<String, Int>()
    for ((i, e) in entries.withIndex()) {
        val k = baseTitleKey(titleOf(e))
        if (k.isNotBlank() && k !in baseIndex) baseIndex[k] = i
    }
    return entries.sortedWith(
        compareBy(
            { relevanceRank(it, query) },
            { baseIndex[baseTitleKey(titleOf(it))] ?: 9999 },
            { effectiveFormatPriority(it) },
            { seasonOrdinal(it) },
            { partOrdinal(it) },
            { it.seasonYear ?: 9999 },
            { it.startDate ?: "9999-99-99" }
        )
    )
}
}
