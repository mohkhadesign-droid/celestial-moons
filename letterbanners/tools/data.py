"""Generates blockstates, models, item models, lang, loot tables, tags and recipes for every letter banner."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from textures import GLYPHS

R = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources"
A = os.path.join(R, "assets/letterbanners")
D = os.path.join(R, "data/letterbanners")
STYLES = {"aurum": "Aurum Forge", "frost": "Frost Spell", "ember": "Ember Gears"}
# the shop collection (SHOPS / market references): same characters plus the market banner
SHOP_STYLES = {"royal": "Royal Shop", "crimson": "Crimson Shop", "azure": "Azure Shop"}
SHOP_GLYPHS = GLYPHS + [("market", "")]
NAMES = {"amp": "&", "excl": "!", "question": "?", "dash": "-", "blank": "Blank", "market": "Market"}

def label(gid, ch):
    if gid in NAMES:
        return NAMES[gid]
    return ("Number " if ch.isdigit() else "Letter ") + ch

def w(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")

VU = 28 / 60 * 16  # 7.4667: where the upper/lower halves meet in the 28x60 texture (14 of 30 model units are in the top block)

def panel(y0, y1, v0, v1, z0, z1):
    edge_u = 16 / 28
    return {"from": [1, y0, z0], "to": [15, y1, z1], "faces": {
        "north": {"uv": [0, v0, 16, v1], "texture": "#banner"},
        "south": {"uv": [0, v0, 16, v1], "texture": "#banner"},  # the back: same picture, reads correctly from behind
        "east": {"uv": [16 - edge_u, v0, 16, v1], "texture": "#banner"},
        "west": {"uv": [0, v0, edge_u, v1], "texture": "#banner"},
        "up": {"uv": [0, v0, 16, v0 + 0.25], "texture": "#banner"},
        "down": {"uv": [0, v1 - 0.25, 16, v1], "texture": "#banner"}}}

def chain(x, y0, y1, z):
    """Two crossed planes of the vanilla chain texture."""
    h = y1 - y0
    return [
        {"from": [x - 1.5, y0, z], "to": [x + 1.5, y1, z], "shade": False,
         "faces": {"north": {"uv": [0, 0, 3, h], "texture": "#chain"}, "south": {"uv": [0, 0, 3, h], "texture": "#chain"}}},
        {"from": [x, y0, z - 1.5], "to": [x, y1, z + 1.5], "shade": False,
         "faces": {"east": {"uv": [3, 0, 6, h], "texture": "#chain"}, "west": {"uv": [3, 0, 6, h], "texture": "#chain"}}},
    ]

def rod(y, z0, z1):
    return {"from": [0.5, y, z0], "to": [15.5, y + 1, z1], "faces": {f: {"uv": [0, 0, 16, 1], "texture": "#rod"} for f in
                                                                       ("north", "south", "east", "west", "up", "down")}}

# templates (front faces north; the blockstate rotates them)
tex = {"chain": "minecraft:block/chain", "rod": "minecraft:block/dark_oak_log"}
templates = {
    # against a wall: panel flush with the wall, a short rod on two chain links
    "template_upper_wall": [panel(0, 14, 0, VU, 15, 16), rod(14, 14.5, 15.5)] + chain(4, 15, 16, 15) + chain(12, 15, 16, 15),
    "template_lower_wall": [panel(0, 16, VU, 16, 15, 16)],
    # hanging free: panel in the middle of the block, rod and chains up to the block above
    "template_upper_free": [panel(0, 14, 0, VU, 7.5, 8.5), rod(14, 7.25, 8.75)] + chain(4, 15, 16, 8) + chain(12, 15, 16, 8),
    "template_lower_free": [panel(0, 16, VU, 16, 7.5, 8.5)],
}
for name, elems in templates.items():
    w(os.path.join(A, "models/block", name + ".json"),
      {"parent": "block/block", "ambientocclusion": False, "textures": dict(tex, particle="#banner"), "elements": elems})

# --- shop banners: a striped rod across the top of the block (model part), the cloth hanging from it, and a cream
# tassel (model part) under the pointed hem. Faces are the same picture front and back, readable from both sides.
def box(x0, y0, z0, x1, y1, z1, tex, uvs=None):
    faces = {}
    for f in ("north", "south", "east", "west", "up", "down"):
        faces[f] = {"uv": (uvs or {}).get(f, [0, 0, 16, 16]), "texture": tex}
    return {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": faces}

def cloth(x0, x1, ytop, height, split_from_top, z0, z1, part, tex_w_px, cloth_x0_px, cloth_x1_px):
    """The cloth quad (with the full texture, transparent outside the silhouette), cut at the block boundary.
    part 'upper': y from 0 to ytop; part 'lower': y from ytop - height + 16 to 16 (lower-block coordinates)."""
    vsplit = split_from_top / height * 16
    if part == "upper":
        y0, y1, v0, v1 = 0, ytop, 0, vsplit
    else:
        y0, y1, v0, v1 = ytop - height + 16, 16, vsplit, 16
    eu0 = (cloth_x0_px + 0.5) / tex_w_px * 16
    eu1 = (cloth_x1_px - 0.5) / tex_w_px * 16
    return {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": {
        "north": {"uv": [0, v0, 16, v1], "texture": "#banner"},
        "south": {"uv": [0, v0, 16, v1], "texture": "#banner"},  # the back: the same picture, readable from behind
        "west": {"uv": [eu0, v0, eu0 + 0.25, v1], "texture": "#banner"},
        "east": {"uv": [eu1 - 0.25, v0, eu1, v1], "texture": "#banner"},
        "up": {"uv": [eu0, v0, eu1, v0 + 0.1], "texture": "#banner"},
        "down": {"uv": [eu0, v1 - 0.1, eu1, v1], "texture": "#banner"}}}

def tassel(cx, y_point, z, cube_w, cube_h, cord_h):
    """A short cord from the hem's point and a cream tassel cube under it (lower-block coordinates)."""
    tu = {"north": [6, 0, 10, 8], "south": [6, 0, 10, 8], "east": [2, 0, 6, 8], "west": [10, 0, 14, 8], "up": [6, 0, 10, 4], "down": [6, 12, 10, 16]}
    return [box(cx - 0.4, y_point - cord_h, z - 0.4, cx + 0.4, y_point + 0.3, z + 0.4, "#tassel", {k: [7, 0, 9, 4] for k in tu}),
            box(cx - cube_w / 2, y_point - cord_h - cube_h, z - cube_w / 2, cx + cube_w / 2, y_point - cord_h, z + cube_w / 2, "#tassel", tu)]

