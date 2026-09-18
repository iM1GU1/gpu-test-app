package com.example.berserkoverlay

import kotlin.math.abs
import kotlin.random.Random

enum class HumanProfile(val prefValue: String, val label: String) {
    PRECISE("precise", "Preciso"),
    UNIVERSAL("universal", "Universal elite"),
    POSITIONAL("positional", "Posicional elite"),
    TACTICAL("tactical", "Táctico elite");

    companion object {
        fun fromPref(value: String?): HumanProfile =
            entries.firstOrNull { it.prefValue == value } ?: UNIVERSAL
    }
}

object HumanMoveSelector {
    fun choose(
        rawLines: List<AnalysisLine>,
        snapshot: AccessibilityBoardStore.Snapshot,
        whiteToMove: Boolean,
        profile: HumanProfile
    ): AnalysisLine? {
        if (rawLines.isEmpty()) return null

        val lines = rawLines.sortedByDescending { evaluation(it) }.take(3)
        val best = lines.first()
        if (profile == HumanProfile.PRECISE || lines.size == 1) return best

        // Una táctica/mate forzado no se degrada artificialmente.
        if (best.mateIn != null) return best

        val bestEval = evaluation(best)
        val weights = lines.mapIndexed { index, line ->
            if (index == 0) {
                1.0 * styleMultiplier(line, snapshot, whiteToMove, profile)
            } else {
                val gap = bestEval - evaluation(line)
                val base = when {
                    gap <= 10.0 -> when (index) { 1 -> 0.72; else -> 0.48 }
                    gap <= 25.0 -> when (index) { 1 -> 0.34; else -> 0.14 }
                    gap <= 50.0 -> when (index) { 1 -> 0.075; else -> 0.02 }
                    else -> 0.0
                }
                base * styleMultiplier(line, snapshot, whiteToMove, profile)
            }
        }

        // Si la mejor es claramente superior, el bot no "fabrica" un error.
        if (weights.drop(1).all { it <= 0.0 }) return best

        val total = weights.sum()
        if (total <= 0.0) return best
        var pick = Random.nextDouble(total)
        for (i in lines.indices) {
            pick -= weights[i]
            if (pick <= 0.0) return lines[i]
        }
        return best
    }

    private fun evaluation(line: AnalysisLine): Double {
        line.mateIn?.let { mate ->
            return if (mate >= 0) 100_000.0 - mate.coerceAtLeast(0)
            else -100_000.0 + abs(mate).toDouble()
        }
        return (line.scoreCp ?: -100_000).toDouble()
    }

    private fun styleMultiplier(
        line: AnalysisLine,
        snapshot: AccessibilityBoardStore.Snapshot,
        whiteToMove: Boolean,
        profile: HumanProfile
    ): Double {
        if (profile == HumanProfile.UNIVERSAL) return 1.0
        if (line.move.length < 4) return 1.0

        val from = line.move.substring(0, 2)
        val to = line.move.substring(2, 4)
        val moving = snapshot.pieces[from]
        val target = snapshot.pieces[to]
        val capture = target != null &&
            (if (whiteToMove) target.isLowerCase() else target.isUpperCase())
        val promotion = line.move.length >= 5
        val castle = (moving == 'K' || moving == 'k') &&
            ((from == "e1" && (to == "g1" || to == "c1")) ||
             (from == "e8" && (to == "g8" || to == "c8")))
        val development = when (moving) {
            'N' -> from == "b1" || from == "g1"
            'B' -> from == "c1" || from == "f1"
            'n' -> from == "b8" || from == "g8"
            'b' -> from == "c8" || from == "f8"
            else -> false
        }

        return when (profile) {
            HumanProfile.POSITIONAL -> when {
                castle -> 1.55
                development -> 1.22
                capture -> 0.82
                promotion -> 0.9
                else -> 1.08
            }
            HumanProfile.TACTICAL -> when {
                promotion -> 1.75
                capture -> 1.48
                castle -> 0.92
                development -> 1.05
                else -> 0.88
            }
            else -> 1.0
        }
    }
}
