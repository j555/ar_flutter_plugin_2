// android/src/main/kotlin/net/kodified/ar_flutter_plugin_updated/ArView.kt
package net.kodified.ar_flutter_plugin_updated

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.*
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.*
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.ar.ARSceneView
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class ArView(
    context: Context,
    private val messenger: BinaryMessenger,
    private val id: Int,
    private val activityLifecycle: Lifecycle,
    private val activity: Activity,
) : PlatformView, DefaultLifecycleObserver {

    private val TAG: String = "ArView_Native"
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val rootLayout: ViewGroup = FrameLayout(context)
    private val sceneView: ARSceneView = ARSceneView(context, null)
    private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    
    private val isDestroyed = AtomicBoolean(false)
    private var isCenterHitTrackingEnabled = false
    private var isBridgeBusy = false
    private var lastFrameTime: Long = 0
    private var currentArFrame: Frame? = null 

    init {
        activityLifecycle.addObserver(this)
        
        sceneView.apply {
            this.lifecycle = activityLifecycle
            this.planeRenderer.isVisible = false
            this.planeRenderer.isEnabled = false
            
            this.sessionConfiguration = { session, config ->
                config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }
                }
            }
        }
        rootLayout.addView(sceneView)

        sessionChannel.setMethodCallHandler { call, result ->
            if (isDestroyed.get()) return@setMethodCallHandler
            when (call.method) {
                "init" -> handleInit(call, result)
                "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
                "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
                "snapshot" -> handleSnapshot(result)
                "getImageIntrinsics" -> handleGetImageIntrinsics(result)
                "getCameraPose" -> handleGetCameraPose(result)
                "getProjectionMatrix" -> handleGetProjectionMatrix(result)
                else -> result.notImplemented()
            }
        }

        sceneView.onSessionUpdated = { _, frame ->
            currentArFrame = frame
            if (isCenterHitTrackingEnabled && !isBridgeBusy && (System.currentTimeMillis() - lastFrameTime >= 50L)) {
                lastFrameTime = System.currentTimeMillis()
                broadcastHardwareTelemetry(frame)
            }
        }
    }

    private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
        val showPlanes = call.argument<Boolean>("showPlanes") ?: false
        sceneView.planeRenderer.isVisible = showPlanes
        result.success(null)
    }

    private fun broadcastHardwareTelemetry(frame: Frame) {
        val camera = frame.camera
        val packet = mutableMapOf<String, Any?>()

        // 1. CORE STATUS & TRACKING
        packet["trackingState"] = camera.trackingState.name
        packet["trackingFailureReason"] = camera.trackingFailureReason.name

        if (camera.trackingState != TrackingState.TRACKING) {
            sendTelemetryPacket(packet.filterValues { it != null } as Map<String, Any>)
            return
        }

        // 2. STABILITY METRICS (Feature points & Lighting)
        try {
            frame.acquirePointCloud().use { pc ->
                packet["featureCount"] = pc.points.remaining() / 4
            }
        } catch (e: Exception) { packet["featureCount"] = 0 }

        packet["lightIntensity"] = frame.lightEstimate.takeIf { it?.state == LightEstimate.State.VALID }
            ?.pixelIntensity?.toDouble() ?: 1.0

        // 3. MATRICES (Critical for Coordinate Matching)
        val camPose = camera.displayOrientedPose
        val camMat = FloatArray(16).also { camPose.toMatrix(it, 0) }
        packet["cameraPose"] = camMat.map { it.toDouble() }

        val projArr = FloatArray(16).also { camera.getProjectionMatrix(it, 0, 0.1f, 100.0f) }
        packet["projectionMatrix"] = projArr.map { it.toDouble() }

        // 4. CAMERA FORWARD VECTOR (For Tilt/Angle Fallbacks)
        val forwardX = -camMat[8].toDouble()
        val forwardY = -camMat[9].toDouble()
        val forwardZ = -camMat[10].toDouble()
        val horizontalDist = sqrt(forwardX * forwardX + forwardZ * forwardZ)

        // 5. HIT TEST LOGIC (Wall Data)
        val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
        val bestHit = hits.firstOrNull { it.trackable is Plane } ?: hits.firstOrNull { it.trackable is DepthPoint }

        if (bestHit != null) {
            val hp = bestHit.hitPose
            val hpMat = FloatArray(16).also { hp.toMatrix(it, 0) }
            val normal = hp.yAxis // Correct Plane Normal

            packet["hit"] = mapOf("transform" to hpMat.map { it.toDouble() })
            packet["worldPosition"] = listOf(hp.tx().toDouble(), hp.ty().toDouble(), hp.tz().toDouble())
            packet["distance"] = sqrt(((hp.tx()-camPose.tx()).pow(2) + (hp.ty()-camPose.ty()).pow(2) + (hp.tz()-camPose.tz()).pow(2)).toDouble())
            packet["hitType"] = if (abs(normal[1]) < 0.5) "VERTICAL" else "HORIZONTAL"
            packet["wallNormal"] = listOf(normal[0].toDouble(), normal[1].toDouble(), normal[2].toDouble())

            // 🎯 PHONE TILT (Pitch): Constant relative to gravity for skew correction
            // We use the camera's forward vector to determine if we are looking up or down
            packet["wallTilt"] = atan2(forwardY, horizontalDist) * (180.0 / PI)

            // 🎯 RELATIVE ANGLE: Phone's heading vs Wall's facing direction
            val camYaw = atan2(forwardX, forwardZ)
            val wallYaw = atan2(normal[0].toDouble(), normal[2].toDouble())
            var relAngle = (camYaw - wallYaw - PI) * (180.0 / PI)
            
            while (relAngle > 180) relAngle -= 360
            while (relAngle < -180) relAngle += 360
            packet["wallAngle"] = relAngle

        } else {
            // SEARCHING STATE: Provide Device Orientation so the UI doesn't freeze or flicker
            packet["hitType"] = "SEARCHING"
            packet["wallTilt"] = atan2(forwardY, horizontalDist) * (180.0 / PI)
            packet["wallAngle"] = atan2(forwardX, forwardZ) * (180.0 / PI)
            packet["distance"] = null
            packet["wallNormal"] = null
            packet["worldPosition"] = null
        }

        sendTelemetryPacket(packet.filterValues { it != null } as Map<String, Any>)
    }

    private fun sendTelemetryPacket(packet: Map<String, Any>) {
        isBridgeBusy = true
        activity.runOnUiThread {
            if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
            isBridgeBusy = false
        }
    }

    private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
        val frame = currentArFrame ?: return result.error("ERR", "No Frame", null)
        val intrinsics = frame.camera.imageIntrinsics
        result.success(mapOf(
            "fx" to intrinsics.focalLength[0].toDouble(), "fy" to intrinsics.focalLength[1].toDouble(),
            "cx" to intrinsics.principalPoint[0].toDouble(), "cy" to intrinsics.principalPoint[1].toDouble(),
            "width" to intrinsics.imageDimensions[0].toDouble(), "height" to intrinsics.imageDimensions[1].toDouble(),
            "viewWidth" to sceneView.width.toDouble(), "viewHeight" to sceneView.height.toDouble(),
            "lightIntensity" to (frame.lightEstimate?.pixelIntensity?.toDouble() ?: 1.0)
        ))
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        if (sceneView.width <= 0 || sceneView.height <= 0) return result.error("ERR", "Invalid View", null)
        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { res ->
            if (res == PixelCopy.SUCCESS) {
                mainScope.launch(Dispatchers.IO) {
                    val stream = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
                }
            } else result.error("ERR", "PixelCopy failed", null)
        }, Handler(Looper.getMainLooper()))
    }

    private fun handleGetCameraPose(result: MethodChannel.Result) {
        currentArFrame?.camera?.displayOrientedPose?.let { p -> 
            val m = FloatArray(16); p.toMatrix(m, 0); result.success(m.map { it.toDouble() })
        } ?: result.error("ERR", "No pose", null)
    }

    private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
        val proj = FloatArray(16); currentArFrame?.camera?.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
        result.success(proj.map { it.toDouble() })
    }

    override fun getView(): View = rootLayout
    override fun onDestroy(owner: LifecycleOwner) { dispose() }
    override fun dispose() {
        if (isDestroyed.getAndSet(true)) return
        activityLifecycle.removeObserver(this)
        mainScope.cancel()
        sceneView.destroy()
    }
}









// // android/src/main/kotlin/net/kodified/ar_flutter_plugin_updated/ArView.kt
// package net.kodified.ar_flutter_plugin_updated

// import android.app.Activity
// import android.content.Context
// import android.graphics.Bitmap
// import android.os.*
// import android.util.Log
// import android.view.*
// import android.widget.FrameLayout
// import androidx.lifecycle.DefaultLifecycleObserver
// import androidx.lifecycle.Lifecycle
// import androidx.lifecycle.LifecycleOwner
// import com.google.ar.core.*
// import io.flutter.plugin.common.BinaryMessenger
// import io.flutter.plugin.common.MethodCall
// import io.flutter.plugin.common.MethodChannel
// import io.flutter.plugin.platform.PlatformView
// import io.github.sceneview.ar.ARSceneView
// import io.github.sceneview.ar.scene.PlaneRenderer
// import kotlinx.coroutines.*
// import java.util.concurrent.atomic.AtomicBoolean
// import kotlin.math.*

