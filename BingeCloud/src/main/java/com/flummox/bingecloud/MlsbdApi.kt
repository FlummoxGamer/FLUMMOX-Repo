package com.flummox.bingecloud

// ═══════════════════════════════════════════════════════════════
// ── MLSBD (DISABLED) ──
//
// Status: dead file. Not referenced from any active code path.
// Reason: mlsbd.co routes every external download link through
// link.bonghd.com → mlsbd.to → mlsbd.co/ (homepage). The bonghd
// wrapper is a JS-mediated rotating hop that can't be resolved
// without a full browser per download link.
//
// To revive:
//   1. StreamScrapers.kt — search "MLSBD REVIVE", uncomment block
//   2. Settings.kt       — search "MLSBD REVIVE", uncomment row
//                          and entry in the `all` list
//   3. CloudflareShield.kt — search "MLSBD REVIVE", uncomment
//                          the "MLSBD" entry in GROUPS
//   4. If bonghd's encoding changed, update mlsbdDecodeBongHd()
//   5. Rebuild
//
// Verified working (2026-09-21):
//   ✓ CF solver reaches mlsbd.co and solves the challenge
//   ✓ Search parses titles correctly
//   ✓ Title matching against MLSBD's noisy titles works
//   ✓ Page fetch + section extraction works
//   ✓ Anchor parsing sees the download links
//   ✓ bonghd x_data decoder (URL → strip:N → ROT13 → base64)
//
// Not working:
//   ✗ bonghd wrapper redirects to mlsbd.to → mlsbd.co/ (home)
//     instead of the download page. Anti-automation.
// ═══════════════════════════════════════════════════════════════

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════
// ── MLSBD: Bangladeshi movie/series link directory ──
// All mlsbd.co requests go through a dedicated OkHttp client
// that uses MlsbdDns to avoid unreachable system addresses.
// Downstream redirect hosts (savelinks, multicloudlinks) fall
// back to cloudflareGet when raw HTTP fails.
// ═══════════════════════════════════════════════════════════════

private const val MLSBD_BASE = "https://mlsbd.co"

// Must match CfSolverDialog.CF_UA and CloudflareHelper.CF_UA exactly —
// cf_clearance is bound to the UA that solved it.
private const val MLSBD_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private val mlsbdHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(MlsbdDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

private fun looksLikeChallenge(body: String): Boolean {
    val lower = body.lowercase()
    return lower.contains("just a moment") ||
        lower.contains("cf-chl") ||
        lower.contains("challenges.cloudflare.com")
}

private suspend fun mlsbdFetch(url: String, referer: String? = null): String? {
    try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MLSBD_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
            .build()

        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val response = mlsbdHttpClient.newCall(request).execute()
            Pair(response.code, response.body?.string())
        }
        val code = result.first
        val body = result.second
        if (code in 200..299 && !body.isNullOrBlank()) {
            if (!looksLikeChallenge(body)) {
                BCLog.d("[MLSBD] fetch ok ${url.take(80)} (${body.length} chars)")
                return body
            }
            BCLog.d("[MLSBD] challenge in 200 body for ${url.take(60)}")
        } else {
            BCLog.d("[MLSBD] fetch $url → HTTP $code")
        }
    } catch (e: Exception) {
        BCLog.e("[MLSBD] fetch failed for ${url.take(60)}: ${e.message}")
    }

    BCLog.d("[MLSBD] falling through to cloudflareGet for ${url.take(60)}")
    return try {
        cloudflareGet(url, referer)
    } catch (e: Exception) {
        BCLog.e("[MLSBD] cloudflareGet failed: ${e.message}")
        null
    }
}

