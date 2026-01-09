package net.kodified.ar_flutter_plugin_updated

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.ar.core.*
import net.kodified.ar_flutter_plugin_updated.Serialization.Deserializers.deserializeMatrix4
import net.kodified.ar_flutter_plugin_updated.Serialization.Serialization.serializeAnchor
import net.kodified.ar_flutter_plugin_updated.Serialization.Serialization.serializeHitResult
import io.flutter.FlutterInjector
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.canHostCloudAnchor
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.node.CloudAnchorNode
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.collision.HitResult as CollisionHitResult
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.toMatrix
import dev.romainguy.kotlin.math.*
import io.github.sceneview.SceneView
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.IntBuffer
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
    private val viewContext: Context = context
    private var sceneView: ARSceneView
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var worldOriginNode: Node? = null
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val rootLayout: ViewGroup = FrameLayout(context)

    private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    private val objectChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorChannel = MethodChannel(messenger, "aranchors_$id")

    private val nodesMap = mutableMapOf<String, ModelNode>()
    private val anchorNodesMap = mutableMapOf<String, AnchorNode>()
    private var handlePans = false
    private var handleRotation = false
    private var isSessionPaused = false
    
    @Volatile private var isDestroyed = false
    @Volatile private var isCenterHitTrackingEnabled = false
    @Volatile private var isCapturingBundle = false

    private var currentArFrame: Frame? = null
    private val detectedPlanes = mutableSetOf<Plane>()
    private val pendingHitTests = ConcurrentLinkedQueue<PendingHitTest>()

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

    // --- 1. CHANNEL HANDLERS ---

    private val onSessionMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) return@MethodCallHandler
        when (call.method) {
            "init" -> handleInit(call, result)
            "showPlanes" -> handleShowPlanes(call, result)
            "showFeaturePoints" -> { showPointCloud = call.argument<Boolean>("show") ?: false; result.success(null) }
            "showPointCloud" -> { showPointCloud = call.argument<Boolean>("showPointCloud") ?: true; result.success(null) }
            "dispose" -> dispose()
            "getAnchorPose" -> handleGetAnchorPose(call, result)
            "getCameraPose" -> handleGetCameraPose(result)
            "getProjectionMatrix" -> handleGetProjectionMatrix(result)
            "getImageIntrinsics" -> handleGetImageIntrinsics(result)
            "snapshot" -> handleSnapshot(result)
            "disableCamera" -> { isSessionPaused = true; result.success(null) }
            "enableCamera" -> { isSessionPaused = false; result.success(null) }
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

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (isDestroyed) return
        if (event != Lifecycle.Event.ON_DESTROY) lifecycleRegistry.handleLifecycleEvent(event)
    }

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        activityLifecycle.addObserver(this)
        sceneView = ARSceneView(viewContext, null).apply {
            lifecycle = lifecycleRegistry
            sessionConfiguration = { session, config ->
                config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
            }
        }
        rootLayout.addView(sceneView)
        sessionChannel.setMethodCallHandler(onSessionMethodCall)
        objectChannel.setMethodCallHandler(onObjectMethodCall)
        anchorChannel.setMethodCallHandler(onAnchorMethodCall)
        if (activityLifecycle.currentState == Lifecycle.State.RESUMED) lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        setupSceneViewListeners()
    }

    private fun setupSceneViewListeners() {
        sceneView.onSessionUpdated = { _, frame ->
            if (!isSessionPaused && !isDestroyed && !isCapturingBundle) {
                currentArFrame = frame
                val camera = frame.camera
                val now = System.currentTimeMillis()

                if (isCenterHitTrackingEnabled && camera.trackingState == TrackingState.TRACKING) {
                    if (now - lastFrameTime >= throttleInterval) {
                        lastFrameTime = now
                        val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
                        val planeHit = hits.firstOrNull { it.trackable is Plane }
                        val pointHit = if (planeHit == null) hits.firstOrNull { it.trackable is com.google.ar.core.Point } else null
                        val hit = planeHit ?: pointHit

                        val packet = mutableMapOf<String, Any>()
                        val camPose = camera.getDisplayOrientedPose()
                        val camArr = FloatArray(16); camPose.toMatrix(camArr, 0)
                        val projArr = FloatArray(16); camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
                        
                        packet["cameraPose"] = camArr.map { it.toDouble() }
                        packet["projectionMatrix"] = projArr.map { it.toDouble() }

                        if (hit != null) {
                            packet["hit"] = serializeHitResult(hit)
                            packet["hitType"] = if (hit.trackable is Plane) "PLANE" else "POINT"
                            
                            val hp = hit.hitPose
                            val dx = hp.tx() - camPose.tx()
                            val dy = hp.ty() - camPose.ty()
                            val dz = hp.tz() - camPose.tz()
                            packet["distance"] = sqrt((dx * dx + dy * dy + dz * dz).toDouble())

                            // Calculate normal from hitPose orientation
                            val normal = hp.yAxis
                            packet["normal"] = normal.map { it.toDouble() }
                            
                            val tilt = acos(kotlin.math.abs(normal[1]).toDouble()) * (180.0 / kotlin.math.PI)
                            packet["wallTilt"] = 90.0 - tilt

                            if (hit.trackable is Plane) packet["surfaceType"] = (hit.trackable as Plane).type.name
                        } else { packet["hitType"] = "NONE" }

                        packet["trackingState"] = camera.trackingState.name
                        activity.runOnUiThread { if (!isDestroyed) sessionChannel.invokeMethod("onUnifiedUpdate", packet) }
                    }
                }
                updatePlanes(frame)
                updatePointCloud(frame)
                processPendingHits(frame)
            }
        }

        sceneView.onTouchEvent = { _, res ->
            val arHit = res as? HitResult
            if (arHit != null && !isDestroyed) {
                val map = serializeHitResult(arHit)
                activity.runOnUiThread { sessionChannel.invokeMethod("onPlaneOrPointTap", listOf(map)) }
                true
            } else false
        }
    }

    private fun updatePlanes(frame: Frame) {
        val updatedPlanes: Collection<Plane> = frame.getUpdatedTrackables(Plane::class.java)
        for (plane in updatedPlanes) {
            if (plane.trackingState == TrackingState.TRACKING) {
                val planeMap = serializePlane(plane)
                if (!detectedPlanes.contains(plane)) {
                    detectedPlanes.add(plane)
                    activity.runOnUiThread { if (!isDestroyed) sessionChannel.invokeMethod("onPlaneDetected", planeMap) }
                } else {
                    activity.runOnUiThread { if (!isDestroyed) sessionChannel.invokeMethod("onPlaneUpdated", planeMap) }
                }
            }
        }
    }

    private fun updatePointCloud(frame: Frame) {
        if (System.currentTimeMillis() % 2L != 0L) return
        val pointCloud = frame.acquirePointCloud()
        try {
            if (pointCloud.timestamp != lastPointCloudTimestamp) {
                lastPointCloudTimestamp = pointCloud.timestamp
                val ids = pointCloud.ids; val points = pointCloud.points; val count = ids.limit()
                val currentIdSet = HashSet<Int>(); for(i in 0 until count) currentIdSet.add(ids[i])
                val iterator = pointCloudNodes.iterator()
                while (iterator.hasNext()) {
                    val node = iterator.next()
                    if (!currentIdSet.contains(node.id)) { sceneView.removeChildNode(node); pointCloudNodePool.add(node); iterator.remove() }
                }
                for (i in 0 until count) {
                    if (pointCloudNodes.size >= maxPoints || points[i * 4 + 3] < minConfidence) continue
                    val id = ids[i]
                    val existing = pointCloudNodes.firstOrNull { it.id == id }
                    if (existing != null) { existing.position = Position(points[i * 4], points[i * 4 + 1], points[i * 4 + 2]) }
                    else {
                        var node = pointCloudNodePool.removeLastOrNull()
                        if (node == null) { getPointCloudModelInstance()?.let { node = PointCloudNode(it, id, points[i*4+3]) } } else { node?.id = id }
                        node?.let { it.isVisible = showPointCloud; it.position = Position(points[i * 4], points[i * 4 + 1], points[i * 4 + 2]); pointCloudNodes.add(it); sceneView.addChildNode(it) }
                    }
                }
            }
        } finally { pointCloud.release() }
    }

    private fun handleGetAnchorPose(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("anchorId"); val anchor = sceneView.session?.allAnchors?.find { it.cloudAnchorId == id } ?: anchorNodesMap[id]?.anchor; anchor?.let { val m = FloatArray(16); it.pose.toMatrix(m, 0); result.success(m.map { it.toDouble() }) } ?: result.error("ERR", "Not found", null)
    }

    private fun handleRemoveAnchor(name: String?, result: MethodChannel.Result) {
        anchorNodesMap[name]?.let { 
            sceneView.removeChildNode(it)
            it.anchor?.detach()
            anchorNodesMap.remove(name)
            result.success(null) 
        } ?: result.error("ERR", "Anchor missing", null)
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        if (isDestroyed || sceneView.width <= 0) return result.error("ERR", "Invalid view", null)
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

    private fun serializePlane(plane: Plane): Map<String, Any> {
        val matrix = FloatArray(16); plane.centerPose.toMatrix(matrix, 0)
        return mapOf("identifier" to plane.hashCode().toString(), "centerPose" to matrix.map { it.toDouble() }, "extent" to listOf(plane.extentX.toDouble(), plane.extentZ.toDouble()))
    }

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        handlePans = call.argument<Boolean>("handlePans") ?: false
        handleRotation = call.argument<Boolean>("handleRotation") ?: false
        sceneView.planeRenderer.isEnabled = call.argument<Boolean>("showPlanes") ?: true
        result.success(null)
    }

    private fun handleShowPlanes(call: MethodCall, result: MethodChannel.Result) {
        sceneView.planeRenderer.isEnabled = call.argument<Boolean>("showPlanes") ?: false
        result.success(null)
    }

    private fun processPendingHits(frame: Frame) {
        while (!pendingHitTests.isEmpty()) {
            val req = pendingHitTests.poll() ?: break
            val hits = frame.hitTest(req.x, req.y)
            hits.firstOrNull { it.trackable is Plane || it.trackable is com.google.ar.core.Point }?.let { hit ->
                val node = AnchorNode(sceneView.engine, hit.createAnchor())
                sceneView.addChildNode(node)
                mainScope.launch { buildModelNode(req.nodeData)?.let { m -> node.addChildNode(m); req.result.success(true) } ?: req.result.success(false) }
            }
        }
    }

    private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
        currentArFrame?.camera?.imageIntrinsics?.let { i ->
            result.success(mapOf("fx" to i.focalLength[0].toDouble(), "fy" to i.focalLength[1].toDouble(), "cx" to i.principalPoint[0].toDouble(), "cy" to i.principalPoint[1].toDouble(), "width" to i.imageDimensions[0].toDouble(), "height" to i.imageDimensions[1].toDouble()))
        } ?: result.error("ERR", "No intrinsics", null)
    }

    private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
        val proj = FloatArray(16); currentArFrame?.camera?.getProjectionMatrix(proj, 0, 0.01f, 100f)
        result.success(proj.map { it.toDouble() })
    }

    private fun handleGetCameraPose(result: MethodChannel.Result) {
        val pose = FloatArray(16); currentArFrame?.camera?.displayOrientedPose?.toMatrix(pose, 0)
        result.success(pose.map { it.toDouble() })
    }

    private fun handleCaptureBundle(result: MethodChannel.Result) {
        mainScope.launch(Dispatchers.Main) {
            val frame = currentArFrame ?: return@launch result.error("ERR", "No frame", null)
            isCapturingBundle = true
            val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(sceneView, bitmap, { copyResult ->
                isCapturingBundle = false
                if (copyResult == PixelCopy.SUCCESS) {
                    mainScope.launch(Dispatchers.IO) {
                        val byteStream = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream)
                        val camera = frame.camera; val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, 0.01f, 100.0f); val view = FloatArray(16); camera.getViewMatrix(view, 0)
                        val data = mutableMapOf<String, Any>("image" to byteStream.toByteArray(), "projectionMatrix" to proj.map { it.toDouble() }, "viewMatrix" to view.map { it.toDouble() })
                        withContext(Dispatchers.Main) { result.success(data) }
                    }
                } else result.error("ERR", "Copy fail", null)
            }, Handler(Looper.getMainLooper()))
        }
    }

    private suspend fun buildModelNode(nodeData: Map<String, Any>): ModelNode? {
        var uri = nodeData["uri"] as? String ?: return null
        when (nodeData["type"] as Int) {
            0 -> uri = FlutterInjector.instance().flutterLoader().getLookupKeyForAsset(uri)
            3 -> uri = viewContext.applicationInfo.dataDir + "/app_flutter/" + uri
        }
        val transform = nodeData["transformation"] as? ArrayList<Double> ?: return null
        return try {
            sceneView.modelLoader.loadModelInstance(uri)?.let { inst ->
                ModelNode(inst).apply {
                    name = nodeData["name"] as? String
                    val scaleVal = transform.first().toFloat(); scale = Scale(scaleVal, scaleVal, scaleVal)
                }
            }
        } catch (e: Exception) { null }
    }

    private fun handleAddNode(nodeData: Map<String, Any>, result: MethodChannel.Result) {
        mainScope.launch {
            buildModelNode(nodeData)?.let { n -> sceneView.addChildNode(n); n.name?.let { nodesMap[it] = n }; result.success(true) } ?: result.success(false)
        }
    }

    private fun handleRemoveNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name"); nodesMap[name]?.let { sceneView.removeChildNode(it); nodesMap.remove(name); result.success(name) } ?: result.error("ERR", "Not found", null)
    }

    private fun handleTransformNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name"); val t = call.argument<ArrayList<Double>>("transformation"); nodesMap[name]?.apply { transform(Mat4.of(*t!!.map { it.toFloat() }.toFloatArray())); result.success(null) } ?: result.error("ERR", "Not found", null)
    }

    private fun handleAddNodeToPlaneAnchor(call: MethodCall, result: MethodChannel.Result) {
        val data = call.arguments as? Map<String, Any>; val nData = data?.get("node") as? Map<String, Any>; val aData = data?.get("anchor") as? Map<String, Any>
        anchorNodesMap[aData?.get("name") as? String]?.let { anchorNode ->
            mainScope.launch { buildModelNode(nData!!)?.let { n -> anchorNode.addChildNode(n); n.name?.let { nodesMap[it] = n }; result.success(true) } ?: result.success(false) }
        } ?: result.success(false)
    }

    private fun handleAddNodeToScreenPosition(call: MethodCall, result: MethodChannel.Result) {
        val nodeData = call.arguments as? Map<String, Any> ?: return result.error("ERR", "No data", null); val pos = call.argument<Map<String, Double>>("screenPosition") ?: return result.error("ERR", "No pos", null)
        pendingHitTests.add(PendingHitTest(pos["x"]!!.toFloat(), pos["y"]!!.toFloat(), nodeData, result))
    }

    private fun handleAddAnchor(call: MethodCall, result: MethodChannel.Result) {
        val t = call.argument<ArrayList<Double>>("transformation") ?: return result.success(false)
        val (p, r) = deserializeMatrix4(t)
        val pose = Pose(floatArrayOf(p.x, p.y, p.z), floatArrayOf(r.x, r.y, r.z, r.w))
        sceneView.session?.createAnchor(pose)?.let {
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

    private fun getPointCloudModelInstance(): ModelInstance? {
        if (pointCloudModelInstances.isEmpty()) { pointCloudModelInstances = sceneView.modelLoader.createInstancedModel("models/point_cloud.glb", maxPoints).toMutableList() }
        return pointCloudModelInstances.removeLastOrNull()
    }

    private fun makeWorldOriginNode(context: Context): Node {
        val loader = MaterialLoader(sceneView.engine, context)
        val root = Node(sceneView.engine)
        // Public API fix for CylinderNode
        val cylinder = CylinderNode(sceneView.engine, radius = 0.005f, height = 0.1f, materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(1f, 0f, 0f, 1f)))
        root.addChildNode(cylinder); return root
    }

    private fun handleShowWorldOrigin(show: Boolean) {
        if (show && worldOriginNode == null) { worldOriginNode = makeWorldOriginNode(viewContext); sceneView.addChildNode(worldOriginNode!!) } else if (!show) { worldOriginNode?.let { sceneView.removeChildNode(it) }; worldOriginNode = null }
    }

    override fun getView(): View = rootLayout
    override fun dispose() {
        if (isDestroyed) return
        isDestroyed = true
        mainScope.launch {
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