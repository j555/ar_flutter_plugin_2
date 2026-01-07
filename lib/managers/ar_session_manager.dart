// lib/managers/ar_session_manager.dart
import 'dart:math' show sqrt;
import 'dart:typed_data';
import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/models/ar_anchor.dart';
import 'package:ar_flutter_plugin_2/models/ar_hittest_result.dart';
import 'package:ar_flutter_plugin_2/utils/json_converters.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:vector_math/vector_math_64.dart';

typedef ARHitResultHandler = void Function(List<ARHitTestResult> hits);
typedef ARPlaneResultHandler = void Function(int planeCount);
typedef ErrorHandler = void Function(String error);

class ARSessionManager {
  late MethodChannel _channel;
  final bool debug;
  final BuildContext buildContext;
  final PlaneDetectionConfig planeDetectionConfig;
  final int id;
  late ARHitResultHandler onPlaneOrPointTap;
  ARPlaneResultHandler? onPlaneDetected;
  ErrorHandler? onError;

  ARSessionManager(this.id, this.buildContext, this.planeDetectionConfig, {this.debug = false}) {
    _channel = MethodChannel('arsession_$id'); 
    _channel.setMethodCallHandler(_platformCallHandler);
  }

  Future<Map<String, double>?> getImageIntrinsics() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('getImageIntrinsics');
      if (result == null) return null;
      
      // Cast to the expected type
      return Map<String, double>.from(result);
    } catch (e) {
      if (debug) print("getImageIntrinsics Error: $e");
      return null;
    }
  }

  Future<Map<String, dynamic>?> captureBundle() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('captureBundle');
      return result != null ? Map<String, dynamic>.from(result) : null;
    } catch (e) {
      return null;
    }
  }

  // === FIX: Hit Test ===
  Future<List<ARHitTestResult>?> hitTest(double x, double y) async {
    try {
      final List<dynamic>? hitResult = await _channel.invokeMethod('hitTest', {'x': x, 'y': y});
      if (hitResult == null) return null;
      return hitResult.map((e) => ARHitTestResult.fromJson(Map<String, dynamic>.from(e))).toList();
    } catch (e) {
      if (debug) print("HitTest Error: $e");
      return null;
    }
  }

  Future<Matrix4?> getCameraPose() async {
    try {
      final poseList = await _channel.invokeMethod<List<dynamic>>('getCameraPose', {});
      if (poseList == null) return null;
      return MatrixConverter().fromJson(poseList);
    } catch (e) { return null; }
  }

  Future<Matrix4?> getProjectionMatrix() async {
    try {
      final serialized = await _channel.invokeMethod<List<dynamic>>('getProjectionMatrix');
      if (serialized == null) return null;
      return MatrixConverter().fromJson(serialized.cast<double>());
    } catch (e) { 
      debugPrint("🚨 Error getting Projection Matrix: $e");
      return null; 
    }
  }

  Future<Map<String, dynamic>?> getLightEstimate() async {
    try {
      final estimate = await _channel.invokeMethod<Map<dynamic, dynamic>>('getLightEstimate');
      if (estimate != null) return Map<String, dynamic>.from(estimate);
    } catch (e) {}
    return null;
  }
  
  // === RESTORED METHODS FOR 2D ===
  Future<Matrix4?> getPose(ARAnchor anchor) async {
    try {
      final poseList = await _channel.invokeMethod<List<dynamic>>('getAnchorPose', {"anchorId": anchor.name});
      if (poseList == null) return null;
      return MatrixConverter().fromJson(poseList);
    } catch (e) { return null; }
  }
  
  // Standard Platform Handler
  Future<void> _platformCallHandler(MethodCall call) {
    try {
      switch (call.method) {
        case 'onPlaneDetected': if (onPlaneDetected != null) onPlaneDetected!(1); break;
        case 'onPlaneOrPointTap': 
           if (onPlaneOrPointTap != null) {
              final raw = call.arguments as List<dynamic>;
              final results = raw.map((e) => ARHitTestResult.fromJson(Map<String, dynamic>.from(e))).toList();
              onPlaneOrPointTap(results);
           }
           break;
        case 'dispose': _channel.invokeMethod<void>("dispose"); break;
      }
    } catch (e) { print(e); }
    return Future.value();
  }

  // Init
  onInitialize({
    bool showAnimatedGuide = true,
    bool showFeaturePoints = false,
    bool showPlanes = true,
    String? customPlaneTexturePath,
    bool showWorldOrigin = false,
    bool handleTaps = true,
    bool handlePans = false,
    bool handleRotation = false,
    int? planeDetectionConfig,
    bool enableDepth = false,
    int lightEstimationMode = 1,
  }) {
    _channel.invokeMethod<void>('init', {
      'showAnimatedGuide': showAnimatedGuide,
      'showFeaturePoints': showFeaturePoints,
      'planeDetectionConfig': planeDetectionConfig ?? this.planeDetectionConfig.index,
      'showPlanes': showPlanes,
      'customPlaneTexturePath': customPlaneTexturePath,
      'showWorldOrigin': showWorldOrigin,
      'handleTaps': handleTaps,
      'handlePans': handlePans,
      'handleRotation': handleRotation,
      'enableDepth': enableDepth,
      'lightEstimation': lightEstimationMode,
    });
  }
  
  dispose() async { try { await _channel.invokeMethod<void>("dispose"); } catch (e) {} }
  Future<ImageProvider> snapshot() async {
    final result = await _channel.invokeMethod<Uint8List>('snapshot');
    return MemoryImage(result!);
  }
  
  void showPlanes(bool show) => _channel.invokeMethod<void>('showPlanes', {"showPlanes": show});
  void showFeaturePoints(bool show) => _channel.invokeMethod<void>('showFeaturePoints', {"showFeaturePoints": show});
  void hidePointCloud(bool hide) => _channel.invokeMethod<void>('hidePointCloud', {"hide": hide});
}