// class ArView(
//     context: Context,
//     private val messenger: BinaryMessenger,
//     private val id: Int,
//     private val activityLifecycle: Lifecycle,
//     private val activity: Activity,
// ) : PlatformView, DefaultLifecycleObserver {

//     private val TAG: String = "ArView_Native"
//     private val mainScope = CoroutineScope(Dispatchers.Main + Job())
//     private val rootLayout: ViewGroup = FrameLayout(context)
//     private val sceneView: ARSceneView = ARSceneView(context, null)
//     private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    
//     private val isDestroyed = AtomicBoolean(false)
//     private var isCenterHitTrackingEnabled = false
//     private var isBridgeBusy = false
//     private var lastFrameTime: Long = 0
//     private var currentArFrame: Frame? = null 

//     init {
//         activityLifecycle.addObserver(this)
        
//         sceneView.apply {
//             this.lifecycle = activityLifecycle
//             this.planeRenderer.isVisible = false
//             this.planeRenderer.isEnabled = false
            
//             this.sessionConfiguration = { session, config ->
//                 config.apply {
//                     planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
//                     updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
//                     focusMode = Config.FocusMode.AUTO
//                     lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    
//                     if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                         depthMode = Config.DepthMode.AUTOMATIC
//                     }
//                 }
//             }
//         }
//         rootLayout.addView(sceneView)

//         sessionChannel.setMethodCallHandler { call, result ->
//             if (isDestroyed.get()) return@setMethodCallHandler
//             when (call.method) {
//                 "init" -> handleInit(call, result)
//                 "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
//                 "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
//                 "snapshot" -> handleSnapshot(result)
//                 "getImageIntrinsics" -> handleGetImageIntrinsics(result)
//                 "getCameraPose" -> handleGetCameraPose(result)
//                 "getProjectionMatrix" -> handleGetProjectionMatrix(result)
//                 else -> result.notImplemented()
//             }
//         }

//         sceneView.onSessionUpdated = { _, frame ->
//             currentArFrame = frame
//             if (isCenterHitTrackingEnabled && !isBridgeBusy && (System.currentTimeMillis() - lastFrameTime >= 50L)) {
//                 lastFrameTime = System.currentTimeMillis()
//                 broadcastHardwareTelemetry(frame)
//             }
//         }
//     }

//     private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
//         val showPlanes = call.argument<Boolean>("showPlanes") ?: false
//         sceneView.planeRenderer.isVisible = showPlanes
//         result.success(null)
//     }

//     override fun onDestroy(owner: LifecycleOwner) {
//         dispose()
//     }

//     private fun broadcastHardwareTelemetry(frame: Frame) {
//         val camera = frame.camera
//         val packet = mutableMapOf<String, Any?>()

//         packet["trackingState"] = camera.trackingState.name
//         if (camera.trackingState != TrackingState.TRACKING) {
//             sendTelemetryPacket(packet.filterValues { it != null } as Map<String, Any>)
//             return
//         }

//         // 1. Point Cloud & Light (Restored)
//         try { frame.acquirePointCloud().use { pc -> packet["featureCount"] = pc.points.remaining() / 4 } } catch (e: Exception) { packet["featureCount"] = 0 }
//         packet["lightIntensity"] = frame.lightEstimate.takeIf { it?.state == LightEstimate.State.VALID }?.pixelIntensity?.toDouble() ?: 1.0

//         // 2. Camera Orientation (The core of the fix)
//         val camPose = camera.displayOrientedPose
//         val q = camPose.rotationQuaternion
//         val qX = q[0].toDouble(); val qY = q[1].toDouble(); val qZ = q[2].toDouble(); val qW = q[3].toDouble()

//         // 🎯 RELATIVE TILT: Phone's pitch relative to gravity (Always active)
//         val pitch = atan2(2.0 * (qY * qZ + qX * qW), qX * qX - qY * qY - qZ * qZ + qW * qW)
//         val phoneTilt = pitch * (180.0 / PI)
//         packet["wallTilt"] = phoneTilt 

//         // 3. Hit Test Logic
//         val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
//         val bestHit = hits.firstOrNull { it.trackable is Plane } ?: hits.firstOrNull { it.trackable is DepthPoint }

//         if (bestHit != null) {
//             val hp = bestHit.hitPose
//             packet["hitType"] = if (abs(hp.yAxis[1]) < 0.5) "VERTICAL" else "HORIZONTAL"
//             packet["distance"] = sqrt(((hp.tx()-camPose.tx()).pow(2) + (hp.ty()-camPose.ty()).pow(2) + (hp.tz()-camPose.tz()).pow(2)).toDouble())
//             packet["worldPosition"] = listOf(hp.tx().toDouble(), hp.ty().toDouble(), hp.tz().toDouble())

//             // 🎯 RELATIVE ANGLE: Phone's heading vs Wall Normal
//             // We compare the phone's forward vector to the wall's normal vector
//             val camYaw = atan2(2.0 * (qY * qW - qX * qZ), qX * qX - qY * qY + qZ * qZ - qW * qW)
//             val wallNormalX = hp.zAxis[0].toDouble()
//             val wallNormalZ = hp.zAxis[2].toDouble()
//             val wallYaw = atan2(wallNormalX, wallNormalZ)
            
//             // Calculate the difference (how "square" you are to the wall)
//             var relAngle = (camYaw - wallYaw) * (180.0 / PI)
//             // Normalize to -180 to 180
//             while (relAngle > 180) relAngle -= 360
//             while (relAngle < -180) relAngle += 360
//             packet["wallAngle"] = relAngle

//         } else {
//             packet["hitType"] = "SEARCHING"
//             packet["wallAngle"] = 0.0 // No wall, no relative angle
//             packet["distance"] = null
//         }

//         sendTelemetryPacket(packet.filterValues { it != null } as Map<String, Any>)
//     }

//     private fun sendTelemetryPacket(packet: Map<String, Any>) {
//         isBridgeBusy = true
//         activity.runOnUiThread {
//             if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
//             isBridgeBusy = false
//         }
//     }

//     private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
//         val frame = currentArFrame ?: return result.error("ERR", "No Frame", null)
//         val intrinsics = frame.camera.imageIntrinsics
//         result.success(mapOf(
//             "fx" to intrinsics.focalLength[0].toDouble(), "fy" to intrinsics.focalLength[1].toDouble(),
//             "cx" to intrinsics.principalPoint[0].toDouble(), "cy" to intrinsics.principalPoint[1].toDouble(),
//             "width" to intrinsics.imageDimensions[0].toDouble(), "height" to intrinsics.imageDimensions[1].toDouble(),
//             "viewWidth" to sceneView.width.toDouble(), "viewHeight" to sceneView.height.toDouble(),
//             "lightIntensity" to (frame.lightEstimate?.pixelIntensity?.toDouble() ?: 1.0)
//         ))
//     }

//     private fun handleSnapshot(result: MethodChannel.Result) {
//         if (sceneView.width <= 0 || sceneView.height <= 0) return result.error("ERR", "Invalid View", null)
//         val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
//         PixelCopy.request(sceneView, bitmap, { res ->
//             if (res == PixelCopy.SUCCESS) {
//                 mainScope.launch(Dispatchers.IO) {
//                     val stream = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
//                     withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
//                 }
//             } else result.error("ERR", "PixelCopy failed", null)
//         }, Handler(Looper.getMainLooper()))
//     }

//     private fun matrixToArray(p: Pose): List<Double> {
//         val m = FloatArray(16); p.toMatrix(m, 0); return m.map { it.toDouble() }
//     }

//     private fun handleGetCameraPose(result: MethodChannel.Result) {
//         currentArFrame?.camera?.displayOrientedPose?.let { p -> 
//             val m = FloatArray(16); p.toMatrix(m, 0); result.success(m.map { it.toDouble() })
//         } ?: result.error("ERR", "No pose", null)
//     }

//     private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
//         val proj = FloatArray(16); currentArFrame?.camera?.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
//         result.success(proj.map { it.toDouble() })
//     }

//     override fun getView(): View = rootLayout

//     override fun dispose() {
//         if (isDestroyed.getAndSet(true)) return
//         activityLifecycle.removeObserver(this)
//         mainScope.cancel()
//         sceneView.destroy()
//     }
// }


// package net.kodified.ar_flutter_plugin_updated

// import android.app.Activity
// import android.content.Context
// import android.graphics.Bitmap
// import android.os.*
// import android.util.Log
// import android.view.*
// import android.widget.FrameLayout
// import androidx.lifecycle.DefaultLifecycleObserver
// import androidx.lifecycle.Lifecycle
// import androidx.lifecycle.LifecycleOwner
// import com.google.ar.core.*
// import io.flutter.plugin.common.BinaryMessenger
// import io.flutter.plugin.common.MethodCall
// import io.flutter.plugin.common.MethodChannel
// import io.flutter.plugin.platform.PlatformView
// import io.github.sceneview.ar.ARSceneView
// import io.github.sceneview.ar.scene.PlaneRenderer
// import kotlinx.coroutines.*
// import java.util.concurrent.atomic.AtomicBoolean
// import kotlin.math.*

