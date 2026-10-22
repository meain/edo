package com.edo.app.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Press-and-drag reordering for a vertical list of keyed rows. The list keeps its own order and
 * hands over the moves: [onMove] fires each time the dragged row passes a neighbour, and
 * [onDrop] once the finger lifts, so the caller can show the order live and save it at the end.
 *
 * Rows report their height through [heightOf] (pixels, including any gap to the next row); the
 * dragged row follows the finger through [offset] and swaps places once its centre passes the
 * neighbour's.
 */
class DragReorderState<K : Any>(
    private val keys: () -> List<K>,
    private val heightOf: (K) -> Float,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: () -> Unit,
) {
    var dragging by mutableStateOf<K?>(null)
        private set

    /** How far the dragged row sits from where its slot currently is, in pixels. */
    var offset by mutableFloatStateOf(0f)
        private set

    internal fun start(key: K) {
        dragging = key
        offset = 0f
    }

    internal fun drag(dy: Float) {
        val key = dragging ?: return
        offset += dy
        while (true) {
            val list = keys()
            val i = list.indexOf(key)
            if (i < 0) return
            val h = heightOf(key)
            val next = list.getOrNull(i + 1)
            val prev = list.getOrNull(i - 1)
            when {
                next != null && offset > (h + heightOf(next)) / 2 -> {
                    offset -= heightOf(next)
                    onMove(i, i + 1)
                }
                prev != null && offset < -(h + heightOf(prev)) / 2 -> {
                    offset += heightOf(prev)
                    onMove(i, i - 1)
                }
                else -> return
            }
        }
    }

    internal fun end() {
        if (dragging == null) return
        dragging = null
        offset = 0f
        onDrop()
    }

    /** Lifts the row for [key] under the finger while it is being dragged. */
    fun Modifier.dragged(key: K, shape: Shape = RectangleShape): Modifier =
        if (dragging == key) zIndex(1f).graphicsLayer { translationY = offset }.shadow(8.dp, shape)
        else this

    /** Starts the drag on long-press anywhere on the row. */
    fun Modifier.dragHandle(key: K, haptics: HapticFeedback): Modifier = pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                start(key)
            },
            onDrag = { change, amount ->
                change.consume()
                drag(amount.y)
            },
            onDragEnd = { end() },
            onDragCancel = { end() },
        )
    }
}
