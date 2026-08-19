package com.qurkos.gate.controlpanel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qurkos.gate.controlpanel.ui.components.EventRow
import com.qurkos.gate.controlpanel.ui.components.PageTitle
import com.qurkos.gate.controlpanel.ui.components.PanelCard
import com.qurkos.gate.controlpanel.ui.model.ControlPanelCallbacks
import com.qurkos.gate.controlpanel.ui.model.ControlPanelUiState
import com.qurkos.gate.controlpanel.ui.model.EventSeverity
import com.qurkos.gate.controlpanel.ui.model.GateEventUi

/** Searchable and severity-filtered controller audit history. */
@Composable
internal fun EventLogScreen(
    state: ControlPanelUiState,
    callbacks: ControlPanelCallbacks,
    modifier: Modifier = Modifier,
) {
    val filteredEvents =
        state.events.filter { event ->
            (state.eventSeverityFilter == null || event.severity == state.eventSeverityFilter) &&
                (state.eventSearchQuery.isBlank() || event.matches(state.eventSearchQuery))
        }
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Event Log", "Search and export controller, passenger, sensor, and safety events") {
            Button(onClick = callbacks::onEventLogExport) {
                Icon(Icons.Outlined.Download, null)
                Text("Export", Modifier.padding(start = 8.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.eventSearchQuery,
                onValueChange = callbacks::onEventSearchChanged,
                modifier = Modifier.weight(1f),
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Search title, detail, or category") },
                singleLine = true,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = state.eventSeverityFilter == null,
                    onClick = { callbacks.onEventSeverityFilterChanged(null) },
                    label = { Text("All · ${state.events.size}") },
                )
                EventSeverity.entries.forEach { severity ->
                    val count = state.events.count { it.severity == severity }
                    FilterChip(
                        selected = state.eventSeverityFilter == severity,
                        onClick = { callbacks.onEventSeverityFilterChanged(severity) },
                        label = { Text("${severity.displayName} · $count") },
                    )
                }
            }
        }
        PanelCard(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                EventTableHeader()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (filteredEvents.isEmpty()) {
                    EmptyEventLog(hasFilters = state.eventSearchQuery.isNotBlank() || state.eventSeverityFilter != null)
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                        items(filteredEvents, key = GateEventUi::id) { event ->
                            EventRow(event)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Time", modifier = Modifier.weight(.16f), style = MaterialTheme.typography.labelMedium)
        Text("State", modifier = Modifier.size(34.dp, 16.dp), style = MaterialTheme.typography.labelMedium)
        Text("Event", modifier = Modifier.weight(.36f), style = MaterialTheme.typography.labelMedium)
        Text("Details", modifier = Modifier.weight(.48f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EmptyEventLog(hasFilters: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.History,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (hasFilters) "No events match the current filters" else "Waiting for controller events",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (hasFilters) {
                    "Change the search or severity filter."
                } else {
                    "Connection, passage, sensor, and safety events will appear here."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun GateEventUi.matches(query: String): Boolean =
    title.contains(query, ignoreCase = true) ||
        detail.contains(query, ignoreCase = true) ||
        category.name.contains(query, ignoreCase = true)

private val EventSeverity.displayName: String
    get() = name.lowercase().replaceFirstChar(Char::uppercase)
