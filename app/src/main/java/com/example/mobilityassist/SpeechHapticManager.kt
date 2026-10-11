package com.example.mobilityassist

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Priority levels for Phase 3 spoken messages.
 * Lower number = higher priority.
 * CRITICAL obstacle warnings use the existing warn(Warning) path — not this enum.
 */
enum class SpeechPriority {
    URGENT,        // 1 — camera obstruction: QUEUE_FLUSH, interrupts lower speech
    IMPORTANT,     // 2 — flashlight state change: QUEUE_ADD
    INFORMATIONAL, // 3 — angle guidance: QUEUE_ADD
    ROUTINE        // 4 — restoration / good-angle confirmations: QUEUE_ADD
}

/** Main-thread only. Never queues frame-by-frame utterances. */
class SpeechHapticManager(context: Context, private val onStatus: (String) -> Unit) {
    private val appContext = context.applicationContext
    private var ready = false
    private var closed = false
    private var speechEnabled = true
    private var onSpeechActivity: ((Boolean) -> Unit)? = null
    private var lastObstacleSpeechMs = Long.MIN_VALUE

    // -----------------------------------------------------------------------
    // Existing obstacle gate — used EXCLUSIVELY by warn(Warning).
    // Phase 3 speech NEVER touches this gate, so Phase 3 messages can never
    // consume the obstacle gate's global quiet period and block a STOP warning.
    // -----------------------------------------------------------------------
    private val gate = WarningGate()

    // Separate gate for all Phase 3 messages — completely isolated.
    private val phase3Gate = Phase3SpeechGate()

    private var tts: TextToSpeech? = null
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else context.getSystemService(Vibrator::class.java)

    init {
        tts = TextToSpeech(appContext) { status ->
            // Post so the constructor assignment has completed even with a fast engine.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!closed) {
                    val engine = tts
                    val englishVoices = if (status == TextToSpeech.SUCCESS) engine?.voices
                        ?.filter { it.locale.language == Locale.ENGLISH.language }
                        ?.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
                        .orEmpty() else emptyList()
                    val voice = englishVoices.firstOrNull()
                    ready = if (voice != null) engine?.setVoice(voice) == TextToSpeech.SUCCESS
                        else status == TextToSpeech.SUCCESS &&
                            (engine?.setLanguage(Locale.ENGLISH) ?: TextToSpeech.LANG_NOT_SUPPORTED) >=
                                TextToSpeech.LANG_AVAILABLE
                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = speechActivity(true)
                        override fun onDone(utteranceId: String?) = speechActivity(false)
                        @Deprecated("Android callback")
                        override fun onError(utteranceId: String?) = speechActivity(false)
                        override fun onStop(utteranceId: String?, interrupted: Boolean) = speechActivity(false)
                    })
                    onStatus(appContext.getString(if (ready) R.string.speech_ready else R.string.speech_unavailable))
                }
            }
        }
    }

    private fun speechActivity(speaking: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!closed) onSpeechActivity?.invoke(speaking)
        }
    }

    fun setSpeechActivityListener(listener: (Boolean) -> Unit) { onSpeechActivity = listener }
    fun isSpeechReady() = ready && speechEnabled && !closed

    // -----------------------------------------------------------------------
    // Existing obstacle warning path — UNCHANGED
    // -----------------------------------------------------------------------
    fun warn(warning: Warning): Boolean {
        if (closed || !gate.allow(warning, SystemClock.elapsedRealtime())) return false
        if (ready && speechEnabled && tts?.speak(warning.text, TextToSpeech.QUEUE_FLUSH, null, warning.name) == TextToSpeech.ERROR) {
            onStatus(appContext.getString(R.string.speech_failed))
        }
        lastObstacleSpeechMs = SystemClock.elapsedRealtime()
        if (vibrator?.hasVibrator() == true) {
            val effect = if (warning.urgent) VibrationEffect.createWaveform(
                longArrayOf(0, 300, 100, 300), intArrayOf(0, 255, 0, 255), -1)
            else VibrationEffect.createOneShot(100, 100)
            vibrator.vibrate(effect)
        }
        return true
    }

    // -----------------------------------------------------------------------
    // Phase 3 speech path — throttled by priority and per-key cooldown.
    // Uses a separate Phase3SpeechGate so obstacle warnings are never blocked.
    // -----------------------------------------------------------------------
    /**
     * Speak a Phase 3 message with per-key throttling.
     *
     * @param text        The utterance text.
     * @param priority    Controls queue mode: URGENT → QUEUE_FLUSH; others → QUEUE_ADD.
     * @param cooldownKey Unique string key for per-message deduplication (e.g. "flashlight_on").
     * @param cooldownMs  How long to suppress repeats of this key (milliseconds).
     * @return true if the message was emitted; false if suppressed or TTS not ready.
     */
    fun speak(
        text: String,
        priority: SpeechPriority,
        cooldownKey: String,
        cooldownMs: Long
    ): Boolean {
        if (closed || !ready || !speechEnabled) return false
        val now = SystemClock.elapsedRealtime()
        if (priority != SpeechPriority.URGENT &&
            now - lastObstacleSpeechMs in 0..5_999L) return false
        val recentObstacle = now - lastObstacleSpeechMs in 0..2_999L
        // Do not queue a stale turn behind a camera warning. Retry on the next fix.
        if (recentObstacle && cooldownKey.startsWith("map_turn_now_")) return false
        if (!phase3Gate.allow(cooldownKey, cooldownMs, now)) return false
        val queueMode = if (priority == SpeechPriority.URGENT && !recentObstacle)
            TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = tts?.speak(text, queueMode, null, "p3_$cooldownKey")
        if (result == TextToSpeech.ERROR) {
            onStatus(appContext.getString(R.string.speech_failed))
            return false
        }
        return true
    }

    /** Reset the Phase 3 cooldown map — call on stop/pause so stale cooldowns don't suppress fresh messages. */
    fun resetPhase3Gate() { phase3Gate.reset() }

    fun setSpeechEnabled(enabled: Boolean) {
        speechEnabled = enabled
        if (!enabled) tts?.stop()
    }

    fun stop() { tts?.stop(); vibrator?.cancel(); gate.reset(); phase3Gate.reset(); lastObstacleSpeechMs = Long.MIN_VALUE }
    fun close() { closed = true; stop(); tts?.shutdown(); tts = null }
}

/**
 * Independent per-key cooldown map for Phase 3 messages.
 *
 * Each key has its own independent timer — one message type being throttled
 * does not suppress a different message type. There is no global quiet period
 * (unlike WarningGate), so angle guidance doesn't block flashlight announcements
 * and neither ever blocks obstacle warnings.
 */
class Phase3SpeechGate {
    private val times = mutableMapOf<String, Long>()

    /**
     * @return true if the key's cooldown has expired (or this is the first call for this key).
     *         Records the current time so subsequent calls within cooldownMs are suppressed.
     */
    fun allow(key: String, cooldownMs: Long, nowMs: Long): Boolean {
        val last = times[key]
        if (last != null && nowMs - last < cooldownMs) return false
        times[key] = nowMs
        return true
    }

    fun reset() { times.clear() }
}
