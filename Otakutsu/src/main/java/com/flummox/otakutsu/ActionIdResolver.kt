package com.flummox.otakutsu

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup

// Turbopack compiles Next.js server action IDs into a server manifest.
// The 40-hex is NEVER emitted to client JS chunks referenced by the
// homepage — but route-scoped chunks loaded by /watch MAY contain it
// (Next.js only tree-shakes per-route, not always).
//
// Strategy:
//   1. Parse watch HTML → extract /_next/static/chunks/* script srcs
//   2. Fetch each in parallel (cap 12, 4s timeout each)
//   3. Regex scan for 40-hex
//   4. Cache hit for process lifetime. Miss → hardcoded fallback.
object ActionIdResolver {

    private const val FALLBACK = "787faac6445fbc39cfe9376659cbfb5168c3f714b2"
    @Volatile private var cached: String? = null

    fun current(): String = cached ?: FALLBACK

    suspend fun resolveFromWatchHtml(watchHtml: String, baseUrl: String): String {
        cached?.let { return it }
        try {
            val doc = Jsoup.parse(watchHtml, baseUrl)
            val chunks = doc.select("script[src]")
                .mapNotNull { it.attr("src").takeIf { s -> s.contains("/_next/static/chunks/") } }
                .distinct()
                .take(12)
            OLog.v("action: scanning ${chunks.size} watch chunks")

            val rxPrimary = Regex("""createServerReference\(["']([a-f0-9]{40})["']""")
            val rxAlt = Regex("""["']([a-f0-9]{40})["']""")

            val hits = coroutineScope {
                chunks.map { src ->
                    async {
                        val tag = src.substringAfterLast("/").take(28)
                        try {
                            val url = if (src.startsWith("http")) src else "$baseUrl$src"
                            val body = app.get(url, timeout = 4000L).text
                            val hit = rxPrimary.find(body)?.groupValues?.get(1)
                                ?: rxAlt.find(body)?.groupValues?.get(1)
                            if (hit != null) {
                                OLog.v("action: HIT $tag → $hit")
                                hit
                            } else {
                                OLog.v("action: miss $tag len=${body.length}")
                                null
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            OLog.v("action: err $tag ${e.message}")
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            val hit = hits.firstOrNull()
            if (hit != null) {
                OLog.d("action id from watch chunk: $hit")
                cached = hit
                return hit
            }
            OLog.d("action id: no hit, using fallback")
        } catch (e: Exception) {
            OLog.e("action resolve failed: ${e.message}")
        }
        return FALLBACK
    }
}
