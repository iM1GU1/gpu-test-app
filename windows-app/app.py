"""Chess22k Overlay for Windows 11 x64. Desktop adaptation, not an emulator."""
import ctypes
from ctypes import wintypes
import json
import logging
from logging.handlers import RotatingFileHandler
import math
import os
from pathlib import Path
import queue
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import chess
from PIL import Image
from PySide6.QtCore import Qt, QTimer, Signal, QObject, QPointF, QRectF
from PySide6.QtGui import QColor, QPainter, QPen, QPolygonF, QFont, QFontDatabase
from PySide6.QtWidgets import (QApplication, QWidget, QVBoxLayout, QHBoxLayout,
    QPushButton, QLabel, QComboBox, QCheckBox, QInputDialog, QMessageBox, QGroupBox)

from core import StablePosition, human_choice
from engine_bridge import Engine
from vision import Classifier, find_boards

ROOT = Path(sys.executable).parent if getattr(sys, 'frozen', False) else Path(__file__).parent
VERSION = '0.1.0-windows'


def load_system_fonts(app):
    # Explicit registration also gives the offscreen CI backend real glyphs.
    # The files remain Windows-owned; no proprietary font is redistributed.
    if sys.platform == 'win32':
        folder = Path(os.environ.get('WINDIR', 'C:/Windows'))/'Fonts'
        for filename in ('segoeui.ttf', 'segoeuib.ttf', 'arial.ttf', 'arialbd.ttf'):
            path = folder/filename
            if path.is_file():
                QFontDatabase.addApplicationFont(str(path))
    app.setFont(QFont('Segoe UI', 10))


def enable_dpi():
    if sys.platform == 'win32':
        user32 = ctypes.WinDLL('user32', use_last_error=True)
        user32.SetProcessDpiAwarenessContext.argtypes = [ctypes.c_void_p]
        user32.SetProcessDpiAwarenessContext.restype = wintypes.BOOL
        user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))


def cursor_position():
    point = wintypes.POINT()
    ctypes.windll.user32.GetCursorPos(ctypes.byref(point))
    return point.x, point.y


def exclude_capture(widget):
    if sys.platform != 'win32':
        return False
    user32 = ctypes.WinDLL('user32', use_last_error=True)
    user32.SetWindowDisplayAffinity.argtypes = [wintypes.HWND, wintypes.DWORD]
    user32.SetWindowDisplayAffinity.restype = wintypes.BOOL
    return bool(user32.SetWindowDisplayAffinity(int(widget.winId()), 0x11))


def position_native(widget, rect):
    x, y, side = rect
    user32 = ctypes.WinDLL('user32', use_last_error=True)
    user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int,
                                   ctypes.c_int, ctypes.c_int, wintypes.UINT]
    user32.SetWindowPos.restype = wintypes.BOOL
    if not user32.SetWindowPos(int(widget.winId()), -1, x, y, side, side, 0x0010):
        raise OSError('No se pudo colocar la ventana del overlay')


