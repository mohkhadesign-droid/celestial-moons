"""Atmosphere textures: particle sprite sheet, moon halo, and Revealing eye 'look' frames (iris shifted 1 texel)."""
import numpy as np, math
from PIL import Image, ImageDraw, ImageFilter
ROOT = 'src/main/resources/assets/ecmoons/textures/'

# ---- sprites: 8 x 32px cells in a 256x32 sheet, white (tinted at render time) ----
S = 32
sheet = Image.new('RGBA', (S * 8, S), (0, 0, 0, 0))
def cell(i):
    return Image.new('RGBA', (S, S), (0, 0, 0, 0))
def radial(power, core=0.0):
    yy, xx = np.mgrid[0:S, 0:S]
    r = np.hypot(xx - (S - 1) / 2, yy - (S - 1) / 2) / (S / 2)
    a = np.clip(1 - r, 0, 1) ** power
    if core: a = np.maximum(a, (r < core) * 1.0)
    img = np.zeros((S, S, 4), np.uint8); img[..., :3] = 255; img[..., 3] = (a * 255).astype(np.uint8)
    return Image.fromarray(img, 'RGBA')
# 0 soft glow dot
sheet.paste(radial(2.2), (0, 0))
# 1 ice crystal (6 arms)
c = cell(1); d = ImageDraw.Draw(c)
for k in range(6):
    ang = k * math.pi / 3; x2 = 16 + 13 * math.cos(ang); y2 = 16 + 13 * math.sin(ang)
    d.line([16, 16, x2, y2], fill=(255, 255, 255, 255), width=2)
    for t in (0.5, 0.75):
        bx, by = 16 + 13 * t * math.cos(ang), 16 + 13 * t * math.sin(ang)
        for s in (-1, 1):
            a2 = ang + s * 0.7
            d.line([bx, by, bx + 4 * math.cos(a2), by + 4 * math.sin(a2)], fill=(255, 255, 255, 220), width=1)
c = Image.alpha_composite(c.filter(ImageFilter.GaussianBlur(0.6)), c)
sheet.paste(c, (S, 0))
# 2 ember: bright core + glow
sheet.paste(radial(3.5, core=0.18), (2 * S, 0))
# 3,4 runes
for i, segs in ((3, [((8, 6), (24, 6)), ((16, 6), (16, 26)), ((8, 26), (24, 16)), ((10, 14), (22, 14))]),
                (4, [((8, 8), (24, 24)), ((24, 8), (8, 24)), ((16, 4), (16, 28)), ((6, 16), (12, 16)), ((20, 16), (26, 16))])):
    c = cell(i); d = ImageDraw.Draw(c)
    for (a_, b_) in segs: d.line([a_, b_], fill=(255, 255, 255, 255), width=2)
    d.ellipse([4, 4, 28, 28], outline=(255, 255, 255, 140), width=1)
    g = c.filter(ImageFilter.GaussianBlur(2.0))
    sheet.paste(Image.alpha_composite(g, c), (i * S, 0))
# 5 sparkle (4-point star)
c = cell(5); arr = np.zeros((S, S, 4), np.uint8); arr[..., :3] = 255
yy, xx = np.mgrid[0:S, 0:S]; dx = np.abs(xx - 15.5); dy = np.abs(yy - 15.5)
star = np.clip(1 - (np.minimum(dx, dy) / 1.6) - np.maximum(dx, dy) / 16, 0, 1) + np.clip(1 - np.hypot(dx, dy) / 6, 0, 1) * 0.6
arr[..., 3] = (np.clip(star, 0, 1) * 255).astype(np.uint8)
sheet.paste(Image.fromarray(arr, 'RGBA'), (5 * S, 0))
# 6 wisp: soft wide blob (noise)
rng = np.random.default_rng(3)
blob = np.zeros((S, S)); 
for _ in range(9):
    cx, cy, r = rng.uniform(8, 24), rng.uniform(11, 21), rng.uniform(5, 10)
    blob += np.clip(1 - np.hypot(xx - cx, (yy - cy) * 1.6) / r, 0, 1)
blob = np.clip(blob / blob.max(), 0, 1) ** 1.4
arr = np.zeros((S, S, 4), np.uint8); arr[..., :3] = 255; arr[..., 3] = (blob * 255).astype(np.uint8)
sheet.paste(Image.fromarray(arr, 'RGBA').filter(ImageFilter.GaussianBlur(1.5)), (6 * S, 0))
# 7 silk strand: thin curved fibre
c = cell(7); d = ImageDraw.Draw(c)
pts = [(4 + t * 24, 16 + 6 * math.sin(t * math.pi * 1.4)) for t in np.linspace(0, 1, 30)]
d.line(pts, fill=(255, 255, 255, 230), width=1)
sheet.paste(Image.alpha_composite(c.filter(ImageFilter.GaussianBlur(1.2)), c), (7 * S, 0))
sheet.save(ROOT + 'atmosphere/sprites.png')

# ---- halo: 64x64 radial ring/glow ----
H = 64
yy, xx = np.mgrid[0:H, 0:H]; r = np.hypot(xx - 31.5, yy - 31.5) / 32
glow = np.clip(1 - r, 0, 1) ** 2.0 * 0.8 + np.exp(-((r - 0.42) / 0.07) ** 2) * 0.5
arr = np.zeros((H, H, 4), np.uint8); arr[..., :3] = 255; arr[..., 3] = (np.clip(glow, 0, 1) * 255).astype(np.uint8)
Image.fromarray(arr, 'RGBA').save(ROOT + 'atmosphere/halo.png')

# ---- Revealing eye look frames: iris+pupil shifted inside the eye ----
src = np.asarray(Image.open(ROOT + 'environment/revealing_moon_phases.png').convert('RGBA')).copy()
CX, CY, R = 15.6, 16.2, 6.2  # iris region inside the 32px moon (cell-local)
for name, (sx, sy) in {'look_l': (-1, 0), 'look_r': (1, 0), 'look_u': (0, -1)}.items():
    out = src.copy()
    for cell_i in range(8):
        ox, oy = (cell_i % 4) * 128 + 48, (cell_i // 4) * 128 + 48
        block = src[oy:oy + 32, ox:ox + 32].copy()
        res = block.copy()
        for y in range(32):
            for x in range(32):
                if math.hypot(x - CX, y - CY) <= R:
                    fx, fy = x - sx, y - sy  # the trailing rim pulls in eye white from just outside the iris
                    res[y, x] = block[fy, fx]
        out[oy:oy + 32, ox:ox + 32] = res
    Image.fromarray(out, 'RGBA').save(ROOT + 'environment/revealing_moon_' + name + '.png')
print('ok')
