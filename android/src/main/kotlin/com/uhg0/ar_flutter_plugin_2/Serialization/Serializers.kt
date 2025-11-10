package com.uhg0.ar_flutter_plugin_2.Serialization

import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toMatrix
import io.github.sceneview.node.Node
import java.util.HashMap

object Serialization {
    fun serializeHitResult(hitResult: HitResult): HashMap<String, Any> {
        val serializedHitResult = HashMap<String, Any>()
        serializedHitResult["distance"] = hitResult.distance.toDouble()
        serializedHitResult["transform"] = serializePose(hitResult.hitPose)

        val trackable = hitResult.trackable
        // Check if trackable is a Plane and is being tracked
        if (trackable is Plane && trackable.trackingState == com.google.ar.core.TrackingState.TRACKING) {
            serializedHitResult["type"] = 1 // Type 1 for Plane
            // We can serialize the plane's anchor
            val planeAnchorMap = serializeAnchor(trackable.createAnchor(trackable.centerPose))
            serializedHitResult["anchor"] = planeAnchorMap
        } else {
            // Treat as a feature point
            serializedHitResult["type"] = 0 // Type 0 for Point
        }

        return serializedHitResult
    }

    // ==========================================================
    // CUSTOM CODE START: Added serializeAnchor function
    // This function is required by ArView.kt
    // ==========================================================
    fun serializeAnchor(anchor: Anchor): Map<String, Any?> {
        val anchorMap = mutableMapOf<String, Any?>()
        
        // Use a unique hash code as the name if cloud ID is not available
        anchorMap["name"] = anchor.cloudAnchorId ?: "anchor_${anchor.hashCode()}"
        anchorMap["transform"] = serializePose(anchor.pose)
        anchorMap["cloudanchorid"] = anchor.cloudAnchorId

        // Check if the anchor is a plane anchor
        val plane = anchor.trackable as? Plane
        if (plane != null) {
            anchorMap["type"] = 1 // Type 1 for Plane
            anchorMap["centerPose"] = serializePose(plane.centerPose) // Use plane's center pose
            anchorMap["extent"] = mapOf("width" to plane.extentX, "height" to plane.extentZ) // Use width/height
            anchorMap["alignment"] = plane.type.ordinal
        } else {
             anchorMap["type"] = 0 // Type 0 for other anchor types
        }

        return anchorMap
    }
    // ==========================================================
    // CUSTOM CODE END
    // ==========================================================

    fun serializePose(pose: Pose): List<Double> {
        val matrix = FloatArray(16)
        pose.toMatrix(matrix, 0)
        return matrix.map { it.toDouble() }
    }

    fun serializeLocalTransformation(node: Node?): Map<String, Any?> {
        if (node == null) {
            return emptyMap()
        }
        val transformMap = mutableMapOf<String, Any?>()
        transformMap["name"] = node.name
        // Use worldTransform to get the final matrix
        transformMap["transform"] = node.worldTransform.toMatrix().data.map { it.toDouble() }
        return transformMap
    }

    fun serializeMatrix(matrix: Transform): List<Double> {
        return matrix.toMatrix().data.map { it.toDouble() }
    }
}