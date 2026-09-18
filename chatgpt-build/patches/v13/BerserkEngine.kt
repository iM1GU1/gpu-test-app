package com.example.berserkoverlay

import nl.s22k.chess.ChessBoardInstances
import nl.s22k.chess.ChessBoardUtil
import nl.s22k.chess.Util
import nl.s22k.chess.engine.EngineConstants
import nl.s22k.chess.engine.MainEngine
import nl.s22k.chess.engine.UciOptions
import nl.s22k.chess.engine.UciOut
import nl.s22k.chess.move.MoveWrapper
import nl.s22k.chess.search.NegamaxUtil
import nl.s22k.chess.search.SearchUtil
import nl.s22k.chess.search.TTUtil
import nl.s22k.chess.search.ThreadData
import nl.s22k.chess.search.TimeUtil
import kotlin.math.abs

/**
 * In-process chess22k 1.14 adapter.
 *
 * v11 keeps the primary transposition table warm between consecutive positions.
 * That is safe because TT entries are keyed by the full Zobrist position key and
 * makes the first recommendation much faster after each move.
 */
class BerserkEngine(@Suppress("UNUSED_PARAMETER") context: android.content.Context) : AutoCloseable {
    companion object {
        private val engineLock = Any()
        @Volatile private var initialized = false

        private fun ensureInitialized() = synchronized(engineLock) {
            if (initialized) return@synchronized
            UciOut.noOutput = true
            UciOptions.setPonder(false)
            UciOptions.setThreadCount(4)
            TTUtil.setSizeMB(64)
            TTUtil.init(false)
            initialized = true
        }
    }

    init { ensureInitialized() }

    fun warmUp() {
        runCatching {
            analyse(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                1,
                45
            )
        }
    }

    fun analyse(fen: String, multiPv: Int, moveTimeMs: Int = 140): List<AnalysisLine> = synchronized(engineLock) {
        ensureInitialized()
        val wanted = multiPv.coerceIn(1, 3)
        val board = ChessBoardInstances.get(0)
        val threadData = ThreadData.getInstance(0)
        val excluded = ArrayList<Int>(wanted)
        val out = ArrayList<AnalysisLine>(wanted)

        threadData.clearHistoryHeuristics()

        try {
            repeat(wanted) { idx ->
                ChessBoardUtil.setFen(fen, board)
                threadData.clearCaches()
                TTUtil.init(false)

                // Keep TT warm for the primary line so related positions profit from
                // the previous search. Alternative root lines use a clean table to
                // avoid a stored primary move bypassing the root-exclusion hook.
                if (idx > 0) TTUtil.clearValues()

                NegamaxUtil.setRootExcludedMoves(excluded.toIntArray())
                MainEngine.pondering = false
                MainEngine.maxDepth = EngineConstants.MAX_PLIES
                TimeUtil.reset()
                TimeUtil.setMoveCount(board.moveCounter)
                TimeUtil.setSimpleTimeWindow(moveTimeMs.toLong() * 2L)
                SearchUtil.start(board)

                val move = threadData.bestMove
                if (move == 0 || excluded.contains(move)) return@repeat
                val score = threadData.bestScore
                val mate = if (abs(score) >= 30000) {
                    val plies = (Util.SHORT_MAX - abs(score)).coerceAtLeast(0)
                    val moves = (plies + 1) / 2
                    if (score >= 0) moves else -moves
                } else null

                out += if (mate != null) {
                    AnalysisLine(idx + 1, MoveWrapper(move).toString(), mateIn = mate)
                } else {
                    AnalysisLine(idx + 1, MoveWrapper(move).toString(), scoreCp = score)
                }
                excluded += move
            }
        } finally {
            NegamaxUtil.setRootExcludedMoves(IntArray(0))
            NegamaxUtil.isRunning = false
        }

        if (out.isEmpty()) error("chess22k no encontró una jugada legal para la posición detectada.")
        out
    }

    fun abortSearch() {
        MainEngine.pondering = false
        NegamaxUtil.isRunning = false
    }

    fun resetState() = synchronized(engineLock) {
        abortSearch()
        runCatching { ThreadData.getInstance(0).clearCaches() }
        runCatching { ThreadData.getInstance(0).clearHistoryHeuristics() }
        runCatching { TTUtil.clearValues() }
        NegamaxUtil.setRootExcludedMoves(IntArray(0))
        NegamaxUtil.isRunning = false
    }

    override fun close() {
        abortSearch()
    }
}
