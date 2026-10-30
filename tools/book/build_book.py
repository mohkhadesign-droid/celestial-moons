"""Generates the Patchouli "Lunar Field Guide" (data + assets). Values are the add-on's defaults."""
import json, os, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources"
DATA = os.path.join(ROOT, "data/ecmoons/patchouli_books/field_guide")
# Patchouli 1.16 reads book contents from the mod's data folder (assets/ only works from 1.17 on)
ASSETS = os.path.join(ROOT, "data/ecmoons/patchouli_books/field_guide/en_us")
os.makedirs(DATA, exist_ok=True)

def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")

write(os.path.join(DATA, "book.json"), {
    "name": "Lunar Field Guide",
    "landing_text": "Every few nights the moon rises changed. This guide lists each moon: how to recognise it, what it does, how long it lasts and how to play around it.$(br2)Numbers are the add-on's defaults; a server can change them in $(o)ecmoons-common.toml$().",
    "version": 1,
    "book_texture": "patchouli:textures/gui/book_purple.png",
    "model": "patchouli:book_purple",
    "creative_tab": "misc",
    "show_progress": False,
    "subtitle": "Enhanced Celestials Moons",
})

CATS = {
    "basics": ("Reading the Sky", "How lunar events work, the Moon Ability key and commands everyone can use.", "minecraft:clock", 0),
    "moons": ("Moons", "One page per moon: what it looks like, what it does and its limits.", "minecraft:ender_eye", 1),
    "abilities": ("Moon Abilities", "Moons that give the $(l)Moon Ability$() key something to do.", "minecraft:phantom_membrane", 2),
}
for cid, (name, desc, icon, n) in CATS.items():
    write(os.path.join(ASSETS, "categories", cid + ".json"), {"name": name, "description": desc, "icon": icon, "sortnum": n})

def text(t, title=None):
    p = {"type": "patchouli:text", "text": t}
    if title:
        p["title"] = title
    return p

def image(moon, title, caption):
    return {"type": "patchouli:image", "images": ["ecmoons:textures/gui/book/%s.png" % moon], "title": title, "border": True, "text": caption}

ENTRIES = []

def entry(cat, eid, name, icon, pages, sortnum, priority=False):
    ENTRIES.append(eid)
    e = {"name": name, "icon": icon, "category": "ecmoons:" + cat, "sortnum": sortnum, "pages": pages}
    if priority:
        e["priority"] = True
    write(os.path.join(ASSETS, "entries", eid + ".json"), e)

# ---- basics -----------------------------------------------------------------------------------------------------
entry("basics", "how_moons_work", "How Moons Work", "minecraft:clock", [
    text("At dusk the server may pick a lunar event for the night. Each moon has its own chance and look; only one can be up at a time, and it sets at dawn.$(br2)A moon only affects the dimension it rises in (normally the Overworld). The Nether and the End never get one.", "How Moons Work"),
    text("You'll see a message when a moon rises and when it sets. The sky, fog and light change colour while it's up, and some moons add particles or sounds.$(br2)Players who join in the middle of a moon see it straight away.$(br2)How much of the sky effects you see is set per player in $(o)config/ecmoons-client.toml$(): $(l)quality$() OFF / LOW / MEDIUM / HIGH. It never changes what a moon does."),
], 0, True)
entry("basics", "moon_ability_key", "The Moon Ability Key", "minecraft:phantom_membrane", [
    text("Some moons give you an ability. They all use one key: $(l)Moon Ability$(), $(k:ecmoons.moon_ability) by default. Change it under $(o)Options > Controls > Enhanced Celestials Moons$().$(br2)On any other night the key just says \"No moon ability tonight\".", "Moon Ability Key"),
    text("The server decides everything: timing, distance, cooldowns and where an ability is allowed. A small indicator next to the crosshair shows steps, windows, charge and cooldowns.$(br2)Abilities never work in the War Moon arena or for players taking part in a War Moon tournament. They end if you die, log out or change dimension."),
], 1)
entry("basics", "player_commands", "Commands for Everyone", "minecraft:paper", [
    text("$(li)$(l)/warmoon status$(): War Moon tournament state and your remaining War Champion time.$(li)$(l)/warmoon spectate$(): toggle watching the next War Moon tournament from the gallery instead of fighting.$(br2)Everything else under $(o)/ecmoons$() is for server operators.", "Commands"),
], 2)

# ---- moons ------------------------------------------------------------------------------------------------------
M = []
def moon(mid, name, icon, caption, *pages):
    M.append((mid, name, icon, caption, pages))

