package net.kodified.ar_flutter_plugin_updated

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class ArViewFactory(private val messenger: BinaryMessenger) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val creationParams = args as Map<String?, Any?>?
        // Note: You'll need to pass the activity and lifecycle here 
        // as we defined in the previous ArView constructor
        return ArView(context, messenger, viewId) 
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