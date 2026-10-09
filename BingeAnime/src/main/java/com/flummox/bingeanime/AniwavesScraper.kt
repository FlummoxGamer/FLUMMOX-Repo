package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val ANIWAVES_CONCURRENCY = 4

// Only Vidplay (sv-id 4) resolves reliably.
private val ALLOWED_SV_IDS = setOf("4")

private fun pickBest(
    hits: List<AniwavesApi.Hit>,
    query: String,
    year: Int?
): AniwavesApi.Hit? {
    if (hits.isEmpty()) return null
    fun norm(s: String) = s.lowercase()
        .replace(Regex("""[^a-z0-9 ]"""), " ")
        .replace(Regex("""\s+"""), " ").trim()
    val qn = norm(query)
    hits.firstOrNull { norm(it.title) == qn && (year == null || it.year == year) }?.let { return it }
    hits.firstOrNull { norm(it.title) == qn }?.let { return it }
    val contain = hits.filter {
        val n = norm(it.title)
        n.isNotBlank() && (n.contains(qn) || qn.contains(n))
    }
    if (contain.isNotEmpty()) {
        return contain.firstOrNull { year != null && it.year == year } ?: contain.first()
    }
    val qw = qn.split(" ").toSet()
    return hits.mapNotNull { h ->
        val hw = norm(h.title).split(" ").toSet()
        val common = qw.intersect(hw).size
        if (common == 0) null else h to (common.toFloat() / maxOf(qw.size, hw.size))
    }.maxByOrNull { it.second }?.first
}

private fun hostOf(url: String): String = try {
    java.net.URI(url).host?.lowercase() ?: ""
} catch (_: Exception) { "" }

private val ANIWAVES_CLICK_SCRIPT = """
    (function tick(n) {
        try {
            var sels = [
                '.jw-icon-playback',
                '.jw-display-icon-container .jw-icon',
                '.jw-display-icon-display',
                '.jw-icon-large',
                '.vds-play-button',
                '.vds-poster',
                'button[aria-label*="play" i]',
                'button[title*="play" i]',
                'button.play',
                'video'
            ];
            for (var i = 0; i < sels.length; i++) {
                var els = document.querySelectorAll(sels[i]);
                for (var j = 0; j < els.length; j++) {
                    try { els[j].click(); } catch (e) {}
                    try {
                        var ev = new MouseEvent('click', {
                            bubbles: true, cancelable: true, view: window
                        });
                        els[j].dispatchEvent(ev);
                    } catch (e) {}
                }
            }
            try {
                var v = document.querySelector('video');
                if (v) {
                    v.muted = true;
                    var p = v.play();
                    if (p && p.catch) p.catch(function(){});
                }
            } catch (e) {}
            if (n < 20) setTimeout(function(){ tick(n + 1); }, 500);
        } catch (e) {}
    })(0);
""".trimIndent()

private suspend fun resolveEmbed(
    label: String,
    embedUrl: String
): String? {
    BLog.d("aniwaves-wv [$label]: ${embedUrl.take(100)}")
    val resolver = WebViewResolver(
        interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
        additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
        script = ANIWAVES_CLICK_SCRIPT,
        useOkhttp = false,
        timeout = 10_000L
    )
    return try {
        val r = app.get(embedUrl, referer = "https://aniwaves.ru/", interceptor = resolver)
        val u = r.url
        when {
            u.isBlank() -> {
                BLog.e("aniwaves-wv [$label]: blank url")
                null
            }
            !u.contains(".m3u8", true) && !u.contains(".mp4", true) -> {
                BLog.e("aniwaves-wv [$label]: non-media ${u.take(80)}")
                null
            }
            else -> {
                BLog.d("aniwaves-wv [$label]: resolved ${u.take(120)}")
                u
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BLog.e("aniwaves-wv [$label]: ${e.message}")
        null
    }
}

suspend fun aniwavesExtractRaw(
    q: StreamQuery,
    onLink: (suspend (ScrapedMirror, Int) -> Unit)? = null,
    onSub: (suspend (String, String) -> Unit)? = null
): AniKageScrape {
    val yr = q.year.toIntOrNull()
    BLog.d("aniwaves: search '${q.title}' ($yr)")

    val hit = pickBest(AniwavesApi.search(q.title), q.title, yr) ?: run {
        BLog.d("aniwaves: no match")
        return AniKageScrape(emptyList(), emptyList())
    }
    BLog.d("aniwaves: hit '${hit.title}' slug=${hit.slug}")

    val ep = if (q.type == "movie") 1 else q.episode.takeIf { it > 0 } ?: 1
    val servers = AniwavesApi.servers(hit.slug, ep)
        .filter { it.serverId in ALLOWED_SV_IDS }
    if (servers.isEmpty()) {
        BLog.d("aniwaves: no servers E$ep")
        return AniKageScrape(emptyList(), emptyList())
    }
    BLog.d("aniwaves: ${servers.size} servers E$ep")

    val sem = Semaphore(ANIWAVES_CONCURRENCY)
    val collected = ConcurrentHashMap<String, ScrapedMirror>()
    val emitted = AtomicInteger(0)
    val referer = "https://aniwaves.ru/watch/${hit.slug}/ep-$ep"

    coroutineScope {
        servers.map { srv ->
            async {
                try {
                    sem.withPermit {
                        val embed = AniwavesApi.resolveLink(srv.linkId, referer)
                            ?: return@withPermit
                        val host = hostOf(embed)
                        BLog.v("aniwaves ${srv.subType}/${srv.label} [$host]: ${embed.take(120)}")

                        val resolved = resolveEmbed(
                            "${srv.label}·${srv.subType.uppercase()}",
                            embed
                        ) ?: return@withPermit

                        val m = ScrapedMirror(
                            quality = "Auto",
                            mirror = "${srv.label} · ${srv.subType.uppercase()}",
                            url = resolved,
                            source = "ANIWAVES",
                            headers = mapOf(
                                "Referer" to "https://$host/",
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                    "Chrome/122.0.0.0 Mobile Safari/537.36"
                            ),
                            captions = emptyList()
                        )
                        if (synchronized(collected) { collected.putIfAbsent(m.url, m) == null }) {
                            val score = LinkScore.prelimScore(m)
                            try { onLink?.invoke(m, score) } catch (_: Exception) {}
                            emitted.incrementAndGet()
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BLog.v("aniwaves ${srv.label}: ${e.message}")
                }
            }
        }.awaitAll()
    }

    val mirrors = synchronized(collected) { collected.values.toList() }
    BLog.d("aniwaves: ${mirrors.size} mirrors emitted")
    return AniKageScrape(mirrors, emptyList())
}
