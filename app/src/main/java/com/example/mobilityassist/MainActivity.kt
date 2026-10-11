package com.example.mobilityassist

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class MainActivity : ComponentActivity() {

    // -----------------------------------------------------------------------
    // Existing Phase 1/2 fields — unchanged
    // -----------------------------------------------------------------------
    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var toggle: Button
    private lateinit var menuPanel: View
    private lateinit var scannerPanel: View
    private lateinit var destinationInput: EditText
    private lateinit var speechToggle: CheckBox
    private lateinit var feedback: SpeechHapticManager
    private val worker = Executors.newSingleThreadExecutor()
    private val engine = DecisionEngine()
    private val stabilizer = GuidanceStabilizer()   // Analysis worker only.
    private var guidanceGeneration = -1              // Analysis worker only.
    private val metrics = PipelineMetrics()
    private val generation = AtomicInteger()
    private var detector: ObjectDetectorHelper? = null  // Worker thread only.
    private var pathSegmenter: PathSegmenter? = null // Worker thread only.
    @Volatile private var angleSuitable = false
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var active = false
    @Volatile private var destroyed = false
    private var paused = false
    private var scanningEnabled = false
    private var initialized = false
    private var failed = false
    private var permissionBlocked = false
    private var lastWarningMs = 0L
    private var pathWarningAnnounced = false // Main thread; enables one recovery announcement.
    private var lastMetricsLogMs = 0L
    private lateinit var locationTracker: PreciseLocationTracker
    private val routeService = MapplsWalkingRoutes()
    private var walkingRoute: WalkingRoute? = null
    private var walkingGuidance: WalkingGuidance? = null
    private var walkingMap: InAppWalkingMap? = null
    private var routeRequestId = 0
    private var rerouting = false
    private var lastRerouteMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var miniMapHost: FrameLayout
    private lateinit var expandedMapHost: FrameLayout
    private lateinit var navigationStatus: TextView

    // -----------------------------------------------------------------------
    // Phase 3 UI views
    // -----------------------------------------------------------------------
    private lateinit var lightingStatus: TextView
    private lateinit var angleStatus: TextView
    private lateinit var cameraStatus: TextView

    // -----------------------------------------------------------------------
    // Phase 3 state — main thread only unless noted
    // -----------------------------------------------------------------------

    /** Stored Camera reference for torch control. Main thread only. Nulled in onStop(). */
    @Volatile private var camera: Camera? = null

    /** Whether the torch is currently commanded on. Main thread only. */
    @Volatile private var torchIsOn = false
    private var torchPending = false

    /** Whether the active camera has a flash unit. Set after bindCamera() returns. */
    private var hasFlash = false

    /** Frame brightness/obstruction estimator. Worker thread only. */
    private val frameAnalyzer = FrameAnalyzer()

    /** Sensor-based orientation estimator. Registered/unregistered in lifecycle. */
    private lateinit var angleDetector: AngleDetector

    /** Aggregated Phase 3 UI state. Main thread only. */
    private var phase3State = Phase3UiState()

    /**
     * Last lighting state for which a speech announcement was made.
     * Prevents re-announcing the same state on every frame.
     */
    private var lastAnnouncedLighting = LightingState.NORMAL

    /**
     * Last occlusion state for which a speech announcement was made.
     * Prevents re-announcing the same state on every frame.
     */
    private var lastAnnouncedOcclusion = OcclusionState.UNAVAILABLE

    // -----------------------------------------------------------------------
    // Permission launcher
    // -----------------------------------------------------------------------
    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] ?: hasCameraPermission()
        permissionBlocked = !cameraGranted &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (hasFineLocationPermission()) locationTracker.start()
        else findViewById<TextView>(R.id.currentLocationStatus)
            .setText(R.string.location_permission_required)
        if (cameraGranted) {
            status.setText(R.string.scanning)
            updateButton()
            if (scanningEnabled) preview.post { bindCamera() }
        } else if (scanningEnabled) {
            showPermissionState()
        }
    }

    private val destinationSpeech = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim().orEmpty()
            if (recognized.isNotBlank()) {
                destinationInput.setText(recognized)
                destinationInput.setSelection(recognized.length)
                feedback.speak(recognized, SpeechPriority.IMPORTANT, "destination_readback", 0L)
            }
        }
    }

    // -----------------------------------------------------------------------
    // onCreate
    // -----------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview        = findViewById(R.id.preview)
        overlay        = findViewById(R.id.overlay)
        status         = findViewById(R.id.status)
        diagnostics    = findViewById(R.id.diagnostics)
        toggle         = findViewById(R.id.toggle)
        menuPanel      = findViewById(R.id.menuPanel)
        scannerPanel   = findViewById(R.id.scannerPanel)
        destinationInput = findViewById(R.id.destinationInput)
        speechToggle   = findViewById(R.id.speechToggle)
        lightingStatus = findViewById(R.id.lightingStatus)
        angleStatus    = findViewById(R.id.angleStatus)
        cameraStatus   = findViewById(R.id.cameraStatus)
        miniMapHost = findViewById(R.id.miniMapHost)
        expandedMapHost = findViewById(R.id.expandedMapHost)
        navigationStatus = findViewById(R.id.navigationStatus)
        locationTracker = PreciseLocationTracker(this) { location -> onLocationFix(location) }

        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER

        feedback = SpeechHapticManager(this) {
            findViewById<TextView>(R.id.speechStatus).text = it
            findViewById<TextView>(R.id.menuSpeechStatus).text = it
        }
        val preferences = getSharedPreferences("mobility_assist", MODE_PRIVATE)
        speechToggle.isChecked = preferences.getBoolean("speech_enabled", true)
        feedback.setSpeechEnabled(speechToggle.isChecked)
        speechToggle.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("speech_enabled", checked).apply()
            feedback.setSpeechEnabled(checked)
        }
        findViewById<Button>(R.id.startAssistance).setOnClickListener { showScanner() }
        findViewById<Button>(R.id.startDepthAssistance).setOnClickListener {
            startActivity(Intent(this, ArCoreDepthActivity::class.java))
        }
        findViewById<Button>(R.id.backToMenu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.speakDestination).setOnClickListener { requestDestinationSpeech() }
        findViewById<Button>(R.id.previewWalkingRoute).setOnClickListener {
            confirmWalkingRoute(destinationInput.text.toString())
        }
        findViewById<Button>(R.id.expandMap).setOnClickListener { setMapExpanded(true) }
        findViewById<Button>(R.id.collapseMap).setOnClickListener { setMapExpanded(false) }
        findViewById<Button>(R.id.saveHome).setOnClickListener {
            val home = destinationInput.text.toString().trim()
            if (home.isBlank()) Toast.makeText(this, R.string.destination_missing, Toast.LENGTH_SHORT).show()
            else {
                preferences.edit().putString("home_address", home).apply()
                Toast.makeText(this, R.string.home_saved, Toast.LENGTH_SHORT).show()
                feedback.speak(getString(R.string.home_saved), SpeechPriority.IMPORTANT,
                    "home_saved", 0L)
            }
        }
        findViewById<Button>(R.id.navigateHome).setOnClickListener {
            val home = preferences.getString("home_address", null)
            if (home.isNullOrBlank()) Toast.makeText(this, R.string.home_missing, Toast.LENGTH_SHORT).show()
            else confirmWalkingRoute(home)
        }

        // Phase 3: angle detector — callback fires on main thread
        angleDetector = AngleDetector(this) { state, guidance ->
            if (!active || destroyed || paused) return@AngleDetector
            angleSuitable = state == AngleState.GOOD
            phase3State = phase3State.copy(angle = state)
            updatePhase3Ui()
            if (guidance != null) {
                feedback.speak(
                    text         = guidance,
                    priority     = SpeechPriority.INFORMATIONAL,
                    cooldownKey  = "angle_guidance",
                    cooldownMs   = 20_000L
                )
            }
        }

        paused = savedInstanceState?.getBoolean("paused") ?: false
        updateButton()
        updatePhase3Ui()

        // Brief onboarding message about Phase 3 features
        status.text = getString(R.string.phase3_onboarding)
        status.postDelayed({
            if (!destroyed && status.text == getString(R.string.phase3_onboarding)) {
                status.setText(R.string.starting_detector)
            }
        }, 3_500L)

        toggle.setOnClickListener {
            when {
                failed -> recreate()
                !hasCameraPermission() -> {
                    if (permissionBlocked) openAppSettings()
                    else requestPermissions()
                }
                else -> {
                    paused = !paused
                    generation.incrementAndGet()
                    feedback.stop()
                    pathWarningAnnounced = false
                    overlay.show(emptyList())
                    metrics.clear()
                    diagnostics.text = ""
                    updateButton()
                    if (paused) {
                        analysis?.clearAnalyzer()
                        safeTorchOff()
                        provider?.unbindAll()
                        frameAnalyzer.reset()
                        angleDetector.unregister()
                        resetPhase3StateToIdle()
                        status.setText(R.string.assistance_paused)
                    } else {
                        preview.post { bindCamera() }
                    }
                }
            }
        }

        // Initialise detector on worker thread; bind camera on completion
        worker.execute {
            try {
                detector = ObjectDetectorHelper(applicationContext)
                pathSegmenter = try {
                    PathSegmenter(applicationContext, PathSegmenter.FAST_SAMPLE_INTERVAL_MS, true)
                } catch (error: Exception) {
                    Log.w("MobilityAssist", "Optional path segmenter unavailable", error)
                    null
                }
                runOnUiThread {
                    if (!destroyed) {
                        initialized = true
                        if (paused) status.setText(R.string.assistance_paused)
                        if (scanningEnabled) {
                            if (hasCameraPermission()) preview.post { bindCamera() }
                            else requestPermissions()
                        }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { fail(getString(R.string.detector_unavailable), error) }
            }
        }
        if (!hasCameraPermission() || !hasFineLocationPermission()) requestPermissions()
        else locationTracker.start()
    }

    private fun showScanner() {
        menuPanel.visibility = View.GONE
        scannerPanel.visibility = View.VISIBLE
        scanningEnabled = true
        paused = false
        pathWarningAnnounced = false
        updateButton()
        if (initialized) {
            if (hasCameraPermission()) preview.post { bindCamera() }
            else requestPermissions()
        }
    }

    private fun showMenu() {
        stopNavigation()
        scanningEnabled = false
        angleSuitable = false
        generation.incrementAndGet()
        analysis?.clearAnalyzer()
        safeTorchOff()
        provider?.unbindAll()
        camera = null
        angleDetector.unregister()
        frameAnalyzer.reset()
        feedback.stop()
        pathWarningAnnounced = false
        overlay.show(emptyList())
        resetPhase3StateToIdle()
        scannerPanel.visibility = View.GONE
        menuPanel.visibility = View.VISIBLE
    }

    private fun requestDestinationSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.destination_hint))
        }
        try {
            destinationSpeech.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.voice_input_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmWalkingRoute(rawDestination: String) {
        val destination = rawDestination.trim()
        if (destination.isBlank()) {
            Toast.makeText(this, R.string.destination_missing, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasFineLocationPermission() || !hasCameraPermission()) {
            requestPermissions()
            Toast.makeText(this, R.string.location_permission_required, Toast.LENGTH_LONG).show()
            return
        }
        val origin = locationTracker.latest
        if (!PreciseLocationTracker.usable(origin)) {
            Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val requestId = ++routeRequestId
        val routeButton = findViewById<Button>(R.id.previewWalkingRoute)
        routeButton.isEnabled = false
        findViewById<TextView>(R.id.currentLocationStatus).setText(R.string.route_loading)
        mainHandler.postDelayed({
            if (!destroyed && routeRequestId == requestId && !routeButton.isEnabled) {
                routeRequestId++
                routeButton.isEnabled = true
                Toast.makeText(this, R.string.route_failed, Toast.LENGTH_LONG).show()
            }
        }, 20_000L)
        routeService.find(destination, origin!!) { result ->
            runOnUiThread {
                if (destroyed || requestId != routeRequestId) return@runOnUiThread
                routeButton.isEnabled = true
                onLocationFix(locationTracker.latest ?: origin)
                result.onSuccess { route ->
                    val completed = AtomicBoolean(false)
                    mainHandler.postDelayed({
                        if (completed.compareAndSet(false, true) && !destroyed && requestId == routeRequestId)
                            showRouteConfirmation(origin, null, route, destination)
                    }, 4_000L)
                    routeService.describeOrigin(origin) { address ->
                        runOnUiThread {
                            if (completed.compareAndSet(false, true) && !destroyed && requestId == routeRequestId)
                                showRouteConfirmation(origin, address, route, destination)
                        }
                    }
                }.onFailure { error ->
                    Log.w("MobilityAssist", "Walking route unavailable", error)
                    Toast.makeText(this, R.string.route_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showRouteConfirmation(origin: Location, originAddress: String?,
                                      route: WalkingRoute, query: String) {
        val originText = originAddress?.takeIf { it.isNotBlank() }
            ?: "${"%.5f".format(origin.latitude)}, ${"%.5f".format(origin.longitude)}"
        val minutes = ceil((route.route.duration() ?: 0.0) / 60.0).toInt().coerceAtLeast(1)
        val km = (route.route.distance() ?: 0.0) / 1000.0
        val message = getString(R.string.route_confirm_details, originText, origin.accuracy,
            route.destinationName, minutes, km)
        feedback.speak(message.replace("\n", ". "), SpeechPriority.IMPORTANT,
            "route_confirmation", 0L)
        AlertDialog.Builder(this)
            .setTitle(R.string.route_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.start_navigation) { _, _ ->
                val latest = locationTracker.latest
                val moved = latest == null || !PreciseLocationTracker.usable(latest) ||
                    WalkingGuidance.distanceMeters(
                        GeoPosition(origin.latitude, origin.longitude),
                        GeoPosition(latest.latitude, latest.longitude)) > 35.0
                if (moved) {
                    Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_LONG).show()
                    confirmWalkingRoute(query)
                } else startNavigation(route)
            }
            .show()
    }

    private fun startNavigation(route: WalkingRoute) {
        walkingRoute = route
        walkingGuidance = guidanceFor(route)
        rerouting = false
        showScanner()
        navigationStatus.visibility = View.VISIBLE
        navigationStatus.text = getString(R.string.navigation_active, route.destinationName)
        miniMapHost.visibility = View.VISIBLE
        expandedMapHost.visibility = View.GONE
        try {
            walkingMap?.destroy()
            walkingMap = InAppWalkingMap(this, miniMapHost, expandedMapHost) { message ->
                Log.w("MobilityAssist", message)
                navigationStatus.setText(R.string.navigation_map_error)
            }.also { map ->
                map.setRoute(route)
                if (active) { map.onStart(); map.onResume() }
                locationTracker.latest?.let(map::updatePosition)
            }
        } catch (error: Exception) {
            Log.e("MobilityAssist", "Map could not start", error)
            navigationStatus.setText(R.string.navigation_map_error)
            miniMapHost.visibility = View.GONE
        }
        feedback.speak("Walking route started. Camera warnings take priority.",
            SpeechPriority.IMPORTANT, "route_started", 0L)
    }

    private fun guidanceFor(value: WalkingRoute): WalkingGuidance {
        val shape = value.route.getCoordinates().map { GeoPosition(it.latitude(), it.longitude()) }
        val turns = value.route.legs().orEmpty().flatMap { it.steps().orEmpty() }.mapNotNull { step ->
            val maneuver = step.maneuver() ?: return@mapNotNull null
            if (maneuver.type() == "depart" || maneuver.type() == "arrive") return@mapNotNull null
            val modifier = maneuver.modifier()?.lowercase().orEmpty()
            val instruction = when {
                modifier.contains("slight left") -> "Bear left"
                modifier.contains("slight right") -> "Bear right"
                modifier.contains("left") -> "Turn left"
                modifier.contains("right") -> "Turn right"
                modifier.contains("straight") -> "Continue straight"
                modifier.contains("uturn") || modifier.contains("u-turn") -> "Turn around"
                else -> null
            } ?: return@mapNotNull null
            val point = maneuver.location() ?: return@mapNotNull null
            RouteTurn(GeoPosition(point.latitude(), point.longitude()), instruction)
        }
        return WalkingGuidance(shape, turns,
            GeoPosition(value.destination.latitude(), value.destination.longitude()))
    }

    private fun onLocationFix(location: Location) {
        val locationText = if (PreciseLocationTracker.usable(location, 100f))
            getString(R.string.location_accuracy, location.latitude, location.longitude, location.accuracy)
        else getString(R.string.location_waiting)
        findViewById<TextView>(R.id.currentLocationStatus).text = locationText
        if (walkingRoute == null || !scanningEnabled) return
        walkingMap?.updatePosition(location)
        if (paused || failed || !hasCameraPermission()) return
        if (!PreciseLocationTracker.usable(location, 25f)) {
            navigationStatus.setText(R.string.navigation_location_uncertain)
            feedback.speak(getString(R.string.navigation_location_uncertain),
                SpeechPriority.IMPORTANT, "location_uncertain", 30_000L)
            return
        }
        val cue = walkingGuidance?.update(
            GeoPosition(location.latitude, location.longitude), location.accuracy) ?: return
        if (navigationStatus.text.toString() != cue.text) navigationStatus.text = cue.text
        if (cue.key == "reroute") rerouteFrom(location)
        if (feedback.speak(cue.text, SpeechPriority.IMPORTANT, "map_${cue.key}", 20_000L)) {
            walkingGuidance?.markSpoken(cue)
        }
    }

    private fun rerouteFrom(location: Location) {
        val route = walkingRoute ?: return
        val now = SystemClock.elapsedRealtime()
        if (rerouting || now - lastRerouteMs < 30_000L) return
        rerouting = true
        lastRerouteMs = now
        val requestId = routeRequestId
        routeService.routeFrom(location, route.destination, route.destinationName) { result ->
            runOnUiThread {
                if (destroyed || requestId != routeRequestId || walkingRoute == null) return@runOnUiThread
                rerouting = false
                result.onSuccess { updated ->
                    walkingRoute = updated
                    walkingGuidance = guidanceFor(updated)
                    walkingMap?.setRoute(updated)
                    navigationStatus.setText(R.string.navigation_route_updated)
                }.onFailure { error ->
                    Log.w("MobilityAssist", "Rerouting failed", error)
                    navigationStatus.setText(R.string.route_failed)
                }
            }
        }
    }

    private fun setMapExpanded(expanded: Boolean) {
        if (walkingMap == null) return
        if (expanded) {
            expandedMapHost.visibility = View.VISIBLE
            walkingMap?.setExpanded(true)
            miniMapHost.visibility = View.GONE
        } else {
            miniMapHost.visibility = View.VISIBLE
            walkingMap?.setExpanded(false)
            expandedMapHost.visibility = View.GONE
        }
        if (active && scanningEnabled && !paused) preview.post { bindCamera() }
    }

    private fun stopNavigation() {
        routeRequestId++
        walkingRoute = null
        walkingGuidance = null
        rerouting = false
        walkingMap?.destroy()
        walkingMap = null
        miniMapHost.visibility = View.GONE
        expandedMapHost.visibility = View.GONE
        navigationStatus.visibility = View.GONE
    }

    // -----------------------------------------------------------------------
    // Camera binding
    // -----------------------------------------------------------------------
    private fun bindCamera() {
        if (destroyed || !active || !scanningEnabled || paused || !initialized || failed || !hasCameraPermission()) return
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

                val cameraPreview = Preview.Builder()
                    .setTargetRotation(rotation).build()
                    .also { it.setSurfaceProvider(preview.surfaceProvider) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis?.clearAnalyzer()
                cameraProvider.unbindAll()
                analysis = imageAnalysis
                frameAnalyzer.reset()
                angleSuitable = false

                imageAnalysis.setAnalyzer(worker) { frame ->
                    val startedNs = SystemClock.elapsedRealtimeNanos()
                    val cameraTimestampNs = frame.imageInfo.timestamp
                    try {
                        if (active && token == generation.get() && !destroyed) {
                            if (guidanceGeneration != token) {
                                stabilizer.reset()
                                pathSegmenter?.reset()
                                guidanceGeneration = token
                            }

                            // ---------------------------------------------------
                            // Phase 3: lightweight frame analysis BEFORE detect().
                            // frame is still open here; detect() never closes it.
                            // ---------------------------------------------------
                            val frameResult: FrameAnalysis? = try {
                                val plane = frame.planes[0]
                                frameAnalyzer.analyze(
                                    buffer    = plane.buffer,
                                    width     = frame.width,
                                    height    = frame.height,
                                    rowStride = plane.rowStride,
                                    torchIsOn = torchIsOn
                                )
                            } catch (e: Exception) {
                                Log.w("MobilityAssist", "FrameAnalyzer failed", e)
                                null
                            }

                            val pathResult = try {
                                pathSegmenter?.maybeAnalyze(frame)
                            } catch (error: Exception) {
                                Log.w("MobilityAssist", "Path segmenter unavailable", error)
                                pathSegmenter?.close()
                                pathSegmenter = null
                                null
                            }

                            // Object boxes retain priority over tentative path guidance.
                            val result = checkNotNull(detector).detect(frame)
                            val objectWarning = engine.decide(result.objects)
                            val pathWarning = PathWarningSelector.select(
                                pathResult, result.objects,
                                angleSuitable && frameResult?.occlusionState == OcclusionState.CLEAR
                            )
                            val candidate = when {
                                objectWarning == Warning.STOP || objectWarning == Warning.AHEAD -> objectWarning
                                pathWarning != null -> pathWarning
                                else -> objectWarning
                            }
                            // PathGuidanceTracker confirms independent model results. Do not
                            // require more CameraX frames or count a cached mask as evidence.
                            val warning = if (candidate == pathWarning && pathWarning != null) {
                                stabilizer.reset()
                                pathWarning
                            } else stabilizer.observe(candidate, SystemClock.elapsedRealtime())

                            runOnUiThread {
                                if (active && !destroyed && token == generation.get()) {
                                    // ---- Existing obstacle UI ----
                                    overlay.show(result.objects, pathResult)
                                    val emitted = warning?.takeUnless {
                                        it == Warning.PATH_CLEAR && !pathWarningAnnounced
                                    }?.let(feedback::warn) ?: false
                                    val nowMs = SystemClock.elapsedRealtime()
                                    if (emitted) {
                                        status.text = warning!!.text
                                        lastWarningMs = nowMs
                                        when (warning) {
                                            Warning.PATH_BLOCKED, Warning.PATH_LEFT, Warning.PATH_RIGHT ->
                                                pathWarningAnnounced = true
                                            Warning.PATH_CLEAR -> pathWarningAnnounced = false
                                            else -> Unit
                                        }
                                    } else if (nowMs - lastWarningMs > 3_000L &&
                                        status.text != getString(R.string.scanning)
                                    ) {
                                        status.setText(R.string.scanning)
                                    }

                                    // ---- Phase 3 frame analysis results ----
                                    if (frameResult != null) {
                                        handleFrameAnalysis(frameResult)
                                    }

                                    // ---- Diagnostics ----
                                    val snapshot = metrics.record(
                                        cameraTimestampNs, startedNs,
                                        SystemClock.elapsedRealtimeNanos(),
                                        result.conversionMs, result.inferenceMs
                                    )
                                    if (snapshot != null) {
                                        diagnostics.text = getString(
                                            R.string.diagnostics_format,
                                            snapshot.analyzedFps,
                                            snapshot.p95AnalysisToDisplayMs,
                                            snapshot.averageConversionMs,
                                            snapshot.averageInferenceMs
                                        )
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
                            if (token == generation.get())
                                fail(getString(R.string.detection_stopped), error)
                        }
                    } finally {
                        // Always close — includes inactive/error/skipped frames.
                        frame.close()
                    }
                }

                // ---- Store Camera ref + check flash ----
                val boundCamera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA,
                    UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(cameraPreview)
                        .addUseCase(imageAnalysis)
                        .build()
                )
                camera = boundCamera

                hasFlash = try {
                    boundCamera.cameraInfo.hasFlashUnit()
                } catch (e: Exception) {
                    Log.w("MobilityAssist", "Flash availability check failed", e)
                    false
                }

                if (!hasFlash) {
                    phase3State = phase3State.copy(lighting = LightingState.UNAVAILABLE)
                    // Announce once that flash is not available on this device
                    feedback.speak(
                        getString(R.string.speech_flashlight_unavailable),
                        SpeechPriority.INFORMATIONAL,
                        "flash_unavailable",
                        60_000L   // once per session is enough
                    )
                } else {
                    // Reset lighting state so we evaluate fresh after rebind
                    phase3State = phase3State.copy(lighting = LightingState.NORMAL)
                    lastAnnouncedLighting = LightingState.NORMAL
                }

                // ---- Register angle sensor with current display rotation ----
                val displayRotation = preview.display?.rotation ?: Surface.ROTATION_0
                angleDetector.register(displayRotation)
                phase3State = phase3State.copy(occlusion = OcclusionState.CLEAR)
                lastAnnouncedOcclusion = OcclusionState.CLEAR

                updatePhase3Ui()
                status.setText(R.string.scanning)

            } catch (error: Exception) {
                fail(getString(R.string.camera_unavailable), error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // -----------------------------------------------------------------------
    // Phase 3: handle FrameAnalysis on the main thread
    // -----------------------------------------------------------------------
    /**
     * Drives torch control and obstruction speech/UI from frame analysis results.
     * Only called when active, not destroyed, and generation token matched.
     */
    private fun handleFrameAnalysis(analysis: FrameAnalysis) {

        // ----------------------------------------------------------------
        // Lighting / flashlight
        // ----------------------------------------------------------------
        if (hasFlash) {
            when (analysis.lightingState) {
                LightingState.LOW_LIGHT -> {
                    if (!torchIsOn) activateTorch()
                }
                LightingState.FLASHLIGHT_OFF -> {
                    if (torchIsOn) deactivateTorch()
                }
                else -> { /* no torch action */ }
            }
        }

        // Derive the effective lighting state for UI
        val effectiveLighting = if (!hasFlash) LightingState.UNAVAILABLE else analysis.lightingState

        // Announce only on state transitions
        if (effectiveLighting != lastAnnouncedLighting) {
            lastAnnouncedLighting = effectiveLighting
            phase3State = phase3State.copy(lighting = effectiveLighting)
            updatePhase3Ui()
            when (effectiveLighting) {
                LightingState.LOW_LIGHT -> feedback.speak(
                    getString(R.string.speech_low_light),
                    SpeechPriority.IMPORTANT, "lighting_low", 8_000L
                )
                LightingState.FLASHLIGHT_OFF -> feedback.speak(
                    getString(R.string.speech_flashlight_off),
                    SpeechPriority.IMPORTANT, "lighting_restored", 8_000L
                )
                else -> { /* torch on/off speech fires in activateTorch/deactivateTorch */ }
            }
        }

        // ----------------------------------------------------------------
        // Obstruction
        // ----------------------------------------------------------------
        val newOcclusion = analysis.occlusionState
        if (newOcclusion != lastAnnouncedOcclusion) {
            val previousOcclusion = lastAnnouncedOcclusion
            lastAnnouncedOcclusion = newOcclusion
            phase3State = phase3State.copy(occlusion = newOcclusion)
            updatePhase3Ui()

            when (newOcclusion) {
                OcclusionState.OBSTRUCTED -> {
                    // URGENT priority — interrupts non-obstacle speech
                    feedback.speak(
                        getString(R.string.speech_camera_covered),
                        SpeechPriority.URGENT, "occlusion_blocked", 5_000L
                    )
                }
                OcclusionState.CLEAR -> {
                    // Only announce restoration if we were previously obstructed/recovering
                    if (previousOcclusion == OcclusionState.OBSTRUCTED ||
                        previousOcclusion == OcclusionState.RECOVERING
                    ) {
                        feedback.speak(
                            getString(R.string.speech_camera_restored),
                            SpeechPriority.ROUTINE, "occlusion_restored", 10_000L
                        )
                    }
                }
                else -> { /* POSSIBLY_OBSTRUCTED and RECOVERING shown visually only */ }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Torch control helpers
    // -----------------------------------------------------------------------
    private fun activateTorch() {
        if (!hasFlash || torchIsOn || torchPending) return
        val cam = camera ?: run {
            phase3State = phase3State.copy(lighting = LightingState.ERROR)
            updatePhase3Ui()
            return
        }
        torchPending = true
        val token = generation.get()
        val future = cam.cameraControl.enableTorch(true)
        future.addListener(
            {
                runOnUiThread {
                    if (!active || destroyed || !scanningEnabled || token != generation.get()) return@runOnUiThread
                    torchPending = false
                    try { future.get() } catch (error: Exception) {
                        Log.w("MobilityAssist", "Torch activation failed", error)
                        phase3State = phase3State.copy(lighting = LightingState.ERROR)
                        updatePhase3Ui()
                        feedback.speak(getString(R.string.speech_flashlight_error),
                            SpeechPriority.IMPORTANT, "flashlight_error", 20_000L)
                        return@runOnUiThread
                    }
                    torchIsOn = true
                    frameAnalyzer.onTorchStateChanged()
                    frameAnalyzer.setLightingState(LightingState.FLASHLIGHT_ON)
                    phase3State = phase3State.copy(lighting = LightingState.FLASHLIGHT_ON)
                    lastAnnouncedLighting = LightingState.FLASHLIGHT_ON
                    updatePhase3Ui()
                    feedback.speak(
                        getString(R.string.speech_flashlight_on),
                        SpeechPriority.IMPORTANT, "flashlight_on", 8_000L
                    )
                    Log.d("MobilityAssist", "Torch ON")
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun deactivateTorch() {
        if (!torchIsOn || torchPending) return
        val cam = camera ?: return
        torchPending = true
        val token = generation.get()
        val future = cam.cameraControl.enableTorch(false)
        future.addListener(
            {
                runOnUiThread {
                    if (!active || destroyed || !scanningEnabled || token != generation.get()) return@runOnUiThread
                    torchPending = false
                    try { future.get() } catch (error: Exception) {
                        Log.w("MobilityAssist", "Torch deactivation failed", error)
                        phase3State = phase3State.copy(lighting = LightingState.ERROR)
                        updatePhase3Ui()
                        return@runOnUiThread
                    }
                    torchIsOn = false
                    frameAnalyzer.onTorchStateChanged()
                    frameAnalyzer.setLightingState(LightingState.NORMAL)
                    phase3State = phase3State.copy(lighting = LightingState.NORMAL)
                    lastAnnouncedLighting = LightingState.NORMAL
                    updatePhase3Ui()
                    feedback.speak(
                        getString(R.string.speech_flashlight_off),
                        SpeechPriority.IMPORTANT, "flashlight_off", 8_000L
                    )
                    Log.d("MobilityAssist", "Torch OFF")
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    /**
     * Turn off the torch without triggering any callbacks or speech.
     * Used in onStop(), fail(), and pause — safe to call when camera may already
     * be unbound (exception swallowed).
     */
    private fun safeTorchOff() {
        val wasOnOrPending = torchIsOn || torchPending
        torchPending = false
        if (!wasOnOrPending) return
        try {
            camera?.cameraControl?.enableTorch(false)
        } catch (e: Exception) {
            Log.w("MobilityAssist", "safeTorchOff failed silently", e)
        }
        torchIsOn = false
    }

    // -----------------------------------------------------------------------
    // Phase 3 UI update
    // -----------------------------------------------------------------------
    private fun updatePhase3Ui() {
        val s = phase3State

        // ---- Lighting / flashlight ----
        val (lightText, lightColor) = when (s.lighting) {
            LightingState.NORMAL         -> Pair(getString(R.string.status_lighting_normal),      0xFF80CBC4.toInt())
            LightingState.LOW_LIGHT      -> Pair(getString(R.string.status_low_light),            0xFFFFE082.toInt())
            LightingState.FLASHLIGHT_ON  -> Pair(getString(R.string.status_flashlight_on),        0xFFFFE082.toInt())
            LightingState.FLASHLIGHT_OFF -> Pair(getString(R.string.status_flashlight_off),       0xFF80CBC4.toInt())
            LightingState.UNAVAILABLE    -> Pair(getString(R.string.status_flashlight_unavailable),0xFFB0BEC5.toInt())
            LightingState.ERROR          -> Pair(getString(R.string.status_flashlight_error),     0xFFEF9A9A.toInt())
        }
        lightingStatus.text = lightText
        lightingStatus.setTextColor(lightColor)
        lightingStatus.contentDescription = lightText

        // ---- Angle ----
        val (angleText, angleColor) = when (s.angle) {
            AngleState.GOOD         -> Pair(getString(R.string.status_angle_good),        0xFF80CBC4.toInt())
            AngleState.TOO_HIGH     -> Pair(getString(R.string.status_angle_too_high),    0xFFFFE082.toInt())
            AngleState.TOO_LOW      -> Pair(getString(R.string.status_angle_too_low),     0xFFFFE082.toInt())
            AngleState.TILTED_LEFT  -> Pair(getString(R.string.status_angle_left),        0xFFFFE082.toInt())
            AngleState.TILTED_RIGHT -> Pair(getString(R.string.status_angle_right),       0xFFFFE082.toInt())
            AngleState.ADJUSTING    -> Pair(getString(R.string.status_angle_adjusting),   0xFFB0BEC5.toInt())
            AngleState.UNAVAILABLE  -> Pair(getString(R.string.status_angle_unavailable), 0xFFB0BEC5.toInt())
        }
        angleStatus.text = angleText
        angleStatus.setTextColor(angleColor)
        angleStatus.contentDescription = angleText

        // ---- Camera / occlusion ----
        val (camText, camColor) = when (s.occlusion) {
            OcclusionState.CLEAR               -> Pair(getString(R.string.status_camera_clear),       0xFF80CBC4.toInt())
            OcclusionState.POSSIBLY_OBSTRUCTED -> Pair(getString(R.string.status_camera_check),       0xFFFFE082.toInt())
            OcclusionState.OBSTRUCTED          -> Pair(getString(R.string.status_camera_blocked),     0xFFEF9A9A.toInt())
            OcclusionState.RECOVERING          -> Pair(getString(R.string.status_camera_recovering),  0xFFFFE082.toInt())
            OcclusionState.UNAVAILABLE         -> Pair(getString(R.string.status_camera_unavailable), 0xFFB0BEC5.toInt())
        }
        cameraStatus.text = camText
        cameraStatus.setTextColor(camColor)
        cameraStatus.contentDescription = camText
    }

    private fun resetPhase3StateToIdle() {
        phase3State = Phase3UiState(
            lighting  = LightingState.NORMAL,
            angle     = AngleState.UNAVAILABLE,
            occlusion = OcclusionState.UNAVAILABLE
        )
        lastAnnouncedLighting  = LightingState.NORMAL
        lastAnnouncedOcclusion = OcclusionState.UNAVAILABLE
        updatePhase3Ui()
    }

    // -----------------------------------------------------------------------
    // Existing helpers — unchanged
    // -----------------------------------------------------------------------
    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasFineLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val missing = mutableListOf<String>()
        if (!hasCameraPermission()) missing += Manifest.permission.CAMERA
        if (!hasFineLocationPermission()) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (missing.isNotEmpty()) permission.launch(missing.toTypedArray())
    }

    private fun showPermissionState() {
        status.setText(if (permissionBlocked) R.string.permission_blocked else R.string.permission_denied)
        updateButton()
        status.announceForAccessibility(status.text)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"))
        )
    }

    private fun fail(message: String, error: Exception) {
        Log.e("MobilityAssist", message, error)
        if (destroyed) return
        failed = true
        stopNavigation()
        generation.incrementAndGet()
        analysis?.clearAnalyzer()
        safeTorchOff()
        provider?.unbindAll()
        feedback.stop()
        pathWarningAnnounced = false
        overlay.show(emptyList())
        status.text = message
        status.announceForAccessibility(message)
        if (!scanningEnabled) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateButton()
    }

    private fun updateButton() {
        toggle.setText(when {
            failed                                   -> R.string.retry_assistance
            !hasCameraPermission() && permissionBlocked -> R.string.open_app_settings
            !hasCameraPermission()                   -> R.string.request_camera_permission
            paused                                   -> R.string.resume_assistance
            else                                     -> R.string.pause_assistance
        })
        if (paused || failed || !hasCameraPermission())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    override fun onStart() {
        super.onStart()
        active = true
        locationTracker.start()
        walkingMap?.onStart()
        if (initialized && hasCameraPermission()) {
            permissionBlocked = false
            updateButton()
        }
        if (scanningEnabled) preview.post { bindCamera() }
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission() && walkingRoute != null) stopNavigation()
        walkingMap?.onResume()
        if (initialized && !hasCameraPermission()) showPermissionState()
        // Update display rotation so angle sensor remapping stays correct
        angleDetector.updateDisplayRotation(
            preview.display?.rotation ?: Surface.ROTATION_0
        )
    }

    override fun onPause() {
        walkingMap?.onPause()
        super.onPause()
    }

    override fun onStop() {
        active = false
        locationTracker.stop()
        walkingMap?.onStop()
        angleSuitable = false
        generation.incrementAndGet()
        analysis?.clearAnalyzer()
        safeTorchOff()
        provider?.unbindAll()
        camera = null                  // Phase 3: null ref so no stale enableTorch calls
        angleDetector.unregister()     // Phase 3: unregister sensors
        frameAnalyzer.reset()          // Phase 3: clear brightness/occlusion state
        feedback.stop()                // resets both obstacle gate AND phase3Gate
        pathWarningAnnounced = false
        overlay.show(emptyList())
        metrics.clear()
        diagnostics.text = ""
        resetPhase3StateToIdle()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("paused", paused)
        walkingMap?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        stopNavigation()
        locationTracker.stop()
        analysis?.clearAnalyzer()
        feedback.close()
        // Queued after any in-flight inference; never closes detector mid-call.
        worker.execute {
            detector?.close(); detector = null
            pathSegmenter?.close(); pathSegmenter = null
        }
        worker.shutdown()
        super.onDestroy()
    }
}
