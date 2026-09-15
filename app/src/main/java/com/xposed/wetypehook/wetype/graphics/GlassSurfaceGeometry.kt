package com.xposed.wetypehook.wetype.graphics

import kotlin.math.ceil

internal data class GlassSurfaceGeometry(
    val width: Int,
    val height: Int,
    val outset: Int,
    val radius: Float,
    val maxEdgeDepth: Float
)

/** One optical rectangle; the parent alone clips the visible keyboard outline. */
internal fun resolveGlassSurfaceGeometry(
    width: Int,
    height: Int,
    corners: WeTypeCornerRadii,
    touchesScreenEdges: Boolean
): GlassSurfaceGeometry {
    require(width > 0 && height > 0)
    val limit = (minOf(width, height) / 2f).coerceAtLeast(1f)
    val radius = maxOf(corners.topLeft, corners.topRight).coerceIn(1f, limit)
    // Native uNmlZAAThreshold = smooth5_vertical(3 / edgeDepth, 0.5), not dp.
    val outset = if (touchesScreenEdges) 3 else 0
    // Keep optical rounding outside the preview's square bottom clip.
    val bottomExtension = if (corners.bottomLeft == 0f && corners.bottomRight == 0f) ceil(radius).toInt() else 0
    return GlassSurfaceGeometry(
        width = width + outset * 2,
        height = height + bottomExtension + outset,
        outset = outset,
        radius = radius,
        maxEdgeDepth = (radius - 3f).coerceAtLeast(0.1f)
    )
}
