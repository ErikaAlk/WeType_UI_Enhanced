package com.xposed.wetypehook.coloros

import android.annotation.SuppressLint
import android.app.Application
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.View
import com.xposed.wetypehook.PropertyUtils
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.findMethod
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.invokeMethodAs
import java.util.concurrent.atomic.AtomicReference

private const val SYSTEMUI_PACKAGE = "com.android.systemui"
private const val WETYPE_PACKAGE = "com.tencent.wetype"
private const val BLUR_SERVICE_PACKAGE = "com.oplus.blur"
private const val BLUR_SERVICE_ACTION = "com.oplus.BlurService"
private const val ACTION_REQUEST_BINDER = "com.xposed.wetypehook.coloros.REQUEST_BLUR_SERVICE"
private const val ACTION_BINDER = "com.xposed.wetypehook.coloros.BLUR_SERVICE"
private const val EXTRA_BINDER = "binder"
private const val SERVICE_DESCRIPTOR = "com.oplus.blur.session.IBlurService"
private const val CONNECTION_DESCRIPTOR = "com.oplus.blur.session.IBlurConnection"
private const val TXN_ADD_BLUR_DRAWABLE = 1
private const val TXN_REMOVE_BLUR_DRAWABLE = 2
private const val CB_DRAWABLES_REMOVED = 2
private const val CB_BLUR_READY = 4
private const val KEY_BUFFER = "on_blur_ready_blur_buffer"
private const val KEY_ROTATION = "on_blur_ready_buffer_rotation"
private const val KEY_SCALE = "on_blur_ready_buffer_scale"
private const val DRAWABLE_ID = 1

// 48 ms minimum between captures. BlurService only captures when layers change, so a still
// screen costs nothing.
private const val CAPTURE_FREQUENCY = 6
private const val MIN_FRAME_INTERVAL_MS = 150L

// BlurService serves one "current" window at a time and our IME window outranks the launcher's,
// so the capture is only held this long after the keyboard shows or its top moves.
private const val SAMPLE_WINDOW_MS = 1000L
private const val BINDER_REQUEST_INTERVAL_MS = 1000L

// Buffer rows between the keyboard top and the sampled row. At scale 0.25 one row is 4 px, so
// two rows keep downscale filtering from mixing in what is under the keyboard.
private const val EDGE_ROW_OFFSET = 2
private const val EDGE_CHANNEL_TOLERANCE = 12
private const val EDGE_MIN_UNIFORM_FRACTION = 0.9f

// Captures of a still bar differ by a unit or so; redrawing for that would be pure churn.
private const val COLOR_CHANGE_THRESHOLD = 2

/**
 * Immersive keyboard background on ColorOS: the color of the app surface right above the
 * keyboard, read from com.oplus.blur's capture of what lies behind the IME window.
 *
 * Binding com.oplus.blur needs a signature permission that SystemUI holds, so SystemUI binds it
 * and hands the binder to WeType only (explicit package, identity-shared broadcast that WeType
 * checks). The binder can capture below any layer its holder names, so it never goes anywhere
 * else. WeType registers its own window through IBlurService txn 1; every capture that comes
 * back is reduced in memory to one color and closed. Nothing is stored or sent.
 */
// Every entry point returns early unless isSupported, which requires API 34.
@SuppressLint("NewApi")
object ColorOsBackdropColor {
    val isSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !PropertyUtils["ro.build.version.oplusrom", ""].isNullOrEmpty()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- SystemUI side ----

    private var bridgeContext: Context? = null
    private var bridgeConnection: ServiceConnection? = null
    private var bridgeRequestReceiver: BroadcastReceiver? = null

    @Volatile
    private var bridgeBinder: IBinder? = null

    fun installSystemUi() {
        if (!isSupported) return
        val current = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
        if (current != null) {
            startBridge(current)
            return
        }
        findMethod("android.app.Instrumentation") {
            name == "callApplicationOnCreate" && parameterTypes.size == 1
        }.hookAfter { param ->
            (param.args[0] as? Application)?.let(::startBridge)
        }
    }

