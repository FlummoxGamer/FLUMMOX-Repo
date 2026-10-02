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
    private const val ROW_TTL = 60 * 60 * 1000L
    private const val GENRE_TTL = 7L * 24 * 60 * 60 * 1000

    // ── rate limiter: token bucket ──
    // Hard cap: no more than 5 HTTP requests in any rolling 1-second window.
    // Shikimori's burst limit is 5/sec. Staying at or below guarantees no 429.
    private val requestTimes = ArrayDeque<Long>()
    private val bucketLock = Mutex()
    private const val WINDOW_MS = 1000L
    private const val MAX_PER_WINDOW = 5

    private suspend fun acquireToken() {
        while (true) {
            val wait: Long = bucketLock.withLock {
                val now = System.currentTimeMillis()
                while (requestTimes.isNotEmpty() && now - requestTimes.first() > WINDOW_MS) {
                    requestTimes.removeFirst()
                }
                if (requestTimes.size < MAX_PER_WINDOW) {
                    requestTimes.addLast(now)
                    return@withLock 0L
                }
                // Wait until the oldest request falls out of the window
                WINDOW_MS - (now - requestTimes.first()) + 20L
            }
            if (wait <= 0L) return
            delay(wait)
        }
    }

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastPrefetchMs = 0L
    @Volatile private var prefetchRunning = false

    private val inFlightRows =
        ConcurrentHashMap<String, CompletableDeferred<List<AniListApi.Entry>>>()

    private var genreMap: Map<String, Int>? = null
    @Volatile private var genreMapDeferred: CompletableDeferred<Map<String, Int>?>? = null
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var cacheDir: java.io.File
    @Volatile private var initialized = false

    private val DYNAMIC_ROWS = setOf("Trending", "Top Anime Series", "Top Anime Movies")
    @Volatile private var exclusions: Map<String, Set<String>> = emptyMap()

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to UA
    )

    // ── row config ──
    private val ROW_QUERY: LinkedHashMap<String, Triple<String, String, String>> = linkedMapOf(
        "Trending"          to Triple("order", "popularity", "popularity"),
        "Top Anime Series"  to Triple("kind", "tv", "ranked"),
        "Top Anime Movies"  to Triple("kind", "movie", "ranked"),
        "Shounen"           to Triple("id", "name:Shounen", "ranked"),
        "Shoujo"            to Triple("id", "name:Shoujo", "ranked"),
        "Seinen"            to Triple("id", "name:Seinen", "ranked"),
        "Josei"             to Triple("id", "name:Josei", "ranked"),
        "Kids"              to Triple("id", "name:Kids", "ranked"),
        "Ecchi"             to Triple("id", "name:Ecchi", "ranked"),
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

    // ── init: load disk cache, mark stale rows, fire revalidation ──
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        prefs = context.getSharedPreferences("bingeanime_shikimori", Context.MODE_PRIVATE)
        cacheDir = java.io.File(context.filesDir, "shikimori_rows").apply { mkdirs() }

        // Load genre map from prefs if fresh
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

        // Load disk cache. Stale files (>ROW_TTL) are loaded anyway and
        // scheduled for background revalidation — stale-while-revalidate.
        val now = System.currentTimeMillis()
        val staleRows = mutableListOf<String>()
        var loaded = 0
        for (f in cacheDir.listFiles() ?: emptyArray()) {
            val rowName = f.nameWithoutExtension
            if (rowName !in ROW_QUERY) continue
            try {
                val content = f.readText()
                // Cache key must match fetchRaw's key.
                BCCache.put("shikimori:raw:$rowName", content)
                loaded++
                if (now - f.lastModified() > ROW_TTL) staleRows.add(rowName)
            } catch (_: Exception) {}
        }
        BLog.d("shikimori disk cache loaded ($loaded rows, ${staleRows.size} stale)")

        if (staleRows.isNotEmpty()) {
            prefetchScope.launch {
                BLog.d("shikimori revalidating ${staleRows.size} stale rows")
                for (row in staleRows) {
                    try { fetchRawForRow(row, 50) } catch (_: Exception) {}
                }
                BLog.d("shikimori revalidation done")
            }
        }
    }

    // ── public fetch: applies year filter at return time ──
    suspend fun fetchForRow(rowName: String, limit: Int = 30): List<AniListApi.Entry> {
        // Always fetch/compute on 50 items; filter+trim at return.
        val raw = fetchRawForRow(rowName, 50)
        val yearFloor = BingeAnimeSettings.getYearFloorIfEnabled()
        val filtered = if (yearFloor == null) raw else raw.filter {
            (it.seasonYear ?: 0) >= yearFloor
        }
        val result = filtered.take(limit)
        BLog.v("shikimori '$rowName' → ${raw.size} raw, ${result.size} after filter (floor=$yearFloor)")
        return result
    }

    // ── raw fetch: cache + network, returns unfiltered list ──
    private suspend fun fetchRawForRow(rowName: String, limit: Int): List<AniListApi.Entry> {
        val cfg = ROW_QUERY[rowName] ?: return emptyList()
        val ck = "shikimori:raw:$rowName"

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
                deferred.complete(emptyList())
                return emptyList()
            }
            val result = fetchAndStore(ck, rowName, resolved, limit)
            deferred.complete(result)
            result
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            inFlightRows.remove(ck)
        }
    }

    private suspend fun fetchAndStore(
        ck: String, rowName: String,
        query: Triple<String, String, String>, limit: Int
    ): List<AniListApi.Entry> {
        acquireToken()
        val url = "$BASE/animes?${query.first}=${query.second}&limit=$limit&order=${query.third}"
        val res = try {
            app.get(url, headers = headers())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("shikimori '$rowName' HTTP failed: ${e.message}")
            return emptyList()
        }
        BLog.v("shikimori '$rowName' → HTTP ${res.code} len=${res.text.length}")
        if (res.code == 429) { BLog.e("shikimori '$rowName' 429"); return emptyList() }
        if (res.code !in 200..299) { BLog.e("shikimori '$rowName' HTTP ${res.code}"); return emptyList() }
        val arr = try { JSONArray(res.text) } catch (e: Exception) {
            BLog.e("shikimori '$rowName' parse: ${e.message}"); return emptyList()
        }
        // Persist raw 50 to disk for next boot
        try {
            java.io.File(cacheDir, "$rowName.json").writeText(arr.toString())
        } catch (_: Exception) {}
        BCCache.put(ck, arr.toString())
        return parseList(arr)
    }

    // ── genre map ──
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
            acquireToken()
            val res = app.get("$BASE/genres", headers = headers())
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

    // ── detail ──
    suspend fun detail(shikiId: Int): AniListApi.Entry? {
        val ck = "shikimori:detail:$shikiId"
        BCCache.get(ck, ROW_TTL)?.let { cached ->
            return try { parseEntry(JSONObject(cached)) } catch (_: Exception) { null }
        }
        acquireToken()
        return try {
            val res = app.get("$BASE/animes/$shikiId", headers = headers())
            if (res.code !in 200..299) return null
            val obj = JSONObject(res.text)
            BCCache.put(ck, obj.toString())
            parseEntry(obj)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { BLog.e("shikimori detail $shikiId failed: ${e.message}"); null }
    }

    // ── prefetch: enabled rows only, priority first ──
    fun warmPrefetch() {
        val now = System.currentTimeMillis()
        if (now - lastPrefetchMs < ROW_TTL) return
        if (prefetchRunning) return
        prefetchRunning = true
        prefetchScope.launch {
            try {
                ensureGenreMap()
   // Only prefetch rows 1-8. Rows 9+ stay cold until
   // CloudStream calls getMainPage for them on scroll —
   // our fetchForRow handles the on-demand network call.
   //
   // Keeps cold-start burst under 8 requests. Stays well
   // inside Shikimori's 5/sec burst limiter.
   val enabledRows = ROW_QUERY.keys.filter {
       BingeAnimeSettings.isRowEnabled(it)
   }.take(8)
   BLog.d("shikimori prefetch: ${enabledRows.size} top rows (lazy for rest)")
   for (row in enabledRows) fetchRawForRow(row, 50)
                lastPrefetchMs = System.currentTimeMillis()
                BLog.d("shikimori prefetch done")
            } catch (e: kotlinx.coroutines.CancellationException) {
                BLog.v("prefetch cancelled")
            } finally {
                prefetchRunning = false
            }
        }
    }

    fun prefetchRowsNow(rows: List<String>) {
        prefetchScope.launch {
            for (row in rows) {
                try { fetchRawForRow(row, 50) } catch (_: Exception) {}
            }
        }
    }

    // ── cross-row dedup ──
    private fun dedupeKey(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]"), "").take(48)

    fun isExcluded(rowName: String, title: String): Boolean {
        val set = exclusions[rowName] ?: return false
        return dedupeKey(title) in set
    }

    fun rebuildExclusions() {
        val rowOrder = ROW_QUERY.keys.toList()
        val titleRows = mutableMapOf<String, MutableList<String>>()
        for (rowName in rowOrder) {
            if (rowName in DYNAMIC_ROWS) continue
            val cached = BCCache.get("shikimori:raw:$rowName", ROW_TTL) ?: continue
            try {
                val arr = JSONArray(cached)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val key = dedupeKey(name)
                    if (key.isBlank()) continue
                    titleRows.getOrPut(key) { mutableListOf() }.add(rowName)
                }
            } catch (_: Exception) {}
        }
        val excluded = mutableMapOf<String, MutableSet<String>>()
        for ((key, rows) in titleRows) {
            if (rows.size < 3) continue
            val sortedRows = rows.sortedBy { rowOrder.indexOf(it) }
            for (r in sortedRows.drop(2)) {
                excluded.getOrPut(r) { mutableSetOf() }.add(key)
            }
        }
        exclusions = excluded
    }

    // ── parse ──
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
