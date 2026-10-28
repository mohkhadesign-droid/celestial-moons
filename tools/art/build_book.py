"""Field-guide pictures: each moon's full-moon frame (phases + details, blended additively like the sky renderer)
on a small night-sky panel. Patchouli image pages show the top-left 200x200 of a 256x256 texture."""
import os, sys, random
from PIL import Image, ImageChops, ImageDraw, ImageFilter

ENV, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
MOONS = ["blood", "blue", "harvest", "mist", "frost", "arcane", "prospector", "wisdom", "quiet", "drift", "revealing",
         "thread", "war", "gluttony", "afterimage", "shell", "wisp"]

def frame(name):
    im = Image.open(os.path.join(ENV, name)).convert("RGB")
    return im.crop((0, 0, 128, 128))

for m in MOONS:
    rnd = random.Random(m)
    panel = Image.new("RGB", (200, 200), (8, 10, 22))
    d = ImageDraw.Draw(panel)
    for y in range(200):  # faint vertical gradient
        c = int(8 + 14 * y / 200)
        d.line([(0, y), (199, y)], fill=(c // 2, c // 2 + 2, c + 10))
    for _ in range(45):
        x, y = rnd.randrange(200), rnd.randrange(200)
        b = rnd.randrange(90, 230)
        panel.putpixel((x, y), (b, b, min(255, b + 20)))
    moon = frame(m + "_moon_phases.png")
    det = os.path.join(ENV, m + "_moon_details.png")
    ghostsrc = None
    if m == "afterimage":  # details are not added on the moon; the sky draws them as offset copies
        ghostsrc = frame(m + "_moon_details.png")
    elif os.path.exists(det):
        dimg = frame(m + "_moon_details.png")
        moon = ImageChops.add(moon, dimg)
    bbox = moon.convert("L").point(lambda v: 255 if v > 12 else 0).getbbox() or (0, 0, 128, 128)
    side = max(bbox[2] - bbox[0], bbox[3] - bbox[1])
    cx, cy = (bbox[0] + bbox[2]) // 2, (bbox[1] + bbox[3]) // 2
    moon = moon.crop((cx - side // 2, cy - side // 2, cx - side // 2 + side, cy - side // 2 + side))
    big = moon.resize((112, 112), Image.NEAREST)
    if ghostsrc is not None:
        ghostbig = ghostsrc.crop((cx - side // 2, cy - side // 2, cx - side // 2 + side, cy - side // 2 + side)).resize((112, 112), Image.NEAREST)
    pos = (54, 38) if m == "afterimage" else (44, 44)
    def opaque(img):
        return img.convert("L").point(lambda v: 255 if v > 10 else 0)
    glowsrc = Image.new("RGB", (200, 200), (0, 0, 0))
    glowsrc.paste(big, pos)
    out = ImageChops.add(panel, glowsrc.filter(ImageFilter.GaussianBlur(10)).point(lambda v: int(v * 0.35)))
    if m == "afterimage":  # the sky draws two fading offset copies of the details layer
        for dx, dz, a in ((30, -13, 0.30), (16, -7, 0.6)):
            ghost = ImageChops.add(ghostbig, big.point(lambda v: int(v * 0.4)))
            out.paste(Image.blend(out.crop((pos[0] - dx, pos[1] - dz, pos[0] - dx + 112, pos[1] - dz + 112)), ghost, a),
                      (pos[0] - dx, pos[1] - dz), opaque(ghost))
    out.paste(big, pos, opaque(big))
    # rounded frame
    canvas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    mask = Image.new("L", (200, 200), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 199, 199], 14, fill=255)
    canvas.paste(out, (0, 0), mask)
    ImageDraw.Draw(canvas).rounded_rectangle([0, 0, 199, 199], 14, outline=(120, 110, 90, 255), width=2)
    canvas.save(os.path.join(OUT, m + ".png"))
print("ok", len(MOONS))
