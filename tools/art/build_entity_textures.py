"""Wisp Moon entity sprites: 16x64, four 16x16 flicker frames stacked vertically (RGBA, pixel art)."""
import os, sys, math
from PIL import Image
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)

def flame(frame, eye=False):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    sway = [0, 1, 0, -1][frame]
    lift = [0, 0, 1, 0][frame]
    for y in range(16):
        for x in range(16):
            # teardrop: round body at the bottom, tail curving up and to one side
            cx = 7.5 + (sway + 1.5) * max(0, (8 - y)) / 8.0
            yy = y + lift
            if yy >= 8:
                r = 4.6 - max(0, yy - 11) * 0.9
            else:
                r = 4.6 * (max(0.0, yy - 1.5) / 6.5) ** 1.6
            d = abs(x - cx)
            if yy < 2 or yy > 14 or d > r:
                continue
            t = d / max(r, 0.01)
            core = (9 <= yy <= 13 and t < 0.55 - abs(yy - 11) * 0.12)
            if core:
                c = (232, 252, 255, 255)
            elif t < 0.75:
                c = (140, 228, 255, 240)
            else:
                c = (70, 170, 235, 200)
            px[x, y] = c
    # a few sparks
    for (sx, sy) in [((3 + frame) % 16, 3), (12, (6 + frame * 2) % 14), (2, (11 - frame) % 14)]:
        if px[sx, sy][3] == 0:
            px[sx, sy] = (170, 236, 255, 170)
    if eye:  # a calm spirit eye in the flame body
        for (x, y) in [(6, 10), (7, 10), (8, 10), (9, 10), (7, 9), (8, 9), (7, 11), (8, 11)]:
            px[x, y] = (20, 60, 90, 255)
        px[8, 10] = (220, 250, 255, 255)
    return im

for name, eye in (("wisp", False), ("soul_observer", True)):
    sheet = Image.new("RGBA", (16, 64), (0, 0, 0, 0))
    for f in range(4):
        sheet.paste(flame(f, eye), (0, f * 16))
    sheet.save(os.path.join(OUT, name + ".png"))
print("ok")
