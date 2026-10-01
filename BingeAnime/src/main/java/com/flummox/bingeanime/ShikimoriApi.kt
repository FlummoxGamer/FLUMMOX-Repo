package com.flummox.bingeanime

import android.content.Context
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object ShikimoriApi {
    private const val BASE = "https://shikimori.one/api"
    private const val IMG_BASE = "https://shikimori.one"
    private const val UA = "BingeAnime/1.0"
    private const val ROW_TTL = 6 * 60 * 60 * 1000L
    private const val GENRE_TTL = 7L * 24 * 60 * 60 * 1000

    // Global rate limiter — minimum 220ms between any two HTTP requests.
    private val rateMutex = Mutex()
    private var lastRequestMs = 0L
    private const val MIN_GAP_MS = 220L

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastPrefetchMs = 0L
    @Volatile private var prefetchRunning = false

    private val inFlightRows =
        ConcurrentHashMap<String, CompletableDeferred<List<AniListApi.Entry>>>()

    // Genre map: rowName -> Shikimori genre ID.
    // Fetched from /api/genres on first run, cached to SharedPreferences
    // for 30 days. Fallback values are the verified IDs from the live API.
    private var genreMap: Map<String, Int>? = null
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bingeanime_shikimori", Context.MODE_PRIVATE)
        val cached = prefs.getString("genre_map", null)
        val ts = prefs.getLong("genre_map_ts", 0L)
        if (cached != null && System.currentTimeMillis() - ts < GENRE_TTL) {
            try {
                val obj = JSONObject(cached)
                val map = mutableMapOf<String, Int>()
                obj.keys().forEach { k -> map[k] = obj.getInt(k) }
                genreMap = map
                BLog.v("shikimori genre map loaded from prefs (${map.size} entries)")
            } catch (_: Exception) {}
        }
    }

    // Row name → query params. Genre/theme IDs are NOT hardcoded.
// They're resolved at runtime from /api/genres, cached 7 days in
// SharedPreferences. If a name isn't found, the row is skipped.
// This prevents the "wrong genre in wrong row" bug we hit when
// IDs were guessed (Death Note in Isekai, School==Historical).
private val ROW_QUERY: LinkedHashMap<String, Triple<String, String, String>> = linkedMapOf(
    "Trending"          to Triple("order", "popularity", "popularity"),
    "Top Anime Series"  to Triple("kind", "tv", "ranked"),
    "Top Anime Movies"  to Triple("kind", "movie", "ranked"),
    // Remaining rows are genre-name lookups resolved at runtime.
    // The value field is the row's display name — matched against
    // /api/genres response.
    "Action"            to Triple("genre_name", "Action", "ranked"),
    "Adventure"         to Triple("genre_name", "Adventure", "ranked"),
    "Isekai"            to Triple("theme_name", "Isekai", "ranked"),
    "Comedy"            to Triple("genre_name", "Comedy", "ranked"),
    "Drama"             to Triple("genre_name", "Drama", "ranked"),
    "Fantasy"           to Triple("genre_name", "Fantasy", "ranked"),
    "Romance"           to Triple("genre_name", "Romance", "ranked"),
    "Sci-Fi"            to Triple("genre_name", "Sci-Fi", "ranked"),
    "Slice of Life"     to Triple("genre_name", "Slice of Life", "ranked"),
    "Supernatural"      to Triple("genre_name", "Supernatural", "ranked"),
    "Mystery"           to Triple("genre_name", "Mystery", "ranked"),
    "Sports"            to Triple("genre_name", "Sports", "ranked"),
    "Mecha"             to Triple("genre_name", "Mecha", "ranked"),
    "School"            to Triple("theme_name", "School", "ranked"),
    "Historical"        to Triple("theme_name", "Historical", "ranked")
)

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to UA
    )

    private suspend fun throttle() {
        rateMutex.withLock {
            val now = System.currentTimeMillis()
            val gap = now - lastRequestMs
            if (gap < MIN_GAP_MS) delay(MIN_GAP_MS - gap)
            lastRequestMs = System.currentTimeMillis()
        }
    }

    // key: "genre:Action" or "theme:Isekai" -> Shikimori id
private suspend fun ensureGenreMap() {
    if (genreMap != null) return
    try {
        throttle()
        val res = app.get("$BASE/genres", headers = headers())
        if (res.code !in 200..299) {
            BLog.e("shikimori genre fetch HTTP ${res.code}")
            return
        }
        val arr = JSONArray(res.text)
        val map = mutableMapOf<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optString("entry_type").equals("Anime", ignoreCase = true)) continue
            val kind = o.optString("kind").lowercase()
            if (kind != "genre" && kind != "theme") continue
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
            val id = o.optInt("id", 0)
            if (id > 0) map["$kind:$name"] = id
        }
        genreMap = map
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit()
            .putString("genre_map", obj.toString())
            .putLong("genre_map_ts", System.currentTimeMillis())
            .apply()
        BLog.d("shikimori genre map fetched (${map.size} entries)")
    } catch (e: Exception) {
        BLog.e("shikimori genre map fetch failed: ${e.message}")
    }
}

