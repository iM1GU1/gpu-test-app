package com.example.berserkoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class BoardRecognizer(private val context: Context) {
    data class Detection(
        val pieces: Map<String, Char>,
        val pieceCount: Int,
        val confidence: Float
    )

    private val sampleSize = 18
    private val channels = 2
    private val featureLen = sampleSize * sampleSize * channels
    private val templateFile = File(context.filesDir, "board_templates_v2.bin")
    private val templates = mutableMapOf<Char, MutableList<FloatArray>>()
    private var occupancyThreshold = 0.07f

    init { load() }

    fun isCalibrated(): Boolean = templates.isNotEmpty()

    fun calibrate(bitmap: Bitmap, board: RectF, whiteAtBottom: Boolean) {
        validateBoardRect(bitmap, board)
        templates.clear()
        val emptyEnergies = mutableListOf<Float>()
        val occupiedEnergies = mutableListOf<Float>()

        for (row in 0..7) for (col in 0..7) {
            val square = screenCellToSquare(row, col, whiteAtBottom)
            val label = initialPieceAt(square)
            val feature = extractFeature(bitmap, board, row, col)
            templates.getOrPut(label) { mutableListOf() }.add(feature)
            if (label == '.') emptyEnergies += energy(feature) else occupiedEnergies += energy(feature)
        }

        val emptyHi = percentile(emptyEnergies, 0.90f)
        val occupiedLo = percentile(occupiedEnergies, 0.10f)
        val emptyMedian = percentile(emptyEnergies, 0.50f)
        val occupiedMedian = percentile(occupiedEnergies, 0.50f)
        occupancyThreshold = if (occupiedLo > emptyHi) {
            (emptyHi + occupiedLo) / 2f
        } else {
            ((emptyMedian + occupiedMedian) / 2f).coerceAtLeast(emptyMedian * 1.15f)
        }.coerceIn(0.018f, 0.35f)
        save()
    }

    fun detectDetailed(bitmap: Bitmap, board: RectF, whiteAtBottom: Boolean): Detection {
        check(isCalibrated()) { "Primero calibra el tablero en la posición inicial." }
        validateBoardRect(bitmap, board)

        val out = linkedMapOf<String, Char>()
        var confidenceSum = 0.0
        var classified = 0

        for (row in 0..7) for (col in 0..7) {
            val feature = extractFeature(bitmap, board, row, col)
            val e = energy(feature)
            val square = screenCellToSquare(row, col, whiteAtBottom)
            if (e < occupancyThreshold) {
                out[square] = '.'
                continue
            }

            var bestLabel = '.'
            var best = Double.POSITIVE_INFINITY
            var second = Double.POSITIVE_INFINITY
            for ((label, samples) in templates) {
                if (label == '.') continue
                var labelBest = Double.POSITIVE_INFINITY
                for (sample in samples) {
                    var mse = 0.0
                    for (i in 0 until featureLen) {
                        val d = feature[i] - sample[i]
                        mse += d * d
                    }
                    mse /= featureLen
                    if (mse < labelBest) labelBest = mse
                }
                if (labelBest < best) {
                    second = best
                    best = labelBest
                    bestLabel = label
                } else if (labelBest < second) {
                    second = labelBest
                }
            }

            out[square] = bestLabel
            if (bestLabel != '.') {
                val c = if (second.isFinite() && second > 1e-9) {
                    (1.0 - best / second).coerceIn(0.0, 1.0)
                } else 0.0
                confidenceSum += c
                classified++
            }
        }

        val count = out.values.count { it != '.' }
        val confidence = if (classified == 0) 0f else (confidenceSum / classified).toFloat()
        return Detection(out, count, confidence)
    }

    fun validate(detection: Detection): String? {
        val values = detection.pieces.values
        val whiteKing = values.count { it == 'K' }
        val blackKing = values.count { it == 'k' }
        if (whiteKing != 1 || blackKing != 1) return "reyes detectados: blancas=$whiteKing, negras=$blackKing"

        val white = values.count { it in "PNBRQK" }
        val black = values.count { it in "pnbrqk" }
        if (white !in 1..16 || black !in 1..16) return "piezas por color: blancas=$white, negras=$black"
        val wp = values.count { it == 'P' }
        val bp = values.count { it == 'p' }
        if (wp > 8 || bp > 8) return "demasiados peones detectados ($wp/$bp)"
        if (detection.pieceCount !in 2..32) return "${detection.pieceCount} piezas detectadas"
        if (detection.confidence < 0.035f) return "confianza baja (${(detection.confidence * 100).toInt()}%)"
        return null
    }

    private fun validateBoardRect(bitmap: Bitmap, board: RectF) {
        require(board.width() >= 160f && board.height() >= 160f) { "El marco del tablero es demasiado pequeño." }
        require(board.left >= 0f && board.top >= 0f && board.right <= bitmap.width && board.bottom <= bitmap.height) {
            "El marco se sale de la captura. Vuelve a ajustarlo exactamente a las 64 casillas."
        }
    }

    private fun extractFeature(bitmap: Bitmap, board: RectF, row: Int, col: Int): FloatArray {
        val cellW = board.width() / 8f
        val cellH = board.height() / 8f
        val left = board.left + col * cellW
        val top = board.top + row * cellH

        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        val points = arrayOf(
            0.06f to 0.06f, 0.94f to 0.06f, 0.06f to 0.94f, 0.94f to 0.94f,
            0.10f to 0.06f, 0.90f to 0.06f, 0.10f to 0.94f, 0.90f to 0.94f
        )
        for ((fx, fy) in points) {
            val x = (left + fx * cellW).toInt().coerceIn(0, bitmap.width - 1)
            val y = (top + fy * cellH).toInt().coerceIn(0, bitmap.height - 1)
            val c = bitmap.getPixel(x, y)
            sr += (c shr 16) and 255
            sg += (c shr 8) and 255
            sb += c and 255
            n++
        }
        val br = sr.toFloat() / max(1, n)
        val bg = sg.toFloat() / max(1, n)
        val bb = sb.toFloat() / max(1, n)

        val f = FloatArray(featureLen)
        var k = 0
        for (sy in 0 until sampleSize) for (sx in 0 until sampleSize) {
            val fx = 0.09f + 0.82f * (sx + 0.5f) / sampleSize
            val fy = 0.09f + 0.82f * (sy + 0.5f) / sampleSize
            val x = (left + fx * cellW).toInt().coerceIn(0, bitmap.width - 1)
            val y = (top + fy * cellH).toInt().coerceIn(0, bitmap.height - 1)
            val c = bitmap.getPixel(x, y)
            val dr = (((c shr 16) and 255) - br) / 255f
            val dg = (((c shr 8) and 255) - bg) / 255f
            val db = ((c and 255) - bb) / 255f
            val luminance = 0.299f * dr + 0.587f * dg + 0.114f * db
            val magnitude = sqrt(dr * dr + dg * dg + db * db)
            f[k++] = luminance
            f[k++] = magnitude
        }
        return f
    }

    private fun energy(feature: FloatArray): Float {
        var total = 0f
        var i = 0
        while (i < feature.size) {
            total += abs(feature[i]) + feature[i + 1]
            i += 2
        }
        return total / feature.size
    }

    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun initialPieceAt(square: String): Char {
        val file = square[0] - 'a'
        val rank = square[1] - '0'
        return when (rank) {
            1 -> "RNBQKBNR"[file]
            2 -> 'P'
            7 -> 'p'
            8 -> "rnbqkbnr"[file]
            else -> '.'
        }
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

    private fun save() {
        DataOutputStream(templateFile.outputStream().buffered()).use { out ->
            out.writeInt(0x43483232)
            out.writeInt(sampleSize)
            out.writeInt(channels)
            out.writeFloat(occupancyThreshold)
            out.writeInt(templates.size)
            for ((label, samples) in templates) {
                out.writeChar(label.code)
                out.writeInt(samples.size)
                for (sample in samples) for (v in sample) out.writeFloat(v)
            }
        }
    }

    private fun load() {
        if (!templateFile.exists()) return
        runCatching {
            DataInputStream(templateFile.inputStream().buffered()).use { input ->
                if (input.readInt() != 0x43483232) return@use
                if (input.readInt() != sampleSize) return@use
                if (input.readInt() != channels) return@use
                occupancyThreshold = input.readFloat()
                val labels = input.readInt()
                repeat(labels) {
                    val label = input.readChar()
                    val count = input.readInt()
                    val list = mutableListOf<FloatArray>()
                    repeat(count) {
                        val arr = FloatArray(featureLen)
                        for (i in arr.indices) arr[i] = input.readFloat()
                        list.add(arr)
                    }
                    templates[label] = list
                }
            }
        }.onFailure { templates.clear() }
    }
}
