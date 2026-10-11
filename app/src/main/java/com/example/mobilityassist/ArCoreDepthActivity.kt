package com.example.mobilityassist

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Optional depth-enhanced scanner. CameraX is stopped when this activity opens. */
class ArCoreDepthActivity : ComponentActivity(), GLSurfaceView.Renderer {
    private lateinit var surface: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var speechStatus: TextView
    private lateinit var feedback: SpeechHapticManager
    private lateinit var angle: AngleDetector
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private val generation = AtomicInteger()
    private val engine = DecisionEngine()
    private val stabilizer = GuidanceStabilizer()
    @Volatile private var depth: DepthObservation? = null
    @Volatile private var angleSuitable = false
    @Volatile private var running = false
    @Volatile private var foreground = false
    private var session: Session? = null
    private var textureId = 0
    private var shader = 0
    private var lastImageMs = 0L
    private var lastDepthMs = 0L
    private var detector: ObjectDetectorHelper? = null // worker only
    private var segmenter: PathSegmenter? = null // worker only
    private var installationRequested = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastFrameErrorMs = 0L

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startAr() else finishWithMessage(getString(R.string.depth_permission_required)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            setText(R.string.depth_starting)
            textSize = 20f
            setPadding(20, 20, 20, 20)
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        speechStatus = TextView(this).apply { setText(R.string.starting_speech) }
        val frame = FrameLayout(this)
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(this@ArCoreDepthActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = OverlayView(this)
        frame.addView(surface, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(status)
        root.addView(speechStatus)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            setText(R.string.depth_note)
            setPadding(20, 12, 20, 12)
        })
        root.addView(Button(this).apply {
            setText(R.string.back_to_menu)
            minHeight = 60
            setOnClickListener { finish() }
        })
        setContentView(root)
        feedback = SpeechHapticManager(this) { message -> speechStatus.text = message }
        val enabled = getSharedPreferences("mobility_assist", MODE_PRIVATE)
            .getBoolean("speech_enabled", true)
        feedback.setSpeechEnabled(enabled)
        angle = AngleDetector(this) { state, guidance ->
            angleSuitable = state == AngleState.GOOD
            if (guidance != null) feedback.speak(guidance, SpeechPriority.INFORMATIONAL,
                "depth_angle", 20_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) cameraPermission.launch(Manifest.permission.CAMERA)
        else startAr()
    }

    private fun startAr() {
        if (running || !foreground || isFinishing) return
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            if (availability.isTransient) {
                mainHandler.postDelayed({ if (foreground && !isFinishing && !running) startAr() }, 500L)
                return
            }
            if (!availability.isSupported) {
                finishWithMessage(getString(R.string.depth_unavailable))
                return
            }
            when (ArCoreApk.getInstance().requestInstall(this, !installationRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installationRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val ar = session ?: Session(this).also { session = it }
            if (!ar.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                finishWithMessage(getString(R.string.depth_unavailable))
                return
            }
            ar.configure(ar.config.apply { depthMode = Config.DepthMode.AUTOMATIC })
            ar.resume()
            depth = null
            lastImageMs = 0L
            lastDepthMs = 0L
            generation.incrementAndGet()
            running = true
            worker.execute { stabilizer.reset(); segmenter?.reset() }
            surface.onResume()
            angle.register(window.decorView.display?.rotation ?: Surface.ROTATION_0)
            status.setText(R.string.depth_scanning)
        } catch (error: Exception) {
            Log.w("MobilityAssist", "ARCore Depth could not start", error)
            finishWithMessage(getString(R.string.depth_start_failed))
        }
    }

    override fun onPause() {
        foreground = false
        running = false
        generation.incrementAndGet()
        depth = null
        angleSuitable = false
        angle.unregister()
        surface.onPause()
        try { session?.pause() } catch (error: Exception) {
            Log.w("MobilityAssist", "ARCore pause failed", error)
        }
        feedback.stop()
        super.onPause()
    }

    override fun onDestroy() {
        running = false
        worker.execute {
            detector?.close(); detector = null
            segmenter?.close(); segmenter = null
        }
        worker.shutdown()
        try { session?.close() } catch (error: Exception) {
            Log.w("MobilityAssist", "ARCore close failed", error)
        }
        feedback.close()
        super.onDestroy()
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        shader = makeProgram()
    }

    private var viewWidth = 1
    private var viewHeight = 1
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val ar = session ?: return
        if (!running || textureId == 0) return
        try {
            ar.setCameraTextureName(textureId)
            ar.setDisplayGeometry(window.decorView.display?.rotation ?: Surface.ROTATION_0,
                viewWidth, viewHeight)
            val frame = ar.update()
            val tex = FloatArray(8)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                QUAD, Coordinates2d.TEXTURE_NORMALIZED, tex)
            drawCamera(tex)
            if (frame.camera.trackingState != TrackingState.TRACKING) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastDepthMs >= 350L) {
                lastDepthMs = now
                depth = try { frame.acquireDepthImage16Bits().use(::readDepth) }
                catch (_: Exception) { null }
            }
            if (now - lastImageMs >= 700L && inFlight.compareAndSet(false, true)) {
                lastImageMs = now
                val bitmap = try { frame.acquireCameraImage().use { image -> cameraBitmap(frame, image) } }
                catch (_: NotYetAvailableException) { null }
                if (bitmap == null) inFlight.set(false)
                else {
                    val token = generation.get()
                    worker.execute {
                        try { analyze(bitmap, token) }
                        catch (error: Exception) { Log.w("MobilityAssist", "ARCore frame analysis failed", error) }
                        finally { bitmap.recycle(); inFlight.set(false) }
                    }
                }
            }
        } catch (error: Exception) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameErrorMs > 10_000L) {
                Log.w("MobilityAssist", "ARCore frame failed", error)
                lastFrameErrorMs = now
            }
        }
    }

    private fun analyze(bitmap: Bitmap, token: Int) {
        if (!running || token != generation.get()) return
        if (detector == null) detector = ObjectDetectorHelper(applicationContext)
        if (segmenter == null) segmenter = try { PathSegmenter(applicationContext) }
            catch (error: Exception) { Log.w("MobilityAssist", "Path model unavailable", error); null }
        val path = try { segmenter?.maybeAnalyzeBitmap(bitmap) }
            catch (error: Exception) { Log.w("MobilityAssist", "Path model failed", error); segmenter?.close(); segmenter = null; null }
        val objects = checkNotNull(detector).detectBitmap(bitmap).objects
        val objectWarning = engine.decide(objects)
        val candidate = when {
            objectWarning == Warning.STOP || objectWarning == Warning.AHEAD -> objectWarning
            angleSuitable && (path?.blocked == true || DepthPolicy.suggestsBarrier(depth, path)) -> Warning.PATH_BLOCKED
            else -> objectWarning
        }
        val warning = stabilizer.observe(candidate, SystemClock.elapsedRealtime())
        runOnUiThread {
            if (!running || isFinishing || token != generation.get()) return@runOnUiThread
            overlay.show(objects, path)
            if (warning != null && feedback.warn(warning)) status.text = warning.text
        }
    }

    private fun readDepth(image: Image): DepthObservation? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        fun median(yStart: Float, yEnd: Float): Pair<Int, Int> {
            val samples = ArrayList<Int>(200)
            var total = 0
            for (y in (height * yStart).toInt() until (height * yEnd).toInt() step 3)
                for (x in width * 2 / 5 until width * 3 / 5 step 3) {
                    total++
                    val offset = y * plane.rowStride + x * plane.pixelStride
                    if (offset + 1 >= buffer.limit()) continue
                    val millimeters = (buffer.get(offset).toInt() and 255) or
                        ((buffer.get(offset + 1).toInt() and 255) shl 8)
                    if (millimeters in 250..5_000) samples.add(millimeters)
                }
            samples.sort()
            return (samples.getOrNull(samples.size / 2) ?: 0) to
                (samples.size * 100 / total.coerceAtLeast(1))
        }
        val upper = median(0.32f, 0.43f)
        val lower = median(0.53f, 0.64f)
        if (upper.first == 0 || lower.first == 0) return null
        return DepthObservation(upper.first, lower.first,
            minOf(upper.second, lower.second) / 100f)
    }

    /** Resamples the ARCore YUV camera into the visible screen coordinate system. */
    private fun cameraBitmap(frame: Frame, image: Image): Bitmap {
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val source = FloatArray(8)
        frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, corners,
            Coordinates2d.IMAGE_PIXELS, source)
        val size = 512
        val pixels = IntArray(size * size)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        for (y in 0 until size) for (x in 0 until size) {
            val fx = x.toFloat() / (size - 1)
            val fy = y.toFloat() / (size - 1)
            val sx = ((1 - fx) * (1 - fy) * source[0] + fx * (1 - fy) * source[2] +
                (1 - fx) * fy * source[4] + fx * fy * source[6]).toInt().coerceIn(0, image.width - 1)
            val sy = ((1 - fx) * (1 - fy) * source[1] + fx * (1 - fy) * source[3] +
                (1 - fx) * fy * source[5] + fx * fy * source[7]).toInt().coerceIn(0, image.height - 1)
            fun value(plane: Image.Plane, px: Int, py: Int): Int =
                plane.buffer.get(py * plane.rowStride + px * plane.pixelStride).toInt() and 255
            val yy = value(yPlane, sx, sy)
            val uu = value(uPlane, sx / 2, sy / 2) - 128
            val vv = value(vPlane, sx / 2, sy / 2) - 128
            val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
            val g = (yy - 0.344f * uu - 0.714f * vv).toInt().coerceIn(0, 255)
            val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
            pixels[y * size + x] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun drawCamera(tex: FloatArray) {
        GLES20.glUseProgram(shader)
        val position = GLES20.glGetAttribLocation(shader, "aPosition")
        val uv = GLES20.glGetAttribLocation(shader, "aTexCoord")
        val vertices = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(QUAD).apply { position(0) }
        val coords = ByteBuffer.allocateDirect(tex.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(tex).apply { position(0) }
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, coords)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(shader, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(uv)
    }

    private fun makeProgram(): Int {
        fun compile(type: Int, code: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
        }
        val vertex = compile(GLES20.GL_VERTEX_SHADER,
            "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; " +
                "void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=aTexCoord;}")
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES uTexture; " +
                "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}")
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex)
            GLES20.glAttachShader(it, fragment)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    companion object {
        private val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    }
}
