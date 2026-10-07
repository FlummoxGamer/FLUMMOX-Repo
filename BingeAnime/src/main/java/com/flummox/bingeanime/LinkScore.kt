package com.flummox.bingeanime

// Confidence scorer for AniKage mirrors. 0-100.
// Higher = more likely to play cleanly.
object LinkScore {

    fun prelimScore(m: ScrapedMirror): Int {
        var s = 50
        val q = m.quality.lowercase()
        when {
            q.contains("2160") || q.contains("4k") -> s += 30
            q.contains("1080") -> s += 30
            q.contains("720") -> s += 20
            q.contains("480") -> s += 5
            else -> s += 0 // "Auto" / unlabeled
        }

        // Source reputation within AniKage's own provider set.
        // koto/suge are the most consistently populated.
        val m2 = m.mirror.lowercase()
        s += when {
            m2.contains("koto") -> 15
            m2.contains("suge") -> 15
            m2.contains("kiwi") -> 10
            m2.contains("wave") -> 6
            m2.contains("megg") -> 3
            m2.contains("zen") -> 3
            else -> 0
        }

        // Having subtitles attached is a small bonus.
        if (m.captions.isNotEmpty()) s += 5

        return s.coerceIn(0, 100)
    }

    fun emoji(score: Int): String = when {
        score >= 70 -> "🟢"
        score >= 40 -> "🟡"
        else -> "🔴"
    }
}
