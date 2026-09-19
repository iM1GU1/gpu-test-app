package com.example.berserkoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class VisionBoardReader(context: Context) : AutoCloseable {
    data class Detection(
        val pieces: Map<String, Char>,
        val boardRect: RectF,
        val whiteAtBottom: Boolean,
        val pieceCount: Int,
        val confidence: Float,
        val diagnostic: String
    )

    private val labels = charArrayOf('1','R','N','B','Q','K','P','r','n','b','q','k','p')
    private val interpreter: Interpreter

    init {
        val bytes = context.assets.open("chess_piece_classifier.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
    }

    fun detect(bitmap: Bitmap, preferred: RectF?): Detection? {
        val rect = preferred?.let { clampSquare(it, bitmap.width, bitmap.height) }
            ?: autoFindBoard(bitmap)
            ?: return null
        if (rect.width() < 160f || rect.height() < 160f) return null

        val left = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val topY = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val side = min(
            rect.width().toInt().coerceAtLeast(1),
            min(bitmap.width - left, bitmap.height - topY)
        )
        if (side < 160) return null

        val boardBmp = Bitmap.createBitmap(bitmap, left, topY, side, side)
        val scaled = Bitmap.createScaledBitmap(boardBmp, 256, 256, true)
        if (boardBmp !== scaled) boardBmp.recycle()

        val raw = Array(64) { FloatArray(labels.size) }
        var totalConfidence = 0f

        for (row in 0..7) for (col in 0..7) {
            val idx = row * 8 + col
            val input = ByteBuffer.allocateDirect(32 * 32 * 4).order(ByteOrder.nativeOrder())
            for (y in 0 until 32) for (x in 0 until 32) {
                val c = scaled.getPixel(col * 32 + x, row * 32 + y)
                val gray = (0.2989f * Color.red(c) + 0.5870f * Color.green(c) + 0.1140f * Color.blue(c)) / 255f
                input.putFloat(gray)
            }
            input.rewind()
            val output = Array(1) { FloatArray(labels.size) }
            interpreter.run(input, output)
            raw[idx] = output[0]
            totalConfidence += output[0].maxOrNull() ?: 0f
        }
        scaled.recycle()

        val top = IntArray(64)
        for (i in 0 until 64) {
            var best = 0
            for (j in 1 until labels.size) if (raw[i][j] > raw[i][best]) best = j
            top[i] = best
        }

        repairUniqueKing(top, raw, labels.indexOf('K'), true)
        repairUniqueKing(top, raw, labels.indexOf('k'), false)
        repairInitialSetup(top)

        val whiteAtBottom = inferOrientation(top)
        val pieces = linkedMapOf<String, Char>()
        for (row in 0..7) for (col in 0..7) {
            val label = labels[top[row * 8 + col]]
            if (label == '1') continue
            pieces[screenCellToSquare(row, col, whiteAtBottom)] = label
        }

        val problem = validate(pieces)
        val confidence = totalConfidence / 64f
        val diagnostic = if (problem != null) {
            "Visual: $problem · conf ${(confidence * 100).toInt()}%"
        } else {
            "Visual ✓ · ${pieces.size} piezas · conf ${(confidence * 100).toInt()}%"
        }

        return Detection(
            pieces, rect, whiteAtBottom, pieces.size, confidence, diagnostic
        )
    }

    private fun repairUniqueKing(top: IntArray, raw: Array<FloatArray>, kingIndex: Int, white: Boolean) {
        val current = top.indices.filter { top[it] == kingIndex }
        if (current.size == 1) return

        // World Chess/FIDE Arena uses outlined white pieces.  The old model often
        // labels the white king as another white piece with high top-1 confidence.
        // Recover a missing/duplicate king from the king probability, colour and
        // expected home-side geometry, but keep final position validation strict.
        var bestSquare = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in 0 until 64) {
            val row = i / 8
            val predicted = labels[top[i]]
            val sameColour = if (white) predicted in "RNBQKP" else predicted in "rnbqkp"
            val kingP = raw[i][kingIndex]
            val homeBonus = if (white) row / 7f else (7 - row) / 7f
            val colourBonus = if (sameColour) 0.16f else 0f
            val score = kingP + 0.10f * homeBonus + colourBonus
            if (score > bestScore) {
                bestScore = score
                bestSquare = i
            }
        }
        if (bestSquare < 0) return

        for (i in current) {
            if (i == bestSquare) continue
            var alt = if (kingIndex == 0) 1 else 0
            for (j in labels.indices) {
                if (j != kingIndex && raw[i][j] > raw[i][alt]) alt = j
            }
            top[i] = alt
        }
        top[bestSquare] = kingIndex
    }

    private fun repairInitialSetup(top: IntArray) {
        // FIDE/World Chess outlined pieces are visually quite different from the
        // model's training set.  At the normal starting position the occupancy
        // pattern itself is unambiguous, so use it as a safe bootstrap instead
        // of trusting a wrong top-1 piece label.
        fun occupied(row: Int): Int = (0..7).count { labels[top[row * 8 + it]] != '1' }
        val middleOccupied = (2..5).sumOf { occupied(it) }
        if (occupied(0) < 7 || occupied(1) < 7 || occupied(6) < 7 || occupied(7) < 7 || middleOccupied > 1) return

        val topWhite = (0..15).count { labels[top[it]] in "RNBQKP" }
        val topBlack = (0..15).count { labels[top[it]] in "rnbqkp" }
        val bottomWhite = (48..63).count { labels[top[it]] in "RNBQKP" }
        val bottomBlack = (48..63).count { labels[top[it]] in "rnbqkp" }

        if (topBlack >= topWhite && bottomWhite >= bottomBlack) {
            val backBlack = "rnbqkbnr"
            val backWhite = "RNBQKBNR"
            for (col in 0..7) {
                top[col] = labels.indexOf(backBlack[col])
                top[8 + col] = labels.indexOf('p')
                top[48 + col] = labels.indexOf('P')
                top[56 + col] = labels.indexOf(backWhite[col])
            }
        } else if (topWhite > topBlack && bottomBlack > bottomWhite) {
            val backWhite = "RNBKQBNR"
            val backBlack = "rnbkqbnr"
            for (col in 0..7) {
                top[col] = labels.indexOf(backWhite[col])
                top[8 + col] = labels.indexOf('P')
                top[48 + col] = labels.indexOf('p')
                top[56 + col] = labels.indexOf(backBlack[col])
            }
        }
    }

    private fun inferOrientation(top: IntArray): Boolean {
        var whiteRows = 0.0
        var whiteN = 0
        var blackRows = 0.0
        var blackN = 0
        for (i in top.indices) {
            val c = labels[top[i]]
            val row = i / 8
            if (c in "RNBQKP") { whiteRows += row; whiteN++ }
            if (c in "rnbqkp") { blackRows += row; blackN++ }
        }
        if (whiteN == 0 || blackN == 0) return true
        return (whiteRows / whiteN) > (blackRows / blackN)
    }

    fun isValid(detection: Detection): Boolean = validate(detection.pieces) == null

    private fun validate(pieces: Map<String, Char>): String? {
        val v = pieces.values
        val wk = v.count { it == 'K' }
        val bk = v.count { it == 'k' }
        if (wk != 1 || bk != 1) return "reyes $wk/$bk"
        val white = v.count { it in "RNBQKP" }
        val black = v.count { it in "rnbqkp" }
        if (white !in 1..16 || black !in 1..16) return "piezas blancas/negras $white/$black"
        val wp = v.count { it == 'P' }
        val bp = v.count { it == 'p' }
        if (wp > 8 || bp > 8) return "demasiados peones $wp/$bp"
        if (pieces.size !in 2..32) return "${pieces.size} piezas"
        return null
    }

    fun autoFindBoard(bitmap: Bitmap): RectF? {
        val w = bitmap.width
        val h = bitmap.height
        val minDim = min(w, h)
        var bestRect: RectF? = null
        var bestScore = 0.0

        val sizes = listOf(0.96f, 0.90f, 0.84f, 0.78f, 0.70f, 0.62f, 0.54f)
            .map { (minDim * it).toInt() }
            .filter { it >= 240 }

        for (size in sizes) {
            val cell = size / 8f
            val xCandidates = linkedSetOf(
                ((w - size) / 2).coerceAtLeast(0),
                0,
                (w - size).coerceAtLeast(0)
            )
            val yStep = max(18, (cell * 0.55f).toInt())
            val maxY = (h - size).coerceAtLeast(0)
            var y = 0
            while (y <= maxY) {
                for (x in xCandidates) {
                    val rect = RectF(x.toFloat(), y.toFloat(), (x + size).toFloat(), (y + size).toFloat())
                    val score = checkerScore(bitmap, rect)
                    if (score > bestScore) {
                        bestScore = score
                        bestRect = rect
                    }
                }
                y += yStep
            }
        }
        return if (bestScore >= 1.55) bestRect else null
    }

    private fun checkerScore(bitmap: Bitmap, rect: RectF): Double {
        val cell = rect.width() / 8f
        val a = ArrayList<FloatArray>(32)
        val b = ArrayList<FloatArray>(32)
        for (row in 0..7) for (col in 0..7) {
            val x = (rect.left + (col + 0.13f) * cell).toInt().coerceIn(0, bitmap.width - 1)
            val y = (rect.top + (row + 0.13f) * cell).toInt().coerceIn(0, bitmap.height - 1)
            val c = bitmap.getPixel(x, y)
            val rgb = floatArrayOf(Color.red(c).toFloat(), Color.green(c).toFloat(), Color.blue(c).toFloat())
            if ((row + col) % 2 == 0) a += rgb else b += rgb
        }
        val ma = mean(a)
        val mb = mean(b)
        val dist = colorDistance(ma, mb)
        if (dist < 28.0) return 0.0
        val va = spread(a, ma)
        val vb = spread(b, mb)
        return dist / (10.0 + va + vb)
    }

    private fun mean(v: List<FloatArray>): FloatArray {
        val m = FloatArray(3)
        for (p in v) for (i in 0..2) m[i] += p[i]
        for (i in 0..2) m[i] /= max(1, v.size)
        return m
    }

    private fun spread(v: List<FloatArray>, m: FloatArray): Double {
        if (v.isEmpty()) return 999.0
        var s = 0.0
        for (p in v) s += colorDistance(p, m)
        return s / v.size
    }

    private fun colorDistance(a: FloatArray, b: FloatArray): Double {
        val dr = a[0] - b[0]
        val dg = a[1] - b[1]
        val db = a[2] - b[2]
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun clampSquare(r: RectF, w: Int, h: Int): RectF {
        val size = min(r.width(), r.height()).coerceAtLeast(1f)
        val left = r.left.coerceIn(0f, max(0f, w - size))
        val top = r.top.coerceIn(0f, max(0f, h - size))
        return RectF(left, top, left + size, top + size)
    }

    private fun screenCellToSquare(row: Int, col: Int, whiteAtBottom: Boolean): String {
        val fileIndex: Int
        val rank: Int
        if (whiteAtBottom) {
            fileIndex = col
            rank = 8 - row
        } else {
            fileIndex = 7 - col
            rank = row + 1
        }
        return "${('a'.code + fileIndex).toChar()}$rank"
    }

    override fun close() {
        interpreter.close()
    }
}
