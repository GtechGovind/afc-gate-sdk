package com.qurkos.gate.controlpanel.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qurkos.afc.control_panel.generated.resources.Res
import com.qurkos.afc.control_panel.generated.resources.gate_closed
import com.qurkos.afc.control_panel.generated.resources.gate_disconnected
import com.qurkos.afc.control_panel.generated.resources.gate_emergency_release
import com.qurkos.afc.control_panel.generated.resources.gate_open
import com.qurkos.gate.controlpanel.ui.model.ConnectionHealth
import com.qurkos.gate.controlpanel.ui.model.FlapPosition
import com.qurkos.gate.controlpanel.ui.model.GateTwinUiState
import org.jetbrains.compose.resources.painterResource

/**
 * Animated visual twin reserved exclusively for confirmed physical gate behavior.
 *
 * The artwork blends closed, open, and emergency assets and can show an authorized passenger
 * moving through the lane. Sensors, RTC, counters, power, firmware, and diagnostic data are kept
 * in dedicated panels outside this image so operational information never obscures the gate.
 */
@Composable
fun GateTwin(
    state: GateTwinUiState,
    modifier: Modifier = Modifier,
) {
    val targetProgress = (state.leftFlapProgress + state.rightFlapProgress) / 2f
    val openProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(if (state.reducedMotion) 1 else 240),
        label = "gate-flap-progress",
    )
    val emergencyAlpha by animateFloatAsState(
        targetValue = if (state.emergencyActive && state.connectionHealth == ConnectionHealth.CONNECTED) 1f else 0f,
        animationSpec = tween(if (state.reducedMotion) 1 else 180),
        label = "gate-emergency-state",
    )
    val connectedAlpha by animateFloatAsState(
        targetValue = if (state.connectionHealth == ConnectionHealth.CONNECTED) 1f else 0f,
        animationSpec = tween(if (state.reducedMotion) 1 else 180),
        label = "gate-connection-state",
    )

    BoxWithConstraints(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background)
                .semantics {
                    contentDescription = "Gate twin, flaps ${state.leftFlap.name.lowercase()}"
                },
    ) {
        val artworkWidth =
            if (maxWidth / maxHeight > GATE_ARTWORK_ASPECT_RATIO) {
                maxHeight * GATE_ARTWORK_ASPECT_RATIO
            } else {
                maxWidth
            }
        val artworkHeight = artworkWidth / GATE_ARTWORK_ASPECT_RATIO
        GateArtwork(
            state = state,
            artworkWidth = artworkWidth,
            artworkHeight = artworkHeight,
            openProgress = openProgress,
            emergencyAlpha = emergencyAlpha,
            connectedAlpha = connectedAlpha,
            modifier = Modifier.align(Alignment.Center),
        )
        GateStateBadge(state, Modifier.align(Alignment.TopStart).padding(18.dp))
    }
}

@Composable
private fun GateArtwork(
    state: GateTwinUiState,
    artworkWidth: Dp,
    artworkHeight: Dp,
    openProgress: Float,
    emergencyAlpha: Float,
    connectedAlpha: Float,
    modifier: Modifier,
) {
    Box(modifier.size(artworkWidth, artworkHeight)) {
        Image(
            painter = painterResource(Res.drawable.gate_disconnected),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = painterResource(Res.drawable.gate_closed),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer(alpha = connectedAlpha * (1f - openProgress)),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = painterResource(Res.drawable.gate_open),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer(alpha = connectedAlpha * openProgress * (1f - emergencyAlpha)),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = painterResource(Res.drawable.gate_emergency_release),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer(alpha = emergencyAlpha),
            contentScale = ContentScale.FillBounds,
        )
        if (state.connectionHealth == ConnectionHealth.CONNECTED) {
            state.passengerProgress?.let { PassengerMarker(it, artworkWidth, artworkHeight) }
        }
    }
}

@Composable
private fun PassengerMarker(
    progress: Float,
    artworkWidth: Dp,
    artworkHeight: Dp,
) {
    val x = artworkWidth * (.50f + (progress - .5f) * .08f)
    val y = artworkHeight * (.75f - progress * .40f)
    Surface(
        modifier = Modifier.offset(x - 22.dp, y - 22.dp).size(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 8.dp,
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = "Authorized passenger",
            modifier = Modifier.padding(9.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun GateStateBadge(
    state: GateTwinUiState,
    modifier: Modifier,
) {
    val (title, accent) =
        when {
            state.connectionHealth == ConnectionHealth.DISCONNECTED -> "GATE DISCONNECTED" to MaterialTheme.colorScheme.error
            state.connectionHealth == ConnectionHealth.CONNECTING -> "VERIFYING GCU" to MaterialTheme.colorScheme.tertiary
            state.connectionHealth == ConnectionHealth.DEGRADED -> "CONNECTION FAILED" to MaterialTheme.colorScheme.error
            state.emergencyActive -> "EMERGENCY RELEASE" to MaterialTheme.colorScheme.primary
            else -> "GCU CONNECTED" to MaterialTheme.colorScheme.secondary
        }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
        shape = RoundedCornerShape(7.dp),
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                accent,
            ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
            Text(
                if (state.connectionHealth == ConnectionHealth.CONNECTED) {
                    "${state.leftFlap.displayName()} · ${state.lamp.name.lowercase().replaceFirstChar(Char::uppercase)} indicator"
                } else {
                    "Commands unavailable"
                },
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

private fun FlapPosition.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

private const val GATE_ARTWORK_ASPECT_RATIO = 1.5f
