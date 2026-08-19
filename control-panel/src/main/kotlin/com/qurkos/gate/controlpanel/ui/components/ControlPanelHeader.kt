package com.qurkos.gate.controlpanel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qurkos.gate.controlpanel.ui.model.ConnectionHealth

/** Persistent controller identity, serial, firmware, and connection summary. */
@Composable
internal fun ControlPanelHeader(
    controllerName: String,
    serialPort: String,
    firmware: String,
    connectionHealth: ConnectionHealth,
    connectionActionEnabled: Boolean,
    onConnectionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(controllerName, style = MaterialTheme.typography.titleLarge)
            Text(
                "Physical gate control",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HeaderFact(Icons.Outlined.Usb, "Serial port", serialPort.ifBlank { "Not selected" })
        HeaderDivider()
        HeaderFact(Icons.Outlined.DeveloperBoard, "Firmware", firmware)
        HeaderDivider()
        HeaderFact(
            Icons.Outlined.Power,
            "Connection",
            connectionHealth.displayName,
            connectionHealth.color,
        )
        OutlinedButton(
            onClick = onConnectionClick,
            enabled = connectionActionEnabled,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(if (connectionHealth == ConnectionHealth.CONNECTED) "Disconnect" else "Connect")
        }
    }
}

@Composable
private fun HeaderFact(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor)
        }
    }
}

@Composable
private fun HeaderDivider() {
    Spacer(
        Modifier
            .fillMaxHeight(.42f)
            .width(1.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

private val ConnectionHealth.displayName: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)

private val ConnectionHealth.color: Color
    @Composable get() =
        when (this) {
            ConnectionHealth.CONNECTED -> MaterialTheme.colorScheme.secondary
            ConnectionHealth.CONNECTING -> MaterialTheme.colorScheme.tertiary
            ConnectionHealth.DEGRADED -> MaterialTheme.colorScheme.error
            ConnectionHealth.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        }
