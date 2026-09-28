package com.flummox.otakutsu

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
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
    private const val K_VERBOSE = "otakutsu_verbose"

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var writer: BufferedWriter? = null
    private var vWriter: BufferedWriter? = null
    private var pendingWrites = 0
    private var vPendingWrites = 0
    private var appContext: Context? = null
    private var verbose = false

    private val XOR_KEY = byteArrayOf(
        0x4F, 0x74, 0x61, 0x6B, 0x75, 0x74, 0x73, 0x75,
        0x46, 0x6C, 0x75, 0x6D, 0x6D, 0x6F, 0x78, 0x52,
        0x65, 0x70, 0x6F, 0x56, 0x65, 0x72, 0x62, 0x6F,
        0x73, 0x65, 0x4C, 0x6F, 0x67, 0x32, 0x30, 0x32
    )

    private fun xorEncrypt(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        val o = ByteArray(b.size)
        for (i in b.indices) o[i] = (b[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        return android.util.Base64.encodeToString(o, android.util.Base64.NO_WRAP)
    }

    private fun xorDecrypt(b64: String): String? = try {
        val o = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val b = ByteArray(o.size)
        for (i in o.indices) b[i] = (o[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        String(b, Charsets.UTF_8)
    } catch (_: Exception) { null }

    fun setVerbose(enabled: Boolean) {
        val changed = synchronized(lock) {
            val was = verbose
            verbose = enabled
            was != enabled
        }
        try { setKey(K_VERBOSE, enabled) } catch (_: Exception) {}
        if (enabled && changed) {
            writeVerbose("[${timeFormat.format(Date())}] ▸ verbose enabled")
        }
    }

    fun isVerbose(): Boolean = synchronized(lock) { verbose }

    private val RX_JWT = Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+""")
    private val RX_BEARER = Regex("""(?i)(bearer\s+)\S+""")
    private val RX_COOKIE = Regex("""(?i)(cookie["']?\s*[:=]\s*["']?)[^"\r\n]+""")
    private val RX_CF = Regex("""(?i)(cloudfront-(?:policy|signature|key-pair-id)=)[^;&"\s]+""")

    private fun sanitize(input: String): String {
        var s = input
        s = RX_JWT.replace(s) { "<JWT>" }
        s = RX_BEARER.replace(s) { m -> m.groupValues[1] + "<BEARER>" }
        s = RX_COOKIE.replace(s) { m -> m.groupValues[1] + "<COOKIE>" }
        s = RX_CF.replace(s) { m -> m.groupValues[1] + "<CF>" }
        return s
    }

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            verbose = try { getKey<Boolean>(K_VERBOSE) ?: false } catch (_: Exception) { false }

            try { writer?.close() } catch (_: Exception) {}
            try { vWriter?.close() } catch (_: Exception) {}
            writer = null; vWriter = null
            buffer.clear()

            try {
                val f = File(context.filesDir, "otakutsu_log.txt")
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    val lines = f.readLines().takeLast(MAX_LINES / 2)
                    f.writeText(lines.joinToString("\n") + "\n")
                }
                if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { buffer.addLast(it) }
                writer = BufferedWriter(FileWriter(f, true))
            } catch (_: Exception) {}

            try {
                val vf = File(context.filesDir, "otakutsu_verbose.enc")
                if (vf.exists() && vf.length() > MAX_FILE_BYTES) {
                    val lines = vf.readLines().takeLast(MAX_LINES / 2)
                    vf.writeText(lines.joinToString("\n") + "\n")
                }
                vWriter = BufferedWriter(FileWriter(vf, true))
            } catch (_: Exception) {}

            try { android.util.Log.d(TAG, "OLog init, verbose=$verbose") } catch (_: Exception) {}
        }
    }

    private fun writeLine(line: String, isVerboseLine: Boolean = false) {
    synchronized(lock) {
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        try {
            writer?.appendLine(line)
            pendingWrites++
            if (pendingWrites >= 30) { writer?.flush(); pendingWrites = 0 }
        } catch (_: Exception) {}
        if (isVerboseLine) {
            try {
                vWriter?.appendLine(xorEncrypt(line))
                vPendingWrites++
                if (vPendingWrites >= 30) { vWriter?.flush(); vPendingWrites = 0 }
            } catch (_: Exception) {}
        }
    }
    try { android.util.Log.d(TAG, line) } catch (_: Exception) {}
}

fun d(message: String) {
    writeLine("[${timeFormat.format(Date())}] $message", false)
}

fun v(message: String) {
    if (!isVerbose()) return
    writeLine("[${timeFormat.format(Date())}] ▸ $message", true)
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

    fun allVerboseSanitized(): String = allSanitized()

    fun count(): Int = synchronized(lock) { buffer.size }
    fun countVerbose(): Int = count()

    fun clear() {
        synchronized(lock) {
            buffer.clear(); vBuffer.clear()
            try { writer?.close(); writer = null } catch (_: Exception) {}
            try { vWriter?.close(); vWriter = null } catch (_: Exception) {}
            try {
                appContext?.let { ctx ->
                    File(ctx.filesDir, "otakutsu_log.txt").delete()
                    File(ctx.filesDir, "otakutsu_verbose.enc").delete()
                    writer = BufferedWriter(FileWriter(File(ctx.filesDir, "otakutsu_log.txt"), true))
                    vWriter = BufferedWriter(FileWriter(File(ctx.filesDir, "otakutsu_verbose.enc"), true))
                }
            } catch (_: Exception) {}
        }
    }
}
