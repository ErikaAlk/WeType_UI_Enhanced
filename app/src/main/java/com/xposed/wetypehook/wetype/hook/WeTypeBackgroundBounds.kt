package com.xposed.wetypehook.wetype.hook

internal data class WeTypeBackgroundLayout(
    val windowTop: Int,
    val height: Int,
    val isShown: Boolean,
    val isLaidOut: Boolean,
    val isLayoutRequested: Boolean
)

internal data class WeTypeBackgroundBounds(val top: Int, val height: Int)

/** Returns decor-local bounds only after all visible content has finished layout. */
internal fun resolveWeTypeBackgroundBounds(
    decor: WeTypeBackgroundLayout,
    contents: List<WeTypeBackgroundLayout>
): WeTypeBackgroundBounds? {
    if (!decor.isShown || !decor.isLaidOut || decor.isLayoutRequested || decor.height <= 0) return null
    var top = decor.height
    for (content in contents) {
        if (!content.isShown) continue
        if (!content.isLaidOut || content.isLayoutRequested) return null
        if (content.height <= 0) continue
        val relativeTop = content.windowTop - decor.windowTop
        if (relativeTop >= 0 && relativeTop < top) top = relativeTop
    }
    if (top == decor.height) return null
    return WeTypeBackgroundBounds(top, decor.height - top)
}
