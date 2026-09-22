package com.flummox.bingecloud

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

private const val SEP = "|"
private const val ROW_TAG = "::"
private const val PREFETCH_DEBOUNCE_MS = 800L

private val PREFETCH_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private var activePrefetchJob: Job? = null
private var lastHomeRenderMs: Long = 0L
private const val HOME_GRACE_MS = 5000L

fun StreamQuery.cacheKey(): String =
    "scrape:${title.lowercase()}:${year}:${type}:${season}:${episode}"

// ═══════════════════════════════════════════════════════════════
// ── adult content filter ──
// Silent blocklist applied to search + home rows.
// ═══════════════════════════════════════════════════════════════
private val ADULT_TERMS = setOf(
    "porn", "porno", "xxx", "erotic", "erotica",
    "nude", "nudes", "sex tape", "18+", "[18+]",
    "hotshots", "ullu", "fliz", "gupchup", "lolypop"
)

private fun isAdultContent(title: String, genres: List<String>?): Boolean {
    val t = title.lowercase()
    if (ADULT_TERMS.any { t.contains(it) }) return true
    if (genres != null && genres.any { it.lowercase().trim() in setOf("erotic", "adult", "18+") }) return true
    return false
}

// ── home content filter: drop daily soaps / talk / reality ──
private val HOME_BLOCKED_GENRES = setOf(
    "soap", "talk", "talk show", "reality", "reality tv", "news", "game show"
)

private fun AioMeta.isJunk(): Boolean {
    val g = genres ?: return false
    return g.any { it.lowercase().trim() in HOME_BLOCKED_GENRES }
}

open class BingeCloudProvider : MainAPI() {
    override var mainUrl = AIOMETA_BASE
    override var name = "BingeCloud"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val mainPage get() = mainPageOf(
    *Settings.getRowOrder()
        .mapNotNull { key ->
            val spec = Settings.getRowSpecByKey(key) ?: return@mapNotNull null
            if (!Settings.isRowEnabled(key)) return@mapNotNull null
            val id = if (spec.defaultGenre != null)
                "${spec.catalogId}$ROW_TAG${spec.defaultGenre}"
            else spec.catalogId
            "${spec.type}$ROW_TAG$id$ROW_TAG${spec.name}" to spec.name
        }.toTypedArray()
)

    // ── home ──
override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
    val parts = request.data.split(ROW_TAG)
    if (parts.size < 2) return null
    lastHomeRenderMs = System.currentTimeMillis()
    val rowType = parts[0]
    val catalogId = parts[1]
    val raw = resolveRow(rowType, catalogId, page)
    val items = raw.filter { !it.isJunk() }.mapNotNull { it.toSearchResponse() }
    return newHomePageResponse(request.name, items, hasNext = raw.size >= 20)
}

private suspend fun resolveRow(rowType: String, catalogId: String, page: Int): List<AioMeta> {
    return when (catalogId) {
        // Western — Aiometa/TMDB base + JustWatch top-up on page 1
        "tmdb.provider.8"    -> routeWestern(rowType, "nfx", 8, page)
        "tmdb.provider.9"    -> routeWestern(rowType, "amazon-prime-video", 9, page)
        "tmdb.provider.350"  -> routeWestern(rowType, "apple-tv-plus", 350, page)

        // Western — TMDB direct (no IN presence on JustWatch)
        "tmdb.provider.1899" -> tmdbDiscoverMerged(1899, (page - 1) * 20)
        "tmdb.provider.337"  -> tmdbDiscoverMerged(337, (page - 1) * 20)

        // Indian — JustWatch primary + TMDB fill
        "tmdb.provider.122"  -> routeIndian(rowType, "jiohotstar", 122, page)
        "tmdb.provider.220"  -> routeIndian(rowType, "jio-cinema", 220, page)
        "tmdb.provider.237"  -> routeIndian(rowType, "sony-liv", 237, page)
        "tmdb.provider.232"  -> routeIndian(rowType, "zee5", 232, page)

        // Language rows — JustWatch primary, TMDB direct fallback
        "tmdb.language"      -> routeLanguage(rowType, "hi", page)
        "justwatch.bengali"  -> routeLanguage(rowType, "bn", page)

        // Everything else (TVDB, MAL anime, etc.) — Aiometa catalog
        else -> aioFetchCatalog(rowType, catalogId, null, (page - 1) * 25)
    }
}