moon("blood", "Blood Moon", "minecraft:redstone", "A red moon. Monsters swarm and nobody sleeps.",
     "Hostile mobs spawn in far greater numbers (Enhanced Celestials' own rule) and beds don't work.$(br2)$(l)Rare drops$() from monsters killed by a player are 25% more likely: a 4% wither skull becomes 5%. Normal drops are never doubled.",
     "$(l)Limits:$() spawner mobs and machine kills (fake players) don't get the bonus. Equipment a mob was wearing isn't a loot-table drop and isn't affected.$(br2)$(l)Counterplay:$() light up and wall in your base before dusk; it's a good night to farm skulls.")
moon("blue", "Blue Moon", "minecraft:prismarine_crystals", "A calm blue moon. Lucky night.",
     "Everyone gets $(l)Luck$() (Enhanced Celestials).$(br2)Fishing bites come 30% sooner, and Lure still works on top. Some catches can include extra bonus loot chosen by the server.",
     "$(l)Tip:$() it's the best night for fishing and for opening loot chests.")
moon("harvest", "Harvest Moon", "minecraft:wheat", "A golden moon over the fields.",
     "Crops grow about twice as fast: each crop's growth tick gets one extra roll. Mature crops drop double (Enhanced Celestials).$(br2)You get hungry 25% more slowly.",
     "$(l)Limits:$() only crops listed by the server grow faster (wheat, carrots, potatoes, beetroot, nether wart, cocoa, berries, sugar cane, cactus, bamboo by default). Immature crops aren't doubled. Other blocks and the world's tick speed are untouched.")
moon("mist", "Mist Moon", "minecraft:white_wool", "A pale, smudged moon. Thick fog rolls in.",
     "A grey fog fades in over 10 seconds: you can see about 24-30 blocks.$(br2)Name tags of other players are hidden beyond 22 blocks, and footsteps are heard at half the usual distance. Combat and other sounds are unchanged.",
     "$(l)Counterplay:$() listen for attacks, not footsteps; stay close to friends.$(br2)Shader packs and OptiFine's \"Fog: Off\" may not show the fog, but name tags and footsteps are still reduced by the server.")
moon("frost", "Frost Moon", "minecraft:snowball", "An icy, cracked moon.",
     "After 20 seconds under the open sky you get cold: 15% slower.$(br2)$(l)Warm up:$() stand within 4 blocks of a lit campfire, fire, lava, magma or a lit furnace; go under any roof; or eat (45 s of warmth).",
     "Each piece of leather armour gives 10 more seconds before you get cold; a full leather set keeps you warm all night.$(br2)Blocks, water and crops never freeze.")
moon("arcane", "Arcane Moon", "minecraft:lapis_lazuli", "A violet moon covered in runes.",
     "Enchanting and anvils cost 25% fewer levels (rounded, minimum 1): the enchanting table costs 1 / 2 / 2 levels. Material repairs on an anvil restore 25% more per material.",
     "$(l)Limits:$() the level requirement and lapis cost don't change. A \"Too Expensive!\" job stays too expensive. The discount for a table is decided when you open it, so a moon setting mid-use can't trick you.")
moon("prospector", "Prospector's Moon", "minecraft:diamond", "A moon glinting with ore.",
     "Mining natural ore by hand: 25% chance of one extra drop. Stone and ores break 25% faster.$(br2)Exposed ore faces in view glint softly.",
     "$(l)Limits:$() ore you placed yourself, cobblestone/stone from generators and blocks moved by pistons don't count. Silk Touch disables the bonus, which doesn't stack with Fortune. Machines and fake players are excluded.")
moon("wisdom", "Wisdom Moon", "minecraft:experience_bottle", "A deep blue moon traced with constellations.",
     "Double XP from monsters killed by a player and from natural ores mined by hand.$(br2)$(l)Limits:$() spawner mobs, XP bottles, smelting, breeding, fishing and trading are unchanged. Picking up orbs is never multiplied.")
moon("quiet", "Quiet Moon", "minecraft:white_bed", "A soft, pale moon.",
     "Only about a third as many monsters spawn (existing ones stay).$(br2)If you're well fed (18+ food) and haven't hit or been hit for 12 seconds, you heal half a heart every 2 seconds on top of normal healing.",
     "$(l)Tip:$() a good night to explore or build outside. PvP, claims and raids are unchanged.")
moon("drift", "Drift Moon", "minecraft:feather", "A light moon with drifting fragments.",
     "Jumps go a little higher (about 1.6 blocks) and you fall more slowly. Fall damage is halved, and jumping costs half the hunger.$(br2)Two-block walls still stop you.",
     "If the moon sets while you're in the air, your landing is still softened. Sprinting, attacks and projectiles are unchanged.")