// import 'dart:math' show sqrt;
// import 'dart:typed_data';

// import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
// import 'package:ar_flutter_plugin_2/models/ar_anchor.dart';
// import 'package:ar_flutter_plugin_2/models/ar_hittest_result.dart';
// import 'package:ar_flutter_plugin_2/utils/json_converters.dart';
// import 'package:flutter/material.dart';
// import 'package:flutter/services.dart';
// import 'package:vector_math/vector_math_64.dart';
// import 'package:ar_flutter_plugin_2/models/ar_node.dart'; // Ensure this is imported

// // Type definitions to enforce a consistent use of the API
// typedef ARHitResultHandler = void Function(List<ARHitTestResult> hits);
// typedef ARPlaneResultHandler = void Function(int planeCount);
// typedef ErrorHandler = void Function(String error);

// /// Manages the session configuration, parameters and events of an [ARView]
// class ARSessionManager {
//   /// Platform channel used for communication from and to [ARSessionManager]
//   late MethodChannel _channel;

//   /// Debugging status flag. If true, all platform calls are printed. Defaults to false.
//   final bool debug;

//   /// Context of the [ARView] widget that this manager is attributed to
//   final BuildContext buildContext;

//   /// Determines the types of planes ARCore and ARKit should show
//   final PlaneDetectionConfig planeDetectionConfig;
  
//   /// The unique identifier for this session, matching the ARView's viewId.
//   final int id;

//   /// Receives hit results from user taps with tracked planes or feature points
//   late ARHitResultHandler onPlaneOrPointTap;

//   /// Receives total number of Planes when a plane is detected and added to the view
//   ARPlaneResultHandler? onPlaneDetected;

//   /// Callback that is triggered once error is triggered
//   ErrorHandler? onError;

//   ARSessionManager(this.id, this.buildContext, this.planeDetectionConfig, 
//       {this.debug = false}) {
//     _channel = MethodChannel('arsession_$id'); 
//     _channel.setMethodCallHandler(_platformCallHandler);
//     if (debug) {
//       print("ARSessionManager initialized");
//     }
//   }

//   // === NEW: Required for Live Distance Logic ===
//   Future<List<ARHitTestResult>?> hitTest(double x, double y) async {
//     try {
//       final List<dynamic>? hitResult = await _channel.invokeMethod('hitTest', {'x': x, 'y': y});
//       if (hitResult == null) return null;
      
//       return hitResult.map((e) {
//         return ARHitTestResult.fromJson(Map<String, dynamic>.from(e));
//       }).toList();
//     } catch (e) {
//       if (debug) print("HitTest Error: $e");
//       return null;
//     }
//   }

