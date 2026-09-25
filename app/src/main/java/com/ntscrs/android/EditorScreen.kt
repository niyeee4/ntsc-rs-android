/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private enum class Tab { TWEAK, PRESETS, RENDER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.TWEAK) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ntsc-rs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val landscape = maxWidth > maxHeight
            if (!landscape) {
                Column(Modifier.fillMaxSize()) {
                    PreviewPane(vm)
                    TabsRow(tab) { tab = it }
                    TabContent(vm, tab, Modifier.weight(1f))
                }
            } else {
                // Landscape like the PC layout: controls on the left,
                // preview on the right.
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.58f)) {
                        TabsRow(tab) { tab = it }
                        TabContent(vm, tab, Modifier.weight(1f))
                    }
                    Column(
                        Modifier.weight(0.42f).verticalScroll(rememberScrollState())
                    ) {
                        PreviewPane(vm, imageHeight = 200.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun TabsRow(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TabButton("Tweak", tab == Tab.TWEAK, Modifier.weight(1f)) { onTab(Tab.TWEAK) }
        TabButton("Presets", tab == Tab.PRESETS, Modifier.weight(1f)) { onTab(Tab.PRESETS) }
        TabButton("Render", tab == Tab.RENDER, Modifier.weight(1f)) { onTab(Tab.RENDER) }
    }
}

@Composable
private fun TabContent(vm: EditorViewModel, tab: Tab, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        when (tab) {
            Tab.TWEAK -> {
                val descs by vm.descriptors.collectAsState()
                val json by vm.settingsJson.collectAsState()
                Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = { vm.resetSettings() }) { Text("Reset") }
                }
                SettingsListUi(descs, json) { name, v -> vm.updateValue(name, v) }
            }
        }
        Tab.PRESETS -> PresetsPane(vm)
        Tab.RENDER -> RenderPane(vm)
    }
    }
}

