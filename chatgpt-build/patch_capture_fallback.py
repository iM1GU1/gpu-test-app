from pathlib import Path
import re
import sys

overlay = Path(sys.argv[1])
gradle = Path(sys.argv[2])
s = overlay.read_text()

pattern = r'''    private fun waitForFreshFrame\(.*?(?=    private fun )'''
replacement = '''    private fun waitForFreshFrame(previousGeneration: Long, attempt: Int, onReady: (android.graphics.Bitmap) -> Unit) {
        if (ProjectionStore.generation() > previousGeneration) {
            val fresh = ProjectionStore.copyLatest()
            if (fresh != null) {
                onReady(fresh)
                return
            }
        }

        // Some Android/OEM implementations only emit a MediaProjection frame
        // when captured content changes. Hiding our overlay may not create a
        // new frame because overlays can be excluded from screen capture.
        // After a short grace period, reuse the latest valid cached frame.
        if (attempt >= 5) {
            val cached = ProjectionStore.copyLatest()
            if (cached != null) {
                onReady(cached)
            } else {
                setCaptureOverlaysVisible(true)
                android.widget.Toast.makeText(
                    this,
                    "No hay ninguna captura disponible. Abre Chess22k AI Overlay y vuelve a pulsar Permitir captura de pantalla.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        main.postDelayed({
            waitForFreshFrame(previousGeneration, attempt + 1, onReady)
        }, 120)
    }

'''

s2, count = re.subn(pattern, replacement, s, count=1, flags=re.S)
if count != 1:
    raise SystemExit("waitForFreshFrame function not found")
overlay.write_text(s2)

g = gradle.read_text()
g = g.replace('versionCode = 5', 'versionCode = 6')
g = g.replace('versionName = "0.5.0"', 'versionName = "0.6.0"')
gradle.write_text(g)
print("capture fallback patched")
