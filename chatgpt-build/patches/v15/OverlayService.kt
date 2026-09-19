package com.example.berserkoverlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var resultView: BoardOverlayView
    private lateinit var panel: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var statusParams: WindowManager.LayoutParams
    private lateinit var engine: BerserkEngine
    private lateinit var visionReader: VisionBoardReader
    private var selectorView: ManualBoardSelectorView? = null

    @Volatile private var worker = newWorker()
    private val visionWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val cacheLock = Any()
    private val primaryCache = LinkedHashMap<String, AnalysisLine>(16, 0.75f, true)
    private val humanChoiceCache = LinkedHashMap<String, AnalysisLine>(16, 0.75f, true)
    @Volatile private var destroyed = false
    @Volatile private var refineKeyInFlight: String? = null
    private var lastPrefetchGeneration = -1L
    private var requestToken = 0L
    private var statusHideToken = 0L
    private val searchSeq = AtomicLong(0L)
    @Volatile private var activeSearchId = 0L
    private var seenGeneration = -1L
    private var processedGeneration = -1L
    private var stableSinceMs = 0L
    private var lastStableSnapshot: AccessibilityBoardStore.Snapshot? = null
    @Volatile private var autoTurnWhite: Boolean? = null
    private var lastForcedScanAt = 0L
    private var lastVisionScanAt = 0L
    @Volatile private var visionScanInFlight = false
    private var lastVisualFailureShownAt = 0L
    private var visualFailureStreak = 0
    private var lastScreenshotAttemptAt = 0L
    private var screenshotRetryScheduled = false

    private fun newWorker() = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()
    )

    private val prefetchRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            val now = android.os.SystemClock.elapsedRealtime()
            val generation = AccessibilityBoardStore.generation()

            if (generation > 0L && generation != seenGeneration) {
                seenGeneration = generation
                stableSinceMs = now
                // Keep the previous arrow visible while the new position is stabilising/analyzing.
                // Clearing it here caused a visible blink on every board update.
                cancelPendingEngineWork(false)
            }

            if (generation > 0L &&
                generation != processedGeneration &&
                now - stableSinceMs >= 65L
            ) {
                processedGeneration = generation
                AccessibilityBoardStore.snapshot()?.let { snapshot ->
                    processStablePosition(snapshot, generation)
                }
            }

            if (AccessibilityBoardStore.ageMs() > 1200L &&
                now - lastForcedScanAt > 750L
            ) {
                lastForcedScanAt = now
                runCatching { ChessAccessibilityService.current()?.readBoardNow() }
            }

            val visualNeeded = prefs.getBoolean("vision_manual", false) ||
                AccessibilityBoardStore.snapshot() == null ||
                AccessibilityBoardStore.ageMs() > 1600L
            if (visualNeeded && now - lastVisionScanAt > 1250L && !visionScanInFlight) {
                lastVisionScanAt = now
                requestVisionScan(showErrors = false)
            }

            main.postDelayed(this, 35L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching {
            engine = BerserkEngine(this)
            visionReader = VisionBoardReader(this)
            addResultOverlay()
            addMiniPanel()
            addStatusBubble()
            main.post(prefetchRunnable)
            worker.execute { runCatching { engine.warmUp() } }
        }.onFailure {
            stopSelf()
        }
    }

    private fun addResultOverlay() {
        resultView = BoardOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(resultView, params)
    }

    private fun addMiniPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setBackgroundColor(Color.TRANSPARENT)
        }

        val white = roundButton("♔", Color.WHITE, Color.BLACK, Color.rgb(115, 115, 115), "Calcular blancas")
        val black = roundButton("♚", Color.BLACK, Color.WHITE, Color.rgb(220, 220, 220), "Calcular negras")
        val reset = roundButton("↻", Color.rgb(70, 70, 70), Color.WHITE, Color.rgb(190, 190, 190), "Resetear motor y tablero")
        val board = roundButton("▣", Color.rgb(38, 70, 92), Color.WHITE, Color.rgb(170, 220, 255), "Ajustar área del tablero")
        panel.addView(white)
        panel.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        panel.addView(black)
        panel.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        panel.addView(reset)
        panel.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        panel.addView(board)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("panel_x_v11", prefs.getInt("panel_x_v10", 18))
            y = prefs.getInt("panel_y_v11", prefs.getInt("panel_y_v10", 180))
        }
        wm.addView(panel, panelParams)
        attachTapAndDrag(white, true)
        attachTapAndDrag(black, false)
        reset.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            hardReset()
        }
        board.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showBoardSelector()
        }
        board.setOnLongClickListener {
            prefs.edit().putBoolean("vision_manual", false).apply()
            showStatus("AUTO visual activado · buscando tablero…", 1200)
            AccessibilityBoardStore.clearSnapshot("AUTO visual · buscando tablero…")
            requestVisionScan(showErrors = true)
            true
        }
    }

    private fun addStatusBubble() {
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            visibility = View.GONE
            maxLines = 4
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(235, 24, 24, 24))
                setStroke(dp(1), Color.argb(160, 255, 255, 255))
            }
        }
        statusParams = WindowManager.LayoutParams(
            dp(285),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelParams.x
            y = panelParams.y + dp(58)
        }
        wm.addView(statusView, statusParams)
    }

    private fun roundButton(text: String, fill: Int, textColor: Int, border: Int, description: String): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            gravity = Gravity.CENTER
            this.text = text
            setTextColor(textColor)
            textSize = 27f
            contentDescription = description
            isClickable = true
            isFocusable = false
            elevation = dp(7).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(dp(2), border)
            }
        }

    private fun attachTapAndDrag(view: View, whiteToMove: Boolean) {
        val dragThreshold = dp(22).toFloat()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            analysePosition(whiteToMove)
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = panelParams.x
                    startY = panelParams.y
                    dragging = false
                    v.alpha = 0.72f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) dragging = true
                    if (dragging) {
                        panelParams.x = startX + dx.toInt()
                        panelParams.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(panel, panelParams) }
                        if (::statusView.isInitialized) {
                            statusParams.x = panelParams.x
                            statusParams.y = panelParams.y + dp(58)
                            runCatching { wm.updateViewLayout(statusView, statusParams) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    if (dragging) {
                        prefs.edit()
                            .putInt("panel_x_v11", panelParams.x)
                            .putInt("panel_y_v11", panelParams.y)
                            .apply()
                    } else {
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> true
            }
        }
    }

    private fun savedBoardRect(): RectF? {
        if (!prefs.getBoolean("vision_manual", false)) return null
        val x = prefs.getFloat("vision_x", -1f)
        val y = prefs.getFloat("vision_y", -1f)
        val size = prefs.getFloat("vision_size", -1f)
        return if (x >= 0f && y >= 0f && size >= 160f) RectF(x, y, x + size, y + size) else null
    }

    private fun showBoardSelector() {
        if (selectorView != null) return
        val initial = savedBoardRect()
        panel.visibility = View.INVISIBLE
        statusView.visibility = View.GONE
        resultView.setAnalysis(emptyList())

        val selector = ManualBoardSelectorView(this, initial) { selected ->
            val current = selectorView
            if (current != null) runCatching { wm.removeView(current) }
            selectorView = null
            panel.visibility = View.VISIBLE

            if (selected != null) {
                val size = minOf(selected.width(), selected.height())
                prefs.edit()
                    .putBoolean("vision_manual", true)
                    .putFloat("vision_x", selected.left)
                    .putFloat("vision_y", selected.top)
                    .putFloat("vision_size", size)
                    .apply()
                AccessibilityBoardStore.clearSnapshot("Tablero fijado · leyendo visualmente…")
                showStatus("▣ Tablero fijado · leyendo…", 0)
                requestVisionScan(showErrors = true)
            } else {
                showStatus("Selección cancelada", 800)
            }
        }
        selectorView = selector
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(selector, params)
    }

    private fun setCaptureUiVisible(visible: Boolean) {
        if (::resultView.isInitialized) resultView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (::panel.isInitialized) panel.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (::statusView.isInitialized) statusView.visibility = if (visible && statusView.text.isNotEmpty()) View.VISIBLE else View.INVISIBLE
    }

    private fun requestVisionScan(showErrors: Boolean) {
        if (visionScanInFlight || destroyed || !::visionReader.isInitialized) return

        // AccessibilityService.takeScreenshot() is rate-limited by Android.
        // Error code 3 means the previous screenshot was taken too recently.
        // Keep a safety margin above one second so FIDE/World Chess can be
        // scanned continuously without falling into a permanent error-3 loop.
        val now = android.os.SystemClock.elapsedRealtime()
        val minInterval = 1150L
        val remaining = minInterval - (now - lastScreenshotAttemptAt)
        if (remaining > 0L) {
            if (!screenshotRetryScheduled) {
                screenshotRetryScheduled = true
                main.postDelayed({
                    screenshotRetryScheduled = false
                    requestVisionScan(showErrors)
                }, remaining + 80L)
            }
            return
        }
        lastScreenshotAttemptAt = now
        val service = ChessAccessibilityService.current()
        if (service == null || !AccessibilityBoardStore.isConnected()) {
            if (showErrors) showStatus("Activa Accesibilidad para usar detección visual", 2200)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (showErrors) showStatus("La detección visual requiere Android 11 o superior", 2600)
            return
        }

        visionScanInFlight = true
        // Do not hide the overlay before screenshots. Hiding/restoring the full-screen
        // overlay and button panel on every visual scan caused periodic UI flicker.
        // VisionBoardReader validates several frames/board constraints, so we keep UI stable.
        main.postDelayed({
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val hw = screenshot.hardwareBuffer
                            val cs = screenshot.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                            val wrapped = Bitmap.wrapHardwareBuffer(hw, cs)
                            val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            hw.close()

                            if (bitmap == null) {
                                visionScanInFlight = false
                                if (showErrors) showStatus("No se pudo leer la captura visual", 2200)
                                return
                            }

                            val manual = savedBoardRect()
                            visionWorker.execute {
                                val detection = runCatching { visionReader.detect(bitmap, manual) }.getOrNull()
                                bitmap.recycle()
                                main.post {
                                    visionScanInFlight = false

                                    if (detection != null && visionReader.isValid(detection)) {
                                        visualFailureStreak = 0
                                        val snap = AccessibilityBoardStore.Snapshot(
                                            pieces = detection.pieces,
                                            boardRect = RectF(detection.boardRect),
                                            whiteAtBottom = detection.whiteAtBottom,
                                            squareCount = 64,
                                            pieceCount = detection.pieceCount,
                                            packageName = "visual-screen",
                                            diagnostic = detection.diagnostic
                                        )
                                        AccessibilityBoardStore.update(snap)
                                        if (showErrors) showStatus(detection.diagnostic, 1200)

                                        if (!prefs.getBoolean("vision_manual", false)) {
                                            prefs.edit()
                                                .putFloat("vision_auto_x", detection.boardRect.left)
                                                .putFloat("vision_auto_y", detection.boardRect.top)
                                                .putFloat("vision_auto_size", detection.boardRect.width())
                                                .apply()
                                        }
                                    } else {
                                        val msg = detection?.diagnostic
                                            ?: if (manual != null) "Tablero fijado, pero no reconoce las piezas"
                                            else "AUTO visual no encontró tablero · pulsa ▣ para fijarlo"
                                        val stale = AccessibilityBoardStore.ageMs() > 1800L
                                        if (stale && AccessibilityBoardStore.snapshot() != null) {
                                            // Do not keep analysing a board from a previous site/window.
                                            AccessibilityBoardStore.clearSnapshot(msg)
                                        } else {
                                            AccessibilityBoardStore.updateDiagnostic(msg)
                                        }
                                        visualFailureStreak++
                                        if (visualFailureStreak >= 2 && ::resultView.isInitialized) {
                                            // A persistent invalid visual read means the old arrow is no longer trustworthy.
                                            resultView.setAnalysis(emptyList())
                                        }
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (showErrors || AccessibilityBoardStore.snapshot() == null) {
                                            if (showErrors || now - lastVisualFailureShownAt > 2400L) {
                                                lastVisualFailureShownAt = now
                                                showStatus(msg, 1800)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            visionScanInFlight = false
                            lastVisionScanAt = android.os.SystemClock.elapsedRealtime()

                            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                                // Normal rate-limit condition: retry after the cooldown instead
                                // of presenting it as an unavailable-capture failure.
                                if (!screenshotRetryScheduled) {
                                    screenshotRetryScheduled = true
                                    main.postDelayed({
                                        screenshotRetryScheduled = false
                                        requestVisionScan(showErrors = false)
                                    }, 1250L)
                                }
                                if (showErrors) showStatus("Captura visual · reintentando…", 700)
                                return
                            }

                            if (showErrors || AccessibilityBoardStore.snapshot() == null) {
                                showStatus("Captura visual no disponible (código $errorCode)", 2200)
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                visionScanInFlight = false
                if (showErrors) showStatus("Error de captura visual: ${t.message ?: "desconocido"}", 2600)
            }
        }, 90L)
    }

    private fun analysePosition(whiteToMove: Boolean) {
        autoTurnWhite = whiteToMove
        val service = ChessAccessibilityService.current()
        if (service == null || !AccessibilityBoardStore.isConnected()) {
            showStatus("Accesibilidad no está conectada. Actívala y vuelve al tablero.", 4500)
            return
        }

        // Never analyse a stale board left over from another web site/window.
        // World Chess/FIDE often exposes no useful accessibility squares, so force
        // the visual fallback when the semantic snapshot is old.
        val stored = AccessibilityBoardStore.snapshot()
        val snapshot = if (stored != null && AccessibilityBoardStore.ageMs() <= 1400L) {
            stored
        } else {
            runCatching { service.readBoardNow() }.getOrNull()
        }

        if (snapshot == null) {
            showStatus("Leyendo tablero visual…", 1400)
            requestVisionScan(showErrors = true)
            return
        }

        val multipv = prefs.getInt("multipv", 1).coerceIn(1, 3)
        val profile = humanProfile()
        val key = positionKey(snapshot, whiteToMove)
        val token = ++requestToken
        applySnapshotGeometry(snapshot)

        // Pulsar ♔/♚ en la misma posición equivale a "volver a pensar":
        // conserva la caché rápida de la mejor, pero rehace la elección humana.
        if (profile != HumanProfile.PRECISE) removeHumanChoice(key, profile)

        val cached = getPrimary(key)
        if (cached != null) {
            resultView.setAnalysis(listOf(cached))
            showStatus("✓ ${cached.move}", 650)
            if (profile == HumanProfile.PRECISE) {
                if (multipv > 1) requestRefinement(snapshot, whiteToMove, multipv, key, token)
            } else {
                requestHumanRefinement(snapshot, whiteToMove, key, token, profile, forceReroll = true)
            }
            return
        }

        showStatus(if (whiteToMove) "BLANCAS · cálculo rápido…" else "NEGRAS · cálculo rápido…", 0)
        cancelPendingEngineWork(false)
        worker.execute {
            val fen = ChessFen.fromPieces(snapshot.pieces, whiteToMove)
            val fast = guardedAnalyse(fen, 1, 60, 650L, true)?.firstOrNull()
            if (fast != null) putPrimary(key, fast)

            main.post {
                if (fast != null && isRequestCurrent(token, key, whiteToMove)) {
                    applySnapshotGeometry(snapshot)
                    resultView.setAnalysis(listOf(fast))
                    showStatus("✓ ${fast.move}", 650)
                } else if (fast == null && isRequestCurrent(token, key, whiteToMove)) {
                    showStatus("El motor no encontró una jugada", 3200)
                }
            }

            if (fast != null) {
                main.post {
                    if (profile == HumanProfile.PRECISE) {
                        if (multipv > 1) requestRefinement(snapshot, whiteToMove, multipv, key, token)
                    } else {
                        requestHumanRefinement(snapshot, whiteToMove, key, token, profile, forceReroll = true)
                    }
                }
            }
        }
    }

    private fun requestHumanRefinement(
        snapshot: AccessibilityBoardStore.Snapshot,
        whiteToMove: Boolean,
        key: String,
        token: Long,
        profile: HumanProfile,
        forceReroll: Boolean
    ) {
        val choiceKey = humanChoiceKey(key, profile)
        if (!forceReroll) {
            getHumanChoice(choiceKey)?.let { cachedChoice ->
                if (isRequestCurrent(token, key, whiteToMove)) {
                    applySnapshotGeometry(snapshot)
                    resultView.setAnalysis(listOf(cachedChoice))
                    showHumanChoice(cachedChoice, profile, auto = false)
                }
                return
            }
        }

        val refineKey = "$key|human|${profile.prefValue}"
        synchronized(cacheLock) {
            if (refineKeyInFlight == refineKey) return
            refineKeyInFlight = refineKey
        }

        worker.execute {
            try {
                val fen = ChessFen.fromPieces(snapshot.pieces, whiteToMove)
                val lines = guardedAnalyse(fen, 3, 70, 900L, false)
                if (!lines.isNullOrEmpty()) {
                    lines.firstOrNull()?.let { putPrimary(key, it) }
                    val chosen = HumanMoveSelector.choose(lines, snapshot, whiteToMove, profile)
                    if (chosen != null) {
                        putHumanChoice(choiceKey, chosen)
                        main.post {
                            if (isRequestCurrent(token, key, whiteToMove)) {
                                applySnapshotGeometry(snapshot)
                                resultView.setAnalysis(listOf(chosen))
                                showHumanChoice(chosen, profile, auto = false)
                            }
                        }
                    }
                }
            } finally {
                synchronized(cacheLock) {
                    if (refineKeyInFlight == refineKey) refineKeyInFlight = null
                }
            }
        }
    }

    private fun requestRefinement(
        snapshot: AccessibilityBoardStore.Snapshot,
        whiteToMove: Boolean,
        multipv: Int,
        key: String,
        token: Long
    ) {
        if (multipv <= 1) return
        val refineKey = "$key|$multipv"
        synchronized(cacheLock) {
            if (refineKeyInFlight == refineKey) return
            refineKeyInFlight = refineKey
        }

        worker.execute {
            try {
                val fen = ChessFen.fromPieces(snapshot.pieces, whiteToMove)
                val lines = guardedAnalyse(fen, multipv, 115, 950L, false)
                if (!lines.isNullOrEmpty()) {
                    lines.firstOrNull()?.let { putPrimary(key, it) }
                    main.post {
                        if (isRequestCurrent(token, key, whiteToMove)) {
                            applySnapshotGeometry(snapshot)
                            resultView.setAnalysis(lines)
                            resultView.postInvalidate()
                        }
                    }
                }
            } finally {
                synchronized(cacheLock) {
                    if (refineKeyInFlight == refineKey) refineKeyInFlight = null
                }
            }
        }
    }

    private fun precomputePrimary(snapshot: AccessibilityBoardStore.Snapshot, generation: Long) {
        val preferred = autoTurnWhite
        // Cuando AUTO ya conoce el turno, prioriza solo ese color. Esto reduce
        // la cola y la latencia en blitz/bullet.
        val order = if (preferred == null) listOf(true, false) else listOf(preferred)
        val profile = humanProfile()

        for (whiteToMove in order) {
            if (destroyed || AccessibilityBoardStore.generation() != generation) return
            val key = positionKey(snapshot, whiteToMove)
            var line = getPrimary(key)

            if (line == null) {
                val fen = ChessFen.fromPieces(snapshot.pieces, whiteToMove)
                line = guardedAnalyse(fen, 1, 72, 650L, false)?.firstOrNull()
                if (line != null && AccessibilityBoardStore.generation() == generation) {
                    putPrimary(key, line)
                }
            }

            if (line != null &&
                AccessibilityBoardStore.generation() == generation &&
                autoTurnWhite == whiteToMove
            ) {
                val autoLine = line
                main.post {
                    if (AccessibilityBoardStore.generation() == generation &&
                        autoTurnWhite == whiteToMove
                    ) {
                        applySnapshotGeometry(snapshot)
                        resultView.setAnalysis(listOf(autoLine))
                        showStatus(
                            "AUTO " + (if (whiteToMove) "♔" else "♚") + " · " + autoLine.move,
                            520
                        )
                    }
                }

                if (profile != HumanProfile.PRECISE &&
                    AccessibilityBoardStore.generation() == generation
                ) {
                    val choiceKey = humanChoiceKey(key, profile)
                    var chosen = getHumanChoice(choiceKey)
                    if (chosen == null) {
                        val fen = ChessFen.fromPieces(snapshot.pieces, whiteToMove)
                        val lines = guardedAnalyse(fen, 3, 65, 850L, false)
                        if (!lines.isNullOrEmpty() &&
                            AccessibilityBoardStore.generation() == generation
                        ) {
                            lines.firstOrNull()?.let { putPrimary(key, it) }
                            chosen = HumanMoveSelector.choose(lines, snapshot, whiteToMove, profile)
                            chosen?.let { putHumanChoice(choiceKey, it) }
                        }
                    }

                    val finalChoice = chosen
                    if (finalChoice != null) {
                        main.post {
                            if (AccessibilityBoardStore.generation() == generation &&
                                autoTurnWhite == whiteToMove
                            ) {
                                applySnapshotGeometry(snapshot)
                                resultView.setAnalysis(listOf(finalChoice))
                                showHumanChoice(finalChoice, profile, auto = true)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun processStablePosition(snapshot: AccessibilityBoardStore.Snapshot, generation: Long) {
        val previous = lastStableSnapshot
        val previousTurn = autoTurnWhite

        autoTurnWhite = when {
            isInitialPosition(snapshot) -> true
            previous != null -> {
                inferMoverWhite(previous, snapshot)?.let { !it }
                    ?: if (plausibleSingleMove(previous, snapshot) && previousTurn != null) !previousTurn
                    else previousTurn
            }
            else -> previousTurn
        }

        lastStableSnapshot = snapshot
        cancelPendingEngineWork(false)
        worker.execute { precomputePrimary(snapshot, generation) }
    }

    private fun inferMoverWhite(
        before: AccessibilityBoardStore.Snapshot,
        after: AccessibilityBoardStore.Snapshot
    ): Boolean? {
        var whiteRemoved = 0
        var whiteAdded = 0
        var blackRemoved = 0
        var blackAdded = 0
        var changed = 0

        for (rank in 1..8) for (file in 'a'..'h') {
            val sq = "$file$rank"
            val oldPiece = before.pieces[sq] ?: '.'
            val newPiece = after.pieces[sq] ?: '.'
            if (oldPiece == newPiece) continue
            changed++

            if (oldPiece != '.') {
                if (oldPiece.isUpperCase()) whiteRemoved++ else blackRemoved++
            }
            if (newPiece != '.') {
                if (newPiece.isUpperCase()) whiteAdded++ else blackAdded++
            }
        }

        if (changed !in 2..6) return null
        val whiteCandidate = whiteRemoved >= 1 && whiteAdded >= 1
        val blackCandidate = blackRemoved >= 1 && blackAdded >= 1
        return when {
            whiteCandidate && !blackCandidate -> true
            blackCandidate && !whiteCandidate -> false
            else -> null
        }
    }

    private fun plausibleSingleMove(
        before: AccessibilityBoardStore.Snapshot,
        after: AccessibilityBoardStore.Snapshot
    ): Boolean {
        var changed = 0
        for (rank in 1..8) for (file in 'a'..'h') {
            val sq = "$file$rank"
            if ((before.pieces[sq] ?: '.') != (after.pieces[sq] ?: '.')) changed++
        }
        return changed in 2..6
    }

    private fun isInitialPosition(snapshot: AccessibilityBoardStore.Snapshot): Boolean {
        if (snapshot.pieceCount != 32) return false
        val p = snapshot.pieces
        if (p["e1"] != 'K' || p["e8"] != 'k') return false
        for (file in 'a'..'h') {
            if (p["$file" + "2"] != 'P' || p["$file" + "7"] != 'p') return false
        }
        return true
    }

    private fun guardedAnalyse(
        fen: String,
        multiPv: Int,
        moveTimeMs: Int,
        timeoutMs: Long,
        notifyOnTimeout: Boolean
    ): List<AnalysisLine>? {
        val id = searchSeq.incrementAndGet()
        activeSearchId = id

        main.postDelayed({
            if (activeSearchId == id) {
                engine.abortSearch()
                worker.queue.clear()
                refineKeyInFlight = null
                if (notifyOnTimeout) {
                    showStatus("Motor lento · recuperando…", 1100)
                }
            }
        }, timeoutMs)

        return try {
            runCatching { engine.analyse(fen, multiPv, moveTimeMs) }.getOrNull()
        } finally {
            if (activeSearchId == id) activeSearchId = 0L
        }
    }

    private fun cancelPendingEngineWork(showMessage: Boolean) {
        activeSearchId = 0L
        engine.abortSearch()
        worker.queue.clear()
        refineKeyInFlight = null
        if (showMessage) showStatus("↻ Cancelando cálculo anterior…", 650)
    }

    private fun hardReset() {
        showStatus("↻ Reiniciando motor y tablero…", 0)
        requestToken++
        cancelPendingEngineWork(false)
        synchronized(cacheLock) {
            primaryCache.clear()
            humanChoiceCache.clear()
        }
        resultView.setAnalysis(emptyList())

        seenGeneration = -1L
        processedGeneration = -1L
        stableSinceMs = 0L
        lastStableSnapshot = null
        autoTurnWhite = null
        lastPrefetchGeneration = -1L

        val old = worker
        old.queue.clear()
        old.shutdownNow()
        worker = newWorker()

        AccessibilityBoardStore.clearSnapshot("Reset manual · leyendo tablero de nuevo…")
        runCatching { ChessAccessibilityService.current()?.readBoardNow() }
        main.postDelayed({ requestVisionScan(showErrors = false) }, 140L)

        worker.execute {
            runCatching { engine.resetState() }
            runCatching { engine.warmUp() }
            main.post {
                if (!destroyed) showStatus("✓ Reset completado", 900)
            }
        }
    }

    private fun applySnapshotGeometry(snapshot: AccessibilityBoardStore.Snapshot) {
        val rect = RectF(snapshot.boardRect)
        val location = IntArray(2)
        runCatching { resultView.getLocationOnScreen(location) }
        rect.offset(-location[0].toFloat(), -location[1].toFloat())
        resultView.boardRect = rect
        resultView.whiteAtBottom = snapshot.whiteAtBottom
        resultView.postInvalidate()
    }

    private fun positionKey(snapshot: AccessibilityBoardStore.Snapshot, whiteToMove: Boolean): String = buildString(70) {
        for (rank in 8 downTo 1) {
            for (file in 'a'..'h') append(snapshot.pieces["$file$rank"] ?: '.')
        }
        append(if (whiteToMove) 'w' else 'b')
    }

    private fun currentKey(whiteToMove: Boolean): String? =
        AccessibilityBoardStore.snapshot()?.let { positionKey(it, whiteToMove) }

    private fun isRequestCurrent(token: Long, key: String, whiteToMove: Boolean): Boolean =
        token == requestToken && currentKey(whiteToMove) == key

    private fun humanProfile(): HumanProfile =
        HumanProfile.fromPref(prefs.getString("human_profile", HumanProfile.UNIVERSAL.prefValue))

    private fun humanChoiceKey(key: String, profile: HumanProfile): String =
        "$key|human|${profile.prefValue}"

    private fun getHumanChoice(key: String): AnalysisLine? =
        synchronized(cacheLock) { humanChoiceCache[key] }

    private fun removeHumanChoice(key: String, profile: HumanProfile) =
        synchronized(cacheLock) { humanChoiceCache.remove(humanChoiceKey(key, profile)) }

    private fun putHumanChoice(key: String, line: AnalysisLine) = synchronized(cacheLock) {
        humanChoiceCache[key] = line
        while (humanChoiceCache.size > 16) {
            val iterator = humanChoiceCache.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            } else break
        }
    }

    private fun showHumanChoice(line: AnalysisLine, profile: HumanProfile, auto: Boolean) {
        val prefix = if (auto) "AUTO " else ""
        val rank = "#${line.rank}"
        val profileTag = when (profile) {
            HumanProfile.UNIVERSAL -> "U"
            HumanProfile.POSITIONAL -> "P"
            HumanProfile.TACTICAL -> "T"
            else -> ""
        }
        showStatus("$prefix★$profileTag $rank · ${line.move}", 850)
    }

    private fun getPrimary(key: String): AnalysisLine? = synchronized(cacheLock) { primaryCache[key] }

    private fun putPrimary(key: String, line: AnalysisLine) = synchronized(cacheLock) {
        primaryCache[key] = line
        while (primaryCache.size > 12) {
            val iterator = primaryCache.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            } else break
        }
    }

    private fun showStatus(message: String, hideAfterMs: Long) {
        if (!::statusView.isInitialized) return
        val token = ++statusHideToken
        statusView.text = message
        statusView.visibility = View.VISIBLE
        statusView.bringToFront()
        if (hideAfterMs > 0) {
            main.postDelayed({
                if (statusHideToken == token && ::statusView.isInitialized) statusView.visibility = View.GONE
            }, hideAfterMs)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(prefetchRunnable)
        selectorView?.let { runCatching { wm.removeView(it) } }
        selectorView = null
        if (::statusView.isInitialized) runCatching { wm.removeView(statusView) }
        if (::panel.isInitialized) runCatching { wm.removeView(panel) }
        if (::resultView.isInitialized) runCatching { wm.removeView(resultView) }
        worker.shutdownNow()
        visionWorker.shutdownNow()
        if (::visionReader.isInitialized) visionReader.close()
        if (::engine.isInitialized) engine.close()
        super.onDestroy()
    }
}