// class ArView(
//     context: Context,
//     private val messenger: BinaryMessenger,
//     private val id: Int,
//     private val activityLifecycle: Lifecycle,
//     private val activity: Activity,
// ) : PlatformView, DefaultLifecycleObserver {

//     private val TAG: String = "ArView_Native"
//     private val mainScope = CoroutineScope(Dispatchers.Main + Job())
//     private val rootLayout: ViewGroup = FrameLayout(context)
//     private val sceneView: ARSceneView = ARSceneView(context, null)
//     private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    
//     private val isDestroyed = AtomicBoolean(false)
//     private var isCenterHitTrackingEnabled = false
//     private var isBridgeBusy = false
//     private var lastFrameTime: Long = 0
//     private var currentArFrame: Frame? = null 

//     init {
//         activityLifecycle.addObserver(this)
        
//         sceneView.apply {
//             this.lifecycle = activityLifecycle
            
//             // 🎯 KEEP DOTS REMOVED
//             this.planeRenderer.isVisible = false
//             this.planeRenderer.isEnabled = false
            
//             this.sessionConfiguration = { session, config ->
//                 config.apply {
//                     planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
//                     updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
//                     focusMode = Config.FocusMode.AUTO
//                     lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    
//                     if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                         depthMode = Config.DepthMode.AUTOMATIC
//                     }
//                 }
//             }
//         }
//         rootLayout.addView(sceneView)

//         sessionChannel.setMethodCallHandler { call, result ->
//             if (isDestroyed.get()) return@setMethodCallHandler
//             when (call.method) {
//                 "init" -> handleInit(call, result)
//                 "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
//                 "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
//                 "snapshot" -> handleSnapshot(result)
//                 "getImageIntrinsics" -> handleGetImageIntrinsics(result)
//                 "getCameraPose" -> handleGetCameraPose(result)
//                 "getProjectionMatrix" -> handleGetProjectionMatrix(result)
//                 else -> result.notImplemented()
//             }
//         }

//         sceneView.onSessionUpdated = { _, frame ->
//             currentArFrame = frame
//             if (isCenterHitTrackingEnabled && !isBridgeBusy && (System.currentTimeMillis() - lastFrameTime >= 50L)) {
//                 lastFrameTime = System.currentTimeMillis()
//                 broadcastHardwareTelemetry(frame)
//             }
//         }
//     }

//     private fun handleInit(call: MethodCall, result: MethodChannel.Result) {
//         val showPlanes = call.argument<Boolean>("showPlanes") ?: false
//         sceneView.planeRenderer.isVisible = showPlanes
//         result.success(null)
//     }

//     override fun onDestroy(owner: LifecycleOwner) {
//         dispose()
//     }

//     private fun broadcastHardwareTelemetry(frame: Frame) {
//         val camera = frame.camera
//         val packet = mutableMapOf<String, Any?>() // Keep this as Any? for now

//         // 1. Core Status Data
//         packet["trackingState"] = camera.trackingState.name
//         packet["trackingFailureReason"] = camera.trackingFailureReason.name

//         if (camera.trackingState != TrackingState.TRACKING) {
//             // 🎯 FIX 1: Filter out nulls before sending
//             sendTelemetryPacket(packet.filterValues { it != null } as Map<String, Any>)
//             return
//         }

//         // 2. Point Cloud / Feature Count
//         try {
//             frame.acquirePointCloud().use { pc ->
//                 packet["featureCount"] = pc.points.remaining() / 4
//             }
//         } catch (e: Exception) {
//             packet["featureCount"] = 0
//         }

//         // 3. Lighting Data
//         packet["lightIntensity"] = frame.lightEstimate.takeIf { it?.state == LightEstimate.State.VALID }
//             ?.pixelIntensity?.toDouble() ?: 1.0

//         // 4. Matrix & Pose Data
//         val camPose = camera.displayOrientedPose
//         val camArr = FloatArray(16).also { camPose.toMatrix(it, 0) }
//         packet["cameraPose"] = camArr.map { it.toDouble() }

//         val projArr = FloatArray(16).also { camera.getProjectionMatrix(it, 0, 0.1f, 100.0f) }
//         packet["projectionMatrix"] = projArr.map { it.toDouble() }

//         // 5. Hit Testing Logic
//         val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
//         val bestHit = hits.firstOrNull { it.trackable is Plane }
//             ?: hits.firstOrNull { it.trackable is DepthPoint }

//         if (bestHit != null) {
//             // STATE: A surface was successfully hit
//             val hp = bestHit.hitPose
//             val hpArr = FloatArray(16).also { hp.toMatrix(it, 0) }

//             packet["hit"] = mapOf("transform" to hpArr.map { it.toDouble() })
//             packet["worldPosition"] = listOf(hp.tx().toDouble(), hp.ty().toDouble(), hp.tz().toDouble())

//             val dx = hp.tx() - camPose.tx()
//             val dy = hp.ty() - camPose.ty()
//             val dz = hp.tz() - camPose.tz()
//             packet["distance"] = sqrt(dx * dx + dy * dy + dz * dz).toDouble()

//             val planeNormal = bestHit.trackable.let { if (it is Plane) it.centerPose.yAxis else hp.yAxis }
//             val normalY = abs(planeNormal[1]).toDouble().coerceIn(0.0, 1.0)
            
//             packet["hitType"] = if (normalY < 0.5) "VERTICAL" else "HORIZONTAL"
//             packet["wallNormal"] = listOf(planeNormal[0].toDouble(), planeNormal[1].toDouble(), planeNormal[2].toDouble())
//             packet["wallTilt"] = 90.0 - (acos(normalY) * (180.0 / PI))

//             val wallNormalX = planeNormal[0].toDouble()
//             val wallNormalZ = planeNormal[2].toDouble()
//             packet["wallAngle"] = (atan2(wallNormalX, wallNormalZ) * (180.0 / PI)) + 90.0

//         } else {
//             // STATE: No surface was hit ("Searching")
//             packet["hitType"] = "SEARCHING"
            
//             val q = camPose.rotationQuaternion
            
//             // 🎯 FIX 2: Convert quaternion components to Double for math functions
//             val qx = q[0].toDouble()
//             val qy = q[1].toDouble()
//             val qz = q[2].toDouble()
//             val qw = q[3].toDouble()

//             // Tilt (Pitch) of the camera
//             val pitch = atan2(2.0 * (qy * qz + qx * qw), qx * qx - qy * qy - qz * qz + qw * qw)
//             packet["wallTilt"] = pitch * (180.0 / PI)

//             // Angle (Yaw) of the camera
//             val yaw = atan2(2.0 * (qy * qw - qx * qz), qx * qx - qy * qy + qz * qz - qw * qw)
//             packet["wallAngle"] = yaw * (180.0 / PI)

//             // Set other hit-related values to null
//             packet["distance"] = null
//             packet["wallNormal"] = null
//             packet["worldPosition"] = null
//         }

//         // 🎯 FIX 1 (Applied again): Filter out nulls before sending
//         sendTelemetryPacket(packet.filterValues { it != null } as Map<String, Any>)
//     }


//     private fun sendTelemetryPacket(packet: Map<String, Any>) {
//         isBridgeBusy = true
//         activity.runOnUiThread {
//             if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
//             isBridgeBusy = false
//         }
//     }

//     private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
//         val frame = currentArFrame ?: return result.error("ERR", "No Frame", null)
//         val intrinsics = frame.camera.imageIntrinsics
//         result.success(mapOf(
//             "fx" to intrinsics.focalLength[0].toDouble(), "fy" to intrinsics.focalLength[1].toDouble(),
//             "cx" to intrinsics.principalPoint[0].toDouble(), "cy" to intrinsics.principalPoint[1].toDouble(),
//             "width" to intrinsics.imageDimensions[0].toDouble(), "height" to intrinsics.imageDimensions[1].toDouble(),
//             "viewWidth" to sceneView.width.toDouble(), "viewHeight" to sceneView.height.toDouble(),
//             "lightIntensity" to (frame.lightEstimate?.pixelIntensity?.toDouble() ?: 1.0)
//         ))
//     }

//     private fun handleSnapshot(result: MethodChannel.Result) {
//         if (sceneView.width <= 0 || sceneView.height <= 0) return result.error("ERR", "Invalid View", null)
//         val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
//         PixelCopy.request(sceneView, bitmap, { res ->
//             if (res == PixelCopy.SUCCESS) {
//                 mainScope.launch(Dispatchers.IO) {
//                     val stream = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
//                     withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
//                 }
//             } else result.error("ERR", "PixelCopy failed", null)
//         }, Handler(Looper.getMainLooper()))
//     }

//     private fun matrixToArray(p: Pose): List<Double> {
//         val m = FloatArray(16); p.toMatrix(m, 0); return m.map { it.toDouble() }
//     }

//     private fun handleGetCameraPose(result: MethodChannel.Result) {
//         currentArFrame?.camera?.displayOrientedPose?.let { p -> 
//             val m = FloatArray(16); p.toMatrix(m, 0); result.success(m.map { it.toDouble() })
//         } ?: result.error("ERR", "No pose", null)
//     }

//     private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
//         val proj = FloatArray(16); currentArFrame?.camera?.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
//         result.success(proj.map { it.toDouble() })
//     }

