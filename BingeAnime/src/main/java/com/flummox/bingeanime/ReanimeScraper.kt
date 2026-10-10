package com.flummox.bingeanime

import android.webkit.CookieManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val REANIME_TIMEOUT_MS = 8_000L

private const val REANIME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

// Referer must match what the WebView player page emits to the CDN.
// The embed lives at flixcloud.cc, so all sub-requests carry that
// referer. Using reanime.to here caused 403 on fetch8/9.flixcloud.cc.
private const val FLIXCLOUD_REFERER = "https://flixcloud.cc/"
private const val FLIXCLOUD_ORIGIN = "https://flixcloud.cc"

private val REANIME_CLICK_SCRIPT = """
    (function tick(n) {
        try {
            var sels = [
                'video',
                'button[aria-label*="play" i]',
                'button[title*="play" i]',
                '.play-button',
                '[class*="play"]'
            ];
            for (var i = 0; i < sels.length; i++) {
                var els = document.querySelectorAll(sels[i]);
                for (var j = 0; j < els.length; j++) {
                    try { els[j].click(); } catch(e) {}
                    try {
                        var ev = new MouseEvent('click', {
                            bubbles: true, cancelable: true, view: window
                        });
                        els[j].dispatchEvent(ev);
                    } catch(e) {}
                }
            }
            try {
                var v = document.querySelector('video');
                if (v) {
                    v.muted = true;
                    v.autoplay = true;
                    var p = v.play();
                    if (p && p.catch) p.catch(function(){});
                }
            } catch(e) {}
            if (n < 12) setTimeout(function(){ tick(n+1); }, 500);
        } catch(e) {}
    })(0);
""".trimIndent()

private fun extractUuid(masterUrl: String): String? =
    Regex("""/_v7/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})/""",
        RegexOption.IGNORE_CASE)
        .find(masterUrl)?.groupValues?.get(1)

private fun buildSubUrls(masterUrl: String): List<Pair<String, String>> {
    val uuid = extractUuid(masterUrl) ?: return emptyList()
    val base = "https://vault-95.fallencdn.top/subtitles/$uuid"
    return listOf(
        "English (SRT)"           to "$base/$uuid.srt",
        "English (Signs + Songs)" to "$base/${uuid}_eng_4.ass",
        "English (Alt)"           to "$base/${uuid}_eng_6.ass",
        "English (Signs)"         to "$base/${uuid}_eng_3.ass",
        "English (Signs 2)"       to "$base/${uuid}_eng_5.ass"
    )
}

// Pull every cookie the WebView picked up during the embed resolve.
// The master m3u8 URL carries a JWT, but the variant playlists
// (video.m3u8 / audio/*.m3u8) are fetched without a token and rely
// on the flixcloud session cookie being present.
private fun grabFlixcloudCookies(): String? {
    val cm = try { CookieManager.getInstance() } catch (_: Exception) { return null }
    val hosts = listOf(
        "https://flixcloud.cc",
        "https://fetch8.flixcloud.cc",
        "https://fetch9.flixcloud.cc"
    )
    val seen = linkedSetOf<String>()
    for (h in hosts) {
        val c = try { cm.getCookie(h) } catch (_: Exception) { null } ?: continue
        c.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { seen.add(it) }
    }
    return if (seen.isEmpty()) null else seen.joinToString("; ")
}

private suspend fun probeMaster(url: String, headers: Map<String, String>): Boolean {
    return try {
        val res = app.get(url, headers = headers, timeout = 5_000L)
        val ct = res.headers["Content-Type"].orEmpty()
        val head = res.text.take(60).replace("\n", "\\n")
        BLog.d("reanime probe: code=${res.code} ct=$ct head='$head'")
        res.code in 200..299 && res.text.contains("#EXTM3U")
    } catch (e: Exception) {
        BLog.e("reanime probe: ${e.message}")
        false
    }
}

