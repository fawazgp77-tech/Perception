package com.example.mobilityassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.provider.Settings
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var toggle: Button
    private lateinit var feedback: SpeechHapticManager
    private val worker = Executors.newSingleThreadExecutor()
    private val engine = DecisionEngine()
    private val stabilizer = GuidanceStabilizer() // Analysis worker only.
    private var guidanceGeneration = -1 // Analysis worker only.
    private val metrics = PipelineMetrics()
    private val generation = AtomicInteger()
    private var detector: ObjectDetectorHelper? = null // Worker thread only.
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var active = false
    @Volatile private var destroyed = false
    private var paused = false
    private var initialized = false
    private var failed = false
    private var permissionBlocked = false
    private var lastWarningMs = 0L
    private var lastMetricsLogMs = 0L

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionBlocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (granted) {
            status.setText(R.string.scanning)
            updateButton()
            preview.post { bindCamera() }
        } else showPermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        diagnostics = findViewById(R.id.diagnostics)
        toggle = findViewById(R.id.toggle)
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER
        feedback = SpeechHapticManager(this) { findViewById<TextView>(R.id.speechStatus).text = it }
        paused = savedInstanceState?.getBoolean("paused") ?: false
        updateButton()
        toggle.setOnClickListener {
            when {
                failed -> recreate()
                !hasCameraPermission() -> {
                    if (permissionBlocked) openAppSettings()
                    else permission.launch(Manifest.permission.CAMERA)
                }
                else -> {
                    paused = !paused
                    generation.incrementAndGet()
                    feedback.stop()
                    overlay.show(emptyList())
                    metrics.clear()
                    diagnostics.text = ""
                    updateButton()
                    if (paused) {
                        analysis?.clearAnalyzer()
                        provider?.unbindAll()
                        status.setText(R.string.assistance_paused)
                    } else preview.post { bindCamera() }
                }
            }
        }
        worker.execute {
            try {
                detector = ObjectDetectorHelper(applicationContext)
                runOnUiThread {
                    if (!destroyed) {
                        initialized = true
                        if (paused) status.setText(R.string.assistance_paused)
                        if (hasCameraPermission()) preview.post { bindCamera() }
                        else permission.launch(Manifest.permission.CAMERA)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { fail(getString(R.string.detector_unavailable), error) }
            }
        }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionState() {
        status.setText(if (permissionBlocked) R.string.permission_blocked else R.string.permission_denied)
        updateButton()
        status.announceForAccessibility(status.text)
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")))
    }

    private fun bindCamera() {
        if (destroyed || !active || paused || !initialized || failed || !hasCameraPermission()) return
        val token = generation.incrementAndGet()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (destroyed || !active || paused || token != generation.get()) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val viewPort = preview.viewPort ?: run {
                    preview.post { bindCamera() }
                    return@addListener
                }
                val rotation = preview.display.rotation
                val cameraPreview = Preview.Builder().setTargetRotation(rotation).build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis?.clearAnalyzer()
                cameraProvider.unbindAll()
                analysis = imageAnalysis
                imageAnalysis.setAnalyzer(worker) { frame ->
                    val startedNs = SystemClock.elapsedRealtimeNanos()
                    val cameraTimestampNs = frame.imageInfo.timestamp
                    try {
                        if (active && token == generation.get() && !destroyed) {
                            if (guidanceGeneration != token) {
                                stabilizer.reset()
                                guidanceGeneration = token
                            }
                            val result = checkNotNull(detector).detect(frame)
                            val warning = stabilizer.observe(engine.decide(result.objects),
                                SystemClock.elapsedRealtime())
                            runOnUiThread {
                                if (active && !destroyed && token == generation.get()) {
                                    overlay.show(result.objects)
                                    val emitted = warning?.let(feedback::warn) ?: false
                                    val nowMs = SystemClock.elapsedRealtime()
                                    if (emitted) {
                                        status.text = warning!!.text
                                        lastWarningMs = nowMs
                                    } else if (nowMs - lastWarningMs > 3_000L &&
                                        status.text != getString(R.string.scanning)) {
                                        status.setText(R.string.scanning)
                                    }
                                    val snapshot = metrics.record(cameraTimestampNs, startedNs,
                                        SystemClock.elapsedRealtimeNanos(), result.conversionMs,
                                        result.inferenceMs)
                                    if (snapshot != null) {
                                        diagnostics.text = getString(R.string.diagnostics_format,
                                            snapshot.analyzedFps, snapshot.p95AnalysisToDisplayMs,
                                            snapshot.averageConversionMs, snapshot.averageInferenceMs)
                                        if (nowMs - lastMetricsLogMs >= 5_000L) {
                                            Log.d("MobilityAssist", "Pipeline: $snapshot")
                                            lastMetricsLogMs = nowMs
                                        }
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            if (token == generation.get()) fail(getString(R.string.detection_stopped), error)
                        }
                    } finally {
                        // Includes inactive frames, inference exceptions and early exits.
                        frame.close()
                    }
                }
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    UseCaseGroup.Builder().setViewPort(viewPort)
                        .addUseCase(cameraPreview).addUseCase(imageAnalysis).build())
                status.setText(R.string.scanning)
            } catch (error: Exception) { fail(getString(R.string.camera_unavailable), error) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun fail(message: String, error: Exception) {
        Log.e("MobilityAssist", message, error)
        if (destroyed) return
        failed = true
        generation.incrementAndGet()
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        feedback.stop()
        overlay.show(emptyList())
        status.text = message
        status.announceForAccessibility(message)
        updateButton()
    }

    private fun updateButton() {
        toggle.setText(when {
            failed -> R.string.retry_assistance
            !hasCameraPermission() && permissionBlocked -> R.string.open_app_settings
            !hasCameraPermission() -> R.string.request_camera_permission
            paused -> R.string.resume_assistance
            else -> R.string.pause_assistance
        })
        if (paused || failed || !hasCameraPermission()) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        active = true
        if (initialized && hasCameraPermission()) {
            permissionBlocked = false
            updateButton()
        }
        preview.post { bindCamera() }
    }

    override fun onResume() {
        super.onResume()
        if (initialized && !hasCameraPermission()) showPermissionState()
    }

    override fun onStop() {
        active = false
        generation.incrementAndGet()
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        feedback.stop()
        overlay.show(emptyList())
        metrics.clear()
        diagnostics.text = ""
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("paused", paused)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        analysis?.clearAnalyzer()
        feedback.close()
        // Queued after any in-flight inference; never closes the detector mid-call.
        worker.execute { detector?.close(); detector = null }
        worker.shutdown()
        super.onDestroy()
    }
}
