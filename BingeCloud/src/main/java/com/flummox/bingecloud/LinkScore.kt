package com.flummox.bingecloud

import java.net.URI

// ── bingecore: link confidence scorer ──
// Returns 0-100. Higher = more likely to play.
// Never drops — sorts and labels only.
object LinkScore {

    // ── scoring ──
fun prelimScore(m: ScrapedMirror): Int {
    var score = 50
    val url = m.url.lowercase()

    when {
        url.endsWith(".mp4") || url.endsWith(".mkv") -> score += 30
        url.endsWith(".m3u8") || url.endsWith(".mpd") -> score += 30
        url.contains(".m3u8?") || url.contains(".mpd?") -> score += 25
        url.contains("drive.google.com") -> score += 25
        url.contains("cloudflarestorage") -> score += 20
        url.contains("r2.dev") -> score += 20
        url.contains("hubcloud") -> score += 10
        url.contains("gdflix") -> score -= 5
    }

    // MovieBox's sbcdn5 edge serves certain subjects (Reacher 2022
    // confirmed) at a data rate ExoPlayer cannot sustain — user
    // reports consistent buffering while every sbcdnw title plays
    // clean. Assignment is server-side sticky per subject ID; not
    // fixable client-side. Demote so auto-pick prefers MD/ShowBox.
    // Verified 2026-09-29 across 9 Reacher episodes × 8 audio langs.
    if (url.contains("sbcdn5.hakunaymatata.com")) score -= 50

    score += when (m.source) {
        "MB" -> 15
        "MD" -> 5
        else -> 0
    }

        val host = hostOf(m.url)
        if (host.isNotEmpty()) score += HostHealth.bonus(host)

        return score.coerceIn(0, 100)
    }

    // ── emoji bands ──
    fun emoji(score: Int): String = when {
        score >= 70 -> "🟢"
        score >= 40 -> "🟡"
        else -> "🔴"
    }

    private fun hostOf(url: String): String = try {
        URI(url).host ?: ""
    } catch (_: Exception) { "" }
}