// ── fetch that prefers the raw client but falls back to the CF solver ──
// Used for savelinks / multicloudlinks which may or may not be protected.
private suspend fun mlsbdFetchVia(
    url: String,
    referer: String? = null,
    followRedirects: Boolean = false
): Triple<Int, String?, String?>? {
    // returns (code, body, Location header) — Location may be null
    return try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MLSBD_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
            .build()

        val client = if (followRedirects) mlsbdHttpClient
            else mlsbdHttpClient.newBuilder().followRedirects(false).build()

        val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute()
        }
        val body = try { resp.body?.string() } catch (_: Exception) { null }
        val loc = resp.header("Location")

        // If we got a challenge page or a 4xx that smells like CF, use solver
        val needSolver = (resp.code == 403 || resp.code == 503) ||
            (body != null && looksLikeChallenge(body))
        if (needSolver) {
            BCLog.d("[MLSBD] ${url.take(80)} → HTTP ${resp.code}, invoking solver")
            val solved = cloudflareGet(url, referer)
            if (solved != null) {
                return Triple(200, solved, loc)
            }
        }
        Triple(resp.code, body, loc)
    } catch (e: Exception) {
        BCLog.e("[MLSBD] fetchVia failed for ${url.take(60)}: ${e.message}")
        null
    }
}

data class MlsbdHit(val url: String, val title: String, val poster: String?)

suspend fun mlsbdSearch(query: String): List<MlsbdHit> {
    val url = "$MLSBD_BASE/?s=${URLEncoder.encode(query, "UTF-8")}"
    val html = mlsbdFetch(url, referer = MLSBD_BASE) ?: return emptyList()
    val doc = Jsoup.parse(html, url)

    val out = mutableListOf<MlsbdHit>()
    for (card in doc.select("div.single-post")) {
        val a = card.selectFirst("div.thumb a[href]")
            ?: card.selectFirst("div.post-desc a[href]")
            ?: continue
        val titleEl = card.selectFirst("h2.post-title")
            ?: card.selectFirst("h2")
            ?: continue
        val href = a.attr("href").takeIf { it.startsWith("http") } ?: continue
        val title = titleEl.text().trim().takeIf { it.isNotBlank() } ?: continue
        val poster = card.selectFirst("div.thumb img[src]")?.attr("src")
            ?.takeIf { it.startsWith("http") }
        out.add(MlsbdHit(href, title, poster))
    }
    BCLog.d("[MLSBD] search '$query' → ${out.size}")
    return out
}

suspend fun mlsbdFindPage(
    title: String, year: String, type: String, season: Int
): String? {
    val hits = mlsbdSearch(title)
    if (hits.isEmpty()) {
        BCLog.d("[MLSBD] findPage: no search hits")
        return null
    }

    var best: MlsbdHit? = null
    var bestScore = 0
    for (h in hits) {
        val tm = titleMatches(title, h.title)
        if (!tm) {
            BCLog.d("[MLSBD] candidate rejected (title): ${h.title.take(80)}")
            continue
        }
        var score = 1
        if (year.isNotBlank() && h.title.contains(year)) score += 2
        val l = h.title.lowercase()
        if (type == "series") {
            if (l.contains("season") || l.contains("s0") || l.contains("series")) score += 2
            if (season > 0) {
                if (pageHasSeason(h.title, season)) score += 2
                else {
                    BCLog.d("[MLSBD] candidate rejected (season): ${h.title.take(80)}")
                    continue
                }
            }
        } else {
            if (!l.contains("season") && !l.contains("series")) score += 1
        }
        BCLog.d("[MLSBD] candidate score=$score: ${h.title.take(80)}")
        if (score > bestScore) { bestScore = score; best = h }
    }
    val picked = best ?: run {
        BCLog.d("[MLSBD] findPage: no candidate passed filters")
        return null
    }
    BCLog.d("[MLSBD] matched '${picked.title.take(80)}' (score=$bestScore)")
    return picked.url
}

