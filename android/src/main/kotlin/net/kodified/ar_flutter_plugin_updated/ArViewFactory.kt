package net.kodified.ar_flutter_plugin_updated

import android.content.Context
import android.app.Activity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class ArViewFactory(
    private val messenger: BinaryMessenger,
    private val plugin: ArFlutterPlugin
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val lifecycle = plugin.getLifecycle() 
            ?: throw IllegalStateException("ARView requires a valid Activity Lifecycle")

        // We cast context to Activity to ensure we have the window context for ARCore
        val activity = context as? Activity 
            ?: throw IllegalStateException("ARView requires an Activity context")

        return ArView(
            context = context,
            messenger = messenger,
            id = viewId,
            activityLifecycle = lifecycle,
            activity = activity
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