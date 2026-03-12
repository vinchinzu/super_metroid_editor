# Roadmap

**See also:** `smile_parity.md` for complete SMILE vs SMEDIT feature comparison.
**See also:** `plan.md` for detailed implementation notes per feature.

---

## In Progress

### Boss Stats Editor
GUI patch editor for all major and mini-boss stats (HP, contact damage, per-attack damages).
Covers Kraid, Phantoon, Ridley, Draygon, Mother Brain, Spore Spawn, Crocomire, Botwoon, Golden Torizo.
Includes sub-enemy attack damages (Kraid claws, Ridley fireballs, Draygon turrets, etc.).

### Enemy Stats Editor
GUI editor for common enemy HP and contact damage values.
Browse all enemy species in the game, edit HP and damage, see changes reflected on export.

### Instant Respawn on Death
Skip the Game Over screen entirely and reload the last save point instantly.
Inspired by Kaizo Possible's instant respawn. Implemented as a toggleable patch.
**Note:** Shelved as of 2026-03-12 — multiple patch attempts freeze after death animation. Needs deeper investigation.

### Hyper Beam Patch
Enable Hyper Beam from the start of the game (the rainbow beam Samus gets during Mother Brain fight).

---

## Planned — Tier 1 (High Impact, Achievable)

### Samus Physics Editor  **[NEW — SMILE parity]**
Full GUI for ~40 physics constants: jump heights (normal, hi-jump, walljump, underwater, lava), running speed/acceleration, air control, gravity, fall speed, knockback, momentum.
Config patch with labeled sliders grouped by category. Essential for Kaizo/challenge hacks.
Data: Bank $91 scattered addresses.

### Palette Editor  **[NEW — SMILE parity]**
Edit 256-color tileset palettes (8 rows x 16 colors), enemy palettes via RGB sliders.
Import/export .pal files. Store overrides in project customGfx.
Visual customization is the #1 most-requested ROM hacking feature.

### Enemy Vulnerability/Resistance Editor  **[NEW — SMILE parity]**
Per-weapon damage multiplier table (22 bytes per species). Wave, Ice, Spazer, Plasma, Charge, Missile, Super, PB.
Grid UI in enemy stats panel. "Immune/Weak/Normal" quick presets.
Data: Bank $B4.

### Enemy Drop Rate Editor  **[NEW — SMILE parity]**
6 entries per species: small energy, large energy, missile, gap, super missile, power bomb.
Add to existing enemy stats panel. Data: Bank $B4.

### BTS Sub-Type Expansion
Expand btsOptionsForBlockType() with all SMILE-documented BTS values for Crumble, Speed, Grapple, Bomb, and Spike block types.
Quick win — design already documented in plan.md.

### Music/Song Selector  **[NEW — SMILE parity]**
Room music dropdown with 36+ named song sets and tracks. Write to state data offsets +4/+5.
Already have setStateDataChange() — just needs UI dropdown.

### Enemy Names Expansion
Merge SMILE's 123 enemy names into ENEMY_NAMES map. Target: 40 → 120+ named enemies.

### Enemy Sprite Images on Map
Convert SMILE's 146 GIF sprites to PNGs. Render on canvas at enemy positions instead of diamond markers.

---

## Planned — Tier 2 (High Impact, More Effort)

### Room Header Editor  **[NEW — SMILE parity]**
Make all room header fields editable: area assignment, minimap position, dimensions, scrollers, CRE flag.
Currently read-only. Data: 11-byte header in bank $8F.

### Multi-State Room Editing
Full per-state editing of enemies, PLMs, scrolls, FX, music, BG scrolling.
SMILE supports all 9 state condition types. Major architectural work but essential for boss rooms and event progression.

### Tileset/Metatile Composer  **[NEW — SMILE parity]**
Define 16x16 metatiles from 4 8x8 tiles. Per sub-tile: palette row, H/V flip, BTS assignment.
Complex but powerful — enables truly custom tilesets.

### Save Station Spawn Point Editor  **[NEW — SMILE parity]**
Dedicated spawn X/Y/screen configuration per save station PLM.
Expose in PLM param editor.

### Door Cloning Tool  **[NEW — SMILE parity]**
Click screen edge to auto-calculate all door properties (cap position, spawn point, direction, distance).
Speeds up door creation massively.

### Validation Suite  **[NEW — SMILE parity]**
- PLM index duplicate scanner (find conflicting item IDs)
- Door consistency validator (destinations exist, coords valid)
- Item bitflag uniqueness checker
- Enemy GFX 4-entry hardware limit warnings
- Room state condition validator

---

## Planned — Tier 3 (Medium Impact)

### Layer 2/BG Scrolling Editor  **[NEW — SMILE parity]**
Parallax mode selector (fixed, follow, custom scroll rates), BG data pointer editing, BG tileset selection.

### Death Counter
Track number of deaths across a playthrough, persisted in SRAM.
Display on HUD or pause screen. Useful for Kaizo hack playtesting.

### Enemy Sprite Export/Import
Decompress, view, and edit enemy sprite graphics from the ROM.
Export to PNG, import modified sprites, recompress and write back.

### Room Scroll Editor
Edit room scroll data (which screens are visible, scroll types).
Live preview of scroll boundaries in the map editor.

### FX Editor Enhancements
Visual editor for room FX (water level, lava, acid, rain, fog).
Palette blend previews and animated FX parameter tuning.

### Music/Tileset Editor
Tileset swapping with live tile preview. Preview music via SPC playback.

### BG Scrolling Editor
Edit background scrolling modes and Layer 2 scroll data.
Parallax configuration, BG tilemap editing.

### Tile/Pattern Config as JSON
Move core CRE tile meta (TilesetDefaults) and built-in patterns to JSON config in resources/.

### Kill Count Editor
Expose the enemy kill count byte in the UI. Add validation warnings when enemies are removed below kill threshold for gray doors.

### Enemy GFX Limit Warnings
Surface the 4-entry GFX hardware limit. Show which species are dropped when exceeded.

### Null Enemy Pointer Room Support
Allow adding enemies to rooms with null (0x0000) enemy population pointer.

---

## Planned — Tier 4 (Backlog)

### Samus Pose/Animation Editor  **[NEW — SMILE parity]**
Configure Samus animation poses for different equipment states.

### Enemy AI Pointer Editor  **[NEW — SMILE parity]**
Edit Init, Main, Shot, Hurt, Touch, PB, Grapple, X-Ray, Frozen AI routine pointers per species.

### Enemy Graphics/Layer Priority  **[NEW — SMILE parity]**
Tile data pointer, size, layer priority (front/behind/background) per species.

### ROM Data Export/Import  **[NEW — SMILE parity]**
Export/import arbitrary BIN chunks at any LoROM address/size.

### Mapshot Tool  **[NEW — SMILE parity]**
Render entire room or area to PNG image file.

### Hotkey Configuration  **[NEW — SMILE parity]**
Customizable keyboard shortcuts for all editor tools.

### Free Space Tracker  **[NEW — SMILE parity]**
Monitor available bytes in shared ROM banks ($8F, $83, $A1, $B4, $C0-$CE).

### Investigate: 2x2 Test Patterns
Some users report 2x2 patterns appearing in pattern list. Source unknown.

### Plugin System  **[NEW — SMILE parity]**
Extensibility framework for custom tool integration.