@Composable
private fun PreviewPane(vm: EditorViewModel, imageHeight: androidx.compose.ui.unit.Dp = 240.dp) {
    val context = LocalContext.current
    val original by vm.original.collectAsState()
    val processed by vm.processed.collectAsState()
    val split by vm.splitPreview.collectAsState()
    val type by vm.sourceType.collectAsState()
    val meta by vm.videoMeta.collectAsState()
    val posUs by vm.videoPosUs.collectAsState()
    var compare by remember { mutableFloatStateOf(0.5f) }
    val previewError by vm.previewError.collectAsState()

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        if (previewError != null) {
            Text(previewError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
        }
        val bmp = processed ?: original
        if (bmp != null) {
            if (split && original != null && processed != null) {
                Row(Modifier.fillMaxWidth().height(imageHeight)) {
                    Image(
                        original!!.asImageBitmap(), "Original",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.weight(compare.coerceIn(0.05f, 0.95f))
                    )
                    Image(
                        processed!!.asImageBitmap(), "Processed",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.weight(1f - compare.coerceIn(0.05f, 0.95f))
                    )
                }
                Slider(value = compare, onValueChange = { compare = it }, modifier = Modifier.fillMaxWidth())
            } else {
                Image(
                    bmp.asImageBitmap(), "Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(imageHeight)
                )
            }
        } else {
            Row(Modifier.fillMaxWidth().height(imageHeight), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(12.dp))
                Text("Loading…")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Effect", style = MaterialTheme.typography.bodySmall)
            val effectOn by vm.effectEnabled.collectAsState()
            Switch(checked = effectOn, onCheckedChange = { vm.setEffectEnabled(it) })
            Spacer(Modifier.width(8.dp))
            Text("Split", style = MaterialTheme.typography.bodySmall)
            Switch(checked = split, onCheckedChange = { vm.setSplitPreview(it) })
        }
        if (type == SourceType.IMAGE && original != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${original!!.width}×${original!!.height}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                PreviewSizeChips(vm)
            }
        }
        if (type == SourceType.VIDEO && meta != null) {
            val m = meta!!
            val playing by vm.isPlaying.collectAsState()
            var sliderPos by remember(posUs, m.durationUs) {
                mutableFloatStateOf(if (m.durationUs > 0) posUs.toFloat() / m.durationUs else 0f)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { vm.togglePlayback(context) }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play"
                    )
                }
                val muted by vm.previewMuted.collectAsState()
                IconButton(onClick = { vm.setPreviewMuted(!muted) }) {
                    Icon(
                        if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (muted) "Unmute preview" else "Mute preview"
                    )
                }
                Text(
                    "${fmtTime((sliderPos * m.durationUs).toLong())} / ${fmtTime(m.durationUs)}" +
                        (if (bmp != null) "  ${bmp.width}×${bmp.height}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                PreviewSizeChips(vm)
            }
            Slider(
                value = sliderPos,
                onValueChange = { sliderPos = it },
                onValueChangeFinished = {
                    vm.setVideoPosition(context, (sliderPos * m.durationUs).toLong())
                },
                modifier = Modifier.fillMaxWidth()
            )
            val playerError by vm.playerError.collectAsState()
            if (playerError != null) {
                Text(playerError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun fmtTime(us: Long): String {
    val s = (us / 1_000_000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Preview resolution (Full = source size, Half = half), next to the resolution readout. */
@Composable
private fun PreviewSizeChips(vm: EditorViewModel) {
    val pvScale by vm.previewScale.collectAsState()
    FilterChip(
        selected = pvScale >= 1f,
        onClick = { vm.setPreviewScale(1f) },
        label = { Text("Full") }
    )
    Spacer(Modifier.width(4.dp))
    FilterChip(
        selected = pvScale < 1f,
        onClick = { vm.setPreviewScale(0.5f) },
        label = { Text("480") }
    )
}

@Composable
private fun PresetsPane(vm: EditorViewModel) {
    val context = LocalContext.current
    val json by vm.settingsJson.collectAsState()
    val easy by vm.easy.collectAsState()
    var userPresets by remember { mutableStateOf(loadUserPresets(context)) }
    var showSave by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var exportTarget by remember { mutableStateOf<Preset?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val p = exportTarget
        if (uri != null && p != null) {
            try {
                exportPresetToUri(context, p, uri)
            } catch (_: Exception) {
            }
        }
        exportTarget = null
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = "imported-${System.currentTimeMillis()}"
            val p = importPresetFromUri(context, uri, name)
            if (p != null) {
                saveUserPreset(context, p.name, p.settingsJson)
                userPresets = loadUserPresets(context)
                vm.applyPreset(p)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Built-in", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        val defaults = remember {
            mapOf(true to NtscBridge.nativeDefaultSettingsJson(true), false to NtscBridge.nativeDefaultSettingsJson(false))
        }
        for ((name, peasy, overrides) in builtinPresets()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(name, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val full = applyOverrides(defaults[peasy] ?: json, overrides)
                    vm.applyPreset(Preset(name, peasy, full))
                }) { Text("Apply") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My presets", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                Icon(Icons.Filled.FileUpload, contentDescription = "Import preset JSON")
            }
        }
        if (userPresets.isEmpty()) Text("None yet — save the current settings below.", style = MaterialTheme.typography.bodySmall)
        for (p in userPresets) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(p.name, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.applyPreset(p) }) { Text("Apply") }
                IconButton(onClick = {
                    exportTarget = p
                    exportLauncher.launch("${p.name}.json")
                }) { Icon(Icons.Filled.Share, contentDescription = "Export") }
                IconButton(onClick = {
                    deleteUserPreset(p)
                    userPresets = loadUserPresets(context)
                }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { showSave = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Text("  Save current settings as preset")
        }
        Text(
            "Preset files are compatible with desktop ntsc-rs-standalone.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save preset") },
            text = {
                OutlinedTextField(value = saveName, onValueChange = { saveName = it }, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    saveUserPreset(context, saveName.ifBlank { "preset" }, json)
                    userPresets = loadUserPresets(context)
                    saveName = ""
                    showSave = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RenderPane(vm: EditorViewModel) {
    val context = LocalContext.current
    val type by vm.sourceType.collectAsState()
    val render by vm.render.collectAsState()

    val savePng by vm.savePng.collectAsState()
    val jpegQ by vm.jpegQuality.collectAsState()
    val h264Quality by vm.h264Quality.collectAsState()
    val h264Speed by vm.h264Speed.collectAsState()
    val subsampling by vm.chromaSubsampling.collectAsState()
    val interlaced by vm.interlacedOutput.collectAsState()
    val scale by vm.exportScale.collectAsState()
    val renderStart by vm.renderStartedMs.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (type == SourceType.IMAGE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PNG (lossless)", modifier = Modifier.weight(1f))
                Switch(checked = savePng, onCheckedChange = { vm.setSavePng(it) })
            }
            if (!savePng) {
                Text("JPEG quality: $jpegQ")
                Slider(value = jpegQ.toFloat(), onValueChange = { vm.setJpegQuality(it.toInt()) }, valueRange = 1f..100f)
            }
            Spacer(Modifier.height(8.dp))
            Text("Size", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = scale == 1f, onClick = { vm.setExportScale(1f) }, label = { Text("Full") })
                FilterChip(selected = scale == 0.5f, onClick = { vm.setExportScale(0.5f) }, label = { Text("480") })
            }
        } else {
            // Codec (H.264 only, like PC's dropdown fixed to H.264)
            Text("Codec", style = MaterialTheme.typography.titleSmall)
            Text("H.264", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            // Quality 0..48, PC mapping: quantizer = 50 - quality.
            // (QP 0-1 / quality 49-50 makes files some phone players reject.)
            Text("Quality: $h264Quality", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = h264Quality.toFloat(),
                onValueChange = { vm.setH264Quality(it.toInt()) },
                valueRange = 0f..48f,
                steps = 47,
                modifier = Modifier.fillMaxWidth()
            )
            // Encoding speed 0..8, like PC.
            val speedName = when (h264Speed.coerceIn(0, 8)) {
                0 -> "veryslow"
                1 -> "slower"
                2 -> "slow"
                3 -> "medium"
                4 -> "fast"
                5 -> "faster"
                6 -> "veryfast"
                7 -> "superfast"
                else -> "ultrafast"
            }
            Text(
                "Encoding speed: $h264Speed ($speedName)",
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = h264Speed.toFloat(),
                onValueChange = { vm.setH264Speed(it.toInt()) },
                valueRange = 0f..8f,
                steps = 7,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("4:2:0 chroma subsampling", modifier = Modifier.weight(1f))
                Switch(checked = subsampling, onCheckedChange = { vm.setChromaSubsampling(it) })
            }
            val allowed = vm.interlacedAllowed()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Interlaced output")
                    if (!allowed) Text(
                        "Needs an interleaved field mode",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = interlaced && allowed,
                    enabled = allowed,
                    onCheckedChange = { vm.setInterlacedOutput(it) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Size", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = scale == 1f, onClick = { vm.setExportScale(1f) }, label = { Text("Full") })
                FilterChip(selected = scale == 0.5f, onClick = { vm.setExportScale(0.5f) }, label = { Text("480") })
            }
            Spacer(Modifier.height(8.dp))
            Text("Audio is copied from the source.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        when (val r = render) {
            is RenderState.Idle -> Button(
                onClick = { vm.renderFull(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (type == SourceType.IMAGE) "Save image" else "Render") }
            is RenderState.Running -> {
                val frac = if (r.total > 0) r.done.toFloat() / r.total else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("${r.done} / ${r.total}  •  ${etaText(r.done, r.total, renderStart)}")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.cancelRender() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
            is RenderState.Done -> {
                Text("Saved ✓", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.shareUri(context, r.uri, if (type == SourceType.IMAGE) "image/*" else "video/*")
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Text(" Share")
                    }
                    OutlinedButton(onClick = { vm.resetRender() }) { Text("New render") }
                }
            }
            is RenderState.Error -> {
                Text("Failed: ${r.message}", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(
                    "If this persists, note the message above — it names the failing stage.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.resetRender() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }
        }
    }
}

private fun etaText(done: Int, total: Int, startedMs: Long): String {
    if (done <= 0 || total <= 0 || startedMs <= 0) return "ETA --:--"
    val elapsed = System.currentTimeMillis() - startedMs
    if (elapsed <= 0) return "ETA --:--"
    val remain = elapsed * (total - done) / done
    val s = (remain / 1000).coerceAtLeast(0)
    return "ETA %d:%02d".format(s / 60, s % 60)
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    AboutScreenImpl(onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreenImpl(onBack: () -> Unit) {
    val context = LocalContext.current
    var licenses by remember { mutableStateOf("Loading…") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        licenses = try {
            context.assets.open("THIRD_PARTY_LICENSES.txt").bufferedReader().readText()
        } catch (_: Exception) {
            "License files missing."
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("About & licenses") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("ntsc-rs for Android", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Build ${com.ntscrs.android.BuildConfig.VERSION_NAME} " +
                    "(code ${com.ntscrs.android.BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "A port of https://github.com/ntsc-rs/ntsc-rs. " +
                    "The video-effect engine is the upstream Rust code, unchanged, " +
                    "running through JNI with all settings (Easy + Advanced), presets, " +
                    "image export (PNG/JPEG) and video render (H.264 MP4 + audio). " +
                    "Video decode/encode/mux uses the same FFmpeg + libx264 stack " +
                    "the desktop app relies on (software decode everywhere, so " +
                    "every device behaves identically). Engine v${NtscBridge.nativeVersion()}."
            )
            Spacer(Modifier.height(12.dp))
            Text(licenses, style = MaterialTheme.typography.bodySmall)
        }
    }
}
