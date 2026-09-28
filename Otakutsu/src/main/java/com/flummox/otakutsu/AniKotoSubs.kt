package com.flummox.otakutsu

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import org.jsoup.Jsoup

// Standalone AniKoto subtitle extractor. Ported from BingeCloud's
// AnikotoExtractors.kt + StreamScrapers.kt AniKoto section.
// Only fetches subtitle tracks — no video URL emission, no chain walk.
object AniKotoSubs {

    private const val DOMAIN = "https://anikototv.to"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to UA,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer
    )

    data class Sub(val label: String, val url: String)

    private fun resultString(json: String): String = try {
        val root = JSONObject(json)
        if (root.optInt("status") == 200) root.optString("result") else ""
    } catch (_: Exception) { "" }

    private fun resultUrl(json: String): String? = try {
        val root = JSONObject(json)
        if (root.optInt("status") == 200)
            root.optJSONObject("result")?.optString("url")?.takeIf { it.isNotBlank() }
        else null
    } catch (_: Exception) { null }

    private fun score(query: String, candidate: String): Int {
        val q = query.lowercase().trim()
        val c = candidate.lowercase().trim()
        if (q.isEmpty() || c.isEmpty()) return 0
        if (q == c) return 100
        if (c.startsWith(q)) return 30
        if (q.startsWith(c)) return 25
        val qw = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        val cw = c.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (qw.size <= 2) return if (cw.take(qw.size).joinToString(" ") == q) 20 else 0
        val common = qw.intersect(cw.toSet()).size
        if (common == 0) return 0
        return ((common.toFloat() / maxOf(qw.size, cw.size)) * 20).toInt()
    }

    private data class Series(val url: String, val title: String, val animeId: String)

    private suspend fun findSeries(title: String): Series? {
        val query = java.net.URLEncoder.encode(title, "UTF-8")
        val doc = try {
            app.get("$DOMAIN/filter?keyword=$query", headers = browserHeaders).document
        } catch (e: Exception) { return null }

        val cards = doc.select("div.ani.items > div.item")
        var best: Series? = null
        var bestScore = 0
        for (card in cards) {
            val titleEl = card.selectFirst("a.name.d-title")
                ?: card.selectFirst("a[title]")
                ?: card.selectFirst("a[href*='/watch/']") ?: continue
            var href = titleEl.attr("href")
            if (href.isBlank()) href = card.selectFirst("div.poster a, a")?.attr("href") ?: ""
            val cand = titleEl.text().trim().ifBlank { titleEl.attr("title").trim() }
            if (href.isBlank() || cand.isBlank()) continue
            val s = score(title, cand)
            if (s > bestScore && s >= 20) {
                bestScore = s
                val full = if (href.startsWith("http")) href else "$DOMAIN$href"
                val seriesUrl = full.replace(Regex("""/ep-\d+/?$"""), "").trimEnd('/')
                best = Series(seriesUrl, cand, "")
            }
        }
        val hit = best ?: return null

        val seriesHtml = try { app.get(hit.url, headers = browserHeaders).text } catch (_: Exception) { "" }
        val sDoc = Jsoup.parse(seriesHtml, hit.url)
        val animeId = sDoc.selectFirst("#watch-main")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: sDoc.selectFirst("[data-id]")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: Regex("""data-id=["'](\d+)["']""").find(seriesHtml)?.groupValues?.get(1)
            ?: ""
        return hit.copy(animeId = animeId)
    }

    private suspend fun serverIdsForEpisode(series: Series, ep: Int): String? {
        if (series.animeId.isBlank()) return null
        val listJson = try {
            resultString(app.get("$DOMAIN/ajax/episode/list/${series.animeId}",
                headers = ajaxHeaders(series.url)).text)
        } catch (_: Exception) { return null }
        if (listJson.isBlank()) return null
        val allEp = Jsoup.parse(listJson).select("a[data-ids]")
        val target = allEp.firstOrNull { it.attr("data-num").toIntOrNull() == ep } ?: return null
        return target.attr("data-ids").takeIf { it.isNotBlank() }
    }

    private suspend fun resolvePlayerUrl(linkId: String, referer: String): String? {
        val encoded = android.net.Uri.encode(linkId)
        try {
            val raw = app.get("$DOMAIN/ajax/server?get=$encoded",
                headers = ajaxHeaders(referer)).text
            if (raw.contains("\"message\"")) return null
            return resultUrl(raw)
        } catch (_: Exception) { return null }
    }

    private suspend fun tracksFromPlayer(playerUrl: String): List<Sub> {
        val type = if (playerUrl.contains("/dub", true)) "dub" else "sub"
        val pageHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to "https://anikototv.to/"
        )
        val doc = try { app.get(playerUrl, headers = pageHeaders).document }
            catch (_: Exception) { return emptyList() }
        val playerEl = doc.selectFirst("#megaplay-player") ?: return emptyList()
        val streamId = playerEl.attr("data-id").takeIf { it.isNotBlank() }
            ?: playerEl.attr("data-realid").takeIf { it.isNotBlank() }
            ?: return emptyList()

        val host = try {
            java.net.URI(playerUrl).let { "${it.scheme}://${it.host}" }
        } catch (_: Exception) { return emptyList() }
        val srcUrl = "$host/stream/getSources?id=$streamId&type=$type"
        val ajaxH = mapOf(
            "User-Agent" to UA,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to host,
            "Referer" to playerUrl
        )
        val root = try {
            JSONObject(app.get(srcUrl, headers = ajaxH, referer = playerUrl).text)
        } catch (_: Exception) { return emptyList() }
        val tracks = root.optJSONArray("tracks") ?: return emptyList()

        val out = mutableListOf<Sub>()
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val kind = t.optString("kind")
            if (kind != "captions" && kind != "subtitles") continue
            val file = t.optString("file").takeIf { it.isNotBlank() } ?: continue
            val url = if (file.startsWith("http")) file else "$host/$file"
            val label = t.optString("label").takeIf { it.isNotBlank() } ?: "Unknown"
            out.add(Sub(label, url))
        }
        return out
    }

    suspend fun fetch(title: String, episode: Int): List<Sub> {
        val series = findSeries(title) ?: return emptyList()
        if (series.animeId.isBlank()) return emptyList()

        val serverIds = serverIdsForEpisode(series, episode) ?: return emptyList()

        val listJson = try {
            resultString(app.get("$DOMAIN/ajax/server/list?servers=${android.net.Uri.encode(serverIds)}",
                headers = ajaxHeaders(series.url)).text)
        } catch (_: Exception) { return emptyList() }
        if (listJson.isBlank()) return emptyList()

        val doc = Jsoup.parse(listJson)
        val entries = mutableListOf<String>()
        for (li in doc.select("li")) {
            val linkId = listOf("data-link-id", "data-id", "data-sv", "data-server", "data-embed")
                .firstNotNullOfOrNull { li.attr(it).takeIf { v -> v.isNotBlank() } }
                ?: continue
            entries.add(linkId)
            if (entries.size >= 3) break
        }

        for (linkId in entries) {
            val playerUrl = resolvePlayerUrl(linkId, series.url) ?: continue
            val full = when {
                playerUrl.startsWith("//") -> "https:$playerUrl"
                playerUrl.startsWith("/") -> "$DOMAIN$playerUrl"
                else -> playerUrl
            }
            val subs = tracksFromPlayer(full)
            if (subs.isNotEmpty()) return subs
        }
        return emptyList()
    }
}