private suspend fun mlsbdResolveSavelinks(savelinksUrl: String): String? {
    val res = mlsbdFetchVia(savelinksUrl, referer = MLSBD_BASE, followRedirects = false)
    if (res == null) {
        BCLog.d("[MLSBD] savelinks: fetch null for ${savelinksUrl.take(80)}")
        return null
    }
    val (code, body, loc) = res
    BCLog.d("[MLSBD] savelinks resp code=$code loc=${loc?.take(100)} body=${body?.length ?: 0}")

    if (!loc.isNullOrBlank() && loc.contains("multicloudlinks")) return loc
    if (body.isNullOrBlank()) return null

    val regex = Regex("""https?://[^"'\s<>]*multicloudlinks\.com/view/[A-Za-z0-9]+""")
    val found = regex.find(body)?.value
    if (found == null) {
        BCLog.d("[MLSBD] savelinks: no multicloud URL in body")
    } else {
        BCLog.d("[MLSBD] savelinks: extracted ${found.take(100)}")
    }
    return found
}

private suspend fun mlsbdExtractFromMulticloud(
    multiUrl: String, quality: String
): List<ScrapedMirror> {
    val res = mlsbdFetchVia(multiUrl, referer = "https://savelinks.me/", followRedirects = true)
    val html = res?.second
    if (html.isNullOrBlank()) {
        BCLog.d("[MLSBD] multicloud: empty body for ${multiUrl.take(80)}")
        return emptyList()
    }
    BCLog.d("[MLSBD] multicloud html ${html.length} chars")
    val doc = Jsoup.parse(html, multiUrl)

    val out = mutableListOf<ScrapedMirror>()

    val playerUrl = doc.selectFirst("a.premium-btn[href*='player.php']")?.attr("href")
    if (!playerUrl.isNullOrBlank()) {
        BCLog.d("[MLSBD] player.php found: ${playerUrl.take(80)}")
        val stream = mlsbdExtractPlayerStream(playerUrl)
        if (stream != null) {
            out.add(ScrapedMirror(quality, "MLSBD Player", stream, "MLSBD"))
            BCLog.d("[MLSBD] player stream $quality → ${stream.take(80)}")
        } else {
            BCLog.d("[MLSBD] player stream null for $quality")
        }
    } else {
        BCLog.d("[MLSBD] no player.php anchor")
    }

    val r2Url = doc.select("a.premium-btn[href]").firstOrNull {
    val t = it.text().lowercase()
    t.contains("turbo download") || t.contains("(r2)")
}?.attr("href")?.takeIf { it.startsWith("http") }
if (r2Url != null) {
    out.add(ScrapedMirror(quality, "MLSBD R2", r2Url, "MLSBD"))
    BCLog.d("[MLSBD] r2 $quality FULL: $r2Url")
} else {
    BCLog.d("[MLSBD] no turbo/r2 anchor")
}

    return out
}

private suspend fun mlsbdExtractPlayerStream(playerUrl: String): String? {
    val res = mlsbdFetchVia(playerUrl, referer = "https://new2.multicloudlinks.com/", followRedirects = true)
    val html = res?.second
    if (html.isNullOrBlank()) {
        BCLog.d("[MLSBD] player: empty body")
        return null
    }
    BCLog.d("[MLSBD] player html ${html.length} chars")
    val m = Regex("""const\s+streamSrc\s*=\s*"([^"]+)"""").find(html)
val url = m?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
if (url == null) {
    BCLog.d("[MLSBD] player: no streamSrc in ${playerUrl.take(60)}")
    return null
}
BCLog.d("[MLSBD] player streamSrc FULL: $url")

// ── probe: what status will ExoPlayer get when it tries this URL? ──
try {
    val probeClient = mlsbdHttpClient.newBuilder().followRedirects(false).build()
    val probeReq = Request.Builder()
        .url(url)
        .head()
        .header("User-Agent", MLSBD_UA)
        .header("Referer", "https://new.multicloudlinks.com/")
        .build()
    val probeResp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        probeClient.newCall(probeReq).execute()
    }
    BCLog.d("[MLSBD] probe code=${probeResp.code} " +
        "loc=${probeResp.header("Location")?.take(120)} " +
        "ct=${probeResp.header("Content-Type")} " +
        "cl=${probeResp.header("Content-Length")}")
} catch (e: Exception) {
    BCLog.e("[MLSBD] probe failed: ${e.message}")
}

return url
    }