private fun jwTypeFor(rowType: String): String? = when (rowType) {
    "movie" -> "MOVIE"
    "series" -> "SHOW"
    else -> null
}

private suspend fun routeWestern(
    rowType: String,
    jwSlug: String,
    tmdbProviderId: Int,
    page: Int
): List<AioMeta> {
    if (page > 1) return tmdbDiscoverMerged(tmdbProviderId, (page - 1) * 20)
    val base = tmdbDiscoverMerged(tmdbProviderId, 0)
    val jwList = jwDiscoverByProvider(jwSlug, jwTypeFor(rowType), 20)
    return (base + jwList).distinctBy { it.id }
}

private suspend fun routeIndian(
    rowType: String,
    jwSlug: String,
    tmdbProviderId: Int,
    page: Int
): List<AioMeta> {
    val jwList = jwDiscoverByProvider(jwSlug, jwTypeFor(rowType), 30)
    if (jwList.size >= 15) return jwList
    val tmdbFill = tmdbDiscoverMerged(tmdbProviderId, (page - 1) * 20)
    return (jwList + tmdbFill).distinctBy { it.id }
}

private suspend fun routeLanguage(
    rowType: String,
    langCode: String,
    page: Int
): List<AioMeta> {
    val jwList = jwDiscoverByLanguage(langCode, jwTypeFor(rowType), 30)
    if (jwList.isNotEmpty()) return jwList
    val tmdbType = if (rowType == "series") "tv" else "movie"
    return tmdbDiscoverByLanguage(tmdbType, langCode, (page - 1) * 20)
}

    // ── search ──
    override suspend fun search(query: String): List<SearchResponse>? {
    val key = BuildConfig.TMDB_API_KEY
    BCLog.v("TMDB key diag: len=${key.length} head=${key.take(6)} tail=${key.takeLast(4)}")
    if (key.isBlank()) {
        BCLog.e("TMDB key missing at runtime")
        return emptyList()
    }
    return searchViaTmdb(query, key)
    }

    private suspend fun searchViaTmdb(query: String, key: String): List<SearchResponse>? {
    val out = mutableListOf<SearchResponse>()
    try {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://api.themoviedb.org/3/search/multi" +
            "?api_key=$key&language=en-US&query=$encoded&page=1&include_adult=false"
        val res = app.get(url)
        val json = res.text
        BCLog.d("TMDB HTTP ${res.code}, body ${json.length} chars")
        if (res.code != 200) {
            BCLog.e("TMDB non-200: ${json.take(500)}")
            return out
        }
        val parsed = tryParseJson<TmdbSearchResponse>(json)
        if (parsed == null) {
            BCLog.e("TMDB parse null. head: ${json.take(300)}")
        } else {
            BCLog.d("TMDB parsed results=${parsed.results?.size ?: 0}")
        }
        parsed?.results?.forEach { item ->
            item.toSearchResponse()?.let { out.add(it) }
        }
    } catch (e: Exception) {
        BCLog.e("TMDB search failed: ${e.message}")
    }
    BCLog.d("TMDB search '$query': ${out.size} results")
    return out
    }

    private suspend fun searchViaAiometa(query: String): List<SearchResponse>? {
        val results = mutableListOf<SearchResponse>()
        for (t in listOf("movie", "series", "anime")) {
            try { results.addAll(aioSearch(query, t).mapNotNull { it.toSearchResponse() }) }
            catch (e: Exception) { BCLog.e("Search $t failed: ${e.message}") }
        }
        return results
    }

    private fun AioMeta.toSearchResponse(): SearchResponse? {
        val metaId = this.id ?: return null
        val metaName = this.name ?: return null
        val typeStr = this.type ?: "movie"
        val tvType = when {
            typeStr.contains("series", true) -> TvType.TvSeries
            typeStr.contains("anime", true) -> TvType.Anime
            else -> TvType.Movie
        }
        val yearInt = (this.releaseInfo ?: this.year)?.take(4)?.toIntOrNull()
        return newMovieSearchResponse(metaName, "/$typeStr$SEP$metaId", tvType) {
            this.posterUrl = this@toSearchResponse.poster
            this.year = yearInt
        }
    }

    private fun TmdbSearchItem.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        val mt = this.media_type ?: return null
        if (mt != "movie" && mt != "tv") return null
        val title = this.title ?: this.name ?: return null
        val isAnime = mt == "tv" &&
            original_language == "ja" &&
            (genre_ids?.contains(16) == true)
        val tvType = when {
            mt == "movie" -> TvType.Movie
            isAnime -> TvType.Anime
            else -> TvType.TvSeries
        }
        val loadType = if (mt == "movie") "movie" else "series"
        val metaId = "tmdb:$id"
        val year = (release_date ?: first_air_date)?.take(4)?.toIntOrNull()
        return newMovieSearchResponse(title, "/$loadType$SEP$metaId", tvType) {
            this.posterUrl = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            this.year = year
        }
    }

    // ── load ──
    override suspend fun load(url: String): LoadResponse? {
        val clean = url.removePrefix(mainUrl).removePrefix("/")
        val parts = clean.split(SEP)
        if (parts.size < 2) return null
        val type = parts[0]
        val metaId = parts[1]
        val meta = aioFetchMeta(type, metaId) ?: return null
        val name = meta.name ?: return null
        val tvType = when {
            type.contains("series", true) -> TvType.TvSeries
            type.contains("anime", true) -> TvType.Anime
            else -> TvType.Movie
        }
        val yearInt = (meta.releaseInfo ?: meta.year)?.take(4)?.toIntOrNull()
        val actors = meta.app_extras?.cast?.mapNotNull { c ->
            val n = c.name ?: return@mapNotNull null
            Actor(n, c.photo)
        } ?: emptyList()
        val videos = meta.videos ?: emptyList()
        val statusTag = computeStatusTag(meta, videos, tvType)
        val desc = meta.description ?: ""
        val plot = if (statusTag.isNotBlank() && desc.isNotBlank()) "<b>$statusTag</b><br><br>$desc"
            else if (statusTag.isNotBlank()) "<b>$statusTag</b>" else desc

        val sinceHome = System.currentTimeMillis() - lastHomeRenderMs
        val fromHomeBanner = sinceHome in 0 until HOME_GRACE_MS
        if (fromHomeBanner) {
            BCLog.d("load() preview (no prefetch): ${name.take(40)} [${sinceHome}ms since home]")
        }
        if (Settings.isPrefetchEnabled() && !fromHomeBanner) {
            val prefetchQuery: StreamQuery? = when {
                tvType == TvType.Movie && videos.isEmpty() ->
                    StreamQuery(name, yearInt?.toString() ?: "", "movie", meta.imdb_id ?: "")
                videos.isNotEmpty() -> {
                    val first = videos.firstOrNull()
                    val s = first?.season
                    val e = first?.episode
                    if (s != null && e != null && s > 0)
                        StreamQuery(name, yearInt?.toString() ?: "", "series", meta.imdb_id ?: "", s, e)
                    else null
                }
                else -> null
            }
            if (prefetchQuery != null) {
                val key = prefetchQuery.cacheKey()
                if (BCCache.getMirrors(key) == null) {
                    activePrefetchJob?.cancel()
                    activePrefetchJob = PREFETCH_SCOPE.launch {
                        try {
                            delay(PREFETCH_DEBOUNCE_MS)
                            if (BCCache.getMirrors(key) != null) return@launch
                            BCLog.d("smart prefetch: ${prefetchQuery.title} S${prefetchQuery.season}E${prefetchQuery.episode}")
                            val mirrors = scrapeAllSources(prefetchQuery)
                            BCCache.putMirrors(key, mirrors)
                            BCLog.d("smart prefetch done: ${mirrors.size} mirrors")
                        } catch (e: CancellationException) {
                            BCLog.d("smart prefetch cancelled")
                        } catch (e: Exception) {
                            BCLog.e("smart prefetch failed: ${e.message}")
                        }
                    }
                }
            }
        }

        return if (tvType == TvType.Movie && videos.isEmpty()) {
            val q = StreamQuery(name, yearInt?.toString() ?: "", "movie", meta.imdb_id ?: "")
            newMovieLoadResponse(name, url, TvType.Movie, encodeQuery(q)) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = plot
                this.year = yearInt
                this.tags = meta.genres
                this.score = Score.from10(meta.imdbRating)
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            val episodes = videos.mapIndexedNotNull { idx, v ->
                val s = v.season ?: return@mapIndexedNotNull null
                val e = v.episode ?: return@mapIndexedNotNull null
                val next = videos.getOrNull(idx + 1)
                val q = StreamQuery(
                    name, yearInt?.toString() ?: "", "series", meta.imdb_id ?: "",
                    s, e,
                    next?.season ?: 0, next?.episode ?: 0
                )
                newEpisode(encodeQuery(q)) {
                    this.name = v.title ?: "Episode $e"
                    this.season = s
                    this.episode = e
                    this.posterUrl = v.thumbnail ?: meta.background
                    this.description = v.overview
                }
            }
            val responseType = if (tvType == TvType.Anime) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(name, url, responseType, episodes) {
                this.posterUrl = meta.poster
                this.backgroundPosterUrl = meta.background
                this.plot = plot
                this.year = yearInt
                this.tags = meta.genres
                this.score = Score.from10(meta.imdbRating)
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    // ── loadLinks ──
    override suspend fun loadLinks(
    data: String, isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val query = decodeQuery(data) ?: return false
    BCLog.section("loadLinks: ${query.title} (${query.year}) ${query.type} S${query.season}E${query.episode}")

    val cached = BCCache.getMirrors(query.cacheKey())
    val mirrors = cached ?: scrapeAllSources(query)
    if (cached != null) BCLog.d("using smart prefetch cache: ${mirrors.size} mirrors")
    if (mirrors.isEmpty()) { BCLog.e("loadLinks: no mirrors"); return false }

    val smartSort = Settings.isPrefilterEnabled()
    val pref = Settings.getQualityPref()
    val prefRank = qualityRank(pref)

    val finalOrder: List<Pair<ScrapedMirror, Int>> = if (smartSort) {
        mirrors
            .map { it to LinkScore.prelimScore(it) }
            .sortedWith(
                compareByDescending<Pair<ScrapedMirror, Int>> {
                    if (prefRank > 0 && qualityRank(it.first.quality) == prefRank) 1 else 0
                }
                    .thenByDescending { it.second }
                    .thenByDescending { qualityRank(it.first.quality) }
                    .thenBy { audioPriority(it.first.mirror, it.first.source) }
            )
    } else {
        mirrors
            .map { it to 0 }
            .sortedWith(
                compareByDescending<Pair<ScrapedMirror, Int>> {
                    if (prefRank > 0 && qualityRank(it.first.quality) == prefRank) 1 else 0
                }
                    .thenByDescending { qualityRank(it.first.quality) }
            )
    }

    val concurrency = Settings.getConcurrency().coerceIn(1, 50)
    BCLog.d("resolving ${finalOrder.size} mirrors (c=$concurrency, smart=$smartSort, streaming=true)")

    val sem = Semaphore(concurrency)
    val hostSems = ConcurrentHashMap<String, Semaphore>()
    fun hostSem(host: String): Semaphore = hostSems.getOrPut(host) { Semaphore(5) }
    val emittedCount = java.util.concurrent.atomic.AtomicInteger(0)

    coroutineScope {
        finalOrder.forEach { (m, score) ->
            launch {
                sem.withPermit {
                    val host = hostOf(m.url).ifBlank { "unknown" }
                    hostSem(host).withPermit {
                        val emoji = if (smartSort) LinkScore.emoji(score) else ""
                        try {
                            when (m.source) {
                                "ANIKOTO" -> {
                                    var emitted = 0
                                    val hashM3u8 = anikotoGetHashM3u8(m.url)
                                    if (hashM3u8 != null) {
                                        callback.invoke(newExtractorLink("AniKoto", "$emoji${m.mirror}", hashM3u8, ExtractorLinkType.M3U8) {
                                            this.referer = "https://anikototv.to/"
                                        })
                                        emitted++
                                    } else {
                                        val domain = hostOf(m.url)
                                        val domainHost = "https://$domain"
                                        val isMegaFam = domain.contains("megaplay", true) ||
                                                domain.contains("vidwish", true) ||
                                                domain.contains("vidtube", true)
                                        if (isMegaFam) {
                                            try {
                                                anikotoExtractMegaPlayUrl(m.url, "https://anikototv.to/", domainHost, "$emoji${m.mirror}", subtitleCallback) { l -> callback.invoke(l); emitted++ }
                                            } catch (e: Exception) { BCLog.e("AniKoto resolve: ${e.message}") }
                                        } else {
                                            try { loadExtractor(m.url, "https://anikototv.to/", subtitleCallback) { l -> callback.invoke(l); emitted++ } } catch (_: Exception) {}
                                        }
                                    }
                                    if (emitted == 0) {
                                        HostHealth.recordFailure(host)
                                    } else {
                                        HostHealth.recordSuccess(host)
                                        emittedCount.addAndGet(emitted)
                                    }
                                }
                                "MB" -> {
                                    val linkType = when {
                                        m.url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                                        m.url.contains(".mpd", true) -> ExtractorLinkType.DASH
                                        else -> ExtractorLinkType.VIDEO
                                    }
                                    val display = "$emoji${m.quality} •MB ${m.mirror}"
                                    BCLog.d("MB link: $display (score=$score)")
                                    val hdrs = m.headers
                                    val link = newExtractorLink("MovieBox", display, m.url, linkType) {
                                        this.referer = "https://h5.aoneroom.com/"
                                    if (hdrs != null) this.headers = hdrs
                                    }
                                        callback.invoke(link)
                                        emittedCount.incrementAndGet()
                                        HostHealth.recordSuccess("mb.local")
                                        m.captions.forEach { (lang, subUrl) ->
                                    try { subtitleCallback(SubtitleFile(lang, subUrl)) } catch (_: Exception) {}
                                    }
                                }
                                    "SHOWBOX" -> {
                                        val linkType = when {
                                            m.url.contains(".mp4", true) -> ExtractorLinkType.VIDEO
                                            m.url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                                            m.url.contains(".mkv", true) -> ExtractorLinkType.VIDEO
                                            else -> ExtractorLinkType.VIDEO
                                        }
                                        val display = "$emoji${m.quality} •ShowBox"
                                         val link = newExtractorLink("ShowBox", display, m.url, linkType) {
                                             this.referer = "https://www.febbox.com/"
                                             val hdrs = mutableMapOf(
                                             "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                                             "Referer" to "https://www.febbox.com/"
                                             )
                                            val cookie = Settings.getFebBoxToken()
                                            if (cookie.isNotBlank()) {
                                            hdrs["Cookie"] = if (cookie.contains("=")) cookie else "ui=$cookie"
                                            }
                                            this.headers = hdrs
                                         }
                                       callback.invoke(link)
                                       emittedCount.incrementAndGet()
                                           HostHealth.recordSuccess("febbox.local")
                                      }
                                      "MLSBD" -> {
                                     // MLSBD already resolved to a direct stream URL from player.php
                                     // or a direct R2 download URL — emit it, don't try to resolve.
                                     val linkType = when {
                                         m.url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                                         m.url.contains(".mpd", true) -> ExtractorLinkType.DASH
                                         else -> ExtractorLinkType.VIDEO
                                    }
                                    val display = "$emoji${m.quality} •${m.mirror}"
                                    val ua = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                    BCLog.d("MLSBD link: $display")
                                    BCLog.d("MLSBD url: ${m.url}")
                                    callback.invoke(
                                        newExtractorLink("MLSBD", display, m.url, linkType) {
                                            this.referer = "https://new.multicloudlinks.com/"
                                            this.headers = mapOf(
                                                "User-Agent" to ua,
                                                "Referer" to "https://new.multicloudlinks.com/"
                                            )
                                        }
                                   )
                                   emittedCount.incrementAndGet()
                                   HostHealth.recordSuccess(host)
                                   }
                                    else -> {
                                    val finalUrl = resolveWrapper(m.url)
                                    if (finalUrl == null) {
                                        BCLog.d("unresolved: ${m.mirror}")
                                        HostHealth.recordFailure(host)
                                    } else {
                                        var emitted = 0
                                        VCloud(m.source, m.mirror, m.quality, emoji)
                                        .getUrl(finalUrl, "", subtitleCallback) { l ->
                                        if (m.source == "HDH" && l.name.startsWith("Unknown")) {
                                        BCLog.d("skip Unknown HDH: ${l.name}")
                                    } else {
                                        callback.invoke(l); emitted++
                                    }
                                 }
                                        if (emitted == 0) {
                                            HostHealth.recordFailure(host)
                                        } else {
                                            HostHealth.recordSuccess(host)
                                            emittedCount.addAndGet(emitted)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            BCLog.e("${m.mirror} failed: ${e.message}")
                            HostHealth.recordFailure(host)
                        }
                    }
                }
            }
        }
    }

    BCLog.d("loadLinks done (${emittedCount.get()} links, streamed)")

    if (Settings.isPrefetchEnabled() && query.type == "series"
        && query.nextSeason > 0 && query.nextEpisode > 0) {
        val nextQ = StreamQuery(
            query.title, query.year, "series", query.imdbId,
            query.nextSeason, query.nextEpisode
        )
        val nextKey = nextQ.cacheKey()
        if (BCCache.getMirrors(nextKey) == null) {
            activePrefetchJob?.cancel()
            activePrefetchJob = PREFETCH_SCOPE.launch {
                try {
                    BCLog.d("smart prefetch next: S${query.nextSeason}E${query.nextEpisode}")
                    val nextMirrors = scrapeAllSources(nextQ)
                    BCCache.putMirrors(nextKey, nextMirrors)
                    BCLog.d("smart prefetch next done: ${nextMirrors.size} mirrors")
                } catch (e: CancellationException) {
                    BCLog.d("smart prefetch next cancelled")
                } catch (e: Exception) {
                    BCLog.e("smart prefetch next failed: ${e.message}")
                }
            }
        }
    }
    return true
    }

    
    
    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: ""
    } catch (_: Exception) { "" }

    private fun qualityRank(q: String): Int = when {
        q.contains("2160", true) || q.contains("4k", true) -> 2160
        q.contains("1440", true) || q.contains("2k", true) -> 1440
        q.contains("1080", true) -> 1080
        q.contains("720", true) -> 720
        q.contains("480", true) -> 480
        q.contains("360", true) -> 360
        else -> 0
    }

    private fun audioPriority(mirror: String, source: String): Int {
        if (source != "MB") return 0
        val l = mirror.lowercase()
        return when {
            l.contains("original") -> 0
            l.contains("hindi") -> 1
            l.contains("english") -> 2
            l.contains("spanish") -> 5
            l.contains("portug") -> 6
            else -> 3
        }
    }

    private fun computeStatusTag(meta: AioMeta, videos: List<AioVideo>, tvType: TvType): String {
        if (tvType == TvType.Movie) return ""
        val rel = meta.releaseInfo ?: ""
        if (rel.endsWith("-")) return "Ongoing"
        if (rel.matches(Regex("""\d{4}-\d{4}"""))) return "Completed"
        val lastEp = videos.lastOrNull()
        if (lastEp?.available == false) return "Ongoing"
        val year = rel.take(4).toIntOrNull()
        val nowYear = Calendar.getInstance().get(Calendar.YEAR)
        if (year != null && year < nowYear - 1) return "Completed"
        return ""
    }
}

private fun encodeQuery(q: StreamQuery): String {
    val o = JSONObject()
    o.put("t", q.title); o.put("y", q.year); o.put("ty", q.type)
    o.put("s", q.season); o.put("e", q.episode); o.put("i", q.imdbId)
    o.put("ns", q.nextSeason); o.put("ne", q.nextEpisode)
    return o.toString()
}

private fun decodeQuery(s: String): StreamQuery? = try {
    val o = JSONObject(s)
    StreamQuery(
        o.optString("t"), o.optString("y"),
        o.optString("ty", "movie"), o.optString("i"),
        o.optInt("s", 0), o.optInt("e", 0),
        o.optInt("ns", 0), o.optInt("ne", 0)
    )
} catch (e: Exception) { null }

private data class TmdbSearchResponse(val results: List<TmdbSearchItem>? = null)
private data class TmdbSearchItem(
    val id: Int? = null,
    val media_type: String? = null,
    val title: String? = null,
    val name: String? = null,
    val poster_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val original_language: String? = null,
    val genre_ids: List<Int>? = null
)
