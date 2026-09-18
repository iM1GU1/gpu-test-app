package com.example.berserkoverlay

import android.graphics.RectF
import android.os.SystemClock

object AccessibilityBoardStore {
    data class Snapshot(
        val pieces: Map<String, Char>,
        val boardRect: RectF,
        val whiteAtBottom: Boolean,
        val squareCount: Int,
        val pieceCount: Int,
        val packageName: String,
        val diagnostic: String
    )

    private val lock = Any()
    private var connected = false
    private var lastSnapshot: Snapshot? = null
    private var lastDiagnostic = "Servicio de Accesibilidad desactivado"
    private var generationValue = 0L
    private var updatedAtMs = 0L

    fun setConnected(value: Boolean) = synchronized(lock) {
        connected = value
        if (!value) {
            lastSnapshot = null
            lastDiagnostic = "Servicio de Accesibilidad desactivado"
            generationValue = 0L
            updatedAtMs = 0L
        }
    }

    fun isConnected(): Boolean = synchronized(lock) { connected }

    fun update(snapshot: Snapshot) = synchronized(lock) {
        val previous = lastSnapshot
        val changed = previous == null ||
            previous.pieces != snapshot.pieces ||
            previous.whiteAtBottom != snapshot.whiteAtBottom ||
            previous.packageName != snapshot.packageName

        lastSnapshot = snapshot.copy(
            pieces = LinkedHashMap(snapshot.pieces),
            boardRect = RectF(snapshot.boardRect)
        )
        if (changed) generationValue++
        updatedAtMs = SystemClock.elapsedRealtime()
        lastDiagnostic = snapshot.diagnostic
    }

    fun updateDiagnostic(message: String) = synchronized(lock) {
        lastDiagnostic = message
    }

    fun snapshot(): Snapshot? = synchronized(lock) {
        lastSnapshot?.let {
            it.copy(
                pieces = LinkedHashMap(it.pieces),
                boardRect = RectF(it.boardRect)
            )
        }
    }

    fun clearSnapshot(message: String = "Leyendo tablero…") = synchronized(lock) {
        lastSnapshot = null
        lastDiagnostic = message
        generationValue++
        updatedAtMs = 0L
    }

    fun diagnostic(): String = synchronized(lock) { lastDiagnostic }

    fun generation(): Long = synchronized(lock) { generationValue }

    fun ageMs(): Long = synchronized(lock) {
        if (updatedAtMs == 0L) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - updatedAtMs
    }
}