//     override fun getView(): View = rootLayout

//     override fun dispose() {
//         if (isDestroyed.getAndSet(true)) return
//         activityLifecycle.removeObserver(this)
//         mainScope.cancel()
//         sceneView.destroy()
//     }
// }

// package net.kodified.ar_flutter_plugin_updated

// import android.app.Activity
// import android.content.Context
// import android.graphics.Bitmap
// import android.os.*
// import android.util.Log
// import android.view.*
// import android.widget.FrameLayout
// import androidx.lifecycle.DefaultLifecycleObserver
// import androidx.lifecycle.Lifecycle
// import androidx.lifecycle.LifecycleOwner
// import com.google.ar.core.*
// import io.flutter.plugin.common.BinaryMessenger
// import io.flutter.plugin.common.MethodChannel
// import io.flutter.plugin.platform.PlatformView
// import io.github.sceneview.ar.ARSceneView
// import io.github.sceneview.ar.scene.PlaneRenderer
// import kotlinx.coroutines.*
// import java.util.concurrent.atomic.AtomicBoolean
// import kotlin.math.*

// class ArView(
//     context: Context,
//     private val messenger: BinaryMessenger,
//     private val id: Int,
//     private val activityLifecycle: Lifecycle,
//     private val activity: Activity,
// ) : PlatformView, DefaultLifecycleObserver {

//     private val TAG: String = "ArView_Native"
//     private val mainScope = CoroutineScope(Dispatchers.Main + Job())
//     private val rootLayout: ViewGroup = FrameLayout(context)
//     private val sceneView: ARSceneView = ARSceneView(context, null)
//     private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    
//     private val isDestroyed = AtomicBoolean(false)
//     private var isCenterHitTrackingEnabled = false
//     private var isBridgeBusy = false
//     private var lastFrameTime: Long = 0
//     private var currentArFrame: Frame? = null 

//     init {
//         activityLifecycle.addObserver(this)
        
//         sceneView.apply {
//             this.lifecycle = activityLifecycle
//             planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_ALL
            
//             this.sessionConfiguration = { session, config ->
//                 config.apply {
//                     planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
//                     updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
//                     focusMode = Config.FocusMode.AUTO
//                     lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    
//                     if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                         depthMode = Config.DepthMode.AUTOMATIC
//                     }
//                 }
//             }
//         }
//         rootLayout.addView(sceneView)

//         sessionChannel.setMethodCallHandler { call, result ->
//             if (isDestroyed.get()) return@setMethodCallHandler
//             when (call.method) {
//                 "init" -> result.success(null)
//                 "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
//                 "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
//                 "snapshot" -> handleSnapshot(result)
//                 "getImageIntrinsics" -> handleGetImageIntrinsics(result)
//                 "getCameraPose" -> handleGetCameraPose(result)
//                 "getProjectionMatrix" -> handleGetProjectionMatrix(result)
//                 else -> result.notImplemented()
//             }
//         }

//         sceneView.onSessionUpdated = { _, frame ->
//             currentArFrame = frame
//             if (isCenterHitTrackingEnabled && !isBridgeBusy && (System.currentTimeMillis() - lastFrameTime >= 50L)) {
//                 lastFrameTime = System.currentTimeMillis()
//                 broadcastHardwareTelemetry(frame)
//             }
//         }
//     }

//     override fun onDestroy(owner: LifecycleOwner) {
//         dispose()
//     }

//     // --- TELEMETRY BROADCAST ---

//     private fun broadcastHardwareTelemetry(frame: Frame) {
//         val camera = frame.camera
//         if (camera.trackingState != TrackingState.TRACKING) return

//         val packet = mutableMapOf<String, Any>()
        
//         // Accurate Lighting Intensity
//         val lightEstimate = frame.lightEstimate
//         packet["lightIntensity"] = if (lightEstimate.state == LightEstimate.State.VALID) {
//             lightEstimate.pixelIntensity.toDouble()
//         } else {
//             1.0
//         }

//         packet["cameraPose"] = matrixToArray(camera.displayOrientedPose)
//         val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
//         packet["projectionMatrix"] = proj.map { it.toDouble() }

//         // Core Hit Testing: Verify hits are on the polygon surface
//         val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
//         val bestHit = hits.firstOrNull { h -> 
//             val t = h.trackable
//             (t is Plane && t.isPoseInPolygon(h.hitPose))
//         } ?: hits.firstOrNull { h -> h.trackable is DepthPoint }

//         if (bestHit != null) {
//             val hp = bestHit.hitPose
//             packet["hit"] = mapOf("transform" to matrixToArray(hp))
//             val dist = sqrt((hp.tx()-camera.pose.tx()).pow(2) + (hp.ty()-camera.pose.ty()).pow(2) + (hp.tz()-camera.pose.tz()).pow(2)).toDouble()
//             packet["distance"] = dist
            
//             val normalY = abs(hp.yAxis[1])
//             packet["hitType"] = if (normalY < 0.5) "VERTICAL" else "HORIZONTAL"
//             packet["wallNormal"] = listOf(hp.yAxis[0].toDouble(), hp.yAxis[1].toDouble(), hp.yAxis[2].toDouble())
//             packet["wallTilt"] = 90.0 - (acos(normalY.toDouble()) * (180.0 / PI))
//         }

//         isBridgeBusy = true
//         activity.runOnUiThread {
//             if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
//             isBridgeBusy = false
//         }
//     }

//     // --- 🎯 RESTORED MISSING METHODS ---

//     private fun handleGetCameraPose(result: MethodChannel.Result) {
//         currentArFrame?.camera?.displayOrientedPose?.let { p -> 
//             result.success(matrixToArray(p)) 
//         } ?: result.error("ERR", "No pose available", null)
//     }

//     private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
//         val proj = FloatArray(16)
//         currentArFrame?.camera?.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
//         result.success(proj.map { it.toDouble() })
//     }

//     private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
//         val frame = currentArFrame ?: return result.error("ERR", "No Frame", null)
//         val intrinsics = frame.camera.imageIntrinsics
        
//         result.success(mapOf(
//             "fx" to intrinsics.focalLength[0].toDouble(),
//             "fy" to intrinsics.focalLength[1].toDouble(),
//             "cx" to intrinsics.principalPoint[0].toDouble(),
//             "cy" to intrinsics.principalPoint[1].toDouble(),
//             "width" to intrinsics.imageDimensions[0].toDouble(),
//             "height" to intrinsics.imageDimensions[1].toDouble(),
//             "viewWidth" to sceneView.width.toDouble(),
//             "viewHeight" to sceneView.height.toDouble(),
//             "lightIntensity" to (frame.lightEstimate?.pixelIntensity?.toDouble() ?: 1.0)
//         ))
//     }

//     private fun handleSnapshot(result: MethodChannel.Result) {
//         if (sceneView.width <= 0 || sceneView.height <= 0) return result.error("ERR", "Invalid View", null)
//         val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
//         try {
//             PixelCopy.request(sceneView, bitmap, { res ->
//                 if (res == PixelCopy.SUCCESS) {
//                     mainScope.launch(Dispatchers.IO) {
//                         val stream = java.io.ByteArrayOutputStream()
//                         bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
//                         val bytes = stream.toByteArray()
//                         withContext(Dispatchers.Main) { result.success(bytes) }
//                     }
//                 } else result.error("ERR", "PixelCopy failed: $res", null)
//             }, Handler(Looper.getMainLooper()))
//         } catch (e: Exception) { result.error("ERR", e.message, null) }
//     }

//     private fun matrixToArray(p: Pose): List<Double> {
//         val m = FloatArray(16); p.toMatrix(m, 0); return m.map { it.toDouble() }
//     }

//     override fun getView(): View = rootLayout

//     override fun dispose() {
//         if (isDestroyed.getAndSet(true)) return
//         activityLifecycle.removeObserver(this)
//         mainScope.cancel()
//         sceneView.destroy()
//     }
// }


// package net.kodified.ar_flutter_plugin_updated

// import android.app.Activity
// import android.content.Context
// import android.graphics.Bitmap
// import android.os.*
// import android.util.Log
// import android.view.*
// import android.widget.FrameLayout
// import androidx.lifecycle.*
// import com.google.ar.core.*
// import io.flutter.plugin.common.*
// import io.flutter.plugin.platform.PlatformView
// import io.github.sceneview.ar.ARSceneView
// import io.github.sceneview.ar.scene.PlaneRenderer
// import kotlinx.coroutines.*
// import java.util.concurrent.atomic.AtomicBoolean
// import kotlin.math.*

// class ArView(
//     context: Context,
//     private val messenger: BinaryMessenger,
//     private val id: Int,
//     private val activityLifecycle: Lifecycle,
//     private val activity: Activity,
// ) : PlatformView, LifecycleOwner, LifecycleEventObserver {

//     private val TAG: String = "ArView_Native"
//     private val mainScope = CoroutineScope(Dispatchers.Main + Job())
//     private val lifecycleRegistry = LifecycleRegistry(this)
//     private val rootLayout: ViewGroup = FrameLayout(context)
//     private val sceneView: ARSceneView = ARSceneView(context, null)
    
