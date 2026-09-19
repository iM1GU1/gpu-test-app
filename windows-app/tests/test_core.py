import random
import unittest
import chess
import numpy as np
from PIL import Image, ImageDraw
from core import (board_labels, screen_square, decode_probabilities, StablePosition,
                  validate_pieces, human_choice)
from vision import find_boards, checker_scores


def probabilities(board, orientation=True):
    raw = np.full((64,13), .0001)
    raw[np.arange(64), board_labels(board, orientation)] = .9988
    return raw


class CoreTests(unittest.TestCase):
    def test_initial(self):
        result = decode_probabilities(probabilities(chess.Board()))
        self.assertEqual(result.board.fen(), chess.STARTING_FEN)

    def test_flipped(self):
        result = decode_probabilities(probabilities(chess.Board(), False), False)
        self.assertEqual(result.board.fen(), chess.STARTING_FEN)
        self.assertEqual(screen_square(0,0,False), chess.H1)

    def test_move_updates_turn_and_en_passant(self):
        previous = chess.Board(); after = previous.copy(); after.push_uci('e2e4')
        result = decode_probabilities(probabilities(after), previous=previous)
        self.assertEqual(result.board.fen(en_passant='fen'), after.fen(en_passant='fen'))
        self.assertEqual(result.board.turn, chess.BLACK)

    def test_castling(self):
        previous = chess.Board('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1')
        after = previous.copy(); after.push_uci('e1g1')
        result = decode_probabilities(probabilities(after), previous=previous)
        self.assertEqual(result.board.fen(), after.fen())

    def test_en_passant_capture(self):
        previous = chess.Board('4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1')
        after = previous.copy(); after.push_uci('e5d6')
        result = decode_probabilities(probabilities(after), previous=previous)
        self.assertEqual(result.board.fen(), after.fen())

    def test_promotion(self):
        previous = chess.Board('4k3/P7/8/8/8/8/8/4K3 w - - 0 1')
        after = previous.copy(); after.push_uci('a7a8q')
        result = decode_probabilities(probabilities(after), previous=previous)
        self.assertEqual(result.board.fen(), after.fen())

    def test_no_king_is_rejected(self):
        board = chess.Board(); board.remove_piece_at(chess.E1); board.remove_piece_at(chess.A2)
        self.assertIsNone(decode_probabilities(probabilities(board)).board)

    def test_duplicate_king_is_rejected_without_context(self):
        board = chess.Board(); board.set_piece_at(chess.D4,chess.Piece(chess.KING,chess.WHITE))
        self.assertIsNone(decode_probabilities(probabilities(board)).board)

    def test_initial_fide_king_repair(self):
        raw = probabilities(chess.Board())
        raw[60,:] = .0001; raw[60,4] = .9988
        self.assertEqual(decode_probabilities(raw).board.fen(), chess.STARTING_FEN)

    def test_temporal_duplicate_king_repair(self):
        previous = chess.Board(); previous.push_uci('e2e4')
        raw = probabilities(previous)
        raw[59,:] = .0001; raw[59,5] = .96; raw[59,4] = .03
        result = decode_probabilities(raw,previous=previous)
        self.assertEqual(result.board.fen(), previous.fen())

    def test_dont_reuse_another_board(self):
        previous = chess.Board()
        other = chess.Board('4k3/8/8/8/8/8/8/4K3 w - - 0 1')
        self.assertIsNone(decode_probabilities(probabilities(other), previous=previous).board)

    def test_dont_invent_missing_occupancy(self):
        previous = chess.Board(); raw = probabilities(previous)
        raw[60,:] = .0001; raw[60,0] = .9988
        self.assertIsNone(decode_probabilities(raw,previous=previous).board)

    def test_rights_not_invented_midgame(self):
        board = chess.Board(); board.push_uci('e2e4')
        result = decode_probabilities(probabilities(board), turn=chess.BLACK)
        self.assertEqual(result.board.castling_rights, 0)

    def test_invalid_shape_and_confidence(self):
        self.assertIsNone(decode_probabilities(np.zeros((64,12))).board)
        self.assertIsNone(decode_probabilities(np.ones((64,13))/13).board)

    def test_stable_and_expiry(self):
        tracker = StablePosition(); board = chess.Board()
        self.assertEqual(tracker.observe(board), 'wait')
        self.assertEqual(tracker.observe(board), 'changed')
        self.assertEqual(tracker.observe(board), 'same')
        self.assertEqual(tracker.observe(None), 'wait')
        self.assertEqual(tracker.observe(None), 'wait')
        self.assertEqual(tracker.observe(None), 'clear')
        tracker.observe(None); tracker.observe(None)
        self.assertIsNone(tracker.board)

    def test_human_avoids_large_blunders(self):
        lines = [{'move':'e2e4','score':100,'mate':None}, {'move':'d2d4','score':-200,'mate':None}]
        for name in ['Universal elite','Posicional elite','Táctico elite']:
            self.assertEqual(human_choice(lines,chess.Board(),name,random.Random(0)),lines[0])

    def test_human_keeps_mate(self):
        lines = [{'move':'e2e4','score':32000,'mate':3}, {'move':'d2d4','score':100,'mate':None}]
        self.assertEqual(human_choice(lines,chess.Board(),'Táctico elite'),lines[0])

    def test_adjacent_kings_rejected(self):
        self.assertIsNotNone(validate_pieces(chess.Board('8/8/8/8/8/8/4k3/4K3 w - - 0 1')))

    def test_checker_geometry(self):
        image = Image.new('RGB',(900,700),'#151c28'); draw=ImageDraw.Draw(image)
        x,y,cell = 140,90,64
        for r in range(8):
            for c in range(8):
                draw.rectangle((x+c*cell,y+r*cell,x+(c+1)*cell-1,y+(r+1)*cell-1),fill='#e5d3b0' if (r+c)%2 else '#71945b')
        self.assertGreater(checker_scores(image,[(x,y,512)])[0],1.25)
        results=find_boards(image)
        self.assertTrue(any(abs(a-x)<14 and abs(b-y)<14 and abs(s-512)<20 for a,b,s in results),results)


if __name__ == '__main__':
    unittest.main()
