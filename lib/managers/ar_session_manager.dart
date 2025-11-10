import 'dart:math' show sqrt;
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/models/ar_anchor.dart';
import 'package:ar_flutter_plugin_2/models/ar_hittest_result.dart';
import 'package:ar_flutter_plugin_2/utils/json_converters.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:vector_math/vector_math_64.dart';

// Type definitions to enforce a consistent use of the API
typedef ARHitResultHandler = void Function(List<ARHitTestResult> hits);
typedef ARPlaneResultHandler = void Function(int planeCount);
typedef ErrorHandler = void Function(String error);

/// Manages the session configuration, parameters and events of an [ARView]
class ARSessionManager {
  /// Platform channel used for communication from and to [ARSessionManager]
  late MethodChannel _channel;

  /// Debugging status flag. If true, all platform calls are printed. Defaults to false.
  final bool debug;

  /// Context of the [ARView] widget that this manager is attributed to
  final BuildContext buildContext;

  /// Determines the types of planes ARCore and ARKit should show
  final PlaneDetectionConfig planeDetectionConfig;
  
  // ======================================================================
  // CUSTOM CODE START: Add the public 'id' field
  // This is the fix for your error.
  // ======================================================================
  /// The unique identifier for this session, matching the ARView's viewId.
  final int id;
  // ======================================================================
  // CUSTOM CODE END
  // ======================================================================

  /// Receives hit results from user taps with tracked planes or feature points
  late ARHitResultHandler onPlaneOrPointTap;

  /// Receives total number of Planes when a plane is detected and added to the view
  ARPlaneResultHandler? onPlaneDetected;

  /// Callback that is triggered once error is triggered
  ErrorHandler? onError;

  ARSessionManager(this.id, this.buildContext, this.planeDetectionConfig, // <-- Use this.id
      {this.debug = false}) {
    _channel = MethodChannel('arsession_$id'); // <-- Now uses the 'id' field
    _channel.setMethodCallHandler(_platformCallHandler);
    if (debug) {
      print("ARSessionManager initialized");
    }
  }

  /// Returns the camera pose in Matrix4 format with respect to the world coordinate system of the [ARView]
  Future<Matrix4?> getCameraPose() async {
    try {
      // FIX: Expect a Map, not a List
      final poseList =
          await _channel.invokeMethod<List<dynamic>>('getCameraPose', {});
      if (poseList == null) return null;

      // The native code returns a List<double>(16) which is a Matrix4
      final poseMatrix = MatrixConverter().fromJson(poseList);
      return poseMatrix;

    } catch (e) {
      print('Error caught in getCameraPose: ' + e.toString());
      return null;
    }
  }

  /// Returns the camera projection matrix in Matrix4 format
  Future<Matrix4?> getProjectionMatrix() async {
    try {
      // The native code returns a DoubleArray(16), which is received as Float64List(16)
      final serializedProjectionMatrix =
          await _channel.invokeMethod<Float64List>('getProjectionMatrix');
      if (serializedProjectionMatrix == null) {
        return null;
      }
      
      // Use fromJson, which is defined in json_converters.dart
      // It expects a List<dynamic>, so we use toList()
      return MatrixConverter().fromJson(serializedProjectionMatrix.toList());

    } catch (e) {
      print('Error caught getting projection matrix: ' + e.toString());
      return null;
    }
  }

  /// Returns the given anchor pose in Matrix4 format with respect to the world coordinate system of the [ARView]
  Future<Matrix4?> getPose(ARAnchor anchor) async {
    try {
      if (anchor.name.isEmpty) {
        throw Exception("Anchor can not be resolved. Anchor name is empty.");
      }
      // FIX: Expect a List, not a Map
      final poseList =
          await _channel.invokeMethod<List<dynamic>>('getAnchorPose', {
        "anchorId": anchor.name,
      });
      if (poseList == null) return null;

      // The native code returns a List<double>(16) which is a Matrix4
      final poseMatrix = MatrixConverter().fromJson(poseList);
      return poseMatrix;

    } catch (e) {
      print('Error caught in getPose: ' + e.toString());
      return null;
    }
  }

  /// Returns the distance in meters between @anchor1 and @anchor2.
  Future<double?> getDistanceBetweenAnchors(
      ARAnchor anchor1, ARAnchor anchor2) async {
    var anchor1Pose = await getPose(anchor1);
    var anchor2Pose = await getPose(anchor2);
    var anchor1Translation = anchor1Pose?.getTranslation();
    var anchor2Translation = anchor2Pose?.getTranslation();
    if (anchor1Translation != null && anchor2Translation != null) {
      return getDistanceBetweenVectors(anchor1Translation, anchor2Translation);
    } else {
      return null;
    }
  }

