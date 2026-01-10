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
import io.flutter.plugin.common.*
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
) : PlatformView, LifecycleOwner, LifecycleEventObserver {

    private val TAG: String = "ArView_Native"
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val rootLayout: ViewGroup = FrameLayout(context)
    private val sceneView: ARSceneView = ARSceneView(context, null)
    private val sessionChannel = MethodChannel(messenger, "arsession_$id")
    
    private val isDestroyed = AtomicBoolean(false)
    private var isCenterHitTrackingEnabled = false
    private var isBridgeBusy = false
    private var lastFrameTime: Long = 0
    private var currentArFrame: Frame? = null // Store frame for intrinsics

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override fun getView(): View = rootLayout 

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        activityLifecycle.addObserver(this)
        
        sceneView.apply {
            lifecycle = lifecycleRegistry
            sessionConfiguration = { session, config ->
                config.apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }
                }
            }
        }
        rootLayout.addView(sceneView)

        sessionChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startCenterHitTracking" -> { isCenterHitTrackingEnabled = true; result.success(null) }
                "stopCenterHitTracking" -> { isCenterHitTrackingEnabled = false; result.success(null) }
                "snapshot" -> handleSnapshot(result)
                "getImageIntrinsics" -> handleGetIntrinsics(result)
                else -> result.success(null)
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

    private fun broadcastHardwareTelemetry(frame: Frame) {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return

        val packet = mutableMapOf<String, Any>()
        packet["cameraPose"] = matrixToArray(camera.displayOrientedPose)
        val proj = FloatArray(16); camera.getProjectionMatrix(proj, 0, 0.1f, 100.0f)
        packet["projectionMatrix"] = proj.map { it.toDouble() }

        frame.acquirePointCloud()?.use { pc ->
            packet["featureCount"] = pc.points.remaining() / 4
        }

        val hits = frame.hitTest(sceneView.width / 2f, sceneView.height / 2f)
        val bestHit = hits.firstOrNull { h -> h.trackable is Plane }
            ?: hits.firstOrNull { h -> h.trackable is DepthPoint }
            ?: frame.hitTestInstantPlacement(sceneView.width / 2f, sceneView.height / 2f, 2.0f).firstOrNull()

        if (bestHit != null) {
            val hp = bestHit.hitPose
            val cp = camera.pose
            packet["hit"] = mapOf("transform" to matrixToArray(hp))
            packet["distance"] = sqrt((hp.tx()-cp.tx()).pow(2) + (hp.ty()-cp.ty()).pow(2) + (hp.tz()-cp.tz()).pow(2)).toDouble()
            
            val normalY = abs(hp.yAxis[1])
            packet["hitType"] = if (normalY < 0.5) "VERTICAL" else "HORIZONTAL"
            packet["wallNormal"] = listOf(hp.yAxis[0].toDouble(), hp.yAxis[1].toDouble(), hp.yAxis[2].toDouble())
            packet["wallTilt"] = 90.0 - (acos(normalY.toDouble()) * (180.0 / PI))
        }

        isBridgeBusy = true
        Handler(Looper.getMainLooper()).post {
            if (!isDestroyed.get()) sessionChannel.invokeMethod("onUnifiedUpdate", packet)
            isBridgeBusy = false
        }
    }

    private fun handleSnapshot(result: MethodChannel.Result) {
        val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(sceneView, bitmap, { res ->
                if (res == PixelCopy.SUCCESS) {
                    mainScope.launch(Dispatchers.IO) {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        val bytes = stream.toByteArray()
                        withContext(Dispatchers.Main) { result.success(bytes) }
                    }
                } else result.error("ERR_SNAPSHOT", "PixelCopy failed: $res", null)
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            result.error("ERR_FATAL", e.message, null)
        }
    }

    private fun handleGetIntrinsics(result: MethodChannel.Result) {
        val intrinsics = currentArFrame?.camera?.imageIntrinsics
        if (intrinsics != null) {
            val map = HashMap<String, Double>()
            map["fx"] = intrinsics.focalLength[0].toDouble()
            map["fy"] = intrinsics.focalLength[1].toDouble()
            map["width"] = intrinsics.imageDimensions[0].toDouble()
            map["height"] = intrinsics.imageDimensions[1].toDouble()
            result.success(map)
        } else {
            result.error("ERR", "Intrinsics unavailable", null)
        }
    }

    private fun matrixToArray(p: Pose): List<Double> {
        val m = FloatArray(16); p.toMatrix(m, 0); return m.map { it.toDouble() }
    }

    override fun dispose() {
        if (isDestroyed.getAndSet(true)) return
        mainScope.cancel()
        sceneView.destroy()
    }

    override fun onStateChanged(s: LifecycleOwner, e: Lifecycle.Event) {
        if (!isDestroyed.get()) lifecycleRegistry.handleLifecycleEvent(e)
    }
}




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