package com.example.berserkoverlay

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var connectAttempt = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)

        val group = findViewById<RadioGroup>(R.id.groupMultiPv)
        when (prefs.getInt("multipv", 1)) {
            2 -> group.check(R.id.pv2)
            3 -> group.check(R.id.pv3)
            else -> group.check(R.id.pv1)
        }
        group.setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt("multipv", when(id){ R.id.pv2 -> 2; R.id.pv3 -> 3; else -> 1 }).apply()
        }

        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else refreshStatus()
        }

        // Solo abre Ajustes cuando el usuario lo pide expresamente.
        // Ya no redirigimos automáticamente durante un reinicio del proceso.
        findViewById<Button>(R.id.btnCapturePermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnStartOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "Estado: primero concede Mostrar sobre otras apps."
                return@setOnClickListener
            }

            when {
                AccessibilityBoardStore.isConnected() -> startOverlay()
                isAccessibilityEnabledInSettings() -> {
                    connectAttempt = 0
                    status.text = "Estado: Accesibilidad habilitada · reconectando…"
                    waitForAccessibilityConnection()
                }
                else -> {
                    status.text = "Estado: activa Chess22k AI Overlay en Accesibilidad con el botón de arriba."
                }
            }
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()

        // Después de reiniciar la app el estado estático puede tardar unas décimas
        // en reconstruirse aunque Android siga teniendo el servicio habilitado.
        if (isAccessibilityEnabledInSettings() && !AccessibilityBoardStore.isConnected()) {
            connectAttempt = 0
            waitForAccessibilityConnection(startOverlayWhenReady = false)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun waitForAccessibilityConnection(startOverlayWhenReady: Boolean = true) {
        if (AccessibilityBoardStore.isConnected()) {
            refreshStatus()
            if (startOverlayWhenReady) startOverlay()
            return
        }

        if (!isAccessibilityEnabledInSettings()) {
            refreshStatus()
            return
        }

        if (connectAttempt >= 15) {
            status.text =
                "Estado: Accesibilidad está habilitada, pero Android aún no la ha conectado. " +
                "Espera unos segundos; abre Ajustes de Accesibilidad solo si persiste."
            return
        }

        connectAttempt++
        main.postDelayed({ waitForAccessibilityConnection(startOverlayWhenReady) }, 180L)
    }

    private fun startOverlay() {
        startService(Intent(this, OverlayService::class.java))
        status.text = "Estado: botones flotantes activos · lectura por Accesibilidad."
        moveTaskToBack(true)
    }

    private fun isAccessibilityEnabledInSettings(): Boolean {
        val expected = ComponentName(this, ChessAccessibilityService::class.java)
        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return raw.split(':').any { entry ->
            ComponentName.unflattenFromString(entry)?.let {
                it.packageName == expected.packageName && it.className == expected.className
            } == true
        }
    }

    private fun refreshStatus() {
        status.text = when {
            !Settings.canDrawOverlays(this) ->
                "Estado: falta permiso Mostrar sobre otras apps."

            AccessibilityBoardStore.isConnected() ->
                "Estado: Accesibilidad activa ✓ · abre el tablero y muestra los botones."

            isAccessibilityEnabledInSettings() ->
                "Estado: Accesibilidad habilitada · esperando reconexión del servicio…"

            else ->
                "Estado: falta activar Chess22k AI Overlay en Accesibilidad."
        }
    }
}
