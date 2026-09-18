package com.example.berserkoverlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var resultView: BoardOverlayView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private var frameView: BoardFrameView? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var frameParams: WindowManager.LayoutParams
    private lateinit var recognizer: BoardRecognizer
    private lateinit var engine: BerserkEngine
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        recognizer = BoardRecognizer(this)
        engine = BerserkEngine(this)
        prepareFrameParams()

        runCatching {
            addResultOverlay()
            addControlPanel()
        }.onFailure {
            runCatching { if (::panel.isInitialized) wm.removeView(panel) }
            runCatching { if (::resultView.isInitialized) wm.removeView(resultView) }
            stopSelf()
        }
    }

    private fun addResultOverlay() {
        resultView = BoardOverlayView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(resultView, p)
        updateResultGeometry()
    }

    private fun addControlPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.argb(225, 25, 25, 25))
        }

        val title = TextView(this).apply {
            text = "☰  chess22k · 4 CPU"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(8, 8, 8, 8)
        }

        status = TextView(this).apply {
            text = if (recognizer.isCalibrated()) "Listo · calibración v4" else "Calibración necesaria"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(8, 4, 8, 8)
        }

        val adjust = button("Ajustar tablero") { showFrame() }
        val save = button("Guardar inicio") { saveCalibration() }
        val analyseWhite = button("Calcular BLANCAS") { analysePosition(whiteToMove = true) }
        val analyseBlack = button("Calcular NEGRAS") { analysePosition(whiteToMove = false) }
        val close = button("Cerrar") { stopSelf() }

        panel.addView(title)
        panel.addView(status)
        panel.addView(adjust)
        panel.addView(save)
        panel.addView(analyseWhite)
        panel.addView(analyseBlack)
        panel.addView(close)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 120
        }
        wm.addView(panel, panelParams)

        var lastX = 0f
        var lastY = 0f
        title.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x += (e.rawX - lastX).toInt()
                    panelParams.y += (e.rawY - lastY).toInt()
                    lastX = e.rawX
                    lastY = e.rawY
                    wm.updateViewLayout(panel, panelParams)
                    true
                }
                else -> true
            }
        }
    }

    private fun prepareFrameParams() {
        val metrics = resources.displayMetrics
        val savedSize = prefs.getInt("board_size", (metrics.widthPixels * 0.9f).toInt())
        frameParams = WindowManager.LayoutParams(
            savedSize,
            savedSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("board_x", ((metrics.widthPixels - savedSize) / 2).coerceAtLeast(0))
            y = prefs.getInt("board_y", ((metrics.heightPixels - savedSize) / 2).coerceAtLeast(0))
        }
    }

    private fun showFrame() {
        if (frameView != null) return
        resultView.setAnalysis(emptyList())
        frameView = BoardFrameView(this, wm, frameParams) { updateResultGeometry() }
        wm.addView(frameView, frameParams)
        status.text = "Marco: ajusta exactamente las 64 casillas"
    }

    private fun hideFrame() {
        frameView?.let { runCatching { wm.removeView(it) } }
        frameView = null
    }

    private fun setCaptureOverlaysVisible(visible: Boolean) {
        resultView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        panel.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun withFreshScreenshot(onReady: (android.graphics.Bitmap) -> Unit) {
        val previousGeneration = ProjectionStore.generation()
        setCaptureOverlaysVisible(false)
        waitForFreshFrame(previousGeneration, 0, onReady)
    }

    private fun waitForFreshFrame(previousGeneration: Long, attempt: Int, onReady: (android.graphics.Bitmap) -> Unit) {
        if (ProjectionStore.generation() > previousGeneration) {
            val bitmap = ProjectionStore.copyLatest()
            if (bitmap != null) {
                onReady(bitmap)
                return
            }
        }
        if (attempt >= 12) {
            setCaptureOverlaysVisible(true)
            status.text = "Sin captura nueva. Vuelve a autorizar Captura de pantalla."
            return
        }
        main.postDelayed({ waitForFreshFrame(previousGeneration, attempt + 1, onReady) }, 120)
    }

    private fun saveCalibration() {
        if (frameView == null) {
            status.text = "Pulsa Ajustar tablero primero"
            return
        }

        persistBoardRect()
        hideFrame()
        status.text = "Calibrando desde posición inicial…"

        withFreshScreenshot { bitmap ->
            val rect = currentBoardRect()
            val whiteAtBottom = prefs.getBoolean("white_side", true)
            worker.execute {
                runCatching { recognizer.calibrate(bitmap, rect, whiteAtBottom) }
                    .onSuccess {
                        main.post {
                            setCaptureOverlaysVisible(true)
                            status.text = "Calibrado ✓ · ahora abre la partida y calcula"
                        }
                    }
                    .onFailure { e ->
                        main.post {
                            setCaptureOverlaysVisible(true)
                            status.text = "Error calibrando: ${e.message}"
                        }
                    }
                bitmap.recycle()
            }
        }
    }

    private fun analysePosition(whiteToMove: Boolean) {
        if (!recognizer.isCalibrated()) {
            status.text = "Falta calibrar: usa una posición inicial completa"
            return
        }

        hideFrame()
        persistBoardRect()
        resultView.setAnalysis(emptyList())
        status.text = if (whiteToMove) "Leyendo tablero para BLANCAS…" else "Leyendo tablero para NEGRAS…"

        withFreshScreenshot { bitmap ->
            val board = currentBoardRect()
            val whiteAtBottom = prefs.getBoolean("white_side", true)
            val multipv = prefs.getInt("multipv", 1).coerceIn(1, 3)

            worker.execute {
                runCatching {
                    val detection = recognizer.detectDetailed(bitmap, board, whiteAtBottom)
                    val problem = recognizer.validate(detection)
                    if (problem != null) {
                        error("Detección no válida: $problem. Ajusta el marco y recalibra desde la posición inicial.")
                    }
                    val fen = ChessFen.fromPieces(detection.pieces, whiteToMove = whiteToMove)
                    val lines = engine.analyse(fen, multipv)
                    detection to lines
                }.onSuccess { (detection, lines) ->
                    main.post {
                        updateResultGeometry()
                        resultView.setAnalysis(lines)
                        setCaptureOverlaysVisible(true)
                        val side = if (whiteToMove) "B" else "N"
                        val conf = (detection.confidence * 100).toInt()
                        status.text = "$side · ${detection.pieceCount} piezas · conf $conf% · " +
                            lines.joinToString("  ") { "${it.rank}:${it.move} ${it.scoreLabel()}" }
                    }
                }.onFailure { e ->
                    main.post {
                        setCaptureOverlaysVisible(true)
                        status.text = e.message ?: "No se pudo analizar la posición"
                    }
                }
                bitmap.recycle()
            }
        }
    }

    private fun updateResultGeometry() {
        resultView.boardRect = currentBoardRect()
        resultView.whiteAtBottom = prefs.getBoolean("white_side", true)
        resultView.invalidate()
    }

    private fun currentBoardRect(): RectF = RectF(
        frameParams.x.toFloat(),
        frameParams.y.toFloat(),
        (frameParams.x + frameParams.width).toFloat(),
        (frameParams.y + frameParams.height).toFloat()
    )

    private fun persistBoardRect() {
        prefs.edit()
            .putInt("board_x", frameParams.x)
            .putInt("board_y", frameParams.y)
            .putInt("board_size", frameParams.width)
            .apply()
        updateResultGeometry()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    override fun onDestroy() {
        hideFrame()
        if (::panel.isInitialized) runCatching { wm.removeView(panel) }
        if (::resultView.isInitialized) runCatching { wm.removeView(resultView) }
        worker.shutdownNow()
        engine.close()
        super.onDestroy()
    }
}
