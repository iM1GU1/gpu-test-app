from pathlib import Path
import base64, zipfile, io, shutil, sys

repo = Path(sys.argv[1])
payload = Path(sys.argv[2])
raw = base64.b64decode(payload.read_text())
work = Path('/tmp/v9patches')
shutil.rmtree(work, ignore_errors=True)
work.mkdir(parents=True)
with zipfile.ZipFile(io.BytesIO(raw)) as z:
    z.extractall(work)

java = repo/'app/src/main/java/com/example/berserkoverlay'
res = repo/'app/src/main/res'
shutil.copy2(work/'MainActivity.kt', java/'MainActivity.kt')
shutil.copy2(work/'OverlayService.kt', java/'OverlayService.kt')
shutil.copy2(work/'AccessibilityBoardStore.kt', java/'AccessibilityBoardStore.kt')
shutil.copy2(work/'ChessAccessibilityService.kt', java/'ChessAccessibilityService.kt')
shutil.copy2(work/'activity_main.xml', res/'layout/activity_main.xml')
(res/'xml').mkdir(parents=True, exist_ok=True)
shutil.copy2(work/'res/xml/accessibility_service_config.xml', res/'xml/accessibility_service_config.xml')

manifest = repo/'app/src/main/AndroidManifest.xml'
m = manifest.read_text()
m = m.replace('android:label="Berserk Overlay"', 'android:label="Chess22k Accessibility Overlay"')
service = '''

        <service
            android:name=".ChessAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>'''
needle = '''        <service
            android:name=".OverlayService"
            android:exported="false" />'''
if 'android:name=".ChessAccessibilityService"' not in m:
    if needle not in m:
        raise SystemExit('OverlayService manifest block not found')
    m = m.replace(needle, service + '\n\n' + needle)
manifest.write_text(m)

gradle = repo/'app/build.gradle.kts'
g = gradle.read_text()
g = g.replace('versionCode = 8', 'versionCode = 9')
g = g.replace('versionName = "0.8.0"', 'versionName = "0.9.0"')
gradle.write_text(g)
print('v9 accessibility patches applied')
