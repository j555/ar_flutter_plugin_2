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
import io.github.sceneview.gesture.*
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

/**
 * PRODUCTION READY AR VIEW
 * Implements Atomic Metadata Capture, Robust Node Management, and Cloud Anchor Support.
 */
class ArView(
    context: Context,
    private val activity: Activity,
    private val activityLifecycle: Lifecycle,
    messenger: BinaryMessenger,
    id: Int,
) : PlatformView, LifecycleOwner, LifecycleEventObserver {

    private val TAG: String = "ArView"
    private val viewContext: Context = context 
    private var sceneView: ARSceneView
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private var worldOriginNode: Node? = null
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val rootLayout: ViewGroup = FrameLayout(context)

    private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    private val objectChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorChannel = MethodChannel(messenger, "aranchors_$id")

    private val nodesMap = mutableMapOf<String, ModelNode>()
    private val anchorNodesMap = mutableMapOf<String, AnchorNode>()
    
    @Volatile private var isDestroyed = false
    @Volatile private var isCenterHitTrackingEnabled = false
    @Volatile private var isSessionPaused = false
    @Volatile private var isBridgeBusy = false

    private var currentArFrame: Frame? = null
    private val detectedPlanes = mutableSetOf<Plane>()
    private val pendingHitTests = ConcurrentLinkedQueue<PendingHitTest>()

    // Point Cloud Management
    private var pointCloudModelInstances = mutableListOf<ModelInstance>()
    private val pointCloudNodes = mutableListOf<PointCloudNode>()
    private val pointCloudNodePool = ArrayList<PointCloudNode>()
    private var showPointCloud = false
    private var lastPointCloudTimestamp: Long? = null
    private var minConfidence = 0.1f
    private var maxPoints = 500

    private var lastFrameTime: Long = 0
    private val throttleInterval = 33L 

    private data class PendingHitTest(val x: Float, val y: Float, val nodeData: Map<String, Any>, val result: MethodChannel.Result)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // --- CHANNEL HANDLERS ---

    private val onSessionMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) return@MethodCallHandler
        when (call.method) {
            "init" -> handleInit(call, result)
            "showPlanes" -> { sceneView.planeRenderer.isEnabled = call.argument<Boolean>("showPlanes") ?: false; result.success(null) }
            "showPointCloud" -> { showPointCloud = call.argument<Boolean>("showPointCloud") ?: true; result.success(null) }
            "dispose" -> dispose()
            "getAnchorPose" -> handleGetAnchorPose(call, result)
            "getCameraPose" -> handleGetCameraPose(result)
            "getProjectionMatrix" -> handleGetProjectionMatrix(result)
            "getImageIntrinsics" -> handleGetImageIntrinsics(result)
            "snapshot" -> handleSnapshot(result)
            "disableCamera" -> { isSessionPaused = true; sceneView.session?.pause(); result.success(null) }
            "enableCamera" -> { isSessionPaused = false; sceneView.session?.resume(); result.success(null) }
            "hitTest" -> handleHitTest(call, result)
            "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
            "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
            "captureBundle" -> handleCaptureBundle(result)
            else -> result.notImplemented()
        }
    }

    private val onObjectMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) return@MethodCallHandler
        when (call.method) {
            "addNode" -> (call.arguments as? Map<String, Any>)?.let { handleAddNode(it, result) }
            "removeNode" -> handleRemoveNode(call, result)
            "transformationChanged" -> handleTransformNode(call, result)
            "addNodeToPlaneAnchor" -> handleAddNodeToPlaneAnchor(call, result)
            "addNodeToScreenPosition" -> handleAddNodeToScreenPosition(call, result)
            else -> result.notImplemented()
        }
    }

    private val onAnchorMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) return@MethodCallHandler
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
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        activityLifecycle.addObserver(this)
        sceneView = ARSceneView(viewContext, null).apply {
            lifecycle = lifecycleRegistry
            sessionConfiguration = { session, config ->
                config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
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
            if (!isSessionPaused && !isDestroyed) {
                currentArFrame = frame
                val now = System.currentTimeMillis()

                if (isCenterHitTrackingEnabled && frame.camera.trackingState == TrackingState.TRACKING && !isBridgeBusy) {
                    if (now - lastFrameTime >= throttleInterval) {
                        lastFrameTime = now
                        broadcastUnifiedUpdate(frame)
                    }
                }
                updatePlanes(frame)
                updatePointCloud(frame)
                processPendingHits(frame)
            }
        }

        sceneView.onTouchEvent = { _, res ->
            (res as? HitResult)?.let { hit ->
                val map = serializeHitResult(hit)
                activity.runOnUiThread { if (!isDestroyed) sessionChannel.invokeMethod("onPlaneOrPointTap", listOf(map)) }
                true
            } ?: false
        }
    }

    private fun broadcastUnifiedUpdate(frame: Frame) {
        val camera = frame.camera
        val packet = mutableMapOf<String, Any>()
        
        // 1. Atomic Pose & Projection
        val camPose = FloatArray(16); camera.displayOrientedPose.toMatrix(camPose, 0)
        val projArr = FloatArray(16); camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
        packet["cameraPose"] = camPose.map { it.toDouble() }
        packet["projectionMatrix"] = projArr.map { it.toDouble() }
        packet["trackingState"] = camera.trackingState.name

        // 2. High-Precision Hit Testing (Center Screen)
        val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
        hits.firstOrNull { it.trackable is Plane || it.trackable is com.google.ar.core.Point }?.let { hit ->
            packet["hit"] = serializeHitResult(hit)
            packet["hitType"] = if (hit.trackable is Plane) "PLANE" else "POINT"
            
            val hp = hit.hitPose
            val cp = camera.displayOrientedPose
            packet["distance"] = sqrt(((hp.tx()-cp.tx()).pow(2) + (hp.ty()-cp.ty()).pow(2) + (hp.tz()-cp.tz()).pow(2)).toDouble())
            packet["wallTilt"] = 90.0 - (acos(abs(hp.yAxis[1]).toDouble()) * (180.0 / PI))
        }

        // 3. Hardware Telemetry (Lighting & Thermal)
        frame.lightEstimate?.let { le ->
            if (le.state == LightEstimate.State.VALID) {
                packet["sphericalHarmonics"] = le.environmentalHdrAmbientSphericalHarmonics.map { it.toDouble() }
                packet["pixelIntensity"] = le.pixelIntensity.toDouble()
            }
        }
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        packet["thermalStatus"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else -1

        // 4. Augmented Images
        val images = frame.getUpdatedTrackables(AugmentedImage::class.java).map { 
            mapOf("name" to it.name, "index" to it.index, "tracking" to it.trackingState.name) 
        }
        if (images.isNotEmpty()) packet["augmentedImages"] = images

        isBridgeBusy = true
        activity.runOnUiThread { 
            if (!isDestroyed) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
            isBridgeBusy = false
        }
    }

    private fun handleCaptureBundle(result: MethodChannel.Result) {
        val frame = currentArFrame ?: return result.error("ERR", "No frame", null)
        val camera = frame.camera
        
        // 🎯 LOCK METADATA AT INSTANT OF CAPTURE
        val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, 0.01f, 100.0f)
        val view = FloatArray(16); camera.getViewMatrix(view, 0)
        val intrinsics = camera.imageIntrinsics
        val intrinsicMap = mapOf(
            "fx" to intrinsics.focalLength[0].toDouble(), "fy" to intrinsics.focalLength[1].toDouble(),
            "cx" to intrinsics.principalPoint[0].toDouble(), "cy" to intrinsics.principalPoint[1].toDouble(),
            "width" to intrinsics.imageDimensions[0].toDouble(), "height" to intrinsics.imageDimensions[1].toDouble()
        )

        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                mainScope.launch(Dispatchers.IO) {
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    val data = mutableMapOf<String, Any>(
                        "image" to stream.toByteArray(),
                        "projectionMatrix" to proj.map { it.toDouble() },
                        "viewMatrix" to view.map { it.toDouble() },
                        "intrinsics" to intrinsicMap
                    )
                    // Depth capture if supported
                    try {
                        frame.acquireDepthImage16Bits().use { img ->
                            val buffer = img.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes); data["depthMap"] = bytes
                        }
                    } catch (e: Exception) {}
                    withContext(Dispatchers.Main) { result.success(data) }
                }
            } else result.error("ERR", "PixelCopy failed", null)
        }, Handler(Looper.getMainLooper()))
    }

    private fun handleRemoveNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        nodesMap[name]?.let { node ->
            sceneView.removeChildNode(node)
            nodesMap.remove(name)
            // Cleanup orphaned anchors
            anchorNodesMap.values.find { it.children.contains(node) }?.let { anchor ->
                if (anchor.children.isEmpty()) {
                    sceneView.removeChildNode(anchor)
                    anchor.anchor?.detach()
                    anchorNodesMap.entries.removeIf { it.value == anchor }
                }
            }
            result.success(name) 
        } ?: result.error("NODE_NOT_FOUND", "Node $name not found", null)
    }

    // --- SUPPORTING CORE METHODS ---

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        sceneView.planeRenderer.isEnabled = call.argument<Boolean>("showPlanes") ?: true
        handleShowWorldOrigin(call.argument<Boolean>("showWorldOrigin") ?: false)
        result.success(null)
    }

    private fun updatePlanes(frame: Frame) {
        frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
            if (plane.trackingState == TrackingState.TRACKING) {
                val planeMap = serializePlane(plane)
                val method = if (detectedPlanes.add(plane)) "onPlaneDetected" else "onPlaneUpdated"
                activity.runOnUiThread { if (!isDestroyed) sessionChannel.invokeMethod(method, planeMap) }
            }
        }
    }

    private fun updatePointCloud(frame: Frame) {
        val pointCloud = frame.acquirePointCloud()
        try {
            if (pointCloud.timestamp != lastPointCloudTimestamp) {
                lastPointCloudTimestamp = pointCloud.timestamp
                val ids = pointCloud.ids; val points = pointCloud.points; val count = ids.limit()
                val currentIds = (0 until count).map { ids[it] }.toSet()
                
                pointCloudNodes.removeAll { node ->
                    if (!currentIds.contains(node.id)) { sceneView.removeChildNode(node); pointCloudNodePool.add(node); true } else false
                }

                for (i in 0 until count) {
                    if (pointCloudNodes.size >= maxPoints || points[i*4+3] < minConfidence) continue
                    val id = ids[i]
                    pointCloudNodes.find { it.id == id }?.apply { 
                        position = Position(points[i*4], points[i*4+1], points[i*4+2]) 
                    } ?: run {
                        val node = pointCloudNodePool.removeLastOrNull() ?: getPointCloudModelInstance()?.let { PointCloudNode(it, id, points[i*4+3]) }
                        node?.let { 
                            it.id = id; it.isVisible = showPointCloud; it.position = Position(points[i*4], points[i*4+1], points[i*4+2])
                            sceneView.addChildNode(it); pointCloudNodes.add(it)
                        }
                    }
                }
            }
        } finally { pointCloud.release() }
    }

    private fun handleAddNode(nodeData: Map<String, Any>, result: MethodChannel.Result) {
        mainScope.launch { buildModelNode(nodeData)?.let { n -> sceneView.addChildNode(n); n.name?.let { nodesMap[it] = n }; result.success(true) } ?: result.success(false) }
    }

    private suspend fun buildModelNode(nodeData: Map<String, Any>): ModelNode? {
        var uri = nodeData["uri"] as? String ?: return null
        val type = nodeData["type"] as Int
        if (type == 0) uri = FlutterInjector.instance().flutterLoader().getLookupKeyForAsset(uri)
        else if (type == 3) uri = viewContext.applicationInfo.dataDir + "/app_flutter/" + uri
        
        return try {
            sceneView.modelLoader.loadModelInstance(uri)?.let { inst ->
                ModelNode(inst).apply {
                    name = nodeData["name"] as? String
                    val transform = nodeData["transformation"] as? ArrayList<Double>
                    transform?.let { val s = it[0].toFloat(); scale = Scale(s, s, s) }
                }
            }
        } catch (e: Exception) { null }
    }

    // Anchor & Cloud Anchor Logic
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
        sceneView.session?.let { s -> s.configure(s.config.apply { cloudAnchorMode = Config.CloudAnchorMode.ENABLED }); result.success(null) } ?: result.error("ERR", "No session", null)
    }

    private fun handleUploadAnchor(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name"); anchorNodesMap[name]?.let { node ->
            val cloud = CloudAnchorNode(sceneView.engine, node.anchor!!)
            cloud.host(sceneView.session!!) { id, state -> if (state == Anchor.CloudAnchorState.SUCCESS) result.success(id) else result.error("ERR", state.name, null) }
            sceneView.addChildNode(cloud)
        } ?: result.error("ERR", "Missing anchor", null)
    }

    private fun handleDownloadAnchor(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("cloudanchorid") ?: return result.error("ERR", "No ID", null)
        CloudAnchorNode.resolve(sceneView.engine, sceneView.session!!, id) { state, node ->
            if (!state.isError && node != null) { sceneView.addChildNode(node); result.success(true) } else result.error("ERR", state.name, null)
        }
    }

    // Boilerplate Getters
    private fun handleGetCameraPose(result: MethodChannel.Result) {
        val pose = FloatArray(16); currentArFrame?.camera?.displayOrientedPose?.toMatrix(pose, 0); result.success(pose.map { it.toDouble() })
    }

    private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
        val proj = FloatArray(16); currentArFrame?.camera?.getProjectionMatrix(proj, 0, 0.01f, 100f); result.success(proj.map { it.toDouble() })
    }

    private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
        currentArFrame?.camera?.imageIntrinsics?.let { i ->
            result.success(mapOf("fx" to i.focalLength[0].toDouble(), "fy" to i.focalLength[1].toDouble(), "cx" to i.principalPoint[0].toDouble(), "cy" to i.principalPoint[1].toDouble(), "width" to i.imageDimensions[0].toDouble(), "height" to i.imageDimensions[1].toDouble()))
        } ?: result.error("ERR", "No intrinsics", null)
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

    private fun handleHitTest(call: MethodCall, result: MethodChannel.Result) {
        val x = call.argument<Double>("x")?.toFloat() ?: (sceneView.width / 2f)
        val y = call.argument<Double>("y")?.toFloat() ?: (sceneView.height / 2f)
        currentArFrame?.let { val hits = it.hitTest(x, y); result.success(hits.map { h -> serializeHitResult(h) }) } ?: result.error("ERR", "No frame", null)
    }

    private fun handleGetAnchorPose(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("anchorId"); val anchor = sceneView.session?.allAnchors?.find { it.cloudAnchorId == id } ?: anchorNodesMap[id]?.anchor
        anchor?.let { val m = FloatArray(16); it.pose.toMatrix(m, 0); result.success(m.map { it.toDouble() }) } ?: result.error("ERR", "Not found", null)
    }

    private fun serializePlane(plane: Plane): Map<String, Any> {
        val matrix = FloatArray(16); plane.centerPose.toMatrix(matrix, 0)
        return mapOf("identifier" to plane.hashCode().toString(), "centerPose" to matrix.map { it.toDouble() }, "extent" to listOf(plane.extentX.toDouble(), plane.extentZ.toDouble()))
    }

    private fun processPendingHits(frame: Frame) {
        while (!pendingHitTests.isEmpty()) {
            val req = pendingHitTests.poll() ?: break
            frame.hitTest(req.x, req.y).firstOrNull { it.trackable is Plane || it.trackable is com.google.ar.core.Point }?.let { hit ->
                val node = AnchorNode(sceneView.engine, hit.createAnchor())
                sceneView.addChildNode(node); mainScope.launch { buildModelNode(req.nodeData)?.let { m -> node.addChildNode(m); req.result.success(true) } ?: req.result.success(false) }
            }
        }
    }

    private fun handleAddNodeToPlaneAnchor(call: MethodCall, result: MethodChannel.Result) {
        val data = call.arguments as? Map<String, Any>; val nData = data?.get("node") as? Map<String, Any>; val aData = data?.get("anchor") as? Map<String, Any>
        anchorNodesMap[aData?.get("name") as? String]?.let { anchorNode ->
            mainScope.launch { buildModelNode(nData!!)?.let { n -> anchorNode.addChildNode(n); n.name?.let { nodesMap[it] = n }; result.success(true) } ?: result.success(false) }
        } ?: result.success(false)
    }

    private fun handleAddNodeToScreenPosition(call: MethodCall, result: MethodChannel.Result) {
        val nodeData = call.arguments as? Map<String, Any> ?: return result.error("ERR", "No data", null)
        val pos = call.argument<Map<String, Double>>("screenPosition") ?: return result.error("ERR", "No pos", null)
        pendingHitTests.add(PendingHitTest(pos["x"]!!.toFloat(), pos["y"]!!.toFloat(), nodeData, result))
    }

    private fun getPointCloudModelInstance(): ModelInstance? {
        if (pointCloudModelInstances.isEmpty()) { pointCloudModelInstances = sceneView.modelLoader.createInstancedModel("models/point_cloud.glb", maxPoints).toMutableList() }
        return pointCloudModelInstances.removeLastOrNull()
    }

    private fun handleShowWorldOrigin(show: Boolean) {
        if (show && worldOriginNode == null) {
            val loader = MaterialLoader(sceneView.engine, viewContext)
            worldOriginNode = Node(sceneView.engine).apply {
                addChildNode(CylinderNode(sceneView.engine, radius = 0.005f, height = 0.1f, materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(1f, 0f, 0f, 1f))))
            }
            sceneView.addChildNode(worldOriginNode!!)
        } else if (!show) { worldOriginNode?.let { sceneView.removeChildNode(it) }; worldOriginNode = null }
    }

    private fun handleTransformNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name"); val t = call.argument<ArrayList<Double>>("transformation")
        nodesMap[name]?.apply { transform(dev.romainguy.kotlin.math.Mat4.of(*t!!.map { it.toFloat() }.toFloatArray())); result.success(null) } ?: result.error("ERR", "Not found", null)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (!isDestroyed) {
            if (event == Lifecycle.Event.ON_DESTROY) dispose()
            else lifecycleRegistry.handleLifecycleEvent(event)
        }
    }

    override fun getView(): View = rootLayout

    override fun dispose() {
        if (isDestroyed) return
        isDestroyed = true
        mainScope.cancel()
        activity.runOnUiThread {
            activityLifecycle.removeObserver(this@ArView)
            sceneView.onSessionUpdated = null
            sceneView.session?.pause()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            sessionChannel.setMethodCallHandler(null)
            objectChannel.setMethodCallHandler(null)
            anchorChannel.setMethodCallHandler(null)
            rootLayout.removeAllViews()
        }
    }
}