"""Classifier and coarse-to-fine board search derived from Android v5/v15."""
from pathlib import Path
import numpy as np
from PIL import Image
from core import decode_probabilities


class Classifier:
    def __init__(self, model):
        import onnxruntime as ort
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        self.session = ort.InferenceSession(str(model), sess_options=options, providers=['CPUExecutionProvider'])
        self.input = self.session.get_inputs()[0]

    def probabilities(self, image):
        rgb = np.asarray(image.convert('RGB').resize((256, 256), Image.Resampling.BILINEAR), dtype=np.float32)
        gray = (rgb @ np.array([.2989, .5870, .1140], dtype=np.float32))/255
        tiles = gray.reshape(8, 32, 8, 32).transpose(0, 2, 1, 3).reshape(64, 32, 32, 1)
        # Exported model can have a fixed batch size of one.
        return np.vstack([self.session.run(None, {self.input.name: tile[None]})[0].reshape(1, 13) for tile in tiles])

    def read(self, image, white_bottom, previous, turn):
        return decode_probabilities(self.probabilities(image), white_bottom, previous, turn)


def checker_scores(image, candidates):
    """Vectorized Android four-corner checker-color score (x,y,side)."""
    rgb = np.asarray(image.convert('RGB'), dtype=np.float32)
    candidates = np.asarray(candidates, dtype=np.float32)
    if not len(candidates):
        return np.array([])
    rows, cols = np.indices((8, 8))
    colors = np.zeros((len(candidates), 64, 3), dtype=np.float32)
    for ox in (.14, .86):
        for oy in (.14, .86):
            xs = (candidates[:, 0, None] + (cols.ravel()+ox)[None]*candidates[:, 2, None]/8).astype(int)
            ys = (candidates[:, 1, None] + (rows.ravel()+oy)[None]*candidates[:, 2, None]/8).astype(int)
            colors += rgb[np.clip(ys, 0, rgb.shape[0]-1), np.clip(xs, 0, rgb.shape[1]-1)]/4
    parity = ((rows+cols).ravel() % 2).astype(bool)
    a, b = colors[:, ~parity].mean(axis=1), colors[:, parity].mean(axis=1)
    own = np.where(parity[None, :, None], b[:, None], a[:, None])
    other = np.where(parity[None, :, None], a[:, None], b[:, None])
    distance = np.linalg.norm(colors-own, axis=2)
    contrast = np.linalg.norm(a-b, axis=1)
    fit = (distance+5 < np.linalg.norm(colors-other, axis=2)).mean(axis=1)
    scores = contrast/255*2.2 + fit*1.35 - distance.mean(axis=1)/255*1.6
    scores[contrast < 18] = -10
    return scores


def find_boards(image):
    scale = min(1., 600/min(image.size))
    small = image.resize(tuple(round(n*scale) for n in image.size), Image.Resampling.BILINEAR)
    w, h = small.size
    candidates = []
    for cell in range(18, min(w, h)//8+1, 3):
        side = cell*8
        step = max(4, cell//4)
        candidates += [(x, y, side) for y in range(0, h-side+1, step) for x in range(0, w-side+1, step)]
    candidates = np.asarray(candidates)
    if not len(candidates):
        return []
    scores = np.concatenate([checker_scores(small, group) for group in np.array_split(candidates, max(1, len(candidates)//3000))])
    refined = []
    seeds = []
    for idx in np.argsort(scores)[::-1]:
        candidate = candidates[idx]
        if all(np.abs(candidate-seed).sum() > candidate[2]*.035 for seed in seeds):
            seeds.append(candidate)
        if len(seeds) == 12:
            break
    for x, y, side in seeds:
        step = max(2, side//96)
        for ds in range(-20, 21, 4):
            s = side+ds
            for yy in range(max(0, y-side//20), min(h-s, y+side//20)+1, step):
                for xx in range(max(0, x-side//20), min(w-s, x+side//20)+1, step):
                    refined.append((xx, yy, s))
    if not refined:
        return []
    scores = checker_scores(small, refined)
    selected = []
    for i in np.argsort(scores)[::-1]:
        if scores[i] < 1.25:
            break
        rect = tuple(round(v/scale) for v in refined[i])
        if all(abs(rect[0]-r[0])+abs(rect[1]-r[1])+abs(rect[2]-r[2]) > rect[2]*.14 for r in selected):
            selected.append(rect)
        if len(selected) == 5:
            break
    return selected
