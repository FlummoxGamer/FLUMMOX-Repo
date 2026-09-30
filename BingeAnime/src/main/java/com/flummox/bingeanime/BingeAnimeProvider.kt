package com.flummox.bingeanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException

private const val ROW_SEP = "|"

// ── home rows ──
// data format: "sort|genre|tag|format|country|status|year"
// "year" = 0 → no filter, 1 → current year, N → N years back
//
// Rows overlap was happening because POPULARITY_DESC + genre returns
// the same mainstream top-30 for every genre (FMA:B is both Action
// and Adventure, etc). Fixed by filtering genre rows to CURRENT
// YEAR — recent-popular Action is genuinely distinct from
// recent-popular Romance. Genre rows re-order every January when
// the current year ticks over.
internal val ROWS: List<Pair<String, String>> = listOf(
    "TRENDING_DESC||||||0"              to "Trending",
    "SCORE_DESC|||TV|||0"               to "Top Anime Series",
    "SCORE_DESC|||MOVIE|||0"            to "Top Anime Movies",
    "POPULARITY_DESC||||CN||0"          to "Donghua",
    "POPULARITY_DESC|Action|||||1"      to "Action",
    "POPULARITY_DESC|Adventure|||||1"   to "Adventure",
    "POPULARITY_DESC||Isekai||||1"      to "Isekai",
    "POPULARITY_DESC|Comedy|||||1"      to "Comedy",
    "POPULARITY_DESC|Drama|||||1"       to "Drama",
    "POPULARITY_DESC|Fantasy|||||1"     to "Fantasy",
    "POPULARITY_DESC|Romance|||||1"     to "Romance",
    "POPULARITY_DESC|Sci-Fi|||||1"      to "Sci-Fi",
    "POPULARITY_DESC|Slice of Life|||||1" to "Slice of Life",
    "POPULARITY_DESC|Supernatural|||||1"  to "Supernatural",
    "POPULARITY_DESC|Mystery|||||1"     to "Mystery",
    "POPULARITY_DESC|Sports|||||1"      to "Sports",
    "POPULARITY_DESC|Mecha|||||1"       to "Mecha",
    "POPULARITY_DESC||School||||1"      to "School",
    "POPULARITY_DESC||Historical||||1"  to "Historical"
)

class BingeAnimeProvider : MainAPI() {

    override var mainUrl = "https://graphql.anilist.co"
    override var name = "BingeAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)