//   /// Returns the camera pose in Matrix4 format with respect to the world coordinate system of the [ARView]
//   Future<Matrix4?> getCameraPose() async {
//     try {
//       final poseList =
//           await _channel.invokeMethod<List<dynamic>>('getCameraPose', {});
//       if (poseList == null) return null;
//       final poseMatrix = MatrixConverter().fromJson(poseList);
//       return poseMatrix;
//     } catch (e) {
//       return null;
//     }
//   }

//   /// Returns the camera projection matrix in Matrix4 format
//   Future<Matrix4?> getProjectionMatrix() async {
//     try {
//       final serializedProjectionMatrix =
//           await _channel.invokeMethod<Float64List>('getProjectionMatrix');
//       if (serializedProjectionMatrix == null) {
//         return null;
//       }
//       return MatrixConverter().fromJson(serializedProjectionMatrix.toList());

//     } catch (e) {
//       print('Error caught getting projection matrix: ' + e.toString());
//       return null;
//     }
//   }
  
//   // --- NEW FEATURE: Light Estimation ---
//   Future<Map<String, dynamic>?> getLightEstimate() async {
//     try {
//       final estimate = await _channel.invokeMethod<Map<dynamic, dynamic>>('getLightEstimate');
//       if (estimate != null) {
//         return Map<String, dynamic>.from(estimate);
//       }
//     } catch (e) { }
//     return null;
//   }

//   /// Returns the given anchor pose in Matrix4 format with respect to the world coordinate system of the [ARView]
//   Future<Matrix4?> getPose(ARAnchor anchor) async {
//     try {
//       if (anchor.name.isEmpty) {
//         throw Exception("Anchor can not be resolved. Anchor name is empty.");
//       }
//       final poseList =
//           await _channel.invokeMethod<List<dynamic>>('getAnchorPose', {
//         "anchorId": anchor.name,
//       });
//       if (poseList == null) return null;
//       final poseMatrix = MatrixConverter().fromJson(poseList);
//       return poseMatrix;

//     } catch (e) {
//       print('Error caught in getPose: ' + e.toString());
//       return null;
//     }
//   }

//   /// Returns the distance in meters between @anchor1 and @anchor2.
//   Future<double?> getDistanceBetweenAnchors(
//       ARAnchor anchor1, ARAnchor anchor2) async {
//     var anchor1Pose = await getPose(anchor1);
//     var anchor2Pose = await getPose(anchor2);
//     var anchor1Translation = anchor1Pose?.getTranslation();
//     var anchor2Translation = anchor2Pose?.getTranslation();
//     if (anchor1Translation != null && anchor2Translation != null) {
//       return getDistanceBetweenVectors(anchor1Translation, anchor2Translation);
//     } else {
//       return null;
//     }
//   }

//   /// Returns the distance in meters between @anchor and device's camera.
//   Future<double?> getDistanceFromAnchor(ARAnchor anchor) async {
//     Matrix4? cameraPose = await getCameraPose();
//     Matrix4? anchorPose = await getPose(anchor);
//     Vector3? cameraTranslation = cameraPose?.getTranslation();
//     Vector3? anchorTranslation = anchorPose?.getTranslation();
//     if (anchorTranslation != null && cameraTranslation != null) {
//       return getDistanceBetweenVectors(anchorTranslation, cameraTranslation);
//     } else {
//       return null;
//     }
//   }

//   /// Returns the distance in meters between @vector1 and @vector2.
//   double getDistanceBetweenVectors(Vector3 vector1, Vector3 vector2) {
//     num dx = vector1.x - vector2.x;
//     num dy = vector1.y - vector2.y;
//     num dz = vector1.z - vector2.z;
//     double distance = sqrt(dx * dx + dy * dy + dz * dz);
//     return distance;
//   }

//   //Disable Camera
//   void disableCamera() {
//     _channel.invokeMethod<void>('disableCamera');
//   }

//   //Enable Camera
//   void enableCamera() {
//     _channel.invokeMethod<void>('enableCamera');
//   }

//   // Show or hide feature points (White Dots)
//   void showFeaturePoints(bool showFeaturePoints){
//     _channel.invokeMethod<void>('showFeaturePoints', {
//       "showFeaturePoints": showFeaturePoints,
//     });
//   } 

//   // Manually hides/shows the visible point cloud nodes (Blue Dots)
//   void hidePointCloud(bool hide) {
//     _channel.invokeMethod<void>('hidePointCloud', {
//       "hide": hide,
//     });
//   } 