//     private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    
//     private val isDestroyed = AtomicBoolean(false)
//     private var isCenterHitTrackingEnabled = false
//     private var isBridgeBusy = false
//     private var lastFrameTime: Long = 0
//     private var currentArFrame: Frame? = null 

//     // Point Cloud Features restored from old code
//     private var showPointCloud = false

//     override val lifecycle: Lifecycle get() = lifecycleRegistry
//     override fun getView(): View = rootLayout 

//     init {
//         lifecycleRegistry.currentState = Lifecycle.State.CREATED
//         activityLifecycle.addObserver(this)
        
//         sceneView.apply {
//             lifecycle = lifecycleRegistry
//             planeRenderer.planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_ALL
//             sessionConfiguration = { session, config ->
//                 config.apply {
//                     planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
//                     updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
//                     focusMode = Config.FocusMode.AUTO
//                     // Restore high-quality lighting from old code
//                     lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
//                     if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                         depthMode = Config.DepthMode.AUTOMATIC
//                     }
//                 }
//             }
//         }
//         rootLayout.addView(sceneView)

//         sessionChannel.setMethodCallHandler { call, result ->
//             if (isDestroyed.get()) return@setMethodCallHandler
//             when (call.method) {
//                 "init" -> result.success(null)
//                 "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
//                 "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
//                 "showPointCloud" -> { showPointCloud = true; result.success(null) }
//                 "hidePointCloud" -> { showPointCloud = false; result.success(null) }
//                 "snapshot" -> handleSnapshot(result)
//                 "getImageIntrinsics" -> handleGetImageIntrinsics(result)
//                 "getCameraPose" -> handleGetCameraPose(result)
//                 "getProjectionMatrix" -> handleGetProjectionMatrix(result)
//                 else -> result.notImplemented()
//             }
//         }

//         sceneView.onSessionUpdated = { _, frame ->
//             currentArFrame = frame
//             // Gate to ~20fps to ensure the Pixel 7 remains stable during heavy math
//             if (isCenterHitTrackingEnabled && !isBridgeBusy && (System.currentTimeMillis() - lastFrameTime >= 50L)) {
//                 lastFrameTime = System.currentTimeMillis()
//                 broadcastHardwareTelemetry(frame)
//             }
//         }
//     }

//     private fun broadcastHardwareTelemetry(frame: Frame) {
//         val camera = frame.camera
//         if (camera.trackingState != TrackingState.TRACKING) return

//         val packet = mutableMapOf<String, Any>()
        
//         // 🎯 1. LIGHT INTENSITY (Integrated here)
//         val lightEstimate = frame.lightEstimate
//         packet["lightIntensity"] = if (lightEstimate.state == LightEstimate.State.VALID) {
//             lightEstimate.pixelIntensity.toDouble()
//         } else {
//             1.0
//         }

//         packet["cameraPose"] = matrixToArray(camera.displayOrientedPose)
//         val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
//         packet["projectionMatrix"] = proj.map { it.toDouble() }

//         // 🎯 2. HIT TEST WITH FALLBACKS (Restored from old code)
//         val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
//         // prioritize verified Planes, then DepthPoints, then Instant Placement
//         val bestHit = hits.firstOrNull { h -> 
//             val t = h.trackable
//             (t is Plane && t.isPoseInPolygon(h.hitPose)) // Ensure it's on the actual wall
//         } ?: hits.firstOrNull { h -> h.trackable is DepthPoint }

//         if (bestHit != null) {
//             val hp = bestHit.hitPose
//             packet["hit"] = mapOf("transform" to matrixToArray(hp))
//             val dist = sqrt((hp.tx()-camera.pose.tx()).pow(2) + (hp.ty()-camera.pose.ty()).pow(2) + (hp.tz()-camera.pose.tz()).pow(2)).toDouble()
//             packet["distance"] = dist
            
//             val normalY = abs(hp.yAxis[1])
//             packet["hitType"] = if (normalY < 0.5) "VERTICAL" else "HORIZONTAL"
//             packet["wallNormal"] = listOf(hp.yAxis[0].toDouble(), hp.yAxis[1].toDouble(), hp.yAxis[2].toDouble())
//             packet["wallTilt"] = 90.0 - (acos(normalY.toDouble()) * (180.0 / PI))
//         }

//         // Thermal Monitoring for Pixel 7
//         val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
//         packet["thermalStatus"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else -1

//         isBridgeBusy = true
//         activity.runOnUiThread {
//             if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
//             isBridgeBusy = false
//         }
//     }

//     private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
//         val frame = currentArFrame ?: return result.error("ERR", "No Frame", null)
//         val intrinsics = frame.camera.imageIntrinsics
        
//         // 🎯 3. LIGHT INTENSITY FOR DATABASE (Integrated here)
//         val lightEstimate = frame.lightEstimate
//         val pixelIntensity = if (lightEstimate.state == LightEstimate.State.VALID) {
//             lightEstimate.pixelIntensity.toDouble()
//         } else {
//             1.0
//         }

//         val data = mapOf(
//             "fx" to intrinsics.focalLength[0].toDouble(),
//             "fy" to intrinsics.focalLength[1].toDouble(),
//             "cx" to intrinsics.principalPoint[0].toDouble(),
//             "cy" to intrinsics.principalPoint[1].toDouble(),
//             "width" to intrinsics.imageDimensions[0].toDouble(),
//             "height" to intrinsics.imageDimensions[1].toDouble(),
//             "viewWidth" to sceneView.width.toDouble(),
//             "viewHeight" to sceneView.height.toDouble(),
//             "lightIntensity" to pixelIntensity // ✅ Added for DB storage
//         )
//         result.success(data)
//     }

//     // Snapshot remains strictly focused on PixelCopy for performance
//     private fun handleSnapshot(result: MethodChannel.Result) {
//         if (sceneView.width <= 0 || sceneView.height <= 0) return result.error("ERR", "Invalid View", null)
//         val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
//         try {
//             PixelCopy.request(sceneView, bitmap, { res ->
//                 if (res == PixelCopy.SUCCESS) {
//                     mainScope.launch(Dispatchers.IO) {
//                         val stream = java.io.ByteArrayOutputStream()
//                         bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
//                         withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
//                     }
//                 } else result.error("ERR", "PixelCopy failed: $res", null)
//             }, Handler(Looper.getMainLooper()))
//         } catch (e: Exception) { result.error("ERR", e.message, null) }
//     }

//     private fun handleGetCameraPose(result: MethodChannel.Result) {
//         currentArFrame?.camera?.displayOrientedPose?.let { p -> result.success(matrixToArray(p)) } ?: result.error("ERR", "No pose", null)
//     }

//     private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
//         val proj = FloatArray(16); currentArFrame?.camera?.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
//         result.success(proj.map { it.toDouble() })
//     }

//     private fun matrixToArray(p: Pose): List<Double> {
//         val m = FloatArray(16); p.toMatrix(m, 0); return m.map { it.toDouble() }
//     }

//     override fun dispose() {
//         if (isDestroyed.getAndSet(true)) return
//         mainScope.cancel()
//         sceneView.destroy()
//     }

//     override fun onStateChanged(s: LifecycleOwner, e: Lifecycle.Event) {
//         if (!isDestroyed.get()) {
//             if (e == Lifecycle.Event.ON_DESTROY) dispose()
//             else lifecycleRegistry.handleLifecycleEvent(e)
//         }
//     }
// }








// package net.kodified.ar_flutter_plugin_updated

// import android.app.Activity
// import android.content.Context
// import android.graphics.Bitmap
// import android.os.*
// import android.util.Log
// import android.view.*
// import android.widget.FrameLayout
// import androidx.lifecycle.*
// import com.google.ar.core.*
// import io.flutter.plugin.common.*
// import io.flutter.plugin.platform.PlatformView
// import io.github.sceneview.ar.ARSceneView
// import kotlinx.coroutines.*
// import java.util.concurrent.atomic.AtomicBoolean
// import kotlin.math.*

// class ArView(
//     context: Context,
//     private val messenger: BinaryMessenger,
//     private val id: Int,
//     private val activityLifecycle: Lifecycle,
// ) : PlatformView, LifecycleOwner, LifecycleEventObserver {

//     private val TAG: String = "ArView_Native"
//     private val mainScope = CoroutineScope(Dispatchers.Main + Job())
//     private val lifecycleRegistry = LifecycleRegistry(this)
//     private val rootLayout: ViewGroup = FrameLayout(context)
//     private val sceneView: ARSceneView = ARSceneView(context, null)
    
//     // 🎯 FIXED: Channel must be 'arsession' for the plugin library to find it
//     private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    
//     private val isDestroyed = AtomicBoolean(false)
//     private var isCenterHitTrackingEnabled = false
//     private var isBridgeBusy = false
//     private var lastFrameTime: Long = 0
//     private var currentArFrame: Frame? = null 

//     override val lifecycle: Lifecycle get() = lifecycleRegistry
//     override fun getView(): View = rootLayout 

//     init {
//         lifecycleRegistry.currentState = Lifecycle.State.CREATED
//         activityLifecycle.addObserver(this)
        
//         sceneView.apply {
//             lifecycle = lifecycleRegistry
//             sessionConfiguration = { session, config ->
//                 config.apply {
//                     planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
//                     updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
//                     focusMode = Config.FocusMode.AUTO
//                     if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                         depthMode = Config.DepthMode.AUTOMATIC
//                     }
//                 }
//             }
//         }
//         rootLayout.addView(sceneView)

