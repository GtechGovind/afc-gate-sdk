package com.qurkos.gate.controlpanel.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.qurkos.gate.controlpanel.ui.model.NumericInputRule
import com.qurkos.gate.controlpanel.ui.model.SerialPortOptionUi
import com.qurkos.gate.sdk.GatePassMode

/** Serial-device selector backed exclusively by descriptors discovered through the SDK. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SerialPortDropdown(
    selectedPort: String,
    ports: List<SerialPortOptionUi>,
    discoveryError: String?,
    onSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectionDetected = ports.any { it.name == selectedPort }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (ports.isNotEmpty()) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedPort,
            onValueChange = {},
            readOnly = true,
            label = { Text("Serial port") },
            placeholder = { Text("No port selected") },
            supportingText = {
                Text(
                    discoveryError
                        ?: when {
                            ports.isEmpty() -> "No serial ports detected"
                            !selectionDetected -> "Select one of ${ports.size} detected ports"
                            else -> ports.first { it.name == selectedPort }.description ?: "Detected serial device"
                        },
                )
            },
            isError = discoveryError != null || (selectedPort.isNotBlank() && !selectionDetected),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh serial ports")
                    }
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = ports.isNotEmpty())
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ports.forEach { port ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(port.name)
                            port.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    onClick = {
                        onSelected(port.name)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Digits-only field with immediate protocol-range and step validation. */
@Composable
internal fun NumericSettingField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    rule: NumericInputRule,
    modifier: Modifier = Modifier,
) {
    val valid = rule.accepts(value)
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.isEmpty() || candidate.all(Char::isDigit)) onValueChange(candidate)
        },
        label = { Text(label) },
        supportingText = { Text(if (valid) rule.guidance else "Enter ${rule.guidance}") },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Selects one vendor-neutral SDK passage mode without exposing protocol wire values. */
@Composable
internal fun PassModeDropdown(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    modes: Collection<GatePassMode> = GatePassMode.entries,
) {
    OptionDropdown(
        label = label,
        value = value,
        onValueChange = onValueChange,
        options = modes.map { SelectionOption(it.name, it.displayName()) },
        modifier = modifier,
    )
}

/** Read-only exposed menu for a finite set of typed configuration choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OptionDropdown(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    options: List<SelectionOption>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.value == value }?.label ?: value.toPassModeLabel(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onValueChange(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Labelled boolean setting with the explanation kept adjacent to its switch. */
@Composable
internal fun ToggleSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

internal data class SelectionOption(
    val value: String,
    val label: String,
)

internal val TAILING_LEVEL_OPTIONS = (0..1).map { SelectionOption(it.toString(), "Level $it") }
internal val HURRY_UP_LEVEL_OPTIONS = (0..3).map { SelectionOption(it.toString(), "Level $it") }
internal val CHILD_DETECTION_OPTIONS =
    listOf(
        SelectionOption("0", "Disabled"),
        SelectionOption("1", "Level 1"),
        SelectionOption("2", "Level 2"),
    )

private fun String.toPassModeLabel(): String {
    val normalized = trim().uppercase().replace(' ', '_').replace('-', '_')
    return GatePassMode.entries.firstOrNull { it.name == normalized }?.displayName() ?: this
}

private fun GatePassMode.displayName(): String =
    name
        .lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
