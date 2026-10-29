"""Letter banner textures: 3 styles x 41 glyphs, matching the gate-sign references (Aurum Forge, Frost Spell,
Ember Gears). Each banner face is 28x60 px (14x30 model units at 2 px per unit); the back of the banner uses the
same picture, so it reads correctly from behind. Plus a 16x16 inventory icon per banner.
Usage: python3 textures.py <resources/assets/letterbanners> [preview.png]"""
import os, sys, random
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 28, 60
GLYPHS = [(c.lower(), c) for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"] + \
         [("amp", "&"), ("excl", "!"), ("question", "?"), ("dash", "-"), ("blank", "")]
FONTS = {
    "aurum": "DejaVuSansCondensed-Bold.ttf",
    "frost": "DejaVuSerifCondensed-Bold.ttf",
    "ember": "DejaVuSans-Bold.ttf",
}

def font_path(name):
    for d in ("/usr/share/fonts/truetype/dejavu/", "/usr/share/fonts/TTF/", "/usr/share/fonts/dejavu/"):
        if os.path.exists(d + name):
            return d + name
    import subprocess
    fam = {"DejaVuSansCondensed-Bold.ttf": "DejaVu Sans Condensed:style=Bold", "DejaVuSerifCondensed-Bold.ttf": "DejaVu Serif Condensed:style=Bold",
           "DejaVuSans-Bold.ttf": "DejaVu Sans:style=Bold"}[name]
    return subprocess.check_output(["fc-match", "-f", "%{file}", fam]).decode()

_cache = {}
def glyph(ch, w, h, font):
    """Pixel mask (h x w): cap height fills h, round letters (O) fill the width, punctuation keeps its place."""
    m = np.zeros((h, w), bool)
    if not ch:
        return m
    f = _cache.setdefault(font, ImageFont.truetype(font_path(font), 600))
    def render(c):
        im = Image.new("L", (1000, 1000), 0)
        ImageDraw.Draw(im).text((150, 50), c, font=f, fill=255)
        return im
    ref = render("H").getbbox()
    cap_top, cap_h = ref[1], ref[3] - ref[1]
    o = render("O").getbbox()
    sx, sy = w / (o[2] - o[0]), h / cap_h
    im = render(ch)
    bb = im.getbbox()
    tw = max(2, min(w, round((bb[2] - bb[0]) * sx)))
    th = max(2, min(h + 3, round((bb[3] - bb[1]) * sy)))
    top = round((bb[1] - cap_top) * sy)
    g = np.asarray(im.crop(bb).resize((tw, th), Image.LANCZOS)) > 120
    ox = (w - tw) // 2
    for y in range(th):
        if 0 <= top + y < h:
            m[top + y, ox:ox + tw] = g[y]
    return m

def outline(mask):
    p = np.pad(mask, 1)
    return (p[:-2, 1:-1] | p[2:, 1:-1] | p[1:-1, :-2] | p[1:-1, 2:]) & ~mask

def canvas(col):
    a = np.zeros((H, W, 4), float)
    a[..., :3] = col
    a[..., 3] = 255
    return a

def rect(y0, y1, x0, x1):
    m = np.zeros((H, W), bool)
    m[y0:y1, x0:x1] = True
    return m

def put(a, mask, col):
    a[mask, :3] = col

YY = np.arange(H)[:, None]
XX = np.arange(W)[None, :]
CX = (W - 1) / 2

# ---------------------------------------------------------------------------------------------------------------------
def aurum(ch, rng):
    """Gold cloth, black block letter, black diamond ornaments top and bottom, pointed swallow hem."""
    a = canvas((236, 182, 34))
    a[..., :3] += rng.normal(0, 5, (H, W, 1)) - ((np.abs(XX - CX) > 11.5)[..., None] * 24)
    black, brown = (28, 22, 16), (70, 48, 12)
    cut = np.array([55 + (0 if d < 2 else 1 if d < 4 else 2 if d < 7 else 3 if d < 10 else 4) for d in np.abs(np.arange(W) - CX)])
    hem = YY >= cut[None, :]
    a[hem, 3] = 0
    edge = rect(0, H, 0, 1) | rect(0, H, W - 1, W) | rect(0, 1, 0, W) | (YY == cut[None, :] - 1)
    inner = (rect(3, 4, 3, W - 3) | ((XX == 3) | (XX == W - 4)) & (YY >= 3)) & (YY <= cut[None, :] - 4) | \
            (YY == cut[None, :] - 4) & (XX >= 3) & (XX <= W - 4)
    put(a, inner & ~hem, brown)
    put(a, edge & ~hem, black)
    def diamond(cx, cy, r, col):
        for y in range(cy - r, cy + r + 1):
            for x in range(cx - r, cx + r + 1):
                if abs(x - cx) + abs(y - cy) <= r:
                    a[y, x, :3] = col
    diamond(14, 6, 2, black); a[6, 14, :3] = (236, 182, 34)
    for cx in (8, 20):
        diamond(cx, 6, 1, black)
    for cx in (5, 23):
        a[6, cx, :3] = black
    diamond(14, 48, 1, black)
    for cx in (9, 19):
        a[48, cx, :3] = black
    m = np.zeros((H, W), bool); m[11:44, 4:24] = glyph(ch, 20, 33, FONTS["aurum"])
    put(a, m, black)
    put(a, m & (rng.random((H, W)) < 0.08), (54, 44, 30))
    return a

def frost(ch, rng):
    """Deep navy cloth, icy white letter with icicles, frosted border, violet gems and sparkles."""
    a = canvas((30, 26, 84))
    a[..., :3] += rng.normal(0, 4, (H, W, 1)) + (YY / H)[..., None] * np.array([-6, -4, 10])
    white, ice, deep = np.array((236, 244, 255)), np.array((160, 200, 240)), np.array((110, 150, 220))
    violet = (172, 110, 240)
    r = random.Random(int(rng.integers(0, 1 << 30)))
    frame = rect(0, H, 0, 2) | rect(0, H, W - 2, W) | rect(0, 2, 0, W) | rect(H - 2, H, 0, W)
    put(a, frame, white)
    a[frame, :3] -= rng.integers(0, 18, (int(frame.sum()), 1))
    for x in range(3, W - 3, 3):
        L = r.choice((1, 2, 2, 3, 4))
        for y in range(2, 2 + L):
            a[y, x, :3] = white if y < 1 + L else ice
    for x in range(4, W - 3, 4):
        for y in range(H - 2 - r.choice((1, 1, 2)), H - 2):
            a[y, x, :3] = ice
    for (y, x) in ((1, 1), (1, W - 3), (H - 3, 1), (H - 3, W - 3), (29, 0), (29, W - 2)):
        a[y:y + 2, x:x + 2, :3] = violet
        a[y, x, :3] = (214, 170, 255)
    m = np.zeros((H, W), bool); m[10:45, 4:24] = glyph(ch, 20, 35, FONTS["frost"])
    grad = np.broadcast_to(np.clip((YY - 10) / 35, 0, 1), (H, W))[..., None] * 0.75
    col = white * (1 - grad) + ice * grad
    a[m, :3] = col[m]
    put(a, outline(m) & ~frame, deep * 0.55)
    for x in range(4, 24):
        ys = np.where(m[:, x])[0]
        if len(ys) and r.random() < 0.45:
            for k in range(r.choice((1, 2, 2, 3))):
                if ys.max() + 1 + k < H - 3:
                    a[ys.max() + 1 + k, x, :3] = ice if k == 0 else deep
    def star(cx, cy):
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            a[cy + dy, cx + dx, :3] = (230, 200, 255) if (dx, dy) == (0, 0) else violet
    for cx, cy in ((6, 6), (21, 6), (14, 6), (7, 51), (20, 52), (14, 53)):
        if not m[cy - 1:cy + 2, cx - 1:cx + 2].any():
            star(cx, cy)
    if not ch:
        for d in range(7):
            for (x, y) in ((14 + d, 29), (14 - d, 29), (14, 29 + d), (14, 29 - d)):
                a[y, x, :3] = violet if d > 1 else (230, 200, 255)
    return a

def ember(ch, rng):
    """Red-orange cloth in a gold frame with iron rivets, black letter with a hot orange rim."""
    a = canvas((214, 74, 28))
    vign = np.sqrt(((XX - CX) / 14) ** 2 + ((YY - (H - 1) / 2) / 30) ** 2)
    a[..., :3] += rng.normal(0, 5, (H, W, 1)) - vign[..., None] * np.array([60, 40, 20])
    gold, gold2, iron, iron2 = (240, 176, 48), (176, 112, 24), (150, 150, 156), (90, 88, 96)
    frame = rect(0, H, 0, 2) | rect(0, H, W - 2, W) | rect(0, 2, 0, W) | rect(H - 2, H, 0, W)
    put(a, frame, gold)
    put(a, rect(0, H, 1, 2) | rect(0, H, W - 2, W - 1) | rect(1, 2, 1, W - 1) | rect(H - 2, H - 1, 1, W - 1), gold2)
    put(a, rect(2, 3, 2, W - 2) | rect(H - 3, H - 2, 2, W - 2) | rect(2, H - 2, 2, 3) | rect(2, H - 2, W - 3, W - 2), (110, 30, 12))
    for (y, x) in ((0, 0), (0, W - 2), (H - 2, 0), (H - 2, W - 2), (0, 13), (H - 2, 13), (29, 0), (29, W - 2)):
        a[y:y + 2, x:x + 2, :3] = iron
        a[y + 1, x + 1, :3] = iron2
    m = np.zeros((H, W), bool); m[11:47, 4:24] = glyph(ch, 20, 36, FONTS["ember"])
    put(a, m, (26, 20, 20))
    o = outline(m)
    put(a, o, (255, 150, 40))
    put(a, outline(m | o) & ~frame, (150, 44, 14))
    put(a, m & (rng.random((H, W)) < 0.1), (48, 34, 30))
    if not ch:  # blank: a small gear
        d = np.sqrt((XX - CX) ** 2 + (YY - 29.5) ** 2)
        ang = np.arctan2(YY - 29.5, XX - CX)
        gear = ((d < 5.5) | ((d < 7.5) & (np.cos(ang * 8) > 0.3))) & (d > 2.2)
        put(a, gear, (36, 28, 26))
    return a

STYLES = {"aurum": aurum, "frost": frost, "ember": ember}

def draw(style, ch, rng):
    return Image.fromarray(np.clip(STYLES[style](ch, rng), 0, 255).astype(np.uint8), "RGBA")

def icon(style, ch, face):
    """16x16 item icon: the banner's look, with the character large enough to read in the inventory."""
    small = face.resize((10, 16), Image.BOX)
    ic = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    ic.paste(small, (3, 0))
    a = np.asarray(ic).copy()
    m = np.zeros((16, 16), bool)
    if ch:
        m[3:12, 4:12] = glyph(ch, 8, 9, FONTS[style])
    col = {"aurum": (28, 22, 16), "frost": (236, 244, 255), "ember": (26, 20, 20)}[style]
    bg = {"aurum": (236, 182, 34), "frost": (30, 26, 84), "ember": (200, 70, 28)}[style]
    a[2:13, 4:12, :3] = bg  # clear the reduced letter, then draw a crisp one
    a[m, :3] = col
    if style == "ember":
        a[outline(m) & (np.arange(16)[:, None] >= 2) & (np.arange(16)[:, None] < 13) & (np.arange(16)[None, :] >= 4)
          & (np.arange(16)[None, :] < 12), :3] = (255, 150, 40)
    return Image.fromarray(a, "RGBA")

if __name__ == "__main__":
    out = sys.argv[1]
    tex = os.path.join(out, "textures/block"); os.makedirs(tex, exist_ok=True)
    item = os.path.join(out, "textures/item"); os.makedirs(item, exist_ok=True)
    rows = []
    for style in STYLES:
        row = []
        for gid, ch in GLYPHS:
            rng = np.random.default_rng(sum(map(ord, style + gid)) * 7919)
            face = draw(style, ch, rng)
            face.save(os.path.join(tex, f"{style}_{gid}.png"))
            ic = icon(style, ch, face)
            ic.save(os.path.join(item, f"{style}_{gid}.png"))
            row.append((face, ic))
        rows.append(row)
    if len(sys.argv) > 2:
        n = len(GLYPHS)
        pv = Image.new("RGBA", (n * 32, 3 * 84), (20, 20, 26, 255))
        for r_, row in enumerate(rows):
            for c, (face, ic) in enumerate(row):
                pv.alpha_composite(face, (c * 32 + 2, r_ * 84 + 2))
                pv.alpha_composite(ic, (c * 32 + 8, r_ * 84 + 64))
        pv.resize((pv.width * 3, pv.height * 3), Image.NEAREST).save(sys.argv[2])
    print("ok", 3 * len(GLYPHS))
