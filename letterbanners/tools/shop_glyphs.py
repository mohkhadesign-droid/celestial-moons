"""The 'shop' banner lettering: an 18 x 39 pixel font in the style of the SHOPS reference banners - very bold
(7-pixel strokes), flat tops, 45-degree chamfered corners, narrow pointed counters.
Glyphs are centre-line paths stroked with an almost-square octagonal pen, rasterised at 8x and reduced to sharp pixels."""
import numpy as np
from PIL import Image, ImageDraw

GW, GH = 18, 39
SS = 8
L, R, T, B, M = 3.5, 14.5, 3.5, 35.5, 20.5
C = 4.0  # skeleton chamfer; with the 7-px pen the outer corner cut is about 7 px like the reference

def _pen(x, y, r, d):
    k = r * 0.35  # small corner cut: stroke ends stay nearly square
    pts = [(-r + k, -r), (r - k, -r), (r, -r + k), (r, r - k), (r - k, r), (-r + k, r), (-r, r - k), (-r, -r + k)]
    return [((x + px) * SS, (y + py) * SS) for px, py in pts]

def _stroke(d, path, w):
    fill = 255
    if w < 0:  # negative width: erase (cut a gap)
        fill, w = 0, -w
    r = w / 2
    for (x0, y0), (x1, y1) in zip(path, path[1:]):
        n = max(2, int(np.hypot(x1 - x0, y1 - y0) * 6))
        for t in np.linspace(0, 1, n):
            d.polygon(_pen(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, r, d), fill=fill)
    if len(path) == 1:
        d.polygon(_pen(path[0][0], path[0][1], r, d), fill=255)

def _rect(d, x0, y0, x1, y1, fill=255):
    d.rectangle([x0 * SS, y0 * SS, x1 * SS - 1, y1 * SS - 1], fill=fill)

O_PATH = [(L + C, T), (R - C, T), (R, T + C), (R, B - C), (R - C, B), (L + C, B), (L, B - C), (L, T + C), (L + C, T)]

