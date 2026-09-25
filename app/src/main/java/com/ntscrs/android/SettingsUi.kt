/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Dynamic settings UI: renders 100% of engine settings from descriptors,
 * in both Easy and Advanced modes. New upstream settings appear with no
 * app changes.
 */
@Composable
fun SettingsListUi(
    descriptors: List<SettingDesc>,
    settingsJson: String,
    onChange: (name: String, value: Any) -> Unit
) {
    val values = remember(settingsJson) {
        try {
            JSONObject(settingsJson)
        } catch (_: Exception) {
            JSONObject()
        }
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(descriptors, key = { it.name }) { d ->
            SettingRow(d, values, 0, onChange)
        }
    }
}

@Composable
private fun SettingRow(
    d: SettingDesc,
    values: JSONObject,
    indent: Int,
    onChange: (String, Any) -> Unit
) {
    when (val k = d.kind) {
        is SettingKind.Group -> {
            val enabled = boolOf(values.settingValue(d.name) ?: true)
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.label, style = MaterialTheme.typography.titleSmall)
                            d.description?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = enabled, onCheckedChange = { onChange(d.name, it) })
                    }
                    if (enabled) {
                        for (child in k.children) {
                            SettingRow(child, values, indent + 1, onChange)
                        }
                    }
                }
            }
        }
        is SettingKind.Bool -> {
            val v = boolOf(values.settingValue(d.name))
            LabeledRow(d, indent, valueText = if (v) "On" else "Off") {
                Switch(checked = v, onCheckedChange = { onChange(d.name, it) })
            }
        }
        is SettingKind.Enum -> EnumRow(d, indent, intOf(values.settingValue(d.name)), k, onChange)
        is SettingKind.IntRange -> {
            val v = intOf(values.settingValue(d.name)).coerceIn(k.min, k.max)
            val span = k.max - k.min
            var editing by remember { mutableStateOf(false) }
            LabeledRow(
                d, indent, valueText = "$v",
                hint = "Range ${k.min}…${k.max}",
                onEdit = { editing = true }
            ) {
                Slider(
                    value = v.toFloat(),
                    onValueChange = { onChange(d.name, it.roundToInt().coerceIn(k.min, k.max)) },
                    valueRange = k.min.toFloat()..k.max.toFloat(),
                    steps = if (span in 1..100) span - 1 else 0,
                    modifier = Modifier.weight(1f)
                )
            }
            if (editing) {
                NumberInputDialog(
                    title = d.label, initial = "$v", hint = "Integer ${k.min}…${k.max}",
                    onDismiss = { editing = false },
                    onConfirm = { num ->
                        if (num != null) onChange(d.name, num.roundToInt().coerceIn(k.min, k.max))
                        editing = false
                    }
                )
            }
        }
        is SettingKind.FloatRange -> FloatRow(d, indent, doubleOf(values.settingValue(d.name)).toFloat(), k, onChange)
        is SettingKind.Percentage -> {
            val v = doubleOf(values.settingValue(d.name)).toFloat().coerceIn(0f, 1f)
            PercentRow(d, indent, v, k.logarithmic, onChange)
        }
    }
}

@Composable
private fun LabeledRow(
    d: SettingDesc,
    indent: Int,
    valueText: String,
    hint: String? = null,
    onEdit: (() -> Unit)? = null,
    control: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = (indent * 12).dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onEdit != null) {
                Text(
                    valueText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onEdit)
                )
            } else {
                Text(valueText, style = MaterialTheme.typography.labelMedium)
            }
        }
        d.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            control()
        }
    }
}

