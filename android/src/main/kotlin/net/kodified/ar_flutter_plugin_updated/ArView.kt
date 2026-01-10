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
import io.flutter.plugin.common.*
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.*
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
    @Volatile private var hardwareUnlocked = false

    // 🎯 FIXED: Variables declared in class scope to resolve Unresolved Reference errors
    private var currentArFrame: Frame? = null
    private var lastFrameTime: Long = 0
    private var lastLogTime: Long = 0

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override fun getView(): View = rootLayout 

    init {
        logHardware("BOOT: Perception Stack 2.3.1 Build Verified")
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        activityLifecycle.addObserver(this)
        
        sceneView.apply {
            lifecycle = lifecycleRegistry
            sessionConfiguration = { session, config ->
                config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }
                }
            }
        }
        rootLayout.addView(sceneView)

        sessionChannel.setMethodCallHandler { call, result ->
            if (isDestroyed.get()) return@setMethodCallHandler
            when (call.method) {
                "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
                "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
                "dispose" -> dispose()
                "getImageIntrinsics" -> handleGetImageIntrinsics(result)
                "snapshot" -> handleSnapshot(result)
                else -> result.success(null)
            }
        }
        setupSceneViewListeners()
    }

    private fun setupSceneViewListeners() {
        sceneView.onSessionUpdated = { session, frame ->
            if (!isDestroyed.get()) {
                currentArFrame = frame
                
                // Hardware Unlock: Pixel 7 specific sync
                if (!hardwareUnlocked && frame.camera.trackingState == TrackingState.TRACKING) {
                    val config = session.config
                    config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                    session.configure(config)
                    hardwareUnlocked = true
                }

                if (isCenterHitTrackingEnabled && !isBridgeBusy && (System.currentTimeMillis() - lastFrameTime >= 33L)) {
                    lastFrameTime = System.currentTimeMillis()
                    broadcastHardwareTelemetry(frame)
                }
                
                try { frame.acquirePointCloud()?.use { } } catch (e: Exception) { }
            }
        }
    }

    private fun broadcastHardwareTelemetry(frame: Frame) {
        val camera = frame.camera
        // 🛡️ LOCK: Only send data if the hardware is actually tracking
        if (camera.trackingState != TrackingState.TRACKING) return

        val packet = mutableMapOf<String, Any>()
        
        // 🎯 INITIALIZE ALL KEYS (No more Nulls)
        packet["featureCount"] = 0
        packet["distance"] = 0.0
        packet["wallTilt"] = 0.0
        packet["hitType"] = "NONE"
        
        // Core Matrices
        val camPose = camera.displayOrientedPose
        packet["cameraPose"] = matrixToArray(camPose)
        val projArr = FloatArray(16); camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
        packet["projectionMatrix"] = projArr.map { it.toDouble() }
        packet["trackingState"] = camera.trackingState.name

        // Dots Count
        var dotsSeen = 0
        try {
            frame.acquirePointCloud()?.use { pc ->
                dotsSeen = pc.points.remaining() / 4
                packet["featureCount"] = dotsSeen
            }
        } catch (e: Exception) { }

        // Normalize Center Coordinates
        val viewCoords = floatArrayOf(sceneView.width / 2f, sceneView.height / 2f)
        val normalizedCoords = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.VIEW, viewCoords, Coordinates2d.VIEW_NORMALIZED, normalizedCoords)

        // Hit Testing
        val hits = frame.hitTestInstantPlacement(normalizedCoords[0], normalizedCoords[1], 2.0f)
        val bestHit = hits.firstOrNull { it.trackable is Plane } ?: hits.firstOrNull()

        if (bestHit != null) {
            packet["hit"] = serializeHitResult(bestHit)
            packet["hitType"] = if (bestHit.trackable is Plane) "PLANE" else "POINT"
            val hp = bestHit.hitPose
            packet["distance"] = sqrt(((hp.tx()-camPose.tx()).pow(2) + (hp.ty()-camPose.ty()).pow(2) + (hp.tz()-camPose.tz()).pow(2)).toDouble())
            packet["wallTilt"] = 90.0 - (acos(abs(hp.yAxis[1]).toDouble()) * (180.0 / PI))
        }

        // 🔥 Send to Flutter
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
            sceneView.onSessionUpdated = null
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
        } ?: result.error("ERR", "Hardware not reporting", null)
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { res ->
            if (res == PixelCopy.SUCCESS) {
                mainScope.launch(Dispatchers.IO) {
                    val stream = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
                }
            } else result.error("ERR", "Copy Fail", null)
        }, Handler(Looper.getMainLooper()))
    }
    
    override fun onStateChanged(s: LifecycleOwner, e: Lifecycle.Event) { if (!isDestroyed.get()) { if (e == Lifecycle.Event.ON_DESTROY) dispose() else lifecycleRegistry.handleLifecycleEvent(e) } }
    private fun logHardware(msg: String) { Log.d(TAG, "🟢 [HARDWARE] $msg") }
}