package com.flummox.bingeanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException

private const val ROW_SEP = "|"

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

    override var mainUrl = "https://shikimori.one"
    override var name = "BingeAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage get() = mainPageOf(
        *ROWS.map { (data, label) -> data to label }.toTypedArray()
    )

    // ── home: Shikimori for 18 rows, AniMapper for Donghua ──
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val rowName = request.name
        BLog.section("home: $rowName")

        // Fire background prefetch — no-op if already warm or running.
        ShikimoriApi.warmPrefetch()

        val entries: List<AniListApi.Entry> = try {
            if (rowName == "Donghua") {
                AniMapperApi.donghua(30)
            } else {
                ShikimoriApi.fetchForRow(rowName, 30)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("row '$rowName' failed: ${e.message}")
            emptyList()
        }

        BLog.d("row '$rowName' → ${entries.size}")
        val items = entries.mapNotNull { it.toSearchResponse() }
        // hasNext must be false — CloudStream calls getMainPage(page=2,3,...)
        // when it's true, and we return the same 30 items each time, which
        // produces the infinite-scroll dupe behavior seen in testing.
        // Home rows are one-shot per refresh; pagination is not implemented.
        return newHomePageResponse(rowName, items, hasNext = false)
    }

    // ── search: AniList only ──
    override suspend fun search(query: String): List<SearchResponse>? {
        BLog.section("search: $query")
        val ani = try { AniListApi.searchAnime(query) } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            BLog.e("AniList search threw: ${e.message}"); emptyList()
        }
        val sorted = AniListApi.sortChronological(ani, query)
        BLog.d("search '$query' → AniList=${sorted.size}")
        return sorted.mapNotNull { it.toSearchResponse() }
    }

    // ── load ──
    override suspend fun load(url: String): LoadResponse? {
        val shikiMatch = Regex("""shikimori:(\d+)""").find(url)
        val animapperMatch = Regex("""animapper:(\d+)""").find(url)
        val aniMatch = Regex("""anilist:(\d+)""").find(url)
        val malMatch = Regex("""mal:(\d+)""").find(url)

        val entry = when {
            shikiMatch != null -> {
                val id = shikiMatch.groupValues[1].toIntOrNull() ?: return null
                BLog.section("load shikimori: $id")
                ShikimoriApi.detail(id)
            }
            animapperMatch != null -> {
                val id = animapperMatch.groupValues[1].toIntOrNull() ?: return null
                BLog.section("load animapper: $id")
                AniMapperApi.detail(id)
            }
            aniMatch != null -> {
                val id = aniMatch.groupValues[1].toIntOrNull() ?: return null
                BLog.section("load AniList: $id")
                AniListApi.getEntry(id)
            }
            malMatch != null -> {
                val id = malMatch.groupValues[1].toIntOrNull() ?: return null
                BLog.section("load MAL: $id")
                JikanApi.detail(id)
            }
            else -> return null
        } ?: return null

        val rawName = entry.title.english ?: entry.title.romaji ?: entry.title.native ?: return null
        val name = AniListApi.convertRomanSeasons(AniListApi.stripCourBranding(rawName))
        val isMovie = entry.format == "MOVIE"
        val totalEps = entry.episodes ?: 1
        val yearInt = entry.seasonYear
        val score10 = entry.averageScore?.let { it / 10.0 }

        val statusTag = when (entry.status) {
            "RELEASING", "currently_airing", "ongoing" -> "Ongoing"
            "FINISHED", "finished_airing", "released" -> "Completed"
            "NOT_YET_RELEASED", "not_yet_aired", "anons" -> "Upcoming"
            "CANCELLED" -> "Cancelled"
            "HIATUS" -> "On Hiatus"
            else -> ""
        }
        val plot = entry.description ?: ""
        val plotWithStatus = when {
            statusTag.isNotBlank() && plot.isNotBlank() -> "<b>$statusTag</b><br><br>$plot"
            statusTag.isNotBlank() -> "<b>$statusTag</b>"
            else -> plot
        }

        if (isMovie) {
            val q = StreamQuery(name, yearInt?.toString() ?: "", "movie", url)
            return newMovieLoadResponse(name, url, TvType.Movie, encodeQuery(q)) {
                this.posterUrl = entry.coverImage
                this.plot = plotWithStatus
                this.year = yearInt
                this.tags = entry.genres
                if (score10 != null) this.score = Score.from10(score10)
            }
        }

        val episodes = (1..totalEps).map { epNum ->
            val q = StreamQuery(name, yearInt?.toString() ?: "", "series", url,
                season = 1, episode = epNum, totalEpisodes = totalEps)
            newEpisode(encodeQuery(q)) {
                this.name = "Episode $epNum"
                this.season = 1
                this.episode = epNum
                this.posterUrl = entry.coverImage
            }
        }

        return newTvSeriesLoadResponse(name, url, TvType.Anime, episodes) {
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
        BLog.section("loadLinks (scraper phase pending)")
        return false
    }

    private fun AniListApi.Entry.toSearchResponse(): SearchResponse? {
        val raw = title.english ?: title.romaji ?: title.native ?: return null
        val displayName = AniListApi.convertRomanSeasons(AniListApi.stripCourBranding(raw))
        val tvType = if (format == "MOVIE") TvType.Movie else TvType.Anime
        val url = "$source:$id"
        return newMovieSearchResponse(displayName, url, tvType) {
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
    val sourceUrl: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val totalEpisodes: Int = 0
)

private fun encodeQuery(q: StreamQuery): String = org.json.JSONObject().apply {
    put("t", q.title); put("y", q.year); put("ty", q.type); put("u", q.sourceUrl)
    put("s", q.season); put("e", q.episode); put("te", q.totalEpisodes)
}.toString()

private fun decodeQuery(s: String): StreamQuery? = try {
    val o = org.json.JSONObject(s)
    StreamQuery(
        o.optString("t"), o.optString("y"), o.optString("ty", "series"),
        o.optString("u"), o.optInt("s", 0), o.optInt("e", 0), o.optInt("te", 0)
    )
} catch (_: Exception) { null }
