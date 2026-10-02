package com.flummox.bingeanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException

private const val ROW_SEP = "|"

// The data-column is unused by the current provider (each row's actual
// query lives in ShikimoriApi.ROW_QUERY). The label is what matters —
// it must match a ROW_QUERY key exactly, otherwise the row is skipped.
internal val ROWS: List<Pair<String, String>> = listOf(
    "TRENDING_DESC||||||0"              to "Trending",
    "SCORE_DESC|||TV|||0"               to "Top Anime Series",
    "SCORE_DESC|||MOVIE|||0"            to "Top Anime Movies",
    "POPULARITY_DESC||||CN||0"          to "Donghua",
    "POPULARITY_DESC|||||1"             to "Shounen",
    "POPULARITY_DESC|||||1"             to "Shoujo",
    "POPULARITY_DESC|||||1"             to "Seinen",
    "POPULARITY_DESC|||||1"             to "Josei",
    "POPULARITY_DESC|||||1"             to "Kids",
    "POPULARITY_DESC|||||1"             to "Ecchi",
    "POPULARITY_DESC|Action|||||1"      to "Action",
    "POPULARITY_DESC|Adventure|||||1"   to "Adventure",
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
    "POPULARITY_DESC||Historical||||1"  to "Historical",
    "POPULARITY_DESC|Horror|||||1"      to "Horror",
    "POPULARITY_DESC|Psychological|||||1" to "Psychological",
    "POPULARITY_DESC|Thriller|||||1"    to "Thriller"
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
            when (rowName) {
                "Donghua" -> AniMapperApi.donghua(
                    30,
                    yearFloor = BingeAnimeSettings.getYearFloorIfEnabled()
                )
                "Trending" -> AniMapperApi.trending(30).ifEmpty {
                    BLog.d("Trending AniMapper empty — falling back to Shikimori")
                    ShikimoriApi.fetchForRow(rowName, 30)
                }
                else -> ShikimoriApi.fetchForRow(rowName, 30)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("row '$rowName' failed: ${e.message}")
            emptyList()
        }
        // Rebuild dedup tables from currently cached rows. Cheap — reads
        // ~26 in-memory lists. Idempotent, safe per-row.
        ShikimoriApi.rebuildExclusions()

       // Drop titles already appearing in 2 higher-priority genre rows.
       // Dynamic rows (Trending/Top Series/Top Movies) are exempted via
       // DYNAMIC_ROWS inside rebuildExclusions.
       val filtered = entries.filter { entry ->
           val t = entry.title.romaji ?: entry.title.english ?: entry.title.native ?: ""
           if (t.isBlank()) true else !ShikimoriApi.isExcluded(rowName, t)
       }
       val dropped = entries.size - filtered.size
       BLog.d("row '$rowName' → ${filtered.size}${if (dropped > 0) " (dedup -$dropped)" else ""}")

       val items = filtered.mapNotNull { it.toSearchResponse() }
       return newHomePageResponse(rowName, items, hasNext = false)
    }

    // ── search: AniList only ──
    override suspend fun search(query: String): List<SearchResponse>? {
    BLog.section("search: $query")
    val useAsched = BingeAnimeSettings.getSearchSource() == "animeschedule"
    BLog.d("search source: ${if (useAsched) "AnimeSchedule" else "AniList"} (primary)")

    return if (useAsched) {
        val a = try { AnimeScheduleApi.search(query) } catch (e: CancellationException) { throw e
        } catch (e: Exception) { BLog.e("AnimeSchedule search threw: ${e.message}"); emptyList() }
        if (a.isNotEmpty()) {
            val sorted = AniListApi.filterAndSortChronological(a, query)
            BLog.d("search '$query' → AnimeSchedule=${sorted.size}")
            return sorted.mapNotNull { it.toSearchResponse() }
        }
        BLog.d("AnimeSchedule empty — falling back to AniList")
        val b = try { AniListApi.searchAnime(query) } catch (e: CancellationException) { throw e
        } catch (e: Exception) { BLog.e("AniList search threw: ${e.message}"); emptyList() }
        val sorted = AniListApi.sortChronological(b, query)
        BLog.d("search '$query' → AniList=${sorted.size}")
        sorted.mapNotNull { it.toSearchResponse() }
    } else {
        val b = try { AniListApi.searchAnime(query) } catch (e: CancellationException) { throw e
        } catch (e: Exception) { BLog.e("AniList search threw: ${e.message}"); emptyList() }
        if (b.isNotEmpty()) {
            val sorted = AniListApi.sortChronological(b, query)
            BLog.d("search '$query' → AniList=${sorted.size}")
            return sorted.mapNotNull { it.toSearchResponse() }
        }
        BLog.d("AniList empty — falling back to AnimeSchedule")
        val a = try { AnimeScheduleApi.search(query) } catch (e: CancellationException) { throw e
        } catch (e: Exception) { BLog.e("AnimeSchedule search threw: ${e.message}"); emptyList() }
        val sorted = AniListApi.filterAndSortChronological(a, query)
        BLog.d("search '$query' → AnimeSchedule=${sorted.size}")
        sorted.mapNotNull { it.toSearchResponse() }
    }
    }

    // ── load ──
    override suspend fun load(url: String): LoadResponse? {
        val shikiMatch = Regex("""shikimori:(\d+)""").find(url)
        val animapperMatch = Regex("""animapper:(\d+)""").find(url)
        val aniMatch = Regex("""anilist:(\d+)""").find(url)
        val malMatch = Regex("""mal:(\d+)""").find(url)
        val aschedMatch = Regex("""animeschedule:(.+)""").find(url)
 
        val entry = when {
            aschedMatch != null -> {
                val route = aschedMatch.groupValues[1]
                BLog.section("load animeschedule: $route")
                AnimeScheduleApi.detail(route)
            }
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
                this.backgroundPosterUrl = entry.bannerUrl
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
             this.backgroundPosterUrl = entry.bannerUrl
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
        // AnimeSchedule (and any future non-numeric source) carries its
        // route slug in sourceId. Numeric sources use the int id.
        val url = if (sourceId != null) "$source:$sourceId" else "$source:$id"
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