def rod(x0, x1, y0, y1, z0, z1):
    L = x1 - x0
    side = {"north": [0, 4, 16, 11], "south": [0, 4, 16, 11], "up": [0, 0, 16, 4], "down": [0, 11, 16, 16],
            "east": [0, 4, 4, 11], "west": [12, 4, 16, 11]}
    return box(x0, y0, z0, x1, y1, z1, "#rod", side)

SHOP_GEOM = {
    # letter banner: 32x96 face, cloth 30 px (x 1-30) = 13.5 units -> 0.45 units/px; rod 2.2 thick along the block's top
    "shop": dict(k=0.45, tw=32, th=96, cx0=1, cx1=31, point_row=77.5, rod=(0, 16, 13.8, 16, 2.2), ytop=14.6, cube=(2.4, 4.5), cord=1.5),
    # market banner: 48x112 face, cloth 48 px = 19.2 units (0.4 units/px), wider than a block; thicker rod
    "market": dict(k=0.4, tw=48, th=112, cx0=0, cx1=48, point_row=100.5, rod=(-2.5, 18.5, 13.0, 16, 3.0), ytop=14.0, cube=(2.8, 4.0), cord=1.6),
}

def shop_templates(kind):
    g = SHOP_GEOM[kind]
    k, tw, th = g["k"], g["tw"], g["th"]
    width, height = tw * k, th * k
    x0, x1 = 8 - width / 2, 8 + width / 2
    ytop = g["ytop"]
    split = ytop  # units of cloth above the block boundary
    rx0, rx1, ry0, ry1, rt = g["rod"]
    y_point_upper = ytop - g["point_row"] * k  # in upper-block coordinates
    out = {}
    for mode, zc in (("wall", 16 - rt / 2), ("free", 8.0)):
        zr0, zr1 = zc - rt / 2, zc + rt / 2
        # the cloth sits just in front of the rod's centre; the market cloth a hair further forward, so where a market
        # banner overlaps its neighbour the two never share a plane
        zoff = -0.25 if kind == "market" else 0.0
        z0, z1 = zc - 0.2 + zoff, zc + 0.2 + zoff
        out["template_%s_upper_%s" % (kind, mode)] = [rod(rx0, rx1, ry0, ry1, zr0, zr1),
                                                       cloth(x0, x1, ytop, height, split, z0, z1, "upper", tw, g["cx0"], g["cx1"])]
        out["template_%s_lower_%s" % (kind, mode)] = [cloth(x0, x1, ytop, height, split, z0, z1, "lower", tw, g["cx0"], g["cx1"])] + \
            tassel(8, y_point_upper + 16, zc + zoff, g["cube"][0], g["cube"][1], g["cord"])
    return out

