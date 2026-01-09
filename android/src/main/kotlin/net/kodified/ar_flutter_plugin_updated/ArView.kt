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

    private val TAG: String = "ArView_Native"
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
    private var bufferCounter: Long = 0

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // --- 1. CORE HANDLERS (Defined as lazy properties for safety) ---

    private val onSessionMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) return@MethodCallHandler
        trace("Call: ${call.method}")
        when (call.method) {
            "init" -> handleInit(call, result)
            "snapshot" -> handleSnapshot(result)
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

    private val onAnchorMethodCall = MethodChannel.MethodCallHandler { _, result -> result.notImplemented() }

    init {
        trace("Initializing AR Engine on Pixel 7...")
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
                
                if (isCenterHitTrackingEnabled && !isBridgeBusy && (now - lastFrameTime >= 33L)) {
                    lastFrameTime = now
                    broadcastFrameUpdate(frame)
                }

                // 🎯 FIX BUFFER LOST: Explicitly release PointCloud every frame
                try {
                    frame.acquirePointCloud().use { pc ->
                        bufferCounter++
                        if (bufferCounter % 100 == 0L) trace("Buffer Health: OK (Frame $bufferCounter)")
                    }
                } catch (e: Exception) { Log.e(TAG, "Buffer Access Fail: ${e.message}") }
            }
        }
    }

    private fun broadcastFrameUpdate(frame: Frame) {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        val packet = mutableMapOf<String, Any>()
        val camPose = camera.displayOrientedPose
        val camArr = FloatArray(16); camPose.toMatrix(camArr, 0)
        val projArr = FloatArray(16); camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
        
        packet["cameraPose"] = camArr.map { it.toDouble() }
        packet["projectionMatrix"] = projArr.map { it.toDouble() }
        packet["trackingState"] = camera.trackingState.name
        packet["augmentedImages"] = emptyList<Map<String, Any>>() // 🎯 FIX Dart Cast Error

        val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
        hits.firstOrNull { it.trackable is Plane }?.let { hit ->
            packet["hit"] = serializeHitResult(hit)
            packet["hitType"] = "PLANE"
            
            val hp = hit.hitPose
            val dist = sqrt(((hp.tx()-camPose.tx()).pow(2) + (hp.ty()-camPose.ty()).pow(2) + (hp.tz()-camPose.tz()).pow(2)).toDouble())
            packet["distance"] = dist
            
            val normal = hp.yAxis 
            packet["wallTilt"] = 90.0 - (acos(abs(normal[1]).toDouble()) * (180.0 / PI))
        } ?: run { packet["hitType"] = "NONE" }

        isBridgeBusy = true
        activity.runOnUiThread {
            if (!isDestroyed) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
            isBridgeBusy = false
        }
    }

    private fun handleCaptureBundle(result: MethodChannel.Result) {
        val frame = currentArFrame ?: return result.error("NO_FRAME", "Session not ready", null)
        val camera = frame.camera
        trace("Capture Bundle: Locking Hardware Matrices")
        
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
                    withContext(Dispatchers.Main) { 
                        trace("Capture Complete. Bytes: ${stream.size()}")
                        result.success(bundle) 
                    }
                }
            } else result.error("SNAP_FAIL", "PixelCopy failed", null)
        }, Handler(Looper.getMainLooper()))
    }

    private fun handleRemoveNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        nodesMap[name]?.let { node ->
            sceneView.removeChildNode(node)
            nodesMap.remove(name)
            anchorNodesMap.values.find { it.childNodes.contains(node) }?.let { anchorNode ->
                if (anchorNode.childNodes.isEmpty()) {
                    sceneView.removeChildNode(anchorNode)
                    anchorNode.anchor?.detach()
                    anchorNodesMap.entries.removeIf { it.value == anchorNode }
                }
            }
            result.success(name)
        } ?: result.error("NODE_NOT_FOUND", "Node not found", null)
    }

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        sceneView.planeRenderer.isEnabled = call.argument<Boolean>("showPlanes") ?: true
        result.success(null)
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { res ->
            if (res == PixelCopy.SUCCESS) {
                mainScope.launch(Dispatchers.IO) {
                    val stream = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
                }
            } else result.error("ERR", "Snap fail", null)
        }, Handler(Looper.getMainLooper()))
    }

    private fun handleGetAnchorPose(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("anchorId")
        val anchor = sceneView.session?.allAnchors?.find { it.cloudAnchorId == id } ?: anchorNodesMap[id]?.anchor
        anchor?.let { val m = FloatArray(16); it.pose.toMatrix(m, 0); result.success(m.map { it.toDouble() }) }
            ?: result.error("ERR", "Anchor missing", null)
    }

    private fun handleAddNode(nodeData: Map<String, Any>, result: MethodChannel.Result) {
        mainScope.launch {
            var uri = nodeData["uri"] as? String ?: return@launch result.success(false)
            val type = nodeData["type"] as Int
            if (type == 0) uri = FlutterInjector.instance().flutterLoader().getLookupKeyForAsset(uri)
            else if (type == 3) uri = activity.applicationInfo.dataDir + "/app_flutter/" + uri
            try {
                sceneView.modelLoader.loadModelInstance(uri)?.let { inst ->
                    val node = ModelNode(inst).apply { 
                        name = nodeData["name"] as? String 
                        val transform = nodeData["transformation"] as? ArrayList<Double>
                        transform?.let { val s = it[0].toFloat(); scale = Scale(s, s, s) }
                    }
                    sceneView.addChildNode(node); node.name?.let { nodesMap[it] = node }
                    result.success(true)
                } ?: result.success(false)
            } catch (e: Exception) { result.success(false) }
        }
    }

    private fun handleAddAnchor(call: MethodCall, result: MethodChannel.Result) {
        val t = call.argument<ArrayList<Double>>("transformation") ?: return result.success(false)
        val (p, r) = deserializeMatrix4(t)
        sceneView.session?.createAnchor(Pose(floatArrayOf(p.x, p.y, p.z), floatArrayOf(r.x, r.y, r.z, r.w)))?.let {
            val node = AnchorNode(sceneView.engine, it)
            sceneView.addChildNode(node); anchorNodesMap[call.argument<String>("name") ?: "anchor"] = node
            result.success(true)
        } ?: result.success(false)
    }

    private fun handleInitGoogleCloudAnchorMode(result: MethodChannel.Result) {
        sceneView.session?.let { s -> s.configure(s.config.apply { cloudAnchorMode = Config.CloudAnchorMode.ENABLED }); result.success(null) }
    }

    private fun handleUploadAnchor(call: MethodCall, result: MethodChannel.Result) {
        anchorNodesMap[call.argument<String>("name")]?.let { node ->
            val cloud = CloudAnchorNode(sceneView.engine, node.anchor!!)
            cloud.host(sceneView.session!!) { id, state -> if (state == Anchor.CloudAnchorState.SUCCESS) result.success(id) else result.error("ERR", state.name, null) }
            sceneView.addChildNode(cloud)
        }
    }

    private fun handleDownloadAnchor(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("cloudanchorid") ?: return
        CloudAnchorNode.resolve(sceneView.engine, sceneView.session!!, id) { state, node ->
            if (!state.isError && node != null) { sceneView.addChildNode(node); result.success(true) } else result.error("ERR", state.name, null)
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (!isDestroyed) lifecycleRegistry.handleLifecycleEvent(event)
    }

    override fun getView(): View = rootLayout
    
    private fun trace(msg: String) { Log.d(TAG, "🟢 [DEBUG] $msg") }

    override fun dispose() {
        if (isDestroyed) return
        isDestroyed = true
        trace("Initiating engine disposal...")
        mainScope.cancel()
        activity.runOnUiThread {
            activityLifecycle.removeObserver(this@ArView)
            sceneView.onSessionUpdated = null
            // 🎯 FIX PANIC/DOUBLE FREE: Clear channels BEFORE stopping lifecycle
            sessionChannel.setMethodCallHandler(null)
            objectChannel.setMethodCallHandler(null)
            anchorChannel.setMethodCallHandler(null)
            
            sceneView.session?.pause()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            rootLayout.removeAllViews()
        }
    }
}