package com.edo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class PanelSide { Left, Right }

/**
 * Position of the two edge panels. [offset] is in px: > 0 means the left panel is sliding in
 * (0 = closed, [width] = fully open), < 0 the right one. Dragging sets it directly so the panel
 * tracks the finger; releasing animates to the nearest end.
 */
class SwipePanelsState internal constructor(private val scope: CoroutineScope) {
    var offset by mutableFloatStateOf(0f)
        internal set
    internal var width by mutableFloatStateOf(0f)
    private var anim: Job? = null

    internal fun cancelAnimation() { anim?.cancel() }

    internal fun animateTo(target: Float, velocity: Float = 0f) {
        anim?.cancel()
        anim = scope.launch {
            animate(
                initialValue = offset,
                targetValue = target,
                initialVelocity = velocity,
                // A spring carries the finger's release velocity into the settle; a tween starts
                // from rest and visibly jerks.
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f),
            ) { v, _ -> offset = v }
        }
    }

    fun open(side: PanelSide) = animateTo(if (side == PanelSide.Left) width else -width)
    fun close() = animateTo(0f)
}

@Composable
fun rememberSwipePanelsState(): SwipePanelsState {
    val scope = rememberCoroutineScope()
    return remember { SwipePanelsState(scope) }
}

/**
 * [content] with a full-width panel on each edge. A horizontal drag on anything that doesn't
 * itself consume it (code blocks, lists) pulls the matching panel in under the finger.
 */
@Composable
fun SwipePanels(
    state: SwipePanelsState,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
    rightEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val leftVisible by remember { derivedStateOf { state.offset > 0f } }
    val rightVisible by remember { derivedStateOf { state.offset < 0f } }
    val anyOpen = leftVisible || rightVisible
    BackHandler(enabled = anyOpen) { state.close() }

    val dragState = rememberDraggableState { delta ->
        val w = state.width
        state.offset = (state.offset + delta).coerceIn(if (rightEnabled) -w else 0f, w)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { state.width = it.width.toFloat() }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { state.cancelAnimation() },
                onDragStopped = { velocity ->
                    val w = state.width
                    val x = state.offset
                    val target = when {
                        x > 0f -> if (velocity > FLING || (x > w / 2 && velocity > -FLING)) w else 0f
                        x < 0f -> if (velocity < -FLING || (-x > w / 2 && velocity < FLING)) -w else 0f
                        else -> 0f
                    }
                    state.animateTo(target, velocity)
                },
            ),
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = state.offset * PARALLAX }) { content() }

        if (anyOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val frac = (abs(state.offset) / size.width).coerceIn(0f, 1f)
                        drawRect(Color.Black.copy(alpha = SCRIM * frac))
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { state.close() },
            )
        }
        // The left panel stays composed (offscreen when closed) so the first frame of a drag
        // doesn't stall on composing it; it's cheap and backed by a live query.
        Surface(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = if (state.offset > 0f) state.offset - state.width else -state.width
            },
            color = MaterialTheme.colorScheme.background,
        ) { left() }
        if (rightVisible) {
            Surface(
                Modifier.fillMaxSize().graphicsLayer { translationX = state.width + state.offset },
                color = MaterialTheme.colorScheme.background,
            ) { right() }
        }
    }
}

private const val FLING = 700f
private const val PARALLAX = 0.2f
private const val SCRIM = 0.32f
