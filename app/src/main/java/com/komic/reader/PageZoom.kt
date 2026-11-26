package com.komic.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize

internal data class PageTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun change(factor: Float, focus: Offset, pan: Offset, bounds: IntSize): PageTransform {
        val next = (scale * factor).coerceIn(1f, 4f)
        if (next == 1f) return PageTransform()
        val translated = focus + (offset - focus) * (next / scale) + pan
        return PageTransform(next, Offset(
            translated.x.coerceIn(bounds.width * (1f - next), 0f),
            translated.y.coerceIn(bounds.height * (1f - next), 0f)
        ))
    }
}

// Track the perpendicular component even while the list/pager is handling a swipe.
internal fun Modifier.readerGestures(
    vertical: Boolean,
    scale: () -> Float,
    change: (factor: Float, focus: Offset, pan: Offset) -> Unit,
    onCenterTap: () -> Unit
): Modifier = pointerInput(change) {
    detectTapGestures(onDoubleTap = { focus ->
        val current = scale()
        change(if (current > 1f) 1f / current else 2f, focus, Offset.Zero)
    }, onTap = { point ->
        if (point.x in size.width / 3f..size.width * 2f / 3f &&
            point.y in size.height / 3f..size.height * 2f / 3f) onCenterTap()
    })
}.pointerInput(vertical, change) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinching = false
        var motion = Offset.Zero
        var dragging = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val count = event.changes.count { it.pressed }
            if (count == 0 && (dragging || pinching)) event.changes.forEach { it.consume() }
            if (count >= 2) pinching = true
            val focus = event.calculateCentroid(useCurrent = false)
            if (count > 0 && focus != Offset.Unspecified) {
                val pan = event.calculatePan()
                motion += pan
                if (pinching) {
                    change(event.calculateZoom(), focus, pan)
                    event.changes.forEach { it.consume() }
                } else if (scale() > 1f) {
                    val wasDragging = dragging
                    dragging = dragging || motion.getDistance() > viewConfiguration.touchSlop
                    if (dragging) {
                        val delta = if (wasDragging) pan else motion
                        val across = if (vertical) Offset(delta.x, 0f) else Offset(0f, delta.y)
                        if (across != Offset.Zero) change(1f, focus, across)
                        // Leave this event available to native scrolling along the reading axis.
                    }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** Consume only the distance available inside the magnified page.
 * Nested scrolling passes the remaining motion on to the pager at the edge. */
internal fun PageTransform.panAlongPage(delta: Offset, vertical: Boolean, bounds: IntSize): PageTransform {
    val along = if (vertical) Offset(0f, delta.y) else Offset(delta.x, 0f)
    return change(1f, Offset.Zero, along, bounds)
}
