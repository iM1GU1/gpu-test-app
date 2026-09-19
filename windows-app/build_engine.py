"""Build pinned chess22k 1.14, retaining the Android root-exclusion patch."""
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parent
engine = root/'build/chess22k'
engine.parent.mkdir(exist_ok=True)
subprocess.run(['git','clone','https://github.com/sandermvdb/chess22k.git',str(engine)],check=True)
subprocess.run(['git','checkout','189434c09dc917bf27026cbfb01c5ca88eb55319'],cwd=engine,check=True)
source = engine/'src/main/java/nl/s22k/chess/search/NegamaxUtil.java'
text = source.read_text()
anchor = 'public static boolean isRunning = false;'
replacement = '''public static volatile boolean isRunning = false;
    private static volatile int[] ROOT_EXCLUDED_MOVES = new int[0];
    public static void setRootExcludedMoves(int[] moves) { ROOT_EXCLUDED_MOVES = moves.clone(); }
    private static boolean isRootMoveExcluded(int move) {
        for (int excluded : ROOT_EXCLUDED_MOVES) if (excluded == move) return true;
        return false;
    }'''
assert text.count(anchor) == 1
text = text.replace(anchor,replacement,1)
anchor = 'final int move = threadData.next();'
assert text.count(anchor) == 1
text = text.replace(anchor,anchor+'\n if (ply == 0 && isRootMoveExcluded(move)) continue;',1)
source.write_text(text)
classes = root/'build/classes'; classes.mkdir(parents=True,exist_ok=True)
sources = list((engine/'src/main/java').rglob('*.java'))+[root/'OverlayBridge.java']
args = root/'build/javac-args.txt'
args.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources),encoding='utf-8')
subprocess.run(['javac','--release','17','-encoding','UTF-8','-d',str(classes),'@'+str(args)],check=True)
out = root/'engine'; out.mkdir(exist_ok=True)
subprocess.run(['jar','--create','--file',str(out/'chess22k-overlay.jar'),'--main-class','nl.s22k.chess.desktop.OverlayBridge','-C',str(classes),'.'],check=True)
licenses = root/'licenses'; licenses.mkdir(exist_ok=True)
shutil.copy(engine/'LICENSE', licenses/'chess22k-GPL-3.0.txt')
source_out = root/'engine-source'; source_out.mkdir(exist_ok=True)
shutil.copytree(engine/'src',source_out/'src',dirs_exist_ok=True)
shutil.copy(engine/'LICENSE',source_out/'LICENSE')
shutil.copy(root/'OverlayBridge.java',source_out/'OverlayBridge.java')
