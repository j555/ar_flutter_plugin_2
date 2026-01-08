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
        val x: Float, 
        val y: Float, 
        val nodeData: Map<String, Any>, 
        val result: MethodChannel.Result
    )
    private val pendingHitTests = ConcurrentLinkedQueue<PendingHitTest>()

    private var pointCloudModelInstances = mutableListOf<ModelInstance>()
    private val pointCloudNodes = mutableListOf<PointCloudNode>()
    private val pointCloudNodePool = ArrayList<PointCloudNode>()
    private var showPointCloud = false

    private var lastPointCloudTimestamp: Long? = null
    private var minConfidence = 0.1f
    private var maxPoints = 500
    private var frameCounter = 0
    private var latestLightEstimate: LightEstimate? = null

    // 🎯 PERFORMANCE TRACKING
    private var lastFrameTime: Long = 0
    private val throttleInterval = 33L 

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (isDestroyed) return
        if (event != Lifecycle.Event.ON_DESTROY) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }
    }

    private val onSessionMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) {
            result.error("DESTROYED", "View is destroyed", null)
            return@MethodCallHandler
        }
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
            "startCenterHitTracking" -> {
                isCenterHitTrackingEnabled = true
                result.success(null)
            }
            "stopCenterHitTracking" -> {
                isCenterHitTrackingEnabled = false
                result.success(null)
            }
            "captureBundle" -> handleCaptureBundle(result)
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

            // 🎯 THE UNIFIED TELEMETRY PACKET (Resolves Finder visibility & responsiveness)
            if (isCenterHitTrackingEnabled && camera.trackingState == TrackingState.TRACKING) {
                if (now - lastFrameTime >= throttleInterval) {
                    lastFrameTime = now
                    
                    val centerX = sceneView.width / 2f
                    val centerY = sceneView.height / 2f
                    val hits = frame.hitTest(centerX, centerY)
                    val hit = hits.firstOrNull { it.trackable is Plane }

                    if (hit != null) {
                        val packet = mutableMapOf<String, Any>()
                        
                        val camArr = FloatArray(16)
                        camera.getDisplayOrientedPose().toMatrix(camArr, 0)
                        packet["cameraPose"] = camArr.map { it.toDouble() }

                        val projArr = FloatArray(16)
                        camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
                        packet["projectionMatrix"] = projArr.map { it.toDouble() }

                        packet["hit"] = serializeHitResult(hit)

                        activity.runOnUiThread {
                            if (!isDestroyed) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
                        }
                    }
                }
            }

            // Process Pending Hit Tests
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
                        val anchor = hitResult.createAnchor()
                        val anchorNode = AnchorNode(sceneView.engine, anchor)
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

            // Plane Detection
            val updatedPlanes = frame.getUpdatedTrackables(Plane::class.java)
            for (plane in updatedPlanes) {
                if (plane.trackingState == TrackingState.TRACKING) {
                    val planeMap = serializePlane(plane)
                    if (!detectedPlanes.contains(plane)) {
                        detectedPlanes.add(plane)
                        rootLayout.findViewWithTag<View>("hand_motion_layout")?.let {
                            activity.runOnUiThread { rootLayout.removeView(it) }
                        }
                        activity.runOnUiThread { if(!isDestroyed) sessionChannel.invokeMethod("onPlaneDetected", planeMap) }
                    } else {
                        activity.runOnUiThread { if(!isDestroyed) sessionChannel.invokeMethod("onPlaneUpdated", planeMap) }
                    }
                }
            }

            // Point Cloud
            val pointCloud = frame.acquirePointCloud()
            try {
                if (pointCloud.timestamp != lastPointCloudTimestamp) {
                    lastPointCloudTimestamp = pointCloud.timestamp
                    val ids: IntBuffer = pointCloud.ids
                    val points: FloatBuffer = pointCloud.points
                    val pointCount = ids.limit()
                    val currentIdSet = HashSet<Int>()
                    for(i in 0 until pointCount) currentIdSet.add(ids[i])

                    val iterator = pointCloudNodes.iterator()
                    while (iterator.hasNext()) {
                        val node = iterator.next()
                        if (!currentIdSet.contains(node.id)) {
                            sceneView.removeChildNode(node)
                            pointCloudNodePool.add(node)
                            iterator.remove()
                        }
                    }

                    for (i in 0 until pointCount) {
                        if (pointCloudNodes.size >= maxPoints) break
                        val id = ids[i]
                        val pIdx = i * 4
                        if (points[pIdx + 3] < minConfidence) continue

                        val existing = pointCloudNodes.firstOrNull { it.id == id }
                        if (existing != null) {
                            existing.position = Position(points[pIdx], points[pIdx+1], points[pIdx+2])
                            existing.isVisible = showPointCloud
                        } else {
                            var node = pointCloudNodePool.removeLastOrNull()
                            if (node == null) {
                                val modelInst = getPointCloudModelInstance() ?: break
                                node = PointCloudNode(modelInst, id, points[pIdx+3])
                            } else { node.id = id }
                            node.isVisible = showPointCloud 
                            node.position = Position(points[pIdx], points[pIdx+1], points[pIdx+2])
                            pointCloudNodes.add(node)
                            sceneView.addChildNode(node)
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
                    activity.runOnUiThread {
                        if (!isDestroyed) sessionChannel.invokeMethod("onPlaneOrPointTap", listOf(serializedHit))
                    }
                    true
                } else false
            }
        }
    }

    private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
        val frame = currentArFrame
        if (frame != null) {
            val intrinsics = frame.camera.imageIntrinsics
            val data = mapOf(
                "fx" to intrinsics.focalLength[0].toDouble(),
                "fy" to intrinsics.focalLength[1].toDouble(),
                "cx" to intrinsics.principalPoint[0].toDouble(),
                "cy" to intrinsics.principalPoint[1].toDouble(),
                "width" to intrinsics.imageDimensions[0].toDouble(),
                "height" to intrinsics.imageDimensions[1].toDouble(),
                "viewWidth" to sceneView.width.toDouble(),
                "viewHeight" to sceneView.height.toDouble()
            )
            result.success(data)
        } else result.error("NO_FRAME", "AR Frame missing", null)
    }

    private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
        val proj = sceneView.cameraNode.projectionTransform?.toMatrix()?.data
        result.success(proj?.map { it.toDouble() })
    }

    private fun handleGetCameraPose(result: MethodChannel.Result) {
        val pose = sceneView.cameraNode.worldTransform.toMatrix().data
        result.success(pose.map { it.toDouble() })
    }

    private fun serializePlane(plane: Plane): Map<String, Any> {
        val matrix = FloatArray(16)
        plane.centerPose.toMatrix(matrix, 0)
        return mapOf(
            "type" to 0, "identifier" to plane.hashCode().toString(),
            "centerPose" to matrix.map { it.toDouble() },
            "extent" to listOf(plane.extentX.toDouble(), plane.extentZ.toDouble())
        )
    }

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        try {
            val argShowAnimatedGuide = call.argument<Boolean>("showAnimatedGuide") ?: true
            val argShowPlanes = call.argument<Boolean>("showPlanes") ?: true
            val argPlaneDetectionConfig: Int? = call.argument<Int>("planeDetectionConfig")
            val showWorldOrigin = call.argument<Boolean>("showWorldOrigin") ?: false
            handlePans = call.argument<Boolean>("handlePans") ?: false
            handleRotation = call.argument<Boolean>("handleRotation") ?: false
            val argEnableDepth = call.argument<Boolean>("enableDepth") ?: false

            sceneView.configureSession { session, config ->
                 config.apply {
                    depthMode = if (argEnableDepth && session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else { Config.DepthMode.DISABLED }

                    planeFindingMode = when (argPlaneDetectionConfig) {
                        1 -> Config.PlaneFindingMode.HORIZONTAL
                        2 -> Config.PlaneFindingMode.VERTICAL
                        3 -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        else -> Config.PlaneFindingMode.DISABLED
                    }
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                }
            }
            handleShowWorldOrigin(showWorldOrigin)
            sceneView.planeRenderer.isEnabled = argShowPlanes
            sceneView.planeRenderer.isVisible = argShowPlanes
            if (argShowAnimatedGuide) {
                val guide = LayoutInflater.from(context).inflate(R.layout.sceneform_hand_layout, rootLayout, false)
                guide.tag = "hand_motion_layout"
                rootLayout.addView(guide)
            }
            result.success(null)
        } catch (e: Exception) { result.error("INIT_ERR", e.message, null) }
    }

    private fun handleDisableCamera(result: MethodChannel.Result) {
        isSessionPaused = true
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        result.success(null)
    }

    private fun handleEnableCamera(result: MethodChannel.Result) {
        isSessionPaused = false
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        result.success(null)
    }

    private fun handleCaptureBundle(result: MethodChannel.Result) {
        mainScope.launch {
            val frame = currentArFrame ?: return@launch result.error("NO_FRAME", "No frame", null)
            isCapturingBundle = true
            val wasPlaneVisible = sceneView.planeRenderer.isVisible
            sceneView.planeRenderer.isVisible = false
            val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
            Handler(Looper.getMainLooper()).postDelayed({
                if (isDestroyed) return@postDelayed
                PixelCopy.request(sceneView, bitmap, { copyResult ->
                    isCapturingBundle = false
                    sceneView.planeRenderer.isVisible = wasPlaneVisible
                    if (copyResult == PixelCopy.SUCCESS) {
                        mainScope.launch(Dispatchers.IO) {
                            val stream = java.io.ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            val camera = frame.camera
                            val proj = FloatArray(16)
                            camera.getProjectionMatrix(proj, 0, 0.01f, 100f)
                            val view = FloatArray(16)
                            camera.getViewMatrix(view, 0)
                            val payload = mapOf(
                                "image" to stream.toByteArray(),
                                "projectionMatrix" to proj.map { it.toDouble() },
                                "viewMatrix" to view.map { it.toDouble() },
                                "intrinsics" to mapOf(
                                    "fx" to camera.imageIntrinsics.focalLength[0].toDouble(),
                                    "fy" to camera.imageIntrinsics.focalLength[1].toDouble(),
                                    "cx" to camera.imageIntrinsics.principalPoint[0].toDouble(),
                                    "cy" to camera.imageIntrinsics.principalPoint[1].toDouble(),
                                    "width" to camera.imageIntrinsics.imageDimensions[0].toDouble(),
                                    "height" to camera.imageIntrinsics.imageDimensions[1].toDouble()
                                )
                            )
                            withContext(Dispatchers.Main) { result.success(payload) }
                        }
                    } else result.error("COPY_FAIL", "PixelCopy failed", null)
                }, Handler(Looper.getMainLooper()))
            }, 64) 
        }
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        if (isDestroyed || sceneView.width <= 0) return result.error("ERR", "View invalid", null)
        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                mainScope.launch(Dispatchers.IO) {
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
                }
            } else result.error("SNAP_FAIL", "Failed", null)
        }, Handler(Looper.getMainLooper()))
    }

    private suspend fun buildModelNode(nodeData: Map<String, Any>): ModelNode? {
        var fileLocation = nodeData["uri"] as? String ?: return null
        when (nodeData["type"] as Int) {
            0 -> fileLocation = FlutterInjector.instance().flutterLoader().getLookupKeyForAsset(fileLocation)
            3 -> fileLocation = viewContext.applicationInfo.dataDir + "/app_flutter/" + fileLocation
        }
        val transformation = nodeData["transformation"] as? ArrayList<Double> ?: return null
        return try {
            sceneView.modelLoader.loadModelInstance(fileLocation)?.let { instance ->
                object : ModelNode(instance) {
                    override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                        if (handlePans) objectChannel.invokeMethod("onPanStart", name)
                        return handlePans && super.onMoveBegin(detector, e)
                    }
                    override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
                        if (handlePans) {
                            super.onMoveEnd(detector, e)
                            objectChannel.invokeMethod("onPanEnd", mapOf("name" to name, "transform" to worldTransform.toMatrix().data.map { it.toDouble() }))
                        }
                    }
                }.apply {
                    isPositionEditable = handlePans
                    isRotationEditable = handleRotation
                    name = nodeData["name"] as? String
                    val scale = transformation.first().toFloat()
                    this.scale = Scale(scale, scale, scale)
                }
            }
        } catch (e: Exception) { null }
    }

    private fun handleAddNode(nodeData: Map<String, Any>, result: MethodChannel.Result) {
        mainScope.launch {
            val node = buildModelNode(nodeData)
            if (node != null) {
                sceneView.addChildNode(node)
                node.name?.let { nodesMap[it] = node }
                result.success(true)
            } else result.success(false)
        }
    }

    private fun handleRemoveNode(call: MethodCall, result: MethodChannel.Result) {
        val name = (call.arguments as? Map<String, Any>)?.get("name") as? String
        nodesMap[name]?.let {
            sceneView.removeChildNode(it)
            nodesMap.remove(name)
            result.success(name)
        } ?: result.error("NOT_FOUND", "Node not found", null)
    }

    private fun handleTransformNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name") ?: return result.error("ERR", "Missing name", null)
        val transform = call.argument<ArrayList<Double>>("transformation") ?: return result.error("ERR", "Missing matrix", null)
        nodesMap[name]?.apply {
            val matrix = Mat4.of(*transform.map { it.toFloat() }.toFloatArray())
            transform(matrix)
            result.success(null)
        } ?: result.error("NOT_FOUND", "Node missing", null)
    }

    private fun handleInitGoogleCloudAnchorMode(result: MethodChannel.Result) {
        sceneView.session?.let { session ->
            session.configure(session.config.apply { cloudAnchorMode = Config.CloudAnchorMode.ENABLED })
            result.success(null)
        } ?: result.error("FAIL", "No Session", null)
    }

    private fun handleUploadAnchor(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        val session = sceneView.session
        val anchorNode = anchorNodesMap[name]
        if (session != null && anchorNode != null) {
            val cloudNode = CloudAnchorNode(sceneView.engine, anchorNode.anchor!!)
            cloudNode.host(session) { id, state ->
                if (state == Anchor.CloudAnchorState.SUCCESS) result.success(id)
                else result.error("FAIL", state.name, null)
            }
            sceneView.addChildNode(cloudNode)
        } else result.error("FAIL", "Missing session or anchor", null)
    }

    private fun handleDownloadAnchor(call: MethodCall, result: MethodChannel.Result) {
        val cloudId = call.argument<String>("cloudanchorid") ?: return result.error("ERR", "Missing ID", null)
        val session = sceneView.session ?: return result.error("ERR", "No Session", null)
        CloudAnchorNode.resolve(sceneView.engine, session, cloudId) { state, node ->
            if (!state.isError && node != null) {
                sceneView.addChildNode(node)
                result.success(true)
            } else result.error("FAIL", state.name, null)
        }
    }

    private fun handleAddNodeToPlaneAnchor(call: MethodCall, result: MethodChannel.Result) {
        val nodeData = call.arguments as? Map<String, Any>
        val dict_node = nodeData?.get("node") as? Map<String, Any>
        val dict_anchor = nodeData?.get("anchor") as? Map<String, Any>
        if (dict_node == null || dict_anchor == null) return result.success(false)
        val anchorNode = anchorNodesMap[dict_anchor["name"] as? String]
        if (anchorNode != null) {
            mainScope.launch {
                buildModelNode(dict_node)?.let { node ->
                    anchorNode.addChildNode(node)
                    node.name?.let { nodesMap[it] = node }
                    result.success(true)
                } ?: result.success(false)
            }
        } else result.success(false)
    }

    private fun handleAddNodeToScreenPosition(call: MethodCall, result: MethodChannel.Result) {
        val nodeData = call.arguments as? Map<String, Any> ?: return result.error("ERR", "Null data", null)
        val pos = call.argument<Map<String, Double>>("screenPosition") ?: return result.error("ERR", "Null pos", null)
        pendingHitTests.add(PendingHitTest(pos["x"]!!.toFloat(), pos["y"]!!.toFloat(), nodeData, result))
    }

    private fun handleAddAnchor(call: MethodCall, result: MethodChannel.Result) {
        val transform = call.argument<ArrayList<Double>>("transformation") ?: return result.success(false)
        val name = call.argument<String>("name") ?: return result.success(false)
        val (pos, rot) = deserializeMatrix4(transform)
        val pose = Pose(floatArrayOf(pos.x, pos.y, pos.z), floatArrayOf(rot.x, rot.y, rot.z, rot.w))
        sceneView.session?.createAnchor(pose)?.let {
            val node = AnchorNode(sceneView.engine, it)
            sceneView.addChildNode(node)
            anchorNodesMap[name] = node
            result.success(true)
        } ?: result.success(false)
    }

    private fun handleRemoveAnchor(name: String?, result: MethodChannel.Result) {
        anchorNodesMap[name]?.let {
            sceneView.removeChildNode(it)
            it.anchor?.detach()
            anchorNodesMap.remove(name)
            result.success(null)
        } ?: result.error("NOT_FOUND", "Anchor missing", null)
    }

    private fun handleShowPlanes(call: MethodCall, result: MethodChannel.Result) {
        val show = call.argument<Boolean>("showPlanes") ?: false
        sceneView.planeRenderer.isEnabled = show
        sceneView.planeRenderer.isVisible = show
        result.success(null)
    }

    private fun handleShowFeaturePoints(call: MethodCall, result: MethodChannel.Result) { result.success(null) }

    private fun getPointCloudModelInstance(): ModelInstance? {
        if (pointCloudModelInstances.isEmpty()) {
            pointCloudModelInstances = sceneView.modelLoader.createInstancedModel(
                assetFileLocation = "models/point_cloud.glb", count = maxPoints
            ).toMutableList()
        }
        return pointCloudModelInstances.removeLastOrNull()
    }

    private fun handleGetLightEstimate(result: MethodChannel.Result) {
        latestLightEstimate?.let {
            if (it.state == LightEstimate.State.VALID) {
                result.success(mapOf("pixelIntensity" to it.pixelIntensity.toDouble()))
            } else result.error("INVALID", "No estimate", null)
        } ?: result.error("NULL", "No estimate", null)
    }

    private fun makeWorldOriginNode(context: Context): Node {
        val engine = sceneView.engine
        val loader = MaterialLoader(engine, context)
        val rootNode = Node(engine)
        // 🎯 FIX: Used named arguments to avoid the private internal constructor
        val xNode = CylinderNode(
            engine = engine, 
            radius = 0.005f, 
            height = 0.1f, 
            materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(1f, 0f, 0f, 1f))
        )
        val yNode = CylinderNode(
            engine = engine, 
            radius = 0.005f, 
            height = 0.1f, 
            materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(0f, 1f, 0f, 1f))
        )
        val zNode = CylinderNode(
            engine = engine, 
            radius = 0.005f, 
            height = 0.1f, 
            materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(0f, 0f, 1f, 1f))
        )
        rootNode.addChildNode(xNode); rootNode.addChildNode(yNode); rootNode.addChildNode(zNode)
        xNode.rotation = Rotation(0f, 0f, 90f); zNode.rotation = Rotation(90f, 0f, 0f)
        return rootNode
    }

    private fun handleShowWorldOrigin(show: Boolean) {
        if (show && worldOriginNode == null) {
            worldOriginNode = makeWorldOriginNode(viewContext)
            sceneView.addChildNode(worldOriginNode!!)
        } else if (!show) {
            worldOriginNode?.let { sceneView.removeChildNode(it) }
            worldOriginNode = null
        }
    }

    override fun getView(): View = rootLayout
    override fun dispose() {
        if (isDestroyed) return
        isDestroyed = true
        Log.i(TAG, "dispose")
        try {
            activityLifecycle.removeObserver(this)
            sceneView.onSessionUpdated = null
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            sessionChannel.setMethodCallHandler(null)
            objectChannel.setMethodCallHandler(null)
            anchorChannel.setMethodCallHandler(null)
            rootLayout.removeAllViews()
        } catch(e: Exception) { Log.e(TAG, "Dispose error", e) }
    }
}