# each glyph: list of (path, width) strokes, plus optional extra ops
G = {
    "a": [([(L, B), (L, T + C), (L + C, T), (R - C, T), (R, T + C), (R, B)], 7), ([(L, 23), (R, 23)], 6)],
    "b": [([(L, T), (L, B)], 7), ([(L, T), (R - 3, T), (R, T + 3), (R, M - 4), (R - 3, M - 1), (L, M - 1)], 7),
          ([(L, M - 1), (R - 3, M - 1), (R, M + 2), (R, B - 3), (R - 3, B), (L, B)], 7)],
    "c": [([(R, T + C + 3), (R, T + C), (R - C, T), (L + C, T), (L, T + C), (L, B - C), (L + C, B), (R - C, B), (R, B - C), (R, B - C - 3)], 7)],
    "d": [([(L, T), (R - C, T), (R, T + C), (R, B - C), (R - C, B), (L, B), (L, T)], 7)],
    "e": [([(R, T), (L, T), (L, B), (R, B)], 7), ([(L, M), (R - 2, M)], 7)],
    "f": [([(R, T), (L, T), (L, B)], 7), ([(L, M), (R - 2, M)], 7)],
    "g": [([(R, T + C + 3), (R, T + C), (R - C, T), (L + C, T), (L, T + C), (L, B - C), (L + C, B), (R - C, B), (R, B - C), (R, M + 1), (R - 3, M + 1)], 7)],
    "h": [([(L, T), (L, B)], 7), ([(R, T), (R, B)], 7), ([(L, M), (R, M)], 7)],
    "i": [([(9, T), (9, B)], 7), ([(L + 1, T), (R - 1, T)], 7), ([(L + 1, B), (R - 1, B)], 7)],
    "j": [([(L + 2, T), (R, T)], 7), ([(R, T), (R, B - C), (R - C, B), (L + C, B), (L, B - C), (L, B - C - 3)], 7)],
    "k": [([(L - 0.5, T), (L - 0.5, B)], 6), ([(R, T), (R, T + 1), (L + 4, M - 1.5)], 5), ([(L + 6, M - 2), (R, B - 4), (R, B)], 5.5)],
    "l": [([(L, T), (L, B), (R, B)], 7)],
    "o": [(O_PATH, 7)],
    "p": [([(L, B), (L, T), (R - C, T), (R, T + C), (R, M + 1 - C), (R - C, M + 1), (L, M + 1)], 7)],
    "q": [([(L + C, T), (R - C, T), (R, T + C), (R, B - 3 - C), (R - C, B - 3), (L + C, B - 3), (L, B - 3 - C), (L, T + C), (L + C, T)], 7),
          ([(R - C, B - 3), (R - 1, B - 1)], -2.5), ([(9.5, B - 9), (R + 1.5, B + 1.5)], 5)],
    "r": [([(L, B), (L, T), (R - C, T), (R, T + C), (R, M + 1 - C), (R - C, M + 1), (L, M + 1)], 7),
          ([(R - 3, M + 1), (R, M + 4), (R, B)], 7)],
    "s": [([(R, T + C + 3), (R, T + C), (R - C, T), (L + C, T), (L, T + C), (L, T + C + 4), (R, B - C - 4), (R, B - C), (R - C, B), (L + C, B), (L, B - C), (L, B - C - 3)], 7)],
    "t": [([(L - 1, T), (R + 1, T)], 7), ([(9, T), (9, B)], 7)],
    "u": [([(L, T), (L, B - C), (L + C, B), (R - C, B), (R, B - C), (R, T)], 7)],
    "v": [([(L, T), (L, B - 14), (9, B)], 6), ([(R, T), (R, B - 14), (9, B)], 6)],
    "x": [([(L, T), (L, T + 4), (R, B - 4), (R, B)], 6), ([(R, T), (R, T + 4), (L, B - 4), (L, B)], 6)],
    "y": [([(L, T), (L, T + 6), (9, M - 1)], 6.5), ([(R, T), (R, T + 6), (9, M - 1)], 6.5), ([(9, M - 1), (9, B)], 7)],
    "z": [([(L, T), (R, T), (R, T + 4), (L, B - 4), (L, B), (R, B)], 7)],
    "0": [([(L + 2, T), (R - 2, T), (R, T + 2), (R, B - 2), (R - 2, B), (L + 2, B), (L, B - 2), (L, T + 2), (L + 2, T)], 7)],
    "1": [([(L, T + 5), (L + 2, T + 5), (9.5, T)], 6), ([(9.5, T), (9.5, B)], 7), ([(L, B), (R, B)], 7)],
    "2": [([(L, T + C + 3), (L, T + C), (L + C, T), (R - C, T), (R, T + C), (R, M - 3), (L, B - 4), (L, B), (R, B)], 7)],
    "3": [([(L, T), (R - C, T), (R, T + C), (R, M - 4), (R - 3, M - 1), (L + 3, M - 1)], 7),
          ([(R - 3, M - 1), (R, M + 2), (R, B - C), (R - C, B), (L, B)], 7)],
    "4": [([(11, T), (L, M + 3), (L, 26.5), (R + 0.5, 26.5)], 7), ([(11, T), (11, B)], 7)],
    "5": [([(R, T), (L, T), (L, M - 1), (R - C, M - 1), (R, M - 1 + C), (R, B - C), (R - C, B), (L, B)], 7)],
    "6": [([(R, T + 1), (R - C, T), (L + C, T), (L, T + C), (L, B - C), (L + C, B), (R - C, B), (R, B - C), (R, M + C), (R - C, M), (L, M)], 7)],
    "7": [([(L, T), (R, T), (R, T + 6), (8, B)], 7)],
    "8": [([(L + 3, T), (R - 3, T), (R, T + 3), (R, M - 4), (R - 3, M - 1), (L + 3, M - 1), (L, M - 4), (L, T + 3), (L + 3, T)], 6.5),
          ([(L + 3, M - 1), (R - 3, M - 1), (R, M + 2), (R, B - 3), (R - 3, B), (L + 3, B), (L, B - 3), (L, M + 2), (L + 3, M - 1)], 6.5)],
    "9": "rot6",
    "excl": [([(9, T), (9, B - 10)], 7)],
    "question": [([(L, T + C + 2), (L, T + C), (L + C, T), (R - C, T), (R, T + C), (R, M - 4), (9, M), (9, B - 10)], 7)],
    "dash": [([(L, M), (R, M)], 7)],
}