/** Type a value directly; out-of-range input is clamped, never rejected. */
@Composable
private fun NumberInputDialog(
    title: String,
    initial: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(hint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toDoubleOrNull()) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumRow(
    d: SettingDesc,
    indent: Int,
    current: Int,
    kind: SettingKind.Enum,
    onChange: (String, Any) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = kind.options.firstOrNull { it.index == current } ?: kind.options.firstOrNull()
    Column(Modifier.fillMaxWidth().padding(start = (indent * 12).dp, top = 4.dp, bottom = 4.dp)) {
        Text(d.label, style = MaterialTheme.typography.bodyMedium)
        d.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = selected?.label ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (o in kind.options) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(o.label)
                                o.description?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        onClick = {
                            onChange(d.name, o.index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatRow(
    d: SettingDesc,
    indent: Int,
    current: Float,
    kind: SettingKind.FloatRange,
    onChange: (String, Any) -> Unit
) {
    val lo = kind.min
    val hi = kind.max
    val t = if (kind.logarithmic) toLogT(current.coerceIn(lo, hi), lo, hi) else {
        if (hi == lo) 0f else ((current - lo) / (hi - lo)).coerceIn(0f, 1f)
    }
    var editing by remember { mutableStateOf(false) }
    LabeledRow(
        d, indent, valueText = fmtFloat(current),
        hint = "Range $lo…$hi",
        onEdit = { editing = true }
    ) {
        Slider(
            value = t,
            onValueChange = {
                val v = if (kind.logarithmic) fromLogT(it, lo, hi) else lo + (hi - lo) * it
                onChange(d.name, v.toDouble())
            },
            modifier = Modifier.weight(1f)
        )
    }
    if (editing) {
        NumberInputDialog(
            title = d.label, initial = current.toString(), hint = "Number $lo…$hi",
            onDismiss = { editing = false },
            onConfirm = { num ->
                if (num != null) onChange(d.name, num.toFloat().coerceIn(lo, hi).toDouble())
                editing = false
            }
        )
    }
}

@Composable
private fun PercentRow(
    d: SettingDesc,
    indent: Int,
    current: Float,
    logarithmic: Boolean,
    onChange: (String, Any) -> Unit
) {
    val t = if (logarithmic) toLogT(current, 0f, 1f) else current
    var editing by remember { mutableStateOf(false) }
    LabeledRow(
        d, indent, valueText = "%.2f%%".format(current * 100),
        hint = "Percent 0…100",
        onEdit = { editing = true }
    ) {
        Slider(
            value = t.coerceIn(0f, 1f),
            onValueChange = {
                val v = if (logarithmic) fromLogT(it, 0f, 1f) else it
                onChange(d.name, v.toDouble())
            },
            modifier = Modifier.weight(1f)
        )
    }
    if (editing) {
        NumberInputDialog(
            title = d.label, initial = "%.4f".format(current * 100), hint = "Percent 0…100",
            onDismiss = { editing = false },
            onConfirm = { num ->
                if (num != null) onChange(d.name, (num / 100).toFloat().coerceIn(0f, 1f).toDouble())
                editing = false
            }
        )
    }
}

private fun toLogT(v: Float, lo: Float, hi: Float): Float {
    if (hi <= 0f) return 0f
    return if (lo <= 0f) {
        // v = hi * t^4  =>  t = (v/hi)^(1/4)
        ((v / hi).coerceIn(0f, 1f)).pow(0.25f)
    } else {
        (ln((v / lo).coerceAtLeast(1e-9f)) / ln(hi / lo)).toFloat().coerceIn(0f, 1f)
    }
}

private fun fromLogT(t: Float, lo: Float, hi: Float): Float {
    val tt = t.coerceIn(0f, 1f)
    return if (lo <= 0f) hi * tt.pow(4f) else lo * (hi / lo).pow(tt)
}

private fun fmtFloat(v: Float): String {
    val a = kotlin.math.abs(v)
    return when {
        a >= 100f -> "%.1f".format(v)
        a >= 1f -> "%.3f".format(v)
        a >= 0.001f -> "%.5f".format(v)
        v == 0f -> "0"
        else -> "%.7f".format(v)
    }
}