moon("revealing", "Revealing Moon", "minecraft:ender_eye", "An eye opens in the sky. It blinks.",
     "Every 60 seconds: \"The eye is searching...\" and a 5-second countdown. The eye narrows, then opens wide, and for the last 3 seconds it stares fully open.$(br2)When the countdown ends it has seen: one golden ripple crosses the sky and everyone within 256 blocks of you is marked for 8 seconds.",
     "Each mark is a spectral eye with a rough distance and direction, never coordinates. Underground players are marked at the surface above them.$(br2)$(l)Never revealed:$() spectators, creative players, War Moon fighters and areas the server protects. Claims and roofs don't hide you.",
     "$(l)Binding Gaze:$() don't stare into the open eye. Two seconds looking straight at it, with open sky between you, and you can't walk, sprint or jump. You can still look around, fight, block and use items.$(br2)Look away to break free. It also ends when the scan does. A roof or cave ceiling protects you.",
     "$(l)Once you are seen$() (8 s, your own timer) the screen edges glow amber and the world's colours drain, coming back as your time runs out.$(br2)$(l)True Exposure:$() Invisibility can't hide you. The potion keeps counting down and hides you again afterwards if it hasn't run out.",
     "$(l)Read Your Enemy:$() while you are seen, hold your aim on another player within 32 blocks for one second (clear view) to read their health, absorption and effects.$(br2)$(l)The world notices:$() nearby monsters walk to where you stood when the eye saw you, search, then give up after about 10 seconds.",
     "$(l)Counterplay:$() keep moving between scans, don't look up when the eye opens wide, and leave the spot where you were seen. Monsters only know that old spot.")
moon("thread", "Thread Moon", "minecraft:string", "A yarn moon. A black cat plays with its threads.",
     "Teammates (teams are set by the server with $(o)/moonteams$()) within 12 blocks with a clear line of sight are $(l)bonded$(). Each bonded teammate gives 5% damage reduction, up to 15%. A thin rose thread links you; only your team sees it.",
     "Separate and the bonus fades over 3 seconds.$(br2)$(l)Not reduced:$() fall, fire, lava, drowning, starvation, magic, wither and the void.$(br2)Abilities below need a bond $(l)right now$(): a wall or distance ends them at once.",
     "$(l)Lifeline$() (crouch + hold the Moon Ability key on an injured teammate within 4 blocks): after a second of aim, you give half a heart each second, up to 2 hearts. You lose exactly what they gain and never drop below 3 hearts. Taking damage, attacking or looking away stops it. 10 s cooldown.",
     "$(l)Guard Thread$() (Guard key, default G, on a bonded teammate; again to clear): you take 20% of the real health they lose to attacks, at most one heart per hit, never going below 3 hearts. One guardian each; no chains.",
     "$(l)Last Stitch$(): after 3 s of active guard, a lethal attack leaves your teammate at half a heart and costs you 2 hearts. Once per player per moon. A totem in hand is used instead.",
     "$(l)Knotbind$() (Moon Ability key): throw yarn at an enemy, then at a second enemy within 6 blocks of the first, both within 3 s. They're tied for 5 s: free to fight, but pulled back if they go past 6 blocks. Hold the key while standing still for 1.5 s to $(l)unravel$(). 25 s cooldown.",
     "$(l)Watch the moon:$() the cat reacts when someone gives a Lifeline, a guard first takes a hit, a knot ties, or a Last Stitch saves a life.")
moon("war", "War Moon", "minecraft:iron_sword", "A cracked, burning moon. The arena calls.",
     "Two minutes after it rises, every eligible online player (up to 16) fights in a separate arena with the same kit. Your own gear, XP and effects are saved first and given back afterwards.$(br2)Use $(l)/warmoon spectate$() to watch from the gallery instead.",
     "10 seconds in the cells, then the gates open. Lethal damage knocks you out to the gallery: no death, no drops. After 6 minutes a shrinking safe square starts.$(br2)The last fighter standing becomes $(l)War Champion$(): Haste I and a gold aura for 30 minutes of online time.")
moon("gluttony", "Gluttony Moon", "minecraft:cooked_beef", "A moon that looks good enough to eat.",
     "Each different kind of food you finish eating gives one extra max heart, up to 4. Raw and cooked versions are one kind; repeats don't count. The new hearts start empty.",
     "The hearts go away when the moon sets, when you die, leave the dimension or enter a War Moon match. Rotten flesh, spider eyes, poisonous potatoes and pufferfish never count.")
moon("afterimage", "Afterimage Moon", "minecraft:phantom_membrane", "A lavender moon trailed by fading copies of itself.",
     "Gives the $(l)Moon Ability$() key the $(l)Frame Step$(): a three-step dash in rhythm. See the $(o)Moon Abilities$() chapter for the timing.$(br2)Each step leaves a fading afterimage of you that everyone nearby can see.")
moon("shell", "Shell Moon", "minecraft:turtle_helmet", "A pale green moon with a shell-plate pattern.",
     "Gives the $(l)Moon Ability$() key the $(l)Shell$(): crouch and hold to raise a dome that stops every projectile. See the $(o)Moon Abilities$() chapter.")

