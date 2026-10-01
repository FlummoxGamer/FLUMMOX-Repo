package com.flummox.bingeanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BingeAnimePlugin : Plugin() {
    override fun load(context: Context) {
        BLog.init(context)
       // One-time wipe of stale genre cache (had wrong key format).
       // Safe to remove after confirming the new key format loads.
       // Wipe on every boot during this testing phase. Once genre map
       // loads correctly, remove this block — it's a dev-only reset
       // to pick up the new key format.
       context.getSharedPreferences("bingeanime_shikimori", android.content.Context.MODE_PRIVATE)
         .edit().remove("genre_map").remove("genre_map_ts").apply()
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
