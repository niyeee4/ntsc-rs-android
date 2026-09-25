/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class SourceType { IMAGE, VIDEO }

sealed interface RenderState {
    data object Idle : RenderState
    data class Running(val done: Int, val total: Int) : RenderState
    data class Done(val uri: Uri) : RenderState
    data class Error(val message: String) : RenderState
}

class EditorViewModel : ViewModel() {
    private val _sourceUri = MutableStateFlow<Uri?>(null)
    val sourceUri: StateFlow<Uri?> = _sourceUri

    private val _sourceType = MutableStateFlow(SourceType.IMAGE)
    val sourceType: StateFlow<SourceType> = _sourceType

    private val _original = MutableStateFlow<Bitmap?>(null)
    val original: StateFlow<Bitmap?> = _original

    private val _processed = MutableStateFlow<Bitmap?>(null)
    val processed: StateFlow<Bitmap?> = _processed

    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing

    private val _easy = MutableStateFlow(false)
    val easy: StateFlow<Boolean> = _easy

    private val _settingsJson = MutableStateFlow("")
    val settingsJson: StateFlow<String> = _settingsJson

    private val _descriptors = MutableStateFlow<List<SettingDesc>>(emptyList())
    val descriptors: StateFlow<List<SettingDesc>> = _descriptors

    private val _videoMeta = MutableStateFlow<VideoMeta?>(null)
    val videoMeta: StateFlow<VideoMeta?> = _videoMeta

    private val _videoPosUs = MutableStateFlow(0L)
    val videoPosUs: StateFlow<Long> = _videoPosUs

    private val _render = MutableStateFlow<RenderState>(RenderState.Idle)
    val render: StateFlow<RenderState> = _render

    // Render options (video mirrors desktop H264Settings defaults)
    private val _savePng = MutableStateFlow(true) // PNG lossless by default
    val savePng: StateFlow<Boolean> = _savePng
    private val _jpegQuality = MutableStateFlow(92)
    val jpegQuality: StateFlow<Int> = _jpegQuality
    private val _h264Quality = MutableStateFlow(25)
    val h264Quality: StateFlow<Int> = _h264Quality
    private val _h264Speed = MutableStateFlow(3) // medium
    val h264Speed: StateFlow<Int> = _h264Speed
    private val _chromaSubsampling = MutableStateFlow(true) // PC default 4:2:0
    val chromaSubsampling: StateFlow<Boolean> = _chromaSubsampling
    private val _interlacedOutput = MutableStateFlow(false)
    val interlacedOutput: StateFlow<Boolean> = _interlacedOutput
    private val _exportScale = MutableStateFlow(0.5f) // 1.0 or 0.5
    val exportScale: StateFlow<Float> = _exportScale
    private val _splitPreview = MutableStateFlow(false)
    val splitPreview: StateFlow<Boolean> = _splitPreview
    private val _previewScale = MutableStateFlow(0.5f) // Half (480p) by default
    val previewScale: StateFlow<Float> = _previewScale
    private val _previewMuted = MutableStateFlow(false)
    val previewMuted: StateFlow<Boolean> = _previewMuted
    private val _effectEnabled = MutableStateFlow(true) // PC's Enable/Disable
    val effectEnabled: StateFlow<Boolean> = _effectEnabled
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _playerError = MutableStateFlow<String?>(null)
    val playerError: StateFlow<String?> = _playerError
    private val _previewError = MutableStateFlow<String?>(null)
    val previewError: StateFlow<String?> = _previewError
    private val _renderStartedMs = MutableStateFlow(0L)
    val renderStartedMs: StateFlow<Long> = _renderStartedMs

    private var ffHandle: Long = 0
    private var playbackJob: Job? = null
    private var playGen = 0
    private var audioPlayer: android.media.MediaPlayer? = null
    private var audioGen = -1

    private var previewJob: Job? = null
    private var lastPreviewMs = 0L
    private var renderCancel = AtomicBoolean(false)
    private var engine: NtscEngine? = null
    private var prefs: android.content.SharedPreferences? = null
    private var appCtx: Context? = null

    fun attachEngine(e: NtscEngine) {
        if (engine == null) {
            engine = e
            if (_settingsJson.value.isEmpty()) {
                setEasy(false)
            }
        }
    }

