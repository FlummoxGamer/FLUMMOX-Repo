package com.flummox.bingeanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async

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

    // ── home: AniList batch primary, MAL fallback ──
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val rowName = request.name
        BLog.section("home: $rowName")

        val specs = ROWS.map { (data, label) ->
            val parts = data.split(ROW_SEP)
            val sort = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "TRENDING_DESC"
            val genre = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            val tag = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            val format = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            val country = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
            val status = parts.getOrNull(5)?.takeIf { it.isNotBlank() }
            val yearMarker = parts.getOrNull(6)?.toIntOrNull() ?: 0
            val year = if (yearMarker <= 0) null
                else java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - (yearMarker - 1)
            AniListApi.CatalogSpec(
                key = label, sort = sort, genre = genre, tag = tag,
                format = format, country = country, status = status, year = year
            )
        }

        val batch = try {
            AniListApi.fetchCatalogBatch(specs)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            BLog.e("home batch failed: ${e.message}")
            emptyMap()
        }

        var entries = batch[rowName] ?: emptyList()
        if (entries.isEmpty()) {
            BLog.d("home row '$rowName' empty from AniList — trying MAL")
            entries = malFallbackForRow(rowName, request.data)
        }
        BLog.d("row '$rowName' → ${entries.size}")
        val items = entries.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(rowName, items, hasNext = items.size >= 30)
    }

    // MAL fallback: map AniList row config to closest MAL ranking type.
    // MAL ranking cannot filter by genre, so genre/tag rows return empty
    // and re-populate on the next AniList refresh.
    private suspend fun malFallbackForRow(rowName: String, data: String): List<AniListApi.Entry> {
    val parts = data.split(ROW_SEP)
    val sort = parts.getOrNull(0) ?: ""
    val genre = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
    val tag = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
    val format = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
    val country = parts.getOrNull(4)?.takeIf { it.isNotBlank() }

    // MAL ranking has no genre, tag, or country filter. Returning
    // generic bypopularity for those rows would show wrong content
    // (e.g. Japanese anime under "Donghua"). Leave empty — the row
    // repopulates on the next AniList refresh.
    if (genre != null || tag != null || country != null) return emptyList()

        return when {
            format == "TV" && sort == "SCORE_DESC" -> MalApi.ranking("tv")
            format == "MOVIE" && sort == "SCORE_DESC" -> MalApi.ranking("movie")
            sort == "TRENDING_DESC" -> MalApi.ranking("bypopularity")
            sort == "POPULARITY_DESC" -> MalApi.ranking("bypopularity")
            else -> MalApi.ranking("all")
        }
    }

    // ── search: MAL + AniList in parallel, merged ──
override suspend fun search(query: String): List<SearchResponse>? {
    BLog.section("search: $query")

    val baseTitle = query.substringBefore(':').trim()

    // MAL — query original + base title (if different). MAL's search
    // matches on title substring, so "Mushoku Tensei: Jobless
    // Reincarnation" misses the S2/S3 entries titled "Mushoku Tensei
    // II: ...". Base-title fallback catches them.
    val malDeferred = kotlinx.coroutines.coroutineScope {
        val a = async { try { MalApi.search(query) } catch (e: CancellationException) { throw e
        } catch (e: Exception) { emptyList() } }
        val b = if (baseTitle.isNotBlank() && baseTitle.length >= 4 && baseTitle != query) {
            async { try { MalApi.search(baseTitle, limit = 30) } catch (e: CancellationException) { throw e
            } catch (e: Exception) { emptyList() } }
        } else null
        val merged = mutableListOf<AniListApi.Entry>()
        val seenMalIds = mutableSetOf<Int>()
        for (e in a.await()) if (seenMalIds.add(e.id)) merged.add(e)
        b?.await()?.let { for (e in it) if (seenMalIds.add(e.id)) merged.add(e) }
        merged
    }

    val ani = try { AniListApi.searchAnime(query) } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        BLog.e("AniList search threw: ${e.message}"); emptyList()
    }

    BLog.d("search '$query' → MAL=${malDeferred.size} AniList=${ani.size}")

    // Merge: AniList entries win on collision (richer data), MAL
    // entries that AniList didn't cover are kept. Dedupe by
    // mergeKey (normalized title + format).
    val seen = mutableSetOf<String>()
    val merged = mutableListOf<AniListApi.Entry>()
    for (e in ani) {
        val k = AniListApi.mergeKey(e)
        if (k.isNotBlank() && seen.add(k)) merged.add(e)
    }
    for (e in malDeferred) {
        val k = AniListApi.mergeKey(e)
        if (k.isNotBlank() && seen.add(k)) merged.add(e)
    }

    val sorted = AniListApi.sortChronological(merged, query)
    BLog.d("search '$query' → merged=${sorted.size}")
    return sorted.mapNotNull { it.toSearchResponse() }
}

    // ── load ──
    override suspend fun load(url: String): LoadResponse? {
        val malMatch = Regex("""mal:(\d+)""").find(url)
        val aniMatch = Regex("""anilist:(\d+)""").find(url)

        val entry = when {
            malMatch != null -> {
                val id = malMatch.groupValues[1].toIntOrNull() ?: return null
                BLog.section("load MAL: $id")
                MalApi.detail(id)
            }
            aniMatch != null -> {
                val id = aniMatch.groupValues[1].toIntOrNull() ?: return null
                BLog.section("load AniList: $id")
                AniListApi.getEntry(id)
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
            "RELEASING", "currently_airing" -> "Ongoing"
            "FINISHED", "finished_airing" -> "Completed"
            "NOT_YET_RELEASED", "not_yet_aired" -> "Upcoming"
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
