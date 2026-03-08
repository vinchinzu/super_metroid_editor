# Roadmap

# Boss Stats Editor
GUI patch editor for all major and mini-boss stats (HP, contact damage, per-attack damages).
Covers Kraid, Phantoon, Ridley, Draygon, Mother Brain, Spore Spawn, Crocomire, Botwoon, Golden Torizo.
Includes sub-enemy attack damages (Kraid claws, Ridley fireballs, Draygon turrets, etc.).
Status: In Progress

# Enemy Stats Editor
GUI editor for common enemy HP and contact damage values.
Browse all enemy species in the game, edit HP and damage, see changes reflected on export.
Status: In Progress

# Instant Respawn on Death
Skip the Game Over screen entirely and reload the last save point instantly.
Inspired by Kaizo Possible's instant respawn. Implemented as a toggleable patch.
Status: In Progress

# Hyper Beam Patch
Enable Hyper Beam from the start of the game (the rainbow beam Samus gets during Mother Brain fight).
Status: In Progress

# Death Counter
Track number of deaths across a playthrough, persisted in SRAM.
Display on HUD or pause screen. Useful for Kaizo hack playtesting.
Status: Planned

# Enemy Sprite Export/Import
Decompress, view, and edit enemy sprite graphics from the ROM.
Export to PNG, import modified sprites, recompress and write back.
Enables full visual customization of enemies and bosses.
Status: Planned

# Room Scroll Editor
Edit room scroll data (which screens are visible, scroll types).
Live preview of scroll boundaries in the map editor.
Status: Planned

# FX Editor Enhancements
Visual editor for room FX (water level, lava, acid, rain, fog).
Palette blend previews and animated FX parameter tuning.
Status: Planned

# Music/Tileset Editor
Change music tracks and tileset assignments per room.
Preview music via SPC playback. Tileset swapping with live tile preview.
Status: Planned

# BG Scrolling Editor
Edit background scrolling modes and Layer 2 scroll data.
Parallax configuration, BG tilemap editing.
Status: Planned

# Tile/Pattern Config as JSON
Move core CRE tile meta (TilesetDefaults) and built-in patterns to JSON config in resources/.
Enables easier compare/contrast/merge of updates against base config. Override layer (project.tileDefaults) remains for user edits.
Status: Planned

# Investigate: 2×2 Test Patterns
Some users report 2×2 patterns appearing in pattern list (not built-in). Source unknown — may be from create-pattern defaults or legacy seed logic. Remove source if found.
Status: Planned

# Kill Count Editor
Expose the enemy kill count byte in the UI. Currently preserved from vanilla — if enemies are removed so fewer than the kill count remain, gray doors become impossible to open. Add validation warnings and allow manual override.
Status: Planned

# Multi-State Room Editing
Support editing enemy populations and GFX sets for non-default room states (e.g., boss-dead E629 conditions). Currently only the default E5E6 state is edited. Add state selector UI and per-state enemy lists.
Status: Planned

# Enemy GFX Limit Warnings
Surface the 4-entry GFX hardware limit in the UI. Show which species are dropped when the limit is exceeded, and suggest merging palette slots or removing species. Prevent silent garbled sprites.
Status: Planned

# Null Enemy Pointer Room Support
Allow adding enemies to rooms that have a null (0x0000) enemy population pointer. Allocate a new enemy set in bank $A1 free space and wire up the state data pointer.
Status: Planned