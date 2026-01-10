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

    // --- 1. ATOMIC CHANNEL HANDLERS (Defined as properties to ensure zero-init race) ---

    private val onSessionMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed.get()) return@MethodCallHandler
        traceData("ACTION: ${call.method}")
        when (call.method) {
            "init" -> handleInit(call, result)
            "snapshot" -> handleSnapshot(result)
            "captureBundle" -> handleCaptureBundle(result)
            "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
            "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
            "dispose" -> dispose()
            "getAnchorPose" -> handleGetAnchorPose(call, result)
            "getCameraPose" -> handleGetCameraPose(result)
            "getProjectionMatrix" -> handleGetProjectionMatrix(result)
            "getImageIntrinsics" -> handleGetImageIntrinsics(result)
            else -> result.notImplemented()
        }
    }

    private val onObjectMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed.get()) return@MethodCallHandler
        when (call.method) {
            "addNode" -> (call.arguments as? Map<String, Any>)?.let { handleAddNode(it, result) }
            "removeNode" -> handleRemoveNode(call, result)
            "transformationChanged" -> handleTransformNode(call, result)
            else -> result.notImplemented()
        }
    }

    private val onAnchorMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed.get()) return@MethodCallHandler
        when (call.method) {
            "addAnchor" -> handleAddAnchor(call, result)
            "removeAnchor" -> handleRemoveAnchor(call.argument<String>("name"), result)
            "initGoogleCloudAnchorMode" -> handleInitGoogleCloudAnchorMode(result)
            "uploadAnchor" -> handleUploadAnchor(call, result)
            "downloadAnchor" -> handleDownloadAnchor(call, result)
            else -> result.notImplemented()
        }
    }

    init {
        logHardware("BOOT: SceneView 2.3.1 + ARCore 1.51 Synchronized Stack")
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
            // 🎯 CRITICAL GUARD: Stop update processing the instant dispose is called
            if (!isDestroyed.get()) {
                currentArFrame = frame
                val now = System.currentTimeMillis()

                if (isCenterHitTrackingEnabled && !isBridgeBusy && (now - lastFrameTime >= 33L)) {
                    lastFrameTime = now
                    broadcastHardwareTelemetry(frame)
                }

                // 🎯 FIX: Release handles immediately to stop "Unable to acquire buffer item"
                try {
                    frame.acquirePointCloud()?.use { pc ->
                        bufferHeartbeat++
                        if (bufferHeartbeat % 300 == 0L) logHardware("SYNC: Buffer Health Nominal")
                    }
                } catch (e: Exception) { }
            }
        }
    }

    private fun broadcastHardwareTelemetry(frame: Frame) {
        if (isDestroyed.get()) return
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        val packet = mutableMapOf<String, Any>()
        val camPose = camera.displayOrientedPose
        val camArr = FloatArray(16); camPose.toMatrix(camArr, 0)
        val projArr = FloatArray(16); camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
        
        packet["cameraPose"] = camArr.map { it.toDouble() }
        packet["projectionMatrix"] = projArr.map { it.toDouble() }
        packet["trackingState"] = camera.trackingState.name
        
        // 🎯 FIX: Never null for Dart cast stability
        packet["augmentedImages"] = ArrayList<Map<String, Any>>() 

        val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
        hits.firstOrNull { it.trackable is Plane }?.let { hit ->
            packet["hit"] = serializeHitResult(hit)
            packet["hitType"] = "PLANE"
            
            val hp = hit.hitPose
            val dist = sqrt(((hp.tx()-camPose.tx()).pow(2) + (hp.ty()-camPose.ty()).pow(2) + (hp.tz()-camPose.tz()).pow(2)).toDouble())
            packet["distance"] = dist
            
            val normal = hp.yAxis 
            packet["wallTilt"] = 90.0 - (acos(abs(normal[1]).toDouble()) * (180.0 / PI))
        } ?: run { 
            packet["hitType"] = "NONE" 
            packet["hit"] = emptyMap<String, Any>()
        }

        isBridgeBusy = true
        activity.runOnUiThread {
            if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
            isBridgeBusy = false
        }
    }

    private fun handleCaptureBundle(result: MethodChannel.Result) {
        val frame = currentArFrame ?: return result.error("ERR", "Hardware context lost", null)
        val camera = frame.camera
        logHardware("CAPTURE: Syncing Hardware Intrinsics")
        
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
            } else result.error("ERR", "PixelCopy Failed", null)
        }, Handler(Looper.getMainLooper()))
    }

    private fun handleTransformNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name") ?: return
        val t = call.argument<ArrayList<Double>>("transformation") ?: return
        val matrixValues = t.map { it.toFloat() }.toFloatArray()
        nodesMap[name]?.apply { 
            transform(dev.romainguy.kotlin.math.Mat4.of(*matrixValues)) 
            result.success(null)
        }
    }

    private fun handleRemoveNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name") ?: return
        nodesMap[name]?.let { node ->
            sceneView.removeChildNode(node)
            nodesMap.remove(name)
            result.success(name)
        } ?: result.error("NOT_FOUND", "Node invalid", null)
    }

    private fun handleRemoveAnchor(name: String?, result: MethodChannel.Result) {
        if (name == null) return result.error("ERR", "Name null", null)
        anchorNodesMap[name]?.let { 
            sceneView.removeChildNode(it)
            it.anchor?.detach()
            anchorNodesMap.remove(name)
            result.success(null) 
        } ?: result.error("ERR", "Anchor invalid", null)
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
                        val tArr = nodeData["transformation"] as? ArrayList<Double>
                        tArr?.let { val s = it[0].toFloat(); scale = Scale(s, s, s) }
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

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        sceneView.planeRenderer.isEnabled = call.argument<Boolean>("showPlanes") ?: true
        result.success(null)
    }

    private fun handleGetCameraPose(result: MethodChannel.Result) { val p = FloatArray(16); currentArFrame?.camera?.displayOrientedPose?.toMatrix(p, 0); result.success(p.map { it.toDouble() }) }
    private fun handleGetProjectionMatrix(result: MethodChannel.Result) { val p = FloatArray(16); currentArFrame?.camera?.getProjectionMatrix(p, 0, 0.01f, 100f); result.success(p.map { it.toDouble() }) }
    private fun handleGetImageIntrinsics(result: MethodChannel.Result) { currentArFrame?.camera?.imageIntrinsics?.let { i -> result.success(mapOf("fx" to i.focalLength[0].toDouble(), "fy" to i.focalLength[1].toDouble(), "cx" to i.principalPoint[0].toDouble(), "cy" to i.principalPoint[1].toDouble(), "width" to i.imageDimensions[0].toDouble(), "height" to i.imageDimensions[1].toDouble())) } }
    private fun handleSnapshot(result: MethodChannel.Result) { val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888); PixelCopy.request(sceneView, bitmap, { res -> if (res == PixelCopy.SUCCESS) { mainScope.launch(Dispatchers.IO) { val stream = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); withContext(Dispatchers.Main) { result.success(stream.toByteArray()) } } } }, Handler(Looper.getMainLooper())) }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (!isDestroyed.get()) {
            traceData("LIFECYCLE_UPDATE: ${event.name}")
            if (event == Lifecycle.Event.ON_DESTROY) dispose()
            else lifecycleRegistry.handleLifecycleEvent(event)
        }
    }

    override fun getView(): View = rootLayout
    private fun logHardware(msg: String) { Log.d(TAG, "🟢 [HARDWARE] $msg") }
    private fun traceData(msg: String) { Log.d(TAG, "🔵 [DATA] $msg") }

    override fun dispose() {
        if (isDestroyed.getAndSet(true)) return
        logHardware("DISPOSE_START: Synchronizing GPU Context...")
        mainScope.cancel()
        
        activity.runOnUiThread {
            activityLifecycle.removeObserver(this@ArView)
            sceneView.onSessionUpdated = null
            
            // 🎯 FIXED: Stop bridge traffic BEFORE touching hardware
            sessionChannel.setMethodCallHandler(null)
            objectChannel.setMethodCallHandler(null)
            anchorChannel.setMethodCallHandler(null)
            
            try {
                // 🎯 FIXED: Detach SceneView from Layout to stop update loops before pause
                rootLayout.removeAllViews()
                sceneView.session?.pause()
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                sceneView.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "🔴 TEARDOWN_ERR: ${e.message}")
            }
            logHardware("DISPOSE_COMPLETE: Resources released.")
        }
    }
}