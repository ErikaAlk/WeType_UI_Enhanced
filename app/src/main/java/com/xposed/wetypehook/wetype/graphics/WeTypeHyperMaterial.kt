package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Point
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.xposed.wetypehook.PropertyUtils
import com.xposed.wetypehook.xposed.Log
import java.lang.reflect.Method

/** System View material used by XiaoAI IME 0.2.910's HyperMaterialHelper. */
internal class WeTypeHyperMaterial(private val view: View) {
    private var appliedStyle: Pair<Boolean, Float>? = null
    private var pendingWindowType: Runnable? = null
    private var pendingTintClear: Runnable? = null
    private var shadowView: View? = null
    private var shader: RuntimeShader? = null
    private var shadowGeometry: Triple<Int, Int, WeTypeCornerRadii>? = null
    private var shadowStyle: Pair<Boolean, Float>? = null

    fun apply(isDark: Boolean): Boolean {
        if (!isAvailable(view.context)) {
            clear()
            return false
        }
        val density = view.resources.displayMetrics.density
        val style = isDark to density
        if (appliedStyle == style) return true
        clear()
        return runCatching {
            val api = checkNotNull(api)
            view.setBackgroundColor(if (supportsOffScreenFill) Color.TRANSPARENT else fallbackColor(isDark))
            api.setPassWindowBlurEnabled(view, true)
            if (supportsOffScreenFill) api.call(view, "setMiBlurWinType", 65536)
            // keyboard/frosted, token version 30: background mode 1, view mode 1,
            // blur type 0, radius 40dp. Its shadow and bloom sections are absent.
            api.call(view, "setMixEffectEnabled", false)
            api.call(view, "setMiBackgroundBlurMode", 1)
            api.call(view, "setMiBackgroundBlurRadius", (40f * density + 0.5f).toInt().coerceIn(0, 400))
            api.call(view, "setMiViewBlurMode", 1)
            val colors = if (isDark) {
                intArrayOf(-428838800, -1726737388, 262385602)
            } else {
                intArrayOf(-2130706433, 1728053247, 1722132652)
            }
            val modes = if (isDark) intArrayOf(15, 3, 3) else intArrayOf(121, 3, 3)
            api.call(view, "setMiBackgroundBlendColors", ArrayList(colors.indices.map { Point(colors[it], modes[it]) }))
            api.call(view, "setMiBackgroundBlurType", 0)
            appliedStyle = style
            if (!supportsOffScreenFill) {
                pendingTintClear = Runnable {
                    pendingTintClear = null
                    if (appliedStyle != null) view.setBackgroundColor(Color.TRANSPARENT)
                }.also { view.postDelayed(it, 20L) }
            }
            if (supportsOffScreenFill) {
                pendingWindowType = Runnable {
                    pendingWindowType = null
                    if (appliedStyle != null && view.isAttachedToWindow) {
                        runCatching { api.call(view, "setMiBlurWinType", 1) }
                            .onFailure {
                                clear()
                                view.setBackgroundColor(fallbackColor(isDark))
                                Log.e(it)
                            }
                    }
                }.also { view.postDelayed(it, 500L) }
            }
            true
        }.getOrElse {
            // Never leave a partially configured compositor effect on the carrier.
            clear(force = true)
            Log.e(it)
            false
        }
    }

