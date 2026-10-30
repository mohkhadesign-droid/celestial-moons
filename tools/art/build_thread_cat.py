"""Thread Moon cat: the animated overlay drawn on the yarn moon.

Source: thread_cat_reference.png - the 252 frames (80 ms each) of the supplied Thread Moon cat animation at its art
resolution (the GIF is drawn at 4x; one art pixel = 4 GIF pixels), rows 0-71 only (everything above the yarn), 14
frames per row, 192 px wide each. The yarn in the reference is 99 art px wide with its top at art y 68; on the
existing moon texture (32 px yarn, top at cell y 48 of a 128 px phase cell) that is a scale of 32/99.

The overlay is drawn at twice the moon's pixel density (one overlay texel = half a moon texel) so the cat stays
readable on a 32 px moon without changing the moon texture itself. Only the cat, its tugged strands and loose fibres
are kept (the reference's sky, stars and yarn body are keyed out).

Output: textures/environment/thread_cat.png (atlas of 100 x 46 cells, 10 per row) and the frame sequences printed as
Java (ThreadMoonCat.SEQ_*).
Usage: python3 build_thread_cat.py <assets/ecmoons> [preview.png]"""
import os, sys
import numpy as np
from PIL import Image
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'thread_cat_reference.png')
FW, FH, PER_ROW, NFRAMES = 192, 72, 14, 252
AX0, AX1, AY0, AY1 = 24, 176, 0, 68           # art region kept (above the yarn)
YARN_X0, YARN_TOP, YARN_W = 40, 68, 99         # yarn in the reference (art px)
MOON_X0, MOON_TOP, MOON_W = 48, 48, 32         # yarn on the moon texture (cell px)
DENSITY = 2
SCALE = MOON_W / YARN_W * DENSITY             # art px -> overlay texels
CW, CH, COLS = 100, 46, 10                    # atlas cell (with a 1 px gutter)
BG = np.array([6, 14, 27.])

# frame sequences (indices into the 80 ms reference)
IDLE = list(range(0, 16)) + list(range(14, 0, -1))                       # quiet loop: stands, tail sways
LIFELINE = list(range(102, 110)) + list(range(108, 101, -1))             # kneads the yarn with alternating paws
GUARD = list(range(42, 55))                                              # digs its paws in and braces backwards
KNOT = list(range(108, 141, 2)) + [132, 120, 108, 102]                   # hooks two ends, bites and tugs
LAST = list(range(153, 178, 3)) + list(range(180, 196, 2)) + list(range(198, 232, 3))  # sharp tug, tumble, fibres

def frames():
    sheet = np.asarray(Image.open(SRC).convert('RGB')).astype(float)
    out = []
    for i in range(NFRAMES):
        r, c = divmod(i, PER_ROW)
        out.append(sheet[r * FH:(r + 1) * FH, c * FW:(c + 1) * FW])
    return out

def foreground(f):
    """Cat, strands and fibres; not the sky, the stars or the top edge of the reference's yarn."""
    d = np.abs(f - BG).sum(2)
    m = d > 14
    pink = (f[..., 0] > 110) & (f[..., 0] > f[..., 2] + 15)
    m[64:] &= ~pink[64:]                     # the reference's own yarn edge (the moon has its own yarn)
    lab, n = ndimage.label(m)
    keep = np.zeros_like(m)
    for i in range(1, n + 1):
        comp = lab == i
        s = comp.sum()
        col = f[comp].mean(0)
        pinkish = col[0] > col[2] + 15 and col[0] > 90
        starlike = not pinkish and col.mean() > 95 and s < 24
        if (s >= 8 or pinkish) and not starlike:
            keep |= comp
    return keep

def downscale(rgb, mask):
    h, w = mask.shape
    H, W = int(round(h * SCALE)), int(round(w * SCALE))
    out = np.zeros((H, W, 4))
    for y in range(H):
        y0 = int(y / SCALE); y1 = max(int((y + 1) / SCALE), y0 + 1)
        for x in range(W):
            x0 = int(x / SCALE); x1 = max(int((x + 1) / SCALE), x0 + 1)
            m = mask[y0:y1, x0:x1]
            if m.mean() < 0.4:
                continue
            c = rgb[y0:y1, x0:x1][m]
            eye = (c[:, 0] > 180) & (c[:, 1] > 170) & (c[:, 2] < 120)
            col = c[eye].mean(0) if eye.any() else np.median(c, 0)
            out[y, x, :3] = col
            out[y, x, 3] = 255
    return out

def build(assets, preview=None):
    fr = frames()
    used = sorted(set(IDLE + LIFELINE + GUARD + KNOT + LAST))
    index = {f: i for i, f in enumerate(used)}
    rows = (len(used) + COLS - 1) // COLS
    atlas = np.zeros((rows * CH, COLS * CW, 4))
    tiles = {}
    for f in used:
        a = fr[f][AY0:AY1, AX0:AX1]
        t = downscale(a, foreground(a))
        tiles[f] = t
        i = index[f]
        r, c = divmod(i, COLS)
        atlas[r * CH + 1:r * CH + 1 + t.shape[0], c * CW + 1:c * CW + 1 + t.shape[1]] = t
    out = os.path.join(assets, 'textures/environment/thread_cat.png')
    Image.fromarray(np.clip(atlas, 0, 255).astype(np.uint8), 'RGBA').save(out)
    th, tw = next(iter(tiles.values())).shape[:2]
    # where the strip sits on a 128 px moon cell
    cx = MOON_X0 + (AX0 - YARN_X0) * MOON_W / YARN_W
    cy = MOON_TOP - (YARN_TOP - AY0) * MOON_W / YARN_W
    print('atlas', atlas.shape[1], 'x', atlas.shape[0], 'tile', tw, 'x', th, 'frames', len(used))
    print('strip on cell: x %.3f y %.3f w %.3f h %.3f' % (cx, cy, tw / DENSITY, th / DENSITY))
    for name, seq in (('IDLE', IDLE), ('LIFELINE', LIFELINE), ('GUARD', GUARD), ('KNOT', KNOT), ('LAST', LAST)):
        print('static final int[] SEQ_%s = {%s};' % (name, ', '.join(str(index[f]) for f in seq)))
    if preview:
        moon = np.asarray(Image.open(os.path.join(assets, 'textures/environment/thread_moon_phases.png')).convert('RGB')).astype(float)[:128, :128]
        big = np.kron(moon, np.ones((DENSITY, DENSITY, 1)))
        row = []
        for f in (IDLE[0], LIFELINE[7], GUARD[6], KNOT[9], LAST[4], LAST[12], LAST[20]):
            b = big.copy(); t = tiles[f]
            x0, y0 = int(round(cx * DENSITY)), int(round(cy * DENSITY))
            al = t[..., 3:4] / 255
            reg = b[y0:y0 + t.shape[0], x0:x0 + t.shape[1]]
            b[y0:y0 + t.shape[0], x0:x0 + t.shape[1]] = reg * (1 - al) + t[..., :3] * al
            row.append(np.kron(b[40:180, 60:200], np.ones((3, 3, 1))))
        Image.fromarray(np.clip(np.concatenate(row, 1), 0, 255).astype(np.uint8)).save(preview)

if __name__ == '__main__':
    build(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