    private fun startBridge(context: Context) = runCatching {
        if (bridgeContext != null) return@runCatching
        bridgeContext = context
        bindBlurService(context)
        bridgeRequestReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // Anyone may ask; the answer only ever goes to WeType.
                bridgeBinder?.let { sendBinderToWeType(context, it) }
            }
        }.also {
            context.registerReceiver(it, IntentFilter(ACTION_REQUEST_BINDER), Context.RECEIVER_EXPORTED)
        }
        Log.i("Success: ColorOS BlurService bridge started")
    }.onFailure {
        Log.e("Failed: Start ColorOS BlurService bridge")
        Log.i(it)
    }

    private fun bindBlurService(context: Context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                bridgeBinder = service
                sendBinderToWeType(context, service)
            }

            // The system reconnects on its own; WeType drops its copy through linkToDeath.
            override fun onServiceDisconnected(name: ComponentName) {
                bridgeBinder = null
            }

            override fun onBindingDied(name: ComponentName) {
                bridgeBinder = null
                if (bridgeConnection !== this) return
                runCatching { context.unbindService(this) }
                bindBlurService(context)
            }
        }
        bridgeConnection = connection
        val intent = Intent(BLUR_SERVICE_ACTION).setPackage(BLUR_SERVICE_PACKAGE)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            Log.e("Failed: Bind ColorOS BlurService from SystemUI")
        }
    }

    private fun sendBinderToWeType(context: Context, binder: IBinder) {
        val intent = Intent(ACTION_BINDER)
            .setPackage(WETYPE_PACKAGE)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtras(Bundle().apply { putBinder(EXTRA_BINDER, binder) })
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        runCatching { context.sendBroadcast(intent, null, options) }.onFailure(Log::i)
    }

    // ---- WeType side (main thread unless noted) ----

    private var receiverContext: Context? = null
    private var binderReceiver: BroadcastReceiver? = null
    private var lastBinderRequestAt = 0L
    private var registered = false

    // Keyboard top the current color was sampled for; -1 asks for a new sample.
    private var sampledTopPx = -1
    private var onColorChanged: (() -> Unit)? = null
    private var color: Int? = null
    private val releaseCapture = Runnable { unregister() }

    @Volatile
    private var worker: Handler? = null

    @Volatile
    private var blurService: IBinder? = null

    // -1 once stopped, so frames still in flight are dropped.
    @Volatile
    private var sampleTopPx = -1

    @Volatile
    private var lastFrameProcessedAt = 0L

    private class Frame(val buffer: HardwareBuffer, val scale: Float, val rotation: Int)

    private val pendingFrame = AtomicReference<Frame?>()

    private val deathRecipient = IBinder.DeathRecipient {
        blurService = null
        mainHandler.post {
            registered = false
            sampledTopPx = -1
            onColorChanged?.invoke()
        }
    }

    private val connection = object : Binder() {
        init {
            attachInterface(null, CONNECTION_DESCRIPTOR)
        }

        // Binder thread.
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                CB_BLUR_READY -> {
                    data.enforceInterface(CONNECTION_DESCRIPTOR)
                    val bundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                    runCatching { bundle?.let(::onFrame) }.onFailure(Log::i)
                    return true
                }

                CB_DRAWABLES_REMOVED -> {
                    data.enforceInterface(CONNECTION_DESCRIPTOR)
                    mainHandler.post { registered = false }
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /**
     * Returns the latest uniform color right above [keyboardTopOnScreen], or null while there is
     * none. Each new keyboard top is sampled by registering [view]'s window with BlurService for
     * SAMPLE_WINDOW_MS. [onChanged] runs on the main thread when the color changes or a sample
     * has to be taken again.
     */
    fun track(view: View, keyboardTopOnScreen: Int, onChanged: () -> Unit): Int? {
        if (!isSupported) return null
        sampleTopPx = keyboardTopOnScreen
        onColorChanged = onChanged
        if (keyboardTopOnScreen == sampledTopPx) return color
        val service = blurService?.takeIf { it.isBinderAlive } ?: run {
            requestBinder(view.context.applicationContext ?: view.context)
            return color
        }
        if (!registered) registered = register(service, view)
        if (registered) {
            sampledTopPx = keyboardTopOnScreen
            mainHandler.removeCallbacks(releaseCapture)
            mainHandler.postDelayed(releaseCapture, SAMPLE_WINDOW_MS)
        }
        return color
    }

    fun stop() {
        onColorChanged = null
        color = null
        sampleTopPx = -1
        sampledTopPx = -1
        pendingFrame.getAndSet(null)?.buffer?.close()
        unregister()
    }

    private fun unregister() {
        mainHandler.removeCallbacks(releaseCapture)
        if (!registered) return
        registered = false
        val service = blurService ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeStrongBinder(connection)
            data.writeInt(DRAWABLE_ID)
            service.transact(TXN_REMOVE_BLUR_DRAWABLE, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: Exception) {
            Log.i(error)
        } finally {
            data.recycle()
        }
    }

    fun prepareForHotReload() {
        stop()
        receiverContext?.let { context -> binderReceiver?.let { runCatching { context.unregisterReceiver(it) } } }
        receiverContext = null
        binderReceiver = null
        blurService?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
        blurService = null
        worker?.looper?.quitSafely()
        worker = null

        bridgeContext?.let { context ->
            bridgeRequestReceiver?.let { runCatching { context.unregisterReceiver(it) } }
            bridgeConnection?.let { runCatching { context.unbindService(it) } }
        }
        bridgeContext = null
        bridgeConnection = null
        bridgeRequestReceiver = null
        bridgeBinder = null
    }

    private fun requestBinder(context: Context) {
        if (binderReceiver == null) {
            binderReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (sentFromPackage != SYSTEMUI_PACKAGE) return
                    val binder = i.extras?.getBinder(EXTRA_BINDER) ?: return
                    if (binder === blurService) return
                    if (runCatching { binder.interfaceDescriptor }.getOrNull() != SERVICE_DESCRIPTOR) return
                    if (runCatching { binder.linkToDeath(deathRecipient, 0) }.isFailure) return
                    blurService?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
                    blurService = binder
                    registered = false
                    sampledTopPx = -1
                    Log.i("Success: Received ColorOS BlurService from SystemUI")
                    onColorChanged?.invoke()
                }
            }.also { context.registerReceiver(it, IntentFilter(ACTION_BINDER), Context.RECEIVER_EXPORTED) }
            receiverContext = context
        }
        val now = SystemClock.uptimeMillis()
        if (lastBinderRequestAt != 0L && now - lastBinderRequestAt < BINDER_REQUEST_INTERVAL_MS) return
        lastBinderRequestAt = now
        context.sendBroadcast(
            Intent(ACTION_REQUEST_BINDER).setPackage(SYSTEMUI_PACKAGE).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        )
    }

    private fun register(service: IBinder, view: View): Boolean {
        val surface = runCatching {
            view.invokeMethodAs<Any>("getViewRootImpl")?.invokeMethodAs<SurfaceControl>("getSurfaceControl")
        }.getOrNull()?.takeIf { it.isValid } ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeInt(DRAWABLE_ID)
            data.writeInt(CAPTURE_FREQUENCY)
            data.writeInt(0) // Rect: none
            data.writeFloatArray(floatArrayOf(0f, 0f, 0f)) // [type, ?, radius]: unblurred
            data.writeInt(0)
            data.writeInt(1)
            surface.writeToParcel(data, 0)
            data.writeStrongBinder(connection)
            service.transact(TXN_ADD_BLUR_DRAWABLE, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: Exception) {
            Log.i(error)
            false
        } finally {
            data.recycle()
        }
    }

    // Binder thread. Keeps only the newest frame and reads at most one per MIN_FRAME_INTERVAL_MS;
    // the trailing frame is always read, so the color settles on the final screen.
    private fun onFrame(bundle: Bundle) {
        val buffer = bundle.getParcelable(KEY_BUFFER, HardwareBuffer::class.java) ?: return
        val frame = Frame(buffer, bundle.getFloat(KEY_SCALE, 0.25f), bundle.getInt(KEY_ROTATION))
        val replaced = pendingFrame.getAndSet(frame)
        if (replaced != null) {
            replaced.buffer.close()
            return
        }
        val handler = worker ?: synchronized(this) {
            worker ?: Handler(HandlerThread("WeTypeBackdropColor").apply { start() }.looper).also { worker = it }
        }
        val delay = lastFrameProcessedAt + MIN_FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        handler.postDelayed(::processPendingFrame, delay.coerceAtLeast(0L))
    }

    // Worker thread.
    private fun processPendingFrame() {
        val frame = pendingFrame.getAndSet(null) ?: return
        lastFrameProcessedAt = SystemClock.uptimeMillis()
        val top = sampleTopPx
        // ponytail: portrait only; a rotated capture needs its rows mapped back to the display.
        val sampled = frame.buffer.use { buffer ->
            if (frame.rotation != 0 || top < 0) null
            else runCatching { readEdgeColor(buffer, top, frame.scale) }.onFailure(Log::i).getOrNull()
        }
        mainHandler.post {
            if (sampleTopPx < 0 || isSameColor(sampled, color)) return@post
            color = sampled
            onColorChanged?.invoke()
        }
    }

    private fun isSameColor(a: Int?, b: Int?): Boolean {
        if (a == null || b == null) return a == b
        return (0..16 step 8).all { shift ->
            kotlin.math.abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)) <= COLOR_CHANGE_THRESHOLD
        }
    }

    private fun readEdgeColor(buffer: HardwareBuffer, topPx: Int, scale: Float): Int? {
        val y = (topPx * scale).toInt() - EDGE_ROW_OFFSET
        if (y < 0 || y >= buffer.height) return null
        val wrapped = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)) ?: return null
        val soft = try {
            wrapped.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            wrapped.recycle()
        } ?: return null
        val row = IntArray(soft.width)
        soft.getPixels(row, 0, soft.width, 0, y, soft.width, 1)
        soft.recycle()
        return uniformEdgeColor(row)
    }
}

