package net.kodified.ar_flutter_plugin_updated.Serialization

import io.github.sceneview.math.Position
// ADDED: Missing import for Quaternion
import io.github.sceneview.math.Quaternion
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Transform
import java.util.ArrayList

object Deserializers {
    
    // This function now correctly returns a Pair of (Position, Quaternion-based Rotation)
    // which is what handleAddAnchor needs to create a Pose.
    fun deserializeMatrix4(transform: ArrayList<Double>): Pair<Position, Quaternion> {
        
        val pos = Position(
            x = transform[12].toFloat(),
            y = transform[13].toFloat(),
            z = transform[14].toFloat()
        )

        // Calculate quaternion from matrix
        val m00 = transform[0].toFloat()
        val m01 = transform[1].toFloat()
        val m02 = transform[2].toFloat()
        val m10 = transform[4].toFloat()
        val m11 = transform[5].toFloat()
        val m12 = transform[6].toFloat()
        val m20 = transform[8].toFloat()
        val m21 = transform[9].toFloat()
        val m22 = transform[10].toFloat()
        
        val trace = m00 + m11 + m22
        var qw: Float
        var qx: Float
        var qy: Float
        var qz: Float

        if (trace > 0) {
            val S = kotlin.math.sqrt(trace + 1.0f) * 2
            qw = 0.25f * S
            qx = (m21 - m12) / S
            qy = (m02 - m20) / S
            qz = (m10 - m01) / S
        } else if ((m00 > m11) && (m00 > m22)) {
            val S = kotlin.math.sqrt(1.0f + m00 - m11 - m22) * 2
            qw = (m21 - m12) / S
            qx = 0.25f * S
            qy = (m01 + m10) / S
            qz = (m02 + m20) / S
        } else if (m11 > m22) {
            val S = kotlin.math.sqrt(1.0f + m11 - m00 - m22) * 2
            qw = (m02 - m20) / S
            qx = (m01 + m10) / S
            qy = 0.25f * S
            qz = (m12 + m21) / S
        } else {
            val S = kotlin.math.sqrt(1.0f + m22 - m00 - m11) * 2
            qw = (m10 - m01) / S
            qx = (m02 + m20) / S
            qy = (m12 + m21) / S
            qz = 0.25f * S
        }

        // Create the quaternion-based Rotation object (which is just a typealias for Quaternion)
        val rot = Quaternion(qx, qy, qz, qw)
        
        return Pair(pos, rot)
    }
}