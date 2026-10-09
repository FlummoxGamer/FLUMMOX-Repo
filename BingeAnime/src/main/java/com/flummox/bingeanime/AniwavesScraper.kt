package com.flummox.bingeanime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val ANIWAVES_CONCURRENCY = 4

// Server IDs (data-sv-id) known dead / unplayable.
//   2 = DGHG → redirects to playmogo.com, dead on site too.
private val DROPPED_SV_IDS = setOf("2")

// Hosts that resolve but have no extractor.
private val DROPPED_EMBED_HOSTS = setOf("playmogo.com")

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

private fun hostOf(url: String): String = try {
    java.net.URI(url).host?.lowercase() ?: ""
} catch (_: Exception) { "" }

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

    val ep = if (q.type == "movie") 1 else q.episode.takeIf { it > 0 } ?: 1
    val servers = AniwavesApi.servers(hit.slug, ep, detail.id)
        .filter { it.serverId !in DROPPED_SV_IDS }
    if (servers.isEmpty()) {
        BLog.d("aniwaves: no servers E$ep")
        return AniKageScrape(emptyList(), emptyList())
    }
    BLog.d("aniwaves: ${servers.size} servers E$ep (after drop)")

    val sem = Semaphore(ANIWAVES_CONCURRENCY)
    val collected = ConcurrentHashMap<String, ScrapedMirror>()
    val emitted = AtomicInteger(0)
    val referer = "https://aniwaves.ru/watch/${hit.slug}/ep-$ep"

    coroutineScope {
        servers.map { srv ->
            async {
                try {
                    sem.withPermit {
                        val embed = AniwavesApi.resolveLink(srv.linkId, referer) ?: return@withPermit
                        val host = hostOf(embed)
                        BLog.v("aniwaves ${srv.subType}/${srv.label} [$host]: $embed")
                        if (host in DROPPED_EMBED_HOSTS) {
                            BLog.d("aniwaves: skip dead host $host (${srv.label})")
                            return@withPermit
                        }
                        // Only mfw09.org (Byse) and play.echovideo.ru (Echovideo)
                        // have registered extractors.
                        if (host != "mfw09.org" && host != "play.echovideo.ru") {
                            BLog.d("aniwaves: skip unhandled host $host (${srv.label})")
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
    BLog.d("aniwaves: ${mirrors.size} mirrors emitted")
    return AniKageScrape(mirrors, emptyList())
}