moon("wisp", "Wisp Moon", "minecraft:soul_lantern", "A slate moon holding a pale blue soul flame.",
     "Gives the $(l)Moon Ability$() key the $(l)Soul Observers$(): pilot a small blue wisp to scout, place up to three observers and look through them later. See the $(o)Moon Abilities$() chapter.")

for i, (mid, name, icon, caption, pages) in enumerate(M):
    ps = [image(mid, name, caption)] + [text(p) for p in pages]
    entry("moons", mid + "_moon", name, icon, ps, i)

# ---- abilities --------------------------------------------------------------------------------------------------
entry("abilities", "frame_step", "Frame Step (Afterimage)", "minecraft:phantom_membrane", [
    image("afterimage", "Frame Step", "Afterimage Moon only. Three timed dashes: 2, 3 and 4 blocks."),
    text("Press $(k:ecmoons.moon_ability) to dash $(l)2 blocks$() the way your movement keys point (or where you look). When the step ends, a short window opens (about a quarter of a second): press again for $(l)3 blocks$(), then again for $(l)4 blocks$(). Each step can turn up to 45 degrees.", "How it works"),
    text("$(l)Too early$() (while still moving, or before the window): the sequence breaks and you're slowed by 40% for half a second.$(br2)Miss the window and the sequence simply ends.$(br2)After the third step, your next melee hit within 1 second has $(l)+60% knockback$(), once."),
    text("$(l)Cooldown:$() 8 seconds, starting when the sequence ends.$(br2)$(l)Limits:$() steps stop at walls, doors, ceilings and the world border; you never pass through blocks. No dashing while flying, gliding, swimming or riding. There's no invulnerability and fall damage is normal.$(br2)$(l)Counterplay:$() back away after the third step; the bonus only lasts a second."),
], 0)
entry("abilities", "shell", "Shell", "minecraft:turtle_helmet", [
    image("shell", "Shell", "Shell Moon only. A dome that stops every projectile, both ways."),
    text("$(l)Crouch$() and $(l)hold$() $(k:ecmoons.moon_ability). After 2 seconds of charging without moving, a dome of radius 3 rises around you for up to 6 seconds.$(br2)It collapses when you let go, stand up, move, when it runs out or breaks.", "How it works"),
    text("$(l)Projectiles$() can't cross it in either direction: arrows, tridents, potions, pearls, fireballs, snowballs and fireworks. Arrows and tridents drop as items at the edge. Nothing splashes, teleports or explodes past it.$(br2)$(l)Durability:$() 100. Each projectile costs 10, explosive ones 30."),
    text("Players inside take $(l)75% less$() explosion damage.$(br2)$(l)Not blocked:$() people and mobs can walk in and out, and melee works as normal. Shells can't overlap.$(br2)$(l)Cooldown:$() 20 seconds after it collapses. You can't raise one where you aren't allowed to build (claims and protected areas).$(br2)$(l)Counterplay:$() walk in and fight up close, or wait it out."),
], 1)

entry("abilities", "soul_observers", "Soul Observers (Wisp)", "minecraft:soul_lantern", [
    image("wisp", "Soul Observers", "Wisp Moon only. Scout with a wisp; watch through observers."),
    text("Press $(k:ecmoons.moon_ability) to open the Wisp menu. $(l)Pilot Wisp$() sends a small blue wisp out from your eyes and you see through it. Fly with your movement keys; jump and sneak go up and down. It can't pass walls or closed doors and stays within $(l)48 blocks$() of your body.", "Piloting"),
    text("Your body stays behind, visible and vulnerable: it can't move, attack, use items or interact. $(l)Any damage$() to your body brings you straight back, and so does moving it.$(br2)Press $(k:ecmoons.moon_ability) or Esc to return. Piloting lasts up to 90 seconds; then a 10-second cooldown."),
    text("While piloting, press $(l)Use$() (right-click) to place a $(l)soul observer$() on the block face the wisp looks at (within 4.5 blocks). Up to $(l)3$(); at the limit, dismiss one from the menu first. Not where you're not allowed to build (claims, protected areas)."),
    text("From your body, the menu lists your observers by number. $(l)View$() one to look through it; you can only turn about 60 degrees from where it faces. Observers more than 48 blocks away show as out of range.$(br2)Damage, moving, $(k:ecmoons.moon_ability) or Esc return you to your body."),
    text("$(l)Counterplay:$() observers and wisps glow and drift soul sparks, so they're easy to spot; any hit destroys one. A player watching through one is standing still somewhere with their guard down.$(br2)Observers vanish when the moon sets or their owner leaves. No x-ray, no markers: an observer only sees what's in front of it."),
], 2)

print("entries:", len(ENTRIES))
