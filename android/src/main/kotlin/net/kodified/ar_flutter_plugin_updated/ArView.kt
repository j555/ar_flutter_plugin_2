package net.kodified.ar_flutter_plugin_updated

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.*
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import androidx.lifecycle.*
import com.google.ar.core.*
import net.kodified.ar_flutter_plugin_updated.Serialization.Deserializers.deserializeMatrix4
import net.kodified.ar_flutter_plugin_updated.Serialization.Serialization.serializeAnchor
import net.kodified.ar_flutter_plugin_updated.Serialization.Serialization.serializeHitResult
import io.flutter.FlutterInjector
import io.flutter.plugin.common.*
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.*
import io.github.sceneview.node.*
import kotlinx.coroutines.*
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class ArView(
    context: Context,
    private val activity: Activity,
    private val activityLifecycle: Lifecycle,
    messenger: BinaryMessenger,
    id: Int,
) : PlatformView, LifecycleOwner, LifecycleEventObserver {

    private val TAG: String = "ArView_Native"
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val rootLayout: ViewGroup = FrameLayout(context)
    private val sceneView: ARSceneView = ARSceneView(context, null)

    private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    private val isDestroyed = AtomicBoolean(false)
    @Volatile private var isCenterHitTrackingEnabled = false
    @Volatile private var isBridgeBusy = false

    private var currentArFrame: Frame? = null
    private var lastFrameTime: Long = 0

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    init {
        logHardware("BOOT: Coordinate Normalization + Instant Placement Active")
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        activityLifecycle.addObserver(this)
        
        sceneView.apply {
            lifecycle = lifecycleRegistry
            sessionConfiguration = { session, config ->
                config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    
                    // 🎯 PERFECTION: Enable Instant Placement for immediate wall tracking
                    if (session.isInstantPlacementModeSupported(Config.InstantPlacementMode.LOCAL_Y_UP)) {
                        instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                    }
                    
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        setDepthMode(Config.DepthMode.AUTOMATIC)
                    }
                }
            }
        }
        rootLayout.addView(sceneView)
        sessionChannel.setMethodCallHandler { call, result ->
            if (isDestroyed.get()) return@setMethodCallHandler
            when (call.method) {
                "init" -> { sceneView.planeRenderer.isEnabled = true; result.success(null) }
                "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
                "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
                "dispose" -> dispose()
                "getImageIntrinsics" -> handleGetImageIntrinsics(result)
                else -> result.notImplemented()
            }
        }
        setupSceneViewListeners()
    }

    private fun setupSceneViewListeners() {
        sceneView.onSessionUpdated = { _, frame ->
            if (!isDestroyed.get()) {
                currentArFrame = frame
                val now = System.currentTimeMillis()
                if (isCenterHitTrackingEnabled && !isBridgeBusy && (now - lastFrameTime >= 33L)) {
                    lastFrameTime = now
                    broadcastHardwareTelemetry(frame)
                }
            }
        }
    }

    private fun broadcastHardwareTelemetry(frame: Frame) {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        // 🎯 PERFECTION: Normalize Screen Coordinates to ARCore Space
        // This maps the 2400px height of the Pixel 7 screen to the 1920px camera buffer
        val viewCoordinates = floatArrayOf(sceneView.width / 2f, sceneView.height / 2f)
        val normalizedCoordinates = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.VIEW,
            viewCoordinates,
            Coordinates2d.VIEW_NORMALIZED,
            normalizedCoordinates
        )

        val packet = mutableMapOf<String, Any>()
        packet["cameraPose"] = matrixToArray(camera.displayOrientedPose)
        packet["trackingState"] = camera.trackingState.name

        // 🎯 SEARCH LOGIC: Plane -> Instant Placement (Local Y Up)
        // Instant Placement allows the Pixel 7 to "guess" the wall based on floor tracking
        val hits = frame.hitTestInstantPlacement(normalizedCoordinates[0], normalizedCoordinates[1], 1.0f)
        val bestHit = hits.firstOrNull { it.trackable is Plane } ?: hits.firstOrNull()

        bestHit?.let { hit ->
            packet["hit"] = serializeHitResult(hit)
            packet["hitType"] = if (hit.trackable is Plane) "PLANE" else "POINT"
            
            val hp = hit.hitPose
            val cp = camera.displayOrientedPose
            packet["distance"] = sqrt(((hp.tx()-cp.tx()).pow(2) + (hp.ty()-cp.ty()).pow(2) + (hp.tz()-cp.tz()).pow(2)).toDouble())
            packet["wallTilt"] = 90.0 - (acos(abs(hp.yAxis[1]).toDouble()) * (180.0 / PI))
        } ?: run { packet["hitType"] = "NONE" }

        isBridgeBusy = true
        activity.runOnUiThread {
            if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
            isBridgeBusy = false
        }
    }

    override fun dispose() {
        if (isDestroyed.getAndSet(true)) return
        mainScope.cancel()
        activity.runOnUiThread {
            activityLifecycle.removeObserver(this@ArView)
            sessionChannel.setMethodCallHandler(null)
            try {
                sceneView.session?.let { s ->
                    val c = s.config
                    c.depthMode = Config.DepthMode.DISABLED
                    c.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    s.configure(c)
                    s.pause()
                }
                sceneView.destroy()
            } catch (e: Exception) { }
            rootLayout.removeAllViews()
        }
    }

    private fun matrixToArray(pose: Pose): List<Double> {
        val m = FloatArray(16); pose.toMatrix(m, 0); return m.map { it.toDouble() }
    }
    private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
        currentArFrame?.camera?.imageIntrinsics?.let { i -> 
            result.success(mapOf("fx" to i.focalLength[0].toDouble(), "fy" to i.focalLength[1].toDouble(), "cx" to i.principalPoint[0].toDouble(), "cy" to i.principalPoint[1].toDouble(), "width" to i.imageDimensions[0].toDouble(), "height" to i.imageDimensions[1].toDouble())) 
        } ?: result.error("ERR", "Not ready", null)
    }
    private fun logHardware(msg: String) { Log.d(TAG, "🟢 [HARDWARE] $msg") }
}