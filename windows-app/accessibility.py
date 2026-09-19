"""Optional fresh Windows UI Automation input; never caches old site positions."""
import re
import time
import numpy as np
from core import LABELS, decode_probabilities, screen_square
import chess

PIECES = {'king':'k', 'rey':'k', 'queen':'q', 'reina':'q', 'dama':'q', 'rook':'r',
          'torre':'r', 'bishop':'b', 'alfil':'b', 'knight':'n', 'caballo':'n', 'pawn':'p', 'peón':'p', 'peon':'p'}


def read_accessibility(rect, white_bottom, previous, turn):
    import uiautomation as auto
    x, y, side = rect
    deadline = time.monotonic()+.45
    with auto.UIAutomationInitializerInThread():
        control = auto.ControlFromPoint(x+side//2, y+side//2)
        for _ in range(4):
            parent = control.GetParentControl()
            if not parent:
                break
            bounds = parent.BoundingRectangle
            if bounds.width() > side*1.5 or bounds.height() > side*1.5:
                break
            control = parent
        stack, squares = [control], {}
        count = 0
        while stack and count < 500 and time.monotonic() < deadline:
            current = stack.pop()
            count += 1
            name = (current.Name or '').lower()
            match = re.search(r'\b([a-h][1-8])\b', name)
            if match:
                bounds = current.BoundingRectangle
                cx, cy = (bounds.left+bounds.right)/2, (bounds.top+bounds.bottom)/2
                if x <= cx < x+side and y <= cy < y+side:
                    symbol = None
                    for word, piece in PIECES.items():
                        if re.search(r'\b'+word+r'\b', name):
                            if any(v in name for v in ('white', 'blanc')):
                                symbol = piece.upper()
                            elif any(v in name for v in ('black', 'negr')):
                                symbol = piece
                            break
                    if symbol is not None or any(v in name for v in ('empty', 'vacía', 'vacia', 'vacío')):
                        squares[chess.parse_square(match[1])] = symbol
            stack.extend(current.GetChildren())
        # Incomplete accessibility trees are not valid board snapshots.
        if len(squares) != 64:
            return None
        raw = np.full((64,13), .0001)
        for r in range(8):
            for c in range(8):
                symbol = squares[screen_square(r,c,white_bottom)]
                raw[r*8+c, LABELS.index(symbol) if symbol else 0] = .9988
        result = decode_probabilities(raw, white_bottom, previous, turn)
        result.diagnostic = 'Accesibilidad Windows: '+result.diagnostic
        return result