// ── bonghd.com x_data: URL-decode → strip ":N" → ROT13 → base64 → URL ──
// Verified format: nUE0pUZ6Yl9h...%3D:1
//   URL-decoded:  nUE0pUZ6Yl9h...=:1
//   Strip ":1":   nUE0pUZ6Yl9h...=
//   ROT13:        aHR0cHM6Ly9h...=
//   base64:       https://...
private fun mlsbdDecodeBongHd(raw: String): String? {
    return try {
        var s = java.net.URLDecoder.decode(raw, "UTF-8")

        // Strip ":N" suffix — the trailing integer is a version marker, not data
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.substring(colon + 1).trim().toIntOrNull() != null) {
            s = s.substring(0, colon)
        }

        // ROT13
        val rot13 = buildString(s.length) {
            for (c in s) {
                when {
                    c in 'a'..'z' -> append(((c - 'a' + 13) % 26 + 'a'.code).toChar())
                    c in 'A'..'Z' -> append(((c - 'A' + 13) % 26 + 'A'.code).toChar())
                    else -> append(c)
                }
            }
        }

        // Pad to multiple of 4
        val padded = rot13 + "=".repeat((4 - rot13.length % 4) % 4)
        val bytes = android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        String(bytes, Charsets.UTF_8).trim().takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        BCLog.e("[MLSBD] bonghd decode: ${e.message}")
        null
    }
}

// ── resolve any wrapper → mirrors (recursive, depth-capped) ──
private suspend fun mlsbdResolveToMirrors(
    url: String, quality: String, depth: Int = 0
): List<ScrapedMirror> {
    if (depth > 4) return emptyList()
    val lower = url.lowercase()

    if (lower.contains("multidownload.") ||
        lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
        lower.endsWith(".m3u8") || lower.contains(".m3u8?") ||
        lower.endsWith(".mpd")) {
        BCLog.d("[MLSBD] direct $quality: ${url.take(120)}")
        return listOf(ScrapedMirror(quality, "MLSBD Direct", url, "MLSBD"))
    }

    if (url.contains("player.php")) {
        val s = mlsbdExtractPlayerStream(url) ?: return emptyList()
        BCLog.d("[MLSBD] player $quality: ${s.take(120)}")
        return listOf(ScrapedMirror(quality, "MLSBD Player", s, "MLSBD"))
    }

    if (url.contains("multicloudlinks.com/view/")) {
        return mlsbdExtractFromMulticloud(url, quality)
    }

    if (url.contains("savelinks.me/view")) {
        val m = mlsbdResolveSavelinks(url) ?: return emptyList()
        return mlsbdResolveToMirrors(m, quality, depth + 1)
    }

    BCLog.d("[MLSBD] wrapper $quality: ${url.take(140)}")
    val res = mlsbdFetchVia(url, referer = MLSBD_BASE, followRedirects = false)
    val (code, body, loc) = res ?: return emptyList()
    BCLog.d("[MLSBD] wrapper code=$code loc=${loc?.take(140)} bodyLen=${body?.length ?: 0}")

    if (!loc.isNullOrBlank()) return mlsbdResolveToMirrors(loc, quality, depth + 1)
    if (!body.isNullOrBlank()) {
        Regex("""https?://[^"'\s<>]*multicloudlinks\.com/view/[A-Za-z0-9]+""").find(body)?.value?.let {
            return mlsbdResolveToMirrors(it, quality, depth + 1)
        }
        Regex("""https?://[^"'\s<>]*savelinks\.me/view/[A-Za-z0-9]+""").find(body)?.value?.let {
            return mlsbdResolveToMirrors(it, quality, depth + 1)
        }
        Regex("""https?://[^"'\s<>]*player\.php[^"'\s<>]*""").find(body)?.value?.let {
            return mlsbdResolveToMirrors(it, quality, depth + 1)
        }
    }
    return emptyList()
}

