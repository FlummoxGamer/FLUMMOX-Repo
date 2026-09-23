package com.flummox.bingecore

import android.content.Context
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

object RepoAnalytics {
    private const val PREFS = "bingecloud_repo_analytics"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_LAST_PING = "last_ping"
    private const val PING_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private const val WORKER_URL =
        "https://flummox-ping-proxy.nacho-rebuff818.workers.dev"
    private const val AUTH_TOKEN =
        "FLUMMOX_8f3a91b2c4d5e6f7a8b9c0d1e2f3a4b5"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun getOrCreateInstallId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_INSTALL_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        }
        return id
    }

    fun ping(context: Context, extensionName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastPing = prefs.getLong(KEY_LAST_PING, 0L)
        val now = System.currentTimeMillis()
        if (now - lastPing < PING_INTERVAL_MS) return

        val installId = getOrCreateInstallId(context)
        val body = JSONObject().apply {
            put("timestamp", now)
            put("installId", installId)
            put("repo", "FLUMMOX-Repo")
            put("ext", extensionName)
            put("ver", BuildConfig.PLUGIN_VERSION.toString())
        }.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.post(
                    WORKER_URL,
                    requestBody = body.toRequestBody(JSON_MEDIA),
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-Auth-Token" to AUTH_TOKEN
                    )
                )
                prefs.edit().putLong(KEY_LAST_PING, now).apply()
            } catch (_: Exception) {}
        }
    }
}
