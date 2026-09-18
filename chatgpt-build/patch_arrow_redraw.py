from pathlib import Path
import sys

overlay = Path(sys.argv[1])
renderer = Path(sys.argv[2])
gradle = Path(sys.argv[3])

s = overlay.read_text()
old = '''                    main.post {
                        resultView.boardRect = detection.boardRect
                        resultView.whiteAtBottom = detection.whiteAtBottom
                        resultView.setAnalysis(lines)
                        setCaptureOverlaysVisible(true)
                        busy = false
                        Toast.makeText(
                            this,
                            "IA: ${detection.pieceCount} piezas · ${(detection.confidence * 100).toInt()}%",
                            Toast.LENGTH_SHORT
                        ).show()
                    }'''
new = '''                    main.post {
                        // Make the drawing surface visible first. Android can discard
                        // invalidations issued while a View is INVISIBLE.
                        setCaptureOverlaysVisible(true)
                        resultView.boardRect = android.graphics.RectF(detection.boardRect)
                        resultView.whiteAtBottom = detection.whiteAtBottom
                        resultView.setAnalysis(lines)
                        resultView.postInvalidate()
                        busy = false
                        val bestMove = lines.firstOrNull()?.move ?: "?"
                        Toast.makeText(
                            this,
                            "Mejor: $bestMove · ${detection.pieceCount} piezas · ${(detection.confidence * 100).toInt()}%",
                            Toast.LENGTH_LONG
                        ).show()
                    }'''
if old not in s:
    raise SystemExit("analysis success block not found")
overlay.write_text(s.replace(old, new, 1))

r = renderer.read_text()
old2 = '''    fun setAnalysis(newLines: List<AnalysisLine>) {
        lines = newLines
        invalidate()
    }'''
new2 = '''    fun setAnalysis(newLines: List<AnalysisLine>) {
        lines = newLines.toList()
        postInvalidateOnAnimation()
    }'''
if old2 not in r:
    raise SystemExit("renderer setAnalysis block not found")
renderer.write_text(r.replace(old2, new2, 1))

g = gradle.read_text()
g = g.replace('versionCode = 6', 'versionCode = 7')
g = g.replace('versionName = "0.6.0"', 'versionName = "0.7.0"')
gradle.write_text(g)
print("arrow redraw patched")