/**
 * The opaque mean of [row] when it reads as one flat surface (an app bar), or null when it does
 * not (content, images, a floating keyboard over the page). Pixels far from the per-channel
 * median, such as an icon edge, are left out of the mean so they cannot tint it.
 */
internal fun uniformEdgeColor(row: IntArray): Int? {
    if (row.isEmpty()) return null
    fun median(shift: Int): Int {
        val channel = IntArray(row.size) { (row[it] ushr shift) and 0xFF }
        channel.sort()
        return channel[channel.size / 2]
    }
    val medianR = median(16)
    val medianG = median(8)
    val medianB = median(0)
    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    var count = 0
    for (pixel in row) {
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        if (
            kotlin.math.abs(r - medianR) <= EDGE_CHANNEL_TOLERANCE &&
            kotlin.math.abs(g - medianG) <= EDGE_CHANNEL_TOLERANCE &&
            kotlin.math.abs(b - medianB) <= EDGE_CHANNEL_TOLERANCE
        ) {
            sumR += r
            sumG += g
            sumB += b
            count++
        }
    }
    if (count < row.size * EDGE_MIN_UNIFORM_FRACTION) return null
    fun mean(sum: Long) = ((sum + count / 2) / count).toInt()
    return (0xFF shl 24) or (mean(sumR) shl 16) or (mean(sumG) shl 8) or mean(sumB)
}
