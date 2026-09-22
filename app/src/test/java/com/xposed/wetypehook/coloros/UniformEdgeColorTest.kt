package com.xposed.wetypehook.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UniformEdgeColorTest {
    private val bar = 0xFFF7F7F7.toInt()

    @Test
    fun flatBarKeepsItsColor() {
        assertEquals(bar, uniformEdgeColor(IntArray(360) { bar }))
    }

    @Test
    fun iconEdgesDoNotTintTheBar() {
        val row = IntArray(360) { if (it % 20 == 0) 0xFF202020.toInt() else bar }
        assertEquals(bar, uniformEdgeColor(row))
    }

    @Test
    fun slightNoiseAveragesOut() {
        val row = IntArray(360) { if (it % 2 == 0) 0xFFF5F5F5.toInt() else 0xFFF9F9F9.toInt() }
        assertEquals(bar, uniformEdgeColor(row))
    }

    @Test
    fun meanRoundsToTheNearestValue() {
        // Captures of a #1A1C1E bar come back a unit darker here and there.
        val row = IntArray(360) { if (it % 10 == 0) 0xFF191B1D.toInt() else 0xFF1A1C1E.toInt() }
        assertEquals(0xFF1A1C1E.toInt(), uniformEdgeColor(row))
    }

    @Test
    fun contentThatIsNotABarIsRejected() {
        val row = IntArray(360) { if (it < 250) bar else 0xFF3A6EA5.toInt() }
        assertNull(uniformEdgeColor(row))
        assertNull(uniformEdgeColor(IntArray(0)))
    }

    @Test
    fun sampledRowSitsJustAboveTheKeyboard() {
        // PJZ110: keyboard top 2037 px, capture 360x792 at scale 0.25.
        assertEquals(507, edgeRow(2037, 0.25f, 0, 792))
        assertNull(edgeRow(4, 0.25f, 0, 792))
        assertNull(edgeRow(4000, 0.25f, 0, 792))
        assertNull(edgeRow(2037, 0.25f, 1, 792))
        assertNull(edgeRow(-1, 0.25f, 0, 792))
    }
}
