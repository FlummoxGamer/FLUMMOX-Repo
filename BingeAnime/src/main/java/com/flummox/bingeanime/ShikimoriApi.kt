package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// Shikimori — Russian MAL mirror with a documented, stable JSON API.
// Rate limit: 5 req/s, 90 req/min per IP (no daily cap).
// Custom User-Agent required — browser UAs are banned on sight.
object ShikimoriApi {
    private const val BASE = "https://shikimori.one/api"
    private const val IMG_BASE = "https://shikimori.one"
    private const val UA = "BingeAnime/1.0"
    private const val ROW_TTL = 6 * 60 * 60 * 1000L
    private const val PREFETCH_TTL = 6 * 60 * 60 * 1000L

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastPrefetchMs = 0L
    @Volatile private var prefetchRunning = false

    // In-flight dedup — home page and prefetch both call fetchForRow.
    // Without this, both fire their own HTTP request for the same row.
    private val inFlightRows =
        ConcurrentHashMap<String, CompletableDeferred<List<AniListApi.Entry>>>()

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to UA
    )

    // Row name → Shikimori query params.
    // MAL splits: `genre` for genres, `theme` for themes.
    //   Isekai, School, Historical are THEMES in MAL.
    //   Action, Adventure, Comedy, Drama, Fantasy, Romance, Sci-Fi,
    //   Slice of Life, Supernatural, Mystery, Sports, Mecha are GENRES.
    private val ROW_PARAMS: LinkedHashMap<String, Map<String, String>> = linkedMapOf(
        "Trending"          to mapOf("order" to "popularity"),
        "Top Anime Series"  to mapOf("kind" to "tv", "order" to "ranked"),
        "Top Anime Movies"  to mapOf("kind" to "movie", "order" to "ranked"),
        "Action"            to mapOf("genre" to "1", "order" to "popularity"),
        "Adventure"         to mapOf("genre" to "2", "order" to "popularity"),
        "Isekai"            to mapOf("theme" to "62", "order" to "popularity"),
        "Comedy"            to mapOf("genre" to "4", "order" to "popularity"),
        "Drama"             to mapOf("genre" to "8", "order" to "popularity"),
        "Fantasy"           to mapOf("genre" to "10", "order" to "popularity"),
        "Romance"           to mapOf("genre" to "22", "order" to "popularity"),
        "Sci-Fi"            to mapOf("genre" to "24", "order" to "popularity"),
        "Slice of Life"     to mapOf("genre" to "36", "order" to "popularity"),
        "Supernatural"      to mapOf("genre" to "37", "order" to "popularity"),
        "Mystery"           to mapOf("genre" to "7", "order" to "popularity"),
        "Sports"            to mapOf("genre" to "30", "order" to "popularity"),
        "Mecha"             to mapOf("genre" to "18", "order" to "popularity"),
        "School"            to mapOf("theme" to "23", "order" to "popularity"),
        "Historical"        to mapOf("theme" to "13", "order" to "popularity")
    )

    // ── public entry ──
    suspend fun fetchForRow(rowName: String, limit: Int = 30): List<AniListApi.Entry> {
        val params = ROW_PARAMS[rowName] ?: run {
            BLog.v("shikimori: unknown row '$rowName'")
            return emptyList()
        }
        val ck = "shikimori:row:$rowName:$limit"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            BLog.v("shikimori '$rowName' cache hit (${cached.length} bytes)")
            return try { parseList(JSONArray(cached)) } catch (e: Exception) {
                BLog.e("shikimori '$rowName' cache parse failed: ${e.message}")
                emptyList()
            }
        }

        // In-flight dedup — if prefetch or another caller is fetching
        // the same row, wait for their result instead of firing a
        // second request.
        inFlightRows[ck]?.let {
            BLog.v("shikimori '$rowName' joining in-flight")
            return it.await()
        }
        val deferred = CompletableDeferred<List<AniListApi.Entry>>()
        val prior = inFlightRows.putIfAbsent(ck, deferred)
        if (prior != null) {
            BLog.v("shikimori '$rowName' joining race")
            return prior.await()
        }

        return try {
            val result = fetchAndCache(ck, rowName, params, limit)
            deferred.complete(result)
            result
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            inFlightRows.remove(ck)
        }
    }

    private suspend fun fetchAndCache(
        ck: String,
        rowName: String,
        params: Map<String, String>,
        limit: Int
    ): List<AniListApi.Entry> {
        val query = buildString {
            params.forEach { (k, v) -> append("$k=$v&") }
            append("limit=$limit")
        }
        val url = "$BASE/animes?$query"
        return try {
            val res = app.get(url, headers = headers())
            BLog.v("shikimori '$rowName' HTTP ${res.code} len=${res.text.length}")
            if (res.code == 429) {
                BLog.e("shikimori '$rowName' 429 rate limited")
                return emptyList()
            }
            if (res.code !in 200..299) {
                BLog.e("shikimori '$rowName' HTTP ${res.code}: ${res.text.take(200)}")
                return emptyList()
            }
            val arr = JSONArray(res.text)
            BCCache.put(ck, arr.toString())
            val parsed = parseList(arr)
            BLog.v("shikimori '$rowName' parsed ${parsed.size} entries")
            parsed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("shikimori '$rowName' failed: ${e.message}")
            emptyList()
        }
    }

    // ── detail ──
    suspend fun detail(shikiId: Int): AniListApi.Entry? {
        val ck = "shikimori:detail:$shikiId"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            return try { parseEntry(JSONObject(cached)) } catch (_: Exception) { null }
        }
        return try {
            val res = app.get("$BASE/animes/$shikiId", headers = headers())
            BLog.v("shikimori detail $shikiId HTTP ${res.code} len=${res.text.length}")
            if (res.code !in 200..299) return null
            val obj = JSONObject(res.text)
            BCCache.put(ck, obj.toString())
            parseEntry(obj)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("shikimori detail $shikiId failed: ${e.message}")
            null
        }
    }

    // ── prefetch warm-up ──
    // Sequential in ROWS order with 150ms gap. Top rows land first
    // (visible on first render), lower rows fill in within ~5 seconds.
    // 1 request per ~350ms = ~3/sec peak, well under 5/sec limit.
    fun warmPrefetch() {
        val now = System.currentTimeMillis()
        if (now - lastPrefetchMs < PREFETCH_TTL) {
            BLog.v("shikimori prefetch skipped (fresh ${(now - lastPrefetchMs) / 1000}s)")
            return
        }
        if (prefetchRunning) {
            BLog.v("shikimori prefetch already running")
            return
        }
        prefetchRunning = true
        prefetchScope.launch {
            val start = System.currentTimeMillis()
            BLog.d("shikimori prefetch start (${ROW_PARAMS.size} rows, sequential, 150ms gap)")
            var ok = 0
            var fail = 0
            for (rowName in ROW_PARAMS.keys) {
                try {
                    val result = fetchForRow(rowName, 30)
                    if (result.isNotEmpty()) ok++ else fail++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    BLog.v("shikimori prefetch cancelled")
                    prefetchRunning = false
                    return@launch
                } catch (e: Exception) {
                    BLog.e("prefetch '$rowName' failed: ${e.message}")
                    fail++
                }
                delay(150)
            }
            lastPrefetchMs = System.currentTimeMillis()
            BLog.d("shikimori prefetch done in ${System.currentTimeMillis() - start}ms ($ok ok, $fail failed)")
            prefetchRunning = false
        }
    }

    // ── parse ──
    private fun parseList(arr: JSONArray): List<AniListApi.Entry> {
        val out = mutableListOf<AniListApi.Entry>()
        for (i in 0 until arr.length()) {
            parseEntry(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    private fun parseEntry(o: JSONObject?): AniListApi.Entry? {
        if (o == null) return null
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
        val russian = o.optString("russian").takeIf { it.isNotBlank() && it != "null" }

        val imgObj = o.optJSONObject("image")
        val imgPath = imgObj?.optString("original")?.takeIf { it.isNotBlank() }
            ?: imgObj?.optString("preview")?.takeIf { it.isNotBlank() }
        val cover = imgPath?.let { if (it.startsWith("http")) it else "$IMG_BASE$it" }

        val kind = o.optString("kind").takeIf { it.isNotBlank() }
        val format = when (kind) {
            "tv" -> "TV"
            "movie" -> "MOVIE"
            "ova" -> "OVA"
            "ona" -> "ONA"
            "special" -> "SPECIAL"
            "tv_special" -> "SPECIAL"
            "music" -> "MUSIC"
            else -> null
        }

        val scoreStr = o.optString("score").takeIf { it.isNotBlank() && it != "null" }
        val score = scoreStr?.toDoubleOrNull()
        val averageScore = score?.let { (it * 10).toInt() }

        val airedOn = o.optString("aired_on").takeIf { it.isNotBlank() && it != "null" }
        val year = airedOn?.take(4)?.toIntOrNull()

        val episodes = o.optInt("episodes", 0).takeIf { it > 0 }

        val statusRaw = o.optString("status").takeIf { it.isNotBlank() }
        val status = when (statusRaw) {
            "released" -> "FINISHED"
            "ongoing" -> "RELEASING"
            "anons" -> "NOT_YET_RELEASED"
            else -> statusRaw
        }

        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }?.takeIf { it.isNotEmpty() }

        return AniListApi.Entry(
            id = id,
            idMal = null,
            title = AniListApi.Title(
                romaji = name,
                english = null,
                native = russian
            ),
            format = format,
            episodes = episodes,
            seasonYear = year,
            startDate = airedOn,
            description = null,
            coverImage = cover,
            averageScore = averageScore,
            status = status,
            genres = genres,
            country = null,
            relations = emptyList(),
            source = "shikimori"
        )
    }
}
