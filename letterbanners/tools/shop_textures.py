"""Shop banners (the SHOPS / market references): royal, crimson and azure, each with A-Z, 0-9, & ! ? -, a blank
banner and the market banner (striped canopy over a closed crate/cube).

Letter banner face: 32 x 96 px (cloth 30 px wide, rows 0-77 with its pointed hem; the rest is transparent and the
model's tassel hangs below the point). Market banner face: 48 x 112 px (cloth rows 0-100). The frames come from
shop_templates (transcribed from the references); the characters from shop_glyphs. Both sizes are multiples of 16, so they never
lower Minecraft's mipmap level. The striped rod and the tassel are model parts with their own small textures.

Usage: python3 shop_textures.py <resources/assets/letterbanners> [preview.png]"""
import os, sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from shop_glyphs import glyph, ORDER, GW, GH
from shop_templates import LETTER, MARKET

STYLES = {
    #           cloth base      light           dark            cream            cream shade      bead
    "royal":   ((8, 44, 160), (22, 64, 184), (4, 30, 124), (246, 232, 216), (222, 205, 186), (200, 200, 214)),
    "crimson": ((146, 2, 12), (178, 8, 20), (108, 0, 8), (246, 232, 216), (224, 204, 186), (236, 196, 190)),
    "azure":   ((4, 100, 176), (24, 126, 204), (0, 78, 148), (246, 234, 222), (224, 210, 196), (214, 218, 226)),
}