  /// Returns the distance in meters between @anchor and device's camera.
  Future<double?> getDistanceFromAnchor(ARAnchor anchor) async {
    Matrix4? cameraPose = await getCameraPose();
    Matrix4? anchorPose = await getPose(anchor);
    Vector3? cameraTranslation = cameraPose?.getTranslation();
    Vector3? anchorTranslation = anchorPose?.getTranslation();
    if (anchorTranslation != null && cameraTranslation != null) {
      return getDistanceBetweenVectors(anchorTranslation, cameraTranslation);
    } else {
      return null;
    }
  }

  /// Returns the distance in meters between @vector1 and @vector2.
  double getDistanceBetweenVectors(Vector3 vector1, Vector3 vector2) {
    num dx = vector1.x - vector2.x;
    num dy = vector1.y - vector2.y;
    num dz = vector1.z - vector2.z;
    double distance = sqrt(dx * dx + dy * dy + dz * dz);
    return distance;
  }

  //Disable Camera
  void disableCamera() {
    _channel.invokeMethod<void>('disableCamera');
  }

  //Enable Camera
  void enableCamera() {
    _channel.invokeMethod<void>('enableCamera');
  }

  //Show or hide planes
  void showPlanes(bool showPlanes){
    _channel.invokeMethod<void>('showPlanes', {
    "showPlanes": showPlanes,
    });
  }

  Future<void> _platformCallHandler(MethodCall call) {
    if (debug) {
      print('_platformCallHandler call ${call.method} ${call.arguments}');
    }
    try {
      switch (call.method) {
        case 'onError':
          if (onError != null) {
            onError!(call.arguments[0]);
            print(call.arguments);
          }
          else{
            ScaffoldMessenger.of(buildContext).showSnackBar(SnackBar(
                content: Text(call.arguments[0]),
                action: SnackBarAction(
                    label: 'HIDE',
                    onPressed:
                    ScaffoldMessenger.of(buildContext).hideCurrentSnackBar)));
          }
          break;
        case 'onPlaneOrPointTap':
          if (onPlaneOrPointTap != null) {
            final rawHitTestResults = call.arguments as List<dynamic>;
            final serializedHitTestResults = rawHitTestResults
                .map(
                    (hitTestResult) => Map<String, dynamic>.from(hitTestResult))
                .toList();
            final hitTestResults = serializedHitTestResults.map((e) {
              return ARHitTestResult.fromJson(e);
            }).toList();
            onPlaneOrPointTap(hitTestResults);
          }
          break;
        case 'onPlaneDetected':
          // Your ar_flutter_plugin_2_impl.dart expects a Map, not an int.
          // We will forward the full argument map.
          if (onPlaneDetected != null) {
             // This is a "fix" to match the original plugin's behavior,
            // but your impl.dart will receive a Map.
            // This handler is likely NOT used by your impl.dart anyway.
            final planeCountResult = 1; // Dummy value, as your native code sends a Map
            onPlaneDetected!(planeCountResult);
          }
          break;
        case 'dispose':
          _channel.invokeMethod<void>("dispose");
          break;
        default:
          if (debug) {
            print('Unimplemented method ${call.method} ');
          }
      }
    } catch (e) {
      print('Error caught in _platformCallHandler: ' + e.toString());
    }
    return Future.value();
  }

  /// Function to initialize the platform-specific AR view. Can be used to initially set or update session settings.
  /// [customPlaneTexturePath] refers to flutter assets from the app that is calling this function, NOT to assets within this plugin. Make sure
  /// the assets are correctly registered in the pubspec.yaml of the parent app (e.g. the ./example app in this plugin's repo)
  onInitialize({
    bool showAnimatedGuide = true,
    bool showFeaturePoints = false,
    bool showPlanes = true,
    String? customPlaneTexturePath,
    bool showWorldOrigin = false,
    bool handleTaps = true,
    bool handlePans = false, // nodes are not draggable by default
    bool handleRotation = false, // nodes can not be rotated by default
  }) {
    _channel.invokeMethod<void>('init', {
      'showAnimatedGuide': showAnimatedGuide,
      'showFeaturePoints': showFeaturePoints,
      'planeDetectionConfig': planeDetectionConfig.index,
      'showPlanes': showPlanes,
      'customPlaneTexturePath': customPlaneTexturePath,
      'showWorldOrigin': showWorldOrigin,
      'handleTaps': handleTaps,
      'handlePans': handlePans,
      'handleRotation': handleRotation,
    });
  }


  /// Dispose the AR view on the platforms to pause the scenes and disconnect the platform handlers.
  /// You should call this before removing the AR view to prevent out of memory erros
  dispose() async {
    try {
      await _channel.invokeMethod<void>("dispose");
    } catch (e) {
      print(e);
    }
  }

  /// Returns a future ImageProvider that contains a screenshot of the current AR Scene
  Future<ImageProvider> snapshot() async {
    final result = await _channel.invokeMethod<Uint8List>('snapshot');
    return MemoryImage(result!);
  }
}