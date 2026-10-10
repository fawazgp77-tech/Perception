package com.example.mobilityassist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var objects = emptyList<Obstacle>()
    private val density = resources.displayMetrics.density
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * resources.displayMetrics.density * resources.configuration.fontScale
    }
    private val background = Paint().apply { color = 0xCC101820.toInt() }
    private val guides = Paint().apply { color = 0x99FFFFFF.toInt(); strokeWidth = density }
    fun show(results: List<Obstacle>) { objects = results; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (i in 1..2) canvas.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), guides)
        listOf("LEFT", "CENTER", "RIGHT").forEachIndexed { i, label ->
            val x = width * (i + 0.5f) / 3 - textPaint.measureText(label) / 2
            canvas.drawText(label, x, 24 * density, textPaint)
        }
        for (obj in objects) {
            boxPaint.color = when (obj.proximity) {
                Proximity.VERY_NEAR -> Color.RED
                Proximity.NEAR -> Color.YELLOW
                Proximity.FAR -> Color.GREEN
            }
            val left = obj.left * width
            val top = obj.top * height
            canvas.drawRect(left, top, obj.right * width, obj.bottom * height, boxPaint)
            val label = "${obj.label} ${(obj.score * 100).toInt()}% · ${obj.region} · ${obj.proximity}"
            val x = left.coerceAtMost((width - textPaint.measureText(label) - 8 * density).coerceAtLeast(0f))
            val baseline = top.coerceIn(48 * density, height.toFloat().coerceAtLeast(48 * density))
            canvas.drawRect(x, baseline + textPaint.ascent() - 4 * density,
                x + textPaint.measureText(label) + 8 * density, baseline + 4 * density, background)
            canvas.drawText(label, x + 4 * density, baseline, textPaint)
        }
    }
}
