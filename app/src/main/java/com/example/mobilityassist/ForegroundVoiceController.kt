package com.example.mobilityassist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/** Foreground-only, best-effort listening. Restarts after each final result; never records audio. */
class ForegroundVoiceController(
    private val context: Context,
    private val onWords: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var visible = false
    private var enabled = true
    private var speaking = false
    private var listening = false
    private var closed = false
    private var retryMs = 500L
    private var onDevice = false
    private val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }
    private val restart = Runnable { listen() }

    fun setVisible(value: Boolean) {
        visible = value
        if (value) schedule(250L) else cancelSession()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) schedule(250L) else {
            cancelSession()
            onStatus("Microphone off. Tap the microphone button to turn it on.")
        }
    }

    fun isEnabled() = enabled

    fun setSpeaking(value: Boolean) {
        speaking = value
        if (value) cancelSession() else schedule(550L)
    }

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true
        val available = SpeechRecognizer.isRecognitionAvailable(context) ||
            (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
        if (!available) {
            onStatus("Speech recognition unavailable. Use the large buttons.")
            return false
        }
        try {
            recognizer = if (Build.VERSION.SDK_INT >= 31) createRecognizerApi31()
                else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onStatus(if (onDevice) "Listening · on-device voice"
                        else "Listening · speech service may use internet")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    listening = false
                    if (!visible || !enabled || speaking) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        onStatus("Microphone permission needed. Tap the microphone button.")
                        return
                    }
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_CLIENT) {
                        recognizer?.destroy(); recognizer = null
                    }
                    retryMs = (retryMs * 2).coerceAtMost(4_000L)
                    schedule(retryMs)
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    retryMs = 500L
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (words.isNotBlank() && visible && enabled && !speaking) onWords(words)
                    schedule(650L)
                }
            })
            return true
        } catch (_: Exception) {
            onStatus("Speech recognition unavailable. Use the large buttons.")
            return false
        }
    }

    @android.annotation.TargetApi(31)
    private fun createRecognizerApi31(): SpeechRecognizer {
        onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun listen() {
        if (closed || !visible || !enabled || speaking || listening ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED || !ensureRecognizer()) return
        try {
            recognizer?.startListening(listenIntent)
            listening = true
        } catch (_: Exception) { schedule(2_000L) }
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(restart)
        if (!closed && visible && enabled && !speaking) handler.postDelayed(restart, delayMs)
    }

    private fun cancelSession() {
        handler.removeCallbacks(restart)
        if (listening) {
            listening = false
            recognizer?.cancel()
        }
    }

    fun close() {
        closed = true
        cancelSession()
        recognizer?.destroy()
        recognizer = null
    }
}
