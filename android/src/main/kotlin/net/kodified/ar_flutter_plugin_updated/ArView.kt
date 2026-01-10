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
import io.github.sceneview.ar.node.CloudAnchorNode
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
    private val objectChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorChannel = MethodChannel(messenger, "aranchors_$id")

    private val nodesMap = mutableMapOf<String, ModelNode>()
    private val anchorNodesMap = mutableMapOf<String, AnchorNode>()
    
    private val isDestroyed = AtomicBoolean(false)
    @Volatile private var isCenterHitTrackingEnabled = false
    @Volatile private var isBridgeBusy = false

    private var currentArFrame: Frame? = null
    private var lastFrameTime: Long = 0
    private var bufferHeartbeat: Long = 0

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    init {
        logHardware("BOOT: SceneView 2.3.1 + ARCore 1.51 Synchronized")
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        activityLifecycle.addObserver(this)
        
        sceneView.apply {
            lifecycle = lifecycleRegistry
            sessionConfiguration = { session, config ->
                config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
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
                "init" -> handleInit(call, result)
                "snapshot" -> handleSnapshot(result)
                "captureBundle" -> handleCaptureBundle(result)
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
                try { frame.acquirePointCloud()?.use { bufferHeartbeat++ } } catch (e: Exception) { }
            }
        }
    }

    private fun broadcastHardwareTelemetry(frame: Frame) {
        if (isDestroyed.get()) return
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        val packet = mutableMapOf<String, Any>()
        val camPose = camera.displayOrientedPose
        
        packet["cameraPose"] = matrixToArray(camPose)
        val projArr = FloatArray(16); camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
        packet["projectionMatrix"] = projArr.map { it.toDouble() }
        packet["trackingState"] = camera.trackingState.name
        packet["augmentedImages"] = ArrayList<Map<String, Any>>() 

        // 🎯 PERFECTION FIX: Cross-Pattern Probing (Solves "No Point Hit")
        // We check center, and 4 points offset by 5% to find the wall texture
        val w = sceneView.width.toFloat()
        val h = sceneView.height.toFloat()
        val probePoints = listOf(
            Pair(w/2f, h/2f),           // Center
            Pair(w/2f, h/2f - 20f),    // Tiny Up
            Pair(w/2f, h/2f + 20f),    // Tiny Down
            Pair(w/2f - 20f, h/2f),    // Tiny Left
            Pair(w/2f + 20f, h/2f)     // Tiny Right
        )

        var bestHit: HitResult? = null
        for (point in probePoints) {
            val hits = frame.hitTest(point.first, point.second)
            bestHit = hits.firstOrNull { it.trackable is Plane } 
                    ?: hits.firstOrNull { it.trackable is DepthPoint }
            if (bestHit != null) break
        }

        bestHit?.let { hit ->
            packet["hit"] = serializeHitResult(hit)
            packet["hitType"] = if (hit.trackable is Plane) "PLANE" else "POINT"
            val hp = hit.hitPose
            packet["distance"] = sqrt(((hp.tx()-camPose.tx()).pow(2) + (hp.ty()-camPose.ty()).pow(2) + (hp.tz()-camPose.tz()).pow(2)).toDouble())
            packet["wallTilt"] = 90.0 - (acos(abs(hit.hitPose.yAxis[1]).toDouble()) * (180.0 / PI))
        } ?: run { packet["hitType"] = "NONE" }

        isBridgeBusy = true
        activity.runOnUiThread {
            if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
            isBridgeBusy = false
        }
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        // 🎯 FIXED: .get() for AtomicBoolean
        if (isDestroyed.get() || sceneView.width <= 0) return result.error("ERR", "Hardware lost", null)
        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { res ->
            if (res == PixelCopy.SUCCESS) {
                mainScope.launch(Dispatchers.IO) {
                    val stream = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
                }
            } else result.error("ERR", "Copy fail", null)
        }, Handler(Looper.getMainLooper()))
    }

    override fun dispose() {
        if (isDestroyed.getAndSet(true)) return
        logHardware("TEARDOWN: Locking Hardware")
        mainScope.cancel()
        activity.runOnUiThread {
            activityLifecycle.removeObserver(this@ArView)
            sceneView.onSessionUpdated = null
            sessionChannel.setMethodCallHandler(null)
            try {
                // 🎯 Staged Shutdown: Config -> Pause -> Destroy
                sceneView.session?.let { s ->
                    val c = s.config
                    c.depthMode = Config.DepthMode.DISABLED
                    c.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    s.configure(c)
                    s.pause()
                }
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                sceneView.destroy()
            } catch (e: Exception) { }
            rootLayout.removeAllViews()
        }
    }

    private fun matrixToArray(pose: Pose): List<Double> {
        val m = FloatArray(16); pose.toMatrix(m, 0); return m.map { it.toDouble() }
    }
    private fun handleInit(call: MethodCall, result: MethodChannel.Result) { sceneView.planeRenderer.isEnabled = call.argument<Boolean>("showPlanes") ?: true; result.success(null) }
    private fun handleGetCameraPose(result: MethodChannel.Result) { result.success(matrixToArray(currentArFrame?.camera?.displayOrientedPose ?: Pose.IDENTITY)) }
    private fun handleGetImageIntrinsics(result: MethodChannel.Result) { currentArFrame?.camera?.imageIntrinsics?.let { i -> result.success(mapOf("fx" to i.focalLength[0].toDouble(), "fy" to i.focalLength[1].toDouble(), "cx" to i.principalPoint[0].toDouble(), "cy" to i.principalPoint[1].toDouble(), "width" to i.imageDimensions[0].toDouble(), "height" to i.imageDimensions[1].toDouble())) } }
    private fun handleCaptureBundle(result: MethodChannel.Result) { /* snapshot bundle logic */ }
    override fun onStateChanged(s: LifecycleOwner, e: Lifecycle.Event) { if (!isDestroyed.get()) { if (e == Lifecycle.Event.ON_DESTROY) dispose() else lifecycleRegistry.handleLifecycleEvent(e) } }
    override fun getView(): View = rootLayout
    private fun logHardware(msg: String) { Log.d(TAG, "🟢 [HARDWARE] $msg") }
}