    /** Load persisted render + effect settings (called once from MainActivity). */
    fun attachPrefs(context: Context) {
        appCtx = context.applicationContext
        if (prefs != null) return
        prefs = context.getSharedPreferences("ntscrs", Context.MODE_PRIVATE)
        val p = prefs!!
        // Prefs schema version: bump when the DEFAULTS change so users coming
        // from older builds actually get the new defaults (old saved values
        // would otherwise mask them forever).
        if (p.getInt("prefsVersion", 0) < 2) {
            // Fresh defaults for r13+ (quality 25 / medium / half size / PNG),
            // but keep the user's effect settings if they have any.
            _h264Quality.value = 25
            _h264Speed.value = 3
            _chromaSubsampling.value = true
            _interlacedOutput.value = false
            _exportScale.value = 0.5f
            _savePng.value = true
            _jpegQuality.value = p.getInt("jpegQuality", 92).coerceIn(1, 100)
            val easy = p.getBoolean("easy", _easy.value)
            val json = p.getString("settingsJson", null)
            if (json != null && engine != null) {
                _easy.value = easy
                _settingsJson.value = json
                _descriptors.value = try {
                    parseDescriptors(NtscBridge.nativeDescriptorsJson(easy))
                } catch (_: Exception) {
                    emptyList()
                }
            }
            p.edit().putInt("prefsVersion", 2).apply()
            saveRenderPrefs()
            return
        }
        _h264Quality.value = p.getInt("h264Quality", 25).coerceIn(0, 48)
        _h264Speed.value = p.getInt("h264Speed", 3).coerceIn(0, 8)
        _chromaSubsampling.value = p.getBoolean("chromaSubsampling", true)
        _interlacedOutput.value = p.getBoolean("interlacedOutput", false)
        _exportScale.value = p.getFloat("exportScale", 0.5f).let { if (it == 1f) 1f else 0.5f }
        _savePng.value = p.getBoolean("savePng", true)
        _jpegQuality.value = p.getInt("jpegQuality", 92).coerceIn(1, 100)
        val easy = p.getBoolean("easy", _easy.value)
        val json = p.getString("settingsJson", null)
        if (json != null && engine != null) {
            _easy.value = easy
            _settingsJson.value = json
            _descriptors.value = try {
                parseDescriptors(NtscBridge.nativeDescriptorsJson(easy))
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun saveRenderPrefs() {
        val p = prefs ?: return
        p.edit()
            .putInt("prefsVersion", 2)
            .putInt("h264Quality", _h264Quality.value)
            .putInt("h264Speed", _h264Speed.value)
            .putBoolean("chromaSubsampling", _chromaSubsampling.value)
            .putBoolean("interlacedOutput", _interlacedOutput.value)
            .putFloat("exportScale", _exportScale.value)
            .putBoolean("savePng", _savePng.value)
            .putInt("jpegQuality", _jpegQuality.value)
            .putBoolean("easy", _easy.value)
            .putString("settingsJson", _settingsJson.value)
            .apply()
    }

    fun openSource(context: Context, uri: Uri, type: SourceType) {
        pausePlayback(reload = false) // fresh source is loaded below
        AppLog.log("open", "$type $uri")
        _sourceUri.value = uri
        _sourceType.value = type
        _render.value = RenderState.Idle
        _previewError.value = null
        _playerError.value = null
        _videoPosUs.value = 0L
        if (type == SourceType.VIDEO) {
            viewModelScope.launch(Dispatchers.IO) {
                val meta = getVideoMeta(context, uri)
                _videoMeta.value = meta
                if (meta == null) {
                    _previewError.value = "Cannot read this video (unsupported file?)"
                    return@launch
                }
                loadPreviewFrame(context)
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) { loadPreviewFrame(context) }
        }
    }

    fun setVideoPosition(context: Context, posUs: Long) {
        pausePlayback(reload = false) // caller reloads below at the new position
        _videoPosUs.value = posUs
        viewModelScope.launch(Dispatchers.IO) { loadPreviewFrame(context) }
    }

    /** PC-style play/pause preview (muted, processed frames at source fps). */
    fun togglePlayback(context: Context) {
        if (_isPlaying.value) {
            pausePlayback()
            return
        }
        val uri = _sourceUri.value ?: return
        if (_sourceType.value != SourceType.VIDEO) return
        val meta = _videoMeta.value ?: return
        pausePlayback(reload = false) // playback paints its own frames
        _playerError.value = null
        _isPlaying.value = true
        val gen = ++playGen
        val gotFrame = AtomicBoolean(false)
        playbackJob = viewModelScope.launch(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val src = copyUriToCache(appCtx, uri, "play-${System.currentTimeMillis()}.bin")
            if (src == null) {
                if (gen == playGen) {
                    _playerError.value = "Preview failed: cannot read video"
                    _isPlaying.value = false
                }
                return@launch
            }
            // Preview audio (original sound, in sync-ish with the frames).
            if (meta.hasAudio) {
                try {
                    val mp = android.media.MediaPlayer()
                    mp.setDataSource(appCtx, uri)
                    mp.prepare()
                    mp.seekTo((_videoPosUs.value / 1000).toInt())
                    if (_previewMuted.value) mp.setVolume(0f, 0f)
                    audioPlayer = mp
                    audioGen = gen
                } catch (e: Exception) {
                    AppLog.log("play", "audio unavailable: ${e.message}")
                    try {
                        audioPlayer?.release()
                    } catch (_: Exception) {
                    }
                    audioPlayer = null
                }
            }
            // Watchdog: bail out if the native side yields nothing.
            launch(Dispatchers.IO) {
                delay(8000)
                if (gen == playGen && _isPlaying.value && !gotFrame.get()) {
                    _playerError.value = "Preview failed: decoder produced no frames"
                    pausePlayback()
                }
            }
            val handle: Long
            try {
                // Full = source resolution; Half = longest side 480.
                val (hw, hh) = halfDims(meta.displayWidth, meta.displayHeight)
                val playDim = if (_previewScale.value < 1f) maxOf(hw, hh) else 4096
                handle = Ffmpeg.nativePlayOpen(src.absolutePath, playDim, _videoPosUs.value, meta.durationUs)
            } catch (e: Exception) {
                if (gen == playGen) {
                    _playerError.value = "Preview failed: ${e.message}"
                    _isPlaying.value = false
                }
                src.delete()
                return@launch
            }
            if (handle == 0L) {
                if (gen == playGen) {
                    val msg = Ffmpeg.nativeLastError().ifEmpty { "cannot open video" }
                    _playerError.value = "Preview failed: $msg"
                    _isPlaying.value = false
                }
                src.delete()
                return@launch
            }
            ffHandle = handle
            // Loop anchor: previews always restart from the very beginning.
            val loopStartUs = 0L
            var startPts: Long? = null
            var startUptime = 0L
            // Start audio together with the first frame batch.
            try {
                audioPlayer?.start()
            } catch (_: Exception) {
            }
            try {
                while (gen == playGen) {
                    val raw = try {
                        Ffmpeg.nativePlayNext(handle, _settingsJson.value, _easy.value, _effectEnabled.value)
                    } catch (e: Exception) {
                        if (gen == playGen) _playerError.value = "Preview failed: ${e.message}"
                        break
                    }
                    if (gen != playGen) break
                    if (raw.isEmpty()) {
                        val err = try {
                            Ffmpeg.nativePlayError(handle)
                        } catch (_: Exception) {
                            ""
                        }
                        if (err.isNotEmpty()) {
                            if (gen == playGen) _playerError.value = "Preview failed: $err"
                            break
                        }
                        // Clean end of video: loop back to the start.
                        try {
                            Ffmpeg.nativePlaySeek(handle, loopStartUs)
                        } catch (_: Exception) {
                            break
                        }
                        startPts = null
                        try {
                            audioPlayer?.seekTo((loopStartUs / 1000).toInt())
                            audioPlayer?.start()
                        } catch (_: Exception) {
                        }
                        continue
                    }
                    val fr = parsePlayFrame(raw) ?: break
                    gotFrame.set(true)
                    // NOTE: never recycle() bitmaps published to Compose.
                    _processed.value = rgbaToBitmap(fr.width, fr.height, fr.rgba)
                    _original.value = null
                    _videoPosUs.value = fr.ptsUs.coerceIn(0, meta.durationUs)
                    // Pace by presentation timestamps (exact speed, VFR-safe),
                    // anchored at the first shown frame (no drift).
                    val now = android.os.SystemClock.uptimeMillis()
                    if (startPts == null) {
                        startPts = fr.ptsUs
                        startUptime = now
                    } else {
                        val target = startUptime + (fr.ptsUs - startPts!!) / 1000
                        if (now < target) {
                            try {
                                delay(target - now)
                            } catch (_: kotlinx.coroutines.CancellationException) {
                                break
                            }
                        }
                    }
                }
            } finally {
                try {
                    Ffmpeg.nativePlayClose(handle)
                } catch (_: Exception) {
                }
                if (ffHandle == handle) ffHandle = 0
                // Only tear down OUR audio (a newer playback may own the field).
                if (audioGen == gen) {
                    try {
                        audioPlayer?.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        audioPlayer?.release()
                    } catch (_: Exception) {
                    }
                    audioPlayer = null
                    audioGen = -1
                }
                src.delete()
            }
            if (gen == playGen) {
                // Error path (looping playback has no natural end):
                // stop everything, including audio, and restore the still.
                pausePlayback()
            }
        }
    }

    fun pausePlayback(reload: Boolean = true) {
        val wasPlaying = _isPlaying.value
        playGen++
        playbackJob?.cancel()
        playbackJob = null
        ffHandle = 0 // the job's finally{} closes the real handle
        try {
            audioPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            audioPlayer?.release()
        } catch (_: Exception) {
        }
        audioPlayer = null
        audioGen = -1
        if (_isPlaying.value) _isPlaying.value = false
        // After playback the still preview is gone (_original == null), so
        // effect/split toggles would do nothing until the next scrub --
        // reload the frame immediately.
        if (reload && wasPlaying && _sourceType.value == SourceType.VIDEO && _original.value == null) {
            val ctx = appCtx
            if (ctx != null) viewModelScope.launch(Dispatchers.IO) { loadPreviewFrame(ctx) }
        }
    }

    /** Whether interlaced output is allowed (PC: only for interleaved fields). */
    fun interlacedAllowed(): Boolean {
        return try {
            val v = org.json.JSONObject(_settingsJson.value).optInt("use_field", 4)
            v == 4 || v == 5
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun loadPreviewFrame(context: Context) {
        val uri = _sourceUri.value ?: return
        // Full = source resolution; Half = longest side 480 (always
        // visibly different from Full, whatever the source size).
        val full = if (_sourceType.value == SourceType.VIDEO) {
            grabVideoFrame(context, uri, _videoPosUs.value, 4096)
        } else {
            loadBitmapSampled(context, uri, 4096)
        }
        if (full == null) {
            _previewError.value = "Cannot decode a preview frame from this file"
            return
        }
        _previewError.value = null
        // NOTE: no recycle() of published bitmaps; `full` here is private.
        val bmp = if (_previewScale.value < 1f) {
            val (hw, hh) = halfDims(full.width, full.height)
            if (hw == full.width && hh == full.height) full
            else {
                val half = Bitmap.createScaledBitmap(full, hw, hh, true)
                if (half !== full) {
                    try {
                        full.recycle()
                    } catch (_: Exception) {
                    }
                }
                half
            }
        } else full
        _original.value = bmp
        reprocess()
    }

    /**
     * Half dims = 480p by height (never upscale smaller sources):
     * 1920x1080 -> 854x480, 1440x1080 -> 640x480. Kept even for x264.
     */
    private fun halfDims(w: Int, h: Int): Pair<Int, Int> {
        if (h <= 480) return w to h
        val hw = (w * 480.0 / h).roundToInt().coerceAtLeast(2)
        return (if (hw % 2 == 1) hw + 1 else hw) to 480
    }

    fun setEasy(easy: Boolean, context: Context? = null) {
        _easy.value = easy
        val eng = engine ?: return
        _settingsJson.value = NtscBridge.nativeDefaultSettingsJson(easy)
        _descriptors.value = try {
            parseDescriptors(NtscBridge.nativeDescriptorsJson(easy))
        } catch (_: Exception) {
            emptyList()
        }
        saveRenderPrefs()
        reprocess()
    }

    fun applyPreset(preset: Preset) {
        _easy.value = preset.easy
        _settingsJson.value = preset.settingsJson
        _descriptors.value = try {
            parseDescriptors(NtscBridge.nativeDescriptorsJson(preset.easy))
        } catch (_: Exception) {
            emptyList()
        }
        reprocess()
        saveRenderPrefs()
    }

    fun updateValue(name: String, value: Any) {
        _settingsJson.value = updateSetting(_settingsJson.value, name, value)
        reprocessThrottled()
        saveRenderPrefs()
    }

    fun importSettingsJson(json: String): Boolean {
        return try {
            // Validate through the engine default lists by parsing keys.
            val o = org.json.JSONObject(json)
            if (!o.has("version")) return false
            _easy.value = isEasyJson(json)
            _settingsJson.value = json
            _descriptors.value = try {
                parseDescriptors(NtscBridge.nativeDescriptorsJson(_easy.value))
            } catch (_: Exception) {
                emptyList()
            }
            reprocess()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setSavePng(v: Boolean) { _savePng.value = v; saveRenderPrefs() }
    fun setJpegQuality(v: Int) { _jpegQuality.value = v; saveRenderPrefs() }
    fun setH264Quality(v: Int) { _h264Quality.value = v.coerceIn(0, 48); saveRenderPrefs() }
    fun setH264Speed(v: Int) { _h264Speed.value = v.coerceIn(0, 8); saveRenderPrefs() }
    fun setChromaSubsampling(v: Boolean) { _chromaSubsampling.value = v; saveRenderPrefs() }
    fun setInterlacedOutput(v: Boolean) {
        _interlacedOutput.value = v && interlacedAllowed()
        saveRenderPrefs()
    }
    fun setExportScale(v: Float) { _exportScale.value = v; saveRenderPrefs() }
    fun setEffectEnabled(v: Boolean) {
        _effectEnabled.value = v
        // Immediate: toggles must flip the preview at once (also reload the
        // still frame first when playback wiped it).
        if (_original.value == null && _sourceType.value == SourceType.VIDEO) {
            val ctx = appCtx
            if (ctx != null) viewModelScope.launch(Dispatchers.IO) { loadPreviewFrame(ctx) }
            else reprocess()
        } else reprocess()
    }
    fun setSplitPreview(v: Boolean) { _splitPreview.value = v }
    fun setPreviewScale(v: Float) {
        _previewScale.value = v
        pausePlayback(reload = false)
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = appCtx ?: return@launch
            loadPreviewFrame(ctx)
        }
    }
    fun setPreviewMuted(v: Boolean) {
        _previewMuted.value = v
        try {
            audioPlayer?.setVolume(if (v) 0f else 1f, if (v) 0f else 1f)
        } catch (_: Exception) {
        }
    }

    /** PC's Reset: restore defaults for the current mode. */
    fun resetSettings() {
        setEasy(_easy.value)
    }

    /**
     * Live slider updates: run at most every ~350ms while dragging (leading
     * edge fires at once, trailing edge always fires after release).
     */
    private fun reprocessThrottled() {
        previewJob?.cancel()
        val wait = (350 - (android.os.SystemClock.uptimeMillis() - lastPreviewMs)).coerceAtLeast(0)
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            if (wait > 0) delay(wait)
            lastPreviewMs = android.os.SystemClock.uptimeMillis()
            reprocess()
        }
    }

    // Serializes preview engine runs and drops stale ones: when sliders move
    // fast, only the latest settings get displayed instead of queueing up
    // outdated frames (which felt like lag).
    private val previewEngineMutex = Mutex()
    private var previewGen = 0

    fun reprocess() {
        val eng = engine ?: return
        val src = _original.value ?: return
        val json = _settingsJson.value
        if (json.isEmpty()) return
        val gen = ++previewGen
        viewModelScope.launch(Dispatchers.Default) {
            // If a newer request is already waiting, skip this one entirely.
            if (gen != previewGen) return@launch
            previewEngineMutex.withLock {
                if (gen != previewGen) return@withLock
                _processing.value = true
                try {
                    if (!_effectEnabled.value) {
                        // PC's Disable: bypass the effect.
                        if (gen == previewGen) _processed.value = null
                    } else {
                        val frameNum = if (_sourceType.value == SourceType.VIDEO) {
                            val meta = _videoMeta.value
                            ((_videoPosUs.value / 1_000_000.0) * (meta?.frameRate ?: 30f)).toInt()
                        } else 0
                        val out = processBitmap(eng, src, frameNum, json, _easy.value)
                        if (gen == previewGen) _processed.value = out
                    }
                } catch (_: Exception) {
                }
                _processing.value = false
            }
        }
    }

    fun renderFull(context: Context) {
        val eng = engine ?: return
        val uri = _sourceUri.value ?: return
        if (_render.value is RenderState.Running) return
        pausePlayback(reload = false)
        renderCancel = AtomicBoolean(false)
        viewModelScope.launch(Dispatchers.IO) {
            _renderStartedMs.value = System.currentTimeMillis()
            _render.value = RenderState.Running(0, 100)
            try {
                if (_sourceType.value == SourceType.IMAGE) {
                    val full = loadBitmapSampled(context, uri, 4096)
                        ?: throw ExportException("Cannot decode image")
                    _render.value = RenderState.Running(30, 100)
                    // Render size: Full = source, Half = longest side 480.
                    val srcBmp = if (_exportScale.value < 1f) {
                        val (hw, hh) = halfDims(full.width, full.height)
                        if (hw == full.width && hh == full.height) full
                        else {
                            val half = Bitmap.createScaledBitmap(full, hw, hh, true)
                            if (half !== full) full.recycle()
                            half
                        }
                    } else full
                    val out = withContext(Dispatchers.Default) {
                        if (_effectEnabled.value) processBitmap(eng, srcBmp, 0, _settingsJson.value, _easy.value)
                        else srcBmp
                    }
                    if (renderCancel.get()) throw RenderCancelled()
                    _render.value = RenderState.Running(80, 100)
                    val name = "ntscrs-${System.currentTimeMillis()}." + if (_savePng.value) "png" else "jpg"
                    val saved = saveBitmapToGallery(context, out, name, _jpegQuality.value, _savePng.value)
                    // All three are private to this render (never published to
                    // Compose), so recycling is safe; the set dedups aliases.
                    for (b in setOf(full, srcBmp, out)) {
                        try {
                            b.recycle()
                        } catch (_: Exception) {
                        }
                    }
                    if (saved == null) throw ExportException("Save failed")
                    _render.value = RenderState.Done(saved)
                } else {
                    val meta = _videoMeta.value ?: throw ExportException("No video info")
                    val dur = meta.durationUs
                    // Always render the whole video (no trim).
                    val s = 0L
                    val e = dur
                    val scale = _exportScale.value
                    // Full = source size (0 = native); Half = longest side 480.
                    val (ow, oh) = if (scale < 1f) {
                        val (hw, hh) = halfDims(meta.codedWidth, meta.codedHeight)
                        if (hw == meta.codedWidth && hh == meta.codedHeight) 0 to 0
                        else hw to hh
                    } else 0 to 0
                    // FFmpeg needs a filesystem path: stage the content URI.
                    val src = copyUriToCache(context, uri, "render-in.bin")
                        ?: throw ExportException("Cannot read video")
                    val tmpOut = java.io.File(context.cacheDir, "ntsc-render-${System.currentTimeMillis()}.mp4")
                    try {
                        val params = org.json.JSONObject().apply {
                            put("in_path", src.absolutePath)
                            put("out_path", tmpOut.absolutePath)
                            put("settings_json", _settingsJson.value)
                            put("easy", _easy.value)
                            put("quality", _h264Quality.value)
                            put("speed", _h264Speed.value)
                            put("use444", !_chromaSubsampling.value)
                            put("interlaced", _interlacedOutput.value && interlacedAllowed())
                            put("trim_start_us", s)
                            put("trim_end_us", e)
                            put("out_w", ow)
                            put("out_h", oh)
                            put("effect_enabled", _effectEnabled.value)
                        }.toString()
                        val progress = object : RenderProgress {
                            override fun onProgress(done: Int, total: Int) {
                                _render.value = RenderState.Running(done, total)
                            }
                        }
                        AppLog.log(
                            "render",
                            "ffmpeg q=${_h264Quality.value} speed=${_h264Speed.value} " +
                                "420=${_chromaSubsampling.value} trim=$s..$e"
                        )
                        val code = Ffmpeg.nativeRender(params, progress)
                        when (code) {
                            Ffmpeg.R_OK -> {
                                val saved = publishMp4(context, tmpOut)
                                _render.value = RenderState.Done(saved)
                            }
                            Ffmpeg.R_CANCELLED -> _render.value = RenderState.Idle
                            else -> {
                                val msg = Ffmpeg.nativeLastError().ifEmpty { "Render failed" }
                                _render.value = RenderState.Error(msg)
                            }
                        }
                    } finally {
                        src.delete()
                        if (tmpOut.exists() && _render.value !is RenderState.Done) tmpOut.delete()
                    }
                }
            } catch (e: RenderCancelled) {
                _render.value = RenderState.Idle
            } catch (e: InterruptedException) {
                _render.value = RenderState.Idle
            } catch (e: Exception) {
                _render.value = if (renderCancel.get()) RenderState.Idle
                else RenderState.Error(e.message ?: "Render failed")
            }
        }
    }

    fun cancelRender() {
        renderCancel.set(true)
        try {
            Ffmpeg.nativeRenderCancel()
        } catch (_: Exception) {
        }
    }

    fun resetRender() {
        _render.value = RenderState.Idle
    }

    fun shareUri(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    override fun onCleared() {
        pausePlayback(reload = false)
        // NOTE: published bitmaps are intentionally not recycled (see above).
    }
}
