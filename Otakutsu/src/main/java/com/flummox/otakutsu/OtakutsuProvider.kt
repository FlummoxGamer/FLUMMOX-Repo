package com.flummox.otakutsu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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

    private val browserHeaders get() = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "home" to "Latest",
        "trending" to "Trending",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = when (request.data) {
            "trending" -> "$mainUrl/discover/trending"
            else -> mainUrl
        }
        OLog.section("mainPage: ${request.data} $url")
        return try {
            val html = app.get(url, headers = browserHeaders).text
            OLog.d("home html len=${html.length}")
            val cards = parseCards(html)
            OLog.d("home parsed ${cards.size} cards")
            newHomePageResponse(request.name, cards, hasNext = false)
        } catch (e: Exception) {
            OLog.e("mainPage failed: ${e.message}")
            null
        }
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val cardRx = Regex(
            """<a[^>]+href="(/anime/([a-f0-9]{24}))"[^>]*>([\s\S]*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val titleRx = Regex("""<span[^>]*class="[^"]*hc-title[^"]*"[^>]*>([^<]+)</span>""")
        val imgRx = Regex("""<img[^>]+src="(https?://[^"]+)""")
        cardRx.findAll(html).forEach { m ->
            val id = m.groupValues[2]
            val inner = m.groupValues[3]
            val title = titleRx.find(inner)?.groupValues?.get(1)?.trim() ?: return@forEach
            val poster = imgRx.find(inner)?.groupValues?.get(1)
            out.add(newMovieSearchResponse(title, "$mainUrl/watch/$id?ep=1", TvType.Anime) {
                this.posterUrl = poster
            })
        }
        return out.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        OLog.section("search: $query")
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/browse?q=$q",
            "$mainUrl/search?q=$q",
            "$mainUrl/?q=$q",
        )
        for (u in urls) {
            try {
                val res = app.get(u, headers = browserHeaders)
                OLog.d("search HTTP ${res.code} $u len=${res.text.length}")
                val cards = parseCards(res.text)
                OLog.d("search parsed ${cards.size} cards")
                if (cards.isNotEmpty()) return cards
            } catch (e: Exception) {
                OLog.e("search try failed: ${e.message}")
            }
        }
        OLog.e("search returned 0 for '$query'")
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""/(?:watch|anime)/([a-f0-9]{24})""").find(url)?.groupValues?.get(1) ?: return null
        OLog.section("load: $id")

        val animeHtml = app.get("$mainUrl/anime/$id", headers = browserHeaders).text
        OLog.d("anime html len=${animeHtml.length}")

        val title = Regex("""<h1[^>]*>([^<]+)</h1>""")
            .find(animeHtml)?.groupValues?.get(1)?.trim() ?: return null
        val poster = Regex("""<img[^>]+src="(https://s4\.anilist\.co/[^"]+)""")
            .find(animeHtml)?.groupValues?.get(1)
        val plot = Regex("""<p[^>]*class="[^"]*line-clamp-3[^"]*"[^>]*>([\s\S]*?)</p>""")
            .find(animeHtml)?.groupValues?.get(1)?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(animeHtml)?.value?.toIntOrNull()

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
        }
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

        // Warm up — puts cookies into the shared client jar
try {
    val warm = app.get(mainUrl, headers = browserHeaders)
    OLog.d("warmup code=${warm.code}")
} catch (e: Exception) {
    OLog.e("warmup failed: ${e.message}")
}


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

try {
    val sesResp = app.post(
        "$mainUrl/api/media/session",
        headers = apiHeaders,
        requestBody = JSONObject().put("streamToken", streamToken)
            .toString().toRequestBody("application/json".toMediaType())
    )
    OLog.d("session code=${sesResp.code} body=${sesResp.text.take(120)}")
} catch (e: Exception) {
    OLog.e("session failed: ${e.message}")
}

        val actionBody = JSONArray().apply {
            put(animeId); put(ep); put(streamToken)
            put(JSONObject().put("force", "\$undefined"))
        }.toString().toRequestBody("text/plain;charset=UTF-8".toMediaType())

        val rsc = app.post(
            "$mainUrl/watch/$animeId?ep=$ep",
            headers = baseHeaders + mapOf(
                "Accept" to "text/x-component",
                "Content-Type" to "text/plain;charset=UTF-8",
                "next-action" to NEXT_ACTION_ID,
            ),
            requestBody = actionBody
        ).text
        OLog.d("RSC resp len=${rsc.length}")
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

            OLog.d("emit [$label] [$server/$subType]")
            callback.invoke(
                newExtractorLink("Otakutsu", "$label [$server/$subType]", fullUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.headers = baseHeaders
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
