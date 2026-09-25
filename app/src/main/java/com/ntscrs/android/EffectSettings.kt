/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Dynamic settings model built from the engine's descriptor JSON
 * ([NtscBridge.nativeDescriptorsJson]). This covers 100% of the effect
 * settings in both Easy and Advanced (full NtscEffect) modes without
 * hardcoding any of them, so new upstream settings appear automatically.
 */
sealed interface SettingKind {
    data class Enum(val options: List<SettingOption>) : SettingKind
    data class Percentage(val logarithmic: Boolean) : SettingKind
    data class IntRange(val min: Int, val max: Int) : SettingKind
    data class FloatRange(val min: Float, val max: Float, val logarithmic: Boolean) : SettingKind
    data object Bool : SettingKind
    data class Group(val children: List<SettingDesc>) : SettingKind
}

data class SettingOption(val label: String, val description: String?, val index: Int)

data class SettingDesc(
    val name: String,
    val id: Int,
    val label: String,
    val description: String?,
    val kind: SettingKind
)

fun parseDescriptors(json: String): List<SettingDesc> =
    parseDescList(JSONArray(json))

private fun parseDescList(arr: JSONArray): List<SettingDesc> =
    List(arr.length()) { i -> parseDesc(arr.getJSONObject(i)) }

private fun parseDesc(o: JSONObject): SettingDesc {
    val kindStr = o.getString("kind")
    val kind: SettingKind = when (kindStr) {
        "enum" -> SettingKind.Enum(
            List(o.getJSONArray("options").length()) { i ->
                val e = o.getJSONArray("options").getJSONObject(i)
                SettingOption(
                    e.getString("label"),
                    e.optString("description").ifEmpty { null },
                    e.getInt("index")
                )
            }
        )
        "percentage" -> SettingKind.Percentage(o.optBoolean("logarithmic"))
        "int" -> SettingKind.IntRange(o.getInt("min"), o.getInt("max"))
        "float" -> SettingKind.FloatRange(
            o.getDouble("min").toFloat(),
            o.getDouble("max").toFloat(),
            o.optBoolean("logarithmic")
        )
        "bool" -> SettingKind.Bool
        "group" -> SettingKind.Group(parseDescList(o.getJSONArray("children")))
        else -> error("Unknown setting kind: $kindStr")
    }
    return SettingDesc(
        name = o.getString("name"),
        id = o.getInt("id"),
        label = o.getString("label"),
        description = o.optString("description").ifEmpty { null },
        kind = kind
    )
}

/** Read a setting value from an engine settings JSON object. */
fun JSONObject.settingValue(name: String): Any? = when (val v = opt(name)) {
    JSONObject.NULL, null -> null
    else -> v
}

/**
 * Return a copy of [settingsJson] with [name] set to [value].
 * Unknown names are added verbatim (forward-compat); the engine ignores
 * unknown keys and clamps out-of-range numbers on parse.
 */
fun updateSetting(settingsJson: String, name: String, value: Any): String {
    val o = JSONObject(settingsJson)
    o.put(name, value)
    return o.toString()
}

/** Apply a batch of overrides on top of a base settings JSON. */
fun applyOverrides(baseJson: String, overrides: Map<String, Any>): String {
    val o = JSONObject(baseJson)
    for ((k, v) in overrides) o.put(k, v)
    return o.toString()
}

fun doubleOf(v: Any?): Double = when (v) {
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    else -> 0.0
}

fun intOf(v: Any?): Int = when (v) {
    is Number -> v.toInt()
    is Boolean -> if (v) 1 else 0
    else -> 0
}

fun boolOf(v: Any?): Boolean = when (v) {
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    else -> false
}