suspend fun mlsbdExtractRaw(q: StreamQuery): List<ScrapedMirror> {
    BCLog.d("[MLSBD] extractRaw '${q.title}' (${q.year}) ${q.type} S${q.season}E${q.episode}")
    val pageUrl = mlsbdFindPage(q.title, q.year, q.type, q.season) ?: run {
        BCLog.d("[MLSBD] no page matched — aborting")
        return emptyList()
    }
    BCLog.d("[MLSBD] page → ${pageUrl.take(100)}")

    val pageHtml = mlsbdFetch(pageUrl, referer = MLSBD_BASE) ?: run {
        BCLog.d("[MLSBD] page fetch null — aborting")
        return emptyList()
    }
    BCLog.d("[MLSBD] page html ${pageHtml.length} chars")
    val doc = Jsoup.parse(pageHtml, pageUrl)

    data class Section(val title: String, val links: List<Element>)
    val sections = mutableListOf<Section>()
    for (secDiv in doc.select("div.post-section-title.download")) {
        val links = mutableListOf<Element>()
        var sib = secDiv.nextElementSibling()
        while (sib != null && !sib.hasClass("post-section-title")) {
            if (sib.tagName() == "p") links.addAll(sib.select("a.Dbtn[href]"))
            sib = sib.nextElementSibling()
        }
        sections.add(Section(secDiv.text(), links))
    }
    BCLog.d("[MLSBD] sections: ${sections.size} on page")
    for (s in sections) BCLog.d("[MLSBD]   section '${s.title.take(60)}' links=${s.links.size}")

    val relevant: List<Section> = if (q.type == "series" && q.episode > 0) {
        sections.filter { s ->
            val m = Regex("""Epi-(\d+)-(\d+)""", RegexOption.IGNORE_CASE)
                .find(s.title) ?: return@filter false
            val start = m.groupValues[1].toIntOrNull() ?: return@filter false
            val end = m.groupValues[2].toIntOrNull() ?: return@filter false
            q.episode in start..end
        }.take(1)
    } else {
        sections
    }
    if (relevant.isEmpty()) {
        BCLog.d("[MLSBD] no matching section for ${q.type} E${q.episode}")
        return emptyList()
    }

        data class Job(val quality: String, val url: String)
    val jobs = mutableListOf<Job>()
    for (sec in relevant) {
        for (a in sec.links) {
            val href = a.attr("href")
            val text = a.text().trim()
            val lower = text.lowercase()
            val quality = when {
                lower.contains("4k") || lower.contains("2160") -> "2160p"
                lower.contains("1080") -> "1080p"
                lower.contains("720") -> "720p"
                lower.contains("480") -> "480p"
                else -> continue
            }
            if (quality == "480p") continue
            BCLog.d("[MLSBD]   anchor q=$quality text='${text.take(60)}' href='${href.take(140)}'")

            var resolved: String? = null

            val xData = Regex("""[?&]x_data=([^&]+)""").find(href)?.groupValues?.get(1)
            if (xData != null) {
                resolved = mlsbdDecodeBongHd(xData)
                if (resolved != null) BCLog.d("[MLSBD]   bonghd decoded → ${resolved.take(140)}")
                else BCLog.d("[MLSBD]   bonghd decode returned null")
            }

            if (resolved == null && href.startsWith("http")) {
                resolved = href
                BCLog.d("[MLSBD]   passing raw href to resolver")
            }

            if (resolved != null) jobs.add(Job(quality, resolved))
        }
    }
    BCLog.d("[MLSBD] jobs: ${jobs.size}")

    val out = mutableListOf<ScrapedMirror>()
    val seen = mutableSetOf<String>()
    for (j in jobs) {
        val key = "${j.quality}|${j.url}"
        if (!seen.add(key)) continue
        out.addAll(mlsbdResolveToMirrors(j.url, j.quality))
    }

    BCLog.d("[MLSBD] total mirrors: ${out.size}")
    return out
}
