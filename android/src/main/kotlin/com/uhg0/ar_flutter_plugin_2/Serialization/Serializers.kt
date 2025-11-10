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

        // ==========================================================
        // CUSTOMIZATION: This is the fix for "Unresolved reference 'trackable'"
        // ==========================================================
        // We must create an anchor to get the trackable, and then detach it.
        val anchor = hitResult.createAnchor()
        val trackable = anchor.trackable
        
        if (trackable is Plane && trackable.trackingState == com.google.ar.core.TrackingState.TRACKING) {
            serializedHitResult["type"] = 1 // Type 1 for Plane
            serializedHitResult["anchor"] = serializeAnchor(anchor) // Serialize the anchor
        } else {
            // Treat as a feature point
            serializedHitResult["type"] = 0 // Type 0 for Point
            serializedHitResult["anchor"] = serializeAnchor(anchor) // Still serialize the anchor
        }
        
        // Detach the temporary anchor
        anchor.detach()
        // ==========================================================
        // END OF CUSTOMIZATION
        // ==========================================================

        return serializedHitResult
    }

    fun serializeAnchor(anchor: Anchor): Map<String, Any?> {
        val anchorMap = mutableMapOf<String, Any?>()
        
        // Use a unique hash code as the name if cloud ID is not available
        anchorMap["name"] = anchor.cloudAnchorId ?: "anchor_${anchor.hashCode()}"
        anchorMap["transform"] = serializePose(anchor.pose)
        anchorMap["cloudanchorid"] = anchor.cloudAnchorId

        // ==========================================================
        // CUSTOMIZATION: This is the fix for "Unresolved reference 'trackable'"
        // ==========================================================
        val trackable = anchor.trackable
        if (trackable is Plane) {
        // ==========================================================
        // END OF CUSTOMIZATION
        // ==========================================================
            anchorMap["type"] = 1 // Type 1 for Plane
            anchorMap["centerPose"] = serializePose(trackable.centerPose) // Use plane's center pose
            anchorMap["extent"] = mapOf("width" to trackable.extentX, "height" to trackable.extentZ) // Use width/height
            anchorMap["alignment"] = trackable.type.ordinal
        } else {
             anchorMap["type"] = 0 // Type 0 for other anchor types
        }

        return anchorMap
    }

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
}