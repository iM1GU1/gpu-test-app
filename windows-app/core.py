"""Desktop adaptation of Chess22k Overlay v15.1. GPL-3.0-or-later."""
from dataclasses import dataclass
import random
import chess
import numpy as np

LABELS = '1RNBQKPrnbqkp'


def screen_square(row, col, white_bottom=True):
    return chess.square(col if white_bottom else 7-col, 7-row if white_bottom else row)


def board_labels(board, white_bottom=True):
    return np.array([LABELS.index(board.piece_at(screen_square(r, c, white_bottom)).symbol())
                     if board.piece_at(screen_square(r, c, white_bottom)) else 0
                     for r in range(8) for c in range(8)])


def validate_pieces(board):
    for color in (chess.WHITE, chess.BLACK):
        if len(board.pieces(chess.KING, color)) != 1:
            return 'Debe haber exactamente un rey por color'
        if chess.popcount(board.occupied_co[color]) > 16:
            return 'Demasiadas piezas de un color'
        if len(board.pieces(chess.PAWN, color)) > 8:
            return 'Demasiados peones'
    if board.pawns & (chess.BB_RANK_1 | chess.BB_RANK_8):
        return 'Peones en la primera o última fila'
    if chess.square_distance(board.king(chess.WHITE), board.king(chess.BLACK)) <= 1:
        return 'Reyes adyacentes'
    return None


def labels_board(labels, white_bottom, turn):
    board = chess.Board(None)
    board.turn = turn
    for i, idx in enumerate(labels):
        if idx:
            board.set_piece_at(screen_square(i//8, i%8, white_bottom), chess.Piece.from_symbol(LABELS[int(idx)]))
    # A screenshot alone cannot prove castling or en-passant rights.
    if board.board_fen() == chess.Board().board_fen():
        board = chess.Board()
        board.turn = turn
    return board


@dataclass
class Reading:
    board: object
    confidence: float
    diagnostic: str


def decode_probabilities(raw, white_bottom=True, previous=None, turn=chess.WHITE):
    raw = np.asarray(raw, dtype=np.float64)
    if raw.shape != (64, 13) or not np.isfinite(raw).all():
        return Reading(None, 0, 'Salida del clasificador inválida')
    top = raw.argmax(axis=1)
    confidence = float(raw.max(axis=1).mean())
    if confidence < .65:
        return Reading(None, confidence, 'Baja confianza visual')
    # Carry forward only legal, visually supported states. This also repairs a
    # duplicated/missing king without inventing arbitrary king placements.
    if previous is not None:
        candidates = [previous.copy(stack=False)]
        for move in list(previous.legal_moves):
            candidate = previous.copy(stack=False)
            candidate.push(move)
            candidates.append(candidate)
        supported = []
        for candidate in candidates:
            expected = board_labels(candidate, white_bottom)
            mismatch = np.flatnonzero(expected != top)
            if len(mismatch) > 2:
                continue
            # Repairs only confuse occupied piece identities, never erase a
            # visibly moved/captured piece or insert one into an empty cell.
            if any((expected[i] == 0) != (top[i] == 0) for i in mismatch):
                continue
            score = float(np.log(np.maximum(raw[np.arange(64), expected], 1e-8)).mean())
            if all(raw[i, expected[i]] >= .015 or LABELS[int(top[i])].lower() == 'k'
                   for i in mismatch):
                supported.append((score, candidate))
        if supported:
            supported.sort(key=lambda p: p[0], reverse=True)
            return Reading(supported[0][1], confidence, 'Visual + continuidad legal')

    # FIDE/World Chess starting-position bootstrap from 32-piece occupancy and
    # color, deliberately stricter than accepting a king forced onto any cell.
    initial = chess.Board()
    expected = board_labels(initial, white_bottom)
    occupied = top != 0
    if previous is None and np.array_equal(occupied, expected != 0):
        correct_color = sum(LABELS[int(top[i])].isupper() == LABELS[int(expected[i])].isupper()
                            for i in np.flatnonzero(occupied))
        if correct_color >= 28:
            top = expected
    board = labels_board(top, white_bottom, turn)
    error = validate_pieces(board)
    if error:
        return Reading(None, confidence, error)
    if previous is not None:
        if board.board_fen() == previous.board_fen():
            return Reading(previous.copy(stack=False), confidence, 'Visual estable')
        for move in list(previous.legal_moves):
            next_board = previous.copy(stack=False)
            next_board.push(move)
            if next_board.board_fen() == board.board_fen():
                return Reading(next_board, confidence, 'Movimiento legal reconocido')
        return Reading(None, confidence, 'Cambio no compatible: espera o pulsa Reiniciar')
    if not board.is_valid():
        return Reading(None, confidence, 'Posición o turno inválido; comprueba Blancas/Negras')
    return Reading(board, confidence, 'Visual válida; sin historial previo')


class StablePosition:
    """Two identical reads commit; three persistent invalid reads clear arrows."""
    def __init__(self):
        self.board = None
        self.pending = None
        self.count = 0
        self.invalid = 0

    def observe(self, board):
        if board is None:
            self.pending, self.count = None, 0
            self.invalid += 1
            if self.invalid >= 5:
                self.board = None
            return 'clear' if self.invalid >= 3 else 'wait'
        self.invalid = 0
        key = board.fen()
        if key == self.pending:
            self.count += 1
        else:
            self.pending, self.count = key, 1
        if self.count < 2:
            return 'wait'
        changed = self.board is None or self.board.fen() != key
        self.board = board.copy(stack=False)
        return 'changed' if changed else 'same'


def line_value(line):
    if line.get('mate') is not None:
        m = line['mate']
        return 100000-m if m >= 0 else -100000+abs(m)
    return line.get('score', -100000)


def human_choice(lines, board, profile, rng=None):
    """Port of v14 HumanProfile: no manufactured large blunders or lost mates."""
    lines = sorted(lines, key=line_value, reverse=True)[:3]
    if not lines:
        return None
    if profile == 'Preciso' or len(lines) == 1 or lines[0].get('mate') is not None:
        return lines[0]
    weights = []
    for i, line in enumerate(lines):
        gap = line_value(lines[0])-line_value(line)
        weight = 1.0 if i == 0 else ((.72 if i == 1 else .48) if gap <= 10 else
                  (.34 if i == 1 else .14) if gap <= 25 else
                  (.075 if i == 1 else .02) if gap <= 50 else 0)
        move = chess.Move.from_uci(line['move'])
        piece = board.piece_at(move.from_square)
        development = piece and piece.piece_type in (chess.KNIGHT, chess.BISHOP) and chess.square_rank(move.from_square) in (0, 7)
        capture, castle = board.is_capture(move), board.is_castling(move)
        if profile == 'Posicional elite':
            weight *= 1.55 if castle else 1.22 if development else .82 if capture else .9 if move.promotion else 1.08
        elif profile == 'Táctico elite':
            weight *= 1.75 if move.promotion else 1.48 if capture else .92 if castle else 1.05 if development else .88
        weights.append(weight)
    return (rng or random).choices(lines, weights=weights, k=1)[0]
