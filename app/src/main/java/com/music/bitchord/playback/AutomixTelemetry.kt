package com.music.bitchord.playback

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent Automix transition telemetry (djmix10).
 *
 * Release builds strip `android.util.Log` (proguard `-assumenosideeffects`),
 * so logcat can never show a transition decision. This object appends one
 * JSON object per line to `automix-telemetry.jsonl` in the app's external
 * files dir — pullable WITHOUT root:
 * `adb pull /sdcard/Android/data/com.music.bitchord.loopfix/files/automix-telemetry.jsonl`
 * (`filesDir/automix-telemetry.jsonl` is the fallback when external storage
 * is unavailable).
 *
 * Best-effort by design: every call swallows exceptions, never blocks the
 * audio path beyond a small append, and never throws into the caller.
 */
object AutomixTelemetry {
    private const val FILE_NAME = "automix-telemetry.jsonl"
    private const val MAX_BYTES = 2_000_000L

    private val counter = AtomicLong(0)
    private val writeLock = Any()
    @Volatile private var dir: File? = null

    /** Must be called once from a Context (PlaybackService). Safe to call repeatedly. */
    fun init(context: Context) {
        if (dir != null) return
        try {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            root.mkdirs()
            dir = root
        } catch (_: Throwable) {
            // Telemetry is optional; playback must not depend on it.
        }
    }

    /** Where the JSONL lives ("" when init hasn't run). */
    fun fileLocation(): String {
        val d = dir ?: return ""
        return File(d, FILE_NAME).path
    }

    /**
     * Append one event line. [name] is dotted, e.g. "mix.plan".
     * Field values: String (quoted), Number/Boolean (raw), null.
     */
    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        val d = dir ?: return
        synchronized(writeLock) {
            try {
                val sb = StringBuilder()
                sb.append('{')
                sb.append("\"ts\":").append(System.currentTimeMillis())
                sb.append(",\"seq\":").append(counter.incrementAndGet())
                sb.append(",\"ev\":").append(quote(name))
                for ((k, v) in fields) {
                    sb.append(',').append(quote(k)).append(':')
                    appendJson(sb, v)
                }
                sb.append("}\n")
                val file = File(d, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    // Single-file ring: start over rather than grow forever.
                    file.delete()
                }
                file.appendText(sb.toString())
            } catch (_: Throwable) {
                // Ignore: telemetry must never break playback.
            }
        }
    }

    private fun appendJson(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Double -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Float -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Number -> sb.append(value)
            is Map<*, *> -> {
                sb.append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) sb.append(',')
                    sb.append(quote(entry.key?.toString().orEmpty())).append(':')
                    appendJson(sb, entry.value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    appendJson(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> {
                sb.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    appendJson(sb, item)
                }
                sb.append(']')
            }
            else -> sb.append(quote(value.toString()))
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
