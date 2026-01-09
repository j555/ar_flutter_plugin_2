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
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.*
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

class ArView(
    context: Context,
    private val activity: Activity,
    private val activityLifecycle: Lifecycle,
    messenger: BinaryMessenger,
    id: Int,
) : PlatformView, LifecycleOwner, LifecycleEventObserver {

    private val TAG: String = "ArView"
    private var sceneView: ARSceneView
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val rootLayout: ViewGroup = FrameLayout(context)

    private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    private val objectChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorChannel = MethodChannel(messenger, "aranchors_$id")

    private val nodesMap = mutableMapOf<String, ModelNode>()
    private val anchorNodesMap = mutableMapOf<String, AnchorNode>()
    
    @Volatile private var isDestroyed = false
    @Volatile private var isCenterHitTrackingEnabled = false
    @Volatile private var isBridgeBusy = false

    private var currentArFrame: Frame? = null
    private var lastFrameTime: Long = 0
    private val throttleInterval = 33L 

    // --- HANDLERS MUST BE DEFINED BEFORE INIT BLOCK TO AVOID INITIALIZATION ERRORS ---
    
    private val onSessionMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) return@MethodCallHandler
        when (call.method) {
            "init" -> result.success(null)
            "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
            "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
            "captureBundle" -> handleCaptureBundle(result)
            "dispose" -> dispose()
            else -> result.notImplemented()
        }
    }

    private val onObjectMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) return@MethodCallHandler
        when (call.method) {
            "removeNode" -> handleRemoveNode(call, result)
            else -> result.notImplemented()
        }
    }

    private val onAnchorMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) return@MethodCallHandler
        when (call.method) {
            "removeAnchor" -> handleRemoveAnchor(call.argument<String>("name"), result)
            else -> result.notImplemented()
        }
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        activityLifecycle.addObserver(this)
        sceneView = ARSceneView(context, null).apply {
            lifecycle = lifecycleRegistry
            sessionConfiguration = { session, config ->
                config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
                }
            }
        }
        rootLayout.addView(sceneView)
        
        // Correctly assign handlers
        sessionChannel.setMethodCallHandler(onSessionMethodCall)
        objectChannel.setMethodCallHandler(onObjectMethodCall)
        anchorChannel.setMethodCallHandler(onAnchorMethodCall)
        
        setupSceneViewListeners()
    }

    private fun setupSceneViewListeners() {
        sceneView.onSessionUpdated = { _, frame ->
            if (!isDestroyed) {
                currentArFrame = frame
                val now = System.currentTimeMillis()
                
                // 1. Unified Hardware Telemetry
                if (isCenterHitTrackingEnabled && !isBridgeBusy && (now - lastFrameTime >= throttleInterval)) {
                    lastFrameTime = now
                    broadcastFrameUpdate(frame)
                }

                // 2. Resource Cleanup: Release Point Clouds immediately to prevent buffer errors
                try { frame.acquirePointCloud().release() } catch (e: Exception) {}
            }
        }
    }

    private fun broadcastFrameUpdate(frame: Frame) {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        val packet = mutableMapOf<String, Any>()
        val camPose = FloatArray(16); camera.displayOrientedPose.toMatrix(camPose, 0)
        val projArr = FloatArray(16); camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
        
        packet["cameraPose"] = camPose.map { it.toDouble() }
        packet["projectionMatrix"] = projArr.map { it.toDouble() }
        packet["trackingState"] = camera.trackingState.name
        packet["augmentedImages"] = emptyList<Map<String, Any>>() 

        val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
        hits.firstOrNull { it.trackable is Plane }?.let { hit ->
            packet["hit"] = serializeHitResult(hit)
            packet["hitType"] = "PLANE"
            val hp = hit.hitPose
            val cp = camera.displayOrientedPose
            packet["distance"] = sqrt(((hp.tx()-cp.tx()).pow(2) + (hp.ty()-cp.ty()).pow(2) + (hp.tz()-cp.tz()).pow(2)).toDouble())
            packet["wallTilt"] = 90.0 - (acos(abs(hp.yAxis[1]).toDouble()) * (180.0 / PI))
        }

        isBridgeBusy = true
        activity.runOnUiThread {
            if (!isDestroyed) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
            isBridgeBusy = false
        }
    }

    private fun handleCaptureBundle(result: MethodChannel.Result) {
        val frame = currentArFrame ?: return result.error("NO_FRAME", "Session not ready", null)
        val camera = frame.camera
        
        // ATOMIC DATA CAPTURE
        val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, 0.01f, 100.0f)
        val view = FloatArray(16); camera.getViewMatrix(view, 0)
        val intrinsics = camera.imageIntrinsics
        val intrinsicMap = mapOf(
            "fx" to intrinsics.focalLength[0].toDouble(), "fy" to intrinsics.focalLength[1].toDouble(),
            "cx" to intrinsics.principalPoint[0].toDouble(), "cy" to intrinsics.principalPoint[1].toDouble(),
            "width" to intrinsics.imageDimensions[0].toDouble(), "height" to intrinsics.imageDimensions[1].toDouble()
        )

        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { res ->
            if (res == PixelCopy.SUCCESS) {
                mainScope.launch(Dispatchers.IO) {
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    val bundle = mutableMapOf<String, Any>(
                        "image" to stream.toByteArray(),
                        "projectionMatrix" to proj.map { it.toDouble() },
                        "viewMatrix" to view.map { it.toDouble() },
                        "intrinsics" to intrinsicMap
                    )
                    withContext(Dispatchers.Main) { result.success(bundle) }
                }
            } else result.error("SNAP_FAIL", "PixelCopy failed", null)
        }, Handler(Looper.getMainLooper()))
    }

    private fun handleRemoveNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        nodesMap[name]?.let { node ->
            sceneView.removeChildNode(node)
            nodesMap.remove(name)
            // Cleanup orphaned anchors using childNodes
            anchorNodesMap.values.find { it.childNodes.contains(node) }?.let { anchorNode ->
                if (anchorNode.childNodes.isEmpty()) {
                    sceneView.removeChildNode(anchorNode)
                    anchorNode.anchor?.detach()
                    anchorNodesMap.entries.removeIf { it.value == anchorNode }
                }
            }
            result.success(name)
        } ?: result.error("NODE_NOT_FOUND", "Node $name not found", null)
    }

    private fun handleRemoveAnchor(name: String?, result: MethodChannel.Result) {
        anchorNodesMap[name]?.let { 
            sceneView.removeChildNode(it)
            it.anchor?.detach()
            anchorNodesMap.remove(name)
            result.success(null) 
        } ?: result.error("ERR", "Anchor missing", null)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (!isDestroyed) lifecycleRegistry.handleLifecycleEvent(event)
    }

    override fun getView(): View = rootLayout
    override fun dispose() {
        if (isDestroyed) return
        isDestroyed = true
        mainScope.cancel()
        activity.runOnUiThread {
            activityLifecycle.removeObserver(this@ArView)
            sceneView.onSessionUpdated = null
            // CRITICAL: Let LifecycleRegistry trigger the destruction. 
            // Manual pause/stop during Surface destruction causes the EGL BAD_ACCESS crash.
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            sessionChannel.setMethodCallHandler(null)
            objectChannel.setMethodCallHandler(null)
            anchorChannel.setMethodCallHandler(null)
            rootLayout.removeAllViews()
        }
    }
}