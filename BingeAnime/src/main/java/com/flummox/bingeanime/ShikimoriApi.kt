package com.flummox.bingeanime

import android.content.Context
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
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

    private val rateMutex = Mutex()
    private var lastRequestMs = 0L
    private const val MIN_GAP_MS = 300L

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastPrefetchMs = 0L
    @Volatile private var prefetchRunning = false

    private val inFlightRows =
        ConcurrentHashMap<String, CompletableDeferred<List<AniListApi.Entry>>>()

    private var genreMap: Map<String, Int>? = null
    private lateinit var cacheDir: java.io.File
    // Only ONE genre-map fetch fires even when 18 rows race.
    @Volatile private var genreMapDeferred: CompletableDeferred<Map<String, Int>?>? = null
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
    prefs = context.getSharedPreferences("bingeanime_shikimori", Context.MODE_PRIVATE)
    cacheDir = java.io.File(context.filesDir, "shikimori_rows").apply { mkdirs() }
    // Load disk-cached rows into memory. Only accept entries newer
    // than ROW_TTL; stale files are deleted.
    val now = System.currentTimeMillis()
    for (f in cacheDir.listFiles() ?: emptyArray()) {
        if (now - f.lastModified() > ROW_TTL) {
            f.delete()
            continue
        }
        try {
            val content = f.readText()
            val rowName = f.nameWithoutExtension
            BCCache.put("shikimori:row:$rowName:30", content)
        } catch (_: Exception) {}
    }
    BLog.v("shikimori disk cache loaded (${cacheDir.listFiles()?.size ?: 0} files)")
        val cached = prefs.getString("genre_map", null)
        val ts = prefs.getLong("genre_map_ts", 0L)
        if (cached != null && System.currentTimeMillis() - ts < GENRE_TTL) {
            try {
                val obj = JSONObject(cached)
                val map = mutableMapOf<String, Int>()
                obj.keys().forEach { k -> map[k] = obj.getInt(k) }
                genreMap = map
                BLog.v("shikimori genre map loaded (${map.size})")
            } catch (_: Exception) {}
        }
    }

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to UA
    )

    // Holds the lock through the entire HTTP request. Guarantees
