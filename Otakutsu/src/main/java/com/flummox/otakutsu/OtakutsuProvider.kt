package com.flummox.otakutsu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

class OtakutsuProvider : MainAPI() {
    override var mainUrl = "https://otakutsu.cc"
    override var name = "Otakutsu"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private val NEXT_ACTION_ID = "787faac6445fbc39cfe9376659cbfb5168c3f714b2"

    private val baseHeaders get() = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

    // Playback headers — no Origin (browser doesn't send it on same-origin GET).
    private var playbackCookie: String = ""

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
    private val browserHeaders get() = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to "$mainUrl/",
    )

    // ── home cache ──
    private var homeHtml: String? = null
    private var homeTime: Long = 0L
    private val HOME_TTL = 10 * 60 * 1000L

    private suspend fun getHomeHtml(): String? {
        val now = System.currentTimeMillis()
        val cached = homeHtml
        if (cached != null && now - homeTime < HOME_TTL) return cached
        return try {
            val html = app.get(mainUrl, headers = browserHeaders).text
            homeHtml = html
            homeTime = now
            html
        } catch (e: Exception) {
            OLog.e("getHomeHtml failed: ${e.message}")
            null
        }
    }

    override val mainPage = mainPageOf(
        "trending"  to "Top 10 Trending",
        "gems"      to "Hidden Gems",
        "weekend"   to "Short & Complete",
        "movies"    to "Feature-Length Favourites",
        "originals" to "Original Stories",
        "classics"  to "Timeless Classics",
        "beyond"    to "Donghua Worth Discovering",
        "wildcard"  to "Pulled From the Vault",
   )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        OLog.section("mainPage: ${request.data}")
        val html = getHomeHtml() ?: return null
        val doc = Jsoup.parse(html, mainUrl)
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
        return newHomePageResponse(request.name, cards, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        OLog.section("search: $query")
        val html = getHomeHtml() ?: return emptyList()
        val doc = Jsoup.parse(html, mainUrl)
        val q = query.trim().lowercase()
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (a in doc.select("a[href^=/anime/]")) {
            val href = a.attr("href")
            val id = Regex("""/anime/((?:[a-f0-9]{24}|al-\d+))""").find(href)?.groupValues?.get(1) ?: continue
            if (!seen.add(id)) continue
            val title = a.selectFirst(".hc-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: continue
            if (!title.lowercase().contains(q)) continue
            val poster = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
            out.add(newMovieSearchResponse(title, "$mainUrl/watch/$id?ep=1", TvType.Anime) {
                this.posterUrl = poster
            })
        }
        OLog.d("search '$query' → ${out.size} matches (catalog filtered)")
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""/(?:watch|anime)/((?:[a-f0-9]{24}|al-\d+))""").find(url)?.groupValues?.get(1) ?: return null
        OLog.section("load: $id")

        val animeHtml = app.get("$mainUrl/anime/$id", headers = browserHeaders).text
        OLog.d("anime html len=${animeHtml.length}")

        val title = Regex("""<h1[^>]*>([^<]+)</h1>""")
            .find(animeHtml)?.groupValues?.get(1)?.trim()?.let {
                it.replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"")
            } ?: return null

        val poster = Regex("""<img[^>]+src="(https://s4\.anilist\.co/[^"]+)""")
            .find(animeHtml)?.groupValues?.get(1)

        val plot = Regex("""<p[^>]*class="[^"]*line-clamp-3[^"]*"[^>]*>([\s\S]*?)</p>""")
            .find(animeHtml)?.groupValues?.get(1)?.trim()?.let {
                it.replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"")
                  .replace(Regex("<[^>]+>"), "")
            }

        val year = Regex("""\b(19|20)\d{2}\b""").find(animeHtml)?.value?.toIntOrNull()

        // Score — look for ★N.N in the page
        val score = Regex("""★</i>\s*([0-9]+(?:\.[0-9]+)?)""")
            .find(animeHtml)?.groupValues?.get(1)?.toDoubleOrNull()
    
        // Genres from /browse?genre= links
        val genres = Regex("""href="/browse\?genre=([^"]+)"""")
            .findAll(animeHtml)
            .map { it.groupValues[1].replace("%20", " ") }
            .distinct().take(8).toList()

        // ── watch page → episodes ──
        val watchHtml = app.get("$mainUrl/watch/$id?ep=1", headers = browserHeaders).text
        OLog.d("watch html len=${watchHtml.length}")
        val eps = Regex("""/watch/$id\?ep=(\d+)""").findAll(watchHtml)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct().sorted().toList()
        OLog.d("episodes found=${eps.size} first=${eps.firstOrNull()} last=${eps.lastOrNull()}")

        if (eps.isEmpty()) {
            val q = JSONObject().apply { put("id", id); put("ep", 1) }.toString()
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, q) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres.takeIf { it.isNotEmpty() }
                if (score != null) this.score = Score.from10(score)
            }
        }

        val episodes = eps.map { ep ->
            val q = JSONObject().apply { put("id", id); put("ep", ep) }.toString()
            newEpisode(q) {
                this.name = "Episode $ep"
                this.episode = ep
                this.season = 1
                this.posterUrl = poster
            }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        OLog.section("loadLinks")
        val payload = try { JSONObject(data) } catch (_: Exception) { return false }
        val animeId = payload.optString("id").takeIf { it.isNotBlank() } ?: return false
        val ep = payload.optInt("ep", 1).coerceAtLeast(1)
        OLog.d("animeId=$animeId ep=$ep")

        try { app.get(mainUrl, headers = browserHeaders) } catch (_: Exception) {}

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

        val bootResp = app.post(
            "$mainUrl/api/media/bootstrap",
            headers = apiHeaders,
            requestBody = JSONObject()
                .put("animeId", animeId).put("ep", ep)
                .toString().toRequestBody("application/json".toMediaType())
        )
        val bootText = bootResp.text
        OLog.d("bootstrap code=${bootResp.code} len=${bootText.length}")
        if (bootText.length < 100) OLog.e("bootstrap body: $bootText")
        val streamToken = JSONObject(bootText).optString("streamToken")
            .takeIf { it.isNotBlank() } ?: return false
        OLog.d("streamToken len=${streamToken.length}")

        // collect cookies set by bootstrap + session
        val cookieJar = mutableListOf<String>()
        bootResp.headers.values("Set-Cookie").forEach { c ->
            c.substringBefore(";").trim().takeIf { it.contains("=") }?.let { cookieJar.add(it) }
        }

        try {
            val sesResp = app.post(
                "$mainUrl/api/media/session",
                headers = apiHeaders,
                requestBody = JSONObject().put("streamToken", streamToken)
                    .toString().toRequestBody("application/json".toMediaType())
        )
        OLog.d("session code=${sesResp.code} body=${sesResp.text.take(120)}")
        sesResp.headers.values("Set-Cookie").forEach { c ->
            c.substringBefore(";").trim().takeIf { it.contains("=") }?.let { cookieJar.add(it) }
        }
    } catch (e: Exception) {
        OLog.e("session failed: ${e.message}")
    }

    val cookieHeader = cookieJar.distinct().joinToString("; ")
    OLog.d("cookie count=${cookieJar.size} headerLen=${cookieHeader.length}")
    playbackCookie = cookieHeader
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
            put("next-action", NEXT_ACTION_ID)
            put("next-router-state-tree", stateTree)
            put("sec-ch-ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"")
            put("sec-ch-ua-mobile", "?1")
            put("sec-ch-ua-platform", "\"Android\"")
            put("sec-fetch-dest", "empty")
            put("sec-fetch-mode", "cors")
            put("sec-fetch-site", "same-origin")
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
         }
         val rsc = app.post(
             watchUrl,
             headers = rscHeaders,
             requestBody = actionBody
         ).text
        OLog.d("RSC resp len=${rsc.length}")
        OLog.d("RSC state-tree len=${stateTree.length}")
        if (rsc.length < 200) OLog.e("RSC suspiciously short: ${rsc.take(300)}")

        val sourcesObj = rsc.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(":")
                if (idx < 1) return@mapNotNull null
                try { JSONObject(line.substring(idx + 1)) } catch (_: Exception) { null }
            }
            .firstOrNull { it.has("sources") } ?: run {
                OLog.e("RSC had no 'sources' key. First 500: ${rsc.take(500)}")
                return false
            }
        val sources = sourcesObj.optJSONArray("sources") ?: return false
        OLog.d("sources count=${sources.length()}")

        var emitted = 0
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val raw = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            val fullUrl = if (raw.startsWith("http")) raw else "$mainUrl$raw"
            val label = s.optString("label").ifBlank { "Otakutsu" }
            val server = s.optString("server").ifBlank { "otakutsu" }
            val subType = s.optString("subType").ifBlank { "sub" }

            OLog.d("emit [$label] [$server/$subType] $fullUrl")

            try {
                val probe = app.get(fullUrl, headers = playbackHeadersFn())
                val ok = probe.code == 200 && probe.text.trimStart().startsWith("#EXTM3U")
                OLog.d("probe code=${probe.code} len=${probe.text.length} isM3u8=$ok")
                if (!ok) {
                    OLog.d("skip [$label] — not a valid m3u8")
                    continue
                }
            } catch (e: Exception) {
                OLog.e("probe failed: ${e.message}")
                continue
            }

           callback.invoke(
               newExtractorLink("Otakutsu", "$label [$server/$subType]", fullUrl, ExtractorLinkType.M3U8) {
                   this.referer = "$mainUrl/"
                   this.headers = playbackHeadersFn()
               }
            )
            emitted++

            val tracks = s.optJSONArray("tracks")
            if (tracks != null) {
                for (j in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(j) ?: continue
                    val kind = t.optString("kind")
                    if (kind != "subtitles" && kind != "captions") continue
                    val subUrl = t.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val subFull = if (subUrl.startsWith("http")) subUrl else "$mainUrl$subUrl"
                    subtitleCallback(SubtitleFile(t.optString("label").ifBlank { "Unknown" }, subFull))
                }
            }
        }

        OLog.d("loadLinks emitted=$emitted")
        return emitted > 0
    }
                           }
