# Letter Banners (`letterbanners`)

A small Forge 1.16.5 mod: two-block-tall letter banners in three styles, to spell out signs like the gate banners.

| Style | Look |
|---|---|
| **Aurum** | gold cloth, black block letters, black diamond ornaments, pointed hem |
| **Frost** | deep navy cloth, icy white letters with icicles, frosted border, violet gems and sparkles |
| **Ember** | red-orange cloth in a gold frame with iron rivets, black letters with a hot orange rim |

Every style has **A–Z, 0–9, & ! ? -** and a **blank** banner (41 per style, 123 in total).

## Placing
- **On a wall:** right-click the side of a block. The banner hangs flat against it, 2 blocks tall (it needs the block below to be free).
- **Hanging:** right-click the underside of a block (a beam, a ceiling). It hangs in the middle of the block on two short chains.
- **On the floor:** right-click the top of a block; it stands 2 blocks tall facing you.
- Both sides show the same picture, readable the right way round. (Seen from behind, a word reads in reverse order, as with any row of signs.)
- Breaking either half removes the whole banner and drops one.

## Getting them
- **Blank banner (makes 2):** 6 wool + 1 style item + 1 stick, like a vanilla banner with the style item in the middle:
  - Aurum: yellow wool + black dye
  - Frost: blue wool + white dye
  - Ember: red wool + gold nugget
- **Any letter:** put a banner of that style in a **stonecutter** and pick the character (any letter can be turned into any other of the same style).
- Creative tab: *Letter Banners*. Or e.g. `/give @p letterbanners:aurum_banner_a 16` (names: `<style>_banner_<char>`, with `amp`, `excl`, `question`, `dash`, `blank`).

## Install
Put `letterbanners-1.0.0-bNN.jar` in `mods/` on the server **and** every client (it adds blocks). It doesn't depend on any other mod.

## Building
`tools/textures.py` draws all textures and icons; `tools/data.py` writes blockstates, models, loot tables, recipes, tags and names.
CI (`.github/workflows/letterbanners.yml`) builds the jar, runs a real client test with screenshots of the fronts and backs, and publishes everything to the `lb-out` branch.
