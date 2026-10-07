package com.flummox.bingeanime

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object AnikageApi {
    const val BASE = "https://anikage.cc"
    const val PROXY = "https://og.bakayaro.live"

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private const val TTL_SEARCH = 30L * 60 * 1000
    private const val TTL_EPS = 6L * 60 * 60 * 1000
    private const val TTL_SERVERS = 6L * 60 * 60 * 1000
    private const val TTL_SOURCES = 10L * 60 * 1000

    private fun headers() = mapOf(
        "Accept" to "application/json",
        "Referer" to "$BASE/",
        "User-Agent" to UA
    )

    data class ServerInfo(val id: String, val subTypes: List<String>)
    data class SourceItem(val slug: String, val quality: String, val isM3U8: Boolean)
    data class SubItem(val file: String, val label: String)
    data class EmbedOption(val key: String, val label: String, val url: String)
    data class EpInfo(val number: Int, val title: String?)
    data class SourceBundle(
        val sources: List<SourceItem>,
        val subs: List<SubItem>,
        val embeds: List<EmbedOption> = emptyList()
    )

    // ── search ──
    suspend fun search(query: String): List<AniListApi.Entry> {
        val ck = "anikage:s:${query.lowercase()}"
        BCCache.get(ck, TTL_SEARCH)?.let { cached ->
            return try { parseSearch(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        val url = "$BASE/api/media/anime/browse?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&sort=popularity&page=1&limit=25&adult=true"
        return try {
            val res = app.get(url, headers = headers())
            if (res.code == 429) { BLog.e("anikage search 429"); return emptyList() }
            if (res.code !in 200..299) { BLog.e("anikage search HTTP ${res.code}"); return emptyList() }
            BCCache.put(ck, res.text)
            parseSearch(JSONObject(res.text))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("anikage search failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseSearch(root: JSONObject): List<AniListApi.Entry> {
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<AniListApi.Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slug = o.optString("slug").takeIf { it.isNotBlank() } ?: continue
            val tObj = o.optJSONObject("title")
            val romaji = tObj?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" }
            val english = tObj?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
            val native = tObj?.optString("native")?.takeIf { it.isNotBlank() && it != "null" }
            if (romaji == null && english == null && native == null) continue

            val cObj = o.optJSONObject("coverImage")
            val cover = cObj?.optString("extraLarge")?.takeIf { it.isNotBlank() && it != "null" }
                ?: cObj?.optString("large")?.takeIf { it.isNotBlank() && it != "null" }

            val format = o.optString("format").takeIf { it.isNotBlank() && it != "null" }
            val status = o.optString("status").takeIf { it.isNotBlank() && it != "null" }
            val year = o.optInt("year", 0).takeIf { it > 0 }
            val eps = o.optInt("totalEpisodes", 0).takeIf { it > 0 }
            val score = o.optInt("averageScore", 0).takeIf { it > 0 }

            val genres = o.optJSONArray("genres")?.let { g ->
                (0 until g.length()).mapNotNull { j ->
                    g.optString(j).takeIf { it.isNotBlank() }
                }
            }?.takeIf { it.isNotEmpty() }

            out.add(AniListApi.Entry(
                id = slug.hashCode(),
                idMal = null,
                title = AniListApi.Title(romaji = romaji, english = english, native = native),
                format = format,
                episodes = eps,
                seasonYear = year,
                startDate = null,
                description = null,
                coverImage = cover,
                bannerUrl = null,
                averageScore = score,
                status = status,
                genres = genres,
                country = null,
                relations = emptyList(),
                source = "anikage",
                sourceId = slug
            ))
        }
        return out
    }

    // ── episodes ──
    suspend fun episodes(slug: String): List<EpInfo> {
        val ck = "anikage:eps:$slug"
        BCCache.get(ck, TTL_EPS)?.let { cached ->
            return try { parseEps(JSONArray(cached)) } catch (_: Exception) { emptyList() }
        }
        return try {
            val res = app.get("$BASE/api/media/anime/$slug/episodes", headers = headers())
            if (res.code !in 200..299) return emptyList()
            val arr = JSONArray(res.text)
            BCCache.put(ck, arr.toString())
            parseEps(arr)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("anikage episodes failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseEps(arr: JSONArray): List<EpInfo> {
        val out = mutableListOf<EpInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optInt("number", 0).takeIf { it > 0 } ?: continue
            val t = o.optString("title").takeIf { it.isNotBlank() && it != "null" }
            out.add(EpInfo(n, t))
        }
        return out.sortedBy { it.number }
    }

    // ── servers ──
    suspend fun servers(slug: String, ep: Int): List<ServerInfo> {
        val ck = "anikage:srv:$slug:$ep"
        BCCache.get(ck, TTL_SERVERS)?.let { cached ->
            return try { parseServers(JSONObject(cached)) } catch (_: Exception) { emptyList() }
        }
        return try {
            val res = app.get("$BASE/api/media/anime/$slug/episodes/$ep/servers", headers = headers())
            if (res.code !in 200..299) return emptyList()
            BCCache.put(ck, res.text)
            parseServers(JSONObject(res.text))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.e("anikage servers failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseServers(root: JSONObject): List<ServerInfo> {
        val arr = root.optJSONArray("servers") ?: return emptyList()
        val out = mutableListOf<ServerInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pid = o.optString("providerId").takeIf { it.isNotBlank() } ?: continue
            val subs = o.optJSONArray("subTypes")?.let { a ->
                (0 until a.length()).mapNotNull { j ->
                    a.optString(j).takeIf { it.isNotBlank() }
                }
            } ?: listOf("sub")
            out.add(ServerInfo(pid, subs))
        }
        return out
    }

    // ── sources ──
    suspend fun sources(slug: String, ep: Int, provider: String, lang: String): SourceBundle? {
        val ck = "anikage:src:$slug:$ep:$provider:$lang"
        BCCache.get(ck, TTL_SOURCES)?.let { cached ->
            return try { parseSources(JSONObject(cached)) } catch (_: Exception) { null }
        }
        return try {
            val url = "$BASE/api/media/anime/$slug/episodes/$ep/sources" +
                "?provider=$provider&lang=$lang&server=$provider"
            val res = app.get(url, headers = headers())
            if (res.code !in 200..299) return null
            BCCache.put(ck, res.text)
            parseSources(JSONObject(res.text))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BLog.v("anikage sources $provider/$lang failed: ${e.message}")
            null
        }
    }

    private fun parseSources(root: JSONObject): SourceBundle {
        val srcs = mutableListOf<SourceItem>()
        root.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                val q = o.optString("quality").ifBlank {
                    o.optString("label").ifBlank { "Auto" }
                }
                val m3u8 = o.optBoolean("isM3U8", false)
                srcs.add(SourceItem(u, q, m3u8))
            }
        }
        val subs = mutableListOf<SubItem>()
        root.optJSONArray("subtitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val f = o.optString("file").takeIf { it.isNotBlank() } ?: continue
                val l = o.optString("label").takeIf { it.isNotBlank() } ?: "Unknown"
                subs.add(SubItem(f, l))
            }
        }
        val embeds = mutableListOf<EmbedOption>()
        root.optJSONArray("embedOptions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key").takeIf { it.isNotBlank() } ?: continue
                val lbl = o.optString("label").takeIf { it.isNotBlank() } ?: "E-$key"
                val u = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                embeds.add(EmbedOption(key, lbl, u))
            }
        }
        return SourceBundle(srcs, subs, embeds)
    }

    // ── URL builders ──
    fun hlsUrl(opaque: String): String = "$PROXY/m3u8/$opaque"
    fun subUrl(opaque: String): String = "$PROXY/stream/$opaque"
    fun referer(): String = "$BASE/"
}
