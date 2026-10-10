package com.example.mobilityassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** Create, infer and close on the same single worker thread. Caller owns ImageProxy. */
class ObjectDetectorHelper(context: Context) : AutoCloseable {
    private val detector = ObjectDetector.createFromOptions(context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_NAME).build())
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.45f)
            .setMaxResults(10)
            .setCategoryAllowlist(listOf("person", "car", "bus", "truck", "bicycle", "motorcycle", "dog"))
            .build())

    data class Result(val objects: List<Obstacle>, val conversionMs: Long, val inferenceMs: Long)

    fun detect(frame: ImageProxy): Result {
        // CameraX handles pixel/row strides in toBitmap(). Shared ViewPort ensures
        // this crop matches PreviewView, including non-4:3 screens and rotation.
        val startNs = SystemClock.elapsedRealtimeNanos()
        val raw = frame.toBitmap()
        var upright: Bitmap? = null
        try {
            val crop = frame.cropRect
            val rotation = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
            val image = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height(), rotation, true)
            upright = image
            val mpImage = BitmapImageBuilder(image).build()
            try {
                val convertedNs = SystemClock.elapsedRealtimeNanos()
                val detections = detector.detect(mpImage).detections()
                val inferredNs = SystemClock.elapsedRealtimeNanos()
                val objects = detections.mapNotNull { detection ->
                    val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                    val box = detection.boundingBox()
                    val left = (box.left / image.width).coerceIn(0f, 1f)
                    val top = (box.top / image.height).coerceIn(0f, 1f)
                    val right = (box.right / image.width).coerceIn(0f, 1f)
                    val bottom = (box.bottom / image.height).coerceIn(0f, 1f)
                    if (right <= left || bottom <= top) null else
                        Obstacle(category.categoryName(), category.score(), left, top, right, bottom)
                }
                return Result(objects, (convertedNs - startNs) / 1_000_000L,
                    (inferredNs - convertedNs) / 1_000_000L)
            } finally { mpImage.close() }
        } finally {
            if (upright !== raw) upright?.recycle()
            raw.recycle()
        }
    }

    override fun close() = detector.close()
    companion object { const val MODEL_NAME = "efficientdet_lite0.tflite" }
}
