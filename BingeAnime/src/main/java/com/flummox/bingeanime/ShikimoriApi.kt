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

    // ── rate limiter: strict serial with fixed gap ──
// Token buckets burst 4-5 requests in the first ~100ms of each
// second, which trips Shikimori's burst limiter even when the
// rolling 1-sec count looks safe. This enforces a fixed 250ms gap
// between consecutive requests and holds the lock through the
// entire HTTP call — peak 4/sec, zero burst, no clock-skew races.
private val requestLock = Mutex()
private var lastRequestMs = 0L
private const val MIN_GAP_MS = 250L

private suspend fun <T> throttled(block: suspend () -> T): T {
    return requestLock.withLock {
        val gap = System.currentTimeMillis() - lastRequestMs
        if (gap < MIN_GAP_MS) delay(MIN_GAP_MS - gap)
        val result = block()
        lastRequestMs = System.currentTimeMillis()
        result
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

    // Shiki id → resolved cover URL. Populated from AniMapper lookups
    // when Shikimori's `image.original` is null. Persisted so we only
    // pay the lookup cost once per title.
    @Volatile private var coverOverrides: MutableMap<Int, String> = mutableMapOf()

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
        // Load cover overrides
        try {
            val co = prefs.getString("cover_overrides", null)
            if (co != null) {
                val obj = JSONObject(co)
                val map = mutableMapOf<Int, String>()
                obj.keys().forEach { k ->
                    val v = obj.optString(k).takeIf { it.isNotBlank() }
                    if (v != null) map[k.toIntOrNull() ?: return@forEach] = v
                }
                coverOverrides = map
                BLog.v("shikimori cover overrides loaded (${map.size})")
           }
       } catch (_: Exception) {}

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
    val raw = fetchRawForRow(rowName, 50)
    val yearFloor = BingeAnimeSettings.getYearFloorIfEnabled()
    val withYear = raw.filter { (it.seasonYear ?: 0) >= yearFloor }
    
    // Shuffle with hourly seed + year-floor salt. Different filter
    // states produce different orders within the same hour so the
    // user sees a visual change when toggling year filter on/off.
    val hourSeed = System.currentTimeMillis() / (60 * 60 * 1000L)
    val seed = hourSeed + (yearFloor?.toLong() ?: 0L)
    val shuffled = withYear.shuffled(java.util.Random(seed))

    // Apply cached cover overrides first (free — no API call).
    val overridden = shuffled.map { e ->
        val c = e.coverImage
        if (!c.isNullOrBlank()) e
        else coverOverrides[e.id]?.let { e.copy(coverImage = it) } ?: e
    }

    // Still missing covers — try AniMapper, capped at 3 per row.
val stillMissing = overridden.filter { it.coverImage.isNullOrBlank() }
if (stillMissing.isNotEmpty()) {
    BLog.d("shikimori '$rowName' missing covers: ${stillMissing.size}, trying AniMapper")
}
val resolved = if (stillMissing.isEmpty()) overridden else {
    val lookup = stillMissing.take(3)
        val resolvedMap = mutableMapOf<Int, String>()
        for (e in lookup) {
            val t = e.title.romaji ?: e.title.english ?: continue
            try {
                val hit = AniMapperApi.searchByTitle(t)
                val cov = hit?.coverImage?.takeIf { it.isNotBlank() }
                if (cov != null) resolvedMap[e.id] = cov
            } catch (_: Exception) {}
        }
        BLog.d("shikimori '$rowName' cover fallback: resolved ${resolvedMap.size}/${lookup.size}")
if (resolvedMap.isNotEmpty()) {
    synchronized(coverOverrides) {
        coverOverrides.putAll(resolvedMap)
        persistCoverOverrides()
    }
}
        overridden.map { e ->
            if (!e.coverImage.isNullOrBlank()) e
            else resolvedMap[e.id]?.let { e.copy(coverImage = it) } ?: e
        }
    }

    // Final: drop any still-coverless entries so CS shows no broken tile.
    val finalList = resolved.filter { !it.coverImage.isNullOrBlank() }
    val result = finalList.take(limit)
    BLog.v("shikimori '$rowName' → ${raw.size} raw, ${result.size} returned (floor=$yearFloor, shuffled)")
    return result
}

private fun persistCoverOverrides() {
    try {
        val obj = JSONObject()
        synchronized(coverOverrides) {
            coverOverrides.forEach { (k, v) -> obj.put(k.toString(), v) }
        }
        prefs.edit().putString("cover_overrides", obj.toString()).apply()
    } catch (_: Exception) {}
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
        val url = "$BASE/animes?${query.first}=${query.second}&limit=$limit&order=${query.third}"
        val res = try {
            throttled { app.get(url, headers = headers()) }
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
            val res = throttled { app.get("$BASE/genres", headers = headers()) }
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
        return try {
            val res = throttled { app.get("$BASE/animes/$shikiId", headers = headers()) }
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

    // Called by the Clear cache button. Wipes in-memory state so the
// next home load refetches everything from network. Disk folder
// is deleted by the caller before calling this.
fun resetForClearCache() {
    inFlightRows.clear()
    exclusions = emptyMap()
    synchronized(coverOverrides) { coverOverrides = mutableMapOf() }
    lastPrefetchMs = 0L
    prefetchRunning = false
    BLog.d("shikimori state reset")
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
        val cover = imgPath
    ?.let { if (it.startsWith("http")) it else "$IMG_BASE$it" }
    // Shikimori serves /assets/globals/missing_*.jpg for entries
    // with no image. Treat those as null so the AniMapper
    // fallback in fetchForRow kicks in.
    ?.takeIf { !it.contains("/assets/") }
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