private suspend fun resolveEmbed(label: String, embedUrl: String): String? {
    BLog.d("reanime-wv [$label]: ${embedUrl.take(110)}")
    val resolver = WebViewResolver(
        interceptUrl = Regex("""(?i)/master\.m3u8(?:\?|$)"""),
        additionalUrls = listOf(Regex("""(?i)\.m3u8(?:\?|$)""")),
        script = REANIME_CLICK_SCRIPT,
        useOkhttp = false,
        timeout = REANIME_TIMEOUT_MS
    )
    return try {
        val r = app.get(embedUrl, referer = "https://reanime.to/", interceptor = resolver)
        val u = r.url
        when {
            u.isBlank() -> { BLog.e("reanime-wv [$label]: blank url"); null }
            !u.contains(".m3u8", true) -> {
                BLog.e("reanime-wv [$label]: non-m3u8 ${u.take(80)}"); null
            }
            else -> {
                BLog.d("reanime-wv [$label]: resolved ${u.take(130)}")
                u
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BLog.e("reanime-wv [$label]: ${e.message}")
        null
    }
}

suspend fun reanimeExtractRaw(
    q: StreamQuery,
    onLink: (suspend (ScrapedMirror, Int) -> Unit)? = null,
    onSub: (suspend (String, String) -> Unit)? = null
): AniKageScrape {
    val yr = q.year.toIntOrNull()
    BLog.d("reanime: search '${q.title}' ($yr)")

    val hits = ReanimeApi.search(q.title)
    val hit = ReanimeApi.pickBest(hits, q.title, yr) ?: run {
        BLog.d("reanime: no match")
        return AniKageScrape(emptyList(), emptyList())
    }
    BLog.d("reanime: hit '${hit.title}' slug=${hit.slug} anilist=${hit.anilistId}")

    val ep = if (q.type == "movie") 1 else q.episode.takeIf { it > 0 } ?: 1
    val servers = ReanimeApi.servers(hit.anilistId, ep)
    if (servers.isEmpty()) {
        BLog.d("reanime: no servers for E$ep")
        return AniKageScrape(emptyList(), emptyList())
    }

    data class Edge(val url: String, val names: String, val types: String)
    val edges: List<Edge> = servers
        .groupBy { it.url }
        .map { (url, list) ->
            Edge(
                url = url,
                names = list.map { it.name }.distinct().joinToString("/"),
                types = list.map { it.dataType }.distinct().joinToString("+")
            )
        }

    BLog.d("reanime: ${servers.size} server entries → ${edges.size} edges E$ep")

    val collected = ConcurrentHashMap<String, ScrapedMirror>()
    val collectedSubs =
        java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    val subSeen = ConcurrentHashMap.newKeySet<String>()
    val emitted = AtomicInteger(0)

    coroutineScope {
        edges.map { edge ->
            async {
                val full = if (edge.url.contains("?"))
                    "${edge.url}&autoPlay=true&a=1"
                else "${edge.url}?autoPlay=true&a=1"

                val label = "${edge.names} (${edge.types.uppercase()})"
                val resolved = resolveEmbed(label, full) ?: return@async

                // Cookies + proper referer — this is the fix.
                val cookies = grabFlixcloudCookies()
                val headers = mutableMapOf(
                    "Referer" to FLIXCLOUD_REFERER,
                    "Origin" to FLIXCLOUD_ORIGIN,
                    "User-Agent" to REANIME_UA,
                    "Accept" to "*/*"
                )
                if (!cookies.isNullOrBlank()) headers["Cookie"] = cookies

                // Probe first. If ExoPlayer would 403, log it now instead
                // of throwing a user-visible error at playback time.
                if (!probeMaster(resolved, headers)) {
                    BLog.e("reanime [$label]: master probe failed — not emitting")
                    return@async
                }

                val mirror = ScrapedMirror(
                    quality = "Auto",
                    mirror = edge.names,
                    url = resolved,
                    source = "REANIME",
                    headers = headers,
                    captions = emptyList()
                )

                val isNew = synchronized(collected) {
                    collected.putIfAbsent(resolved, mirror) == null
                }
                if (!isNew) return@async

                val score = LinkScore.prelimScore(mirror)
                try { onLink?.invoke(mirror, score) } catch (_: Exception) {}
                emitted.incrementAndGet()

                if (onSub != null) {
                    for ((subLabel, subUrl) in buildSubUrls(resolved)) {
                        if (subSeen.add(subUrl)) {
                            try { onSub.invoke(subUrl, subLabel) } catch (_: Exception) {}
                            collectedSubs.add(subLabel to subUrl)
                        }
                    }
                }
            }
        }.awaitAll()
    }

    val mirrors = synchronized(collected) { collected.values.toList() }
    val subs = synchronized(collectedSubs) { collectedSubs.toList() }
    BLog.d("reanime: ${mirrors.size} mirrors, ${subs.size} subs")
    return AniKageScrape(mirrors, subs)
}
