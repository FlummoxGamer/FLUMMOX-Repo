package com.flummox.bingeanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BingeAnimePlugin : Plugin() {
    override fun load(context: Context) {
        BLog.init(context)
       // One-time reset of stale row prefs + genre cache from earlier
       // builds. Safe to remove next version — everything defaults
       // correctly now.
       context.getSharedPreferences("bingeanime_shikimori", android.content.Context.MODE_PRIVATE)
           .edit().remove("genre_map").remove("genre_map_ts").apply()
       java.io.File(context.filesDir, "shikimori_rows").deleteRecursively()
       ShikimoriApi.init(context)
        BLog.setVerbose(BingeAnimeSettings.isVerbose())
        BLog.d("BingeAnime boot v${BuildConfig.PLUGIN_VERSION}")
        registerMainAPI(BingeAnimeProvider())
        this.openSettings = { ctx ->
            BingeAnimeSettings.show(ctx)
        }
        // Kick off home-row prefetch in background. Cache will be warm
        // by the time the user navigates to home.
        ShikimoriApi.warmPrefetch()
    }
}
