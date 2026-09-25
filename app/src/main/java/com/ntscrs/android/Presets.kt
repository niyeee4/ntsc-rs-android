/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Presets. The JSON format is identical to desktop ntsc-rs-standalone, so
 * presets can be exchanged with the desktop app (export/import below).
 *
 * Built-ins are stored as override-maps over the engine defaults, which
 * keeps them valid even if upstream adds new settings.
 */
data class Preset(
    val name: String,
    val easy: Boolean,
    val settingsJson: String,
    val userFile: File? = null // non-null for user presets on disk
)

// Enum indices must match rust/vendor/ntscrs (repr(u8) order).
// UseField: Alternating=0 Upper=1 Lower=2 Both=3 InterleavedUpper=4 InterleavedLower=5
// VHSTapeSpeed: NONE=0 SP=1 LP=2 EP=3
// ChromaDemodulationFilter: Box=0 Notch=1 OneLineComb=2 TwoLineComb=3 TwoD=4 TwoDAdaptive=5
// LumaLowpass: None=0 Box=1 Notch=2 ; ChromaLowpass: None=0 Light=1 Full=2

fun builtinPresets(): List<Triple<String, Boolean, Map<String, Any>>> = listOf(
    Triple(
        "Broadcast NTSC", false, mapOf(
            // Desktop defaults, made explicit so the preset is self-describing.
            "use_field" to 4,
            "chroma_demodulation" to 1,
            "composite_noise" to true,
            "luma_noise" to true,
            "chroma_noise" to true,
            "head_switching" to false,
            "tracking_noise" to false,
            "ringing" to false,
            "vhs_settings" to false
        )
    ),
    Triple(
        "VHS SP", true, mapOf(
            "ez_vhs_settings" to true,
            "ez_vhs_tape_speed" to 1,
            "ez_vhs_sharpen" to 0.35,
            "ez_vhs_edge_wave" to 0.5,
            "ez_vhs_head_switching" to 6.0,
            "ez_snow" to 0.0008
        )
    ),
    Triple(
        "VHS EP (worn tape)", true, mapOf(
            "ez_vhs_settings" to true,
            "ez_vhs_tape_speed" to 3,
            "ez_vhs_chroma_loss" to 0.02,
            "ez_vhs_sharpen" to 0.2,
            "ez_vhs_edge_wave" to 1.4,
            "ez_vhs_head_switching" to 9.0,
            "ez_tracking_noise_enabled" to true,
            "ez_tracking_noise_height" to 24,
            "ez_tracking_noise_intensity" to 0.6,
            "ez_snow" to 0.004
        )
    ),
    Triple(
        "Bad tracking", false, mapOf(
            "use_field" to 4,
            "tracking_noise" to true,
            "tracking_noise_height" to 48,
            "tracking_noise_wave_intensity" to 28.0,
            "tracking_noise_snow_intensity" to 0.2,
            "tracking_noise_snow_anisotropy" to 0.4,
            "tracking_noise_noise_intensity" to 0.5,
            "head_switching" to true,
            "head_switching_height" to 12,
            "head_switching_offset" to 4,
            "head_switching_horizontal_shift" to 72.0,
            "snow_intensity" to 0.004
        )
    ),
    Triple(
        "Rainbow artifacts", false, mapOf(
            "use_field" to 4,
            "input_luma_filter" to 0, // None -> rainbows everywhere
            "chroma_lowpass_in" to 0,
            "chroma_demodulation" to 0, // Box
            "chroma_lowpass_out" to 0,
            "luma_smear" to 0.9,
            "composite_preemphasis" to 2.0
        )
    ),
    Triple(
        "Clean composite", false, mapOf(
            "use_field" to 3, // Both fields, no interlace loss
            "input_luma_filter" to 2,
            "chroma_lowpass_in" to 2,
            "chroma_demodulation" to 4, // 2D
            "chroma_lowpass_out" to 2,
            "composite_noise" to false,
            "luma_noise" to false,
            "chroma_noise" to false,
            "snow_intensity" to 0.0,
            "chroma_phase_noise_intensity" to 0.0,
            "head_switching" to false,
            "tracking_noise" to false,
            "ringing" to false,
            "vhs_settings" to false
        )
    )
)

private fun presetsDir(context: Context): File =
    File(context.filesDir, "presets").apply { mkdirs() }

fun loadUserPresets(context: Context): List<Preset> =
    presetsDir(context).listFiles { f -> f.extension == "json" }
        ?.sortedBy { it.name }
        ?.mapNotNull { f ->
            try {
                val json = f.readText()
                JSONObject(json) // validate
                Preset(f.nameWithoutExtension, isEasyJson(json), json, f)
            } catch (_: Exception) {
                null
            }
        } ?: emptyList()

fun isEasyJson(json: String): Boolean = try {
    val o = JSONObject(json)
    o.has("ez_random_seed") || (o.has("version") && !o.has("random_seed"))
} catch (_: Exception) {
    false
}

fun saveUserPreset(context: Context, name: String, settingsJson: String): File {
    val safe = name.replace(Regex("[^A-Za-z0-9 _-]+"), "_").trim().ifEmpty { "preset" }
    val f = File(presetsDir(context), "$safe.json")
    f.writeText(settingsJson)
    return f
}

fun deleteUserPreset(preset: Preset): Boolean = preset.userFile?.delete() ?: false

/** Export preset JSON to a user-chosen SAF URI. */
fun exportPresetToUri(context: Context, preset: Preset, uri: Uri) {
    context.contentResolver.openOutputStream(uri)?.use { it.write(preset.settingsJson.toByteArray()) }
}

/** Import a desktop (or our) preset file; returns parsed preset or null. */
fun importPresetFromUri(context: Context, uri: Uri, fallbackName: String): Preset? {
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val json = bytes.toString(Charsets.UTF_8)
        JSONObject(json)
        if (!JSONObject(json).has("version")) return null
        Preset(fallbackName, isEasyJson(json), json)
    } catch (_: Exception) {
        null
    }
}
