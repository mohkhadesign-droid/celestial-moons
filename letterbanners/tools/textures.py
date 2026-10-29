"""Letter banner textures: 3 styles x 41 glyphs. Each banner face is 28x60 px (14x30 model units at 2 px/unit),
plus a 16x16 inventory icon. Pixel art: glyphs come from a TTF rendered large and reduced to a pixel mask."""
import os, sys, random
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT = sys.argv[1] if __name__ == "__main__" else None
W, H = 28, 60
GLYPHS = [(c.lower(), c) for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"] + \
         [("amp", "&"), ("excl", "!"), ("question", "?"), ("dash", "-"), ("blank", "")]
FONTS = {
    "aurum": "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",
    "frost": "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",
    "ember": "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
}

def find_font(path):
    if os.path.exists(path):
        return path
    import subprocess
    name = "DejaVu Serif:style=Bold" if "Serif" in path else "DejaVu Sans:style=Bold"
    return subprocess.check_output(["fc-match", "-f", "%{file}", name]).decode()

def glyph_mask(ch, font, bw, bh):
    """Pixel mask of the glyph fitted into a bw x bh box (keeps proportions of tall letters, stretches narrow ones a little)."""
    if not ch:
        return np.zeros((bh, bw), bool)
    f = ImageFont.truetype(find_font(font), 400)
    im = Image.new("L", (600, 600), 0)
    ImageDraw.Draw(im).text((100, 20), ch, font=f, fill=255)
    box = im.getbbox()
    im = im.crop(box)
    w, h = im.size
    # letters fill the height; width scaled with a slight condensing so wide letters (M, W) still fit
    tw = min(bw, max(4, round(w / h * bh)))
    if ch in "-":
        tw, th = bw - 4, 4
        m = np.zeros((bh, bw), bool)
        y0 = bh // 2 - 2
        m[y0:y0 + th, 2:2 + tw] = True
        return m
    th = bh
    small = im.resize((tw, th), Image.LANCZOS)
    a = np.asarray(small) > 110
    m = np.zeros((bh, bw), bool)
    x0 = (bw - tw) // 2
    m[:, x0:x0 + tw] = a
    return m

def dilate(m, n=1):
    out = m.copy()
    for _ in range(n):
        o = out.copy()
        o[1:, :] |= out[:-1, :]; o[:-1, :] |= out[1:, :]; o[:, 1:] |= out[:, :-1]; o[:, :-1] |= out[:, 1:]
        out = o
    return out

def noise(rng, shape, amt):
    return rng.integers(-amt, amt + 1, shape + (1,))

def banner_shape(style):
    """Alpha mask of the banner cloth (notched/pointed hems)."""
    a = np.ones((H, W), bool)
    if style == "aurum":  # shallow pointed hem with a centre notch, like the gold banners
        for x in range(W):
            d = abs(x - (W - 1) / 2)
            cut = int(round(4 - d * 4 / (W / 2)))  # longer at the edges
            a[H - 1 - max(0, 3 - cut):, x] = False if 3 - cut > 0 else a[H - 1:, x]
        for x in range(W):
            d = abs(x - (W - 1) / 2)
            depth = max(0, 4 - int(d * 0.6))
            if depth:
                a[H - depth:, x] = False
    elif style == "frost":  # straight hem
        pass
    else:  # ember: framed plate, square
        pass
    return a

def draw(style, ch, rng):
    img = np.zeros((H, W, 4), int)
    shape = banner_shape(style)
    yy, xx = np.mgrid[0:H, 0:W]
    edge = (xx == 0) | (xx == W - 1) | (yy == 0)
    if style == "aurum":
        base = np.array([236, 178, 26])
        col = base + noise(rng, (H, W), 10)
        col = col - (np.abs(xx - W / 2) / W * 30)[..., None]  # slight cloth shading
        img[..., :3] = col
        # dark border + top band with diamonds
        border = dilate(~shape, 1) & shape | edge
        img[border, :3] = [52, 34, 10]
        img[3:5, 2:W - 2, :3] = [52, 34, 10]
        for x in range(4, W - 3, 6):
            for dx, dy in [(0, -1), (-1, 0), (0, 0), (1, 0), (0, 1)]:
                img[8 + dy, x + dx, :3] = [52, 34, 10]
        img[H - 9:H - 8, 2:W - 2, :3] = [52, 34, 10]
        for x in range(5, W - 4, 6):
            img[H - 11, x, :3] = [52, 34, 10]
        m = glyph_mask(ch, FONTS[style], 20, 30)
        g = np.zeros((H, W), bool); g[15:45, 4:24] = m
        img[g, :3] = [24, 18, 12] + noise(rng, (int(g.sum()),), 6)[:, 0:1]
        hl = g & ~np.roll(g, 1, axis=1)  # left edge of strokes: worn brown
        img[hl, :3] = [70, 48, 18]
    elif style == "frost":
        base = np.array([30, 26, 92])
        col = base + noise(rng, (H, W), 6)
        img[..., :3] = col
        # white frosted frame with lavender corner gems
        frame = (xx <= 1) | (xx >= W - 2) | (yy <= 1) | (yy >= H - 2)
        img[frame, :3] = [226, 236, 250]
        img[(xx == 2) | (xx == W - 3) | (yy == 2) | (yy == H - 3), :3] = [150, 170, 220]
        for cx, cy in [(1, 1), (W - 2, 1), (1, H - 2), (W - 2, H - 2), (1, H // 2), (W - 2, H // 2)]:
            img[cy - 1:cy + 1, cx - 1:cx + 1, :3] = [176, 120, 236]
        # icicles hanging from the top frame
        for x in range(3, W - 3, 2):
            L = rng.integers(1, 4)
            img[3:3 + L, x, :3] = [200, 220, 248]
        m = glyph_mask(ch, FONTS[style], 18, 30)
        g = np.zeros((H, W), bool); g[14:44, 5:23] = m
        # icy gradient: white top -> pale blue bottom, with a darker blue shadow on the right
        grad = np.clip((yy - 13) / 32, 0, 1)[..., None]
        ice = np.array([246, 250, 255]) * (1 - grad) + np.array([150, 200, 245]) * grad
        img[g, :3] = ice[g]
        sh = g & ~np.roll(g, -1, axis=1)
        img[sh, :3] = [110, 150, 220]
        # drips under the strokes
        bottoms = g & ~np.roll(g, -1, axis=0)
        for y, x in zip(*np.where(bottoms)):
            if rng.random() < 0.45 and y + 3 < H - 4:
                L = rng.integers(1, 4)
                img[y + 1:y + 1 + L, x, :3] = [190, 220, 250]
        # purple sparkles (4-point stars) away from the letter
        for _ in range(4):
            for _t in range(30):
                sx, sy = rng.integers(4, W - 4), rng.integers(8, H - 8)
                if not dilate(g, 2)[sy, sx]:
                    break
            img[sy, sx, :3] = [236, 210, 255]
            for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                img[sy + dy, sx + dx, :3] = [170, 110, 235]
    else:  # ember
        base = np.array([206, 66, 26])
        col = base + noise(rng, (H, W), 9)
        col = col + (((yy * 7 + xx * 3) % 11 == 0) * 14)[..., None]  # cloth weave
        img[..., :3] = col
        gold = np.array([236, 170, 50]); dark = np.array([40, 28, 22])
        outer = (xx == 0) | (xx == W - 1) | (yy == 0) | (yy == H - 1)
        frame = (xx <= 2) | (xx >= W - 3) | (yy <= 2) | (yy >= H - 3)
        img[frame, :3] = gold + noise(rng, (H, W), 8)[frame]
        img[outer, :3] = dark
        img[(xx == 3) | (xx == W - 4) | ((yy == 3) | (yy == H - 4)) & (xx >= 3) & (xx <= W - 4), :3] = [150, 40, 18]
        for cx, cy in [(1, 1), (W - 2, 1), (1, H - 2), (W - 2, H - 2), (1, H // 2), (W - 2, H // 2)]:
            img[cy, cx, :3] = [120, 120, 125]
        m = glyph_mask(ch, FONTS[style], 16, 28)
        g = np.zeros((H, W), bool); g[16:44, 6:22] = m
        ring = dilate(g, 1) & ~g
        img[ring, :3] = gold
        img[g, :3] = [30, 22, 20] + noise(rng, (int(g.sum()),), 5)[:, 0:1]
    img[..., 3] = np.where(shape, 255, 0)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA")

def icon(face):
    """16x16 item icon: the banner scaled into the middle with a little rod on top."""
    ic = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    small = face.resize((7, 15), Image.NEAREST if False else Image.BOX)
    ic.paste(small, (4, 1))
    d = ImageDraw.Draw(ic)
    d.line([(3, 0), (12, 0)], fill=(90, 62, 36, 255))
    return ic

if __name__ == "__main__":
    tex = os.path.join(OUT, "textures/block"); os.makedirs(tex, exist_ok=True)
    item = os.path.join(OUT, "textures/item"); os.makedirs(item, exist_ok=True)
    for style in ("aurum", "frost", "ember"):
        for gid, ch in GLYPHS:
            rng = np.random.default_rng(abs(hash((style, gid))) % 2**32 if False else (sum(map(ord, style + gid)) * 7919))
            face = draw(style, ch, rng)
            face.save(os.path.join(tex, f"{style}_{gid}.png"))
            icon(face).save(os.path.join(item, f"{style}_{gid}.png"))
    print("ok", 3 * len(GLYPHS))
