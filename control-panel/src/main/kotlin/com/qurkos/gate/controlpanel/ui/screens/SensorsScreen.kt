package com.qurkos.gate.controlpanel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qurkos.gate.controlpanel.ui.components.PageTitle
import com.qurkos.gate.controlpanel.ui.components.PanelCard
import com.qurkos.gate.controlpanel.ui.components.StatusBadge
import com.qurkos.gate.controlpanel.ui.model.ConnectionHealth
import com.qurkos.gate.controlpanel.ui.model.ControlPanelCallbacks
import com.qurkos.gate.controlpanel.ui.model.ControlPanelUiState
import com.qurkos.gate.controlpanel.ui.model.GateSensorUi
import com.qurkos.gate.controlpanel.ui.model.SensorGroup
import com.qurkos.gate.controlpanel.ui.model.SensorHealth

/** Compact paired-beam overview that keeps every physical input visible without scrolling. */
@Composable
internal fun SensorsScreen(
    state: ControlPanelUiState,
    callbacks: ControlPanelCallbacks,
    modifier: Modifier = Modifier,
) {
    val populatedGroups =
        state.gateTwin.sensors
            .map(GateSensorUi::group)
            .distinct()
    val filteredSensors =
        state.gateTwin.sensors.filter {
            state.sensorGroupFilter == null || it.group == state.sensorGroupFilter
        }
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Sensors", "Paired physical inputs for fast obstruction and fault isolation")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = state.sensorGroupFilter == null,
                onClick = { callbacks.onSensorGroupFilterChanged(null) },
                label = { Text("All · ${state.gateTwin.sensors.size}") },
            )
            populatedGroups.forEach { group ->
                FilterChip(
                    selected = state.sensorGroupFilter == group,
                    onClick = { callbacks.onSensorGroupFilterChanged(group) },
                    label = { Text(group.title) },
                )
            }
        }
        SensorSummary(state)
        LazyVerticalGrid(
            columns = GridCells.Fixed(SENSOR_PAIR_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(pairSensors(filteredSensors), key = SensorPairUi::key) { pair ->
                SensorPairCard(
                    pair = pair,
                    selectedSensorId = state.gateTwin.highlightedSensorId,
                    onSensorSelected = callbacks::onSensorSelected,
                )
            }
        }
    }
}

@Composable
private fun SensorSummary(state: ControlPanelUiState) {
    if (state.connectionHealth != ConnectionHealth.CONNECTED) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = .08f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(SensorHealth.UNKNOWN)
            Text("Telemetry unavailable", style = MaterialTheme.typography.labelLarge)
            Text(
                "Connect the physical gate to read all ${state.gateTwin.sensors.size} inputs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SensorHealth.entries.forEach { health ->
            val count = state.gateTwin.sensors.count { it.health == health }
            if (count > 0) {
                StatusBadge(label = "$count ${health.name}", positive = health == SensorHealth.CLEAR)
            }
        }
    }
}

@Composable
private fun SensorPairCard(
    pair: SensorPairUi,
    selectedSensorId: Int?,
    onSensorSelected: (Int) -> Unit,
) {
    PanelCard(Modifier.fillMaxWidth().height(SENSOR_PAIR_HEIGHT)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pair.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(pair.group.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(Modifier.padding(vertical = 7.dp), color = MaterialTheme.colorScheme.outlineVariant)
            pair.sensors.forEachIndexed { index, sensor ->
                SensorLine(
                    sensor = sensor,
                    selected = sensor.id == selectedSensorId,
                    onClick = { onSensorSelected(sensor.id) },
                )
                if (index < pair.sensors.lastIndex) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SensorLine(
    sensor: GateSensorUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(5.dp),
                ).clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = "${sensor.code}, ${sensor.name}, ${sensor.health.name.lowercase()}"
                }.padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(sensor.code, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(.22f))
        Text(
            sensor.name.substringAfterLast(' '),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(.43f),
        )
        Row(
            modifier = Modifier.weight(.35f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(sensor.health)
            Text(sensor.health.displayName, style = MaterialTheme.typography.labelMedium, color = sensor.health.statusColor)
        }
    }
}

@Composable
private fun StatusDot(health: SensorHealth) {
    Box(Modifier.size(7.dp).background(health.statusColor, RoundedCornerShape(50)))
}

internal data class SensorPairUi(
    val key: String,
    val title: String,
    val group: SensorGroup,
    val sensors: List<GateSensorUi>,
)

internal fun pairSensors(sensors: List<GateSensorUi>): List<SensorPairUi> =
    sensors
        .groupBy { it.code.substringBeforeLast('-') }
        .map { (key, pair) ->
            SensorPairUi(
                key = key,
                title =
                    pair
                        .first()
                        .name
                        .removeSuffix(" left")
                        .removeSuffix(" right"),
                group = pair.first().group,
                sensors = pair.sortedBy(GateSensorUi::id),
            )
        }

private val SensorHealth.statusColor: androidx.compose.ui.graphics.Color
    @Composable get() =
        when (this) {
            SensorHealth.CLEAR -> MaterialTheme.colorScheme.secondary
            SensorHealth.ACTIVE -> MaterialTheme.colorScheme.primary
            SensorHealth.BLOCKED, SensorHealth.FAULT -> MaterialTheme.colorScheme.error
            SensorHealth.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        }

private val SensorHealth.displayName: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)

private const val SENSOR_PAIR_COLUMNS = 2
private val SENSOR_PAIR_HEIGHT = 126.dp
