package net.kodified.ar_flutter_plugin_updated

import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode

class PointCloudNode(
    modelInstance: ModelInstance,
    var id: Int,
    var confidence: Float
) : ModelNode(modelInstance)