for kind in ("shop", "market"):
    for name, elems in shop_templates(kind).items():
        w(os.path.join(A, "models/block", name + ".json"),
          {"parent": "block/block", "ambientocclusion": False, "textures": {"particle": "#banner"}, "elements": elems})

ROT = {"north": 0, "east": 90, "south": 180, "west": 270}
lang = {"itemGroup.letterbanners": "Letter Banners"}
ALL_STYLES = list(STYLES.items()) + list(SHOP_STYLES.items())
for style, sname in ALL_STYLES:
    tag = []
    shop = style in SHOP_STYLES
    for gid, ch in (SHOP_GLYPHS if shop else GLYPHS):
        name = f"{style}_banner_{gid}"
        tag.append("letterbanners:" + name)
        for part in ("upper_wall", "lower_wall", "upper_free", "lower_free"):
            if shop:
                kind = "market" if gid == "market" else "shop"
                w(os.path.join(A, "models/block", f"{name}_{part}.json"),
                  {"parent": f"letterbanners:block/template_{kind}_{part}", "textures": {
                      "banner": f"letterbanners:block/{style}_{gid}", "rod": f"letterbanners:block/{style}_rod",
                      "tassel": "letterbanners:block/shop_tassel"}})
            else:
                w(os.path.join(A, "models/block", f"{name}_{part}.json"),
                  {"parent": f"letterbanners:block/template_{part}", "textures": {"banner": f"letterbanners:block/{style}_{gid}"}})
        variants = {}
        for facing, r in ROT.items():
            for half in ("upper", "lower"):
                for wall in ("true", "false"):
                    m = {"model": f"letterbanners:block/{name}_{half}_{'wall' if wall == 'true' else 'free'}"}
                    if r:
                        m["y"] = r
                    variants[f"facing={facing},half={half},wall={wall}"] = m
        w(os.path.join(A, "blockstates", name + ".json"), {"variants": variants})
        w(os.path.join(A, "models/item", name + ".json"),
          {"parent": "item/generated", "textures": {"layer0": f"letterbanners:item/{style}_{gid}"}})
        lang[f"block.letterbanners.{name}"] = f"{sname} Banner: {label(gid, ch)}"
        w(os.path.join(D, "loot_tables/blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "letterbanners:" + name}],
                       "conditions": [{"condition": "minecraft:survives_explosion"},
                                      {"condition": "minecraft:block_state_property", "block": "letterbanners:" + name,
                                       "properties": {"half": "lower"}}]}]})
        w(os.path.join(D, "recipes", f"{name}_from_stonecutter.json"), {
            "type": "minecraft:stonecutting", "ingredient": {"tag": f"letterbanners:{style}_banners"},
            "result": "letterbanners:" + name, "count": 1})
    w(os.path.join(D, "tags/items", f"{style}_banners.json"), {"replace": False, "values": tag})

# blank banners: like a vanilla banner (6 wool + stick) plus a style ingredient, makes 2
BLANK = {
    "aurum": ("minecraft:yellow_wool", "minecraft:black_dye"),
    "frost": ("minecraft:blue_wool", "minecraft:white_dye"),
    "ember": ("minecraft:red_wool", "minecraft:gold_nugget"),
    # shop banners: string in the middle (it becomes the tassel)
    "royal": ("minecraft:blue_wool", "minecraft:string"),
    "crimson": ("minecraft:red_wool", "minecraft:string"),
    "azure": ("minecraft:light_blue_wool", "minecraft:string"),
}
for style, (wool, extra) in BLANK.items():
    w(os.path.join(D, "recipes", f"{style}_banner_blank.json"), {
        "type": "minecraft:crafting_shaped", "pattern": ["WWW", "WXW", " S "],
        "key": {"W": {"item": wool}, "X": {"item": extra}, "S": {"item": "minecraft:stick"}},
        "result": {"item": f"letterbanners:{style}_banner_blank", "count": 2}})
w(os.path.join(A, "lang/en_us.json"), lang)
print("ok", len(lang))