# S, H, O and P transcribed pixel for pixel from the SHOPS reference banners (the rest of the set follows them)
EXACT = {
    "s": [
        ".......#####......",
        ".....########.....",
        "....##########....",
        "...############...",
        "..##############..",
        ".################.",
        ".#################",
        ".#################",
        ".#######...#######",
        ".######....#######",
        ".######.....#####.",
        ".######......###..",
        ".#######.....##...",
        ".########.....#...",
        "..########........",
        "..#########.......",
        "...#########......",
        "....#########.....",
        ".....#########....",
        "......#########...",
        ".......#########..",
        "........#########.",
        ".........#########",
        "..........########",
        "...........#######",
        "....#......#######",
        "...###......######",
        "..####......######",
        "..#####.....######",
        ".#######...#######",
        ".########.########",
        ".#################",
        ".################.",
        "..##############..",
        "...############...",
        "....##########....",
        ".....########.....",
        "......######......",
        ".......#####......",
    ],
    "h": [
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "##################",
        "##################",
        "##################",
        "##################",
        "##################",
        "##################",
        "##################",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
    ],
    "o": [
        ".......####.......",
        "......######......",
        ".....########.....",
        "....##########....",
        "...############...",
        "...############...",
        "..##############..",
        "##################",
        "########..########",
        "########..########",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "########..########",
        "########..########",
        "##################",
        "##################",
        ".################.",
        "..##############..",
        "...############...",
        "....##########....",
        ".....########.....",
        "......######......",
    ],
    "p": [
        "##############....",
        "###############...",
        "################..",
        "#################.",
        "##################",
        "##################",
        "##################",
        "#######...########",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#######....#######",
        "#########.########",
        "#################.",
        "#################.",
        "################..",
        "###############...",
        "##############....",
        "#############.....",
        "############......",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
        "#######...........",
    ],
}

def glyph(gid):
    """Boolean mask GH x GW for a glyph id (letters lower-case, digits, excl/question/dash/amp/blank)."""
    if gid == "blank":
        return np.zeros((GH, GW), bool)
    if gid in EXACT:
        return np.array([[c == "#" for c in r] for r in EXACT[gid]])
    if gid == "9":
        return glyph("6")[::-1, ::-1].copy()
    if gid == "amp":
        return _amp()
    if gid in ("m", "n", "w"):
        return _mnw(gid)
    img = Image.new("L", (GW * SS, GH * SS), 0)
    d = ImageDraw.Draw(img)
    for path, w in G[gid]:
        if path == "poly":
            d.polygon([(x * SS, y * SS) for x, y in w], fill=255)
        else:
            _stroke(d, path, w)
    if gid == "0":  # dotted zero, so it never looks like O
        pass
    if gid in ("excl", "question"):  # the dot
        _rect(d, 5.5, B - 3.5, 12.5, B + 3.5)
    m = np.asarray(img.resize((GW, GH), Image.BOX)) > 127
    if gid == "0":
        m[17:23, 8:10] = True
    return m

def _mnw(gid):
    """M, N, W drawn pixel by pixel: 5-px stems so the diagonals have room (at 18 px wide a 7-px stem leaves none)."""
    m = np.zeros((GH, GW), bool)
    m[:, 0:5] = True
    m[:, 13:18] = True
    if gid == "n":
        for y in range(GH):
            c = 5 + round(y * 6 / (GH - 1))
            m[y, c:min(13, c + 3)] = True
        return m
    for y in range(12):  # M: arms from the tops of the stems down to a point in the middle
        k = min(3, y // 3)
        m[y, 5 + k:7 + k] = True
        m[y, 11 - k:13 - k] = True
    m[12:14, 8:10] = True
    return m[::-1].copy() if gid == "w" else m

def _amp():
    """& from DejaVu Sans Bold, thickened to the stroke weight of the set."""
    from PIL import ImageFont
    import os
    path = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    if not os.path.exists(path):
        import subprocess
        path = subprocess.check_output(["fc-match", "-f", "%{file}", "DejaVu Sans:style=Bold"]).decode()
    f = ImageFont.truetype(path, 600)
    im = Image.new("L", (900, 900), 0)
    ImageDraw.Draw(im).text((100, 50), "&", font=f, fill=255)
    im = im.crop(im.getbbox()).resize((GW, GH), Image.LANCZOS)
    m = np.asarray(im) > 70
    p = np.pad(m, 1)
    return m | p[:-2, 1:-1] | p[1:-1, :-2]

ORDER = [c for c in "abcdefghijklmnopqrstuvwxyz0123456789"] + ["amp", "excl", "question", "dash", "blank"]

if __name__ == "__main__":
    import sys
    rows = [ORDER[i:i + 14] for i in range(0, len(ORDER), 14)]
    out = Image.new("RGB", (14 * 22 * 4, len(rows) * 43 * 4), (20, 30, 120))
    for r, row in enumerate(rows):
        for c, g in enumerate(row):
            m = glyph(g)
            a = np.zeros((GH, GW, 3), np.uint8); a[:] = (20, 50, 170); a[m] = (246, 232, 216)
            out.paste(Image.fromarray(a).resize((GW * 4, GH * 4), Image.NEAREST), (c * 22 * 4 + 8, r * 43 * 4 + 8))
    out.save(sys.argv[1])
