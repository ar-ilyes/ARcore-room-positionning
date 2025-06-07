package com.example.arcore

import android.content.Context
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Session
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import kotlin.math.sqrt

// Simple Vector3 class to replace Sceneform's Vector3
data class Vector3(val x: Float, val y: Float, val z: Float) {
    companion object {
        fun zero() = Vector3(0f, 0f, 0f)
    }

    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vector3(x * scalar, y * scalar, z * scalar)

    fun length(): Float = sqrt(x*x + y*y + z*z)

    override fun toString(): String = "(%.2f, %.2f, %.2f)".format(x, y, z)
}

class AnchorManager(private val context: Context) {
    private val knownAnchors = mutableListOf<AnchorData>()
    private val resolvedAnchors = mutableListOf<Anchor>()
    private var session: Session? = null

    data class AnchorData(
        val id: String,
        val worldPosition: Vector3,
        val name: String
    )

    fun setSession(session: Session) {
        this.session = session
        Log.d("AnchorManager", "AR Session set")
    }

    fun isSessionSet(): Boolean = session != null
    fun getTotalAnchors(): Int = knownAnchors.size
    fun getResolvedAnchorsCount(): Int = resolvedAnchors.size

    fun createCloudAnchor(localAnchor: Anchor, worldPos: Vector3, name: String) {
        try {
            val anchorData = AnchorData(
                id = "local_${System.currentTimeMillis()}_${name}",
                worldPosition = worldPos,
                name = name
            )

            knownAnchors.add(anchorData)
            resolvedAnchors.add(localAnchor)
            saveAnchorToStorage(anchorData)

            Log.d("AnchorManager", "Local anchor created: $name at $worldPos")

        } catch (e: Exception) {
            Log.e("AnchorManager", "Error creating anchor", e)
        }
    }

    fun getVisibleAnchors(cameraPose: Pose): List<VisibleAnchor> {
        return resolvedAnchors.mapIndexedNotNull { index, anchor ->
            try {
                if (index < knownAnchors.size && anchor.trackingState == TrackingState.TRACKING) {
                    val anchorData = knownAnchors[index]
                    val cameraPos = cameraPose.translation
                    val anchorPos = anchor.pose.translation

                    val dx = cameraPos[0] - anchorPos[0]
                    val dy = cameraPos[1] - anchorPos[1]
                    val dz = cameraPos[2] - anchorPos[2]
                    val distance = sqrt(dx*dx + dy*dy + dz*dz)

                    VisibleAnchor(anchor, anchorData, distance)
                } else null
            } catch (e: Exception) {
                Log.e("AnchorManager", "Error processing anchor", e)
                null
            }
        }
    }

    fun resolveExistingAnchors() {
        Log.d("AnchorManager", "Using ${knownAnchors.size} local anchors for positioning")
    }

    private fun saveAnchorToStorage(anchorData: AnchorData) {
        try {
            val sharedPref = context.getSharedPreferences("anchors", Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("anchor_${anchorData.name}_id", anchorData.id)
                putFloat("anchor_${anchorData.name}_x", anchorData.worldPosition.x)
                putFloat("anchor_${anchorData.name}_y", anchorData.worldPosition.y)
                putFloat("anchor_${anchorData.name}_z", anchorData.worldPosition.z)
                apply()
            }
            Log.d("AnchorManager", "Saved anchor ${anchorData.name} to storage")
        } catch (e: Exception) {
            Log.e("AnchorManager", "Error saving anchor", e)
        }
    }

    fun loadAnchorsFromStorage() {
        try {
            val sharedPref = context.getSharedPreferences("anchors", Context.MODE_PRIVATE)
            val allPrefs = sharedPref.all

            val anchorGroups = allPrefs.keys.filter { it.startsWith("anchor_") }
                .mapNotNull { key ->
                    val parts = key.split("_")
                    if (parts.size >= 3) parts[1] else null
                }
                .toSet()

            anchorGroups.forEach { name ->
                val id = sharedPref.getString("anchor_${name}_id", null)
                val x = sharedPref.getFloat("anchor_${name}_x", 0f)
                val y = sharedPref.getFloat("anchor_${name}_y", 0f)
                val z = sharedPref.getFloat("anchor_${name}_z", 0f)

                if (id != null) {
                    val anchorData = AnchorData(
                        id = id,
                        worldPosition = Vector3(x, y, z),
                        name = name
                    )
                    knownAnchors.add(anchorData)
                    Log.d("AnchorManager", "Loaded anchor $name from storage")
                }
            }
        } catch (e: Exception) {
            Log.e("AnchorManager", "Error loading anchors", e)
        }
    }

    fun clearAllAnchors() {
        knownAnchors.clear()
        resolvedAnchors.clear()
        try {
            val sharedPref = context.getSharedPreferences("anchors", Context.MODE_PRIVATE)
            sharedPref.edit().clear().apply()
            Log.d("AnchorManager", "Cleared all anchors")
        } catch (e: Exception) {
            Log.e("AnchorManager", "Error clearing anchors", e)
        }
    }

    data class VisibleAnchor(
        val anchor: Anchor,
        val data: AnchorData,
        val distance: Float
    )
}