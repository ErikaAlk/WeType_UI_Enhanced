package com.xposed.wetypehook.wetype.graphics

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassSurfaceGeometryTest {
    @Test
    fun hardwareBottomRadiusCannotSplitOrResizeTheOpticalSurface() {
        val first = resolveGlassSurfaceGeometry(1080, 800, WeTypeCornerRadii(48f, 48f, 120f, 120f), true)
        val second = resolveGlassSurfaceGeometry(1080, 800, WeTypeCornerRadii(48f, 48f, 160f, 100f), true)
        assertEquals(first, second)
        assertEquals(48f, first.radius)
        assertEquals(45f, first.maxEdgeDepth)
        assertEquals(1086, first.width)
        assertEquals(803, first.height)
        assertEquals(3, first.outset)
    }

    @Test
    fun changingTheTopRadiusUpdatesOpticsWithoutMovingTheSurface() {
        val first = resolveGlassSurfaceGeometry(1080, 800, WeTypeCornerRadii(48f, 48f, 120f, 120f), true)
        val second = resolveGlassSurfaceGeometry(1080, 800, WeTypeCornerRadii(64f, 64f, 120f, 120f), true)
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        assertEquals(first.outset, second.outset)
        assertEquals(64f, second.radius)
    }

    @Test
    fun squarePreviewBottomKeepsItsExistingExtension() {
        assertEquals(
            GlassSurfaceGeometry(320, 212, 0, 32f, 29f),
            resolveGlassSurfaceGeometry(320, 180, WeTypeCornerRadii(32f, 32f, 0f, 0f), false)
        )
    }

    @Test
    fun extremeTopRadiiStayWithinTheNativeNormalCache() {
        val small = resolveGlassSurfaceGeometry(100, 80, WeTypeCornerRadii(0f, 0f, 40f, 40f), true)
        val large = resolveGlassSurfaceGeometry(100, 80, WeTypeCornerRadii(500f, 500f, 40f, 40f), true)
        assertEquals(1f, small.radius)
        assertEquals(0.1f, small.maxEdgeDepth)
        assertEquals(40f, large.radius)
        assertEquals(37f, large.maxEdgeDepth)
    }
}