// Resolve a row's (kind=genre|theme, name) to its Shikimori ID.
private suspend fun resolveRowId(name: String, kind: String): Int? {
    ensureGenreMap()
    val map = genreMap ?: return null
    val key = "$kind:$name"
    val id = map[key]
    if (id == null) BLog.d("shikimori: no id for $key (map has ${map.size} entries)")
    return id
}

    suspend fun fetchForRow(rowName: String, limit: Int = 30): List<AniListApi.Entry> {
    val cfg = ROW_QUERY[rowName] ?: return emptyList()
    val ck = "shikimori:row:$rowName:$limit"
    BCCache.get(ck, ROW_TTL)?.let { cached ->
        return try { parseList(JSONArray(cached)) } catch (_: Exception) { emptyList() }
    }

    inFlightRows[ck]?.let { return it.await() }
    val deferred = CompletableDeferred<List<AniListApi.Entry>>()
    val prior = inFlightRows.putIfAbsent(ck, deferred)
    if (prior != null) return prior.await()

    return try {
        // Resolve genre/theme name → id, if this row needs one.
        val resolvedCfg: Triple<String, String, String>? = when (cfg.first) {
            "genre_name" -> resolveRowId(cfg.second, "genre")?.let {
                Triple("genre", it.toString(), cfg.third)
            }
            "theme_name" -> resolveRowId(cfg.second, "theme")?.let {
                Triple("theme", it.toString(), cfg.third)
            }
            else -> cfg
        }
        if (resolvedCfg == null) {
            BLog.d("shikimori '$rowName' unresolved — skipping")
            deferred.complete(emptyList())
            return emptyList()
        }
        val result = fetchAndCache(ck, rowName, resolvedCfg, limit)
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
        ck: String, rowName: String,
        query: Triple<String, String, String>, limit: Int
    ): List<AniListApi.Entry> {
        throttle()
        val url = "$BASE/animes?${query.first}=${query.second}&limit=$limit&order=${query.third}"
        return try {
            val res = app.get(url, headers = headers())
            if (res.code == 429) { BLog.e("shikimori '$rowName' 429"); return emptyList() }
            if (res.code !in 200..299) { BLog.e("shikimori '$rowName' HTTP ${res.code}"); return emptyList() }
            val arr = JSONArray(res.text)
            BCCache.put(ck, arr.toString())
            parseList(arr)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { BLog.e("shikimori '$rowName' failed: ${e.message}"); emptyList() }
    }

    suspend fun detail(shikiId: Int): AniListApi.Entry? {
        val ck = "shikimori:detail:$shikiId"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            return try { parseEntry(JSONObject(cached)) } catch (_: Exception) { null }
        }
        throttle()
        return try {
            val res = app.get("$BASE/animes/$shikiId", headers = headers())
            if (res.code !in 200..299) return null
            val obj = JSONObject(res.text)
            BCCache.put(ck, obj.toString())
            parseEntry(obj)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { BLog.e("shikimori detail $shikiId failed: ${e.message}"); null }
    }

    // Priority prefetch: top 6 immediate, remaining 12 with 300ms gap.
    fun warmPrefetch() {
        val now = System.currentTimeMillis()
        if (now - lastPrefetchMs < ROW_TTL) return
        if (prefetchRunning) return
        prefetchRunning = true
        prefetchScope.launch {
            try {
                ensureGenreMap()
                val priority = listOf(
                    "Trending", "Top Anime Series", "Top Anime Movies",
                    "Action", "Adventure", "Isekai"
                )
                val rest = ROW_QUERY.keys.filter { it !in priority }
                for (row in priority) fetchForRow(row, 30)
                for (row in rest) {
                    fetchForRow(row, 30)
                    delay(300)
                }
                lastPrefetchMs = System.currentTimeMillis()
                BLog.d("shikimori prefetch done")
            } catch (e: kotlinx.coroutines.CancellationException) {
                BLog.v("prefetch cancelled")
            } finally {
                prefetchRunning = false
            }
        }
    }

    private fun parseList(arr: JSONArray): List<AniListApi.Entry> {
        val out = mutableListOf<AniListApi.Entry>()
        for (i in 0 until arr.length()) parseEntry(arr.optJSONObject(i))?.let { out.add(it) }
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
        // Shikimori only has portrait posters. Use the same URL for
        // banner — no better source available.
        val banner = cover
        val kind = o.optString("kind").takeIf { it.isNotBlank() }
        val format = when (kind) {
            "tv" -> "TV"; "movie" -> "MOVIE"; "ova" -> "OVA"; "ona" -> "ONA"
            "special", "tv_special" -> "SPECIAL"; "music" -> "MUSIC"; else -> null
        }
        val score = o.optString("score").takeIf { it.isNotBlank() && it != "null" }?.toDoubleOrNull()
        val averageScore = score?.let { (it * 10).toInt() }
        val airedOn = o.optString("aired_on").takeIf { it.isNotBlank() && it != "null" }
        val year = airedOn?.take(4)?.toIntOrNull()
        val episodes = o.optInt("episodes", 0).takeIf { it > 0 }
        val status = when (o.optString("status")) {
            "released" -> "FINISHED"; "ongoing" -> "RELEASING"
            "anons" -> "NOT_YET_RELEASED"; else -> null
        }
        val genres = o.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }?.takeIf { it.isNotEmpty() }
        return AniListApi.Entry(
            id = id, idMal = null,
            title = AniListApi.Title(romaji = name, english = null, native = russian),
            format = format, episodes = episodes, seasonYear = year,
            startDate = airedOn, description = null, coverImage = cover,
            bannerUrl = banner,
            averageScore = averageScore, status = status, genres = genres,
            country = null, relations = emptyList(), source = "shikimori"
        )
    }
}
