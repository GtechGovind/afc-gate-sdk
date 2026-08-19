package com.qurkos.gate.controlpanel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qurkos.gate.controlpanel.ui.model.ControlPanelDestination
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Stable 220dp navigation rail used by every desktop screen. */
@Composable
internal fun ControlPanelSidebar(
    selected: ControlPanelDestination,
    onNavigate: (ControlPanelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.SwapHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Column {
                Text("AFC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Gate Control", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        ControlPanelDestination.entries.forEach { destination ->
            NavigationItem(
                destination = destination,
                selected = destination == selected,
                onClick = { onNavigate(destination) },
            )
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(horizontal = 20.dp)) {
            StatusBadge(
                label = "HARDWARE",
                positive = false,
            )
        }
        Spacer(Modifier.height(12.dp))
        WorkstationClock()
    }
}

/** Displays the host workstation clock and updates on aligned one-second boundaries. */
@Composable
private fun WorkstationClock() {
    var localTime by remember { mutableStateOf(currentLocalDateTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = Clock.System.now()
            localTime = now.toLocalDateTime(TimeZone.currentSystemDefault())
            delay(1_000L - (now.toEpochMilliseconds() % 1_000L))
        }
    }
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "WORKSTATION TIME",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            localTime.displayText(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun currentLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

internal fun LocalDateTime.displayText(): String =
    "$day ${MONTH_NAMES[month.ordinal]} $year\n" +
        "${hour.twoDigits()}:${minute.twoDigits()}:${second.twoDigits()}"

private fun Int.twoDigits(): String = toString().padStart(2, '0')

@Composable
private fun NavigationItem(
    destination: ControlPanelDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val foreground = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .clickable(role = Role.Tab, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(3.dp, 30.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Icon(destination.icon, destination.title, tint = foreground)
        Text(destination.title, color = foreground, style = MaterialTheme.typography.bodyLarge)
    }
}

private val ControlPanelDestination.icon: ImageVector
    get() =
        when (this) {
            ControlPanelDestination.LIVE_CONTROL -> Icons.Outlined.Home
            ControlPanelDestination.SENSORS -> Icons.Outlined.Sensors
            ControlPanelDestination.CONFIGURATION -> Icons.Outlined.Settings
            ControlPanelDestination.DIAGNOSTICS -> Icons.AutoMirrored.Outlined.ShowChart
            ControlPanelDestination.EVENT_LOG -> Icons.AutoMirrored.Outlined.ListAlt
        }

private val MONTH_NAMES =
    listOf(
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December",
    )
