package com.flummox.otakutsu

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OLog {

    private const val MAX_LINES = 2000
    private const val MAX_FILE_BYTES = 500_000L
    private const val TAG = "Otakutsu"

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var writer: BufferedWriter? = null
    private var verbose: Boolean = false
    private var pendingWrites: Int = 0
    private var appContext: Context? = null

    fun setVerbose(enabled: Boolean) { synchronized(lock) { verbose = enabled } }
    fun isVerbose(): Boolean = synchronized(lock) { verbose }

    private val RX_JWT = Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+""")
    private val RX_BEARER = Regex("""(?i)(bearer\s+)\S+""")
    private val RX_SIGN_COOKIE = Regex("""(?i)("signCookie"\s*:\s*")[^"]+""")
    private val RX_COOKIE = Regex("""(?i)(cookie["']?\s*[:=]\s*["']?)[^"\r\n]+""")
    private val RX_CF = Regex("""(?i)(cloudfront-(?:policy|signature|key-pair-id)=)[^;&"\s]+""")

    private fun sanitize(input: String): String {
        var s = input
        s = RX_JWT.replace(s) { "<JWT>" }
        s = RX_BEARER.replace(s) { m -> m.groupValues[1] + "<BEARER>" }
        s = RX_SIGN_COOKIE.replace(s) { m -> m.groupValues[1] + "<COOKIE>" }
        s = RX_COOKIE.replace(s) { m -> m.groupValues[1] + "<COOKIE>" }
        s = RX_CF.replace(s) { m -> m.groupValues[1] + "<CF>" }
        return s
    }

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            try {
                try { writer?.close() } catch (_: Exception) {}
                writer = null
                buffer.clear()
                val f = File(context.filesDir, "otakutsu_log.txt")
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    val lines = f.readLines().takeLast(MAX_LINES / 2)
                    f.writeText(lines.joinToString("\n") + "\n")
                }
                if (f.exists()) {
                    f.readLines().takeLast(MAX_LINES).forEach { buffer.addLast(it) }
                }
                writer = BufferedWriter(FileWriter(f, true))
            } catch (_: Exception) {}
        }
    }

    private fun writeLine(line: String) {
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            try {
                writer?.appendLine(line)
                pendingWrites++
                if (pendingWrites >= 30) {
                    writer?.flush()
                    pendingWrites = 0
                }
            } catch (_: Exception) {}
        }
        try { android.util.Log.d(TAG, line) } catch (_: Exception) {}
    }

    fun d(message: String) { writeLine("[${timeFormat.format(Date())}] $message") }

    fun v(message: String) {
        if (!isVerbose()) return
        writeLine("[${timeFormat.format(Date())}] $message")
    }

    fun e(message: String) {
        writeLine("[${timeFormat.format(Date())}] ✗ $message")
        try { android.util.Log.e(TAG, message) } catch (_: Exception) {}
    }

    fun section(title: String) {
        writeLine("───── $title ─────")
    }

    fun allSanitized(): String = sanitize(
        synchronized(lock) {
            if (buffer.isEmpty()) "(no logs yet)" else buffer.joinToString("\n")
        }
    )

    fun count(): Int = synchronized(lock) { buffer.size }

    fun recent(n: Int): List<String> = synchronized(lock) {
        if (buffer.isEmpty()) emptyList()
        else buffer.takeLast(n).toList()
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            try {
                writer?.close()
                writer = null
            } catch (_: Exception) {}
            try {
                appContext?.let { ctx ->
                    File(ctx.filesDir, "otakutsu_log.txt").delete()
                    writer = BufferedWriter(FileWriter(File(ctx.filesDir, "otakutsu_log.txt"), true))
                }
            } catch (_: Exception) {}
        }
    }
}
