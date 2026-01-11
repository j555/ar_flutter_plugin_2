// android/src/main/kotlin/net/kodified/ar_flutter_plugin_updated/ArViewFactory.kt
package net.kodified.ar_flutter_plugin_updated

import android.content.Context
import android.app.Activity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import androidx.lifecycle.Lifecycle

class ArViewFactory(
    private val messenger: BinaryMessenger,
    private val plugin: ArFlutterPlugin
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val lifecycle = plugin.getLifecycle()
        val activity = plugin.getActivity()

        // 🎯 Fallback: If the plugin hasn't attached to activity yet, use context
        val finalActivity = activity ?: (context as? Activity)
            ?: throw IllegalStateException("ARView requires an Activity context")
            
        val finalLifecycle = lifecycle 
            ?: (finalActivity as? LifecycleOwner)?.lifecycle
            ?: throw IllegalStateException("ARView requires a LifecycleOwner")

        return ArView(
            context = context,
            messenger = messenger,
            id = viewId,
            activityLifecycle = finalLifecycle,
            activity = finalActivity
        )
    }
}


// package net.kodified.ar_flutter_plugin_updated

// import android.app.Activity
// import android.content.Context
// import io.flutter.plugin.common.BinaryMessenger
// import io.flutter.plugin.common.StandardMessageCodec
// import io.flutter.plugin.platform.PlatformView
// import io.flutter.plugin.platform.PlatformViewFactory
// import androidx.lifecycle.Lifecycle

// class ArViewFactory(
//     private val messenger: BinaryMessenger,
//     private val activity: Activity,
//     private val lifecycle: Lifecycle
// ) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

//     override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
//         return ArView(context, activity, lifecycle, messenger, viewId)
//     }
// }