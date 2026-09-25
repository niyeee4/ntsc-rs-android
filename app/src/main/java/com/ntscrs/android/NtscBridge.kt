/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

import android.graphics.Bitmap

/**
 * JNI bridge to the vendored ntsc-rs engine (rust/ntscrs-android).
 *
 * Pixel contract: RGBA bytes, row-major, `w*h*4` bytes. The engine pixel
 * format is `Rgbx`+`u8`, i.e. R,G,B,A per byte. Android [Bitmap] with
 * [Bitmap.Config.ARGB_8888] stores packed 0xAARRGGBB ints, so we convert
 * explicitly here -- no endianness assumptions.
 *
 * Settings contract: JSON strings identical to desktop ntsc-rs-standalone,
 * so presets can be shared with the desktop app byte-for-byte.
 */
class NtscBridge {
    companion object {
        init {
            System.loadLibrary("ntscrs_android")
        }

        const val OK = 0
        const val ERR_BAD_JSON = -1
        const val ERR_BAD_PIXELS = -2
        const val ERR_BAD_DIMS = -3
        const val ERR_INTERNAL = -4

        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeDefaultSettingsJson(easy: Boolean): String
        @JvmStatic external fun nativeEasyToFull(easyJson: String): String
        @JvmStatic external fun nativeDescriptorsJson(easy: Boolean): String
        @JvmStatic external fun nativeProcessRgba(
            handle: Long,
            width: Int,
            height: Int,
            frameNum: Int,
            scaleX: Float,
            scaleY: Float,
            settingsJson: String,
            easy: Boolean,
            pixels: ByteArray
        ): Int
        @JvmStatic external fun nativeVersion(): String

        fun errorMessage(code: Int): String = when (code) {
            OK -> "OK"
            ERR_BAD_JSON -> "Invalid settings"
            ERR_BAD_PIXELS -> "Invalid frame data"
            ERR_BAD_DIMS -> "Invalid frame size"
            else -> "Engine error ($code)"
        }
    }
}

/** Long-lived engine context (thread pool + SIMD level). Close when done. */
class NtscEngine : AutoCloseable {
    val handle: Long = NtscBridge.nativeCreate()

    /**
     * Process [pixels] (RGBA, [width]*[height]*4 bytes) in place.
     * @return 0 on success, negative error code otherwise.
     */
    fun processRgba(
        width: Int,
        height: Int,
        frameNum: Int,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        settingsJson: String,
        easy: Boolean,
        pixels: ByteArray
    ): Int = NtscBridge.nativeProcessRgba(
        handle, width, height, frameNum, scaleX, scaleY, settingsJson, easy, pixels
    )

    override fun close() = NtscBridge.nativeDestroy(handle)
}

/** Convert an ARGB_8888 bitmap to packed RGBA bytes. */
fun bitmapToRgba(src: Bitmap): Triple<Int, Int, ByteArray> {
    val bmp = if (src.config == Bitmap.Config.ARGB_8888) src
    else src.copy(Bitmap.Config.ARGB_8888, false) ?: src
    val w = bmp.width
    val h = bmp.height
    val argb = IntArray(w * h)
    bmp.getPixels(argb, 0, w, 0, 0, w, h)
    val out = ByteArray(w * h * 4)
    var j = 0
    for (px in argb) {
        out[j++] = ((px ushr 16) and 0xFF).toByte() // R
        out[j++] = ((px ushr 8) and 0xFF).toByte()  // G
        out[j++] = (px and 0xFF).toByte()           // B
        out[j++] = ((px ushr 24) and 0xFF).toByte() // A
    }
    if (bmp !== src) bmp.recycle()
    return Triple(w, h, out)
}

/** Convert packed RGBA bytes back to an ARGB_8888 bitmap. */
fun rgbaToBitmap(width: Int, height: Int, rgba: ByteArray): Bitmap {
    val argb = IntArray(width * height)
    var j = 0
    for (i in argb.indices) {
        val r = rgba[j++].toInt() and 0xFF
        val g = rgba[j++].toInt() and 0xFF
        val b = rgba[j++].toInt() and 0xFF
        val a = rgba[j++].toInt() and 0xFF
        argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * Process a bitmap through the engine, returning a new bitmap.
 * Alpha is preserved by the engine; we force it opaque-safe anyway.
 */
fun processBitmap(
    engine: NtscEngine,
    src: Bitmap,
    frameNum: Int,
    settingsJson: String,
    easy: Boolean
): Bitmap {
    val (w, h, rgba) = bitmapToRgba(src)
    val code = engine.processRgba(w, h, frameNum, 1f, 1f, settingsJson, easy, rgba)
    if (code != NtscBridge.OK) throw IllegalStateException(NtscBridge.errorMessage(code))
    return rgbaToBitmap(w, h, rgba)
}
