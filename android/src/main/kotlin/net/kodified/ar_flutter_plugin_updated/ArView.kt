package net.kodified.ar_flutter_plugin_updated

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
    @Volatile private var isProcessingFrame = false
    @Volatile private var isCenterHitTrackingEnabled = false
    @Volatile private var isCapturingBundle = false

    private var currentArFrame: Frame? = null
    private val detectedPlanes = mutableSetOf<Plane>()
    
    private data class PendingHitTest(
        val x: Float, val y: Float, val nodeData: Map<String, Any>, val result: MethodChannel.Result
    )
    private val pendingHitTests = ConcurrentLinkedQueue<PendingHitTest>()

    private var pointCloudModelInstances = mutableListOf<ModelInstance>()
    private val pointCloudNodes = mutableListOf<PointCloudNode>()
    private val pointCloudNodePool = ArrayList<PointCloudNode>()
    private var showPointCloud = false

    private var lastPointCloudTimestamp: Long? = null
    private var minConfidence = 0.1f
    private var maxPoints = 500
    private var latestLightEstimate: LightEstimate? = null

    // 🎯 Performance Tuning for Bridge Stability
    private var lastFrameTime: Long = 0
    private val throttleInterval = 33L 

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // --- Method Channel Handlers ---

    private val onSessionMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) { result.error("DESTROYED", "View is destroyed", null); return@MethodCallHandler }
        when (call.method) {
            "init" -> handleInit(call, result)
            "showPlanes" -> handleShowPlanes(call, result)
            "showFeaturePoints" -> handleShowFeaturePoints(call, result)
            "showPointCloud" -> handleShowPointCloud(call, result)
            "hidePointCloud" -> handleShowPointCloud(call, result)
            "dispose" -> dispose()
            "getAnchorPose" -> handleGetAnchorPose(call, result)
            "getCameraPose" -> handleGetCameraPose(result)
            "getProjectionMatrix" -> handleGetProjectionMatrix(result)
            "getLightEstimate" -> handleGetLightEstimate(result)
            "snapshot" -> handleSnapshot(result)
            "disableCamera" -> handleDisableCamera(result)
            "enableCamera" -> handleEnableCamera(result)
            "hitTest" -> handleHitTest(call, result)
            "getImageIntrinsics" -> handleGetImageIntrinsics(result)
            "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
            "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
            "captureBundle" -> handleCaptureBundle(result)
            else -> result.notImplemented()
        }
    }

    private val onObjectMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) { result.error("DESTROYED", "View is destroyed", null); return@MethodCallHandler }
        when (call.method) {
            "addNode" -> {
                val nodeData = call.arguments as? Map<String, Any>
                nodeData?.let { handleAddNode(it, result) } ?: result.error("INVALID_ARGUMENTS", "Node data is required", null)
            }
            "addNodeToPlaneAnchor" -> handleAddNodeToPlaneAnchor(call, result)
            "addNodeToScreenPosition" -> handleAddNodeToScreenPosition(call, result)
            "removeNode" -> handleRemoveNode(call, result)
            "transformationChanged" -> handleTransformNode(call, result)
            else -> result.notImplemented()
        }
    }

    private val onAnchorMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) { result.error("DESTROYED", "View is destroyed", null); return@MethodCallHandler }
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

        sceneView = ARSceneView(
            context = viewContext,
            sharedLifecycle = lifecycleRegistry, 
            sessionConfiguration = { session, config ->
                config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    depthMode = Config.DepthMode.DISABLED 
                    instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                    focusMode = Config.FocusMode.AUTO
                }
            }
        )

        rootLayout.addView(sceneView)
        sessionChannel.setMethodCallHandler(onSessionMethodCall)
        objectChannel.setMethodCallHandler(onObjectMethodCall)
        anchorChannel.setMethodCallHandler(onAnchorMethodCall)
        
        if (activityLifecycle.currentState == Lifecycle.State.RESUMED) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        setupSceneViewListeners()
    }

    private fun setupSceneViewListeners() {
        sceneView.onSessionUpdated = sessionUpdated@{ session, frame ->
            currentArFrame = frame
            if (isSessionPaused || isDestroyed || isCapturingBundle) return@sessionUpdated
            
            val camera = frame.camera
            val now = System.currentTimeMillis()

            // 🎯 THE UNIFIED TELEMETRY PACKET (Resolves visibility, math, and UI lag)
            if (isCenterHitTrackingEnabled && camera.trackingState == TrackingState.TRACKING) {
                if (now - lastFrameTime >= throttleInterval) {
                    lastFrameTime = now
                    
                    val centerX = sceneView.width / 2f
                    val centerY = sceneView.height / 2f
                    val hits = frame.hitTest(centerX, centerY)
                    val hit = hits.firstOrNull { it.trackable is Plane }

                    if (hit != null) {
                        val packet = mutableMapOf<String, Any>()
                        
                        // 1. Sync Camera Pose
                        val camArr = FloatArray(16)
                        camera.getDisplayOrientedPose().toMatrix(camArr, 0)
                        packet["cameraPose"] = camArr.map { it.toDouble() }

                        // 2. Sync Projection Matrix
                        val projArr = FloatArray(16)
                        camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
                        packet["projectionMatrix"] = projArr.map { it.toDouble() }

                        // 3. Serialize Hit Result
                        packet["hit"] = serializeHitResult(hit)

                        // 4. Thread-Safe Dispatch
                        activity.runOnUiThread {
                            if (!isDestroyed) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
                        }
                    }
                }
            }

            // --- Original Logic: Process Pending Hits ---
            while (!pendingHitTests.isEmpty()) {
                val request = pendingHitTests.poll() ?: break
                try {
                    val hitResults = frame.hitTest(request.x, request.y)
                    val hitResult = hitResults.firstOrNull { 
                        val t = it.trackable 
                        (t is Plane && t.trackingState == TrackingState.TRACKING) || 
                        (t is com.google.ar.core.Point && t.trackingState == TrackingState.TRACKING)
                    }
                    if (hitResult != null) {
                        val anchorNode = AnchorNode(sceneView.engine, hitResult.createAnchor())
                        sceneView.addChildNode(anchorNode)
                        mainScope.launch {
                            buildModelNode(request.nodeData)?.let { node ->
                                anchorNode.addChildNode(node)
                                request.result.success(true)
                            } ?: request.result.success(false)
                        }
                    } else { request.result.error("HIT_FAIL", "No hit", null) }
                } catch (e: Exception) { request.result.error("HIT_ERR", e.message, null) }
            }

            // --- Original Logic: Plane Updates ---
            val updatedPlanes = frame.getUpdatedTrackables(Plane::class.java)
            for (plane in updatedPlanes) {
                if (plane.trackingState == TrackingState.TRACKING) {
                    val planeMap = serializePlane(plane)
                    if (!detectedPlanes.contains(plane)) {
                        detectedPlanes.add(plane)
                        activity.runOnUiThread { 
                            rootLayout.findViewWithTag<View>("hand_motion_layout")?.let { rootLayout.removeView(it) }
                            if(!isDestroyed) sessionChannel.invokeMethod("onPlaneDetected", planeMap) 
                        }
                    } else {
                        activity.runOnUiThread { if(!isDestroyed) sessionChannel.invokeMethod("onPlaneUpdated", planeMap) }
                    }
                }
            }

            // --- Original Logic: Point Cloud pooling ---
            val pointCloud = frame.acquirePointCloud()
            try {
                if (pointCloud.timestamp != lastPointCloudTimestamp) {
                    lastPointCloudTimestamp = pointCloud.timestamp
                    val ids: IntBuffer = pointCloud.ids
                    val points: FloatBuffer = pointCloud.points
                    val currentIdSet = HashSet<Int>()
                    for(i in 0 until ids.limit()) currentIdSet.add(ids[i])

                    val iterator = pointCloudNodes.iterator()
                    while (iterator.hasNext()) {
                        val node = iterator.next()
                        if (!currentIdSet.contains(node.id)) {
                            sceneView.removeChildNode(node)
                            pointCloudNodePool.add(node)
                            iterator.remove()
                        }
                    }

                    for (i in 0 until ids.limit()) {
                        if (pointCloudNodes.size >= maxPoints) break
                        val id = ids[i]
                        val confidence = points[i * 4 + 3]
                        if (confidence < minConfidence) continue

                        val existing = pointCloudNodes.firstOrNull { it.id == id }
                        if (existing != null) {
                            existing.position = Position(points[i * 4], points[i * 4 + 1], points[i * 4 + 2])
                        } else {
                            var node = pointCloudNodePool.removeLastOrNull()
                            if (node == null) {
                                getPointCloudModelInstance()?.let { node = PointCloudNode(it, id, confidence) }
                            } else { node?.id = id }
                            node?.let {
                                it.isVisible = showPointCloud
                                it.position = Position(points[i * 4], points[i * 4 + 1], points[i * 4 + 2])
                                pointCloudNodes.add(it)
                                sceneView.addChildNode(it)
                            }
                        }
                    }
                }
            } finally { pointCloud.release() }
        }

        sceneView.onTouchEvent = { _, collisionHitResult ->
            if (isDestroyed) false
            else {
                val arHit: HitResult? = collisionHitResult as? HitResult
                if (arHit != null && arHit.trackable.trackingState == TrackingState.TRACKING) {
                    val serializedHit = serializeHitResult(arHit)
                    activity.runOnUiThread { if (!isDestroyed) sessionChannel.invokeMethod("onPlaneOrPointTap", listOf(serializedHit)) }
                    true
                } else false
            }
        }
    }

    private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
        currentArFrame?.camera?.imageIntrinsics?.let { i ->
            result.success(mapOf(
                "fx" to i.focalLength[0].toDouble(), "fy" to i.focalLength[1].toDouble(),
                "cx" to i.principalPoint[0].toDouble(), "cy" to i.principalPoint[1].toDouble(),
                "width" to i.imageDimensions[0].toDouble(), "height" to i.imageDimensions[1].toDouble(),
                "viewWidth" to sceneView.width.toDouble(), "viewHeight" to sceneView.height.toDouble()
            ))
        } ?: result.error("ERR", "No Frame", null)
    }

    private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
        sceneView.cameraNode.projectionTransform?.toMatrix()?.data?.let {
            result.success(it.map { v -> v.toDouble() })
        } ?: result.error("ERR", "Not ready", null)
    }

    private fun handleGetCameraPose(result: MethodChannel.Result) {
        val pose = sceneView.cameraNode.worldTransform.toMatrix().data
        result.success(pose.map { it.toDouble() })
    }

    private fun handleGetAnchorPose(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("anchorId")
        val anchor = sceneView.session?.allAnchors?.find { it.cloudAnchorId == id } ?: anchorNodesMap[id]?.anchor
        anchor?.let {
            val m = FloatArray(16); it.pose.toMatrix(m, 0)
            result.success(m.map { it.toDouble() })
        } ?: result.error("ERR", "Not found", null)
    }

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        try {
            val argPlaneConfig: Int? = call.argument<Int>("planeDetectionConfig")
            handlePans = call.argument<Boolean>("handlePans") ?: false
            handleRotation = call.argument<Boolean>("handleRotation") ?: false
            
            sceneView.configureSession { _, config ->
                 config.apply {
                    planeFindingMode = when (argPlaneConfig) {
                        1 -> Config.PlaneFindingMode.HORIZONTAL
                        2 -> Config.PlaneFindingMode.VERTICAL
                        3 -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        else -> Config.PlaneFindingMode.DISABLED
                    }
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                }
            }
            handleShowWorldOrigin(call.argument<Boolean>("showWorldOrigin") ?: false)
            result.success(null)
        } catch (e: Exception) { result.error("ERR", e.message, null) }
    }

    private fun handleShowPlanes(call: MethodCall, result: MethodChannel.Result) {
        sceneView.planeRenderer.isEnabled = call.argument<Boolean>("showPlanes") ?: false
        result.success(null)
    }

    private fun handleShowFeaturePoints(call: MethodCall, result: MethodChannel.Result) {
        showPointCloud = call.argument<Boolean>("show") ?: false
        pointCloudNodes.forEach { it.isVisible = showPointCloud }
        result.success(null)
    }

    private fun handleShowPointCloud(call: MethodCall, result: MethodChannel.Result) {
        showPointCloud = call.argument<Boolean>("showPointCloud") ?: true
        pointCloudNodes.forEach { it.isVisible = showPointCloud }
        result.success(null)
    }

    private fun handleHitTest(call: MethodCall, result: MethodChannel.Result) {
        val x = call.argument<Double>("x")?.toFloat() ?: (sceneView.width / 2f)
        val y = call.argument<Double>("y")?.toFloat() ?: (sceneView.height / 2f)
        result.success(currentArFrame?.hitTest(x, y)?.map { serializeHitResult(it) })
    }

    private fun handleCaptureBundle(result: MethodChannel.Result) {
        mainScope.launch {
            isCapturingBundle = true
            val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(sceneView, bitmap, { res ->
                isCapturingBundle = false
                if (res == PixelCopy.SUCCESS) {
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val cam = currentArFrame!!.camera
                    val proj = FloatArray(16); cam.getProjectionMatrix(proj, 0, 0.1f, 100f)
                    val view = FloatArray(16); cam.getViewMatrix(view, 0)
                    result.success(mapOf(
                        "image" to stream.toByteArray(), "projectionMatrix" to proj.map { it.toDouble() },
                        "viewMatrix" to view.map { it.toDouble() }, "intrinsics" to mapOf("fx" to cam.imageIntrinsics.focalLength[0].toDouble())
                    ))
                } else result.error("ERR", "Capture Fail", null)
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { res ->
            if (res == PixelCopy.SUCCESS) {
                val s = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, s)
                result.success(s.toByteArray())
            } else result.error("ERR", "Snap Fail", null)
        }, Handler(Looper.getMainLooper()))
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
                object : ModelNode(inst) {
                    override fun onMoveBegin(det: MoveGestureDetector, e: MotionEvent): Boolean {
                        if (handlePans) objectChannel.invokeMethod("onPanStart", name)
                        return handlePans && super.onMoveBegin(det, e)
                    }
                    override fun onMoveEnd(det: MoveGestureDetector, e: MotionEvent) {
                        if (handlePans) {
                            super.onMoveEnd(det, e)
                            objectChannel.invokeMethod("onPanEnd", mapOf("name" to name, "transform" to worldTransform.toMatrix().data.map { it.toDouble() }))
                        }
                    }
                }.apply {
                    isPositionEditable = handlePans; isRotationEditable = handleRotation
                    name = nodeData["name"] as? String
                    val s = transform.first().toFloat(); scale = Scale(s, s, s)
                }
            }
        } catch (e: Exception) { null }
    }

    private fun handleAddNode(nodeData: Map<String, Any>, result: MethodChannel.Result) {
        mainScope.launch {
            buildModelNode(nodeData)?.let { n ->
                sceneView.addChildNode(n); n.name?.let { nodesMap[it] = n }; result.success(true)
            } ?: result.success(false)
        }
    }

    private fun handleAddNodeToPlaneAnchor(call: MethodCall, result: MethodChannel.Result) {
        val data = call.arguments as? Map<String, Any>
        val nodeData = data?.get("node") as? Map<String, Any>
        val anchorData = data?.get("anchor") as? Map<String, Any>
        if (nodeData == null || anchorData == null) return result.success(false)
        val anchorNode = anchorNodesMap[anchorData["name"] as? String]
        if (anchorNode != null) {
            mainScope.launch {
                buildModelNode(nodeData)?.let { n ->
                    anchorNode.addChildNode(n); n.name?.let { nodesMap[it] = n }; result.success(true)
                } ?: result.success(false)
            }
        } else result.success(false)
    }

    private fun handleAddNodeToScreenPosition(call: MethodCall, result: MethodChannel.Result) {
        val nodeData = call.arguments as? Map<String, Any> ?: return result.error("ERR", "No data", null)
        val pos = call.argument<Map<String, Double>>("screenPosition") ?: return result.error("ERR", "No pos", null)
        pendingHitTests.add(PendingHitTest(pos["x"]!!.toFloat(), pos["y"]!!.toFloat(), nodeData, result))
    }

    private fun handleRemoveNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        nodesMap[name]?.let { sceneView.removeChildNode(it); nodesMap.remove(name); result.success(name) }
            ?: result.error("ERR", "Not found", null)
    }

    private fun handleTransformNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        val t = call.argument<ArrayList<Double>>("transformation")
        nodesMap[name]?.apply {
            transform(Mat4.of(*t!!.map { it.toFloat() }.toFloatArray()))
            result.success(null)
        } ?: result.error("ERR", "Not found", null)
    }

    private fun handleAddAnchor(call: MethodCall, result: MethodChannel.Result) {
        val t = call.argument<ArrayList<Double>>("transformation") ?: return result.success(false)
        val name = call.argument<String>("name") ?: "anchor"
        val (p, r) = deserializeMatrix4(t)
        val pose = Pose(floatArrayOf(p.x, p.y, p.z), floatArrayOf(r.x, r.y, r.z, r.w))
        sceneView.session?.createAnchor(pose)?.let {
            val node = AnchorNode(sceneView.engine, it)
            sceneView.addChildNode(node); anchorNodesMap[name] = node; result.success(true)
        } ?: result.success(false)
    }

    private fun handleRemoveAnchor(name: String?, result: MethodChannel.Result) {
        anchorNodesMap[name]?.let { sceneView.removeChildNode(it); it.anchor?.detach(); anchorNodesMap.remove(name); result.success(null) }
            ?: result.error("ERR", "Not found", null)
    }

    private fun handleInitGoogleCloudAnchorMode(result: MethodChannel.Result) {
        sceneView.session?.let { s -> s.configure(s.config.apply { cloudAnchorMode = Config.CloudAnchorMode.ENABLED }); result.success(null) }
            ?: result.error("ERR", "No session", null)
    }

    private fun handleUploadAnchor(call: MethodCall, result: MethodChannel.Result) {
        val node = anchorNodesMap[call.argument<String>("name")]
        if (node != null && sceneView.session != null) {
            val cloud = CloudAnchorNode(sceneView.engine, node.anchor!!)
            cloud.host(sceneView.session!!) { id, state ->
                if (state == Anchor.CloudAnchorState.SUCCESS) result.success(id) else result.error("ERR", state.name, null)
            }
            sceneView.addChildNode(cloud)
        } else result.error("ERR", "Missing session/anchor", null)
    }

    private fun handleDownloadAnchor(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("cloudanchorid") ?: return result.error("ERR", "No ID", null)
        CloudAnchorNode.resolve(sceneView.engine, sceneView.session!!, id) { state, node ->
            if (!state.isError && node != null) { sceneView.addChildNode(node); result.success(true) }
            else result.error("ERR", state.name, null)
        }
    }

    private fun handleDisableCamera(result: MethodChannel.Result) {
        isSessionPaused = true; lifecycleRegistry.currentState = Lifecycle.State.STARTED; result.success(null)
    }

    private fun handleEnableCamera(result: MethodChannel.Result) {
        isSessionPaused = false; lifecycleRegistry.currentState = Lifecycle.State.RESUMED; result.success(null)
    }

    private fun getPointCloudModelInstance(): ModelInstance? {
        if (pointCloudModelInstances.isEmpty()) {
            pointCloudModelInstances = sceneView.modelLoader.createInstancedModel("models/point_cloud.glb", maxPoints).toMutableList()
        }
        return pointCloudModelInstances.removeLastOrNull()
    }

    private fun makeWorldOriginNode(context: Context): Node {
        val loader = MaterialLoader(sceneView.engine, context)
        val root = Node(sceneView.engine)
        // 🎯 FIX: TARGETED PUBLIC CONSTRUCTOR CALL
        val x = CylinderNode(sceneView.engine, radius = 0.005f, height = 0.1f, materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(1f, 0f, 0f, 1f)))
        val y = CylinderNode(sceneView.engine, radius = 0.005f, height = 0.1f, materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(0f, 1f, 0f, 1f)))
        val z = CylinderNode(sceneView.engine, radius = 0.005f, height = 0.1f, materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(0f, 0f, 1f, 1f)))
        root.addChildNode(x); root.addChildNode(y); root.addChildNode(z)
        x.rotation = Rotation(0f, 0f, 90f); z.rotation = Rotation(90f, 0f, 0f)
        return root
    }

    private fun handleShowWorldOrigin(show: Boolean) {
        if (show && worldOriginNode == null) {
            worldOriginNode = makeWorldOriginNode(viewContext); sceneView.addChildNode(worldOriginNode!!)
        } else if (!show) {
            worldOriginNode?.let { sceneView.removeChildNode(it) }; worldOriginNode = null
        }
    }

    override fun getView(): View = rootLayout
    override fun dispose() {
        if (isDestroyed) return
        isDestroyed = true
        mainScope.launch {
            activityLifecycle.removeObserver(this@ArView)
            sceneView.onSessionUpdated = null
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            sessionChannel.setMethodCallHandler(null)
            objectChannel.setMethodCallHandler(null)
            anchorChannel.setMethodCallHandler(null)
            rootLayout.removeAllViews()
        }
    }
}