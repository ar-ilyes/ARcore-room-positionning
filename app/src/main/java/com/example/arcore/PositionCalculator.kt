package com.example.arcore

import com.google.ar.core.Pose
import kotlin.math.sqrt

class PositionCalculator {

    fun calculatePosition(
        visibleAnchors: List<AnchorManager.VisibleAnchor>,
        cameraPose: Pose
    ): Vector3? {

        return when {
            visibleAnchors.size >= 3 -> {
                triangulatePosition(visibleAnchors, cameraPose)
            }
            visibleAnchors.size == 2 -> {
                twoAnchorPosition(visibleAnchors, cameraPose)
            }
            visibleAnchors.size == 1 -> {
                singleAnchorEstimate(visibleAnchors[0], cameraPose)
            }
            else -> null
        }
    }

    private fun triangulatePosition(
        anchors: List<AnchorManager.VisibleAnchor>,
        cameraPose: Pose
    ): Vector3 {
        var totalX = 0f
        var totalY = 0f
        var totalZ = 0f
        var totalWeight = 0f

        anchors.forEach { visibleAnchor ->
            val cameraPos = cameraPose.translation
            val anchorPos = visibleAnchor.anchor.pose.translation

            val relativeX = cameraPos[0] - anchorPos[0]
            val relativeY = cameraPos[1] - anchorPos[1]
            val relativeZ = cameraPos[2] - anchorPos[2]

            val absoluteX = visibleAnchor.data.worldPosition.x + relativeX
            val absoluteY = visibleAnchor.data.worldPosition.y + relativeY
            val absoluteZ = visibleAnchor.data.worldPosition.z + relativeZ

            // Weight by inverse distance (closer = more accurate)
            val weight = 1f / (visibleAnchor.distance + 0.1f)

            totalX += absoluteX * weight
            totalY += absoluteY * weight
            totalZ += absoluteZ * weight
            totalWeight += weight
        }

        return Vector3(totalX / totalWeight, totalY / totalWeight, totalZ / totalWeight)
    }

    private fun twoAnchorPosition(
        anchors: List<AnchorManager.VisibleAnchor>,
        cameraPose: Pose
    ): Vector3 {
        return triangulatePosition(anchors, cameraPose)
    }

    private fun singleAnchorEstimate(
        anchor: AnchorManager.VisibleAnchor,
        cameraPose: Pose
    ): Vector3 {
        val cameraPos = cameraPose.translation
        val anchorPos = anchor.anchor.pose.translation

        val relativeX = cameraPos[0] - anchorPos[0]
        val relativeY = cameraPos[1] - anchorPos[1]
        val relativeZ = cameraPos[2] - anchorPos[2]

        return Vector3(
            anchor.data.worldPosition.x + relativeX,
            anchor.data.worldPosition.y + relativeY,
            anchor.data.worldPosition.z + relativeZ
        )
    }
}