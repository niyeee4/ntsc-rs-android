/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-app event log. Every important stage (open, decode, engine, x264,
 * mux, errors with stack traces) is recorded here so users can export it
 * from Settings and send it for diagnosis.
 */
object AppLog {
    data class Entry(val timeMs: Long, val tag: String, val msg: String)

    private const val MAX = 1000
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, msg: String) {
        try {
            Log.d("ntsc-rs/$tag", msg)
        } catch (_: Exception) {
        }
        synchronized(lock) {
            entries.addLast(Entry(System.currentTimeMillis(), tag, msg))
            while (entries.size > MAX) entries.removeFirst()
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun deviceHeader(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("ntsc-rs for Android -- diagnostic log")
        sb.appendLine("exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("app: 0.9.6  engine: ${runCatching { NtscBridge.nativeVersion() }.getOrDefault("?")}")
        sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}  sdk=${Build.VERSION.SDK_INT}")
        sb.appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("package: ${context.packageName}")
        return sb.toString()
    }

    fun decoderInventory(): String {
        val sb = StringBuilder()
        sb.appendLine("--- decoders ---")
        val mimes = listOf("video/avc", "video/hevc", "video/mp4v", "video/x-vnd.on2.vp9", "video/av01")
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (mime in mimes) {
                sb.appendLine("$mime:")
                var any = false
                for (info in list.codecInfos) {
                    if (info.isEncoder) continue
                    val types = try {
                        info.supportedTypes
                    } catch (_: Exception) {
                        continue
                    }
                    if (!types.contains(mime)) continue
                    any = true
                    val sw = info.name.startsWith("OMX.google.", true) ||
                        info.name.startsWith("c2.android.", true)
                    sb.appendLine("  ${info.name}${if (sw) " [software]" else " [HW]"}")
                }
                if (!any) sb.appendLine("  (none)")
            }
        } catch (e: Exception) {
            sb.appendLine("inventory failed: ${e.message}")
        }
        return sb.toString()
    }

    fun fullText(context: Context): String {
        val sb = StringBuilder()
        sb.append(deviceHeader(context))
        sb.append(decoderInventory())
        sb.appendLine("--- events (${entries.size}) ---")
        synchronized(lock) {
            for (e in entries) {
                sb.appendLine("${timeFmt.format(Date(e.timeMs))} [${e.tag}] ${e.msg}")
            }
        }
        return sb.toString()
    }

    /** Recent logcat lines for our process (native/render messages included). */
    fun dumpLogcat(maxLines: Int = 1500): String {
        return try {
            val pid = android.os.Process.myPid().toString()
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid", "-v", "brief"))
            val lines = p.inputStream.bufferedReader().readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "logcat unavailable: ${e.message}"
        }
    }

    fun fullTextWithLogcat(context: Context): String {
        return fullText(context) + "\n--- logcat (this process) ---\n" + dumpLogcat()
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ntsc-rs diagnostic log")
            putExtra(Intent.EXTRA_TEXT, text.take(450_000))
        }
        context.startActivity(Intent.createChooser(intent, "Share log"))
    }
}

fun stackOf(e: Throwable): String {
    val sw = java.io.StringWriter()
    e.printStackTrace(java.io.PrintWriter(sw))
    return sw.toString().take(4000)
}
