package com.flummox.otakutsu

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// Standalone AniZone subtitle extractor. Ported from BingeCloud's
// AniZoneApi.kt — only the subtitle extraction path.
object AniZoneSubs {

    private const val BASE = "https://anizone.to"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    data class Sub(val label: String, val url: String)

    private val RX_JSON_PARSE_TPL = Regex(
        """\b(%s)\s*:\s*JSON\.parse\(\s*['"](.+?)['"]\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val RX_PLAYER = Regex(
        """vidstackPlayer\(\s*JSON\.parse\(\s*['"](.+?)['"]\s*\)\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val RX_NON_ALNUM = Regex("""[^\p{L}\p{N}\s]""")
    private val RX_WS = Regex("""\s+""")

    private fun unescapeJs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'u' -> if (i + 5 < s.length) {
                        val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6; continue }
                        sb.append(c); i++
                    } else { sb.append(c); i++ }
                    '/' -> { sb.append('/'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    else -> { sb.append(s[i + 1]); i += 2 }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun extractJsonParse(html: String, key: String): String? {
        val rx = Regex(RX_JSON_PARSE_TPL.pattern.format(Regex.escape(key)),
            RegexOption.DOT_MATCHES_ALL)
        return rx.find(html)?.groupValues?.get(2)?.let { unescapeJs(it) }
    }

    private fun normalize(s: String): String =
        s.lowercase().trim().replace(RX_NON_ALNUM, " ").replace(RX_WS, " ").trim()

    private data class Hit(val slug: String, val titles: List<String>)

    private suspend fun search(query: String): List<Hit> {
        val url = "$BASE/anime?search=${URLEncoder.encode(query, "UTF-8")}"
        val html = try {
            app.get(url, headers = mapOf("User-Agent" to UA)).text
        } catch (_: Exception) { return emptyList() }
        val itemsRaw = extractJsonParse(html, "items") ?: return emptyList()
        return try {
            val arr = JSONArray(itemsRaw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val main = o.optString("main_title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val titles = mutableListOf(main)
                o.optJSONObject("title_list")?.let { tl ->
                    val keys = tl.keys()
                    while (keys.hasNext()) {
                        val t = tl.optString(keys.next()).takeIf { it.isNotBlank() }
                        if (t != null && t !in titles) titles.add(t)
                    }
                }
                Hit(slug, titles)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun pickBest(hits: List<Hit>, query: String): Hit? {
        if (hits.isEmpty()) return null
        val q = normalize(query)
        hits.firstOrNull { h -> h.titles.any { normalize(it) == q } }?.let { return it }
        hits.firstOrNull { h -> h.titles.any { t ->
            val n = normalize(t)
            n.length >= 3 && (q.contains(n) || n.contains(q))
        } }?.let { return it }
        return hits.first()
    }

    private suspend fun getSubtitles(slug: String, ep: Int): List<Sub> {
        val url = "$BASE/anime/$slug/$ep"
        val html = try {
            app.get(url, headers = mapOf("User-Agent" to UA)).text
        } catch (_: Exception) { return emptyList() }
        val m = RX_PLAYER.find(html) ?: return emptyList()
        val json = unescapeJs(m.groupValues[1])
        return try {
            val root = JSONObject(json)
            val subs = root.optJSONArray("subtitles") ?: return emptyList()
            val out = mutableListOf<Sub>()
            for (i in 0 until subs.length()) {
                val o = subs.optJSONObject(i) ?: continue
                val label = o.optString("title").takeIf { it.isNotBlank() } ?: continue
                val file = o.optString("file").takeIf { it.isNotBlank() } ?: continue
                out.add(Sub(label, file))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    suspend fun fetch(title: String, episode: Int): List<Sub> {
        val hits = search(title)
        val hit = pickBest(hits, title) ?: return emptyList()
        return getSubtitles(hit.slug, episode)
    }
}
