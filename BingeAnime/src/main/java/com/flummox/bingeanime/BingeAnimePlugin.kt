package com.flummox.bingeanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BingeAnimePlugin : Plugin() {
    override fun load(context: Context) {
        BLog.init(context)
        BLog.setVerbose(BingeAnimeSettings.isVerbose())
        BLog.d("BingeAnime boot v${BuildConfig.PLUGIN_VERSION}")
        registerMainAPI(BingeAnimeProvider())
        this.openSettings = { ctx ->
            BingeAnimeSettings.show(ctx)
        }
    }
}
