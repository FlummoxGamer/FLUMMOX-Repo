package com.flummox.otakutsu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class OtakutsuProvider : MainAPI() {
    companion object {
        private val sectionCache = mutableMapOf<String, Pair<Long, List<SearchResponse>>>()
        fun clearSectionCache() = synchronized(this) { sectionCache.clear() }
    }

    override var mainUrl = "https://otakutsu.cc"
    override var name = "Otakutsu"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private val NEXT_ACTION_ID_FALLBACK = "787faac6445fbc39cfe9376659cbfb5168c3f714b2"
    private var cachedActionId: String? = null
    private var playbackCookie: String = ""

    private val baseHeaders get() = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    private val browserHeaders get() = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to "$mainUrl/",
    )

    private fun playbackHeadersFn(): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put("Accept", "*/*")
        put("Accept-Language", "en-US,en;q=0.9")
        put("Referer", "$mainUrl/")
        put("sec-ch-ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"")
        put("sec-ch-ua-mobile", "?1")
        put("sec-ch-ua-platform", "\"Android\"")
        put("sec-fetch-dest", "empty")
        put("sec-fetch-mode", "cors")
        put("sec-fetch-site", "same-origin")
        if (playbackCookie.isNotBlank()) put("Cookie", playbackCookie)
    }

    private var homeHtml: String? = null
    private var homeTime: Long = 0L
    private var homeDoc: Document? = null
    private val HOME_TTL = 10 * 60 * 1000L

    private val PREFETCH_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activePrefetchJob: Job? = null
    private var lastHomeRenderMs: Long = 0L
    private val PREFETCH_DEBOUNCE_MS = 800L
    private val HOME_GRACE_MS = 5000L

    private suspend fun getHomeHtml(): String? {
        val now = System.currentTimeMillis()
        homeHtml?.let { if (now - homeTime < HOME_TTL) return it }
        return try {
            val html = app.get(mainUrl, headers = browserHeaders).text
            homeHtml = html
            homeTime = now
            homeDoc = null
            html
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OLog.e("getHomeHtml failed: ${e.message}")
            null
        }
    }

    private suspend fun getHomeDoc(): Document? {
        val now = System.currentTimeMillis()
        homeDoc?.let { if (now - homeTime < HOME_TTL) return it }
        val html = getHomeHtml() ?: return null
        return try {
            val doc = Jsoup.parse(html, mainUrl)
            homeDoc = doc
            doc
        } catch (e: Exception) {
            OLog.e("home doc parse failed: ${e.message}")
            null
        }
    }

    private fun resolveActionId(): String = cachedActionId ?: NEXT_ACTION_ID_FALLBACK

    override val mainPage = mainPageOf(
        "trending"   to "Top 10 Trending",
        "weekend"    to "Short & Complete",
        "movies"     to "Movies",
        "beyond"     to "Donghua",
        "underrated" to "Underrated",
        "classics"   to "Timeless Classics",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        lastHomeRenderMs = System.currentTimeMillis()

        val ck = "home:${request.data}:$page"
        synchronized(OtakutsuProvider::class.java) {
            sectionCache[ck]?.let { (t, cards) ->
                if (System.currentTimeMillis() - t < HOME_TTL) {
                    return newHomePageResponse(request.name, cards, hasNext = false)
                }
            }
        }

        OLog.section("mainPage: ${request.data}")
        val doc = getHomeDoc() ?: return null
        val section = doc.selectFirst("section#${request.data}")
        if (section == null) {
            OLog.e("section #${request.data} not found")
            return null
        }
        val cards = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in section.select("a[href^=/anime/]")) {
            val href = a.attr("href")
            val id = Regex("""/anime/((?:[a-f0-9]{24}|al-\d+))""").find(href)?.groupValues?.get(1) ?: continue
            if (!seen.add(id)) continue
            val title = a.selectFirst(".hc-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: continue
            val poster = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
            cards.add(newMovieSearchResponse(title, "$mainUrl/watch/$id?ep=1", TvType.Anime) {
                this.posterUrl = poster
            })
        }
        OLog.d("section #${request.data} → ${cards.size} cards")
        synchronized(OtakutsuProvider::class.java) {
            sectionCache[ck] = System.currentTimeMillis() to cards
        }
        return newHomePageResponse(request.name, cards, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        OLog.section("search: $query")
        val pref = OSettings.getTitleLang()
        OLog.d("search pref=$pref")
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$mainUrl/api/feed/search?query=$q&per_page=30&page=1&sort=popularity"
            OLog.v("search URL: $url")
            val json = app.get(url, headers = baseHeaders + mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Referer" to "$mainUrl/",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
            )).text
            OLog.d("search HTTP len=${json.length}")
            val root = JSONObject(json)
            val results = root.optJSONArray("results") ?: return emptyList()
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(id)) continue
                val titleObj = o.optJSONObject("title")
                val romaji = titleObj?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" }
                val english = titleObj?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
                val native = titleObj?.optString("native")?.takeIf { it.isNotBlank() && it != "null" }
                val chosen = when (pref) {
                    "romaji" -> romaji ?: english ?: native
                    "native" -> native ?: english ?: romaji
                    else -> english ?: romaji ?: native
                } ?: continue
                val poster = o.optJSONObject("cover_image")?.optString("large")
                    ?.takeIf { it.startsWith("http") }
                out.add(newMovieSearchResponse(chosen, "$mainUrl/watch/$id?ep=1", TvType.Anime) {
                    this.posterUrl = poster
                })
            }
            OLog.d("search '$query' → ${out.size} results")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OLog.e("search failed: ${e.message}")
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""/(?:watch|anime)/((?:[a-f0-9]{24}|al-\d+))""").find(url)?.groupValues?.get(1) ?: return null
        OLog.section("load: $id")

        val (animeHtml, watchHtml) = coroutineScope {
            val a = async { app.get("$mainUrl/anime/$id", headers = browserHeaders).text }
            val w = async { app.get("$mainUrl/watch/$id?ep=1", headers = browserHeaders).text }
            a.await() to w.await()
        }
        OLog.d("anime html len=${animeHtml.length}")
        OLog.d("watch html len=${watchHtml.length}")

        if (cachedActionId == null) {
            val m = Regex("""["']([a-f0-9]{40})["']""").find(watchHtml)
                ?: Regex("""\b([a-f0-9]{40})\b""").find(watchHtml)
            if (m != null) {
                OLog.d("action id from watch page: ${m.groupValues[1]}")
                cachedActionId = m.groupValues[1]
            } else {
                OLog.d("no 40-hex in watch html")
            }
        }

        val title = Regex("""<h1[^>]*>([^<]+)</h1>""")
            .find(animeHtml)?.groupValues?.get(1)?.trim()?.let {
                it.replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"")
            } ?: return null

        val poster = Regex("""<img[^>]+src="(https://s4\.anilist\.co/[^"]+)""")
            .find(animeHtml)?.groupValues?.get(1)

        val seasonNum = run {
            val m = Regex("""\b(?:season|s)\s*(\d+)\b""", RegexOption.IGNORE_CASE).find(title)
            m?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }
        OLog.d("parsed season=$seasonNum from title='$title'")

        val plot = Regex("""<p[^>]*class="[^"]*line-clamp-3[^"]*"[^>]*>([\s\S]*?)</p>""")
            .find(animeHtml)?.groupValues?.get(1)?.trim()?.let {
                it.replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"")
                    .replace(Regex("<[^>]+>"), "")
            }

        val year = run {
            val patterns = listOf(
                Regex("""</span>\s*<span[^>]*>\s*(19\d{2}|20\d{2})\s*</span>"""),
                Regex("""\bReleased?\s*[:\-]\s*(19\d{2}|20\d{2})\b""", RegexOption.IGNORE_CASE),
                Regex("""\b(19\d{2}|20\d{2})\b(?=[^<]*</span>)"""),
            )
            patterns.firstNotNullOfOrNull { rx ->
                rx.find(animeHtml)?.groupValues?.get(1)?.toIntOrNull()
            }
        }
        OLog.d("parsed year=$year")

        val score = run {
            val patterns = listOf(
                Regex("""★\s*</i>\s*(\d+(?:\.\d+)?)"""),
                Regex("""★\D{0,20}?(\d+(?:\.\d+)?)"""),
                Regex("""&#9733;\D{0,20}?(\d+(?:\.\d+)?)"""),
            )
            patterns.firstNotNullOfOrNull { rx ->
                rx.find(animeHtml)?.groupValues?.get(1)?.toDoubleOrNull()
            }
        }
        OLog.d("parsed score=$score")

        val baseGenres = Regex("""href="/browse\?genre=([^"]+)"""")
            .findAll(animeHtml)
            .map { it.groupValues[1].replace("%20", " ") }
            .distinct().take(8).toList()

        val statusLabels = listOf("Finished", "Ongoing", "Releasing", "Completed", "Upcoming", "Cancelled", "On Hiatus")
        val statusStr = statusLabels.firstOrNull { s ->
            Regex(""">\s*${Regex.escape(s)}\s*<""").containsMatchIn(animeHtml)
        }
        OLog.d("parsed status=$statusStr")

        val genres = if (statusStr != null) {
            (listOf(statusStr) + baseGenres).take(9)
        } else baseGenres

        val wDoc = Jsoup.parse(watchHtml, mainUrl)
        data class EpData(val num: Int, val name: String?, val thumb: String?)
        val epList = mutableListOf<EpData>()
        val epSeen = mutableSetOf<Int>()
        for (a in wDoc.select("a[href*='/watch/$id?ep=']")) {
            val href = a.attr("href")
            val epNum = Regex("""[?&]ep=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (!epSeen.add(epNum)) continue
            val thumb = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
            val epName = a.selectFirst("p.text-sm.font-medium")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }
            epList.add(EpData(epNum, epName, thumb))
        }
        epList.sortBy { it.num }
        OLog.d("episodes found=${epList.size} first=${epList.firstOrNull()?.num} last=${epList.lastOrNull()?.num}")

        if (epList.isEmpty()) {
            val q = JSONObject().apply {
                put("id", id); put("ep", 1); put("t", title)
                put("s", seasonNum); put("ne", 0)
            }.toString()
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, q) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres.takeIf { it.isNotEmpty() }
                if (score != null) this.score = Score.from10(score)
            }
        }

        val episodes = epList.mapIndexed { idx, e ->
            val next = epList.getOrNull(idx + 1)?.num ?: 0
            val q = JSONObject().apply {
                put("id", id); put("ep", e.num); put("t", title)
                put("s", seasonNum); put("ne", next)
            }.toString()
            newEpisode(q) {
                this.name = e.name ?: "Episode ${e.num}"
                this.episode = e.num
                this.season = seasonNum
                this.posterUrl = e.thumb ?: poster
            }
        }

        val sinceHome = System.currentTimeMillis() - lastHomeRenderMs
        val fromHome = lastHomeRenderMs > 0 && sinceHome in 0 until HOME_GRACE_MS
        if (OSettings.isPrefetchEnabled() && !fromHome && epList.isNotEmpty()) {
            schedulePrefetch(id, epList.first().num)
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres.takeIf { it.isNotEmpty() }
            if (score != null) this.score = Score.from10(score)
        }
    }

    private fun buildStateTree(animeId: String, ep: Int): String {
        val raw = """["",{"children":["watch",{"children":[["id","$animeId","d",null],{"children":[["ep","$ep","d",null],{"children":["__PAGE__",{},null,null,5120]}],null,null,5124]}],null,null,5128]}],null,null,5144]"""
        return URLEncoder.encode(raw, "UTF-8")
    }

    private fun schedulePrefetch(animeId: String, ep: Int) {
        val key = "$animeId:$ep"
        if (OCache.hasPrefetch(key)) return
        activePrefetchJob?.cancel()
        activePrefetchJob = PREFETCH_SCOPE.launch {
            try {
                delay(PREFETCH_DEBOUNCE_MS)
                if (OCache.hasPrefetch(key)) return@launch
                OLog.d("prefetch start: $animeId E$ep")
                val result = fetchChain(animeId, ep) ?: run {
                    OLog.d("prefetch: no result")
                    return@launch
                }
                OCache.putPrefetch(key, result)
                OLog.d("prefetch done: ${result.sources.size} sources")
            } catch (e: CancellationException) {
                OLog.v("prefetch cancelled")
            } catch (e: Exception) {
                OLog.e("prefetch failed: ${e.message}")
            }
        }
    }

    private suspend fun fetchChain(animeId: String, ep: Int): PrefetchCache? {
        val apiHeaders = baseHeaders + mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "sec-ch-ua" to "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "same-origin",
        )

        OLog.v("chain: bootstrap POST $animeId E$ep")
        val bootResp = app.post(
            "$mainUrl/api/media/bootstrap",
            headers = apiHeaders,
            requestBody = JSONObject()
                .put("animeId", animeId).put("ep", ep)
                .toString().toRequestBody("application/json".toMediaType())
        )
        val bootText = bootResp.text
        OLog.v("chain: bootstrap code=${bootResp.code} len=${bootText.length}")
        if (bootText.length < 100) OLog.e("bootstrap short: $bootText")
        val streamToken = JSONObject(bootText).optString("streamToken")
            .takeIf { it.isNotBlank() } ?: return null
        OLog.v("chain: token len=${streamToken.length}")

        val cookieJar = mutableListOf<String>()
        bootResp.headers.values("Set-Cookie").forEach { c ->
            c.substringBefore(";").trim().takeIf { it.contains("=") }?.let { cookieJar.add(it) }
        }

        try {
            OLog.v("chain: session POST")
            val sesResp = app.post(
                "$mainUrl/api/media/session",
                headers = apiHeaders,
                requestBody = JSONObject().put("streamToken", streamToken)
                    .toString().toRequestBody("application/json".toMediaType())
            )
            OLog.v("chain: session code=${sesResp.code}")
            sesResp.headers.values("Set-Cookie").forEach { c ->
                c.substringBefore(";").trim().takeIf { it.contains("=") }?.let { cookieJar.add(it) }
            }
        } catch (e: Exception) {
            OLog.v("session failed: ${e.message}")
        }

        val cookieHeader = cookieJar.distinct().joinToString("; ")
        OLog.v("chain: cookies=${cookieJar.size} len=${cookieHeader.length}")
        val actionId = resolveActionId()
        OLog.v("chain: action id=$actionId")
        val stateTree = buildStateTree(animeId, ep)
        val actionBody = JSONArray().apply {
            put(animeId); put(ep); put(streamToken)
        }.toString().toRequestBody("text/plain;charset=UTF-8".toMediaType())

        val watchUrl = "$mainUrl/watch/$animeId?ep=$ep"
        val rscHeaders = buildMap {
            put("User-Agent", UA)
            put("Accept", "text/x-component")
            put("Accept-Language", "en-US,en;q=0.9")
            put("Content-Type", "text/plain;charset=UTF-8")
            put("Origin", mainUrl)
            put("Referer", watchUrl)
            put("next-action", actionId)
            put("next-router-state-tree", stateTree)
            put("sec-ch-ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"")
            put("sec-ch-ua-mobile", "?1")
            put("sec-ch-ua-platform", "\"Android\"")
            put("sec-fetch-dest", "empty")
            put("sec-fetch-mode", "cors")
            put("sec-fetch-site", "same-origin")
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
        }

        OLog.v("chain: RSC POST $watchUrl")
        val rsc = app.post(watchUrl, headers = rscHeaders, requestBody = actionBody).text
        OLog.v("chain: RSC len=${rsc.length}")

        val sourcesObj = rsc.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(":")
                if (idx < 1) return@mapNotNull null
                try { JSONObject(line.substring(idx + 1)) } catch (_: Exception) { null }
            }
            .firstOrNull { it.has("sources") }

        if (sourcesObj == null) {
            if (rsc.length > 20000 && rsc.contains("\"\$Sreact.fragment\"")) {
                OLog.e("OTAKUTSU UPDATED — plugin needs update (action id likely stale)")
                OLog.e("current action id=$actionId")
            } else {
                OLog.v("chain: no sources key, len=${rsc.length}")
            }
            return null
        }

        val sources = sourcesObj.optJSONArray("sources") ?: return null
        val out = mutableListOf<OtakutsuSource>()
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val raw = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            val fullUrl = if (raw.startsWith("http")) raw else "$mainUrl$raw"
            val label = s.optString("label").ifBlank { "Otakutsu" }
            val server = s.optString("server").ifBlank { "otakutsu" }
            val subType = s.optString("subType").ifBlank { "sub" }
            out.add(OtakutsuSource(label, server, subType, fullUrl))
            OLog.v("chain: source [$label] [$server/$subType] $fullUrl")
        }
        OLog.v("chain: parsed ${out.size} sources")
        return PrefetchCache(out, cookieHeader)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = try { JSONObject(data) } catch (_: Exception) { return false }
        val animeId = payload.optString("id").takeIf { it.isNotBlank() } ?: return false
        val ep = payload.optInt("ep", 1).coerceAtLeast(1)
        val nextEp = payload.optInt("ne", 0)
        OLog.section("loadLinks: $animeId E$ep")

        val key = "$animeId:$ep"
        val cached = OCache.getPrefetch(key)
        val result = cached ?: run {
            val fresh = fetchChain(animeId, ep) ?: return false
            OCache.putPrefetch(key, fresh)
            OLog.d("cache miss, fetched ${fresh.sources.size} sources")
            fresh
        }
        if (cached != null) OLog.d("cache hit: ${cached.sources.size} sources")

        playbackCookie = result.cookieHeader

        var emitted = 0
        for (s in result.sources) {
            try {
                OLog.v("probe: ${s.url}")
                val probe = app.get(s.url, headers = playbackHeadersFn())
                OLog.v("probe code=${probe.code} len=${probe.text.length}")
                val ok = probe.code == 200 && probe.text.trimStart().startsWith("#EXTM3U")
                if (!ok) continue
                val master = probe.text
                val hasVariants = master.contains("#EXT-X-STREAM-INF")
                val hasMedia = master.contains("#EXT-X-MEDIA")
                if (!hasVariants && !hasMedia) continue
                callback.invoke(
                    newExtractorLink("Otakutsu", "${s.label} [${s.server}/${s.subType}]", s.url, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = playbackHeadersFn()
                    }
                )
                emitted++
            } catch (e: Exception) {
                OLog.e("probe failed: ${e.message}")
            }
        }

        OLog.d("loadLinks emitted=$emitted")

        if (nextEp > 0 && OSettings.isPrefetchEnabled()) {
            schedulePrefetch(animeId, nextEp)
        }

        return emitted > 0
    }
}
