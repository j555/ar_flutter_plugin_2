package net.kodified.ar_flutter_plugin_updated.Serialization

import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
// ADDED: Explicit import for Trackable and TrackingState
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toMatrix
import io.github.sceneview.node.Node
import java.util.HashMap

object Serialization {
    fun serializeHitResult(hitResult: HitResult): HashMap<String, Any> {
        val serializedHitResult = HashMap<String, Any>()
        serializedHitResult["distance"] = hitResult.distance.toDouble()
        serializedHitResult["transform"] = serializePose(hitResult.hitPose)

        val anchor = hitResult.createAnchor()
        
        // FIXED: 'trackable' is now resolved due to new import
        val trackable = anchor.trackable
        
        // FIXED: 'TrackingState' is now resolved due to new import
        if (trackable is Plane && trackable.trackingState == TrackingState.TRACKING) {
            serializedHitResult["type"] = 1 // Type 1 for Plane
            serializedHitResult["anchor"] = serializeAnchor(anchor) // Serialize the anchor
        } else {
            serializedHitResult["type"] = 0 // Type 0 for Point
            serializedHitResult["anchor"] = serializeAnchor(anchor) // Still serialize the anchor
        }
        
        anchor.detach()
        
        return serializedHitResult
    }

    fun serializeAnchor(anchor: Anchor): Map<String, Any?> {
        val anchorMap = mutableMapOf<String, Any?>()
        
        anchorMap["name"] = anchor.cloudAnchorId ?: "anchor_${anchor.hashCode()}"
        anchorMap["transform"] = serializePose(anchor.pose)
        anchorMap["cloudanchorid"] = anchor.cloudAnchorId

        // FIXED: 'trackable' is now resolved dueto new import
        val trackable = anchor.trackable
        if (trackable is Plane) {
            anchorMap["type"] = 1 // Type 1 for Plane
            anchorMap["centerPose"] = serializePose(trackable.centerPose)
            anchorMap["extent"] = mapOf("width" to trackable.extentX, "height" to trackable.extentZ)
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
        transformMap["transform"] = node.worldTransform.toMatrix().data.map { it.toDouble() }
        return transformMap
    }
}