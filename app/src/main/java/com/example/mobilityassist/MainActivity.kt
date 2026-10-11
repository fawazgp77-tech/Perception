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
import android.text.Editable
import android.text.TextWatcher
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
import com.mappls.sdk.geojson.Point
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
    private lateinit var destinationSuggestions: LinearLayout
    private lateinit var destinationSearchStatus: TextView
    private lateinit var menuMapStatus: TextView
    private lateinit var menuMapHost: FrameLayout
    private var selectedDestination: PlaceCandidate? = null
    private var selectedPoint: Point? = null
    private var searchGeneration = 0
    private var searchRunnable: Runnable? = null
    private lateinit var speechToggle: CheckBox
    private lateinit var feedback: SpeechHapticManager
    private val worker = Executors.newSingleThreadExecutor()
    private val engine = DecisionEngine()
    private val scanRecovery = ScanRecoveryTracker()
    private val stairDetector = StairPatternDetector()
    private val stairTracker = StairPatternTracker()
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
    private lateinit var voice: ForegroundVoiceController
    private lateinit var microphoneToggle: Button
    private lateinit var scannerMicrophoneToggle: Button
    private lateinit var navigationMenu: View
    private lateinit var depthOption: CheckBox
    private enum class VoiceStage { MENU, DESTINATION, CANDIDATE, ROUTE_CONFIRM, SCANNER }
    private var voiceStage = VoiceStage.MENU
    private var voiceSearchPending = false
    private var voiceRouteFlow = false
    private var pendingVoiceRouteStart = false
    private var voiceCandidates = emptyList<PlaceCandidate>()
    private var voiceCandidateIndex = 0
    private var routeConfirmationDialog: AlertDialog? = null
    private var confirmRouteByVoice: (() -> Unit)? = null
    private var splashFinished = false
    private var introPrompted = false

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
        if (hasLocationPermission()) locationTracker.start()
        updateLocationStatus()
        updateMicrophoneButton()
        if (hasMicrophonePermission() && splashFinished) {
            voice.setVisible(true)
            if (!introPrompted) maybePromptIntro()
        } else if (!hasMicrophonePermission()) {
            findViewById<TextView>(R.id.voiceStatus).text =
                "Microphone permission is off. Tap the microphone button for voice commands."
        }
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
        destinationSuggestions = findViewById(R.id.destinationSuggestions)
        destinationSearchStatus = findViewById(R.id.destinationSearchStatus)
        menuMapStatus = findViewById(R.id.menuMapStatus)
        menuMapHost = findViewById(R.id.menuMapHost)
        microphoneToggle = findViewById(R.id.microphoneToggle)
        scannerMicrophoneToggle = findViewById(R.id.scannerMicrophoneToggle)
        navigationMenu = findViewById(R.id.navigationMenu)
        depthOption = findViewById(R.id.depthOption)
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
            maybePromptIntro()
        }
        voice = ForegroundVoiceController(this, ::onVoiceWords) {
            findViewById<TextView>(R.id.voiceStatus).text = it
        }
        feedback.setSpeechActivityListener { voice.setSpeaking(it) }
        val preferences = getSharedPreferences("mobility_assist", MODE_PRIVATE)
        microphoneToggle.setOnClickListener {
            if (!hasMicrophonePermission()) {
                val asked = preferences.getBoolean("microphone_asked", false)
                if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
                    openAppSettings()
                else {
                    preferences.edit().putBoolean("microphone_asked", true).apply()
                    permission.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            } else {
                voice.setEnabled(!voice.isEnabled())
                updateMicrophoneButton()
                if (voice.isEnabled()) promptForCurrentStage()
            }
        }
        scannerMicrophoneToggle.setOnClickListener { microphoneToggle.performClick() }
        speechToggle.isChecked = preferences.getBoolean("speech_enabled", true)
        feedback.setSpeechEnabled(speechToggle.isChecked)
        speechToggle.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("speech_enabled", checked).apply()
            feedback.setSpeechEnabled(checked)
            if (checked) maybePromptIntro()
        }
        findViewById<Button>(R.id.startAssistance).setOnClickListener { startSelectedAssistance() }
        findViewById<Button>(R.id.backToMenu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.openNavigation).setOnClickListener { openNavigationMenu() }
        findViewById<Button>(R.id.speakDestination).setOnClickListener { requestDestinationSpeech() }
        findViewById<Button>(R.id.previewWalkingRoute).setOnClickListener {
            confirmWalkingRoute()
        }
        findViewById<Button>(R.id.retryLocation).setOnClickListener {
            if (!hasFineLocationPermission()) {
                if (getSharedPreferences("mobility_assist", MODE_PRIVATE).getBoolean("location_asked", false) &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION))
                    openAppSettings()
                else requestPermissions()
            } else if (!locationTracker.isEnabled())
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else {
                locationTracker.refresh()
                updateLocationStatus()
            }
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
            else {
                destinationInput.setText(home)
                destinationInput.setSelection(home.length)
                destinationSearchStatus.setText(R.string.destination_choose)
            }
        }

        destinationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                queueDestinationSearch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        try {
            walkingMap = InAppWalkingMap(this, menuMapHost, miniMapHost, expandedMapHost,
                onReady = { menuMapStatus.setText(R.string.map_ready) },
                onError = { code ->
                    Log.w("MobilityAssist", "Mappls map error code $code")
                    val resource = when (code) {
                        4 -> R.string.map_service_failed
                        403 -> R.string.map_quota_failed
                        401, 1, 2, 3, 5, 6 -> R.string.map_access_failed
                        else -> R.string.map_failed
                    }
                    menuMapStatus.text = getString(R.string.map_error_detail,
                        getString(resource), "map", code)
                    navigationStatus.setText(R.string.navigation_map_error)
                })
        } catch (error: Exception) {
            Log.w("MobilityAssist", "Map initialization unavailable", error)
            menuMapStatus.setText(R.string.map_access_failed)
        }

        // Phase 3: angle detector — callback fires on main thread
        angleDetector = AngleDetector(this) { state, _ ->
            if (!active || destroyed || paused) return@AngleDetector
            angleSuitable = state == AngleState.GOOD
            phase3State = phase3State.copy(angle = state)
            updatePhase3Ui()
            // Spoken correction is driven by the analyzed floor mask below, not
            // by orientation alone. A good angle never needs a spoken message.
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
        if (!hasCameraPermission() || !hasFineLocationPermission() ||
            !hasMicrophonePermission()) requestPermissions()
        else locationTracker.start()
        updateLocationStatus()
        updateMicrophoneButton()
        animateSplash()
    }

    private fun animateSplash() {
        val splash = findViewById<View>(R.id.splashPanel)
        val content = findViewById<View>(R.id.splashContent)
        content.translationY = 320f * resources.displayMetrics.density
        content.scaleX = 1.38f
        content.scaleY = 1.38f
        content.alpha = 0f
        content.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(1_200L).withEndAction {
                mainHandler.postDelayed({
                    splash.animate().alpha(0f).setDuration(350L).withEndAction {
                        splash.visibility = View.GONE
                        splashFinished = true
                        maybePromptIntro()
                    }.start()
                }, 650L)
            }.start()
    }

    private fun hasMicrophonePermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateMicrophoneButton() {
        microphoneToggle.setText(if (hasMicrophonePermission() && voice.isEnabled())
            R.string.mic_on else R.string.mic_off)
        scannerMicrophoneToggle.setText(if (hasMicrophonePermission() && voice.isEnabled())
            R.string.mic_short_on else R.string.mic_short_off)
        scannerMicrophoneToggle.contentDescription =
            getString(if (hasMicrophonePermission() && voice.isEnabled()) R.string.mic_on else R.string.mic_off)
    }

    private fun maybePromptIntro() {
        if (!::voice.isInitialized || !splashFinished || introPrompted ||
            !hasMicrophonePermission() || !active || !feedback.isSpeechReady()) return
        introPrompted = true
        voice.setVisible(true)
        voiceStage = VoiceStage.MENU
        promptForCurrentStage()
    }

    private fun voicePrompt(text: String) {
        findViewById<TextView>(R.id.voiceStatus).text = text
        voice.setSpeaking(true)
        if (!feedback.speak(text, SpeechPriority.URGENT, "voice_prompt_${SystemClock.elapsedRealtime()}", 0L))
            voice.setSpeaking(false)
    }

    private fun promptForCurrentStage() {
        if (!hasMicrophonePermission() || !voice.isEnabled()) return
        when (voiceStage) {
            VoiceStage.MENU -> voicePrompt("Say Start Camera Assistance or Enter Destination.")
            VoiceStage.DESTINATION -> voicePrompt("Where would you like to go? Say a place or address.")
            VoiceStage.CANDIDATE -> announceCandidate()
            VoiceStage.ROUTE_CONFIRM -> Unit
            VoiceStage.SCANNER -> voicePrompt("Camera Assistance is active. Say back to menu or microphone off.")
        }
    }

    private fun startSelectedAssistance() {
        if (depthOption.isChecked) startActivity(Intent(this, ArCoreDepthActivity::class.java))
        else showScanner()
    }

    private fun openNavigationMenu() {
        navigationMenu.visibility = View.VISIBLE
        voiceStage = VoiceStage.DESTINATION
        locationTracker.refresh()
        if (voice.isEnabled()) promptForCurrentStage()
    }

    private fun showScanner() {
        voiceStage = VoiceStage.SCANNER
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
        updateCameraAspect(false)
    }

    private fun showMenu() {
        voiceSearchPending = false
        voiceRouteFlow = false
        pendingVoiceRouteStart = false
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
        voiceStage = VoiceStage.MENU
        if (voice.isEnabled()) promptForCurrentStage()
    }

    private fun onVoiceWords(raw: String) {
        val command = VoiceCommands.parse(raw)
        if (command == VoiceIntent.MIC_OFF) {
            voice.setEnabled(false)
            updateMicrophoneButton()
            feedback.speak("Microphone off. Tap the microphone button to turn it on.",
                SpeechPriority.IMPORTANT, "mic_off_confirmation", 0L)
            return
        }
        if (command == VoiceIntent.MIC_ON) {
            voice.setEnabled(true)
            updateMicrophoneButton()
            promptForCurrentStage()
            return
        }
        if (command == VoiceIntent.MENU) { showMenu(); return }
        if (voiceSearchPending) return
        when (voiceStage) {
            VoiceStage.MENU -> when (command) {
                VoiceIntent.CAMERA -> startSelectedAssistance()
                VoiceIntent.NAVIGATE -> openNavigationMenu()
                else -> voicePrompt("Say Start Camera Assistance or Enter Destination.")
            }
            VoiceStage.DESTINATION -> when (command) {
                VoiceIntent.CAMERA -> startSelectedAssistance()
                else -> {
                    val query = raw.trim().replace(
                        Regex("(?i)^(please\\s+)?(enter destination|navigate|take me|go|walk|directions)\\s+(to\\s+)?"),
                        "").trim()
                    if (query.length < 3) {
                        promptForCurrentStage()
                        return
                    }
                    voiceSearchPending = true
                    destinationInput.setText(query)
                    destinationInput.setSelection(destinationInput.length())
                    voicePrompt("Searching for $query.")
                }
            }
            VoiceStage.CANDIDATE -> when (command) {
                VoiceIntent.YES -> {
                    val candidate = voiceCandidates.getOrNull(voiceCandidateIndex) ?: return
                    voiceStage = VoiceStage.ROUTE_CONFIRM
                    voiceRouteFlow = true
                    voicePrompt("Finding the walking route to ${candidate.name}.")
                    selectDestination(candidate) {
                        if (voiceRouteFlow && voiceStage == VoiceStage.ROUTE_CONFIRM)
                            confirmWalkingRoute()
                    }
                }
                VoiceIntent.NO -> {
                    voiceCandidateIndex++
                    if (voiceCandidateIndex >= voiceCandidates.size) {
                        voiceStage = VoiceStage.DESTINATION
                        voicePrompt("No more matching places. Say a different destination.")
                    } else announceCandidate()
                }
                else -> voicePrompt("Say yes to select this place, or no for the next result.")
            }
            VoiceStage.ROUTE_CONFIRM -> when (command) {
                VoiceIntent.YES -> {
                    if (confirmRouteByVoice == null) {
                        voicePrompt("Still finding the walking route. Please wait.")
                        return
                    }
                    routeConfirmationDialog?.dismiss()
                    routeConfirmationDialog = null
                    confirmRouteByVoice?.invoke()
                    confirmRouteByVoice = null
                }
                VoiceIntent.NO -> {
                    routeRequestId++
                    routeConfirmationDialog?.dismiss()
                    routeConfirmationDialog = null
                    confirmRouteByVoice = null
                    voiceRouteFlow = false
                    pendingVoiceRouteStart = false
                    voiceStage = VoiceStage.DESTINATION
                    voicePrompt("Route cancelled. Say another destination.")
                }
                else -> voicePrompt("Say yes to start walking guidance, or no to cancel.")
            }
            VoiceStage.SCANNER -> when (command) {
                VoiceIntent.NAVIGATE -> {
                    showMenu()
                    openNavigationMenu()
                }
                else -> Unit
            }
        }
    }

    private fun announceCandidate() {
        val candidate = voiceCandidates.getOrNull(voiceCandidateIndex) ?: return
        val locality = candidate.address.substringBefore(',').trim()
        val spoken = if (locality.isNotBlank() && !candidate.name.contains(locality, true))
            "${candidate.name}, $locality" else candidate.name
        voicePrompt("Did you mean $spoken? Say yes or no.")
    }

    private fun updateCameraAspect(expanded: Boolean) {
        val area = findViewById<FrameLayout>(R.id.cameraArea)
        area.post {
            val params = area.layoutParams as LinearLayout.LayoutParams
            if (expanded) {
                params.height = 0
                params.weight = 1f
            } else {
                val panel = findViewById<LinearLayout>(R.id.scannerPanel)
                val otherHeight = (0 until panel.childCount).sumOf { index ->
                    panel.getChildAt(index).let { child ->
                        if (child === area || child.visibility == View.GONE) 0 else child.height
                    }
                }
                val available = (panel.height - otherHeight).coerceAtLeast(1)
                params.height = minOf((area.width * 4f / 3f).toInt(), available)
                params.weight = 0f
            }
            area.layoutParams = params
        }
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

    private fun queueDestinationSearch(text: String) {
        searchRunnable?.let(mainHandler::removeCallbacks)
        val generation = ++searchGeneration
        routeRequestId++
        findViewById<Button>(R.id.previewWalkingRoute).isEnabled = true
        selectedDestination = null
        selectedPoint = null
        walkingMap?.setSelectedPlace(null)
        destinationSuggestions.removeAllViews()
        val query = text.trim()
        if (query.length < 3) {
            destinationSearchStatus.setText(R.string.destination_search_prompt)
            return
        }
        destinationSearchStatus.setText(R.string.destination_searching)
        searchRunnable = Runnable {
            routeService.suggest(query, locationTracker.latest) { result ->
                runOnUiThread {
                    if (destroyed || generation != searchGeneration) return@runOnUiThread
                    result.onSuccess { choices ->
                        destinationSuggestions.removeAllViews()
                        destinationSearchStatus.setText(
                            if (choices.isEmpty()) R.string.destination_no_results else R.string.destination_choose)
                        choices.forEach { candidate ->
                            val button = Button(this).apply {
                                setText(candidate.label)
                                isAllCaps = false
                                minHeight = (56 * resources.displayMetrics.density).toInt()
                                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_primary_button)
                                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cream))
                                contentDescription = candidate.label
                                setOnClickListener { selectDestination(candidate) }
                            }
                            destinationSuggestions.addView(button)
                        }
                        if (voiceSearchPending) {
                            voiceSearchPending = false
                            voiceCandidates = choices
                            voiceCandidateIndex = 0
                            if (choices.isEmpty()) {
                                voiceStage = VoiceStage.DESTINATION
                                voicePrompt("I could not find that place. Say a more specific destination.")
                            } else {
                                voiceStage = VoiceStage.CANDIDATE
                                announceCandidate()
                            }
                        }
                    }.onFailure {
                        showMapFailure(it, destinationSearchStatus)
                        if (voiceSearchPending) {
                            voiceSearchPending = false
                            voiceStage = VoiceStage.DESTINATION
                            voicePrompt("Place search failed. Check the connection and say the destination again.")
                        }
                    }
                }
            }
        }.also { mainHandler.postDelayed(it, 400L) }
    }

    private fun selectDestination(candidate: PlaceCandidate, onResolved: (() -> Unit)? = null) {
        destinationInput.setText(candidate.label)
        destinationInput.setSelection(destinationInput.length())
        searchRunnable?.let(mainHandler::removeCallbacks)
        val generation = ++searchGeneration
        selectedDestination = candidate
        destinationSuggestions.removeAllViews()
        destinationSearchStatus.setText(R.string.destination_resolving)
        findViewById<Button>(R.id.previewWalkingRoute).isEnabled = false
        routeService.resolve(candidate) { result ->
            runOnUiThread {
                if (destroyed || generation != searchGeneration) return@runOnUiThread
                findViewById<Button>(R.id.previewWalkingRoute).isEnabled = true
                result.onSuccess { point ->
                    selectedPoint = point
                    walkingMap?.setSelectedPlace(point)
                    destinationSearchStatus.text = getString(R.string.destination_selected, candidate.label)
                    menuMapStatus.setText(R.string.map_destination_preview)
                    onResolved?.invoke()
                }.onFailure {
                    showMapFailure(it, destinationSearchStatus)
                    if (onResolved != null) {
                        voiceStage = VoiceStage.DESTINATION
                        voicePrompt("I could not locate that place. Say another destination.")
                    }
                }
            }
        }
    }

    private fun showMapFailure(error: Throwable, target: TextView) {
        val failure = error as? MapFailure
        Log.w("MobilityAssist", "Mappls ${failure?.stage ?: "operation"} failed: ${failure?.kind ?: "unknown"} (${failure?.code ?: 0})")
        val resource = when (failure?.kind) {
            MapFailureKind.NO_RESULTS -> R.string.destination_no_results
            MapFailureKind.NO_ROUTE -> R.string.route_no_pedestrian
            MapFailureKind.NETWORK -> R.string.map_network_failed
            MapFailureKind.ACCESS -> R.string.map_access_failed
            MapFailureKind.QUOTA -> R.string.map_quota_failed
            MapFailureKind.SERVICE -> R.string.map_service_failed
            else -> R.string.route_failed
        }
        target.text = if (failure != null && failure.code != 0)
            getString(R.string.map_error_detail, getString(resource), failure.stage, failure.code)
        else getString(resource)
    }

    private fun confirmWalkingRoute() {
        val destination = selectedDestination
        val point = selectedPoint
        if (destination == null || point == null) {
            destinationSearchStatus.setText(R.string.destination_choose)
            return
        }
        if (!hasCameraPermission()) {
            requestPermissions()
            if (voiceRouteFlow) voicePrompt("Camera permission is needed to start guidance.")
            return
        }
        if (!hasFineLocationPermission()) {
            updateLocationStatus()
            Toast.makeText(this, R.string.location_permission_required, Toast.LENGTH_LONG).show()
            if (voiceRouteFlow) voicePrompt("Precise location permission is needed to start walking guidance.")
            return
        }
        if (!locationTracker.isEnabled()) {
            updateLocationStatus()
            if (voiceRouteFlow) voicePrompt("Device location is off. Please turn it on.")
            return
        }
        val origin = locationTracker.latest
        if (!PreciseLocationTracker.usable(origin, 40f)) {
            locationTracker.refresh()
            updateLocationStatus()
            destinationSearchStatus.setText(R.string.location_wait_for_route)
            if (voiceRouteFlow && !pendingVoiceRouteStart) {
                pendingVoiceRouteStart = true
                voicePrompt("Waiting for a precise location. Please move outdoors. I will continue when it is ready.")
            }
            return
        }
        pendingVoiceRouteStart = false
        val requestId = ++routeRequestId
        val routeButton = findViewById<Button>(R.id.previewWalkingRoute)
        routeButton.isEnabled = false
        findViewById<TextView>(R.id.currentLocationStatus).setText(R.string.route_loading)
        mainHandler.postDelayed({
            if (!destroyed && routeRequestId == requestId && !routeButton.isEnabled) {
                routeRequestId++
                routeButton.isEnabled = true
                destinationSearchStatus.setText(R.string.route_timeout)
                if (voiceRouteFlow) {
                    voiceStage = VoiceStage.DESTINATION
                    voiceRouteFlow = false
                    voicePrompt("The walking route timed out. Please try the destination again.")
                }
            }
        }, 20_000L)
        routeService.routeFrom(origin!!, point, destination.label) { result ->
            runOnUiThread {
                if (destroyed || requestId != routeRequestId) return@runOnUiThread
                routeButton.isEnabled = true
                onLocationFix(locationTracker.latest ?: origin)
                result.onSuccess { route ->
                    walkingMap?.setRoute(route)
                    menuMapStatus.setText(R.string.map_route_preview)
                    val completed = AtomicBoolean(false)
                    mainHandler.postDelayed({
                        if (completed.compareAndSet(false, true) && !destroyed && requestId == routeRequestId)
                            showRouteConfirmation(origin, null, route)
                    }, 4_000L)
                    routeService.describeOrigin(origin) { address ->
                        runOnUiThread {
                            if (completed.compareAndSet(false, true) && !destroyed && requestId == routeRequestId)
                                showRouteConfirmation(origin, address, route)
                        }
                    }
                }.onFailure { error ->
                    showMapFailure(error, destinationSearchStatus)
                    if (voiceRouteFlow) {
                        voiceStage = VoiceStage.DESTINATION
                        voiceRouteFlow = false
                        voicePrompt("I could not find a walking route. Try another destination.")
                    }
                }
            }
        }
    }

    private fun showRouteConfirmation(origin: Location, originAddress: String?,
                                      route: WalkingRoute) {
        val originText = originAddress?.takeIf { it.isNotBlank() }
            ?: "${"%.5f".format(origin.latitude)}, ${"%.5f".format(origin.longitude)}"
        val minutes = ceil((route.route.duration() ?: 0.0) / 60.0).toInt().coerceAtLeast(1)
        val km = (route.route.distance() ?: 0.0) / 1000.0
        val message = getString(R.string.route_confirm_details, originText, origin.accuracy,
            route.destinationName, minutes, km)
        val spokenName = route.destinationName.substringBefore(',').trim()
            .ifBlank { route.destinationName }
        voiceStage = VoiceStage.ROUTE_CONFIRM
        val startRoute = {
            val latest = locationTracker.latest
            val moved = latest == null || !PreciseLocationTracker.usable(latest, 40f) ||
                WalkingGuidance.distanceMeters(
                    GeoPosition(origin.latitude, origin.longitude),
                    GeoPosition(latest.latitude, latest.longitude)) > 35.0
            if (moved) {
                Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_LONG).show()
                confirmWalkingRoute()
            } else startNavigation(route)
        }
        confirmRouteByVoice = startRoute
        voicePrompt("From your current location to $spokenName. About $minutes minutes. Say yes to start walking navigation, or no to cancel.")
        routeConfirmationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.route_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel) { _, _ ->
                confirmRouteByVoice = null
                voiceRouteFlow = false
                voiceStage = VoiceStage.DESTINATION
            }
            .setPositiveButton(R.string.start_navigation) { _, _ ->
                confirmRouteByVoice = null
                startRoute()
            }
            .show().also { dialog ->
                listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE).forEach { which ->
                    dialog.getButton(which)?.apply {
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_primary_button)
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cream))
                        isAllCaps = false
                    }
                }
            }
    }

    private fun startNavigation(route: WalkingRoute) {
        voiceStage = VoiceStage.SCANNER
        voiceRouteFlow = false
        pendingVoiceRouteStart = false
        walkingRoute = route
        walkingGuidance = guidanceFor(route)
        rerouting = false
        lastRerouteMs = 0L
        showScanner()
        navigationStatus.visibility = View.VISIBLE
        navigationStatus.text = getString(R.string.navigation_active, route.destinationName)
        miniMapHost.visibility = View.VISIBLE
        expandedMapHost.visibility = View.GONE
        if (walkingMap == null) {
            navigationStatus.setText(R.string.navigation_map_error)
            miniMapHost.visibility = View.GONE
        } else {
            walkingMap?.setRoute(route)
            walkingMap?.setExpanded(false)
            locationTracker.latest?.let { walkingMap?.updatePosition(it) }
        }
        feedback.speak("Walking route started. Camera warnings take priority.",
            SpeechPriority.IMPORTANT, "route_started", 0L)
        locationTracker.latest?.let(::onLocationFix)
    }

    private fun guidanceFor(value: WalkingRoute): WalkingGuidance {
        val shape = value.route.getCoordinates().map { GeoPosition(it.latitude(), it.longitude()) }
        val mappedTurns = value.route.legs().orEmpty().flatMap { it.steps().orEmpty() }.mapNotNull { step ->
            val maneuver = step.maneuver() ?: return@mapNotNull null
            if (maneuver.type() == "depart" || maneuver.type() == "arrive") return@mapNotNull null
            val modifier = maneuver.modifier()?.lowercase().orEmpty()
            val instruction = when {
                modifier.contains("slight left") -> "Take a slight left"
                modifier.contains("slight right") -> "Take a slight right"
                modifier.contains("left") -> "Turn left"
                modifier.contains("right") -> "Turn right"
                modifier.contains("straight") -> null // Periodic straight cues use route progress.
                modifier.contains("uturn") || modifier.contains("u-turn") -> "Turn around"
                else -> null
            } ?: return@mapNotNull null
            val point = maneuver.location() ?: return@mapNotNull null
            RouteTurn(GeoPosition(point.latitude(), point.longitude()), instruction)
        }
        val inferredTurns = RouteGeometry.inferredTurns(shape)
        val turns = mappedTurns + inferredTurns.filter { inferred ->
            mappedTurns.none { WalkingGuidance.distanceMeters(it.position, inferred.position) < 30.0 }
        }
        Log.d("MobilityAssist", "Walking maneuvers: Mappls=${mappedTurns.size}, geometry=${inferredTurns.size}")
        return WalkingGuidance(shape, turns,
            GeoPosition(value.destination.latitude(), value.destination.longitude()))
    }

    private fun onLocationFix(location: Location) {
        updateLocationStatus()
        if (pendingVoiceRouteStart && PreciseLocationTracker.usable(location, 40f) &&
            selectedDestination != null && selectedPoint != null) {
            pendingVoiceRouteStart = false
            confirmWalkingRoute()
        }
        if (walkingRoute == null || !scanningEnabled) {
            walkingMap?.updatePosition(location)
            return
        }
        if (paused || failed || !hasCameraPermission()) return
        if (!PreciseLocationTracker.usable(location, 45f)) {
            walkingMap?.updatePosition(location)
            navigationStatus.setText(R.string.navigation_location_uncertain)
            feedback.speak(getString(R.string.navigation_location_uncertain),
                SpeechPriority.IMPORTANT, "location_uncertain", 30_000L)
            return
        }
        val cue = walkingGuidance?.update(
            GeoPosition(location.latitude, location.longitude), location.accuracy)
        val match = walkingGuidance?.lastMatch
        val display = if (match != null && match.distanceMeters <=
            maxOf(35.0, location.accuracy * 1.5)) Location(location).apply {
                latitude = match.point.latitude
                longitude = match.point.longitude
            } else location
        walkingMap?.updatePosition(display)
        if (cue == null) return
        if (navigationStatus.text.toString() != cue.text) navigationStatus.text = cue.text
        if (cue.key == "reroute") rerouteFrom(location)
        val priority = if (cue.key.startsWith("turn_now_") || cue.key == "reroute")
            SpeechPriority.URGENT else SpeechPriority.IMPORTANT
        if (feedback.speak(cue.text, priority, "map_${cue.key}", 20_000L)) {
            walkingGuidance?.markSpoken(cue)
        }
    }

    private fun rerouteFrom(location: Location) {
        val route = walkingRoute ?: return
        val now = SystemClock.elapsedRealtime()
        if (rerouting || now - lastRerouteMs < 10_000L) return
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
                    feedback.speak("Walking route updated. Check the next instruction.",
                        SpeechPriority.IMPORTANT, "map_route_updated", 5_000L)
                }.onFailure { error ->
                    Log.w("MobilityAssist", "Rerouting failed", error)
                    navigationStatus.setText(R.string.route_failed)
                    feedback.speak("Cannot update the walking route. Stop and check your location.",
                        SpeechPriority.URGENT, "map_route_failed", 15_000L)
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
        updateCameraAspect(expanded)
    }

    private fun stopNavigation() {
        routeRequestId++
        walkingRoute = null
        walkingGuidance = null
        rerouting = false
        walkingMap?.showMenu()
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
                                scanRecovery.reset()
                                stairDetector.reset()
                                stairTracker.reset()
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
                                pathSegmenter?.maybeAnalyze(frame, frameResult)
                            } catch (error: Exception) {
                                Log.w("MobilityAssist", "Path segmenter unavailable", error)
                                pathSegmenter?.close()
                                pathSegmenter = null
                                null
                            }

                            // Object boxes retain priority over tentative path guidance.
                            val result = checkNotNull(detector).detect(frame)
                            val reliableView = angleSuitable &&
                                frameResult?.occlusionState == OcclusionState.CLEAR
                            val objectWarning = engine.decide(result.objects, pathResult, reliableView)
                            val pathWarning = PathWarningSelector.select(
                                pathResult, result.objects,
                                reliableView
                            )
                            val stairWarning = if (reliableView) try {
                                stairTracker.observe(stairDetector.maybeAnalyze(frame))
                            } catch (error: Exception) {
                                Log.w("MobilityAssist", "Stair pattern analysis failed", error)
                                stairTracker.reset()
                                null
                            } else {
                                stairTracker.reset()
                                null
                            }
                            val candidate = scanRecovery.observe(stairWarning ?: objectWarning ?: pathWarning,
                                pathResult, reliableView, result.objects)
                            // PathGuidanceTracker confirms independent model results. Do not
                            // require more CameraX frames or count a cached mask as evidence.
                            val warning = if (candidate == Warning.PATH_CLEAR ||
                                candidate == Warning.POSSIBLE_STAIRS ||
                                candidate == pathWarning && pathWarning != null) {
                                stabilizer.reset()
                                candidate
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
                                            Warning.SCAN_LEFT, Warning.SCAN_RIGHT, Warning.SCAN_SIDES,
                                            Warning.POSSIBLE_OBSTRUCTION, Warning.PATH_BLOCKED,
                                            Warning.PATH_LEFT, Warning.PATH_RIGHT ->
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
                                    if (frameResult?.occlusionState == OcclusionState.CLEAR && pathResult != null &&
                                        phase3State.angle != AngleState.GOOD &&
                                        !pathResult.blocked &&
                                        result.objects.none { it.blocksCenter && it.proximity != Proximity.FAR } &&
                                        !pathResult.floorVisible()) {
                                        feedback.speak("Tilt the phone until the floor is visible.",
                                            SpeechPriority.INFORMATIONAL, "floor_angle", 5_000L)
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

    private fun hasLocationPermission() = hasFineLocationPermission() ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateLocationStatus() {
        val label = findViewById<TextView>(R.id.currentLocationStatus)
        label.text = when {
            !hasLocationPermission() -> getString(R.string.location_denied)
            !hasFineLocationPermission() -> getString(R.string.location_approximate)
            !locationTracker.isEnabled() -> getString(R.string.location_off)
            PreciseLocationTracker.usable(locationTracker.latest) -> {
                val fix = locationTracker.latest!!
                getString(R.string.location_accuracy, fix.latitude, fix.longitude, fix.accuracy)
            }
            locationTracker.latest != null &&
                PreciseLocationTracker.usable(locationTracker.latest, Float.MAX_VALUE) ->
                getString(R.string.location_weak, locationTracker.latest!!.accuracy)
            else -> getString(R.string.location_acquiring)
        }
    }

    private fun requestPermissions() {
        val missing = mutableListOf<String>()
        if (!hasCameraPermission()) missing += Manifest.permission.CAMERA
        if (!hasFineLocationPermission()) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
            getSharedPreferences("mobility_assist", MODE_PRIVATE).edit()
                .putBoolean("location_asked", true).apply()
        }
        val preferences = getSharedPreferences("mobility_assist", MODE_PRIVATE)
        if (!hasMicrophonePermission() && !preferences.getBoolean("microphone_asked", false)) {
            missing += Manifest.permission.RECORD_AUDIO
            preferences.edit().putBoolean("microphone_asked", true).apply()
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
        maybePromptIntro()
        locationTracker.start()
        updateLocationStatus()
        walkingMap?.onStart()
        if (initialized && hasCameraPermission()) {
            permissionBlocked = false
            updateButton()
        }
        if (scanningEnabled) preview.post { bindCamera() }
    }

    override fun onResume() {
        super.onResume()
        if (::voice.isInitialized && hasMicrophonePermission() && splashFinished)
            voice.setVisible(true)
        maybePromptIntro()
        locationTracker.start()
        if (hasLocationPermission() && locationTracker.isEnabled()) locationTracker.refresh()
        updateLocationStatus()
        if (!hasCameraPermission() && walkingRoute != null) stopNavigation()
        walkingMap?.onResume()
        if (initialized && !hasCameraPermission()) showPermissionState()
        // Update display rotation so angle sensor remapping stays correct
        angleDetector.updateDisplayRotation(
            preview.display?.rotation ?: Surface.ROTATION_0
        )
    }

    override fun onPause() {
        if (::voice.isInitialized) voice.setVisible(false)
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
        routeConfirmationDialog?.dismiss()
        if (::voice.isInitialized) voice.close()
        stopNavigation()
        walkingMap?.destroy()
        walkingMap = null
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