//         sessionChannel.setMethodCallHandler { call, result ->
//             if (isDestroyed.get()) return@setMethodCallHandler
//             when (call.method) {
//                 "init" -> result.success(null)
//                 "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
//                 "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
//                 "snapshot" -> handleSnapshot(result)
//                 "getImageIntrinsics" -> handleGetIntrinsics(result)
//                 "getCameraPose" -> handleGetCameraPose(result)
//                 "getProjectionMatrix" -> handleGetProjectionMatrix(result)
//                 else -> result.notImplemented()
//             }
//         }

//         sceneView.onSessionUpdated = { _, frame ->
//             currentArFrame = frame
//             // Gate to 20fps to ensure the Pixel 7 doesn't overheat or choke its GPU buffer
//             if (isCenterHitTrackingEnabled && !isBridgeBusy && (System.currentTimeMillis() - lastFrameTime >= 50L)) {
//                 lastFrameTime = System.currentTimeMillis()
//                 broadcastHardwareTelemetry(frame)
//             }
//         }
//     }

//     private fun broadcastHardwareTelemetry(frame: Frame) {
//         val camera = frame.camera
//         if (camera.trackingState != TrackingState.TRACKING) return

//         val packet = mutableMapOf<String, Any>()
//         packet["cameraPose"] = matrixToArray(camera.displayOrientedPose)
//         val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
//         packet["projectionMatrix"] = proj.map { it.toDouble() }

//         frame.acquirePointCloud()?.use { pc ->
//             packet["featureCount"] = pc.points.remaining() / 4
//         }

//         val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
//         val bestHit = hits.firstOrNull { h -> h.trackable is Plane }
//             ?: hits.firstOrNull { h -> h.trackable is DepthPoint }
//             ?: frame.hitTestInstantPlacement(sceneView.width / 2f, sceneView.height / 2f, 2.0f).firstOrNull()

//         if (bestHit != null) {
//             val hp = bestHit.hitPose
//             packet["hit"] = mapOf("transform" to matrixToArray(hp))
//             val dist = sqrt((hp.tx()-camera.pose.tx()).pow(2) + (hp.ty()-camera.pose.ty()).pow(2) + (hp.tz()-camera.pose.tz()).pow(2)).toDouble()
//             packet["distance"] = dist
            
//             val normalY = abs(hp.yAxis[1])
//             packet["hitType"] = if (normalY < 0.5) "VERTICAL" else "HORIZONTAL"
//             packet["wallNormal"] = listOf(hp.yAxis[0].toDouble(), hp.yAxis[1].toDouble(), hp.yAxis[2].toDouble())
//             packet["wallTilt"] = 90.0 - (acos(normalY.toDouble()) * (180.0 / PI))
//         }

//         isBridgeBusy = true
//         Handler(Looper.getMainLooper()).post {
//             if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
//             isBridgeBusy = false
//         }
//     }

//     private fun handleSnapshot(result: MethodChannel.Result) {
//         val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
//         try {
//             PixelCopy.request(sceneView, bitmap, { res ->
//                 if (res == PixelCopy.SUCCESS) {
//                     mainScope.launch(Dispatchers.IO) {
//                         val stream = java.io.ByteArrayOutputStream()
//                         bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
//                         val bytes = stream.toByteArray()
//                         withContext(Dispatchers.Main) { result.success(bytes) }
//                     }
//                 } else result.error("ERR", "PixelCopy failed: $res", null)
//             }, Handler(Looper.getMainLooper()))
//         } catch (e: Exception) {
//             result.error("ERR", e.message, null)
//         }
//     }

//     private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
//     val frame = currentArFrame ?: return result.error("NO_FRAME", "Frame null", null)
//     val intrinsics = frame.camera.imageIntrinsics
    
//     // 🎯 THE FIX: Provide both Sensor AND View dimensions
//     // This allows us to calculate scale without hardcoded values
//     val data = mapOf(
//         "fx" to intrinsics.focalLength[0].toDouble(),
//         "fy" to intrinsics.focalLength[1].toDouble(),
//         "cx" to intrinsics.principalPoint[0].toDouble(),
//         "cy" to intrinsics.principalPoint[1].toDouble(),
//         "width" to intrinsics.imageDimensions[0].toDouble(),  // Sensor Width
//         "height" to intrinsics.imageDimensions[1].toDouble(), // Sensor Height
//         "viewWidth" to sceneView.width.toDouble(),           // 🎯 Actual Screen Pixels
//         "viewHeight" to sceneView.height.toDouble()          // 🎯 Actual Screen Pixels
//     )
//     result.success(data)
// }

//     private fun handleGetCameraPose(result: MethodChannel.Result) {
//         currentArFrame?.camera?.displayOrientedPose?.let { p -> result.success(matrixToArray(p)) } ?: result.error("ERR", "No pose", null)
//     }

//     private fun handleGetProjectionMatrix(result: MethodChannel.Result) {
//         val proj = FloatArray(16)
//         currentArFrame?.camera?.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
//         result.success(proj.map { it.toDouble() })
//     }

//     private fun matrixToArray(p: Pose): List<Double> {
//         val m = FloatArray(16); p.toMatrix(m, 0); return m.map { it.toDouble() }
//     }

//     override fun dispose() {
//         if (isDestroyed.getAndSet(true)) return
//         mainScope.cancel()
//         sceneView.destroy()
//     }

//     override fun onStateChanged(s: LifecycleOwner, e: Lifecycle.Event) {
//         if (!isDestroyed.get()) {
//             if (e == Lifecycle.Event.ON_DESTROY) dispose()
//             else lifecycleRegistry.handleLifecycleEvent(e)
//         }
//     }
// }









// package net.kodified.ar_flutter_plugin_updated

// import android.app.Activity
// import android.content.Context
// import android.graphics.Bitmap
// import android.os.*
// import android.util.Log
// import android.view.*
// import android.widget.FrameLayout
// import androidx.lifecycle.*
// import com.google.ar.core.*
// import io.flutter.plugin.common.*
// import io.flutter.plugin.platform.PlatformView
// import io.github.sceneview.ar.ARSceneView
// import kotlinx.coroutines.*
// import java.util.concurrent.atomic.AtomicBoolean
// import kotlin.math.*

// class ArView(
//     context: Context,
//     private val messenger: BinaryMessenger,
//     private val id: Int,
//     private val activityLifecycle: Lifecycle,
// ) : PlatformView, LifecycleOwner, LifecycleEventObserver {

//     private val TAG: String = "ArView_Native"
//     private val mainScope = CoroutineScope(Dispatchers.Main + Job())
//     private val lifecycleRegistry = LifecycleRegistry(this)
//     private val rootLayout: ViewGroup = FrameLayout(context)
//     private val sceneView: ARSceneView = ARSceneView(context, null)
//     private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    
//     private val isDestroyed = AtomicBoolean(false)
//     private var isCenterHitTrackingEnabled = false
//     private var isBridgeBusy = false
//     private var lastFrameTime: Long = 0
//     private var currentArFrame: Frame? = null // Store frame for intrinsics

//     override val lifecycle: Lifecycle get() = lifecycleRegistry
//     override fun getView(): View = rootLayout 

//     init {
//         lifecycleRegistry.currentState = Lifecycle.State.CREATED
//         activityLifecycle.addObserver(this)
        
//         sceneView.apply {
//             lifecycle = lifecycleRegistry
//             sessionConfiguration = { session, config ->
//                 config.apply {
//                     planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
//                     updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
//                     focusMode = Config.FocusMode.AUTO
//                     if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                         depthMode = Config.DepthMode.AUTOMATIC
//                     }
//                 }
//             }
//         }
//         rootLayout.addView(sceneView)

//         sessionChannel.setMethodCallHandler { call, result ->
//             when (call.method) {
//                 "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
//                 "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
//                 "snapshot" -> handleSnapshot(result)
//                 "getImageIntrinsics" -> handleGetIntrinsics(result)
//                 else -> result.success(null)
//             }
//         }

//         sceneView.onSessionUpdated = { _, frame ->
//             currentArFrame = frame
//             if (isCenterHitTrackingEnabled && !isBridgeBusy && (System.currentTimeMillis() - lastFrameTime >= 50L)) {
//                 lastFrameTime = System.currentTimeMillis()
//                 broadcastHardwareTelemetry(frame)
//             }
//         }
//     }

//     private fun broadcastHardwareTelemetry(frame: Frame) {
//         val camera = frame.camera
//         if (camera.trackingState != TrackingState.TRACKING) return

//         val packet = mutableMapOf<String, Any>()
//         packet["cameraPose"] = matrixToArray(camera.displayOrientedPose)
//         val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
//         packet["projectionMatrix"] = proj.map { it.toDouble() }

//         frame.acquirePointCloud()?.use { pc ->
//             packet["featureCount"] = pc.points.remaining() / 4
//         }

//         val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
//         val bestHit = hits.firstOrNull { h -> h.trackable is Plane }
//             ?: hits.firstOrNull { h -> h.trackable is DepthPoint }
//             ?: frame.hitTestInstantPlacement(sceneView.width / 2f, sceneView.height / 2f, 2.0f).firstOrNull()

