package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

object AniwavesApi {
    const val BASE = "https://aniwaves.ru"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private const val TTL_SEARCH  = 30L * 60 * 1000
    private const val TTL_DETAIL  = 6L * 60 * 60 * 1000
    private const val TTL_SOURCES = 10L * 60 * 1000

    private fun baseHeaders() = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$BASE/"
    )
    private fun ajaxHeaders(referer: String) = baseHeaders() + mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer
    )

    data class Hit(
        val slug: String,
        val title: String,
        val jpTitle: String?,
        val poster: String?,
        val year: Int?,
        val type: String?
    )

    data class Ep(
        val number: Int,
        val title: String?,
        val aired: String?,
        val filler: Boolean,
        val hasSub: Boolean,
        val hasDub: Boolean
    )

    data class Srv(
        val label: String,
        val subType: String,
        val serverId: String,
        val linkId: String
    )

    data class Detail(
        val id: String,
        val title: String,
        val poster: String?,
        val description: String?,
        val year: Int?,
        val genres: List<String>,
        val isMovie: Boolean,
        val episodes: List<Ep>,
        val totalSub: Int,
        val totalDub: Int
    )

    suspend fun search(query: String): List<Hit> {
        val ck = "aniwaves:s:${query.lowercase()}"
        BCCache.get(ck, TTL_SEARCH)?.let { cached ->
            return try { parseSearch(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/ajax/anime/search?keyword=${URLEncoder.encode(query, "UTF-8")}"
        return try {
            val res = app.get(url, headers = ajaxHeaders("$BASE/"))
            if (res.code !in 200..299) return emptyList()
            BCCache.put(ck, res.text)
            parseSearch(JSONObject(res.text))
        } catch (e: Exception) {
            BLog.e("aniwaves search: ${e.message}"); emptyList()
        }
    }

    private fun parseSearch(root: JSONObject): List<Hit> {
        val html = root.optJSONObject("result")?.optString("html")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()
        val doc = Jsoup.parse(html)
        val out = mutableListOf<Hit>()
        for (item in doc.select("a.item[href^=/watch/]")) {
            val slug = item.attr("href").removePrefix("/watch/")
                .substringBefore("/").takeIf { it.isNotBlank() } ?: continue
            val nameEl = item.selectFirst("div.name.d-title") ?: continue
            val title = nameEl.text().trim().takeIf { it.isNotBlank() } ?: continue
            val jp = nameEl.attr("data-jp").takeIf { it.isNotBlank() }
            val poster = item.selectFirst("div.poster img[src]")?.attr("src")
                ?.takeIf { it.startsWith("http") }
            var year: Int? = null; var type: String? = null
            for (dot in item.select("div.meta span.dot")) {
                val t = dot.text().trim(); if (t.isEmpty()) continue
                Regex("""\b(19|20)\d{2}\b""").find(t)?.value?.toIntOrNull()?.let { year = it }
                if (t == "TV" || t == "Movie" || t.contains("Special", true) ||
                    t.contains("OVA", true) || t.contains("ONA", true)) type = t
            }
            out.add(Hit(slug, title, jp, poster, year, type))
        }
        return out
    }

    suspend fun detail(slug: String): Detail? {
        val ck = "aniwaves:d:$slug"
        BCCache.get(ck, TTL_DETAIL)?.let { cached ->
            return try { parseDetail(cached, slug) } catch (_: Exception) { null }
        }
        return try {
            val res = app.get("$BASE/watch/$slug", headers = baseHeaders())
            BLog.d("aniwaves: detail HTTP ${res.code} len=${res.text.length}")
            if (res.code !in 200..299) return null
            BCCache.put(ck, res.text)
            parseDetail(res.text, slug)
        } catch (e: Exception) {
            BLog.e("aniwaves detail: ${e.message}"); null
        }
    }

    private fun parseDetail(html: String, slug: String): Detail? {
        val doc = Jsoup.parse(html, "$BASE/watch/$slug")
        BLog.d("aniwaves: detail html=${html.length}b watchMain=${doc.select("#watch-main").size} epRanges=${doc.select("ul.ep-range").size} epLinks=${doc.select("ul.ep-range a[data-num]").size}")
        val main = doc.selectFirst("#watch-main") ?: run {
            BLog.d("aniwaves: no #watch-main in detail html")
            return null
        }
        val id = main.attr("data-id").takeIf { it.isNotBlank() } ?: run {
            BLog.d("aniwaves: no data-id in #watch-main")
            return null
        }

        var title: String? = null; var desc: String? = null; var poster: String? = null
        var year: Int? = null; var genres = emptyList<String>()
        var isMovie = false; var totalSub = 0; var totalDub = 0

        for (ld in doc.select("script[type=application/ld+json]")) {
            try {
                val obj = JSONObject(ld.data())
                val t = obj.optString("@type")
                if (t == "TVSeries" || t == "Movie") {
                    title = title ?: obj.optString("name").takeIf { it.isNotBlank() }
                    desc  = desc  ?: obj.optString("description").takeIf { it.isNotBlank() }
                    poster = poster ?: obj.optString("image").takeIf { it.startsWith("http") }
                    year = year ?: obj.optString("datePublished").take(4).toIntOrNull()
                    isMovie = t == "Movie"
                    val g = obj.optJSONArray("genre")
                    if (g != null && genres.isEmpty())
                        genres = (0 until g.length()).mapNotNull { g.optString(it).takeIf { s -> s.isNotBlank() } }
                    obj.optJSONArray("additionalProperty")?.let { ap ->
                        for (i in 0 until ap.length()) {
                            val p = ap.optJSONObject(i) ?: continue
                            val n = p.optString("name").lowercase()
                            val v = p.optInt("value", 0)
                            if (n.contains("subbed")) totalSub = v
                            else if (n.contains("dubbed")) totalDub = v
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (title == null) title = doc.selectFirst("h1")?.text()?.trim() ?: slug

        val eps = mutableListOf<Ep>()
        for (ul in doc.select("ul.ep-range")) {
            for (a in ul.select("a[data-num]")) {
                val n = a.attr("data-num").toIntOrNull() ?: continue
                val rawTitle = a.parent()?.attr("title")?.takeIf { it.isNotBlank() }
                val cleanTitle = rawTitle?.substringAfter(" - ")?.takeIf { it.isNotBlank() }
                eps.add(Ep(
                    number = n,
                    title = cleanTitle,
                    aired = a.attr("data-aired").takeIf { it.isNotBlank() },
                    filler = a.attr("data-filler") == "1",
                    hasSub = a.attr("data-sub") == "1",
                    hasDub = a.attr("data-dub") == "1"
                ))
            }
        }

        return Detail(id, title, poster, desc, year, genres, isMovie,
            eps.sortedBy { it.number }, totalSub, totalDub)
    }

suspend fun servers(slug: String, ep: Int): List<Srv> {
    val ck = "aniwaves:srv:$slug:$ep"
    BCCache.get(ck, TTL_DETAIL)?.let { cached ->
        return try { parseServers(cached) } catch (_: Exception) { emptyList() }
    }
    return try {
        val res = app.get("$BASE/watch/$slug/ep-$ep", headers = baseHeaders())
        BLog.d("aniwaves: srv page HTTP ${res.code} len=${res.text.length}")
        if (res.code !in 200..299) return emptyList()
        BCCache.put(ck, res.text)
        parseServers(res.text)
    } catch (e: Exception) {
        BLog.e("aniwaves servers: ${e.message}"); emptyList()
    }
}

private fun parseServers(html: String): List<Srv> {
    val doc = Jsoup.parse(html)
    val out = mutableListOf<Srv>()
    for (typeDiv in doc.select("#w-servers div.type[data-type]")) {
        val subType = typeDiv.attr("data-type")
        for (li in typeDiv.select("ul > li[data-link-id]")) {
            val label = li.text().trim().takeIf { it.isNotBlank() } ?: continue
            out.add(Srv(label, subType, li.attr("data-sv-id"), li.attr("data-link-id")))
        }
    }
    BLog.d("aniwaves: srv typeDivs=${doc.select("#w-servers div.type[data-type]").size} srv=${out.size}")
    return out
}

    suspend fun resolveLink(linkId: String, referer: String): String? {
        val ck = "aniwaves:solve:$linkId"
        BCCache.get(ck, TTL_SOURCES)?.let { cached ->
            return try {
                JSONObject(cached).optString("url").takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
        }
        val url = "$BASE/ajax/sources?id=${android.net.Uri.encode(linkId)}&asi=0&autoPlay=0"
        return try {
            val res = app.get(url, headers = ajaxHeaders(referer))
            if (res.code !in 200..299) return null
            val root = JSONObject(res.text)
            if (root.optInt("status") != 200) return null
            val u = root.optJSONObject("result")?.optString("url")
                ?.takeIf { it.isNotBlank() } ?: return null
            BCCache.put(ck, root.toString())
            u
        } catch (e: Exception) {
            BLog.v("aniwaves resolve: ${e.message}"); null
        }
    }
}
