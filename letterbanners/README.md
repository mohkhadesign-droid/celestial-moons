# Letter Banners (`letterbanners`)

A small Forge 1.16.5 mod: two-block-tall letter banners in three styles, to spell out signs like the gate banners.

| Style | Look |
|---|---|
| **Aurum** | gold cloth, black block letters, black diamond ornaments, pointed hem |
| **Frost** | deep navy cloth, icy white letters with icicles, frosted border, violet gems and sparkles |
| **Ember** | red-orange cloth in a gold frame with iron rivets, black letters with a hot orange rim |
| **Royal Shop** | royal blue and cream: ornamental frame with diamonds, pointed hem, striped rod and cream tassel |
| **Crimson Shop** | the same design in crimson red and cream |
| **Azure Shop** | the same design in bright azure blue and cream |

Every style has **A–Z, 0–9, & ! ? -** and a **blank** banner. The three shop styles also have the **Market** banner
(a striped market canopy over a closed crate). 41 + 42 per style: **249 banners** in total.

The shop banners follow the SHOPS reference: the frame and the market design were transcribed pixel by pixel from
it, S, H, O and P are the reference letters, and the rest of the alphabet and the numbers are drawn in the same
style (7-pixel strokes, flat tops, 45° chamfers). The striped rod and the tassel are real 3D model parts. Rods of
neighbouring banners join into one continuous rod. The market banner is wider than a block (like the reference), so
it overlaps its neighbours slightly; give it a free block on each side if you want a gap.

## Placing
- **On a wall:** right-click the side of a block. The banner hangs flat against it, 2 blocks tall (it needs the block below to be free).
- **Hanging:** right-click the underside of a block (a beam, a ceiling). It hangs in the middle of the block on two short chains.
- **On the floor:** right-click the top of a block; it stands 2 blocks tall facing you. (Not for the shop styles: their
  tassel hangs below the banner, so they go on a wall or under a block only.)
- **Shop banners** hang a little lower than the two blocks they occupy: the pointed hem and tassel reach about half a
  block into the space below, like a vanilla wall banner. Keep that space free of blocks.
- Both sides show the same picture, readable the right way round. (Seen from behind, a word reads in reverse order, as with any row of signs.)
- Breaking either half removes the whole banner and drops one.

## Getting them
- **Blank banner (makes 2):** 6 wool + 1 style item + 1 stick, like a vanilla banner with the style item in the middle:
  - Aurum: yellow wool + black dye
  - Frost: blue wool + white dye
  - Ember: red wool + gold nugget
  - Royal Shop: blue wool + string
  - Crimson Shop: red wool + string
  - Azure Shop: light blue wool + string
- **Any letter:** put a banner of that style in a **stonecutter** and pick the character (any letter can be turned into any other of the same style).
- Creative tab: *Letter Banners*. Names read like "Royal Shop Banner: Letter A", "…: Number 1", "…: Market".
- Commands: `/give @p letterbanners:<style>_banner_<char> 16`, styles `aurum`, `frost`, `ember`, `royal`, `crimson`,
  `azure`; characters `a`–`z`, `0`–`9`, `amp`, `excl`, `question`, `dash`, `blank`, and `market` (shop styles).
  For example `/give @p letterbanners:royal_banner_market` or `/give @p letterbanners:crimson_banner_s 2`.

## Install
Put `letterbanners-1.0.0-bNN.jar` in `mods/` on the server **and** every client (it adds blocks). It doesn't depend on any other mod.

## Building
`tools/textures.py` draws the aurum/frost/ember textures and icons. `tools/shop_textures.py` draws the shop
collection from `tools/shop_templates.py` (the two frames, transcribed from the references) and `tools/shop_glyphs.py`
(the lettering), plus the striped rods and the tassel. `tools/data.py` writes blockstates, models (including the rod
and tassel geometry), loot tables, recipes, tags and names. Re-run them after any change.
CI (`.github/workflows/letterbanners.yml`) builds the jar, runs a real client test with screenshots of the fronts and backs, and publishes everything to the `lb-out` branch.
