package com.example.mobilityassist

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.atomic.AtomicBoolean

/** A sparse, bundled on-device classifier for walls in the central camera view. */
class SceneLabeler : AutoCloseable {
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.65f).build()
    )
    private val inFlight = AtomicBoolean(false)
    private var lastStartedMs = 0L // CameraX worker only.
    private var consecutiveWallLabels = 0 // ML Kit callback (main thread).
    @Volatile private var closed = false

    /** Caller retains and closes ImageProxy; this method copies only one sparse frame. */
    fun maybeAnalyze(frame: ImageProxy, onWall: (Boolean) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (closed || now - lastStartedMs < 1_500L || !inFlight.compareAndSet(false, true)) return
        lastStartedMs = now
        var crop: Bitmap? = null
        try {
            val raw = frame.toBitmap()
            try {
                val x = raw.width / 5
                val y = raw.height / 6
                crop = Bitmap.createBitmap(raw, x, y, raw.width - 2 * x, raw.height - 2 * y)
            } finally { raw.recycle() }
            val retained = checkNotNull(crop)
            val image = InputImage.fromBitmap(retained, frame.imageInfo.rotationDegrees)
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    if (!closed) {
                        val wall = WallLabelPolicy.isLikelyWall(labels.map { it.text to it.confidence })
                        consecutiveWallLabels = if (wall) consecutiveWallLabels + 1 else 0
                        onWall(consecutiveWallLabels >= 2)
                    }
                }
                .addOnFailureListener { consecutiveWallLabels = 0 }
                .addOnCompleteListener {
                    retained.recycle()
                    inFlight.set(false)
                    if (closed) labeler.close()
                }
        } catch (error: Exception) {
            crop?.recycle()
            inFlight.set(false)
            throw error
        }
    }

    override fun close() {
        closed = true
        if (!inFlight.get()) labeler.close()
    }
}

/** No location or distance is inferred from an image-level label. */
object WallLabelPolicy {
    fun isLikelyWall(labels: List<Pair<String, Float>>): Boolean =
        labels.any { (name, score) -> name.equals("Wall", ignoreCase = true) && score >= 0.70f }
}