    fun updateGeometry(cornerRadii: WeTypeCornerRadii) {
        val style = appliedStyle ?: return
        val parent = view.parent as? ViewGroup ?: return
        if (view.width <= 0 || view.height <= 0) return
        runCatching {
            val effectView = shadowView ?: View(view.context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // A sibling outside the clipped frosted panel, as in HyperMaterialHelper.
                parent.addView(this, parent.indexOfChild(view) + 1, FrameLayout.LayoutParams(0, 0))
            }.also { shadowView = it }
            val density = style.second
            val outset = (60f * density + 0.5f).toInt()
            val width = view.width + outset * 2
            val height = view.height + outset * 2
            effectView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            effectView.layout(view.left - outset, view.top - outset, view.right + outset, view.bottom + outset)
            val geometry = Triple(view.width, view.height, cornerRadii)
            if (shadowGeometry != geometry || shadowStyle != style) {
                val shader = shader ?: RuntimeShader(HYPER_MATERIAL_SHADOW_SHADER).also { shader = it }
                val path = createWeTypeContinuousRoundedPath(view.width.toFloat(), view.height.toFloat(), cornerRadii)
                // Start at quarter-pixel precision; bound the uniform array for unusually complex paths.
                var error = 0.25f
                var points = path.approximate(error)
                while (points.size / 3 > 255) {
                    error *= 2f
                    points = path.approximate(error)
                }
                val count = points.size / 3
                val contour = FloatArray(256 * 2)
                for (index in 0 until count) {
                    contour[index * 2] = points[index * 3 + 1]
                    contour[index * 2 + 1] = points[index * 3 + 2]
                }
                contour[count * 2] = contour[0]
                contour[count * 2 + 1] = contour[1]
                shader.setFloatUniform("uContour", contour)
                shader.setIntUniform("uPointCount", count + 1)
                shader.setFloatUniform("uCornerExtent", cornerRadii.maxRadius() * 2f)
                shader.setFloatUniform("uResolution", width.toFloat(), height.toFloat())
                shader.setFloatUniform("uStrokeWidth", density)
                shader.setFloatUniform("uOutset", outset.toFloat())
                val shadowColor = if (style.first) 340281416 else 134217728
                shader.setFloatUniform("uShadowColor", Color.red(shadowColor) / 255f,
                    Color.green(shadowColor) / 255f, Color.blue(shadowColor) / 255f)
                shader.setFloatUniform("uShadowAlpha", Color.alpha(shadowColor) / 255f)
                shader.setFloatUniform("uShadowRadius", minOf((if (style.first) 60f else 40f) * density, outset.toFloat()))
                shader.setFloatUniform("uStrokeAlphaTop", if (style.first) 0.12156863f else 0.59607846f)
                shader.setFloatUniform("uStrokeAlphaBottom", 0.050980393f)
                effectView.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "uInputContent"))
                shadowGeometry = geometry
                shadowStyle = style
            }
            effectView.visibility = view.visibility
        }.onFailure {
            clear()
            view.setBackgroundColor(fallbackColor(style.first))
            Log.e(it)
        }
    }

    fun clear(force: Boolean = false) {
        pendingTintClear?.let(view::removeCallbacks)
        pendingTintClear = null
        shadowView?.let {
            it.setRenderEffect(null)
            (it.parent as? ViewGroup)?.removeView(it)
        }
        shadowView = null
        shadowGeometry = null
        shadowStyle = null
        pendingWindowType?.let(view::removeCallbacks)
        pendingWindowType = null
        if (appliedStyle == null && !force) return
        appliedStyle = null
        val api = api ?: return
        listOf(
            "setMiBackgroundBlurMode" to 0,
            "setMiViewBlurMode" to 0,
            "setMiBackgroundBlurRadius" to 0,
            "setMiBackgroundBlurType" to 0,
            "setMixEffectEnabled" to false
        ).forEach { (name, value) -> runCatching { api.call(view, name, value) } }
        runCatching { api.setPassWindowBlurEnabled(view, false) }
        runCatching { api.call(view, "clearMiBackgroundBlendColor") }
        if (supportsOffScreenFill) runCatching { api.call(view, "setMiBlurWinType", 0) }
        view.invalidate()
    }

    companion object {
        private const val BLUR_SETTING = "background_blur_enable"
        private val osVersion by lazy { PropertyUtils["ro.mi.os.version.code", "0"]?.toIntOrNull() ?: 0 }
        private val visualVersion by lazy {
            val property = if (osVersion > 1) "persist.sys.advanced_visual_release" else "persist.sys.background_blur_version"
            PropertyUtils[property, "-1"]?.toIntOrNull() ?: -1
        }
        private val supportsOffScreenFill get() = visualVersion >= 6
        private val api: MaterialApi? by lazy { runCatching { MaterialApi(supportsOffScreenFill) }.getOrNull() }
        private val shaderSupported by lazy {
            Build.VERSION.SDK_INT >= 33 && runCatching {
                RuntimeShader(HYPER_MATERIAL_SHADOW_SHADER)
                true
            }.getOrDefault(false)
        }
        private val blurSupported by lazy {
            PropertyUtils["persist.sys.background_blur_supported", "false"].toBoolean()
        }

        fun fallbackColor(isDark: Boolean): Int = if (isDark) 0xFF18191B.toInt() else 0xFFE5E6E7.toInt()

        fun isAvailable(context: Context): Boolean = runCatching {
            // The source selects this token's blend colors starting at OS 3. Bionic
            // glass and gradient blur defaults do not gate the frosted keyboard token.
            Build.VERSION.SDK_INT >= 33 && osVersion >= 3 && blurSupported &&
                Settings.Secure.getInt(context.contentResolver, BLUR_SETTING, 0) == 1 &&
                api != null && shaderSupported
        }.getOrDefault(false)

        fun observeAvailability(context: Context, onChanged: () -> Unit): () -> Unit {
            val resolver = context.contentResolver
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onChanged()
            }
            resolver.registerContentObserver(Settings.Secure.getUriFor(BLUR_SETTING), false, observer)
            return { resolver.unregisterContentObserver(observer) }
        }
    }

    private class MaterialApi(offScreenFill: Boolean) {
        private val methods = buildMap<String, Method> {
            listOf("setMiBackgroundBlurMode", "setMiBackgroundBlurRadius", "setMiViewBlurMode", "setMiBackgroundBlurType")
                .forEach { put(it, View::class.java.getMethod(it, Int::class.javaPrimitiveType)) }
            listOf("setPassWindowBlurEnabled", "setMixEffectEnabled")
                .forEach { put(it, View::class.java.getMethod(it, Boolean::class.javaPrimitiveType)) }
            put("setMiBackgroundBlendColors", View::class.java.getMethod("setMiBackgroundBlendColors", ArrayList::class.java))
            put("clearMiBackgroundBlendColor", View::class.java.getMethod("clearMiBackgroundBlendColor"))
            if (offScreenFill) put("setMiBlurWinType", View::class.java.getMethod("setMiBlurWinType", Int::class.javaPrimitiveType))
        }

        fun setPassWindowBlurEnabled(view: View, enabled: Boolean) {
            val root = View::class.java.getMethod("getViewRootImpl").invoke(view)
            checkNotNull(root) { "Material carrier must be attached before enabling pass-window blur" }
            val filterField = runCatching {
                root.javaClass.getDeclaredField("mPassWindowBlurFilterData").apply { isAccessible = true }
            }.getOrNull()
            val originalFilter = filterField?.get(root) as? String
            val packageName = view.context.packageName
            // HyperOS rejects third-party pass-window sampling without throwing. Only
            // admit this carrier's call on its own ViewRoot, then restore the ROM list.
            // Never modify the static filter switch, system properties or cloud data.
            val needsAdmission = !originalFilter.isNullOrEmpty() && !originalFilter.contains(packageName)
            try {
                if (needsAdmission) filterField?.set(root, "$originalFilter $packageName")
                call(view, "setPassWindowBlurEnabled", enabled)
                // The boolean return also means "already set", so inspect actual state.
                val state = View::class.java.getDeclaredField("mNeedPassWindowBlur").apply { isAccessible = true }
                check(state.getBoolean(view) == enabled) { "System rejected pass-window blur state" }
            } finally {
                if (needsAdmission) filterField?.set(root, originalFilter)
            }
        }

        fun call(view: View, name: String, vararg args: Any) {
            methods.getValue(name).invoke(view, *args)
        }
    }
}
