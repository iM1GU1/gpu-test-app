from pathlib import Path
import base64, zipfile, io, shutil, sys

repo = Path(sys.argv[1])
payload = Path(sys.argv[2])
raw = base64.b64decode(payload.read_text())
work = Path('/tmp/v10patches')
shutil.rmtree(work, ignore_errors=True)
work.mkdir(parents=True)
with zipfile.ZipFile(io.BytesIO(raw)) as z:
    z.extractall(work)

java = repo/'app/src/main/java/com/example/berserkoverlay'
shutil.copy2(work/'OverlayService.kt', java/'OverlayService.kt')
shutil.copy2(work/'ChessAccessibilityService.kt', java/'ChessAccessibilityService.kt')

gradle = repo/'app/build.gradle.kts'
g = gradle.read_text()
g = g.replace('versionCode = 9', 'versionCode = 10')
g = g.replace('versionName = "0.9.0"', 'versionName = "0.10.0"')
gradle.write_text(g)
print('v10 patches applied')
