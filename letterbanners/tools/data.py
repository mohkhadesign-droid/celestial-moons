"""Generates blockstates, models, item models, lang, loot tables, tags and recipes for every letter banner."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from textures import GLYPHS

R = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources"
A = os.path.join(R, "assets/letterbanners")
D = os.path.join(R, "data/letterbanners")
STYLES = {"aurum": "Aurum Forge", "frost": "Frost Spell", "ember": "Ember Gears"}
NAMES = {"amp": "&", "excl": "!", "question": "?", "dash": "-", "blank": "Blank"}

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

ROT = {"north": 0, "east": 90, "south": 180, "west": 270}
lang = {"itemGroup.letterbanners": "Letter Banners"}
for style, sname in STYLES.items():
    tag = []
    for gid, ch in GLYPHS:
        name = f"{style}_banner_{gid}"
        tag.append("letterbanners:" + name)
        for part in ("upper_wall", "lower_wall", "upper_free", "lower_free"):
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
        label = NAMES.get(gid, ch)
        lang[f"block.letterbanners.{name}"] = f"{sname} Banner: {label}" if gid != "blank" else f"{sname} Banner (Blank)"
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
}
for style, (wool, extra) in BLANK.items():
    w(os.path.join(D, "recipes", f"{style}_banner_blank.json"), {
        "type": "minecraft:crafting_shaped", "pattern": ["WWW", "WXW", " S "],
        "key": {"W": {"item": wool}, "X": {"item": extra}, "S": {"item": "minecraft:stick"}},
        "result": {"item": f"letterbanners:{style}_banner_blank", "count": 2}})
w(os.path.join(A, "lang/en_us.json"), lang)
print("ok", len(lang))