//         if (bestHit != null) {
//             val hp = bestHit.hitPose
//             val cp = camera.pose
//             packet["hit"] = mapOf("transform" to matrixToArray(hp))
//             packet["distance"] = sqrt((hp.tx()-cp.tx()).pow(2) + (hp.ty()-cp.ty()).pow(2) + (hp.tz()-cp.tz()).pow(2)).toDouble()
            
//             val normalY = abs(hp.yAxis[1])
//             packet["hitType"] = if (normalY < 0.5) "VERTICAL" else "HORIZONTAL"
//             packet["wallNormal"] = listOf(hp.yAxis[0].toDouble(), hp.yAxis[1].toDouble(), hp.yAxis[2].toDouble())
//             packet["wallTilt"] = 90.0 - (acos(normalY.toDouble()) * (180.0 / PI))
//         }

//         isBridgeBusy = true
//         Handler(Looper.getMainLooper()).post {
//             if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
//             isBridgeBusy = false
//         }
//     }

//     private fun handleSnapshot(result: MethodChannel.Result) {
//         val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
//         try {
//             PixelCopy.request(sceneView, bitmap, { res ->
//                 if (res == PixelCopy.SUCCESS) {
//                     mainScope.launch(Dispatchers.IO) {
//                         val stream = java.io.ByteArrayOutputStream()
//                         bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
//                         val bytes = stream.toByteArray()
//                         withContext(Dispatchers.Main) { result.success(bytes) }
//                     }
//                 } else result.error("ERR_SNAPSHOT", "PixelCopy failed: $res", null)
//             }, Handler(Looper.getMainLooper()))
//         } catch (e: Exception) {
//             result.error("ERR_FATAL", e.message, null)
//         }
//     }

//     private fun handleGetIntrinsics(result: MethodChannel.Result) {
//         val intrinsics = currentArFrame?.camera?.imageIntrinsics
//         if (intrinsics != null) {
//             val map = HashMap<String, Double>()
//             map["fx"] = intrinsics.focalLength[0].toDouble()
//             map["fy"] = intrinsics.focalLength[1].toDouble()
//             map["width"] = intrinsics.imageDimensions[0].toDouble()
//             map["height"] = intrinsics.imageDimensions[1].toDouble()
//             result.success(map)
//         } else {
//             result.error("ERR", "Intrinsics unavailable", null)
//         }
//     }

//     private fun matrixToArray(p: Pose): List<Double> {
//         val m = FloatArray(16); p.toMatrix(m, 0); return m.map { it.toDouble() }
//     }

//     override fun dispose() {
//         if (isDestroyed.getAndSet(true)) return
//         mainScope.cancel()
//         sceneView.destroy()
//     }

//     override fun onStateChanged(s: LifecycleOwner, e: Lifecycle.Event) {
//         if (!isDestroyed.get()) lifecycleRegistry.handleLifecycleEvent(e)
//     }
// }




// package net.kodified.ar_flutter_plugin_updated

// import android.content.Context
// import android.graphics.Bitmap
// import android.os.*
// import android.util.Log
// import android.view.*
// import android.widget.FrameLayout
// import androidx.lifecycle.*
// import com.google.ar.core.*
// import io.flutter.plugin.common.*
// import io.flutter.plugin.platform.PlatformView
// import io.github.sceneview.ar.ARSceneView
// import kotlinx.coroutines.*
// import java.util.concurrent.atomic.AtomicBoolean
// import kotlin.math.*

// class ArView(
//     context: Context,
//     messenger: BinaryMessenger,
//     id: Int,
//     private val activityLifecycle: Lifecycle,
// ) : PlatformView, LifecycleOwner, LifecycleEventObserver {

//     private val mainScope = CoroutineScope(Dispatchers.Main + Job())
//     private val lifecycleRegistry = LifecycleRegistry(this)
//     private val rootLayout: ViewGroup = FrameLayout(context)
//     private val sceneView: ARSceneView = ARSceneView(context, null)
//     private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    
//     private val isDestroyed = AtomicBoolean(false)
//     private var isCenterHitTrackingEnabled = false
//     private var isBridgeBusy = false
//     private var lastFrameTime: Long = 0

//     override val lifecycle: Lifecycle get() = lifecycleRegistry
//     override fun getView(): View = rootLayout 

//     init {
//         lifecycleRegistry.currentState = Lifecycle.State.CREATED
//         activityLifecycle.addObserver(this)
        
//         sceneView.apply {
//             lifecycle = lifecycleRegistry
//             sessionConfiguration = { session, config ->
//                 config.apply {
//                     planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
//                     updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
//                     focusMode = Config.FocusMode.AUTO
//                     // Production depth is critical for non-textured walls
//                     if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                         depthMode = Config.DepthMode.AUTOMATIC
//                     }
//                 }
//             }
//         }
//         rootLayout.addView(sceneView)

//         sessionChannel.setMethodCallHandler { call, result ->
//             when (call.method) {
//                 "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
//                 "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
//                 "snapshot" -> handleSnapshot(result)
//                 else -> result.success(null)
//             }
//         }

//         sceneView.onSessionUpdated = { _, frame ->
//             if (isCenterHitTrackingEnabled && !isBridgeBusy && (System.currentTimeMillis() - lastFrameTime >= 48L)) {
//                 lastFrameTime = System.currentTimeMillis()
//                 broadcastHardwareTelemetry(frame)
//             }
//         }
//     }

//     private fun broadcastHardwareTelemetry(frame: Frame) {
//         val session = sceneView.session ?: return
//         val camera = frame.camera
//         if (camera.trackingState != TrackingState.TRACKING) return

//         val packet = mutableMapOf<String, Any>()
        
//         // Core Poses
//         val cameraPose = camera.displayOrientedPose
//         packet["cameraPose"] = matrixToArray(cameraPose)
//         val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
//         packet["projectionMatrix"] = proj.map { it.toDouble() }

//         // Dots
//         frame.acquirePointCloud()?.use { pc ->
//             packet["featureCount"] = pc.points.remaining() / 4
//         }

//         // 🎯 IMPROVED HIT TESTING
//         val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
//         val bestHit = hits.firstOrNull { h -> h.trackable is Plane }
//             ?: hits.firstOrNull { h -> h.trackable is DepthPoint }
//             ?: frame.hitTestInstantPlacement(sceneView.width / 2f, sceneView.height / 2f, 2.0f).firstOrNull()

//         if (bestHit != null) {
//             val hp = bestHit.hitPose
//             packet["hitType"] = if (abs(hp.yAxis[1]) < 0.5) "VERTICAL" else "HORIZONTAL"
//             packet["hit"] = mapOf("transform" to matrixToArray(hp))
            
//             // 🎯 DISTANCE CALC
//             packet["distance"] = sqrt(
//                 (hp.tx() - cameraPose.tx()).toDouble().pow(2) + 
//                 (hp.ty() - cameraPose.ty()).toDouble().pow(2) + 
//                 (hp.tz() - cameraPose.tz()).toDouble().pow(2)
//             )

//             // 🎯 THE "40 DEGREE" FIX: Send the surface normal for perspective math
//             packet["wallNormal"] = listOf(hp.yAxis[0].toDouble(), hp.yAxis[1].toDouble(), hp.yAxis[2].toDouble())
            
//             // Gravity Tilt (is the wall leaning?)
//             val normalY = abs(hp.yAxis[1])
//             packet["wallTilt"] = 90.0 - (acos(normalY.toDouble()) * (180.0 / PI))
//         }

//         isBridgeBusy = true
//         Handler(Looper.getMainLooper()).post {
//             if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
//             isBridgeBusy = false
//         }
//     }

//     private fun matrixToArray(p: Pose): List<Double> {
//         val m = FloatArray(16); p.toMatrix(m, 0); return m.map { it.toDouble() }
//     }

//     private fun handleSnapshot(result: MethodChannel.Result) {
//         val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
//         PixelCopy.request(sceneView, bitmap, { res ->
//             if (res == PixelCopy.SUCCESS) {
//                 mainScope.launch(Dispatchers.IO) {
//                     val stream = java.io.ByteArrayOutputStream()
//                     bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
//                     withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
//                 }
//             } else result.error("ERR", "Copy failed", null)
//         }, Handler(Looper.getMainLooper()))
//     }

//     override fun dispose() {
//         if (isDestroyed.getAndSet(true)) return
//         mainScope.cancel()
//         sceneView.destroy()
//     }

//     override fun onStateChanged(s: LifecycleOwner, e: Lifecycle.Event) {
//         if (!isDestroyed.get()) {
//             if (e == Lifecycle.Event.ON_DESTROY) dispose()
//             else lifecycleRegistry.handleLifecycleEvent(e)
//         }
//     }
// }


// package net.kodified.ar_flutter_plugin_updated

