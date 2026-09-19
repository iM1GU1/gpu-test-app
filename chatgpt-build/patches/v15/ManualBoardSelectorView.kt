package com.example.berserkoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

class ManualBoardSelectorView(
    context: Context,
    private val initial: RectF?,
    private val onSelected: (RectF?) -> Unit
) : View(context) {

    private val shade = Paint().apply { color = Color.argb(145, 0, 0, 0) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
    }

    private var startX = 0f
    private var startY = 0f
    private var current: RectF? = initial?.let { RectF(it) }
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)

        val r = current
        if (r != null) {
            // Vacía visualmente la zona elegida.
            val clear = Paint().apply { color = Color.argb(65, 255, 255, 255) }
            canvas.drawRect(r, clear)
            canvas.drawRect(r, line)
            val cell = r.width() / 8f
            for (i in 1..7) {
                canvas.drawLine(r.left + i * cell, r.top, r.left + i * cell, r.bottom, grid)
                canvas.drawLine(r.left, r.top + i * cell, r.right, r.top + i * cell, grid)
            }
        }

        canvas.drawText(
            "Arrastra sobre las 64 casillas",
            28f,
            64f,
            textPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                current = RectF(startX, startY, startX, startY)
                dragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val dx = event.x - startX
                val dy = event.y - startY
                val side = min(abs(dx), abs(dy))
                if (side < 1f) return true
                val right = startX + if (dx >= 0f) side else -side
                val bottom = startY + if (dy >= 0f) side else -side
                current = RectF(
                    minOf(startX, right),
                    minOf(startY, bottom),
                    maxOf(startX, right),
                    maxOf(startY, bottom)
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                val r = current
                if (r != null && r.width() >= 160f && r.height() >= 160f) {
                    onSelected(RectF(r))
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onSelected(null)
                return true
            }
        }
        return true
    }
}