//   // Show or hide planes (Plane Meshes)
//   void showPlanes(bool showPlanes){
//     _channel.invokeMethod<void>('showPlanes', {
//       "showPlanes": showPlanes,
//     });
//   }

//   // === RESTORED METHODS FOR 2D IMAGES ===
//   Future<bool> addNode(ARNode node) async {
//     try {
//       return await _channel.invokeMethod<bool>('addNode', node.toMap()) ?? false;
//     } catch (e) {
//       return false;
//     }
//   }

//   Future<void> removeNode(ARNode node) async {
//     try {
//       await _channel.invokeMethod<String>('removeNode', {'name': node.name});
//     } catch (e) {
//       print(e);
//     }
//   }

//   Future<void> _platformCallHandler(MethodCall call) {
//     if (debug) {
//       print('_platformCallHandler call ${call.method} ${call.arguments}');
//     }
//     try {
//       switch (call.method) {
//         case 'onError':
//           if (onError != null) {
//             onError!(call.arguments[0]);
//             print(call.arguments);
//           }
//           else{
//             ScaffoldMessenger.of(buildContext).showSnackBar(SnackBar(
//                 content: Text(call.arguments[0]),
//                 action: SnackBarAction(
//                     label: 'HIDE',
//                     onPressed:
//                     ScaffoldMessenger.of(buildContext).hideCurrentSnackBar)));
//           }
//           break;
//         case 'onPlaneOrPointTap':
//           if (onPlaneOrPointTap != null) {
//             final rawHitTestResults = call.arguments as List<dynamic>;
//             final serializedHitTestResults = rawHitTestResults
//                 .map(
//                     (hitTestResult) => Map<String, dynamic>.from(hitTestResult))
//                 .toList();
//             final hitTestResults = serializedHitTestResults.map((e) {
//               return ARHitTestResult.fromJson(e);
//             }).toList();
//             onPlaneOrPointTap(hitTestResults);
//           }
//           break;
//         case 'onPlaneDetected':
//           if (onPlaneDetected != null) {
//             final planeCountResult = 1; 
//             onPlaneDetected!(planeCountResult);
//           }
//           break;
//         case 'dispose':
//           _channel.invokeMethod<void>("dispose");
//           break;
//         default:
//           if (debug) {
//             print('Unimplemented method ${call.method} ');
//           }
//       }
//     } catch (e) {
//       print('Error caught in _platformCallHandler: ' + e.toString());
//     }
//     return Future.value();
//   }

//   onInitialize({
//     bool showAnimatedGuide = true,
//     bool showFeaturePoints = false,
//     bool showPlanes = true,
//     String? customPlaneTexturePath,
//     bool showWorldOrigin = false,
//     bool handleTaps = true,
//     bool handlePans = false,
//     bool handleRotation = false,
//     int? planeDetectionConfig,
//     bool enableDepth = false,
//     int lightEstimationMode = 1,
//   }) {
//     // DEBUG LOG: Print what we are sending to native
//     print("ARSessionManager: Initializing with config: $planeDetectionConfig (Class default: ${this.planeDetectionConfig.index}) Depth: $enableDepth");

//     _channel.invokeMethod<void>('init', {
//       'showAnimatedGuide': showAnimatedGuide,
//       'showFeaturePoints': showFeaturePoints,
//       'planeDetectionConfig': planeDetectionConfig ?? this.planeDetectionConfig.index,
//       'showPlanes': showPlanes,
//       'customPlaneTexturePath': customPlaneTexturePath,
//       'showWorldOrigin': showWorldOrigin,
//       'handleTaps': handleTaps,
//       'handlePans': handlePans,
//       'handleRotation': handleRotation,
//       'enableDepth': enableDepth, // Pass new flag to native
//       'lightEstimation': lightEstimationMode,
//     });
//   }


//   /// Dispose the AR view on the platforms to pause the scenes and disconnect the platform handlers.
//   dispose() async {
//     try {
//       await _channel.invokeMethod<void>("dispose");
//     } catch (e) {
//       print(e);
//     }
//   }

//   /// Returns a future ImageProvider that contains a screenshot of the current AR Scene
//   Future<ImageProvider> snapshot() async {
//     final result = await _channel.invokeMethod<Uint8List>('snapshot');
//     return MemoryImage(result!);
//   }
// }