class Arrows(QWidget):
    def __init__(self):
        super().__init__(None, Qt.WindowType.Tool | Qt.WindowType.FramelessWindowHint |
                         Qt.WindowType.WindowStaysOnTopHint | Qt.WindowType.WindowTransparentForInput |
                         Qt.WindowType.WindowDoesNotAcceptFocus)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self.moves, self.rect_px, self.white_bottom = [], None, True
        self.capture_safe = False

    def set_board(self, rect, white_bottom):
        self.rect_px, self.white_bottom = rect, white_bottom
        self.show()
        position_native(self, rect)
        self.capture_safe = exclude_capture(self)
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        cell = min(self.width(), self.height())/8
        def center(square):
            f, r = chess.square_file(square), chess.square_rank(square)
            return QPointF((f+.5 if self.white_bottom else 7-f+.5)*cell,
                           (7-r+.5 if self.white_bottom else r+.5)*cell)
        colors = ['#42dd9c', '#f4c65d', '#f37777']
        for i, line in reversed(list(enumerate(self.moves[:3]))):
            move = chess.Move.from_uci(line['move'])
            a, b = center(move.from_square), center(move.to_square)
            dx, dy = b.x()-a.x(), b.y()-a.y()
            length = math.hypot(dx, dy)
            if not length:
                continue
            ux, uy = dx/length, dy/length
            head = cell*.28
            end = QPointF(b.x()-ux*head*.65, b.y()-uy*head*.65)
            color = QColor(colors[i]); color.setAlpha(220)
            painter.setPen(QPen(color, max(5., cell*.09), Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            painter.drawLine(a, end)
            painter.setPen(Qt.PenStyle.NoPen); painter.setBrush(color)
            painter.drawPolygon(QPolygonF([b, QPointF(b.x()-ux*head-uy*head*.58, b.y()-uy*head+ux*head*.58),
                QPointF(b.x()-ux*head+uy*head*.58, b.y()-uy*head-ux*head*.58)]))


class Selector(QWidget):
    selected = Signal(object)
    cancelled = Signal()

    def __init__(self, screen):
        super().__init__(None, Qt.WindowType.Tool | Qt.WindowType.FramelessWindowHint | Qt.WindowType.WindowStaysOnTopHint)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setGeometry(screen.geometry())
        self.setCursor(Qt.CursorShape.CrossCursor)
        self.start_local = self.end_local = None
        self.start_native = None
        self.setMouseTracking(True)

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.RightButton:
            self.cancelled.emit(); return
        self.start_local = self.end_local = event.position()
        self.start_native = cursor_position()
        self.update()

    def mouseMoveEvent(self, event):
        if self.start_local is not None:
            self.end_local = event.position(); self.update()

    def mouseReleaseEvent(self, event):
        if self.start_native is None:
            return
        x1, y1 = self.start_native
        x2, y2 = cursor_position()
        width, height = abs(x2-x1), abs(y2-y1)
        if min(width, height) < 160 or abs(width-height) > max(width,height)*.12:
            self.start_native = None; self.start_local = None; self.update(); return
        self.selected.emit((min(x1,x2), min(y1,y2), round((width+height)/2)))

    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Escape:
            self.cancelled.emit()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor(9, 16, 26, 65))
        painter.setPen(QColor('#ffffff')); painter.setFont(QFont('Segoe UI', 15))
        painter.drawText(35, 50, 'Arrastra de una esquina a la opuesta del tablero · Esc cancela')
        if self.start_local is not None:
            painter.setPen(QPen(QColor('#50e3a5'), 3))
            painter.drawRect(QRectF(self.start_local, self.end_local).normalized())


class Events(QObject):
    done = Signal(int, str, object)


class Window(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle('Chess22k Overlay · Windows')
        self.resize(460, 550)
        self.setWindowFlag(Qt.WindowType.WindowStaysOnTopHint, True)
        self.pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix='chess-overlay')
        self.events = Events(); self.events.done.connect(self.result)
        self.engine = Engine(ROOT)
        self.classifier = None
        self.arrows = Arrows()
        self.tracker = StablePosition()
        self.rect_px = None
        self.running = self.scan_busy = self.engine_busy = False
        self.generation = 0
        self.analysis_key = None
        self.last_valid_at = 0
        self.displayed_at = 0
        self.last_lines = []
        self.last_display_board = None
        self.uia_thread = None
        self.manual_mode = False
        self.selectors = []
        layout = QVBoxLayout(self); layout.setSpacing(12); layout.setContentsMargins(22,22,22,22)
        title = QLabel('CHESS22K  /  OVERLAY'); title.setObjectName('title'); layout.addWidget(title)
        subtitle = QLabel('Windows 11 · Análisis local · Base Android v15.1'); subtitle.setObjectName('muted'); layout.addWidget(subtitle)
        row = QHBoxLayout(); layout.addLayout(row)
        self.pick = QPushButton('Seleccionar tablero'); self.pick.clicked.connect(self.select_board); row.addWidget(self.pick)
        self.find = QPushButton('Detectar automáticamente'); self.find.clicked.connect(self.auto_find); row.addWidget(self.find)
        options = QGroupBox('Lectura y análisis'); options_layout = QVBoxLayout(options); layout.addWidget(options)
        self.orientation = QComboBox(); self.orientation.addItems(['Blancas abajo', 'Negras abajo'])
        self.orientation.currentIndexChanged.connect(self.orientation_changed); options_layout.addWidget(self.orientation)
        turn_row = QHBoxLayout(); options_layout.addLayout(turn_row)
        self.white = QPushButton('Turno: blancas'); self.black = QPushButton('Turno: negras')
        self.white.clicked.connect(lambda: self.set_turn(chess.WHITE)); self.black.clicked.connect(lambda: self.set_turn(chess.BLACK))
        turn_row.addWidget(self.white); turn_row.addWidget(self.black)
        self.turn = chess.WHITE
        self.profile = QComboBox(); self.profile.addItems(['Preciso', 'Universal elite', 'Posicional elite', 'Táctico elite'])
        self.profile.currentIndexChanged.connect(self.refresh_moves); options_layout.addWidget(self.profile)
        self.count = QComboBox(); self.count.addItems(['1 flecha', '3 alternativas']); self.count.currentIndexChanged.connect(self.refresh_moves)
        options_layout.addWidget(self.count)
        self.uia = QCheckBox('Usar accesibilidad si el navegador la ofrece'); self.uia.setChecked(True); options_layout.addWidget(self.uia)
        row = QHBoxLayout(); layout.addLayout(row)
        self.start = QPushButton('Iniciar'); self.start.setObjectName('primary'); self.start.clicked.connect(self.toggle); row.addWidget(self.start)
        reset = QPushButton('Reiniciar'); reset.clicked.connect(self.reset); row.addWidget(reset)
        fen = QPushButton('Importar FEN'); fen.clicked.connect(self.import_fen); row.addWidget(fen)
        self.status = QLabel('Selecciona el tablero visible para empezar.'); self.status.setWordWrap(True); layout.addWidget(self.status)
        self.moves_text = QLabel('Sin recomendación'); self.moves_text.setWordWrap(True); self.moves_text.setMinimumHeight(65); layout.addWidget(self.moves_text)
        footer = QLabel('Solo análisis y entrenamiento donde esté permitido.\nNo mueve piezas ni envía capturas a Internet.'); footer.setObjectName('muted'); footer.setWordWrap(True); layout.addWidget(footer)
        self.setStyleSheet('''QWidget {background:#111c2b;color:#edf3fc;font:10pt "Segoe UI";}
          QLabel#title {font-size:18pt;font-weight:700;} QLabel#muted {color:#9badc5;font-size:9pt;}
          QPushButton,QComboBox {background:#23344a;border:1px solid #3b506a;border-radius:7px;padding:9px;}
          QPushButton:hover {background:#314962;} QPushButton#primary {background:#197953;border-color:#30996f;font-weight:700;}
          QGroupBox {border:1px solid #33475f;border-radius:8px;margin-top:12px;padding:12px;} QGroupBox::title {subcontrol-origin:margin;left:12px;}
        ''')
        self.timer = QTimer(self); self.timer.timeout.connect(self.tick); self.timer.start(1200)
        self.watchdog = QTimer(self); self.watchdog.timeout.connect(self.expire); self.watchdog.start(500)

    @property
    def white_bottom(self):
        return self.orientation.currentIndex() == 0

    def submit(self, kind, function, *args):
        generation = self.generation
        future = self.pool.submit(function, *args)
        def complete(f):
            try:
                payload = f.result()
            except Exception as error:
                logging.exception('%s failed', kind)
                payload = error
            self.events.done.emit(generation, kind, payload)
        future.add_done_callback(complete)

    def clear_arrows(self):
        self.arrows.moves = []; self.arrows.update()
        self.moves_text.setText('Sin recomendación válida')

    def reset(self):
        self.generation += 1
        self.turn = chess.WHITE
        self.tracker = StablePosition(); self.analysis_key = None
        self.last_lines = []; self.last_display_board = None; self.manual_mode = False
        self.last_valid_at = 0
        self.clear_arrows()
        self.status.setText('Estado reiniciado. Comprueba la orientación y el turno.')

    def orientation_changed(self):
        self.reset()
        if self.rect_px:
            self.arrows.set_board(self.rect_px, self.white_bottom)

    def set_turn(self, turn):
        self.turn = turn
        self.generation += 1; self.analysis_key = None
        self.clear_arrows()
        if self.tracker.board:
            board = self.tracker.board.copy(stack=False); board.turn = turn
            if board.is_valid():
                self.tracker.board = board
                self.tracker.pending = board.fen(); self.tracker.count = 2
                if self.manual_mode:
                    self.analyse()
            else:
                self.tracker = StablePosition()
        self.status.setText('Turno fijado: '+('blancas' if turn else 'negras'))

    def stop(self):
        self.running = False; self.generation += 1
        self.analysis_key = None
        self.start.setText('Iniciar'); self.clear_arrows()

    def toggle(self):
        if self.running:
            self.stop(); self.status.setText('En pausa'); return
        if not self.rect_px:
            self.select_board(); return
        if not self.arrows.capture_safe:
            QMessageBox.warning(self, 'Captura', 'Windows no permite excluir el overlay de la captura. No se iniciará para evitar lecturas contaminadas.'); return
        self.manual_mode = False; self.running = True; self.start.setText('Pausar')
        self.tick()

    def select_board(self):
        self.stop()
        self.arrows.hide()
        self.hide()
        self.close_selectors()
        for screen in QApplication.screens():
            selector = Selector(screen)
            selector.selected.connect(self.board_selected)
            selector.cancelled.connect(self.cancel_selection)
            self.selectors.append(selector); selector.show(); selector.activateWindow()

    def close_selectors(self):
        for selector in self.selectors:
            selector.close(); selector.deleteLater()
        self.selectors = []

    def cancel_selection(self):
        self.close_selectors(); self.show()
        if self.rect_px:
            self.arrows.set_board(self.rect_px, self.white_bottom)

    def board_selected(self, rect):
        self.close_selectors(); self.show(); exclude_capture(self)
        self.reset(); self.rect_px = rect
        self.arrows.set_board(rect, self.white_bottom)
        self.status.setText(f'Tablero seleccionado: {rect[2]} × {rect[2]} px. Pulsa Iniciar.')

    def auto_find(self):
        if self.scan_busy:
            return
        self.stop(); self.reset(); self.scan_busy = True
        self.status.setText('Buscando un tablero 8 × 8 visible…')
        self.submit('find', self.find_worker, self.white_bottom, self.turn)

    def get_classifier(self):
        if self.classifier is None:
            self.classifier = Classifier(ROOT/'models/pieces.onnx')
        return self.classifier

    def find_worker(self, white_bottom, turn):
        import mss
        classifier = self.get_classifier()
        with mss.mss() as capture:
            for monitor in capture.monitors[1:]:
                frame = capture.grab(monitor)
                image = Image.frombytes('RGB', frame.size, frame.rgb)
                for x, y, side in find_boards(image):
                    crop = image.crop((x, y, x+side, y+side))
                    reading = classifier.read(crop, white_bottom, None, turn)
                    if reading.board is not None:
                        return (x+monitor['left'], y+monitor['top'], side)
        raise RuntimeError('No encontré un tablero fiable. Utiliza Seleccionar tablero.')

    def tick(self):
        if not self.running or self.scan_busy or not self.rect_px:
            return
        self.scan_busy = True
        previous = self.tracker.board.copy(stack=False) if self.tracker.board else None
        self.submit('scan', self.scan_worker, self.rect_px, self.white_bottom, previous, self.turn, self.uia.isChecked())

    def scan_worker(self, rect, white_bottom, previous, turn, use_uia):
        import mss
        if use_uia and (self.uia_thread is None or not self.uia_thread.is_alive()):
            results = queue.Queue()
            def uia_read():
                try:
                    from accessibility import read_accessibility
                    results.put(read_accessibility(rect, white_bottom, previous, turn))
                except Exception:
                    results.put(None)
            self.uia_thread = threading.Thread(target=uia_read, daemon=True)
            self.uia_thread.start()
            try:
                result = results.get(timeout=.55)
                if result and result.board is not None:
                    return result
            except queue.Empty:
                pass
        x, y, side = rect
        with mss.mss() as capture:
            frame = capture.grab({'left':x, 'top':y, 'width':side, 'height':side})
            image = Image.frombytes('RGB', frame.size, frame.rgb)
        return self.get_classifier().read(image, white_bottom, previous, turn)

    def result(self, generation, kind, payload):
        if kind in ('scan', 'find'):
            self.scan_busy = False
        if kind == 'engine':
            self.engine_busy = False
        if generation != self.generation:
            if kind == 'engine' and self.manual_mode:
                self.analyse()
            return
        if isinstance(payload, Exception):
            self.status.setText(str(payload))
            if kind == 'engine':
                self.clear_arrows(); self.analysis_key = None
            if kind == 'scan':
                if self.tracker.observe(None) == 'clear':
                    self.clear_arrows(); self.analysis_key = None
            return
        if kind == 'find':
            self.board_selected(payload); return
        if kind == 'scan':
            state = self.tracker.observe(payload.board)
            self.status.setText(f'{payload.diagnostic} · {payload.confidence:.0%}')
            if state == 'clear':
                self.clear_arrows(); self.analysis_key = None
            if state in ('changed', 'same'):
                self.last_valid_at = time.monotonic()
                if state == 'changed':
                    self.generation += 1; self.analysis_key = None
                self.turn = self.tracker.board.turn
                self.analyse()
            return
        if kind == 'engine':
            board, lines = payload
            if self.tracker.board is None or self.tracker.board.fen() != board.fen():
                return
            if not self.manual_mode and (not self.running or self.tracker.invalid or self.tracker.pending != board.fen() or time.monotonic()-self.last_valid_at > 4):
                return
            self.analysis_key = board.fen()
            self.last_lines, self.last_display_board = lines, board
            self.displayed_at = time.monotonic()
            self.refresh_moves()

    def analyse(self):
        board = self.tracker.board
        if board is None or self.engine_busy or self.analysis_key == board.fen():
            return
        self.engine_busy = True
        board = board.copy(stack=False)
        self.submit('engine', lambda: (board, self.engine.analyse(board, 3, 180)))

    def refresh_moves(self):
        if self.last_display_board is None:
            return
        if self.tracker.board is None or self.last_display_board.fen() != self.tracker.board.fen():
            return
        if not self.manual_mode and (not self.running or self.tracker.invalid >= 3 or time.monotonic()-self.last_valid_at > 4):
            return
        lines, board = self.last_lines, self.last_display_board
        if not lines:
            self.clear_arrows(); self.moves_text.setText('Posición terminada: sin jugadas legales'); return
        if self.count.currentIndex() == 0:
            lines = [human_choice(lines, board, self.profile.currentText())]
        self.arrows.moves = lines; self.arrows.update()
        text = []
        for i, line in enumerate(lines, 1):
            move = chess.Move.from_uci(line['move'])
            score = ('Mate '+str(line['mate'])) if line.get('mate') is not None else f'{line["score"]/100:+.2f}'
            text.append(f'{i}. {board.san(move)}   {score}')
        self.moves_text.setText(('Blancas' if board.turn else 'Negras')+' juegan\n'+'\n'.join(text))

    def expire(self):
        if self.running and self.last_valid_at and time.monotonic()-self.last_valid_at > 4:
            self.clear_arrows(); self.analysis_key = None
        if self.running and self.last_display_board and self.tracker.board and self.last_display_board.fen() != self.tracker.board.fen() and time.monotonic()-self.displayed_at > 4:
            self.clear_arrows()

    def import_fen(self):
        text, ok = QInputDialog.getText(self, 'Analizar FEN', 'Pega una posición FEN completa:')
        if not ok:
            return
        try:
            board = chess.Board(text.strip())
            if not board.is_valid():
                raise ValueError('FEN no válida')
        except ValueError as error:
            QMessageBox.warning(self, 'FEN', str(error)); return
        self.stop(); self.reset(); self.manual_mode = True
        self.tracker.board = board; self.turn = board.turn
        self.tracker.pending = board.fen(); self.tracker.count = 2
        self.last_valid_at = time.monotonic()
        self.status.setText('FEN manual: captura en pausa hasta pulsar Iniciar.')
        self.analyse()

    def closeEvent(self, event):
        self.running = False; self.timer.stop(); self.watchdog.stop()
        self.generation += 1
        self.close_selectors(); self.arrows.close()
        self.pool.shutdown(wait=False, cancel_futures=True)
        self.engine.close()
        super().closeEvent(event)


def self_test():
    import numpy as np
    report = {'version':VERSION, 'platform':sys.platform, 'bits':ctypes.sizeof(ctypes.c_void_p)*8}
    classifier = Classifier(ROOT/'models/pieces.onnx')
    raw = classifier.probabilities(Image.new('RGB', (256,256), '#999999'))
    assert raw.shape == (64,13) and np.isfinite(raw).all()
    report['classifier'] = 'ok'
    engine = Engine(ROOT)
    try:
        lines = engine.analyse(chess.Board(), 3, 100)
        assert len(lines) == 3 and len({line['move'] for line in lines}) == 3
        report['engine'] = lines
    finally:
        engine.close()
    app = QApplication.instance() or QApplication([])
    load_system_fonts(app)
    window = Window(); window.show(); app.processEvents()
    window.grab().save(str(ROOT/'self-test-window.png'))
    report['qt_window'] = 'ok'
    window.close(); app.processEvents()
    output = ROOT/'self-test-result.json'
    output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    return 0


def main():
    enable_dpi()
    logdir = Path(os.environ.get('LOCALAPPDATA', str(ROOT)))/'Chess22kOverlay'
    logdir.mkdir(parents=True, exist_ok=True)
    handler = RotatingFileHandler(logdir/'overlay.log', maxBytes=1000000, backupCount=2, encoding='utf-8')
    logging.basicConfig(handlers=[handler], level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    if '--self-test' in sys.argv:
        try:
            return self_test()
        except Exception:
            logging.exception('Self test failed')
            return 1
    app = QApplication(sys.argv)
    load_system_fonts(app)
    app.setApplicationName('Chess22k Overlay')
    window = Window(); window.show(); exclude_capture(window)
    return app.exec()


if __name__ == '__main__':
    sys.exit(main())
