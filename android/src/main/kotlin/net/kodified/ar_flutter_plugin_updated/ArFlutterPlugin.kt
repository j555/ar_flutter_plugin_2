package net.kodified.ar_flutter_plugin_updated

import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import androidx.lifecycle.Lifecycle
import io.flutter.embedding.engine.plugins.lifecycle.FlutterLifecycleAdapter

class ArFlutterPlugin: FlutterPlugin, ActivityAware {
    private var lifecycle: Lifecycle? = null

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        // Register the View Factory with the string key "ar_view"
        // This MUST match the viewType used in your Flutter code
        binding.platformViewRegistry.registerViewFactory(
            "ar_view", 
            ArViewFactory(binding.binaryMessenger, this)
        )
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {}

    // 🎯 LIFECYCLE BRIDGE: This captures the Activity and its Lifecycle for the AR Session
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(binding)
        // Pass the activity and lifecycle to your factory if needed, 
        // but here we store it to be requested by the Factory's 'create' method
    }

    fun getLifecycle(): Lifecycle? = lifecycle

    override fun onDetachedFromActivityForConfigChanges() { lifecycle = null }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        lifecycle = FlutterLifecycleAdapter.getActivityLifecycle(binding)
    }
    override fun onDetachedFromActivity() { lifecycle = null }
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
