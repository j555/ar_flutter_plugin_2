package net.kodified.ar_flutter_plugin_updated

import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

class ArFlutterPlugin: FlutterPlugin, ActivityAware {
    private var lifecycle: androidx.lifecycle.Lifecycle? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        // Register the PlatformView factory
        flutterPluginBinding.platformViewRegistry.registerViewFactory(
            "ar_view", 
            ArViewFactory(flutterPluginBinding.binaryMessenger)
        )
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {}

    // ActivityAware methods to provide the lifecycle to ArView
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        // This is where we get the activity lifecycle for ArView
    }

    override fun onDetachedFromActivityForConfigChanges() {}
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}
    override fun onDetachedFromActivity() {}
}


// package net.kodified.ar_flutter_plugin_updated

// import android.app.Activity
// import androidx.lifecycle.Lifecycle
// import androidx.lifecycle.LifecycleOwner
// import io.flutter.embedding.engine.plugins.FlutterPlugin
// import io.flutter.embedding.engine.plugins.activity.ActivityAware
// import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
// import net.kodified.ar_flutter_plugin_updated.ArViewFactory

// class ArFlutterPlugin: FlutterPlugin, ActivityAware {
//     private var activity: Activity? = null
//     private var lifecycle: Lifecycle? = null
//     private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null

//     override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
//         flutterPluginBinding = binding
//     }

//     override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
//         flutterPluginBinding = null
//     }

//     override fun onAttachedToActivity(binding: ActivityPluginBinding) {
//         activity = binding.activity
//         lifecycle = (activity as LifecycleOwner).lifecycle
        
//         flutterPluginBinding?.let { flutterBinding ->
//             // NOTE: The view factory ID "ar_flutter_plugin_2" MUST NOT change.
//             // This is the "viewType" string that the Dart-side ARView widget uses
//             // to identify which native view to create.
//             flutterBinding.platformViewRegistry.registerViewFactory(
//                 "ar_flutter_plugin_2",
//                 ArViewFactory(
//                     messenger = flutterBinding.binaryMessenger,
//                     activity = activity!!,
//                     lifecycle = lifecycle!!
//                 )
//             )
//         }
//     }

//     override fun onDetachedFromActivityForConfigChanges() {
//         activity = null
//         lifecycle = null
//     }

//     override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
//         activity = binding.activity
//         lifecycle = (activity as LifecycleOwner).lifecycle
        
//         flutterPluginBinding?.let { flutterBinding ->
//             // Re-register with the same "ar_flutter_plugin_2" ID
//             flutterBinding.platformViewRegistry.registerViewFactory(
//                 "ar_flutter_plugin_2",
//                 ArViewFactory(
//                     messenger = flutterBinding.binaryMessenger,
//                     activity = activity!!,
//                     lifecycle = lifecycle!!
//                 )
//             )
//         }
//     }

//     override fun onDetachedFromActivity() {
//         activity = null
//         lifecycle = null
//     }
// }
