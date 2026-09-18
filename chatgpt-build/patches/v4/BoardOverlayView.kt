package com.example.berserkoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class BoardOverlayView(context: Context) : View(context) {
    var boardRect = RectF()
    var whiteAtBottom = true
    private var lines: List<AnalysisLine> = emptyList()

    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val scoreFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun setAnalysis(newLines: List<AnalysisLine>) {
        lines = newLines
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boardRect.width() <= 0 || lines.isEmpty()) return

        val colors = intArrayOf(
            Color.rgb(0, 200, 83),
            Color.rgb(255, 214, 0),
            Color.rgb(213, 0, 0)
        )
        val cell = min(boardRect.width(), boardRect.height()) / 8f

        lines.take(3).forEachIndexed { index, line ->
            if (line.move.length < 4) return@forEachIndexed
            val color = colors[index]
            val from = squareRect(line.move.substring(0, 2))
            val to = squareRect(line.move.substring(2, 4))
            drawMoveArrow(canvas, from, to, color, cell)
            drawScore(canvas, to, line.scoreLabel(), color, cell)
        }
    }

    private fun drawMoveArrow(canvas: Canvas, from: RectF, to: RectF, color: Int, cell: Float) {
        val sx = from.centerX()
        val sy = from.centerY()
        val tx = to.centerX()
        val ty = to.centerY()
        val angle = atan2(ty - sy, tx - sx)
        val startInset = cell * 0.10f
        val headLength = cell * 0.30f
        val headWidth = cell * 0.20f

        val startX = sx + cos(angle) * startInset
        val startY = sy + sin(angle) * startInset
        val shaftEndX = tx - cos(angle) * headLength * 0.72f
        val shaftEndY = ty - sin(angle) * headLength * 0.72f

        arrow.color = Color.argb(235, Color.red(color), Color.green(color), Color.blue(color))
        arrow.strokeWidth = cell * 0.13f
        canvas.drawLine(startX, startY, shaftEndX, shaftEndY, arrow)

        val baseX = tx - cos(angle) * headLength
        val baseY = ty - sin(angle) * headLength
        val px = -sin(angle)
        val py = cos(angle)
        val path = Path().apply {
            moveTo(tx, ty)
            lineTo(baseX + px * headWidth, baseY + py * headWidth)
            lineTo(baseX - px * headWidth, baseY - py * headWidth)
            close()
        }
        arrowHead.color = Color.argb(245, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawPath(path, arrowHead)
    }

    private fun drawScore(canvas: Canvas, to: RectF, label: String, color: Int, cell: Float) {
        val boxW = cell * 0.68f
        val boxH = cell * 0.32f
        val left = (to.centerX() - boxW / 2f).coerceIn(boardRect.left, boardRect.right - boxW)
        val top = (to.bottom - boxH - cell * 0.05f).coerceIn(boardRect.top, boardRect.bottom - boxH)
        val box = RectF(left, top, left + boxW, top + boxH)
        scoreFill.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawRoundRect(box, cell * 0.07f, cell * 0.07f, scoreFill)

        text.textSize = cell * 0.21f
        text.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        val cy = box.centerY() - (text.ascent() + text.descent()) / 2f
        canvas.drawText(label, box.centerX(), cy, text)
    }

    private fun squareRect(square: String): RectF {
        val file = square[0] - 'a'
        val rank = square[1] - '0'
        val col: Int
        val row: Int
        if (whiteAtBottom) {
            col = file
            row = 8 - rank
        } else {
            col = 7 - file
            row = rank - 1
        }
        val w = boardRect.width() / 8f
        val h = boardRect.height() / 8f
        return RectF(
            boardRect.left + col * w,
            boardRect.top + row * h,
            boardRect.left + (col + 1) * w,
            boardRect.top + (row + 1) * h
        )
    }
}
