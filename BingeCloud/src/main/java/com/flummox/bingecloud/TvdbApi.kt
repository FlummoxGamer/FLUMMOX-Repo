package com.flummox.bingecloud

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════
// ── TVDB v4 client ──
// Auth token cached 25 days (token valid ~1 month).
// Used for language-based discovery (Hindi, Bangla, Korean).
// Soap genre filtered at source so we don't need episode heuristics.
// ═══════════════════════════════════════════════════════════════

private const val TVDB_ENDPOINT = "https://api4.thetvdb.com/v4"
private val TVDB_JSON = "application/json; charset=utf-8".toMediaType()

private val LANGUAGE_ISO3 = mapOf(
    "hi" to "hin", "bn" to "ben", "ko" to "kor",
    "ta" to "tam", "te" to "tel", "ja" to "jpn",
    "zh" to "zho", "ml" to "mal", "kn" to "kan"
)

object TvdbAuth {
    private var memoryToken: String? = null
    private var memoryExpiry: Long = 0L

    suspend fun getToken(): String? {
        val now = System.currentTimeMillis()
        memoryToken?.let { if (memoryExpiry > now) return it }

        val stored = Settings.getTvdbToken()
        val storedExp = Settings.getTvdbTokenExp()
        if (!stored.isNullOrBlank() && storedExp > now) {
            memoryToken = stored
            memoryExpiry = storedExp
            return stored
        }
        return login()
    }

    private suspend fun login(): String? {
        val key = BuildConfig.TVDB_API_KEY
        if (key.isBlank()) {
            BCLog.e("[TVDB] no API key in BuildConfig")
            return null
        }
        return try {
            val body = JSONObject().apply { put("apikey", key) }.toString()
            val res = app.post(
                "$TVDB_ENDPOINT/login",
                requestBody = body.toRequestBody(TVDB_JSON),
                headers = mapOf("Content-Type" to "application/json")
            )
            val token = JSONObject(res.text).optJSONObject("data")
                ?.optString("token")?.takeIf { it.isNotBlank() }
            if (token != null) {
                val exp = System.currentTimeMillis() + 25L * 24 * 60 * 60 * 1000
                memoryToken = token
                memoryExpiry = exp
                Settings.saveTvdbToken(token, exp)
                BCLog.d("[TVDB] auth ok")
            } else {
                BCLog.e("[TVDB] login response no token: ${res.text.take(200)}")
            }
            token
        } catch (e: Exception) {
            BCLog.e("[TVDB] login failed: ${e.message}")
            null
        }
    }
}

suspend fun tvdbDiscover(
    type: String,        // "series" | "movies"
    langCode: String?,   // null = no language filter (trending)
    limit: Int = 30
): List<AioMeta> {
    val token = TvdbAuth.getToken() ?: return emptyList()
    val path = if (type == "movies") "movies" else "series"
    val langParam = langCode?.let { LANGUAGE_ISO3[it] }?.let { "&lang=$it" } ?: ""
    val url = "$TVDB_ENDPOINT/$path/filter?sort=score&sortType=desc&page=0$langParam"

    return try {
        val res = app.get(
            url,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/json"
            )
        )
        val json = JSONObject(res.text)
        val data = json.optJSONArray("data") ?: run {
            BCLog.e("[TVDB] no data array. head=${res.text.take(250)}")
            return emptyList()
        }

        val out = mutableListOf<AioMeta>()
        for (i in 0 until minOf(data.length(), limit)) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0).takeIf { it > 0 } ?: continue
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue

            // Soap genre filter — TVDB tags it as a real genre
            val genres = o.optJSONArray("genres")
            var isSoap = false
            if (genres != null) {
                for (j in 0 until genres.length()) {
                    val g = genres.optJSONObject(j)?.optString("name")?.lowercase()
                    if (g == "soap") { isSoap = true; break }
                }
            }
            if (isSoap) continue

            val image = o.optString("image").takeIf { it.startsWith("http") }
            val firstAired = o.optString("firstAired").takeIf { it.isNotBlank() }
            val year = firstAired?.take(4)?.toIntOrNull()?.toString()
                ?: o.optString("year").take(4).toIntOrNull()?.toString()

            out.add(
                AioMeta(
                    id = "tvdb:$id",
                    name = name,
                    type = if (type == "movies") "movie" else "series",
                    poster = image,
                    releaseInfo = year,
                    year = year
                )
            )
        }
        BCLog.d("[TVDB] $type/${langCode ?: "trending"} → ${out.size}")
        out
    } catch (e: Exception) {
        BCLog.e("[TVDB] discover failed: ${e.message}")
        emptyList()
    }
}
