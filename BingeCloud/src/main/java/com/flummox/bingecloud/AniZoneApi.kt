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

    data class Hit(val slug: String, val title: String, val allTitles: List<String>, val year: Int?, val episodes: Int)
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

    // ── query variants (shortening) ──
    private fun buildVariants(query: String): List<String> {
        val out = linkedSetOf<String>()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        out.add(q)
        val base = q.split(RX_CUT, limit = 2).firstOrNull()?.trim()
        if (!base.isNullOrBlank() && base != q && base.length >= 3) out.add(base)
        val words = q.split(" ").filter { it.isNotBlank() }
        if (words.size > 3) out.add(words.take(3).joinToString(" ").trimEnd(':', '-', '–', '—').trim())
        if (words.size > 2) out.add(words.take(2).joinToString(" ").trimEnd(':', '-', '–', '—').trim())
        return out.filter { it.isNotBlank() }.toList()
    }

    // ── search (cached) ──
    suspend fun search(query: String): List<Hit> {
        val ck = searchKey(query)
        BCCache.get(ck, SEARCH_TTL)?.let { cached ->
            return try {
                val arr = JSONArray(cached)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val allTitles = mutableListOf<String>()
                    o.optJSONArray("a")?.let { a ->
                        for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let { allTitles.add(it) }
                    }
                    if (allTitles.isEmpty()) allTitles.add(o.optString("t"))
                    Hit(
                        slug = o.optString("s"),
                        title = o.optString("t"),
                        allTitles = allTitles,
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
                val mainTitle = o.optString("main_title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val allTitles = mutableListOf(mainTitle)
                o.optJSONObject("title_list")?.let { tl ->
                    val keys = tl.keys()
                    while (keys.hasNext()) {
                        val t = tl.optString(keys.next()).takeIf { it.isNotBlank() }
                        if (t != null && t !in allTitles) allTitles.add(t)
                    }
                }
                Hit(
                    slug = slug,
                    title = mainTitle,
                    allTitles = allTitles,
                    year = o.optInt("start_year", 0).takeIf { it > 0 },
                    episodes = o.optInt("episode_count", 0)
                )
            }
        } catch (e: Exception) { BCLog.e("AniZone search parse: ${e.message}"); emptyList() }

        if (hits.isNotEmpty()) {
            try {
                val arr = JSONArray()
                for (h in hits) arr.put(JSONObject().apply {
                    put("s", h.slug); put("t", h.title); put("y", h.year ?: 0); put("e", h.episodes)
                    put("a", JSONArray().apply { h.allTitles.forEach { put(it) } })
                })
                BCCache.put(ck, arr.toString())
            } catch (_: Exception) {}
        }
        return hits
    }

    // Priority: exact (any title) → containment (any title) → fuzzy → year → episode count
    fun pickBest(hits: List<Hit>, query: String, year: String, altTitles: List<String> = emptyList()): Hit? {
        if (hits.isEmpty()) return null
        val queries = (listOf(query) + altTitles).map { normalize(it) }.filter { it.isNotBlank() }.distinct()
        val yr = year.toIntOrNull()

        // 1. Exact match against any variant against any hit title
        val exact = hits.filter { h -> h.allTitles.any { at -> queries.any { q -> normalize(at) == q } } }
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) {
            if (yr != null) exact.firstOrNull { it.year == yr }?.let { return it }
            return exact.maxByOrNull { it.episodes }
        }

        // 2. Containment — prefer candidate whose title length is closest
        //    to the query length. Prevents picking "Attack on Titan" (S1)
        //    when query is "Attack on Titan: Final Season Part 2".
        val contained = hits.mapNotNull { h ->
            var best = Int.MAX_VALUE
            for (at in h.allTitles) {
                val cn = normalize(at)
                if (cn.length < 3) continue
                for (q in queries) {
                    if (q.contains(cn) || cn.contains(q)) {
                       val d = kotlin.math.abs(cn.length - q.length)
                       if (d < best) best = d
                   }
               }
            }
            if (best == Int.MAX_VALUE) null else h to best
        }
        if (contained.isNotEmpty()) {
            val pool = if (yr != null) {
                contained.filter { it.first.year == yr }.ifEmpty { contained }
           } else contained
           return pool.minByOrNull { it.second }?.first
       }

        // 3. Fuzzy via titleMatches
        val fuzzy = hits.filter { h -> h.allTitles.any { at -> titleMatches(query, at) } }
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

         // ── pick best AniList entry for a query ──
private fun pickBestAniList(hits: List<AniListApi.Entry>, query: String, year: String): AniListApi.Entry? {
    if (hits.isEmpty()) return null
    val qn = normalize(query)
    val yr = year.toIntOrNull()

    val exact = hits.filter { h -> h.title.all().any { normalize(it) == qn } }
    if (exact.isNotEmpty()) {
        if (yr != null) exact.firstOrNull { it.seasonYear == yr }?.let { return it }
        return exact.first()
    }

    val contained = hits.filter { h ->
        h.title.all().any { at ->
            val cn = normalize(at)
            cn.length >= 3 && (qn.contains(cn) || cn.contains(qn))
        }
    }
    if (contained.isNotEmpty()) {
        if (yr != null) contained.firstOrNull { it.seasonYear == yr }?.let { return it }
        return contained.first()
    }

    return hits.firstOrNull()
}

// ── inverse Part case: TMDB combined, site split ──
// Called when site's episode list is smaller than q.episode.
// Walks AniList chain forward, subtracting each part's episode count,
// until target episode lands on the correct part.
private suspend fun tryNextPartInChain(
    q: StreamQuery,
    currentSlug: String,
    currentSiteCount: Int,
    startMs: Long
): List<ScrapedMirror> {
    if (q.type != "series") return emptyList()
    if (q.episode <= currentSiteCount) return emptyList()
    BCLog.d("AniZone: chain walk for E${q.episode} > site=$currentSiteCount")

    val alHits = AniListApi.searchAnime(q.title, q.year.toIntOrNull())
    val alEntry = alHits.firstOrNull { h ->
        h.title.all().any { t -> titleMatches(q.title, t) }
    } ?: return emptyList()
    val chain = AniListApi.resolveChain(alEntry.id)
    if (chain.size < 2) return emptyList()
    val idx = chain.indexOfFirst { it.id == alEntry.id }
    if (idx < 0) return emptyList()

    var remaining = q.episode
    var cursor = idx
    while (remaining > 0 && cursor < chain.size) {
        val entryEps = chain[cursor].episodes ?: 0
        if (entryEps <= 0) return emptyList()
        if (remaining <= entryEps) {
            if (cursor == idx) return emptyList()
            val target = chain[cursor]
            val targetTitle = target.title.romaji ?: target.title.english ?: q.title
            BCLog.d("AniZone: next part '$targetTitle' E$remaining (steps=${cursor - idx})")

            var nextHits = emptyList<Hit>()
            val tried = mutableSetOf<String>()
            for (t in target.title.all() + buildVariants(q.title)) {
                if (!tried.add(t.lowercase())) continue
                nextHits = search(t).filter { it.slug != currentSlug }
                if (nextHits.isNotEmpty()) break
            }
            if (nextHits.isEmpty()) return emptyList()

            val nextHit = pickBest(
                nextHits,
                targetTitle,
                target.seasonYear?.toString() ?: "",
                target.title.all()
            ) ?: return emptyList()

            val stream = getStream(nextHit.slug, remaining) ?: return emptyList()
            BCLog.d("AniZone: next-part hit '${nextHit.title}' E$remaining (${System.currentTimeMillis() - startMs}ms)")
            return listOf(mirror(nextHit, remaining, null, stream))
        }
        remaining -= entryEps
        cursor++
    }
    return emptyList()
}

// ── main entry ──
suspend fun resolve(q: StreamQuery): List<ScrapedMirror> {
    val start = System.currentTimeMillis()
    val isAnime = q.originalLanguage in setOf("ja", "ko", "zh")

    // Phase 1 — AniList title variants (anime only)
    var altTitles = emptyList<String>()
    if (isAnime) {
        val alHits = AniListApi.searchAnime(q.title, q.year.toIntOrNull())
        if (alHits.isNotEmpty()) {
            val best = pickBestAniList(alHits, q.title, q.year)
            if (best != null) {
                altTitles = best.title.all()
                BCLog.d("AniList: '${best.title.romaji ?: best.title.english}' (${best.episodes ?: 0}eps) alts=${altTitles.size} = $altTitles")
            }
        }
    }

    // Phase 2 — search AniZone: AniList titles first, then original + variants
    var hits = emptyList<Hit>()
    val tried = mutableSetOf<String>()
    for (s in (altTitles + buildVariants(q.title))) {
        if (!tried.add(s.lowercase())) continue
        hits = search(s)
        if (hits.isNotEmpty()) break
    }

    if (hits.isEmpty()) { BCLog.d("AniZone: no hits (${System.currentTimeMillis() - start}ms)"); return emptyList() }
    val hit = pickBest(hits, q.title, q.year, altTitles) ?: run {
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

        val ep = eps.firstOrNull { it.number == targetEp }
        if (ep == null) {
            BCLog.d("AniZone: E$targetEp not found (have ${eps.size}) — checking next part")
            return tryNextPartInChain(q, hit.slug, eps.size, start)
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
