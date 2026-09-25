/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

/**
 * FFmpeg-native pipeline (SW decode everywhere, libx264 encode, MP4 mux):
 * identical behavior on every device. All heavy lifting runs in Rust
 * (rust/ntscrs-android/src/ffbridge.rs + ffshim.c).
 */
interface RenderProgress {
    fun onProgress(done: Int, total: Int)
}

class Ffmpeg {
    companion object {
        init {
            System.loadLibrary("ntscrs_android")
        }

        const val R_OK = 0
        const val R_CANCELLED = 1
        const val R_FAILED = -1

        /** Blocking render. Returns R_OK / R_CANCELLED / R_FAILED. */
        @JvmStatic external fun nativeRender(paramsJson: String, progress: RenderProgress): Int
        @JvmStatic external fun nativeLastError(): String
        @JvmStatic external fun nativeRenderCancel()

        /** Open a preview player. Returns handle, 0 on failure. */
        @JvmStatic external fun nativePlayOpen(
            path: String, maxDim: Int, startUs: Long, endUs: Long
        ): Long
        /** Next frame; empty array = EOS or error (check nativePlayError). */
        @JvmStatic external fun nativePlayNext(
            handle: Long, settingsJson: String, easy: Boolean, effectEnabled: Boolean
        ): ByteArray
        @JvmStatic external fun nativePlayError(handle: Long): String
        @JvmStatic external fun nativePlaySeek(handle: Long, posUs: Long): Int
        @JvmStatic external fun nativePlayClose(handle: Long)
    }
}

data class PlayFrame(val width: Int, val height: Int, val ptsUs: Long, val rgba: ByteArray)

private fun le32(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0xFF)) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

private fun le64(b: ByteArray, off: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
    return v
}

/** Parse a native frame packet; null for EOS/invalid. */
fun parsePlayFrame(b: ByteArray): PlayFrame? {
    if (b.size < 16) return null
    val w = le32(b, 0)
    val h = le32(b, 4)
    val pts = le64(b, 8)
    if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
    val rgba = b.copyOfRange(16, b.size)
    if (rgba.size != w * h * 4) return null
    return PlayFrame(w, h, pts, rgba)
}

/** Copy a content:// URI to a temp file (ffmpeg needs a filesystem path). */
fun copyUriToCache(context: android.content.Context, uri: android.net.Uri, name: String): java.io.File? {
    return try {
        val out = java.io.File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { inp ->
            out.outputStream().use { inp.copyTo(it) }
        }
        out
    } catch (_: Exception) {
        null
    }
}
