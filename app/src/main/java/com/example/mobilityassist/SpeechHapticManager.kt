package com.example.mobilityassist

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Main-thread only. Never queues frame-by-frame utterances. */
class SpeechHapticManager(context: Context, private val onStatus: (String) -> Unit) {
    private val appContext = context.applicationContext
    private var ready = false
    private var closed = false
    private val gate = WarningGate()
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
    fun stop() { tts?.stop(); vibrator?.cancel(); gate.reset() }
    fun close() { closed = true; stop(); tts?.shutdown(); tts = null }
}
