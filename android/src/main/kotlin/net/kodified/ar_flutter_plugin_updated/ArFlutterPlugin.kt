// android/src/main/kotlin/net/kodified/ar_flutter_plugin_updated/ArFlutterPlugin.kt
package net.kodified.ar_flutter_plugin_updated

import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import android.app.Activity

class ArFlutterPlugin: FlutterPlugin, ActivityAware {
    private var lifecycle: LifecycleRegistry? = null
    private var activity: Activity? = null

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        binding.platformViewRegistry.registerViewFactory(
            "ar_view", 
            ArViewFactory(binding.binaryMessenger, this)
        )
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {}

    // 🎯 ROBUST LIFECYCLE BRIDGE: Works on all devices without Unresolved Reference errors
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        // Use the activity directly if it's a LifecycleOwner (which FlutterActivity is)
        if (activity is LifecycleOwner) {
            lifecycle = (activity as LifecycleOwner).lifecycle as? LifecycleRegistry
        }
    }

    fun getLifecycle(): Lifecycle? = lifecycle
    fun getActivity(): Activity? = activity

    override fun onDetachedFromActivityForConfigChanges() { 
        activity = null
    }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }
    override fun onDetachedFromActivity() { 
        activity = null
    }
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
