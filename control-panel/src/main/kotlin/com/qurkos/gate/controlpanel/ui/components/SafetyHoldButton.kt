package com.qurkos.gate.controlpanel.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Protected action surface that delegates hold timing and authorization to the state owner. */
@Composable
internal fun SafetyHoldButton(
    label: String,
    holdProgress: Float,
    enabled: Boolean,
    onHoldCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnHoldCompleted by rememberUpdatedState(onHoldCompleted)
    val animatedProgress by animateFloatAsState(
        targetValue = if (isPressed) 1f else holdProgress,
        animationSpec = tween(durationMillis = if (isPressed) HOLD_DURATION_MILLIS else RELEASE_DURATION_MILLIS),
        label = "safety-hold-progress",
    )
    LaunchedEffect(isPressed) {
        if (isPressed && enabled) {
            delay(HOLD_DURATION_MILLIS.toLong())
            currentOnHoldCompleted()
            isPressed = false
        }
    }
    val shape = RoundedCornerShape(8.dp)
    val emphasisColor = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(74.dp)
                .clip(shape)
                .background(emphasisColor.copy(alpha = if (enabled) .08f else .04f))
                .border(if (enabled) 2.dp else 1.dp, emphasisColor.copy(alpha = if (enabled) .75f else .35f), shape)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                    )
                }.semantics {
                    role = Role.Button
                    contentDescription =
                        if (enabled) "$label. Press and hold for three seconds" else "$label. Disabled until connected"
                },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress)
                .background(MaterialTheme.colorScheme.error.copy(alpha = .22f)),
        )
        Row(
            modifier = Modifier.matchParentSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.PanTool,
                null,
                tint = emphasisColor.copy(alpha = if (enabled) 1f else .72f),
            )
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(
                    label,
                    color = emphasisColor.copy(alpha = if (enabled) 1f else .72f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    if (enabled) "Press and hold for 3 seconds" else "Connect physical gate to enable",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

private const val HOLD_DURATION_MILLIS = 3_000
private const val RELEASE_DURATION_MILLIS = 150
