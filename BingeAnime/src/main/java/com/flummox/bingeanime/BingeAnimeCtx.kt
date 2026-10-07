package com.flummox.bingeanime

import android.content.Context

// Context holder so StreamScrapers can write downloaded subs to
// app-private storage without threading a Context through every call.
object BingeAnimeCtx {
    var context: Context? = null
}
