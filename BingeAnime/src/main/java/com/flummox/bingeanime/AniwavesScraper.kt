package com.flummox.bingeanime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val ANIWAVES_CONCURRENCY = 4

private fun pickBest(hits: List<AniwavesApi.Hit>, query: String, year: Int?): AniwavesApi.Hit? {
    if (hits.isEmpty()) return null
    fun norm(s: String) = s.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ")
        .replace(Regex("""\s+"""), " ").trim()
    val qn = norm(query)
    hits.firstOrNull { norm(it.title) == qn && (year == null || it.year == year) }?.let { return it }
    hits.firstOrNull { norm(it.title) == qn }?.let { return it }
    val contain = hits.filter { val n = norm(it.title); n.isNotBlank() && (n.contains(qn) || qn.contains(n)) }
    if (contain.isNotEmpty()) return contain.firstOrNull { year != null && it.year == year } ?: contain.first()
    val qw = qn.split(" ").toSet()
    return hits.mapNotNull { h ->
        val hw = norm(h.title).split(" ").toSet()
        val common = qw.intersect(hw).size
        if (common == 0) null else h to (common.toFloat() / maxOf(qw.size, hw.size))
    }.maxByOrNull { it.second }?.first
}

suspend fun aniwavesExtractRaw(
    q: StreamQuery,
    onLink: (suspend (ScrapedMirror, Int) -> Unit)? = null,
    onSub: (suspend (String, String) -> Unit)? = null
): AniKageScrape {
    val yr = q.year.toIntOrNull()
    BLog.d("aniwaves: search '${q.title}' ($yr)")

    val hit = pickBest(AniwavesApi.search(q.title), q.title, yr) ?: run {
        BLog.d("aniwaves: no match"); return AniKageScrape(emptyList(), emptyList())
    }
    BLog.d("aniwaves: hit '${hit.title}' slug=${hit.slug}")

    val detail = AniwavesApi.detail(hit.slug) ?: run {
        BLog.d("aniwaves: detail failed"); return AniKageScrape(emptyList(), emptyList())
    }
    BLog.d("aniwaves: '${detail.title}' eps=${detail.episodes.size} movie=${detail.isMovie}")

    val ep = if (q.type == "movie") 1 else q.episode.takeIf { it > 0 } ?: 1
    val servers = AniwavesApi.servers(hit.slug, ep, detail.id)
    if (servers.isEmpty()) {
        BLog.d("aniwaves: no servers E$ep")
        return AniKageScrape(emptyList(), emptyList())
    }
    BLog.d("aniwaves: ${servers.size} servers E$ep")

    val sem = Semaphore(ANIWAVES_CONCURRENCY)
    val collected = ConcurrentHashMap<String, ScrapedMirror>()
    val subs = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    val emitted = AtomicInteger(0)
    val referer = "https://aniwaves.ru/watch/${hit.slug}/ep-$ep"

    coroutineScope {
        servers.map { srv ->
            async {
                try {
                    sem.withPermit {
                        val embed = AniwavesApi.resolveLink(srv.linkId, referer) ?: return@withPermit
                        BLog.v("aniwaves ${srv.subType}/${srv.label}: $embed")
                        // Only emit mfw09.org embeds — ByseExtractor handles them.
                        // play.echovideo.ru embeds are JW Player + MSE blob, not
                        // extractable without running their JS; skip them for now.
                        if (!embed.contains("mfw09.org")) {
                            BLog.v("aniwaves: skip non-byse embed ${embed.take(80)}")
                            return@withPermit
                        }
                        val m = ScrapedMirror(
                            quality = "Auto",
                            mirror = "${srv.label} · ${srv.subType.uppercase()}",
                            url = embed,
                            source = "ANIWAVES",
                            headers = null,
                            captions = emptyList()
                        )
                        if (synchronized(collected) { collected.putIfAbsent(m.url, m) == null }) {
                            val score = LinkScore.prelimScore(m)
                            try { onLink?.invoke(m, score) } catch (_: Exception) {}
                            emitted.incrementAndGet()
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { BLog.v("aniwaves ${srv.label}: ${e.message}") }
            }
        }.awaitAll()
    }

    val mirrors = synchronized(collected) { collected.values.toList() }
    val subsOut = synchronized(subs) { subs.toList() }
    BLog.d("aniwaves: ${mirrors.size} mirrors, ${subsOut.size} subs")
    return AniKageScrape(mirrors, subsOut)
}
