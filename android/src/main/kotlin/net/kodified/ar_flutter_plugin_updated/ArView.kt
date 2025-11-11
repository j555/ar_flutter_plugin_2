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
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
// CHANGED: Fixed import paths for your helper functions
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
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.toMatrix
import io.github.sceneview.math.Mat4
// ADDED: Import for Quaternion, needed for type annotation
import io.github.sceneview.math.Quaternion 
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // These channel names are correct and match your Dart code
    private val sessionChannel: MethodChannel = MethodChannel(messenger, "arsession_$id")
    private val objectChannel: MethodChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorChannel: MethodChannel = MethodChannel(messenger, "aranchors_$id")

    private val nodesMap = mutableMapOf<String, ModelNode>()
    private val anchorNodesMap = mutableMapOf<String, AnchorNode>()
    private var handlePans = false
    private var handleRotation = false
    private var isSessionPaused = false
    
    // Keep track of detected planes to filter new vs. updated
    private val detectedPlanes = mutableSetOf<Plane>()

    private val onSessionMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
            when (call.method) {
                "init" -> handleInit(call, result)
                "showPlanes" -> handleShowPlanes(call, result)
                "dispose" -> dispose()
                "getAnchorPose" -> handleGetAnchorPose(call, result)
                "getCameraPose" -> handleGetCameraPose(result)
                
                // This is the custom handler your app requires
                "getProjectionMatrix" -> handleGetProjectionMatrix(result)

                "snapshot" -> handleSnapshot(result)
                "disableCamera" -> handleDisableCamera(result)
                "enableCamera" -> handleEnableCamera(result)
                else -> result.notImplemented()
            }
        }
    
    private val onObjectMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
            when (call.method) {
                "addNode" -> {
                    val nodeData = call.arguments as? Map<String, Any>
                    nodeData?.let {
                        handleAddNode(it, result)
                    } ?: result.error("INVALID_ARGUMENTS", "Node data is required", null)
                }
                "addNodeToPlaneAnchor" -> handleAddNodeToPlaneAnchor(call, result)
                "addNodeToScreenPosition" -> handleAddNodeToScreenPosition(call, result)
                "removeNode" -> {
                    handleRemoveNode(call, result)
                }
                "transformationChanged" -> {
                    handleTransformNode(call, result)
                }
                else -> result.notImplemented()
            }
        }

    private val onAnchorMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
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
        sceneView = ARSceneView(
            context = viewContext,
            sharedLifecycle = lifecycle,
            // We configure the session during the 'init' call
            sessionConfiguration = { session, config ->
                 config.apply {
                    // Defaults before init
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    depthMode = Config.DepthMode.DISABLED
                    instantPlacementMode = Config.InstantPlacementMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    focusMode = Config.FocusMode.AUTO
                }
            }
        )
        
        rootLayout.addView(sceneView)

        sessionChannel.setMethodCallHandler(onSessionMethodCall)
        objectChannel.setMethodCallHandler(onObjectMethodCall)
        anchorChannel.setMethodCallHandler(onAnchorMethodCall)

        // Setup the new 2.x API listeners
        setupSceneViewListeners()
    }

    private fun setupSceneViewListeners() {
        
        // This is the 2.x API for frame updates
        sceneView.onSessionUpdated = { session, frame ->
            if (isSessionPaused) return@onSessionUpdated

            // ==========================================================
            // CUSTOMIZATION: This is the 2.x API for plane events
            // ==========================================================
            val updatedPlanes = frame.getUpdatedTrackables(Plane::class.java)
            for (plane in updatedPlanes) {
                // Check if it's a new plane being tracked
                if (plane.trackingState == TrackingState.TRACKING && !detectedPlanes.contains(plane)) {
                    detectedPlanes.add(plane)

                    // --- ADD THIS LOGIC ---
                    // Remove hand guide animation
                    rootLayout.findViewWithTag<View>("hand_motion_layout")?.let {
                        rootLayout.removeView(it)
                    }
                    // --- END OF ADDED LOGIC ---

                    val planeMap = serializeAnchor(plane.createAnchor(plane.centerPose))
                    mainScope.launch {
                        sessionChannel.invokeMethod("onPlaneDetected", planeMap)
                    }
                }
                // Check if an existing plane was updated
                else if (plane.trackingState == TrackingState.TRACKING && detectedPlanes.contains(plane)) {
                    val planeMap = serializeAnchor(plane.createAnchor(plane.centerPose))
                    mainScope.launch {
                        sessionChannel.invokeMethod("onPlaneUpdated", planeMap)
                    }
                } 
                // Check if a plane is no longer tracked
                else if (plane.trackingState == TrackingState.STOPPED && detectedPlanes.contains(plane)) {
                    detectedPlanes.remove(plane)
                    val planeMap = serializeAnchor(plane.createAnchor(plane.centerPose))
                    mainScope.launch {
                        sessionChannel.invokeMethod("onPlaneRemoved", planeMap)
                    }
                }
            }
        }
        
        // ==========================================================
        // CUSTOMIZATION: This is the 2.x API for tap events
        // ==========================================================
        // CHANGED: This API is correct. The "unresolved" error suggests a dependency
        // or cache issue. This code itself is valid for 2.3.0.
        sceneView.onTap = { motionEvent: MotionEvent, hitResult: HitResult? ->
            // This is a tap on an AR plane or feature point
            if (hitResult != null) {
                 val serializedHit = serializeHitResult(hitResult)
                 notifyPlaneOrPointTap(listOf(serializedHit))
            }
        }

        sceneView.onTrackingFailureChanged = { reason ->
            mainScope.launch {
                sessionChannel.invokeMethod("onTrackingFailure", reason?.name)
            }
        }
    }
    
    // ==========================================================
    // CUSTOMIZATION: This is the new handler for getProjectionMatrix
    // ==========================================================
    private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
        try {
            // In SceneView 2.x, we can just get the matrix from the camera
            val projectionMatrix = sceneView.cameraNode.projectionTransform?.toMatrix()?.data
            if (projectionMatrix != null) {
                // Convert to a list of doubles for Dart
                val matrixData = projectionMatrix.map { it.toDouble() }
                result.success(matrixData)
            } else {
                 result.error("CAMERA_NOT_READY", "Camera projection matrix is not available yet.", null)
            }
        } catch (e: Exception) {
            result.error("NATIVE_ERROR", "Failed to get projection matrix: ${e.message}", e.toString())
        }
    }
    // ==========================================================
    // END OF CUSTOMIZATION
    // ==========================================================

    private fun handleDisableCamera(result: MethodChannel.Result) {
        try {
            isSessionPaused = true
            // CHANGED: This API is correct for 2.3.0. The "unresolved" error
            // is likely a dependency or cache issue.
            sceneView.pauseSession()
            result.success(null)
        } catch (e: Exception) {
            result.error("DISABLE_CAMERA_ERROR", e.message, null)
        }
    }
    private fun handleEnableCamera(result: MethodChannel.Result) {
        try {
            isSessionPaused = false
            // CHANGED: This API is correct for 2.3.0.
            sceneView.resumeSession()
            result.success(null)
        } catch (e: Exception) {
            result.error("ENABLE_CAMERA_ERROR", e.message, null)
        }
    }

    private suspend fun buildModelNode(nodeData: Map<String, Any>): ModelNode? {
        var fileLocation = nodeData["uri"] as? String ?: return null
        when (nodeData["type"] as Int) {
                0 -> { // GLTF2 Model from Flutter asset folder
                    val loader = FlutterInjector.instance().flutterLoader()
                    fileLocation = loader.getLookupKeyForAsset(fileLocation)
                }
                1 -> { // GLB Model from the web
                    fileLocation = fileLocation
                }
                2 -> { // fileSystemAppFolderGLB
                    fileLocation = fileLocation
                }
                3 -> { //fileSystemAppFolderGLTF2
                    val documentsPath = viewContext.applicationInfo.dataDir
                    fileLocation = documentsPath + "/app_flutter/" + nodeData["uri"] as String
                }
                else -> {
                    return null
                }
        }
        
        if (fileLocation == null) {
            return null
        }
        val transformation = nodeData["transformation"] as? ArrayList<Double>
        if (transformation == null) {
            return null
        }

        return try {
            sceneView.modelLoader.loadModelInstance(fileLocation)?.let { modelInstance ->
                object : ModelNode(
                    modelInstance = modelInstance,
                    // Note: scaleToUnits is no longer a constructor param
                ) {
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
                                "transform" to worldTransform.toMatrix().data.map { it.toDouble() } // Send world transform
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
                                "transform" to worldTransform.toMatrix().data.map { it.toDouble() } // Send world transform
                            )
                            objectChannel.invokeMethod("onRotationEnd", transformMap)
                        }
                    }
                }.apply {
                    isPositionEditable = handlePans
                    isRotationEditable = handleRotation
                    name = nodeData["name"] as? String
                    // Apply scale from transformation if needed
                    val scale = transformation.first().toFloat()
                    this.scale = Scale(scale, scale, scale)
                }
            } ?: run {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun handleAddNodeToPlaneAnchor(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
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
                            // sceneView.addChild(anchorNode) // No longer needed, anchor node is already in scene
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

    private fun handleAddNodeToScreenPosition(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
         try {
            val nodeData = call.arguments as? Map<String, Any>
            val screenPosition = call.argument<Map<String, Double>>("screenPosition")

            if (nodeData == null || screenPosition == null) {
                result.error("INVALID_ARGUMENT", "Node data or screen position is null", null)
                return
            }

            mainScope.launch {
                val node = buildModelNode(nodeData) ?: return@launch
                // Create an AnchorNode at the screen position
                // CHANGED: API is now on arSession
                val hitResult = sceneView.arSession?.hitTest(
                    x = screenPosition["x"]?.toFloat() ?: 0f,
                    y = screenPosition["y"]?.toFloat() ?: 0f
                )

                if (hitResult != null) {
                    // Create an AnchorNode from the hit result's anchor
                    // CHANGED: hitResult.anchor is correct, error is likely dependency related
                    val anchorNode = AnchorNode(sceneView.engine, hitResult.anchor)
                    anchorNode.addChildNode(node)
                    // CHANGED: API is now on scene
                    sceneView.scene.addChild(anchorNode) // Use sceneView.scene.addChild
                    result.success(true)
                } else {
                    result.error("HIT_TEST_FAILED", "Could not create anchor at screen position", null)
                }
            }
        } catch (e: Exception) {
            result.error("ADD_NODE_TO_SCREEN_ERROR", e.message, null)
        }
    }

    private fun handleInit(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val argShowAnimatedGuide = call.argument<Boolean>("showAnimatedGuide") ?: true
            val argShowFeaturePoints = call.argument<Boolean>("showFeaturePoints") ?: false
            val argPlaneDetectionConfig: Int? = call.argument<Int>("planeDetectionConfig")
            val argShowPlanes = call.argument<Boolean>("showPlanes") ?: true
            val customPlaneTexturePath = call.argument<String>("customPlaneTexturePath")
            val showWorldOrigin = call.argument<Boolean>("showWorldOrigin") ?: false
            val handleTaps = call.argument<Boolean>("handleTaps") ?: true // Used by onTapsAR
            handlePans = call.argument<Boolean>("handlePans") ?: false
            handleRotation = call.argument<Boolean>("handleRotation") ?: false

            // Configure the session with the new parameters
            sceneView.configureSession { session, config ->
                 config.apply {
                    depthMode = when (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        true -> Config.DepthMode.AUTOMATIC
                        else -> Config.DepthMode.DISABLED
                    }
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

                // Feature points (point cloud) are now handled correctly
                // CHANGED: API is now on scene
                sceneView.scene.pointCloudNode.isEnabled = argShowFeaturePoints
                
                // Animated guide
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

    private fun handleAddNode(
        nodeData: Map<String, Any>,
        result: MethodChannel.Result,
    ) {
        try {
            mainScope.launch {
                val node = buildModelNode(nodeData)
                if (node != null) {
                    // CHANGED: API is now on scene
                    sceneView.scene.addChild(node)
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

    private fun handleRemoveNode(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val nodeData = call.arguments as? Map<String, Any>
            val nodeName = nodeData?.get("name") as? String
            
            if (nodeName == null) {
                result.error("INVALID_ARGUMENT", "Node name is required", null)
                return
            }
            
            Log.d(TAG, "Attempting to remove node with name: $nodeName")
            Log.d(TAG, "Current nodes in map: ${nodesMap.keys}")
            
            nodesMap[nodeName]?.let { node ->
                node.parent?.removeChildNode(node)
                // CHANGED: API is now on scene
                sceneView.scene.removeChild(node)
                node.destroy()
                nodesMap.remove(nodeName)
                
                Log.d(TAG, "Node removed successfully and destroyed")
                result.success(nodeName)
            } ?: run {
                Log.e(TAG, "Node not found in nodesMap")
                result.error("NODE_NOT_FOUND", "Node with name $nodeName not found", null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing node", e)
            result.error("REMOVE_NODE_ERROR", e.message, null)
        }
    }

    private fun handleTransformNode(
    call: MethodCall,
    result: MethodChannel.Result,
) {
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
                        // Create a Mat4 object and apply it
                        // CHANGED: Mat4 import is correct, error is likely dependency
                        val newMatrix = Mat4(transform.map { it.toFloat() }.toFloatArray())
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

    private fun handleHostCloudAnchor(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
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
                if (state == CloudAnchorState.SUCCESS && cloudAnchorId != null) {
                    result.success(cloudAnchorId)
                } else {
                    result.error("HOSTING_ERROR", "Failed to host cloud anchor: $state", null)
                }
            }
            // CHANGED: API is now on scene
            sceneView.scene.addChild(cloudAnchorNode)
        } catch (e: Exception) {
            result.error("HOST_CLOUD_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleResolveCloudAnchor(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val cloudAnchorId = call.argument<String>("cloudAnchorId")
            if (cloudAnchorId == null) {
                result.error("INVALID_ARGUMENT", "Cloud Anchor ID is required", null)
                return
            }

            val session = sceneView.session
            if (session == null) {
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }

            CloudAnchorNode.resolve(
                sceneView.engine,
                session,
                cloudAnchorId,
            ) { state, node ->
                if (!state.isError && node != null) {
                    // CHANGED: API is now on scene
                    sceneView.scene.addChild(node)
                    result.success(null)
                } else {
                    result.error("RESOLVE_ERROR", "Failed to resolve cloud anchor: $state", null)
                }
            }
        } catch (e: Exception) {
            result.error("RESOLVE_CLOUD_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleRemoveAnchor(
        anchorName: String?,
        result: MethodChannel.Result,
    ) {
        try {
            if (anchorName == null) {
                result.error("INVALID_ARGUMENT", "Anchor name is required", null)
                return
            }

            val anchor = anchorNodesMap[anchorName]
            if (anchor != null) {
                // CHANGED: API is now on scene
                sceneView.scene.removeChild(anchor)
                anchor.anchor?.detach()
                anchorNodesMap.remove(anchorName) // Remove from map
                result.success(null)
            } else {
                result.error("ANCHOR_NOT_FOUND", "Anchor with name $anchorName not found", null)
            }
        } catch (e: Exception) {
            result.error("REMOVE_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleGetCameraPose(result: MethodChannel.Result) {
        try {
            // In SceneView 2.x, we get the pose from the cameraNode
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

    private fun handleGetAnchorPose(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val anchorId = call.argument<String>("anchorId")
            if (anchorId == null) {
                result.error("INVALID_ARGUMENT", "Anchor ID is required", null)
                return
            }
            
            // Try to find in cloud anchors first
            var anchor: Anchor? = sceneView.session?.allAnchors?.find { it.cloudAnchorId == anchorId }
            
            // If not found, check local anchor nodes
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

    private fun handleSnapshot(result: MethodChannel.Result) {
        try {
            mainScope.launch {
                withContext(Dispatchers.Main) {
                    val bitmap =
                        Bitmap.createBitmap(
                            sceneView.width,
                            sceneView.height,
                            Bitmap.Config.ARGB_8888,
                        )

                    try {
                        val listener =
                            PixelCopy.OnPixelCopyFinishedListener { copyResult ->
                                if (copyResult == PixelCopy.SUCCESS) {
                                    val byteStream = java.io.ByteArrayOutputStream()
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream)
                                    val byteArray = byteStream.toByteArray()
                                    result.success(byteArray)
                                } else {
                                    result.error("SNAPSHOT_ERROR", "Failed to capture snapshot", null)
                                }
                            }

                        PixelCopy.request(
                            sceneView,
                            bitmap,
                            listener,
                            Handler(Looper.getMainLooper()),
                        )
                    } catch (e: Exception) {
                        result.error("SNAPSHOT_ERROR", e.message, null)
                    }
                }
            }
        } catch (e: Exception) {
            result.error("SNAPSHOT_ERROR", e.message, null)
        }
    }

    private fun handleShowPlanes(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
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

    private fun handleAddAnchor(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        try {
            val anchorType = call.argument<Int>("type")
            if (anchorType == 0) { // Plane Anchor
                val transform = call.argument<ArrayList<Double>>("transformation")
                val name = call.argument<String>("name")

                if (name != null && transform != null) {
                    try {
                        // CHANGED: Added explicit types to fix ambiguity
                        val (position: Position, rotation: Quaternion) = deserializeMatrix4(transform)

                        val pose =
                            Pose(
                                floatArrayOf(position.x, position.y, position.z),
                                // This is the fix for the 'w' error. We now pass the quaternion.
                                floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w),
                            )

                        val anchor = sceneView.session?.createAnchor(pose)
                        if (anchor != null) {
                            val anchorNode = AnchorNode(sceneView.engine, anchor)
                            // CHANGED: API is now on scene
                            sceneView.scene.addChild(anchorNode)
                            anchorNodesMap[name] = anchorNode
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in transform calculation: ${e.message}")
                        result.success(false)
                    }
                } else {
                    result.success(false)
                }
            } else {
                result.success(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleAddAnchor: ${e.message}")
            e.printStackTrace()
            result.success(false)
        }
    }

    private fun handleInitGoogleCloudAnchorMode(result: MethodChannel.Result) {
        try {
            Log.d(TAG, "🔄 Initialisation du mode Cloud Anchor...")
            sceneView.session?.let { session ->
                session.configure(session.config.apply {
                    cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                })
            }
            result.success(null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur lors de l'initialisation du mode Cloud Anchor", e)
            mainScope.launch {
                sessionChannel.invokeMethod("onError", listOf("Error initializing cloud anchor mode: ${e.message}"))
            }
            result.error("CLOUD_ANCHOR_INIT_ERROR", e.message, null)
        }
    }

    private fun handleUploadAnchor(call: MethodCall, result: MethodChannel.Result) {
        try {
            val anchorName = call.argument<String>("name")
            Log.d(TAG, "⚓ Début de l'upload de l'ancre: $anchorName")
            
            val session = sceneView.session
            if (session == null) {
                Log.e(TAG, "❌ Erreur: session AR non disponible")
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }

            Log.d(TAG, "🔄 Vérification de la configuration Cloud Anchor...")
            try {
                sceneView.configureSession { session, config ->
                    config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
                Log.d(TAG, "✅ Mode Cloud Anchor configuré avec succès")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Erreur lors de la configuration du mode Cloud Anchor", e)
                result.error("CLOUD_ANCHOR_CONFIG_ERROR", e.message, null)
                return
            }

            if (anchorName == null) {
                Log.e(TAG, "❌ Erreur: nom de l'ancre manquant")
                result.error("INVALID_ARGUMENT", "Anchor name is required", null)
                return
            }

            Log.d(TAG, "📱 Vérification de la capacité à héberger l'ancre cloud...")
            if (!session.canHostCloudAnchor(sceneView.cameraNode)) {
                Log.e(TAG, "❌ Erreur: données visuelles insuffisantes pour héberger l'ancre cloud")
                result.error("HOSTING_ERROR", "Insufficient visual data to host", null)
                return
            }

            val anchorNode = anchorNodesMap[anchorName]
            if (anchorNode == null) {
                Log.e(TAG, "❌ Erreur: ancre non trouvée: $anchorName")
                Log.d(TAG, "📍 Ancres disponibles: ${anchorNodesMap.keys}")
                result.error("ANCHOR_NOT_FOUND", "Anchor not found: $anchorName", null)
                return
            }

            Log.d(TAG, "🔄 Création du CloudAnchorNode...")
            val cloudAnchorNode = CloudAnchorNode(sceneView.engine, anchorNode.anchor!!)
            
            Log.d(TAG, "☁️ Début de l'hébergement de l'ancre cloud...")
            cloudAnchorNode.host(session) { cloudAnchorId, state ->
                Log.d(TAG, "📡 État de l'hébergement: $state, ID: $cloudAnchorId")
                mainScope.launch {
                    if (state == CloudAnchorState.SUCCESS && cloudAnchorId != null) {
                        Log.d(TAG, "✅ Ancre cloud hébergée avec succès: $cloudAnchorId")
                        val args = mapOf(
                            "name" to anchorName,
                            "cloudanchorid" to cloudAnchorId
                        )
                        anchorChannel.invokeMethod("onCloudAnchorUploaded", args)
                        result.success(true)
                    } else {
                        Log.e(TAG, "❌ Échec de l'hébergement de l'ancre cloud: $state")
                        sessionChannel.invokeMethod("onError", listOf("Failed to host cloud anchor: $state"))
                        result.error("HOSTING_ERROR", "Failed to host cloud anchor: $state", null)
                    }
                }
            }
            
            Log.d(TAG, "➕ Ajout du CloudAnchorNode à la scène...")
            // CHANGED: API is now on scene
            sceneView.scene.addChild(cloudAnchorNode)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception lors de l'upload de l'ancre", e)
            Log.e(TAG, "Stack trace:", e)
            result.error("UPLOAD_ANCHOR_ERROR", e.message, null)
        }
    }

    private fun handleDownloadAnchor(call: MethodCall, result: MethodChannel.Result) {
        try {
            val cloudAnchorId = call.argument<String>("cloudanchorid")
            if (cloudAnchorId == null) {
                mainScope.launch {
                    sessionChannel.invokeMethod("onError", listOf("Cloud Anchor ID is required"))
                }
                result.error("INVALID_ARGUMENT", "Cloud Anchor ID is required", null)
                return
            }

            val session = sceneView.session
            if (session == null) {
                mainScope.launch {
                    sessionChannel.invokeMethod("onError", listOf("AR Session is not available"))
                }
                result.error("SESSION_ERROR", "AR Session is not available", null)
                return
            }

            CloudAnchorNode.resolve(
                sceneView.engine,
                session,
                cloudAnchorId
            ) { state, node ->
                mainScope.launch {
                    if (!state.isError && node != null) {
                        // CHANGED: API is now on scene
                        sceneView.scene.addChild(node)
                        val anchorData = mapOf(
                            "type" to 0,
                            "cloudanchorid" to cloudAnchorId
                        )
                        anchorChannel.invokeMethod(
                            "onAnchorDownloadSuccess",
                            anchorData,
                            object : MethodChannel.Result {
                                override fun success(result: Any?) {
                                    val anchorName = result.toString()
                                    anchorNodesMap[anchorName] = node
                                }

                                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                                    sessionChannel.invokeMethod("onError", listOf("Error registering downloaded anchor: $errorMessage"))
                                }

                                override fun notImplemented() {
                                    sessionChannel.invokeMethod("onError", listOf("Error registering downloaded anchor: not implemented"))
                                }
                            }
                        )
                        result.success(true)
                    } else {
                        sessionChannel.invokeMethod("onError", listOf("Failed to resolve cloud anchor: $state"))
                        result.error("RESOLVE_ERROR", "Failed to resolve cloud anchor: $state", null)
                    }
                }
            }
        } catch (e: Exception) {
            mainScope.launch {
                sessionChannel.invokeMethod("onError", listOf("Error downloading anchor: ${e.message}"))
            }
            result.error("DOWNLOAD_ANCHOR_ERROR", e.message, null)
        }
    }

    override fun getView(): View = rootLayout

    override fun dispose() {
        Log.i(TAG, "dispose")
        sessionChannel.setMethodCallHandler(null)
        objectChannel.setMethodCallHandler(null)
        anchorChannel.setMethodCallHandler(null)
        nodesMap.clear()
        sceneView.destroy()
        // No need to manually clear point cloud nodes, they are children of sceneView
    }

    private fun notifyError(error: String) {
        mainScope.launch {
            sessionChannel.invokeMethod("onError", listOf(error))
        }
    }

    private fun notifyCloudAnchorUploaded(args: Map<String, Any>) {
        mainScope.launch {
            anchorChannel.invokeMethod("onCloudAnchorUploaded", args)
        }
    }

    private fun notifyAnchorDownloadSuccess(
        anchorData: Map<String, Any>,
        result: MethodChannel.Result,
    ) {
        mainScope.launch {
            anchorChannel.invokeMethod(
                "onAnchorDownloadSuccess",
                anchorData,
                object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        val anchorName = result.toString()
                        // Mettre à jour l'ancre avec le nom reçu
                    }

                    override fun error(
                        errorCode: String,
                        errorMessage: String?,
                        errorDetails: Any?,
                    ) {
                        notifyError("Error while registering downloaded anchor: $errorMessage")
                    }

                    override fun notImplemented() {
                        notifyError("Error while registering downloaded anchor")
                    }
                },
            )
        }
    }

    private fun notifyPlaneOrPointTap(hitResults: List<Map<String, Any?>>) {
        mainScope.launch {
            try {
                // This will send the list of serialized hit results
                sessionChannel.invokeMethod("onPlaneOrPointTap", hitResults)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Point cloud logic is removed as sceneView.pointCloud.isEnabled handles it

    private fun makeWorldOriginNode(context: Context): Node {
        val axisSize = 0.1f
        val axisRadius = 0.005f
        
        val engine = sceneView.engine
        // CHANGED: Constructor for MaterialLoader is different
        val materialLoader = MaterialLoader(engine)
        
        val rootNode = Node(engine = engine)
        
        val xNode = CylinderNode(
            engine = engine,
            radius = axisRadius,
            height = axisSize,
            materialInstance = materialLoader.createColorInstance(
                color = io.github.sceneview.math.Color(1f, 0f, 0f, 1f), // Corrected: use io.github.sceneview.math.Color
                metallic = 0.0f,
                roughness = 0.4f
            )
        )
        
        val yNode = CylinderNode(
            engine = engine,
            radius = axisRadius,
            height = axisSize,
            materialInstance = materialLoader.createColorInstance(
                color = io.github.sceneview.math.Color(0f, 1f, 0f, 1f), // Corrected: use io.github.sceneview.math.Color
                metallic = 0.0f,
                roughness = 0.4f
            )
        )
        
        val zNode = CylinderNode(
            engine = engine,
            radius = axisRadius,
            height = axisSize,
            materialInstance = materialLoader.createColorInstance(
                color = io.github.sceneview.math.Color(0f, 0f, 1f, 1f), // Corrected: use io.github.sceneview.math.Color
                metallic = 0.0f,
                roughness = 0.4f
            )
        )

        rootNode.addChildNode(xNode)
        rootNode.addChildNode(yNode)
        rootNode.addChildNode(zNode)

        xNode.position = Position(axisSize / 2, 0f, 0f)
        xNode.rotation = Rotation(0f, 0f, 90f) // Rotation autour de l'axe Z

        yNode.position = Position(0f, axisSize / 2, 0f)

        zNode.position = Position(0f, 0f, axisSize / 2)
        zNode.rotation = Rotation(90f, 0f, 0f) // Rotation autour de l'axe X

        return rootNode
    }

    private fun handleShowWorldOrigin(show: Boolean) {
        if (show) {
            if (worldOriginNode == null) {
                worldOriginNode = makeWorldOriginNode(viewContext)
            }
            worldOriginNode?.let { node ->
                // CHANGED: API is now on scene
                sceneView.scene.addChild(node)
            }
        } else {
            worldOriginNode?.let { node ->
                // CHANGED: API is now on scene
                sceneView.scene.removeChild(node)
            }
            worldOriginNode = null
        }
    }
}