from pathlib import Path
import sys

repo = Path(sys.argv[1])

acc = repo / 'app/src/main/java/com/example/berserkoverlay/ChessAccessibilityService.kt'
s = acc.read_text()
old = '''    override fun onAccessibilityEvent(event: AccessibilityEvent?) = scheduleScan()
    override fun onInterrupt() = Unit
'''
new = '''    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        active = this
        if (!AccessibilityBoardStore.isConnected()) {
            AccessibilityBoardStore.setConnected(true)
        }
        scheduleScan()
    }

    override fun onInterrupt() {
        scheduleScan()
    }
'''
if old in s:
    s = s.replace(old, new, 1)
acc.write_text(s)

g = repo / 'app/build.gradle.kts'
gs = g.read_text()
gs = gs.replace('versionCode = 12', 'versionCode = 13')
gs = gs.replace('versionName = "0.12.0"', 'versionName = "0.13.0"')
g.write_text(gs)
print('v13 lifecycle patch applied')
