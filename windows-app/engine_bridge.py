from pathlib import Path
import queue
import subprocess
import threading
import chess
from core import line_value


class Engine:
    def __init__(self, root):
        self.root = Path(root)
        self.process = None
        self.lock = threading.Lock()

    def _start(self):
        java = self.root/'runtime/bin/java.exe'
        if not java.exists():
            java = 'java'
        self.process = subprocess.Popen([str(java), '-Xmx256m', '-jar', str(self.root/'engine/chess22k-overlay.jar')],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding='utf-8', bufsize=1,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.lines = queue.Queue()
        process, lines = self.process, self.lines
        def reader():
            for line in process.stdout:
                lines.put(line.strip())
            lines.put('ERROR|El motor se ha cerrado')
        threading.Thread(target=reader, daemon=True).start()
        self._receive('READY', 15)

    def _receive(self, prefix, timeout):
        import time
        deadline = time.monotonic()+timeout
        while time.monotonic() < deadline:
            try:
                line = self.lines.get(timeout=max(.01, deadline-time.monotonic()))
            except queue.Empty:
                break
            if line.startswith('ERROR'):
                raise RuntimeError(line)
            if line.startswith(prefix):
                return line
        self.close()
        raise TimeoutError('El motor excedió el tiempo de espera; se reiniciará')

    def analyse(self, board, count=3, milliseconds=180):
        if not board.is_valid():
            raise ValueError('No se analiza una posición ilegal')
        if board.is_game_over():
            return []
        with self.lock:
            if self.process is None or self.process.poll() is not None:
                self._start()
            self.process.stdin.write(f'ANALYSE\t{board.fen()}\t{count}\t{milliseconds}\n')
            self.process.stdin.flush()
            reply = self._receive('RESULT|', 12)
        lines = []
        for entry in reply.partition('|')[2].split(';'):
            if not entry:
                continue
            move, score, mate = entry.split(',')
            if chess.Move.from_uci(move) not in board.legal_moves:
                raise RuntimeError('El motor devolvió una jugada no legal')
            if move not in [line['move'] for line in lines]:
                lines.append({'move':move, 'score':int(score), 'mate':int(mate) if mate != '-' else None})
        return sorted(lines, key=line_value, reverse=True)

    def close(self):
        process, self.process = self.process, None
        if process and process.poll() is None:
            try:
                process.stdin.write('QUIT\n')
                process.stdin.flush()
                process.wait(timeout=.5)
            except (OSError, subprocess.TimeoutExpired):
                process.kill()
                process.wait(timeout=3)
