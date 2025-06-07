package com.example.arcore

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt
import kotlin.math.abs

data class AnchorInfo(
    val anchor: Anchor,
    val id: Int,
    val creationTime: Long,
    var worldPosition: FloatArray = floatArrayOf(0f, 0f, 0f),
    var distanceFromOrigin: Float = 0f
)

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private var surfaceView: GLSurfaceView? = null
    private var statusText: TextView? = null
    private var btnCreateAnchor: Button? = null
    private var btnResolveAnchors: Button? = null

    private var session: Session? = null
    private var installRequested = false
    private var userRequestedInstall = true

    // Camera rendering
    private var backgroundTextureId = 0
    private var cameraProgram = 0
    private var cameraVertexBuffer: FloatBuffer? = null
    private var cameraTexCoordBuffer: FloatBuffer? = null

    // High-precision world coordinate system
    private var worldOriginAnchor: Anchor? = null
    private var isWorldOriginSet = false
    private val anchorInfoList = mutableListOf<AnchorInfo>()
    private var anchorIdCounter = 1

    // Enhanced position tracking
    private var currentWorldPosition = floatArrayOf(0f, 0f, 0f)
    private var smoothedWorldPosition = floatArrayOf(0f, 0f, 0f)
    private var totalDistanceMoved = 0f
    private var lastCameraPosition = floatArrayOf(0f, 0f, 0f)
    private var positionHistory = mutableListOf<FloatArray>()
    private val maxHistorySize = 10

    // Room calibration
    private var roomDimensions = floatArrayOf(0f, 0f, 0f) // width, height, depth
    private var minRoomBounds = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    private var maxRoomBounds = floatArrayOf(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE)

    // Anchor management
    private var lastTapTime = 0L
    private var shouldCreateCenterAnchor = false
    private var shouldProcessTap = false
    private var shouldSetWorldOrigin = false
    private var shouldCalibrateRoom = false
    private var queuedTapX = 0f
    private var queuedTapY = 0f

    // Matrices for 3D rendering
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "RoomTracker"

        // Movement thresholds for accuracy
        private const val MIN_MOVEMENT_THRESHOLD = 0.005f // 5mm
        private const val POSITION_SMOOTHING_FACTOR = 0.8f

        // Vertex shader for camera background
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        // Fragment shader for camera background
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """

        // Simple 3D object shader for anchors
        private const val OBJECT_VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            attribute vec3 a_Color;
            varying vec3 v_Color;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
                v_Color = a_Color;
            }
        """

        private const val OBJECT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 v_Color;
            void main() {
                gl_FragColor = vec4(v_Color, 1.0);
            }
        """

        // Full screen quad vertices
        private val QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f,  1.0f,
            1.0f,  1.0f
        )

        // Fixed texture coordinates (90° counter-clockwise)
        private val QUAD_TEXCOORDS = floatArrayOf(
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            0.0f, 1.0f
        )

        // Precise cube vertices for anchor visualization
        private val CUBE_VERTICES = floatArrayOf(
            // Front face (Red)
            -0.02f, -0.02f,  0.02f,  1.0f, 0.3f, 0.3f,
            0.02f, -0.02f,  0.02f,  1.0f, 0.3f, 0.3f,
            0.02f,  0.02f,  0.02f,  1.0f, 0.3f, 0.3f,
            -0.02f,  0.02f,  0.02f,  1.0f, 0.3f, 0.3f,

            // Back face (Green)
            -0.02f, -0.02f, -0.02f,  0.3f, 1.0f, 0.3f,
            0.02f, -0.02f, -0.02f,  0.3f, 1.0f, 0.3f,
            0.02f,  0.02f, -0.02f,  0.3f, 1.0f, 0.3f,
            -0.02f,  0.02f, -0.02f,  0.3f, 1.0f, 0.3f
        )

        // Origin marker vertices (larger, golden)
        private val ORIGIN_VERTICES = floatArrayOf(
            // Front face (Gold)
            -0.05f, -0.05f,  0.05f,  1.0f, 0.8f, 0.0f,
            0.05f, -0.05f,  0.05f,  1.0f, 0.8f, 0.0f,
            0.05f,  0.05f,  0.05f,  1.0f, 0.8f, 0.0f,
            -0.05f,  0.05f,  0.05f,  1.0f, 0.8f, 0.0f,

            // Back face (Dark Gold)
            -0.05f, -0.05f, -0.05f,  0.8f, 0.6f, 0.0f,
            0.05f, -0.05f, -0.05f,  0.8f, 0.6f, 0.0f,
            0.05f,  0.05f, -0.05f,  0.8f, 0.6f, 0.0f,
            -0.05f,  0.05f, -0.05f,  0.8f, 0.6f, 0.0f
        )

        private val CUBE_INDICES = shortArrayOf(
            // Front face
            0, 1, 2, 0, 2, 3,
            // Back face
            4, 6, 5, 4, 7, 6,
            // Left face
            4, 0, 3, 4, 3, 7,
            // Right face
            1, 5, 6, 1, 6, 2,
            // Top face
            3, 2, 6, 3, 6, 7,
            // Bottom face
            4, 1, 0, 4, 5, 1
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surface_view)
        statusText = findViewById(R.id.position_text)
        btnCreateAnchor = findViewById(R.id.btn_create_anchor)
        btnResolveAnchors = findViewById(R.id.btn_resolve_anchors)

        if (surfaceView == null || statusText == null) {
            Toast.makeText(this, "Layout error", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Set up touch listener for tap-to-place anchors
        surfaceView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleTap(event.x, event.y)
            }
            true
        }

        // Set up button listeners
        btnCreateAnchor?.setOnClickListener {
            if (!isWorldOriginSet) {
                setWorldOrigin()
            } else {
                createAnchorAtCenter()
            }
        }

        btnResolveAnchors?.setOnClickListener {
            if (!isWorldOriginSet) {
                Toast.makeText(this, "Set world origin first!", Toast.LENGTH_SHORT).show()
            } else {
                resolveAnchors()
            }
        }

        statusText?.text = "🔧 Checking permissions..."

        if (checkCameraPermission()) {
            maybeEnableArButton()
        } else {
            requestCameraPermission()
        }
    }

    private fun setWorldOrigin() {
        shouldSetWorldOrigin = true
        Toast.makeText(this, "📍 Setting room origin...", Toast.LENGTH_SHORT).show()
    }

    private fun handleTap(x: Float, y: Float) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime < 300) return // Prevent spam tapping
        lastTapTime = currentTime

        if (!isWorldOriginSet) {
            Toast.makeText(this, "🏠 First set room origin using the button!", Toast.LENGTH_SHORT).show()
            return
        }

        // Store tap coordinates for processing in OpenGL thread
        queuedTapX = x
        queuedTapY = y
        shouldProcessTap = true
    }

    private fun createAnchorAtCenter() {
        if (!isWorldOriginSet) {
            Toast.makeText(this, "🏠 First set room origin!", Toast.LENGTH_SHORT).show()
            return
        }

        shouldCreateCenterAnchor = true
        Toast.makeText(this, "🎯 Creating anchor...", Toast.LENGTH_SHORT).show()
    }

    private fun resolveAnchors() {
        updateAnchorWorldPositions()

        if (anchorInfoList.isEmpty()) {
            Toast.makeText(this, "📍 No anchors to resolve", Toast.LENGTH_SHORT).show()
            return
        }

        val trackingAnchors = anchorInfoList.filter {
            it.anchor.trackingState == TrackingState.TRACKING
        }

        if (trackingAnchors.isEmpty()) {
            Toast.makeText(this, "⚠️ No tracking anchors found", Toast.LENGTH_SHORT).show()
            return
        }

        val detailedInfo = StringBuilder()
        detailedInfo.append("📍 PRECISE ROOM COORDINATES:\n\n")
        detailedInfo.append("🏠 Origin: (0.000m, 0.000m, 0.000m)\n")
        detailedInfo.append("📱 Your Position: (${formatMeters(smoothedWorldPosition[0])}, ${formatMeters(smoothedWorldPosition[1])}, ${formatMeters(smoothedWorldPosition[2])})\n")
        detailedInfo.append("📏 Distance from origin: ${formatMeters(calculateDistance(smoothedWorldPosition))}\n\n")

        detailedInfo.append("🎯 ANCHORS:\n")
        trackingAnchors.forEachIndexed { index, anchorInfo ->
            detailedInfo.append("Anchor ${anchorInfo.id}: (${formatMeters(anchorInfo.worldPosition[0])}, ${formatMeters(anchorInfo.worldPosition[1])}, ${formatMeters(anchorInfo.worldPosition[2])}) - ${formatMeters(anchorInfo.distanceFromOrigin)}\n")
        }

        detailedInfo.append("\n📊 ROOM ANALYSIS:\n")
        detailedInfo.append("Width: ${formatMeters(roomDimensions[0])}\n")
        detailedInfo.append("Height: ${formatMeters(roomDimensions[1])}\n")
        detailedInfo.append("Depth: ${formatMeters(roomDimensions[2])}\n")
        detailedInfo.append("Total movement: ${formatMeters(totalDistanceMoved)}")

        Log.d(TAG, detailedInfo.toString())

        runOnUiThread {
            statusText?.text = detailedInfo.toString()
        }
    }

    private fun formatMeters(value: Float): String {
        return when {
            abs(value) < 0.01f -> "${String.format("%.1f", value * 1000)}mm"
            abs(value) < 1.0f -> "${String.format("%.2f", value * 100)}cm"
            else -> "${String.format("%.3f", value)}m"
        }
    }

    private fun calculateDistance(position: FloatArray): Float {
        return sqrt(position[0] * position[0] + position[1] * position[1] + position[2] * position[2])
    }

    private fun updateAnchorWorldPositions() {
        val worldOrigin = worldOriginAnchor ?: return

        if (worldOrigin.trackingState != TrackingState.TRACKING) return

        val worldOriginPose = worldOrigin.pose

        for (anchorInfo in anchorInfoList) {
            if (anchorInfo.anchor.trackingState == TrackingState.TRACKING) {
                val anchorPose = anchorInfo.anchor.pose

                // Calculate high-precision position relative to world origin
                anchorInfo.worldPosition = floatArrayOf(
                    anchorPose.translation[0] - worldOriginPose.translation[0],
                    anchorPose.translation[1] - worldOriginPose.translation[1],
                    anchorPose.translation[2] - worldOriginPose.translation[2]
                )

                anchorInfo.distanceFromOrigin = calculateDistance(anchorInfo.worldPosition)

                // Update room bounds
                updateRoomBounds(anchorInfo.worldPosition)
            }
        }
    }

    private fun updateRoomBounds(position: FloatArray) {
        // Track minimum and maximum bounds
        for (i in 0..2) {
            if (position[i] < minRoomBounds[i]) minRoomBounds[i] = position[i]
            if (position[i] > maxRoomBounds[i]) maxRoomBounds[i] = position[i]
        }

        // Calculate room dimensions
        for (i in 0..2) {
            roomDimensions[i] = maxRoomBounds[i] - minRoomBounds[i]
        }
    }

    private fun updateCameraWorldPosition(cameraPose: Pose) {
        val worldOrigin = worldOriginAnchor ?: return

        if (worldOrigin.trackingState != TrackingState.TRACKING) return

        val worldOriginPose = worldOrigin.pose

        // Calculate raw camera position relative to world origin
        val rawPosition = floatArrayOf(
            cameraPose.translation[0] - worldOriginPose.translation[0],
            cameraPose.translation[1] - worldOriginPose.translation[1],
            cameraPose.translation[2] - worldOriginPose.translation[2]
        )

        // Apply position smoothing for better accuracy
        if (positionHistory.isNotEmpty()) {
            smoothedWorldPosition[0] = smoothedWorldPosition[0] * POSITION_SMOOTHING_FACTOR + rawPosition[0] * (1 - POSITION_SMOOTHING_FACTOR)
            smoothedWorldPosition[1] = smoothedWorldPosition[1] * POSITION_SMOOTHING_FACTOR + rawPosition[1] * (1 - POSITION_SMOOTHING_FACTOR)
            smoothedWorldPosition[2] = smoothedWorldPosition[2] * POSITION_SMOOTHING_FACTOR + rawPosition[2] * (1 - POSITION_SMOOTHING_FACTOR)
        } else {
            smoothedWorldPosition = rawPosition.copyOf()
        }

        currentWorldPosition = rawPosition.copyOf()

        // Add to position history
        positionHistory.add(rawPosition.copyOf())
        if (positionHistory.size > maxHistorySize) {
            positionHistory.removeAt(0)
        }

        // Calculate movement with high precision
        val dx = currentWorldPosition[0] - lastCameraPosition[0]
        val dy = currentWorldPosition[1] - lastCameraPosition[1]
        val dz = currentWorldPosition[2] - lastCameraPosition[2]
        val frameDistance = sqrt(dx*dx + dy*dy + dz*dz)

        if (frameDistance > MIN_MOVEMENT_THRESHOLD) {
            totalDistanceMoved += frameDistance
            lastCameraPosition = currentWorldPosition.copyOf()
        }

        // Update room bounds with current position
        updateRoomBounds(smoothedWorldPosition)
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                maybeEnableArButton()
            } else {
                Toast.makeText(this, "Camera permission required for room tracking", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun maybeEnableArButton() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)

        if (availability.isTransient) {
            statusText?.postDelayed({ maybeEnableArButton() }, 200)
        }

        if (availability.isSupported) {
            statusText?.text = "🚀 ARCore ready, initializing precision tracking..."
            setupAR()
        } else {
            statusText?.text = "❌ ARCore not supported on this device"
        }
    }

    private fun setupAR() {
        val surface = surfaceView ?: return

        surface.preserveEGLContextOnPause = true
        surface.setEGLContextClientVersion(2)
        surface.setRenderer(this)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        statusText?.text = "📍 Click 'Create Anchor' to set room origin (0,0,0)"
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            var exception: Exception? = null
            var message: String? = null

            try {
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        userRequestedInstall = false
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        // Success! Create the AR session.
                    }
                }

                if (!checkCameraPermission()) {
                    requestCameraPermission()
                    return
                }

                session = Session(this)

            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "This device does not support AR"
                exception = e
            } catch (e: Exception) {
                message = "Failed to create AR session"
                exception = e
            }

            if (message != null) {
                statusText?.text = message
                Log.e(TAG, "Exception creating session", exception)
                return
            }
        }

        try {
            configureSession()
            session?.resume()
            surfaceView?.onResume()
        } catch (e: CameraNotAvailableException) {
            statusText?.text = "Camera not available. Try restarting the app."
            session = null
            return
        }
    }

    private fun configureSession() {
        val config = Config(session)
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        // Enhanced precision settings
        config.focusMode = Config.FocusMode.AUTO
        session?.configure(config)
    }

    override fun onPause() {
        super.onPause()
        surfaceView?.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        // Clean up anchors
        anchorInfoList.forEach { it.anchor.detach() }
        anchorInfoList.clear()
        worldOriginAnchor?.detach()

        session?.close()
        session = null
        super.onDestroy()
    }

    // OpenGL rendering variables
    private var objectProgram = 0
    private var cubeVertexBuffer: FloatBuffer? = null
    private var originVertexBuffer: FloatBuffer? = null
    private var cubeIndexBuffer: java.nio.ShortBuffer? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // Create camera texture and program
        setupCameraRendering()

        // Create 3D object rendering
        setup3DRendering()
    }

    private fun setupCameraRendering() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        backgroundTextureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        cameraProgram = createShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        cameraVertexBuffer = createFloatBuffer(QUAD_VERTICES)
        cameraTexCoordBuffer = createFloatBuffer(QUAD_TEXCOORDS)
    }

    private fun setup3DRendering() {
        objectProgram = createShaderProgram(OBJECT_VERTEX_SHADER, OBJECT_FRAGMENT_SHADER)
        cubeVertexBuffer = createFloatBuffer(CUBE_VERTICES)
        originVertexBuffer = createFloatBuffer(ORIGIN_VERTICES)
        cubeIndexBuffer = createShortBuffer(CUBE_INDICES)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        session?.let { session ->
            try {
                session.setCameraTextureName(backgroundTextureId)

                val frame = session.update()
                val camera = frame.camera

                // Handle world origin setup (safe in OpenGL thread)
                if (shouldSetWorldOrigin && camera.trackingState == TrackingState.TRACKING) {
                    shouldSetWorldOrigin = false

                    // Create world origin anchor at current camera position
                    val originPose = camera.pose
                    worldOriginAnchor = session.createAnchor(originPose)
                    isWorldOriginSet = true

                    // Initialize tracking variables
                    currentWorldPosition = floatArrayOf(0f, 0f, 0f)
                    smoothedWorldPosition = floatArrayOf(0f, 0f, 0f)
                    lastCameraPosition = floatArrayOf(0f, 0f, 0f)
                    totalDistanceMoved = 0f
                    positionHistory.clear()

                    // Reset room bounds
                    minRoomBounds = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
                    maxRoomBounds = floatArrayOf(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE)

                    Log.d(TAG, "🏠 Room origin established at: ${originPose.translation[0]}, ${originPose.translation[1]}, ${originPose.translation[2]}")

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "🏠 Room origin set! Start moving around your room.", Toast.LENGTH_LONG).show()
                        btnCreateAnchor?.text = "Add Anchor"
                    }
                }

                // Handle center anchor creation
                if (shouldCreateCenterAnchor && camera.trackingState == TrackingState.TRACKING && isWorldOriginSet) {
                    shouldCreateCenterAnchor = false

                    // Create anchor at current camera position
                    val cameraPose = camera.pose
                    val anchor = session.createAnchor(cameraPose)
                    val anchorInfo = AnchorInfo(anchor, anchorIdCounter++, System.currentTimeMillis())
                    anchorInfoList.add(anchorInfo)

                    Log.d(TAG, "🎯 Anchor ${anchorInfo.id} created at current position")

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "🎯 Anchor ${anchorInfo.id} created! Total: ${anchorInfoList.size}", Toast.LENGTH_SHORT).show()
                    }
                }

                // Handle tap-to-place anchors
                if (shouldProcessTap && camera.trackingState == TrackingState.TRACKING && isWorldOriginSet) {
                    shouldProcessTap = false

                    val hits = frame.hitTest(queuedTapX, queuedTapY)

                    for (hit in hits) {
                        val trackable = hit.trackable

                        if ((trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) ||
                            (trackable is Point && trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)) {

                            val anchor = hit.createAnchor()
                            val anchorInfo = AnchorInfo(anchor, anchorIdCounter++, System.currentTimeMillis())
                            anchorInfoList.add(anchorInfo)

                            Log.d(TAG, "🎯 Surface anchor ${anchorInfo.id} created")

                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "🎯 Surface anchor ${anchorInfo.id} created! Total: ${anchorInfoList.size}", Toast.LENGTH_SHORT).show()
                            }
                            break
                        }
                    }
                }

                // Draw camera background
                drawCameraBackground()

                if (camera.trackingState == TrackingState.TRACKING) {
                    // Get camera matrices
                    camera.getViewMatrix(viewMatrix, 0)
                    camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)

                    // Update high-precision world coordinates
                    if (isWorldOriginSet) {
                        updateCameraWorldPosition(camera.pose)
                        updateAnchorWorldPositions()
                    }

                    // Draw all anchors and origin
                    drawAnchors()

                    // Update real-time status
                    updateStatus(camera)
                }

            } catch (t: Throwable) {
                Log.e(TAG, "Exception in onDrawFrame", t)
            }
        }
    }

    private fun drawAnchors() {
        if (objectProgram == 0) return

        GLES20.glUseProgram(objectProgram)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val mvpMatrixHandle = GLES20.glGetUniformLocation(objectProgram, "u_ModelViewProjection")
        val positionHandle = GLES20.glGetAttribLocation(objectProgram, "a_Position")
        val colorHandle = GLES20.glGetAttribLocation(objectProgram, "a_Color")

        // Remove invalid anchors
        anchorInfoList.removeAll { it.anchor.trackingState == TrackingState.STOPPED }

        // Draw world origin marker (large golden cube)
        worldOriginAnchor?.let { originAnchor ->
            if (originAnchor.trackingState == TrackingState.TRACKING) {
                val modelMatrix = FloatArray(16)
                originAnchor.pose.toMatrix(modelMatrix, 0)

                val mvMatrix = FloatArray(16)
                val mvpMatrix = FloatArray(16)
                Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
                Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

                GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
                GLES20.glEnableVertexAttribArray(positionHandle)
                GLES20.glEnableVertexAttribArray(colorHandle)

                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, originVertexBuffer)
                originVertexBuffer?.position(3)
                GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, 24, originVertexBuffer)
                originVertexBuffer?.position(0)

                GLES20.glDrawElements(GLES20.GL_TRIANGLES, CUBE_INDICES.size, GLES20.GL_UNSIGNED_SHORT, cubeIndexBuffer)

                GLES20.glDisableVertexAttribArray(positionHandle)
                GLES20.glDisableVertexAttribArray(colorHandle)
            }
        }

        // Draw anchor markers (small colored cubes)
        for (anchorInfo in anchorInfoList) {
            val anchor = anchorInfo.anchor
            if (anchor.trackingState != TrackingState.TRACKING) continue

            val modelMatrix = FloatArray(16)
            anchor.pose.toMatrix(modelMatrix, 0)

            val mvMatrix = FloatArray(16)
            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glEnableVertexAttribArray(colorHandle)

            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 24, cubeVertexBuffer)
            cubeVertexBuffer?.position(3)
            GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, 24, cubeVertexBuffer)
            cubeVertexBuffer?.position(0)

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, CUBE_INDICES.size, GLES20.GL_UNSIGNED_SHORT, cubeIndexBuffer)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(colorHandle)
        }
    }

    private fun updateStatus(camera: Camera) {
        if (!isWorldOriginSet) {
            runOnUiThread {
                statusText?.text = """
                    🏠 ROOM TRACKING SETUP
                    
                    📍 Step 1: Set room origin
                    Click 'Create Anchor' to establish (0,0,0) 
                    at your current position in the room.
                    
                    This will be your reference point for 
                    precise room-scale tracking.
                """.trimIndent()
            }
            return
        }

        val positionAccuracy = if (positionHistory.size >= 5) "High" else "Calibrating"
        val trackingQuality = when {
            camera.trackingState == TrackingState.TRACKING && positionHistory.size >= 8 -> "Excellent"
            camera.trackingState == TrackingState.TRACKING && positionHistory.size >= 5 -> "Good"
            camera.trackingState == TrackingState.TRACKING -> "Fair"
            else -> "Poor"
        }

        runOnUiThread {
            statusText?.text = """
                🏠 ROOM POSITION (Real-time)
                
                📍 Current: (${formatMeters(smoothedWorldPosition[0])}, ${formatMeters(smoothedWorldPosition[1])}, ${formatMeters(smoothedWorldPosition[2])})
                📏 From origin: ${formatMeters(calculateDistance(smoothedWorldPosition))}
                🚶 Total moved: ${formatMeters(totalDistanceMoved)}
                
                📊 TRACKING QUALITY
                🎯 Accuracy: $positionAccuracy
                📡 Quality: $trackingQuality
                🔢 Anchors: ${anchorInfoList.size}
                
                📐 ROOM DIMENSIONS
                Width: ${formatMeters(roomDimensions[0])}
                Height: ${formatMeters(roomDimensions[1])}
                Depth: ${formatMeters(roomDimensions[2])}
                
                💡 Tap screen or use buttons to place anchors
                📋 Use 'Resolve Anchors' for detailed coordinates
            """.trimIndent()
        }
    }

    private fun drawCameraBackground() {
        if (cameraProgram == 0 || backgroundTextureId == 0) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(cameraProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTextureId)

        val positionHandle = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
        val texCoordHandle = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
        val textureHandle = GLES20.glGetUniformLocation(cameraProgram, "u_Texture")

        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, cameraVertexBuffer)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, cameraTexCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
    }

    private fun createShaderProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == GLES20.GL_FALSE) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            return 0
        }

        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == GLES20.GL_FALSE) {
            Log.e(TAG, "Shader compilation failed: ${GLES20.glGetShaderInfoLog(shader)}")
            return 0
        }

        return shader
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }
    }

    private fun createShortBuffer(data: ShortArray): java.nio.ShortBuffer {
        return ByteBuffer.allocateDirect(data.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(data)
                position(0)
            }
    }
}