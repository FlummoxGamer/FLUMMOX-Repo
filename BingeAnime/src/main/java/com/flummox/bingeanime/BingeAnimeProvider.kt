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
        *ROWS
            .filter { (_, label) -> BingeAnimeSettings.isRowEnabled(label) }
            .map { (data, label) -> data to label }
            .toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val rowName = request.name
        BLog.section("home: $rowName")

        // Mark home render so PrefetchEngine can skip the first click.
        PrefetchEngine.markHomeRender()

        // Home prefetch — ALWAYS on, never gated by the UI toggle.
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

        ShikimoriApi.rebuildExclusions()

        val filtered = entries.filter { entry ->
            val t = entry.title.romaji ?: entry.title.english ?: entry.title.native ?: ""
            if (t.isBlank()) true else !ShikimoriApi.isExcluded(rowName, t)
        }
        val dropped = entries.size - filtered.size
        BLog.d("row '$rowName' → ${filtered.size}${if (dropped > 0) " (dedup -$dropped)" else ""}")

        val items = filtered.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(rowName, items, hasNext = false)
    }

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
            PrefetchEngine.beginSession(q.animeKey())
            // Background ep-1 prefetch (skipped if just landed from home).
            PrefetchEngine.warmOnLoad(q) { scrapeAllSources(it) }
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

        // Warm ep 1 in background — will be joined by the tap on ep 1
        // because PrefetchEngine.obtain dedupes via in-flight map.
        val ep1 = StreamQuery(name, yearInt?.toString() ?: "", "series", url,
            season = 1, episode = 1, totalEpisodes = totalEps)
        PrefetchEngine.beginSession(ep1.animeKey())
        PrefetchEngine.warmOnLoad(ep1) { scrapeAllSources(it) }

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
        val q = decodeQuery(data) ?: return false
        BLog.section("loadLinks: ${q.title} (${q.year}) ${q.type} S${q.season}E${q.episode}")

        val key = q.cacheKey()
        val mirrors = try {
            PrefetchEngine.obtain(key) { scrapeAllSources(q) } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("scrapeAllSources threw: ${e.message}")
            emptyList()
        }

        if (mirrors.isEmpty()) { BLog.e("loadLinks: no mirrors"); return false }

        // LinkScore sort — best first, emoji prefix.
        val scored = mirrors
            .map { it to LinkScore.prelimScore(it) }
            .sortedByDescending { it.second }

        var emitted = 0
        val emittedSubs = mutableSetOf<String>()
        for ((m, score) in scored) {
            val emoji = LinkScore.emoji(score)
            val linkType = when {
                m.url.contains(".mpd", true) || m.url.contains("/mpd", true) ->
                    ExtractorLinkType.DASH
                m.url.contains(".m3u8", true) || m.url.contains("/m3u8", true) ->
                    ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
            try {
                callback.invoke(newExtractorLink(
                    source = "BingeAnime",
                    name = "$emoji ${m.source} • ${m.mirror}",
                    url = m.url,
                    type = linkType
                ) {
                    this.referer = AnikageApi.referer()
                    val clean = m.headers
                        ?.filterKeys { it.lowercase() != "referer" }
                        ?.toMutableMap() ?: mutableMapOf()
                    if (clean.keys.none { it.equals("user-agent", true) }) {
                        clean["User-Agent"] = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 " +
                            "Mobile Safari/537.36"
                    }
                    this.headers = clean
                })
                emitted++
            } catch (e: Exception) {
                BLog.e("emit failed: ${m.mirror}: ${e.message}")
                continue
            }
            m.captions.forEach { (label, url) ->
                if (emittedSubs.add(url)) {
                    try { subtitleCallback(SubtitleFile(label, url)) }
                    catch (_: Exception) {}
                }
            }
        }

        BLog.d("loadLinks: emitted=$emitted subs=${emittedSubs.size}")

        // Record play + warm next episode in background.
        PrefetchEngine.recordPlay(q)
        PrefetchEngine.warmAfterPlay(q) { scrapeAllSources(it) }

        return emitted > 0
    }

    private fun AniListApi.Entry.toSearchResponse(): SearchResponse? {
        val raw = title.english ?: title.romaji ?: title.native ?: return null
        val displayName = AniListApi.convertRomanSeasons(AniListApi.stripCourBranding(raw))
        val tvType = if (format == "MOVIE") TvType.Movie else TvType.Anime
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