    override val mainPage get() = mainPageOf(
        *ROWS.map { (data, label) -> data to label }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val parts = request.data.split(ROW_SEP)
        if (parts.size < 7) return null
        val sort = parts[0].takeIf { it.isNotBlank() } ?: "TRENDING_DESC"
        val genre = parts[1].takeIf { it.isNotBlank() }
        val tag = parts[2].takeIf { it.isNotBlank() }
        val format = parts[3].takeIf { it.isNotBlank() }
        val country = parts[4].takeIf { it.isNotBlank() }
        val status = parts[5].takeIf { it.isNotBlank() }
        val yearMarker = parts[6].toIntOrNull() ?: 0
        val year = when {
            yearMarker <= 0 -> null
            else -> java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - (yearMarker - 1)
        }

        BLog.section("home: ${request.name}")
        val entries = try {
            AniListApi.fetchCatalog(sort, genre, tag, format, country, status, year, page, 30)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            BLog.e("row '${request.name}' failed: ${e.message}")
            emptyList()
        }
        BLog.d("row '${request.name}' → ${entries.size}")

        val items = entries.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.size >= 30)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
    BLog.section("search: $query")
    val entries = try { AniListApi.searchAnime(query) } catch (e: Exception) {
        BLog.e("search failed: ${e.message}"); emptyList()
    }
    // Sort chronologically: TV/ONA first (S1 → S1P2 → S2 ...), then
    // Movie, OVA, Special, Short. Groups entries from the same
    // franchise together even when AniList returns them in random
    // SEARCH_MATCH order.
    val sorted = AniListApi.sortChronological(entries, query)
    BLog.d("search '$query' → ${sorted.size}")
    return sorted.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""anilist:(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        BLog.section("load: $id")
        val entry = try { AniListApi.getEntry(id) } catch (e: Exception) {
            BLog.e("load failed: ${e.message}"); null
        } ?: return null

        val rawName = entry.title.english ?: entry.title.romaji ?: entry.title.native ?: return null
        val name = AniListApi.convertRomanSeasons(AniListApi.stripCourBranding(rawName))
        val isMovie = entry.format == "MOVIE"
        val totalEps = entry.episodes ?: 1
        val yearInt = entry.seasonYear
        val score10 = entry.averageScore?.let { it / 10.0 }
        val statusTag = when (entry.status) {
            "RELEASING" -> "Ongoing"
            "FINISHED" -> "Completed"
            "NOT_YET_RELEASED" -> "Upcoming"
            "CANCELLED" -> "Cancelled"
            "HIATUS" -> "On Hiatus"
            else -> ""
        }
        val plot = entry.description ?: ""
        val plotWithStatus = if (statusTag.isNotBlank() && plot.isNotBlank()) "<b>$statusTag</b><br><br>$plot"
            else if (statusTag.isNotBlank()) "<b>$statusTag</b>" else plot

        if (isMovie) {
            val q = StreamQuery(name, yearInt?.toString() ?: "", "movie", "anilist:$id")
            return newMovieLoadResponse(name, "/anilist:$id", TvType.Movie, encodeQuery(q)) {
                this.posterUrl = entry.coverImage
                this.plot = plotWithStatus
                this.year = yearInt
                this.tags = entry.genres
                if (score10 != null) this.score = Score.from10(score10)
            }
        }

        val episodes = (1..totalEps).map { epNum ->
            val q = StreamQuery(name, yearInt?.toString() ?: "", "series", "anilist:$id",
                season = 1, episode = epNum, totalEpisodes = totalEps)
            newEpisode(encodeQuery(q)) {
                this.name = "Episode $epNum"
                this.season = 1
                this.episode = epNum
                this.posterUrl = entry.coverImage
            }
        }

        return newTvSeriesLoadResponse(name, "/anilist:$id", TvType.Anime, episodes) {
            this.posterUrl = entry.coverImage
            this.plot = plotWithStatus
            this.year = yearInt
            this.tags = entry.genres
            if (score10 != null) this.score = Score.from10(score10)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        BLog.section("loadLinks (phase 1 stub)")
        return false
    }

    private fun AniListApi.Entry.toSearchResponse(): SearchResponse? {
    val raw = title.english ?: title.romaji ?: title.native ?: return null
    // 1. Strip "Cour N" branding (Cour 1 → dropped, Cour N≥2 → "Part N")
    // 2. Convert roman numerals in the season slot (III → Season 3)
    val displayName = AniListApi.convertRomanSeasons(AniListApi.stripCourBranding(raw))
    val tvType = if (format == "MOVIE") TvType.Movie else TvType.Anime
    return newMovieSearchResponse(displayName, "/anilist:$id", tvType) {
        this.posterUrl = coverImage
        this.year = seasonYear
    }
    }
}

// ── query serialization ──
data class StreamQuery(
    val title: String,
    val year: String,
    val type: String,
    val sourceId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val totalEpisodes: Int = 0
)

private fun encodeQuery(q: StreamQuery): String = org.json.JSONObject().apply {
    put("t", q.title); put("y", q.year); put("ty", q.type); put("sid", q.sourceId)
    put("s", q.season); put("e", q.episode); put("te", q.totalEpisodes)
}.toString()

private fun decodeQuery(s: String): StreamQuery? = try {
    val o = org.json.JSONObject(s)
    StreamQuery(
        o.optString("t"), o.optString("y"), o.optString("ty", "series"),
        o.optString("sid"), o.optInt("s", 0), o.optInt("e", 0), o.optInt("te", 0)
    )
} catch (_: Exception) { null }
