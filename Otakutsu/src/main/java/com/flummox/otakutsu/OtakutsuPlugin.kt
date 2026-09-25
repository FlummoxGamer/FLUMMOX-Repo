package com.flummox.otakutsu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OtakutsuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OtakutsuProvider())
    }
}
