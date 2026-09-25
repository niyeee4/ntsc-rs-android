/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

class ExportException(stage: String, cause: Throwable? = null) :
    RuntimeException("$stage${cause?.let { ": ${it.message}" } ?: ""}", cause)

class RenderCancelled : RuntimeException("Cancelled")

// ---------------------------------------------------------------------------
// Still-image helpers
// ---------------------------------------------------------------------------

fun loadBitmapSampled(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val mw = bounds.outWidth
        val mh = bounds.outHeight
        if (mw <= 0 || mh <= 0) return null
        while (mw / sample > maxDim || mh / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (_: Exception) {
        null
    }
}

fun saveBitmapToGallery(
    context: Context,
    bmp: Bitmap,
    displayName: String,
    jpegQuality: Int, // 0..100; used when png=false
    png: Boolean
): Uri? = try {
    val mime = if (png) "image/png" else "image/jpeg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ntsc-rs")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    resolver.openOutputStream(uri)?.use { out ->
        if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        else bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(1, 100), out)
    }
    if (Build.VERSION.SDK_INT >= 29) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    uri
} catch (_: Exception) {
    null
}

// ---------------------------------------------------------------------------
// Video inspection / frame grab (preview)
// ---------------------------------------------------------------------------

data class VideoMeta(
    val codedWidth: Int,
    val codedHeight: Int,
    val rotation: Int,
    val durationUs: Long,
    val frameRate: Float,
    val hasAudio: Boolean
) {
    /** Display size after applying rotation. */
    val displayWidth: Int get() = if (rotation == 90 || rotation == 270) codedHeight else codedWidth
    val displayHeight: Int get() = if (rotation == 90 || rotation == 270) codedWidth else codedHeight
}

fun getVideoMeta(context: Context, uri: Uri): VideoMeta? {
    return try {
        val r = MediaMetadataRetriever()
        r.setDataSource(context, uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        if (w == null || h == null) {
            AppLog.log("meta", "no video size in metadata")
            r.release()
            return null
        }
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val fps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
        val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        r.release()
        val m = VideoMeta(w, h, rot, durMs * 1000L, fps, hasAudio)
        AppLog.log("meta", "video ${w}x$h rot=$rot durMs=$durMs fps=$fps audio=$hasAudio")
        m
    } catch (e: Exception) {
        AppLog.log("meta", "failed: ${e.message}\n${stackOf(e)}")
        null
    }
}

/** Grab one frame for preview, rotation-corrected and downscaled to [maxDim]. */
fun grabVideoFrame(context: Context, uri: Uri, timeUs: Long, maxDim: Int): Bitmap? {
    return try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
        } catch (e: Exception) {
            AppLog.log("grab", "setDataSource failed: ${e.message}")
            r.release()
            return null
        }
        val raw = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        if (raw == null) {
            AppLog.log("grab", "getFrameAtTime($timeUs) returned null")
            r.release()
            return null
        }
        var bmp = raw
        r.release()
        val rot = try {
            val r2 = MediaMetadataRetriever()
            r2.setDataSource(context, uri)
            val v = r2.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            r2.release()
            v
        } catch (_: Exception) {
            0
        }
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height).toFloat())
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt().coerceAtLeast(2), (bmp.height * scale).toInt().coerceAtLeast(2), true
            )
        } else bmp
        AppLog.log("grab", "frame ok ${out.width}x${out.height} @${timeUs}us")
        out
    } catch (e: Exception) {
        AppLog.log("grab", "failed: ${e.message}\n${stackOf(e)}")
        null
    }
}

// ---------------------------------------------------------------------------
// Decoder selection: HW first, software fallback
// ---------------------------------------------------------------------------
// Some vendor decoders (e.g. Qualcomm HEVC outputting UBWC-compressed frames)
// cannot render into an ImageReader surface, so no frames ever arrive.
// In that case we retry with a software (Google/C2-android) decoder, whose
// linear output ImageReader always accepts.

fun publishMp4(context: Context, file: File): Uri {    try {
        val name = "ntscrs-${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ntsc-rs")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ExportException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw ExportException("MediaStore write failed")
        if (Build.VERSION.SDK_INT >= 29) {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        file.delete()
        AppLog.log("render", "DONE published $uri")
        return uri
    } catch (e: Exception) {
        if (e is ExportException) throw e
        throw ExportException("Publishing MP4 failed", e)
    }
}

/** Cheap bilinear RGBA rescale. */