// strictly serial execution with MIN_GAP_MS between requests.
// Previous version released the lock before app.get() fired,
// letting 5+ requests go out within the same second and trip
// Shikimori's burst limiter.
private suspend fun throttledGet(url: String): NiceResponse? {
    return rateMutex.withLock {
        val now = System.currentTimeMillis()
        val gap = now - lastRequestMs
        if (gap < MIN_GAP_MS) delay(MIN_GAP_MS - gap)
        lastRequestMs = System.currentTimeMillis()
        try {
            app.get(url, headers = headers())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("shikimori HTTP failed: ${e.message}")
            null
        }
    }
}

    // Row name -> (param-key, param-value, sort-order)
    //
    // All genre rows send `genre=<id>` — Shikimori accepts the same param
    // for genres and themes, but only if the id is correct. Isekai is a
    // MAL theme id 62 that isn't exposed in /api/genres, so it's hardcoded.
    //
    // Value semantics:
    //   "name:Action"  -> resolve by name against /api/genres
    //   "id:62"        -> use directly (Isekai exception)
    //   other          -> pass through (kind=, order= for dynamic rows)
    private val ROW_QUERY: LinkedHashMap<String, Triple<String, String, String>> = linkedMapOf(
    // Dynamic rows — global ordering, no genre filter
    "Trending"          to Triple("order", "popularity", "popularity"),
    "Top Anime Series"  to Triple("kind", "tv", "ranked"),
    "Top Anime Movies"  to Triple("kind", "movie", "ranked"),
    // Demographics — audience-targeted, sit above genre rows
    "Shounen"           to Triple("id", "name:Shounen", "ranked"),
    "Shoujo"            to Triple("id", "name:Shoujo", "ranked"),
    "Seinen"            to Triple("id", "name:Seinen", "ranked"),
    "Josei"             to Triple("id", "name:Josei", "ranked"),
    "Kids"              to Triple("id", "name:Kids", "ranked"),
    "Ecchi"             to Triple("id", "name:Ecchi", "ranked"),
    // Genre / theme rows
    "Action"            to Triple("id", "name:Action", "ranked"),
    "Adventure"         to Triple("id", "name:Adventure", "ranked"),
    "Comedy"            to Triple("id", "name:Comedy", "ranked"),
    "Drama"             to Triple("id", "name:Drama", "ranked"),
    "Fantasy"           to Triple("id", "name:Fantasy", "ranked"),
    "Romance"           to Triple("id", "name:Romance", "ranked"),
    "Sci-Fi"            to Triple("id", "name:Sci-Fi", "ranked"),
    "Slice of Life"     to Triple("id", "name:Slice of Life", "ranked"),
    "Supernatural"      to Triple("id", "name:Supernatural", "ranked"),
    "Mystery"           to Triple("id", "name:Mystery", "ranked"),
    "Sports"            to Triple("id", "name:Sports", "ranked"),
    "Mecha"             to Triple("id", "name:Mecha", "ranked"),
    "School"            to Triple("id", "name:School", "ranked"),
    "Historical"        to Triple("id", "name:Historical", "ranked"),
    "Horror"            to Triple("id", "name:Horror", "ranked"),
    "Psychological"     to Triple("id", "name:Psychological", "ranked"),
    "Thriller"          to Triple("id", "name:Thriller", "ranked")
)

    // Single-fetch genre map. First caller owns; others await same deferred.
    private suspend fun ensureGenreMap() {
        genreMap?.let { return }
        val existing = genreMapDeferred
        if (existing != null) { existing.await(); return }
        val deferred = CompletableDeferred<Map<String, Int>?>()
        if (!compareAndSetDeferred(deferred)) {
            genreMapDeferred?.await()
            return
        }
        try {
            val res = throttledGet("$BASE/genres") ?: run {
                deferred.complete(null)
                return
            }
            if (res.code !in 200..299) {
                BLog.e("shikimori genre fetch HTTP ${res.code}")
                deferred.complete(null)
                return
            }
            val arr = JSONArray(res.text)
            val map = mutableMapOf<String, Int>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.optString("entry_type").equals("Anime", ignoreCase = true)) continue
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                val id = o.optInt("id", 0)
                if (id > 0) map[name] = id
            }
            genreMap = map
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            prefs.edit()
                .putString("genre_map", obj.toString())
                .putLong("genre_map_ts", System.currentTimeMillis())
                .apply()
            BLog.d("shikimori genre map fetched (${map.size})")
            deferred.complete(map)
        } catch (e: kotlinx.coroutines.CancellationException) {
            deferred.complete(null)
            throw e
        } catch (e: Exception) {
            BLog.e("shikimori genre map fetch failed: ${e.message}")
            deferred.complete(null)
        }
    }

    @Synchronized
    private fun compareAndSetDeferred(d: CompletableDeferred<Map<String, Int>?>): Boolean {
        if (genreMapDeferred != null) return false
        genreMapDeferred = d
        return true
    }

    private suspend fun resolveRowId(name: String): Int? {
        ensureGenreMap()
        val map = genreMap ?: return null
        return map[name]
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
            val resolved: Triple<String, String, String>? = when {
                cfg.second.startsWith("name:") -> {
                    val name = cfg.second.removePrefix("name:")
                    resolveRowId(name)?.let { Triple("genre", it.toString(), cfg.third) }
                }
                cfg.second.startsWith("id:") -> {
                    Triple("genre", cfg.second.removePrefix("id:"), cfg.third)
                }
                else -> cfg
            }
            if (resolved == null) {
                BLog.d("shikimori '$rowName' unresolved — skipping")
                deferred.complete(emptyList())
                return emptyList()
            }
            val result = fetchAndCache(ck, rowName, resolved, limit)
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
    val url = "$BASE/animes?${query.first}=${query.second}&limit=$limit&order=${query.third}"
    val res = throttledGet(url) ?: return emptyList()
    BLog.v("shikimori '$rowName' → HTTP ${res.code} len=${res.text.length}")
    if (res.code == 429) { BLog.e("shikimori '$rowName' 429"); return emptyList() }
    if (res.code !in 200..299) { BLog.e("shikimori '$rowName' HTTP ${res.code}"); return emptyList() }
    val arr = try { JSONArray(res.text) } catch (e: Exception) {
        BLog.e("shikimori '$rowName' JSON parse: ${e.message}"); return emptyList()
    }
    // Persist to disk so home rows survive process kill.
    try {
        val f = java.io.File(cacheDir, "$rowName.json")
        f.writeText(arr.toString())
    } catch (_: Exception) {}
    BCCache.put(ck, arr.toString())
    return parseList(arr)
    }
    suspend fun detail(shikiId: Int): AniListApi.Entry? {
        val ck = "shikimori:detail:$shikiId"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            return try { parseEntry(JSONObject(cached)) } catch (_: Exception) { null }
        }
        val res = throttledGet("$BASE/animes/$shikiId") ?: return null
        return try {
            if (res.code !in 200..299) return null
            val obj = JSONObject(res.text)
            BCCache.put(ck, obj.toString())
            parseEntry(obj)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { BLog.e("shikimori detail $shikiId failed: ${e.message}"); null }
    }

    fun warmPrefetch() {
    val now = System.currentTimeMillis()
    if (now - lastPrefetchMs < ROW_TTL) return
    if (prefetchRunning) return
    prefetchRunning = true
    prefetchScope.launch {
        try {
            ensureGenreMap()
            // Only fetch rows the user has enabled. Disabled rows
            // cost 0 requests at cold start. Priority rows fetch
            // first so the visible top of home is warm fastest.
            val enabledRows = ROW_QUERY.keys.filter {
                BingeAnimeSettings.isRowEnabled(it)
            }
            val priority = listOf(
                "Trending", "Top Anime Series", "Top Anime Movies"
            ).filter { it in enabledRows }
            val rest = enabledRows.filter { it !in priority }
            BLog.d("shikimori prefetch: ${enabledRows.size} enabled rows")
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
            averageScore = averageScore, status = status, genres = genres,
            country = null, relations = emptyList(), source = "shikimori"
        )
    }
}
