package com.komic.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransformTest {
    @Test fun diagonalPanningConsumesBothComponentsAndHandsOverflowToPager() {
        val size = IntSize(300, 600)
        val start = PageTransform(2f, Offset(-60f, -30f))
        val diagonal = Offset(12f, 45f)
        val cross = start.change(1f, Offset.Zero, Offset(diagonal.x, 0f), size)
        val end = cross.panAlongPage(diagonal, true, size)
        assertEquals(Offset(-48f, 0f), end.offset)
        assertEquals(15f, diagonal.y - (end.offset.y - cross.offset.y), 0f)
        val horizontal = start.change(1f, Offset.Zero, Offset(0f, diagonal.y), size)
            .panAlongPage(diagonal, false, size)
        assertEquals(end.offset, horizontal.offset)
    }

    @Test fun focalPointStaysUnderFingersWhileZoomingAndMoving() {
        val bounds = IntSize(400, 800)
        val focus = Offset(100f, 200f)
        val zoom = PageTransform().change(2f, focus, Offset.Zero, bounds)
        assertEquals(focus, focus * zoom.scale + zoom.offset)
        val pan = Offset(10f, -15f)
        val moved = zoom.change(1.5f, focus, pan, bounds)
        assertEquals(focus + pan, focus * moved.scale + moved.offset)
    }

    @Test fun scaleAndPanStayInBoundsInBothOrientations() {
        for (size in listOf(IntSize(400, 800), IntSize(800, 400))) {
            val zoom = PageTransform().change(10f, Offset.Zero, Offset(-9999f, -9999f), size)
            assertEquals(4f, zoom.scale, 0f)
            assertEquals(Offset(-3f * size.width, -3f * size.height), zoom.offset)
            assertEquals(PageTransform(), zoom.change(.1f, Offset.Zero, Offset.Zero, size))
        }
    }
}
