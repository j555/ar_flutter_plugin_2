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

class ArView(
    context: Context,
    private val activity: Activity,
    private val lifecycle: Lifecycle,
    messenger: BinaryMessenger,
    id: Int,
) : PlatformView {

    private val TAG: String = ArView::class.java.name
    private val viewContext: Context = context
    private var sceneView: ARSceneView
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var worldOriginNode: Node? = null

    private val rootLayout: ViewGroup = FrameLayout(context)

    private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    private val objectChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorChannel = MethodChannel(messenger, "aranchors_$id")

    private val nodesMap = mutableMapOf<String, ModelNode>()
    private val anchorNodesMap = mutableMapOf<String, AnchorNode>()
    private var handlePans = false
    private var handleRotation = false
    private var isSessionPaused = false
    private var isDestroyed = false

    private val detectedPlanes = mutableSetOf<Plane>()

    // --- Point Cloud Pooling ---
    private var pointCloudModelInstances = mutableListOf<ModelInstance>()
    private val pointCloudNodes = mutableListOf<PointCloudNode>()
    private val pointCloudNodePool = ArrayList<PointCloudNode>()
    
    private var lastPointCloudTimestamp: Long? = null
    private var lastPointCloudFrame: Frame? = null
    private var minConfidence = 0.1f
    private var maxPoints = 500
    private var frameCounter = 0

    // Cache the latest light estimate
    private var latestLightEstimate: LightEstimate? = null

    init {
        sceneView = ARSceneView(
            context = viewContext,
            sharedLifecycle = lifecycle,
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

        sessionChannel.setMethodCallHandler { call, result ->
            onSessionMethodCall(call, result)
        }
        objectChannel.setMethodCallHandler(onObjectMethodCall)
        anchorChannel.setMethodCallHandler(onAnchorMethodCall)

        setupSceneViewListeners()
    }

    private fun onSessionMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> handleInit(call, result)
            "showPlanes" -> handleShowPlanes(call, result)
            "showFeaturePoints" -> handleShowFeaturePoints(call, result)
            "hidePointCloud" -> handleHidePointCloud(call, result)
            "dispose" -> dispose()
            "getAnchorPose" -> handleGetAnchorPose(call, result)
            "getCameraPose" -> handleGetCameraPose(result)
            "getProjectionMatrix" -> handleGetProjectionMatrix(result)
            "getLightEstimate" -> handleGetLightEstimate(result) 
            "snapshot" -> handleSnapshot(result)
            "disableCamera" -> handleDisableCamera(result)
            "enableCamera" -> handleEnableCamera(result)
            else -> result.notImplemented()
        }
    }

    private val onObjectMethodCall = MethodChannel.MethodCallHandler { call, result ->
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

    // --- HANDLER FUNCTIONS ---

    private fun handleGetCameraPose(result: MethodChannel.Result) {
        if (isDestroyed) {
            result.error("VIEW_DESTROYED", "ArView is disposed", null)
            return
        }
        try {
            val cameraPose = sceneView.cameraNode.worldTransform.toMatrix().data
            if (cameraPose != null) {
                val matrixData = cameraPose.map { it.toDouble() }
                result.success(matrixData)
            } else {
                result.error("NO_CAMERA_POSE", "Camera pose is not available", null)
            }
        } catch (e: Exception) {
            result.error("CAMERA_POSE_ERROR", e.message, null)
        }
    }

    private fun handleGetAnchorPose(call: MethodCall, result: MethodChannel.Result) {
        try {
            val anchorId = call.argument<String>("anchorId")
            if (anchorId == null) {
                result.error("INVALID_ARGUMENT", "Anchor ID is required", null)
                return
            }
            
            var anchor: Anchor? = sceneView.session?.allAnchors?.find { it.cloudAnchorId == anchorId }
            if (anchor == null) {
                anchor = anchorNodesMap[anchorId]?.anchor
            }
            
            if (anchor != null) {
                val anchorPose = anchor.pose
                val matrix = FloatArray(16)
                anchorPose.toMatrix(matrix, 0)
                val matrixData = matrix.map { it.toDouble() }
                result.success(matrixData)
            } else {
                result.error("ANCHOR_NOT_FOUND", "Anchor with ID $anchorId not found", null)
            }
        } catch (e: Exception) {
            result.error("ANCHOR_POSE_ERROR", e.message, null)
        }
    }

    private fun handleGetLightEstimate(result: MethodChannel.Result) {
        if (isDestroyed) {
            result.error("VIEW_DESTROYED", "View disposed", null)
            return
        }
        
        val estimate = latestLightEstimate
        if (estimate != null && estimate.state == LightEstimate.State.VALID) {
            // Safe array for color correction
            val colorCorrectionFloats = FloatArray(4)
            try {
                estimate.getColorCorrection(colorCorrectionFloats, 0)
            } catch (e: Exception) {
                // Ignore if unavailable in current mode
            }

            val args = mapOf(
                "pixelIntensity" to estimate.pixelIntensity,
                "colorCorrection" to colorCorrectionFloats.map { it.toDouble() }
            )
            result.success(args)
        } else {
            result.error("LIGHT_ESTIMATE_ERROR", "Light estimate not valid", null)
        }
    }

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        try {
            val argShowAnimatedGuide = call.argument<Boolean>("showAnimatedGuide") ?: true
            val argShowFeaturePoints = call.argument<Boolean>("showFeaturePoints") ?: false
            val argPlaneDetectionConfig: Int? = call.argument<Int>("planeDetectionConfig")
            val argShowPlanes = call.argument<Boolean>("showPlanes") ?: true
            val customPlaneTexturePath = call.argument<String>("customPlaneTexturePath")
            val showWorldOrigin = call.argument<Boolean>("showWorldOrigin") ?: false
            val handleTaps = call.argument<Boolean>("handleTaps") ?: true
            handlePans = call.argument<Boolean>("handlePans") ?: false
            handleRotation = call.argument<Boolean>("handleRotation") ?: false
            
            // --- NEW: Configurable Options ---
            val argEnableDepth = call.argument<Boolean>("enableDepth") ?: false
            val argLightEstimation = call.argument<Int>("lightEstimation") ?: 1 // Default to AMBIENT_INTENSITY (1)

            Log.d(TAG, "HANDLE INIT: Config=$argPlaneDetectionConfig Depth=$argEnableDepth Light=$argLightEstimation")

            sceneView.configureSession { session, config ->
                 config.apply {
                    // Depth Mode
                    depthMode = if (argEnableDepth && session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }

                    // Light Estimation Mode
                    // 0: Disabled, 1: Ambient Intensity (Default), 2: Environmental HDR
                    lightEstimationMode = when (argLightEstimation) {
                        0 -> Config.LightEstimationMode.DISABLED
                        2 -> Config.LightEstimationMode.ENVIRONMENTAL_HDR
                        else -> Config.LightEstimationMode.AMBIENT_INTENSITY
                    }

                    // Plane Finding
                    planeFindingMode = when (argPlaneDetectionConfig) {
                        1 -> Config.PlaneFindingMode.HORIZONTAL
                        2 -> Config.PlaneFindingMode.VERTICAL
                        3 -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        else -> Config.PlaneFindingMode.DISABLED
                    }
                }
            }

            handleShowWorldOrigin(showWorldOrigin)
            
            sceneView.apply {
                environment = environmentLoader.createHDREnvironment(
                    assetFileLocation = "environments/evening_meadow_2k.hdr"
                )!!

                planeRenderer.isEnabled = argShowPlanes
                planeRenderer.isVisible = argShowPlanes
                planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_ALL

                if (argShowAnimatedGuide) {
                    val handMotionLayout =
                        LayoutInflater
                            .from(context)
                            .inflate(R.layout.sceneform_hand_layout, rootLayout, false)
                            .apply {
                                tag = "hand_motion_layout"
                            }
                    rootLayout.addView(handMotionLayout)
                }
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("AR_VIEW_ERROR", e.message, null)
        }
    }

    // --- OTHER HANDLERS ---

    private fun handleShowPlanes(call: MethodCall, result: MethodChannel.Result) {
        try {
            val showPlanes = call.argument<Boolean>("showPlanes") ?: false
            sceneView.apply {
                planeRenderer.isEnabled = showPlanes
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("SHOW_PLANES_ERROR", e.message, null)
        }
    }

    private fun handleShowFeaturePoints(call: MethodCall, result: MethodChannel.Result) {
        result.success(null) 
    }

    private fun handleHidePointCloud(call: MethodCall, result: MethodChannel.Result) {
        try {
            val hide = call.argument<Boolean>("hide") ?: true
            pointCloudNodes.forEach { node ->
                node.isVisible = !hide
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("POINT_CLOUD_ERROR", e.message, null)
        }
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        if (isDestroyed) {
             result.error("VIEW_DESTROYED", "ArView is disposed", null)
             return
        }
        if (sceneView.width <= 0 || sceneView.height <= 0) {
             Log.e(TAG, "SNAPSHOT_ERROR: View has invalid dimensions (${sceneView.width}x${sceneView.height})")
             result.error("SNAPSHOT_ERROR", "View has invalid dimensions", null)
             return
        }
        
        mainScope.launch(Dispatchers.Main) { 
            val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
            try {
                val listener = PixelCopy.OnPixelCopyFinishedListener { copyResult ->
                        if (isDestroyed) return@OnPixelCopyFinishedListener
                        if (copyResult == PixelCopy.SUCCESS) {
                            mainScope.launch(Dispatchers.IO) {
                                val byteStream = java.io.ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream)
                                val byteArray = byteStream.toByteArray()
                                withContext(Dispatchers.Main) { result.success(byteArray) }
                            }
                        } else {
                            result.error("SNAPSHOT_ERROR", "Failed to capture snapshot", null)
                        }
                    }
                PixelCopy.request(sceneView, bitmap, listener, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                result.error("SNAPSHOT_ERROR", e.message, null)
            }
        }
    }

    private fun handleDisableCamera(result: MethodChannel.Result) {
        try {
            isSessionPaused = true
            sceneView.session?.pause()
            result.success(null)
        } catch (e: Exception) {
            result.error("DISABLE_CAMERA_ERROR", e.message, null)
        }
    }

    private fun handleEnableCamera(result: MethodChannel.Result) {
        try {
            isSessionPaused = false
            sceneView.session?.resume()
            result.success(null)
        } catch (e: Exception) {
            result.error("ENABLE_CAMERA_ERROR", e.message, null)
        }
    }

    private suspend fun buildModelNode(nodeData: Map<String, Any>): ModelNode? {
        var fileLocation = nodeData["uri"] as? String ?: return null
        when (nodeData["type"] as Int) {
                0 -> { 
                    val loader = FlutterInjector.instance().flutterLoader()
                    fileLocation = loader.getLookupKeyForAsset(fileLocation)
                }
                1 -> { fileLocation = fileLocation }
                2 -> { fileLocation = fileLocation }
                3 -> { 
                    val documentsPath = viewContext.applicationInfo.dataDir
                    fileLocation = documentsPath + "/app_flutter/" + nodeData["uri"] as String
                }
                else -> { return null }
        }
        
        if (fileLocation == null) return null
        val transformation = nodeData["transformation"] as? ArrayList<Double>
        if (transformation == null) return null

        return try {
            sceneView.modelLoader.loadModelInstance(fileLocation)?.let { modelInstance ->
                object : ModelNode(modelInstance = modelInstance) {
                    override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                            if (handlePans) {
                            val defaultResult = super.onMove(detector, e)
                            objectChannel.invokeMethod("onPanChange", name)
                            return defaultResult
                            }
                    return false
                    }
                    override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                        if (handlePans) {
                            val defaultResult = super.onMoveBegin(detector, e)
                            objectChannel.invokeMethod("onPanStart", name)
                            return defaultResult
                        } 
                        return false
                    }
                    override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
                        if (handlePans) {
                            super.onMoveEnd(detector, e)
                            val transformMap = mapOf(
                                "name" to name,
                                "transform" to worldTransform.toMatrix().data.map { it.toDouble() }
                            )
                            objectChannel.invokeMethod("onPanEnd", transformMap)
                        }
                    }
                    override fun onRotateBegin(detector: RotateGestureDetector, e: MotionEvent): Boolean {
                        if (handleRotation) {
                            val defaultResult = super.onRotateBegin(detector, e)
                            objectChannel.invokeMethod("onRotationStart", name)
                            return defaultResult
                        }
                        return false
                    }
                    override fun onRotate(detector: RotateGestureDetector, e: MotionEvent): Boolean {
                        if (handleRotation) {
                            val defaultResult = super.onRotate(detector, e)
                            objectChannel.invokeMethod("onRotationChange", name)
                            return defaultResult
                        }
                        return false
                    }
                    override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent) {
                        if (handleRotation) {
                            super.onRotateEnd(detector, e)
                            val transformMap = mapOf(
                                "name" to name,
                                "transform" to worldTransform.toMatrix().data.map { it.toDouble() }
                            )
                            objectChannel.invokeMethod("onRotationEnd", transformMap)
                        }
                    }
                }.apply {
                    isPositionEditable = handlePans
                    isRotationEditable = handleRotation
                    name = nodeData["name"] as? String
                    val scale = transformation.first().toFloat()
                    this.scale = Scale(scale, scale, scale)
                }
            } ?: run { null }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun handleAddNode(nodeData: Map<String, Any>, result: MethodChannel.Result) {
        try {
            mainScope.launch {
                val node = buildModelNode(nodeData)
                if (node != null) {
                    sceneView.addChildNode(node)
                    node.name?.let { nodeName ->
                        nodesMap[nodeName] = node
                    }
                    result.success(true)
                } else {
                    result.success(false)
                }
            }
        } catch (e: Exception) {
            result.success(false)
        }
    }

    private fun handleRemoveNode(call: MethodCall, result: MethodChannel.Result) {
        try {
            val nodeData = call.arguments as? Map<String, Any>
            val nodeName = nodeData?.get("name") as? String
            
            if (nodeName == null) {
                result.error("INVALID_ARGUMENT", "Node name is required", null)
                return
            }
            
            nodesMap[nodeName]?.let { node ->
                node.parent?.removeChildNode(node)
                sceneView.removeChildNode(node)
                node.destroy()
                nodesMap.remove(nodeName)
                result.success(nodeName)
            } ?: run {
                result.error("NODE_NOT_FOUND", "Node with name $nodeName not found", null)
            }
        } catch (e: Exception) {
            result.error("REMOVE_NODE_ERROR", e.message, null)
        }
    }

    private fun handleTransformNode(call: MethodCall, result: MethodChannel.Result) {
        try {
            if (handlePans || handleRotation) {
                val name = call.argument<String>("name")
                val newTransformation: ArrayList<Double>? = call.argument<ArrayList<Double>>("transformation")

                if (name == null) {
                    result.error("INVALID_ARGUMENT", "Node name is required", null)
                    return
                }
                nodesMap[name]?.let { node ->
                    newTransformation?.let { transform ->
                        if (transform.size != 16) {
                            result.error("INVALID_TRANSFORMATION", "Transformation must be a 4x4 matrix (16 values)", null)
                            return
                        }
                        node.apply {
                            val newMatrix = Mat4.of(*transform.map { it.toFloat() }.toFloatArray())
                            transform(newMatrix)
                        }
                        result.success(null)
                    } ?: result.error("INVALID_TRANSFORMATION", "Transformation is required", null)
                } ?: result.error("NODE_NOT_FOUND", "Node with name $name not found", null)
            }
        } catch (e: Exception) {
            result.error("TRANSFORM_NODE_ERROR", e.message, null)
        }
    }

    private fun handleHostCloudAnchor(call: MethodCall, result: MethodChannel.Result) {
        try {
            val anchorId = call.argument<String>("anchorId")
            if (anchorId == null) {
                result.error("INVALID_ARGUMENT", "Anchor ID is required", null)
                return
            }
            val session = sceneView.session
            if (session == null) {
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }
            if (!session.canHostCloudAnchor(sceneView.cameraNode)) {
                result.error("HOSTING_ERROR", "Insufficient visual data to host", null)
                return
            }
            val anchor = session.allAnchors.find { it.cloudAnchorId == anchorId }
            if (anchor == null) {
                result.error("ANCHOR_NOT_FOUND", "Anchor with ID $anchorId not found", null)
                return
            }
            val cloudAnchorNode = CloudAnchorNode(sceneView.engine, anchor)
            cloudAnchorNode.host(session) { cloudAnchorId, state ->
                if (state == Anchor.CloudAnchorState.SUCCESS && cloudAnchorId != null) {
                    result.success(cloudAnchorId)
                } else {
                    result.error("HOSTING_ERROR", "Failed to host cloud anchor: $state", null)
                }
            }
            sceneView.addChildNode(cloudAnchorNode)
        } catch (e: Exception) {
            result.error("HOST_CLOUD_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleResolveCloudAnchor(call: MethodCall, result: MethodChannel.Result) {
        try {
            val cloudAnchorId = call.argument<String>("cloudanchorid")
            if (cloudAnchorId == null) {
                result.error("INVALID_ARGUMENT", "Cloud Anchor ID is required", null)
                return
            }
            val session = sceneView.session
            if (session == null) {
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }
            CloudAnchorNode.resolve(sceneView.engine, session, cloudAnchorId) { state, node ->
                if (!state.isError && node != null) {
                    sceneView.addChildNode(node)
                    result.success(null)
                } else {
                    result.error("RESOLVE_ERROR", "Failed to resolve cloud anchor: $state", null)
                }
            }
        } catch (e: Exception) {
            result.error("RESOLVE_CLOUD_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleRemoveAnchor(anchorName: String?, result: MethodChannel.Result) {
        try {
            if (anchorName == null) {
                result.error("INVALID_ARGUMENT", "Anchor name is required", null)
                return
            }
            val anchor = anchorNodesMap[anchorName]
            if (anchor != null) {
                sceneView.removeChildNode(anchor)
                anchor.anchor?.detach()
                anchorNodesMap.remove(anchorName)
                result.success(null)
            } else {
                result.error("ANCHOR_NOT_FOUND", "Anchor with name $anchorName not found", null)
            }
        } catch (e: Exception) {
            result.error("REMOVE_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleAddNodeToPlaneAnchor(call: MethodCall, result: MethodChannel.Result) {
        try {
            val nodeData = call.arguments as? Map<String, Any>
            val dict_node = nodeData?.get("node") as? Map<String, Any>
            val dict_anchor = nodeData?.get("anchor") as? Map<String, Any>
            if (dict_node == null || dict_anchor == null) {
                result.success(false)
                return
            }
            val anchorName = dict_anchor["name"] as? String
            val anchorNode = anchorNodesMap[anchorName]
            if (anchorNode != null) {
                mainScope.launch {
                    try {
                        buildModelNode(dict_node)?.let { node ->
                            anchorNode.addChildNode(node)
                            node.name?.let { nodeName ->
                                nodesMap[nodeName] = node
                            }
                            result.success(true)
                        } ?: result.success(false)
                    } catch (e: Exception) {
                        result.success(false)
                    }
                }
            } else {
                result.success(false)
            }
        } catch (e: Exception) {
            result.success(false)
        }
    }

    private fun handleAddNodeToScreenPosition(call: MethodCall, result: MethodChannel.Result) {
         try {
            val nodeData = call.arguments as? Map<String, Any>
            val screenPosition = call.argument<Map<String, Double>>("screenPosition")

            if (nodeData == null || screenPosition == null) {
                result.error("INVALID_ARGUMENT", "Node data or screen position is null", null)
                return
            }
            mainScope.launch {
                val node = buildModelNode(nodeData) ?: return@launch
                val frame = sceneView.session?.update()
                if (frame == null) {
                    result.error("SESSION_ERROR", "AR Session is not ready", null)
                    return@launch
                }
                val hitResults = frame.hitTest(
                    screenPosition["x"]?.toFloat() ?: 0f,
                    screenPosition["y"]?.toFloat() ?: 0f
                )
                val hitResult = hitResults.firstOrNull { 
                    val trackable = it.trackable 
                    (trackable is Plane && trackable.trackingState == TrackingState.TRACKING) || (trackable is com.google.ar.core.Point && trackable.trackingState == TrackingState.TRACKING)
                }
                if (hitResult != null) {
                    val anchorNode = AnchorNode(sceneView.engine, hitResult.createAnchor())
                    anchorNode.addChildNode(node)
                    sceneView.addChildNode(anchorNode)
                    result.success(true)
                } else {
                    result.error("HIT_TEST_FAILED", "Could not create anchor at screen position", null)
                }
            }
        } catch (e: Exception) {
            result.error("ADD_NODE_TO_SCREEN_ERROR", e.message, null)
        }
    }

    private fun handleAddAnchor(call: MethodCall, result: MethodChannel.Result) {
        try {
            val anchorType = call.argument<Int>("type")
            if (anchorType == 0) { // Plane Anchor
                val transform = call.argument<ArrayList<Double>>("transformation")
                val name = call.argument<String>("name")
                if (name != null && transform != null) {
                    try {
                        val (position: Position, rotation: Quaternion) = deserializeMatrix4(transform)
                        val pose = Pose(
                                floatArrayOf(position.x, position.y, position.z),
                                floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w),
                            )
                        val anchor = sceneView.session?.createAnchor(pose)
                        if (anchor != null) {
                            val anchorNode = AnchorNode(sceneView.engine, anchor)
                            sceneView.addChildNode(anchorNode)
                            anchorNodesMap[name] = anchorNode
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    } catch (e: Exception) {
                        result.success(false)
                    }
                } else {
                    result.success(false)
                }
            } else {
                result.success(false)
            }
        } catch (e: Exception) {
            result.success(false)
        }
    }

    private fun handleInitGoogleCloudAnchorMode(result: MethodChannel.Result) {
        try {
            sceneView.session?.let { session ->
                session.configure(session.config.apply {
                    cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                })
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("CLOUD_ANCHOR_INIT_ERROR", e.message, null)
        }
    }

    private fun handleUploadAnchor(call: MethodCall, result: MethodChannel.Result) {
        try {
            val anchorName = call.argument<String>("name")
            val session = sceneView.session
            if (session == null) {
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }
            sceneView.configureSession { session, config ->
                config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
            if (anchorName == null) {
                result.error("INVALID_ARGUMENT", "Anchor name is required", null)
                return
            }
            if (!session.canHostCloudAnchor(sceneView.cameraNode)) {
                result.error("HOSTING_ERROR", "Insufficient visual data to host", null)
                return
            }
            val anchorNode = anchorNodesMap[anchorName]
            if (anchorNode == null) {
                result.error("ANCHOR_NOT_FOUND", "Anchor not found: $anchorName", null)
                return
            }
            val cloudAnchorNode = CloudAnchorNode(sceneView.engine, anchorNode.anchor!!)
            cloudAnchorNode.host(session) { cloudAnchorId, state ->
                if (state == Anchor.CloudAnchorState.SUCCESS && cloudAnchorId != null) {
                    val args = mapOf("name" to anchorName, "cloudanchorid" to cloudAnchorId)
                    mainScope.launch { anchorChannel.invokeMethod("onCloudAnchorUploaded", args) }
                    result.success(true)
                } else {
                    result.error("HOSTING_ERROR", "Failed to host cloud anchor: $state", null)
                }
            }
            sceneView.addChildNode(cloudAnchorNode)
        } catch (e: Exception) {
            result.error("UPLOAD_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleDownloadAnchor(call: MethodCall, result: MethodChannel.Result) {
         try {
            val cloudAnchorId = call.argument<String>("cloudanchorid")
            if (cloudAnchorId == null) {
                result.error("INVALID_ARGUMENT", "Cloud Anchor ID is required", null)
                return
            }
            val session = sceneView.session
            if (session == null) {
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }
            CloudAnchorNode.resolve(sceneView.engine, session, cloudAnchorId) { state, node ->
                mainScope.launch {
                    if (!state.isError && node != null) {
                        sceneView.addChildNode(node)
                        val anchorData = mapOf("type" to 0, "cloudanchorid" to cloudAnchorId)
                        anchorChannel.invokeMethod("onAnchorDownloadSuccess", anchorData)
                        result.success(true)
                    } else {
                        result.error("RESOLVE_ERROR", "Failed to resolve cloud anchor: $state", null)
                    }
                }
            }
        } catch (e: Exception) {
            result.error("DOWNLOAD_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun getPointCloudModelInstance(): ModelInstance? {
        if (pointCloudModelInstances.isEmpty()) {
            pointCloudModelInstances = sceneView.modelLoader.createInstancedModel(
                assetFileLocation = "models/point_cloud.glb",
                count = maxPoints
            ).toMutableList()
        }
        return pointCloudModelInstances.removeLastOrNull()
    }

    private fun removePointCloudNode(node: PointCloudNode) {
        pointCloudNodes.remove(node)
        sceneView.removeChildNode(node)
        pointCloudNodePool.add(node)
    }

    private fun notifyPlaneOrPointTap(hitResults: List<Map<String, Any?>>) {
        mainScope.launch {
            try {
                sessionChannel.invokeMethod("onPlaneOrPointTap", hitResults)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun makeWorldOriginNode(context: Context): Node {
        val axisSize = 0.1f; val axisRadius = 0.005f
        val engine = sceneView.engine
        val materialLoader = MaterialLoader(engine = engine, context = context)
        val rootNode = Node(engine = engine)
        
        val xNode = CylinderNode(engine = engine, radius = axisRadius, height = axisSize, materialInstance = materialLoader.createColorInstance(io.github.sceneview.math.Color(1f, 0f, 0f, 1f), 0f, 0.4f))
        val yNode = CylinderNode(engine = engine, radius = axisRadius, height = axisSize, materialInstance = materialLoader.createColorInstance(io.github.sceneview.math.Color(0f, 1f, 0f, 1f), 0f, 0.4f))
        val zNode = CylinderNode(engine = engine, radius = axisRadius, height = axisSize, materialInstance = materialLoader.createColorInstance(io.github.sceneview.math.Color(0f, 0f, 1f, 1f), 0f, 0.4f))

        rootNode.addChildNode(xNode); rootNode.addChildNode(yNode); rootNode.addChildNode(zNode)
        xNode.position = Position(axisSize/2, 0f, 0f); xNode.rotation = Rotation(0f, 0f, 90f)
        yNode.position = Position(0f, axisSize/2, 0f)
        zNode.position = Position(0f, 0f, axisSize/2); zNode.rotation = Rotation(90f, 0f, 0f)
        return rootNode
    }

    private fun handleShowWorldOrigin(show: Boolean) {
        if (show) { if (worldOriginNode == null) { worldOriginNode = makeWorldOriginNode(viewContext); sceneView.addChildNode(worldOriginNode!!) } }
        else { worldOriginNode?.let { sceneView.removeChildNode(it) }; worldOriginNode = null }
    }

    override fun getView(): View = rootLayout

    override fun dispose() {
        if (isDestroyed) return
        isDestroyed = true
        Log.i(TAG, "dispose")
        
        sessionChannel.setMethodCallHandler(null)
        objectChannel.setMethodCallHandler(null)
        anchorChannel.setMethodCallHandler(null)

        pointCloudNodePool.clear()
        pointCloudNodes.clear()
        nodesMap.clear()
        anchorNodesMap.clear()

        rootLayout.removeAllViews()
    }
}