// import android.app.Activity
// import android.content.Context
// import android.graphics.Bitmap
// import android.os.*
// import android.util.Log
// import android.view.*
// import android.widget.FrameLayout
// import androidx.lifecycle.*
// import com.google.ar.core.*
// import net.kodified.ar_flutter_plugin_updated.Serialization.Deserializers.deserializeMatrix4
// import net.kodified.ar_flutter_plugin_updated.Serialization.Serialization.serializeAnchor
// import net.kodified.ar_flutter_plugin_updated.Serialization.Serialization.serializeHitResult
// import io.flutter.plugin.common.*
// import io.flutter.plugin.platform.PlatformView
// import io.github.sceneview.ar.ARSceneView
// import io.github.sceneview.math.*
// import kotlinx.coroutines.*
// import java.util.ArrayList
// import java.util.concurrent.atomic.AtomicBoolean
// import kotlin.math.*

// class ArView(
//     context: Context,
//     private val activity: Activity,
//     private val activityLifecycle: Lifecycle,
//     messenger: BinaryMessenger,
//     id: Int,
// ) : PlatformView, LifecycleOwner, LifecycleEventObserver {

//     private val TAG: String = "ArView_Native"
//     private val mainScope = CoroutineScope(Dispatchers.Main + Job())
//     private val lifecycleRegistry = LifecycleRegistry(this)
//     private val rootLayout: ViewGroup = FrameLayout(context)
//     private val sceneView: ARSceneView = ARSceneView(context, null)
//     private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    
//     private val isDestroyed = AtomicBoolean(false)
//     @Volatile private var isCenterHitTrackingEnabled = false
//     @Volatile private var isBridgeBusy = false
//     @Volatile private var hardwareUnlocked = false

//     // 🎯 FIXED: Variables declared in class scope to resolve Unresolved Reference errors
//     private var currentArFrame: Frame? = null
//     private var lastFrameTime: Long = 0
//     private var lastLogTime: Long = 0

//     override val lifecycle: Lifecycle get() = lifecycleRegistry
//     override fun getView(): View = rootLayout 

//     init {
//         logHardware("BOOT: Perception Stack 2.3.1 Build Verified")
//         lifecycleRegistry.currentState = Lifecycle.State.CREATED
//         activityLifecycle.addObserver(this)
        
//         sceneView.apply {
//             lifecycle = lifecycleRegistry
//             sessionConfiguration = { session, config ->
//                 config.apply {
//                     planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
//                     updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
//                     focusMode = Config.FocusMode.AUTO
//                     instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
//                     if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                         depthMode = Config.DepthMode.AUTOMATIC
//                     }
//                 }
//             }
//         }
//         rootLayout.addView(sceneView)

//         sessionChannel.setMethodCallHandler { call, result ->
//             if (isDestroyed.get()) return@setMethodCallHandler
//             when (call.method) {
//                 "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
//                 "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
//                 "dispose" -> dispose()
//                 "getImageIntrinsics" -> handleGetImageIntrinsics(result)
//                 "snapshot" -> handleSnapshot(result)
//                 else -> result.success(null)
//             }
//         }
//         setupSceneViewListeners()
//     }

//     private fun setupSceneViewListeners() {
//         sceneView.onSessionUpdated = { session, frame ->
//             if (!isDestroyed.get()) {
//                 currentArFrame = frame
                
//                 // Hardware Unlock: Pixel 7 specific sync
//                 if (!hardwareUnlocked && frame.camera.trackingState == TrackingState.TRACKING) {
//                     val config = session.config
//                     config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
//                     session.configure(config)
//                     hardwareUnlocked = true
//                 }

//                 if (isCenterHitTrackingEnabled && !isBridgeBusy && (System.currentTimeMillis() - lastFrameTime >= 33L)) {
//                     lastFrameTime = System.currentTimeMillis()
//                     broadcastHardwareTelemetry(frame)
//                 }
                
//                 try { frame.acquirePointCloud()?.use { } } catch (e: Exception) { }
//             }
//         }
//     }

//     private fun broadcastHardwareTelemetry(frame: Frame) {
//         val frame = session?.update() ?: return
//         val camera = frame.camera

//         // 🛡️ LOCK: Only send data if the hardware is actually tracking
//         if (camera.trackingState != TrackingState.TRACKING) return

//         val packet = mutableMapOf<String, Any>()
        
//         // 🎯 INITIALIZE ALL KEYS (No more Nulls)
//         packet["featureCount"] = 0
//         packet["distance"] = 0.0
//         packet["wallTilt"] = 0.0
//         packet["hitType"] = "NONE"
        
//         // Core Matrices
//         val camPose = camera.displayOrientedPose
//         packet["cameraPose"] = matrixToArray(camPose)
//         val projArr = FloatArray(16); camera.getProjectionMatrix(projArr, 0, 0.01f, 100.0f)
//         packet["projectionMatrix"] = projArr.map { it.toDouble() }
//         packet["trackingState"] = camera.trackingState.name

//         // Dots Count
//         var dotsSeen = 0
//         try {
//             frame.acquirePointCloud()?.use { pc ->
//                 dotsSeen = pc.points.remaining() / 4
//                 packet["featureCount"] = dotsSeen
//             }
//         } catch (e: Exception) { }

//         // Normalize Center Coordinates
//         val viewCoords = floatArrayOf(sceneView.width / 2f, sceneView.height / 2f)
//         val normalizedCoords = FloatArray(2)
//         frame.transformCoordinates2d(Coordinates2d.VIEW, viewCoords, Coordinates2d.VIEW_NORMALIZED, normalizedCoords)

//         // 🎯 HIT TEST FIX: Request Instant Placement with a preference for VERTICAL
//         val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)

//         // 🎯 SELECTION FIX: Find the hit with the "most vertical" normal (Y-axis near 0)
//         val bestHit = hits.firstOrNull { it.trackable is Plane } 
//               ?: hits.firstOrNull { it.trackable is DepthPoint }
//               ?: frame.hitTestInstantPlacement(sceneView.width / 2f, sceneView.height / 2f, 2.0f).firstOrNull()

//         if (bestHit != null) {
//             val hp = bestHit.hitPose
//             val cp = camera.pose
            
//             // Calculate the Normal Vector of the surface
//             // In ARCore, the Y-axis (index 1) of a Plane's HitPose is the Normal
//             val normal = hp.yAxis 
            
//             packet["hitType"] = when {
//                 Math.abs(normal[1]) > 0.7 -> "HORIZONTAL" // Floor/Ceiling
//                 else -> "VERTICAL" // Wall
//             }
            
//             packet["hit"] = mapOf("transform" to matrixToArray(hp))
            
//             // Precise Euclidean Distance
//             packet["distance"] = Math.sqrt(
//                 Math.pow((hp.tx() - cp.tx()).toDouble(), 2.0) +
//                 Math.pow((hp.ty() - cp.ty()).toDouble(), 2.0) +
//                 Math.pow((hp.tz() - cp.tz()).toDouble(), 2.0)
//             )
            
//             // wallTilt: 0 = Vertical, 90 = Horizontal
//             packet["wallTilt"] = Math.toDegrees(Math.acos(Math.abs(normal[1]).toDouble()))
//         }

//         // 🔥 Send to Flutter
//         isBridgeBusy = true
//         activity.runOnUiThread {
//             if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
//             isBridgeBusy = false
//         }
//     }

//     override fun dispose() {
//         if (isDestroyed.getAndSet(true)) return
//         mainScope.cancel()
//         activity.runOnUiThread {
//             activityLifecycle.removeObserver(this@ArView)
//             sceneView.onSessionUpdated = null
//             sessionChannel.setMethodCallHandler(null)
//             try {
//                 sceneView.session?.let { s ->
//                     val c = s.config
//                     c.depthMode = Config.DepthMode.DISABLED
//                     c.instantPlacementMode = Config.InstantPlacementMode.DISABLED
//                     s.configure(c)
//                     s.pause()
//                 }
//                 sceneView.destroy()
//             } catch (e: Exception) { }
//             rootLayout.removeAllViews()
//         }
//     }

//     private fun matrixToArray(pose: Pose): List<Double> {
//         val m = FloatArray(16); pose.toMatrix(m, 0); return m.map { it.toDouble() }
//     }

//     private fun handleGetImageIntrinsics(result: MethodChannel.Result) {
//         currentArFrame?.camera?.imageIntrinsics?.let { i -> 
//             result.success(mapOf("fx" to i.focalLength[0].toDouble(), "fy" to i.focalLength[1].toDouble(), "cx" to i.principalPoint[0].toDouble(), "cy" to i.principalPoint[1].toDouble(), "width" to i.imageDimensions[0].toDouble(), "height" to i.imageDimensions[1].toDouble())) 
//         } ?: result.error("ERR", "Hardware not reporting", null)
//     }

//     private fun handleSnapshot(result: MethodChannel.Result) {
//         val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
//         PixelCopy.request(sceneView, bitmap, { res ->
//             if (res == PixelCopy.SUCCESS) {
//                 mainScope.launch(Dispatchers.IO) {
//                     val stream = java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
//                     withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
//                 }
//             } else result.error("ERR", "Copy Fail", null)
//         }, Handler(Looper.getMainLooper()))
//     }
    
//     override fun onStateChanged(s: LifecycleOwner, e: Lifecycle.Event) { if (!isDestroyed.get()) { if (e == Lifecycle.Event.ON_DESTROY) dispose() else lifecycleRegistry.handleLifecycleEvent(e) } }
//     private fun logHardware(msg: String) { Log.d(TAG, "🟢 [HARDWARE] $msg") }
// }