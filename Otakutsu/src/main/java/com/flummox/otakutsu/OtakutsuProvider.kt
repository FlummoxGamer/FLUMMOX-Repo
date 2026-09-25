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
    override val hasMainPage = false
    override var lang = "en"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    // Otakutsu redeploy → rotate this. Update if loadLinks returns 0 sources.
    private val NEXT_ACTION_ID = "787faac6445fbc39cfe9376659cbfb5168c3f714b2"

    private val baseHeaders get() = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
    )

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
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        for (u in listOf("$mainUrl/browse?q=$q", "$mainUrl/search?q=$q")) {
            try {
                val html = app.get(u, headers = baseHeaders).text
                val cards = parseCards(html)
                if (cards.isNotEmpty()) return cards
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""/(?:watch|anime)/([a-f0-9]{24})""").find(url)?.groupValues?.get(1) ?: return null
        val animeHtml = app.get("$mainUrl/anime/$id", headers = baseHeaders).text

        val title = Regex("""<h1[^>]*>([^<]+)</h1>""")
            .find(animeHtml)?.groupValues?.get(1)?.trim() ?: return null
        val poster = Regex("""<img[^>]+src="(https://s4\.anilist\.co/[^"]+)""")
            .find(animeHtml)?.groupValues?.get(1)
        val plot = Regex("""<p[^>]*class="[^"]*line-clamp-3[^"]*"[^>]*>([\s\S]*?)</p>""")
            .find(animeHtml)?.groupValues?.get(1)?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(animeHtml)?.value?.toIntOrNull()

        val watchHtml = app.get("$mainUrl/watch/$id?ep=1", headers = baseHeaders).text
        val eps = Regex("""/watch/$id\?ep=(\d+)""").findAll(watchHtml)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct().sorted().toList()

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
        val payload = try { JSONObject(data) } catch (_: Exception) { return false }
        val animeId = payload.optString("id").takeIf { it.isNotBlank() } ?: return false
        val ep = payload.optInt("ep", 1).coerceAtLeast(1)

        val bootText = app.post(
            "$mainUrl/api/media/bootstrap",
            headers = baseHeaders + mapOf("Content-Type" to "application/json"),
            requestBody = JSONObject()
                .put("animeId", animeId).put("ep", ep)
                .toString().toRequestBody("application/json".toMediaType())
        ).text
        val streamToken = JSONObject(bootText).optString("streamToken")
            .takeIf { it.isNotBlank() } ?: return false

        try {
            app.post(
                "$mainUrl/api/media/session",
                headers = baseHeaders + mapOf("Content-Type" to "application/json"),
                requestBody = JSONObject().put("streamToken", streamToken)
                    .toString().toRequestBody("application/json".toMediaType())
            )
        } catch (_: Exception) {}

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

        val sourcesObj = rsc.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(":")
                if (idx < 1) return@mapNotNull null
                try { JSONObject(line.substring(idx + 1)) } catch (_: Exception) { null }
            }
            .firstOrNull { it.has("sources") } ?: return false
        val sources = sourcesObj.optJSONArray("sources") ?: return false

        var emitted = 0
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val raw = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            val fullUrl = if (raw.startsWith("http")) raw else "$mainUrl$raw"
            val label = s.optString("label").ifBlank { "Otakutsu" }
            val server = s.optString("server").ifBlank { "otakutsu" }
            val subType = s.optString("subType").ifBlank { "sub" }

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

        return emitted > 0
    }
}
