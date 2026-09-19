import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import unittest
import chess
try:
    from PySide6.QtWidgets import QApplication
    from app import Window
except ImportError:
    QApplication = None


@unittest.skipIf(QApplication is None, 'Qt only available on the Windows build runner')
class UiStateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.application = QApplication.instance() or QApplication([])

    def setUp(self):
        import time
        self.window = Window()
        self.window.timer.stop(); self.window.watchdog.stop()
        self.window.analyse = lambda: None
        self.window.tracker.board = chess.Board()
        self.window.last_display_board = chess.Board()
        self.window.tracker.pending = chess.STARTING_FEN
        self.window.tracker.count = 2
        self.window.last_lines = [{'move':'e2e4','score':20,'mate':None}]
        self.window.last_valid_at = time.monotonic()
        self.window.running = True

    def tearDown(self):
        self.window.close()
        self.application.processEvents()

    def test_profile_cannot_revive_expired_arrows(self):
        self.window.refresh_moves()
        self.assertTrue(self.window.arrows.moves)
        self.window.tracker.invalid = 3
        self.window.clear_arrows(); self.window.refresh_moves()
        self.assertFalse(self.window.arrows.moves)

    def test_stale_async_result_ignored(self):
        self.window.result(-1,'engine',(chess.Board(),self.window.last_lines))
        self.assertFalse(self.window.arrows.moves)

    def test_pause_removes_arrows(self):
        self.window.refresh_moves()
        self.window.stop(); self.window.refresh_moves()
        self.assertFalse(self.window.arrows.moves)
        self.assertFalse(self.window.running)

    def test_engine_failure_removes_arrows(self):
        self.window.refresh_moves()
        self.window.result(self.window.generation,'engine',TimeoutError('test'))
        self.assertFalse(self.window.arrows.moves)

    def test_watchdog_removes_stale_arrows(self):
        import time
        self.window.refresh_moves()
        self.window.last_valid_at = time.monotonic()-10
        self.window.expire(); self.window.refresh_moves()
        self.assertFalse(self.window.arrows.moves)

    def test_reset_forgets_previous_site(self):
        self.window.turn = chess.BLACK
        self.window.reset()
        self.assertIsNone(self.window.tracker.board)
        self.assertFalse(self.window.arrows.moves)
        self.assertEqual(self.window.turn, chess.WHITE)


if __name__ == '__main__':
    unittest.main()
