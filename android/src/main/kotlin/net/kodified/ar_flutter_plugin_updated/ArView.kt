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

    // Internal LifecycleRegistry to manually control ARSceneView's lifecycle
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
    
    @Volatile
    private var isDestroyed = false
    
    @Volatile
    private var isProcessingFrame = false

    // Control flag for continuous center hit tracking
    @Volatile
    private var isCenterHitTrackingEnabled = false

    @Volatile
    private var isCapturingBundle = false

    // FIX: Store the latest frame here so we can access it on demand
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
    private var latestLightEstimate: LightEstimate? = null

    // Performance tracking
    private var lastFrameTime: Long = 0
    private val throttleInterval = 33L // ~30fps bridge limit

    // --- LifecycleOwner Implementation ---
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry


    // --- LifecycleEventObserver Implementation ---
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (isDestroyed) return
        
        // Mirror the activity's lifecycle to our internal registry.
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

    private val onObjectMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) {
            result.error("DESTROYED", "View is destroyed", null)
            return@MethodCallHandler
        }
        when (call.method) {
            "addNode" -> {
                val nodeData = call.arguments as? Map<String, Any>
                nodeData?.let { handleAddNode(it, result) }
                    ?: result.error("INVALID_ARGUMENTS", "Node data is required", null)
            }
            "addNodeToPlaneAnchor" -> handleAddNodeToPlaneAnchor(call, result)
            "addNodeToScreenPosition" -> handleAddNodeToScreenPosition(call, result)
            "removeNode" -> handleRemoveNode(call, result)
            "transformationChanged" -> handleTransformNode(call, result)
            else -> result.notImplemented()
        }
    }

    private val onAnchorMethodCall = MethodChannel.MethodCallHandler { call, result ->
        if (isDestroyed) {
            result.error("DESTROYED", "View is destroyed", null)
            return@MethodCallHandler
        }
        when (call.method) {
            "addAnchor" -> handleAddAnchor(call, result)
            "removeAnchor" -> {
                val anchorName = call.argument<String>("name")
                handleRemoveAnchor(anchorName, result)
            }
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
                    
                    // Enable Depth API for Occlusion and Precision
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }
                    
                    // Enable Environmental HDR for perfecta Lighting directionality
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
            }
        }

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
        sceneView.onSessionUpdated = { session, frame ->
            if (isSessionPaused || isDestroyed || isCapturingBundle) {
                currentArFrame = null
                // Return unit
            } else {
                currentArFrame = frame
                val camera = frame.camera
                val now = System.currentTimeMillis()

                // 🎯 THE PERFECT UNIFIED TELEMETRY PACKET (Integrated requested features)
                if (isCenterHitTrackingEnabled && camera.trackingState == TrackingState.TRACKING) {
                    if (now - lastFrameTime >= throttleInterval) {
                        lastFrameTime = now
                        
                        val centerX = sceneView.width / 2f
                        val centerY = sceneView.height / 2f
                        val hits = frame.hitTest(centerX, centerY)
                        
                        // Fallback: Check Plane first, then Point (allows faster scanning)
                        val planeHit = hits.firstOrNull { it.trackable is Plane }
                        val pointHit = if (planeHit == null) hits.firstOrNull { it.trackable is com.google.ar.core.Point } else null
                        val finalHit = planeHit ?: pointHit

                        if (finalHit != null) {
                            val packet = mutableMapOf<String, Any>()
                            
                            // 1. Hardware Transforms
                            val camArr = FloatArray(16)
                            camera.getDisplayOrientedPose().toMatrix(camArr, 0)
                            packet["cameraPose"] = camArr.map { it.toDouble() }

                            val projArr = FloatArray(16)
                            camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
                            packet["projectionMatrix"] = projArr.map { it.toDouble() }

                            // 2. Hit Data
                            packet["hit"] = serializeHitResult(finalHit)
                            packet["hitType"] = if (finalHit.trackable is Plane) "PLANE" else "POINT"

                            // 3. Environmental HDR (Lighting Spherical Harmonics)
                            frame.lightEstimate?.let { le ->
                                if (le.state == LightEstimate.State.VALID) {
                                    try {
                                        // 🎯 FIX: Exact method name for SDK version (returns array, takes no arguments)
                                        val sh = le.environmentalHdrAmbientSphericalHarmonics
                                        packet["sphericalHarmonics"] = sh.map { it.toDouble() }
                                    } catch (e: Exception) {}
                                }
                            }

                            // 4. Tracking Metadata (Smart Hints)
                            packet["trackingState"] = camera.trackingState.name
                            packet["failureReason"] = camera.trackingFailureReason.name

                            // 5. Semantic Labeling
                            if (finalHit.trackable is Plane) {
                                packet["surfaceType"] = (finalHit.trackable as Plane).type.name
                            }

                            // 6. Native Thermal Monitoring
                            val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                            packet["thermalStatus"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                powerManager.currentThermalStatus 
                            } else { -1 }

                            // 7. Augmented Image Recognition
                            val updatedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
                            if (updatedImages.isNotEmpty()) {
                                packet["recognizedImages"] = updatedImages.map { img ->
                                    mapOf("name" to img.name, "state" to img.trackingState.name)
                                }
                            }

                            // 8. Thread-Safe Dispatch to Flutter
                            activity.runOnUiThread {
                                if (!isDestroyed) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
                            }
                        }
                    }
                }

                // Process Pending Hit Tests
                while (!pendingHitTests.isEmpty()) {
                    val request = pendingHitTests.poll() ?: break
                    if (isDestroyed) break
                    try {
                        val hitResults = frame.hitTest(request.x, request.y)
                        val hitResult = hitResults.firstOrNull { 
                            val trackable = it.trackable 
                            (trackable is Plane && trackable.trackingState == TrackingState.TRACKING) || 
                            (trackable is com.google.ar.core.Point && trackable.trackingState == TrackingState.TRACKING)
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
                        } else {
                            request.result.error("HIT_TEST_FAILED", "No surface found", null)
                        }
                    } catch (e: Exception) {
                        request.result.error("HIT_TEST_ERROR", e.message, null)
                    }
                }

                // Plane Detection logic
                val updatedTrackables: Collection<Plane> = frame.getUpdatedTrackables(Plane::class.java)
                for (plane in updatedTrackables) {
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

                // Point Cloud logic (Pooling restored)
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
                            val confidence = points[pIdx + 3]

                            if (confidence < minConfidence) continue

                            val existing = pointCloudNodes.firstOrNull { it.id == id }
                            if (existing != null) {
                                existing.position = Position(points[pIdx], points[pIdx+1], points[pIdx+2])
                            } else {
                                var node = pointCloudNodePool.removeLastOrNull()
                                if (node == null) {
                                    val modelInst = getPointCloudModelInstance() ?: break
                                    node = PointCloudNode(modelInst, id, confidence)
                                } else {
                                    node.id = id
                                }
                                node?.let {
                                    it.isVisible = showPointCloud
                                    it.position = Position(points[pIdx], points[pIdx+1], points[pIdx+2])
                                    pointCloudNodes.add(it)
                                    sceneView.addChildNode(it)
                                }
                            }
                        }
                    }
                } finally {
                    // 🎯 CRITICAL: Release point cloud buffer
                    pointCloud.release() 
                }
            }
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

        sceneView.onTrackingFailureChanged = { reason ->
            if (!isDestroyed) {
                mainScope.launch {
                    sessionChannel.invokeMethod("onTrackingFailure", reason?.name)
                }
            }
        }
    }

    private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
        try {
            val frame = currentArFrame
            if (frame != null) {
                val camera = frame.camera
                val intrinsics = camera.imageIntrinsics
                val data: Map<String, Double> = mapOf(
                    "fx" to intrinsics.focalLength[0].toDouble(), "fy" to intrinsics.focalLength[1].toDouble(),
                    "cx" to intrinsics.principalPoint[0].toDouble(), "cy" to intrinsics.principalPoint[1].toDouble(),
                    "width" to intrinsics.imageDimensions[0].toDouble(), "height" to intrinsics.imageDimensions[1].toDouble(),
                    "viewWidth" to sceneView.width.toDouble(), "viewHeight" to sceneView.height.toDouble()
                )
                result.success(data)
            } else result.error("NO_FRAME", "AR Frame missing", null)
        } catch (e: Exception) { result.error("INTRINSICS_ERROR", e.message, null) }
    }

    private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
        sceneView.cameraNode.projectionTransform?.toMatrix()?.data?.let {
            result.success(it.map { v -> v.toDouble() })
        } ?: result.error("ERR", "Projection not ready", null)
    }

    private fun handleGetCameraPose(result: MethodChannel.Result) {
        val pose = sceneView.cameraNode.worldTransform.toMatrix().data
        result.success(pose.map { it.toDouble() })
    }

    private fun handleGetAnchorPose(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("anchorId") ?: return result.error("ERR", "No ID", null)
        val anchor = sceneView.session?.allAnchors?.find { it.cloudAnchorId == id } ?: anchorNodesMap[id]?.anchor
        anchor?.let {
            val matrix = FloatArray(16); it.pose.toMatrix(matrix, 0)
            result.success(matrix.map { it.toDouble() })
        } ?: result.error("NOT_FOUND", "Anchor not found", null)
    }

    private fun serializePlane(plane: Plane): Map<String, Any> {
        val matrix = FloatArray(16); plane.centerPose.toMatrix(matrix, 0)
        return mapOf(
            "type" to 0,
            "identifier" to plane.hashCode().toString(),
            "centerPose" to matrix.map { it.toDouble() },
            "extent" to listOf(plane.extentX.toDouble(), plane.extentZ.toDouble())
        )
    }

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        try {
            val argPlaneConfig: Int? = call.argument<Int>("planeDetectionConfig")
            handlePans = call.argument<Boolean>("handlePans") ?: false
            handleRotation = call.argument<Boolean>("handleRotation") ?: false
            val argEnableDepth = call.argument<Boolean>("enableDepth") ?: false

            sceneView.configureSession { session, config ->
                 config.apply {
                    depthMode = if (argEnableDepth && session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else { Config.DepthMode.DISABLED }

                    planeFindingMode = when (argPlaneConfig) {
                        1 -> Config.PlaneFindingMode.HORIZONTAL
                        2 -> Config.PlaneFindingMode.VERTICAL
                        3 -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        else -> Config.PlaneFindingMode.DISABLED
                    }
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                }
            }
            handleShowWorldOrigin(call.argument<Boolean>("showWorldOrigin") ?: false)
            sceneView.planeRenderer.isEnabled = call.argument<Boolean>("showPlanes") ?: true
            result.success(null)
        } catch (e: Exception) { result.error("ERR", e.message, null) }
    }

    private fun handleShowPlanes(call: MethodCall, result: MethodChannel.Result) {
        sceneView.planeRenderer.isEnabled = call.argument<Boolean>("showPlanes") ?: false
        result.success(null)
    }

    private fun handleShowFeaturePoints(call: MethodCall, result: MethodChannel.Result) {
        val show = call.argument<Boolean>("show") ?: false
        showPointCloud = show
        pointCloudNodes.forEach { it.isVisible = show }
        result.success(null)
    }

    private fun handleGetLightEstimate(result: MethodChannel.Result) {
        latestLightEstimate?.let { le ->
            if (le.state == LightEstimate.State.VALID) {
                result.success(mapOf("pixelIntensity" to le.pixelIntensity.toDouble()))
            } else result.error("INVALID", "Light estimate invalid", null)
        } ?: result.error("NULL", "No light estimate available", null)
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

    private fun handleHitTest(call: MethodCall, result: MethodChannel.Result) {
        val x = call.argument<Double>("x")?.toFloat() ?: (sceneView.width / 2f)
        val y = call.argument<Double>("y")?.toFloat() ?: (sceneView.height / 2f)
        val frame = currentArFrame
        if (frame != null) {
            val hits = frame.hitTest(x, y)
            val serializedHits = hits.map { serializeHitResult(it) }
            result.success(serializedHits)
        } else {
            result.error("NO_FRAME", "No frame available for hit test", null)
        }
    }

    private fun handleCaptureBundle(result: MethodChannel.Result) {
        val frame = currentArFrame ?: return result.error("NO_FRAME", "No frame", null)

        // 1. Capture AR data synchronously while frame is valid
        val camera = frame.camera
        val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, 0.01f, 100.0f)
        val view = FloatArray(16); camera.getViewMatrix(view, 0)
        val intrinsics = camera.imageIntrinsics
        val intrinsicsMap = mapOf(
            "fx" to intrinsics.focalLength[0].toDouble(),
            "fy" to intrinsics.focalLength[1].toDouble(),
            "cx" to intrinsics.principalPoint[0].toDouble(),
            "cy" to intrinsics.principalPoint[1].toDouble(),
            "width" to intrinsics.imageDimensions[0].toDouble(),
            "height" to intrinsics.imageDimensions[1].toDouble()
        )

        // 2. Capture Depth synchronously if available
        var depthData: ByteArray? = null
        try {
            frame.acquireDepthImage16Bits().use { depthImage ->
                val buffer = depthImage.planes[0].buffer
                depthData = ByteArray(buffer.remaining())
                buffer.get(depthData!!)
            }
        } catch (e: Exception) { /* Depth not available */ }

        // 3. Trigger Async Screenshot
        isCapturingBundle = true
        val wasPlaneVisible = sceneView.planeRenderer.isVisible
        sceneView.planeRenderer.isVisible = false

        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        
        PixelCopy.request(sceneView, bitmap, { copyResult ->
            isCapturingBundle = false
            // Restore state on UI thread
            Handler(Looper.getMainLooper()).post {
                if (!isDestroyed) sceneView.planeRenderer.isVisible = wasPlaneVisible
            }

            if (copyResult == PixelCopy.SUCCESS) {
                mainScope.launch(Dispatchers.IO) {
                    val byteStream = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream)

                    val data = mutableMapOf<String, Any>(
                        "image" to byteStream.toByteArray(),
                        "projectionMatrix" to proj.map { it.toDouble() },
                        "viewMatrix" to view.map { it.toDouble() },
                        "intrinsics" to intrinsicsMap
                    )
                    depthData?.let { data["depthMap"] = it }
                    withContext(Dispatchers.Main) { result.success(data) }
                }
            } else {
                result.error("CAPTURE_FAILED", "PixelCopy failed", null)
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        if (isDestroyed || sceneView.width <= 0) return result.error("ERR", "View invalid", null)
        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { res ->
            if (res == PixelCopy.SUCCESS) {
                mainScope.launch(Dispatchers.IO) {
                    val stream = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
                }
            } else result.error("SNAP_FAIL", "Failed", null)
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
            sceneView.modelLoader.loadModelInstance(uri)?.let { instance ->
                object : ModelNode(instance) {
                    override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                        if (handlePans) objectChannel.invokeMethod("onPanStart", name)
                        return handlePans && super.onMoveBegin(detector, e)
                    }
                    override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                        if (handlePans) {
                            val r = super.onMove(detector, e)
                            objectChannel.invokeMethod("onPanChange", name)
                            return r
                        }
                        return false
                    }
                    override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
                        if (handlePans) {
                            super.onMoveEnd(detector, e)
                            objectChannel.invokeMethod("onPanEnd", mapOf("name" to name, "transform" to worldTransform.toMatrix().data.map { it.toDouble() }))
                        }
                    }
                    override fun onRotateBegin(detector: RotateGestureDetector, e: MotionEvent): Boolean {
                        if (handleRotation) objectChannel.invokeMethod("onRotationStart", name)
                        return handleRotation && super.onRotateBegin(detector, e)
                    }
                    override fun onRotate(detector: RotateGestureDetector, e: MotionEvent): Boolean {
                        if (handleRotation) {
                            val r = super.onRotate(detector, e)
                            objectChannel.invokeMethod("onRotationChange", name)
                            return r
                        }
                        return false
                    }
                    override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent) {
                        if (handleRotation) {
                            super.onRotateEnd(detector, e)
                            objectChannel.invokeMethod("onRotationEnd", mapOf("name" to name, "transform" to worldTransform.toMatrix().data.map { it.toDouble() }))
                        }
                    }
                }.apply {
                    isPositionEditable = handlePans; isRotationEditable = handleRotation
                    name = nodeData["name"] as? String
                    val scaleVal = transform.first().toFloat(); scale = Scale(scaleVal, scaleVal, scaleVal)
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
        val dictNode = data?.get("node") as? Map<String, Any>
        val dictAnchor = data?.get("anchor") as? Map<String, Any>
        if (dictNode == null || dictAnchor == null) return result.success(false)
        val anchorNode = anchorNodesMap[dictAnchor["name"] as? String]
        if (anchorNode != null) {
            mainScope.launch {
                buildModelNode(dictNode)?.let { node ->
                    anchorNode.addChildNode(node)
                    node.name?.let { nodesMap[it] = node }
                    result.success(true)
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
        nodesMap[name]?.let { 
            sceneView.removeChildNode(it)
            nodesMap.remove(name)
            result.success(name) 
        } ?: result.error("NODE_NOT_FOUND", "Node not found", null)
    }

    private fun handleTransformNode(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        val transform = call.argument<ArrayList<Double>>("transformation")
        nodesMap[name]?.apply {
            transform(Mat4.of(*transform!!.map { it.toFloat() }.toFloatArray()))
            result.success(null)
        } ?: result.error("TRANSFORM_ERROR", "Node missing", null)
    }

    private fun handleAddAnchor(call: MethodCall, result: MethodChannel.Result) {
        val transform = call.argument<ArrayList<Double>>("transformation") ?: return result.success(false)
        val name = call.argument<String>("name") ?: "anchor"
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
        } ?: result.error("ANCHOR_NOT_FOUND", "Anchor missing", null)
    }

    private fun handleInitGoogleCloudAnchorMode(result: MethodChannel.Result) {
        sceneView.session?.let { session ->
            session.configure(session.config.apply { cloudAnchorMode = Config.CloudAnchorMode.ENABLED })
            result.success(null)
        } ?: result.error("SESSION_FAIL", "No session", null)
    }

    private fun handleUploadAnchor(call: MethodCall, result: MethodChannel.Result) {
        val name = call.argument<String>("name")
        val session = sceneView.session
        val anchorNode = anchorNodesMap[name]
        if (session != null && anchorNode != null) {
            val cloudNode = CloudAnchorNode(sceneView.engine, anchorNode.anchor!!)
            cloudNode.host(session) { id, state ->
                if (state == Anchor.CloudAnchorState.SUCCESS) result.success(id)
                else result.error("UPLOAD_FAIL", state.name, null)
            }
            sceneView.addChildNode(cloudNode)
        } else result.error("UPLOAD_FAIL", "Missing session/anchor", null)
    }

    private fun handleDownloadAnchor(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("cloudanchorid") ?: return result.error("ERR", "No ID", null)
        val session = sceneView.session ?: return result.error("ERR", "No session", null)
        CloudAnchorNode.resolve(sceneView.engine, session, id) { state, node ->
            if (!state.isError && node != null) { 
                sceneView.addChildNode(node)
                result.success(true) 
            } else result.error("RESOLVE_FAIL", state.name, null)
        }
    }

    private fun handleShowPointCloud(call: MethodCall, result: MethodChannel.Result) {
        try {
            if (call.hasArgument("showPointCloud")) {
                showPointCloud = call.argument<Boolean>("showPointCloud") ?: true
            } else if (call.hasArgument("hide")) {
                showPointCloud = !(call.argument<Boolean>("hide") ?: false)
            }
            pointCloudNodes.forEach { node ->
                node.isVisible = showPointCloud
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("POINT_CLOUD_ERROR", e.message, null)
        }
    }

    private fun getPointCloudModelInstance(): ModelInstance? {
        if (pointCloudModelInstances.isEmpty()) {
            pointCloudModelInstances = sceneView.modelLoader.createInstancedModel(
                assetFileLocation = "models/point_cloud.glb", count = maxPoints
            ).toMutableList()
        }
        return pointCloudModelInstances.removeLastOrNull()
    }

    private fun makeWorldOriginNode(context: Context): Node {
        val loader = MaterialLoader(sceneView.engine, context)
        val root = Node(sceneView.engine)
        val x = CylinderNode(sceneView.engine, radius = 0.005f, height = 0.1f, materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(1f, 0f, 0f, 1f)))
        val y = CylinderNode(sceneView.engine, radius = 0.005f, height = 0.1f, materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(0f, 1f, 0f, 1f)))
        val z = CylinderNode(sceneView.engine, radius = 0.005f, height = 0.1f, materialInstance = loader.createColorInstance(io.github.sceneview.math.Color(0f, 0f, 1f, 1f)))
        root.addChildNode(x); root.addChildNode(y); root.addChildNode(z)
        x.rotation = Rotation(0f, 0f, 90f); z.rotation = Rotation(90f, 0f, 0f)
        return root
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
            // 1. Stop processing frames immediately
            sceneView.onSessionUpdated = null
            currentArFrame = null
            
            activityLifecycle.removeObserver(this@ArView)
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            
            sessionChannel.setMethodCallHandler(null)
            objectChannel.setMethodCallHandler(null)
            anchorChannel.setMethodCallHandler(null)
            rootLayout.removeAllViews()
        } catch(e: Exception) { Log.e(TAG, "Dispose error", e) }
    }
}