def shade(h, w, style, seed):
    """Cloth colour with a soft woven mottle and faint diagonal folds, like the reference cloth."""
    base, light = (np.array(c, float) for c in STYLES[style][:2])
    rng = np.random.default_rng(seed)
    n = rng.random((h // 4 + 2, w // 4 + 2))
    n = np.kron(n, np.ones((4, 4)))[:h, :w]
    n = (n + np.roll(n, 2, 0) + np.roll(n, 2, 1) + np.roll(n, (2, 2), (0, 1))) / 4
    yy, xx = np.mgrid[0:h, 0:w]
    diag = 0.5 + 0.5 * np.sin((xx + yy * 0.6) / 7.0)
    f = np.clip(0.35 * n + 0.35 * diag - 0.15, 0, 1)[..., None]
    return base * (1 - f) + light * f + rng.normal(0, 2.5, (h, w, 1))

def from_template(rows, tw, th, x_off, style, seed):
    """Paint a template (# cream, o cloth, space transparent) into a tw x th RGBA canvas at column x_off."""
    base, light, dark, cream, cream2, bead = (np.array(c, float) for c in STYLES[style])
    th_rows, tw_cols = len(rows), len(rows[0])
    a = np.zeros((th, tw, 4))
    col = shade(th, tw, style, seed)
    for y, r in enumerate(rows):
        for x, c in enumerate(r):
            if c == " ":
                continue
            a[y, x + x_off, :3] = cream if c == "#" else col[y, x + x_off]
            a[y, x + x_off, 3] = 255
    # the outermost cloth pixel is a little darker, so the silhouette reads against any background
    solid = a[..., 3] > 0
    inner = solid & np.roll(solid, 1, 1) & np.roll(solid, -1, 1) & np.roll(solid, -1, 0)
    edge = solid & ~inner
    cloth_px = edge & (a[..., 0] + a[..., 1] + a[..., 2] < 600)
    a[cloth_px, :3] = dark
    return a

LW, LH = 32, 96          # letter banner face: cloth 30 px wide at x 1-30, rows 0-77; rows 78-95 transparent
LETTER_X, LETTER_Y = 1 + 6, 17  # where the 18 x 39 character goes (template columns 6-23, rows 17-55)

def letter_banner(style, gid, seed):
    base, light, dark, cream, cream2, bead = (np.array(c, float) for c in STYLES[style])
    a = from_template(LETTER, LW, LH, 1, style, seed)
    m = glyph(gid)
    sub = a[LETTER_Y:LETTER_Y + GH, LETTER_X:LETTER_X + GW]
    sub[m, :3] = cream
    # woven look: the lower-right edge pixels of each stroke are a slightly darker cream
    sh = m & (~np.roll(m, -1, 1) | ~np.roll(m, -1, 0))
    sub[sh, :3] = cream2
    a[a[..., 3] == 0] = 0
    return a

MW, MH = 48, 112         # market banner face: cloth rows 0-100

def market_banner(style, seed):
    base, light, dark, cream, cream2, bead = (np.array(c, float) for c in STYLES[style])
    a = from_template(MARKET, MW, MH, 0, style, seed)
    cloth = (a[..., 3] > 0) & (a[..., :3].sum(2) < 600)
    yy, xx = np.mgrid[0:MH, 0:MW]
    # canopy: the coloured panels between the cream stripes are a lighter cloth
    canopy = cloth & (yy >= 24) & (yy <= 40) & (xx >= 7) & (xx <= 40)
    a[canopy, :3] = a[canopy, :3] * 0.6 + light * 0.4
    # beads at the tips of the four lobes of the canopy's valance
    for bx in (9, 18, 30, 39):
        for (x, y) in ((bx - 1, 38), (bx, 38), (bx - 1, 39), (bx, 39)):
            if a[y, x, :3].sum() < 600:
                a[y, x, :3] = bead
    # the crate: lighter top face, darker side faces, so the cube reads as a solid closed box
    top = cloth & (yy >= 50) & (yy <= 58) & (np.abs(xx - 23.5) / 14 + np.abs(yy - 54.5) / 5 <= 1)
    a[top, :3] = a[top, :3] * 0.7 + light * 0.3
    left = cloth & (yy >= 57) & (yy <= 74) & (xx >= 11) & (xx <= 23)
    right = cloth & (yy >= 57) & (yy <= 74) & (xx >= 24) & (xx <= 36)
    a[left, :3] = a[left, :3] * 0.9
    a[right, :3] = a[right, :3] * 0.75 + dark * 0.1
    a[a[..., 3] == 0] = 0
    return a

def rod(style):
    """16x16 rod texture: coloured, with two cream bands per block and a dark seam at each end (where rods meet)."""
    base, light, dark, cream, cream2, bead = (np.array(c, float) for c in STYLES[style])
    a = np.zeros((16, 16, 4))
    a[..., 3] = 255
    for v in range(16):
        k = 1.25 if v < 4 else (1.0 if v < 11 else 0.7)
        for u in range(16):
            band = 2 <= u <= 5 or 10 <= u <= 13
            col = (cream if band else base) * k
            if u in (0, 15):
                col = dark * 0.8
            a[v, u, :3] = col
    return a

def tassel():
    a = np.zeros((16, 16, 4))
    a[..., 3] = 255
    cream = np.array((246, 236, 222), float)
    for v in range(16):
        for u in range(16):
            a[v, u, :3] = cream * (1.04 - 0.012 * u - 0.006 * v)
    a[:, ::5, :3] *= 0.94  # threads
    return a

def to_img(a):
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")

def icon_letter(style, gid, face):
    """16x16 item icon: banner shape with the character drawn crisp and large enough to read."""
    base, light, dark, cream, cream2, bead = STYLES[style]
    ic = np.zeros((16, 16, 4))
    for y in range(16):
        hw = 5 if y < 11 else max(1, 5 - (y - 10))
        for x in range(8 - hw, 8 + hw):
            ic[y, x, :3] = base
            ic[y, x, 3] = 255
    ic[0, 3:13, :3] = cream  # rod
    if gid == "market":
        for y in range(2, 7):
            for x in range(8 - (y - 1), 8 + (y - 1)):
                ic[y, x, :3] = cream if (x // 2) % 2 == 0 else light
        for (x, y) in ((5, 8), (10, 8), (5, 9), (10, 9)):
            ic[y, x, :3] = cream
        ic[10:13, 5:11, :3] = cream
        ic[11, 6:10, :3] = light
    elif gid != "blank":
        g = np.asarray(Image.fromarray(glyph(gid).astype(np.uint8) * 255).resize((7, 11), Image.BOX)) > 100
        ic[2:13, 4:11][g] = list(cream) + [255]
    return to_img(ic)

def build(out, preview=None):
    tex = os.path.join(out, "textures/block"); os.makedirs(tex, exist_ok=True)
    item = os.path.join(out, "textures/item"); os.makedirs(item, exist_ok=True)
    faces = {}
    for s_i, style in enumerate(STYLES):
        for g_i, gid in enumerate(ORDER + ["market"]):
            seed = 1000 * s_i + g_i
            a = market_banner(style, seed) if gid == "market" else letter_banner(style, gid, seed)
            img = to_img(a)
            img.save(os.path.join(tex, f"{style}_{gid}.png"))
            icon_letter(style, gid, img).save(os.path.join(item, f"{style}_{gid}.png"))
            faces[(style, gid)] = img
        to_img(rod(style)).save(os.path.join(tex, f"{style}_rod.png"))
    to_img(tassel()).save(os.path.join(tex, "shop_tassel.png"))
    if preview:
        glyphs = ["market", "s", "h", "o", "p", "s", "a", "0", "blank"]
        pv = Image.new("RGBA", (len(glyphs) * 52, 3 * 118), (2, 15, 38, 255))
        for r, style in enumerate(STYLES):
            for c, g in enumerate(glyphs):
                pv.alpha_composite(faces[(style, g)], (c * 52 + 2, r * 118 + 2))
        pv.resize((pv.width * 3, pv.height * 3), Image.NEAREST).save(preview)
    return faces

if __name__ == "__main__":
    build(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
    print("ok", 3 * (len(ORDER) + 1))
