from pathlib import Path
import hashlib
import importlib.metadata
import json
import shutil
import sys
import zipfile

root = Path(__file__).resolve().parent
dist = root/'dist/Chess22kOverlay'
if '--zip' in sys.argv:
    archive=root/'Chess22kOverlay-Windows11-x64-v0.1.0.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for path in sorted(dist.rglob('*')):
            if path.is_file():
                z.write(path,Path('Chess22kOverlay')/path.relative_to(dist))
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        names=set(z.namelist())
        for name in ['Chess22kOverlay.exe','runtime/bin/java.exe','engine/chess22k-overlay.jar','models/pieces.onnx','self-test-result.json','LEEME.txt']:
            assert 'Chess22kOverlay/'+name in names, name
    digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    (root/'Chess22kOverlay-Windows11-x64-v0.1.0.sha256').write_text(digest+'  '+archive.name+'\n')
    print(archive.name,archive.stat().st_size,digest)
    sys.exit(0)
for directory in ['runtime','engine','models','licenses','engine-source']:
    shutil.copytree(root/directory,dist/directory,dirs_exist_ok=True)
shutil.copy(root/'LEEME.txt',dist/'LEEME.txt')
source=dist/'source'; source.mkdir(exist_ok=True)
for path in root.iterdir():
    if path.is_file() and path.suffix in ('.py','.java','.txt') and path.name != 'self-test-result.json':
        shutil.copy(path,source/path.name)
shutil.copytree(root/'tests',source/'tests',ignore=shutil.ignore_patterns('__pycache__'),dirs_exist_ok=True)
shutil.copy(root.parent/'.github/workflows/chess22k-windows.yml',source/'chess22k-windows.yml')
shutil.copy(dist/'licenses/chess22k-GPL-3.0.txt',source/'LICENSE')
packages=[]
for name in ['numpy','Pillow','chess','PySide6','PySide6-Essentials','PySide6-Addons','shiboken6','onnxruntime','mss','uiautomation','comtypes','pyinstaller']:
    distribution=importlib.metadata.distribution(name)
    packages.append({'name':name,'version':distribution.version,'license':distribution.metadata.get('License','See package notices')})
    for entry in distribution.files or []:
        if any(word in entry.name.lower() for word in ('license','copying','notice','copyright')):
            src=distribution.locate_file(entry)
            if src.is_file():
                target=dist/'licenses'/name/str(entry).replace('../','').replace('..\\','')
                target.parent.mkdir(parents=True,exist_ok=True); shutil.copy(src,target)
(dist/'licenses/dependencies.json').write_text(json.dumps(packages,indent=2),encoding='utf-8')
