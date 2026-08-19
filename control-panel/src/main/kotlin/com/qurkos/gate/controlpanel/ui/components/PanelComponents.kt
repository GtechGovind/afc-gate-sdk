package com.qurkos.gate.controlpanel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.qurkos.gate.controlpanel.ui.model.EventSeverity
import com.qurkos.gate.controlpanel.ui.model.GateEventUi

/** Standard bordered section card for dense control-panel content. */
@Composable
internal fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp),
    ) {
        content()
    }
}

/** Page heading with optional controls aligned to the trailing edge. */
@Composable
internal fun PageTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

/** Compact state capsule used for connection and sensor summaries. */
@Composable
internal fun StatusBadge(
    label: String,
    positive: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (positive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
    Row(
        modifier =
            modifier
                .border(1.dp, color.copy(alpha = .55f), RoundedCornerShape(50))
                .background(color.copy(alpha = .12f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** One consistently formatted operation or audit event. */
@Composable
internal fun EventRow(
    event: GateEventUi,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(event.timestamp, modifier = Modifier.weight(.16f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val (icon, tint) = event.severity.visual
        Icon(icon, contentDescription = event.severity.name, tint = tint, modifier = Modifier.size(20.dp))
        Text(event.title, modifier = Modifier.weight(.36f))
        Text(event.detail, modifier = Modifier.weight(.48f), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val EventSeverity.visual: Pair<ImageVector, Color>
    @Composable get() =
        when (this) {
            EventSeverity.INFO -> Icons.Outlined.Info to MaterialTheme.colorScheme.primary
            EventSeverity.SUCCESS -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.secondary
            EventSeverity.WARNING -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.tertiary
            EventSeverity.ERROR -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        }
