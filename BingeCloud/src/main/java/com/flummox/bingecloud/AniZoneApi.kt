package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object AniZoneApi {
    private const val BASE = "https://anizone.to"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val RX_NON_ALNUM = Regex("""[^\p{L}\p{N}\s]""")
    private val RX_WS = Regex("""\s+""")
    private val RX_JSON_PARSE_TPL = Regex("""\b(%s)\s*:\s*JSON\.parse\(\s*['"](.+?)['"]\s*\)""", RegexOption.DOT_MATCHES_ALL)
    private val RX_PLAYER = Regex("""vidstackPlayer\(\s*JSON\.parse\(\s*['"](.+?)['"]\s*\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
    private val RX_CUT = Regex("""\s*[:\-–—]\s+""")

    data class Hit(val slug: String, val title: String, val year: Int?, val episodes: Int)
    data class Episode(val number: Int, val title: String?)
    data class StreamResult(val url: String, val subtitles: List<Pair<String, String>>)

    private fun searchKey(q: String) = "anizone:s:${q.lowercase()}"
    private fun epsKey(slug: String) = "anizone:e:$slug"
    private const val SEARCH_TTL = 30 * 60 * 1000L
    private const val EPS_TTL = 60 * 60 * 1000L

    // ── parsing helpers ──
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
        val rx = Regex(RX_JSON_PARSE_TPL.pattern.format(Regex.escape(key)), RegexOption.DOT_MATCHES_ALL)
        return rx.find(html)?.groupValues?.get(2)?.let { unescapeJs(it) }
    }

    fun normalize(s: String): String =
        s.lowercase().trim().replace(RX_NON_ALNUM, " ").replace(RX_WS, " ").trim()

    // ── query variants ──
    // Try full title first; if 0 hits, retry with shortened forms. Cached per
    // variant, so repeat plays cost nothing.
    private fun buildVariants(query: String): List<String> {
        val out = linkedSetOf<String>()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        out.add(q)

        // Base = everything before first `:` or ` - `
        val base = q.split(RX_CUT, limit = 2).firstOrNull()?.trim()
        if (!base.isNullOrBlank() && base != q && base.length >= 3) out.add(base)

        val words = q.split(" ").filter { it.isNotBlank() }
        if (words.size > 3) {
            out.add(words.take(3).joinToString(" ").trimEnd(':', '-', '–', '—').trim())
        }
        if (words.size > 2) {
            out.add(words.take(2).joinToString(" ").trimEnd(':', '-', '–', '—').trim())
        }
        return out.filter { it.isNotBlank() }.toList()
    }

    suspend fun searchWithVariants(query: String): List<Hit> {
        val variants = buildVariants(query)
        for ((idx, v) in variants.withIndex()) {
            val hits = search(v)
            if (hits.isNotEmpty()) {
                if (idx > 0) BCLog.d("AniZone: variant '$v' → ${hits.size} hits")
                return hits
            }
        }
        return emptyList()
    }

    // ── search (cached) ──
    suspend fun search(query: String): List<Hit> {
        val ck = searchKey(query)
        BCCache.get(ck, SEARCH_TTL)?.let { cached ->
            return try {
                val arr = JSONArray(cached)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Hit(
                        slug = o.optString("s"),
                        title = o.optString("t"),
                        year = o.optInt("y", 0).takeIf { it > 0 },
                        episodes = o.optInt("e", 0)
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/anime?search=${URLEncoder.encode(query, "UTF-8")}"
        val html = try { app.get(url, headers = mapOf("User-Agent" to UA)).text }
        catch (e: Exception) { BCLog.e("AniZone search failed: ${e.message}"); return emptyList() }

        val itemsRaw = extractJsonParse(html, "items") ?: return emptyList()
        val hits = try {
            val arr = JSONArray(itemsRaw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = o.optString("main_title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Hit(slug, title, o.optInt("start_year", 0).takeIf { it > 0 }, o.optInt("episode_count", 0))
            }
        } catch (e: Exception) { BCLog.e("AniZone search parse: ${e.message}"); emptyList() }

        if (hits.isNotEmpty()) {
            try {
                val arr = JSONArray()
                for (h in hits) arr.put(JSONObject().apply {
                    put("s", h.slug); put("t", h.title); put("y", h.year ?: 0); put("e", h.episodes)
                })
                BCCache.put(ck, arr.toString())
            } catch (_: Exception) {}
        }
        return hits
    }

    // Priority: exact name → containment → fuzzy → year → episode count (last)
    fun pickBest(hits: List<Hit>, query: String, year: String): Hit? {
        if (hits.isEmpty()) return null
        val qn = normalize(query)
        val yr = year.toIntOrNull()

        // 1. Exact normalized name
        val exact = hits.filter { normalize(it.title) == qn }
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) {
            if (yr != null) exact.firstOrNull { it.year == yr }?.let { return it }
            return exact.maxByOrNull { it.episodes }
        }

        // 2. Containment: candidate title fully inside query, or query fully inside candidate
        val contained = hits.filter {
            val cn = normalize(it.title)
            cn.isNotBlank() && cn.length >= 3 && (qn.contains(cn) || cn.contains(qn))
        }
        if (contained.isNotEmpty()) {
            if (yr != null) contained.firstOrNull { it.year == yr }?.let { return it }
            return contained.maxByOrNull { it.episodes }
        }

        // 3. Fuzzy via shared titleMatches()
        val fuzzy = hits.filter { titleMatches(query, it.title) }
        if (fuzzy.isEmpty()) return null
        if (fuzzy.size == 1) return fuzzy[0]
        if (yr != null) fuzzy.firstOrNull { it.year == yr }?.let { return it }
        return fuzzy.maxByOrNull { it.episodes }
    }

    // ── episodes (cached) ──
    suspend fun getEpisodes(slug: String): List<Episode> {
        val ck = epsKey(slug)
        BCCache.get(ck, EPS_TTL)?.let { cached ->
            return try {
                val arr = JSONArray(cached)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val n = o.optInt("n", 0).takeIf { it > 0 } ?: return@mapNotNull null
                    Episode(n, o.optString("t").takeIf { it.isNotBlank() })
                }
            } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/anime/$slug"
        val html = try { app.get(url, headers = mapOf("User-Agent" to UA)).text }
        catch (e: Exception) { BCLog.e("AniZone detail failed: ${e.message}"); return emptyList() }

        val itemsRaw = extractJsonParse(html, "items") ?: return emptyList()
        val eps = try {
            val arr = JSONArray(itemsRaw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val num = o.optString("slug").toIntOrNull() ?: return@mapNotNull null
                val tl = o.optJSONObject("title_list")
                val t = tl?.optString("1")?.takeIf { it.isNotBlank() }
                    ?: tl?.optString("2")?.takeIf { it.isNotBlank() }
                Episode(num, t)
            }.distinctBy { it.number }.sortedBy { it.number }
        } catch (e: Exception) { BCLog.e("AniZone episodes parse: ${e.message}"); emptyList() }

        if (eps.isNotEmpty()) {
            try {
                val arr = JSONArray()
                for (e in eps) arr.put(JSONObject().apply {
                    put("n", e.number); put("t", e.title ?: "")
                })
                BCCache.put(ck, arr.toString())
            } catch (_: Exception) {}
        }
        return eps
    }

    // ── stream ──
    suspend fun getStream(slug: String, episode: Int): StreamResult? {
        val url = "$BASE/anime/$slug/$episode"
        val html = try { app.get(url, headers = mapOf("User-Agent" to UA)).text }
        catch (e: Exception) { BCLog.e("AniZone player failed: ${e.message}"); return null }

        val m = RX_PLAYER.find(html) ?: return null
        val json = unescapeJs(m.groupValues[1])
        return try {
            val root = JSONObject(json)
            val src = root.optString("src").takeIf { it.isNotBlank() } ?: return null
            val subs = mutableListOf<Pair<String, String>>()
            root.optJSONArray("subtitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val label = o.optString("title").takeIf { it.isNotBlank() } ?: continue
                    val file = o.optString("file").takeIf { it.isNotBlank() } ?: continue
                    subs.add(label to file)
                }
            }
            StreamResult(src, subs)
        } catch (e: Exception) {
            BCLog.e("AniZone stream parse: ${e.message}"); null
        }
    }

    // ── main entry ──
    suspend fun resolve(q: StreamQuery): List<ScrapedMirror> {
        val start = System.currentTimeMillis()

        val hits = searchWithVariants(q.title)
        if (hits.isEmpty()) { BCLog.d("AniZone: no hits (${System.currentTimeMillis() - start}ms)"); return emptyList() }
        val hit = pickBest(hits, q.title, q.year) ?: run {
            BCLog.d("AniZone: no name match — top: ${hits.take(5).joinToString(" | ") { it.title }}")
            return emptyList()
        }

        val targetEp = when {
            q.type == "movie" -> 1
            q.episode > 0 -> q.episode
            else -> 1
        }

        val fastStream = getStream(hit.slug, targetEp)
        if (fastStream != null) {
            BCLog.d("AniZone: fast hit '${hit.title}' E$targetEp (${System.currentTimeMillis() - start}ms)")
            return listOf(mirror(hit, targetEp, null, fastStream))
        }

        BCLog.d("AniZone: fast miss E$targetEp, fetching episode list")
        val eps = getEpisodes(hit.slug)
        if (eps.isEmpty()) { BCLog.d("AniZone: no episodes"); return emptyList() }

        val ep = eps.firstOrNull { it.number == targetEp } ?: run {
            BCLog.d("AniZone: episode $targetEp not found (have ${eps.size})")
            return emptyList()
        }

        val stream = getStream(hit.slug, ep.number) ?: run {
            BCLog.d("AniZone: stream failed at E${ep.number}"); return emptyList()
        }
        BCLog.d("AniZone: slow hit '${hit.title}' E${ep.number} (${System.currentTimeMillis() - start}ms)")
        return listOf(mirror(hit, ep.number, ep.title, stream))
    }

    private fun mirror(hit: Hit, epNum: Int, epTitle: String?, s: StreamResult): ScrapedMirror =
        ScrapedMirror(
            quality = "Auto",
            mirror = buildString {
                append("AniZone")
                if (epNum > 0) append(" E$epNum")
                epTitle?.take(28)?.let { append(" •$it") }
            },
            url = s.url,
            source = "ANIZONE",
            headers = mapOf("Referer" to "https://anizone.to/"),
            captions = s.subtitles
        )
}
