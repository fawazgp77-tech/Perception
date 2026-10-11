package com.example.mobilityassist

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
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
                    val voice = if (status == TextToSpeech.SUCCESS) engine?.voices
                        ?.filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language }
                        ?.sortedBy { it.name }?.firstOrNull() else null
                    ready = voice != null && engine?.setVoice(voice) == TextToSpeech.SUCCESS
                    onStatus(appContext.getString(if (ready) R.string.speech_ready else R.string.speech_unavailable))
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Existing obstacle warning path — UNCHANGED
    // -----------------------------------------------------------------------
    fun warn(warning: Warning): Boolean {
        if (closed || !gate.allow(warning, SystemClock.elapsedRealtime())) return false
        if (ready && tts?.speak(warning.text, TextToSpeech.QUEUE_FLUSH, null, warning.name) == TextToSpeech.ERROR) {
            onStatus(appContext.getString(R.string.speech_failed))
        }
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
        if (closed || !ready) return false
        if (!phase3Gate.allow(cooldownKey, cooldownMs, SystemClock.elapsedRealtime())) return false
        val queueMode = if (priority == SpeechPriority.URGENT) TextToSpeech.QUEUE_FLUSH
                        else TextToSpeech.QUEUE_ADD
        val result = tts?.speak(text, queueMode, null, "p3_$cooldownKey")
        if (result == TextToSpeech.ERROR) {
            onStatus(appContext.getString(R.string.speech_failed))
            return false
        }
        return true
    }

    /** Reset the Phase 3 cooldown map — call on stop/pause so stale cooldowns don't suppress fresh messages. */
    fun resetPhase3Gate() { phase3Gate.reset() }

    fun stop() { tts?.stop(); vibrator?.cancel(); gate.reset(); phase3Gate.reset() }
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
