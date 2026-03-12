# SMEDIT vs SMILE — Complete Feature Parity Analysis

**Date:** 2026-03-12
**SMILE version analyzed:** SMILE RF (VB6, ~42 forms, last updated ~2009)
**SMEDIT version analyzed:** Current main branch

---

## Executive Summary

SMEDIT has **surpassed** SMILE in several critical areas (embedded emulator, boss AI tuning, sprite pixel editing, 78 ROM patches, cross-platform, modern UI). However, SMILE still has deeper coverage in **room infrastructure editing** (doors, PLMs, room states, ASM) and **data validation/safety** features. This document catalogs every discrepancy.

---

## 1. AREAS WHERE SMEDIT EXCEEDS SMILE

These are features we have that SMILE does NOT:

| Feature | SMEDIT | SMILE |
|---------|--------|-------|
| **Embedded emulator** | Full libretro + RetroArch NWA integration, live room sync | QuickMet (basic, external only) |
| **Cross-platform** | macOS, Windows, Linux (Kotlin/Compose) | Windows only (VB6) |
| **Boss AI behavior editor** | Phantoon: 8 timer values, movement speeds, flame rain, wave params | None — hex edit only |
| **Boss stat editor (GUI)** | 6 bosses + 7 mini-bosses, per-attack damage fields | None — hex edit only |
| **Enemy stat editor (GUI)** | 60+ enemies, HP + contact damage | None — hex edit only |
| **78 ROM patches** | Movement, weapons, QoL, difficulty, physics — all toggleable | None built-in |
| **Config patches** | Ceres timer, beam damage, controller remap — GUI-driven | None |
| **Sprite pixel editor** | ARGB pixel grid for enemies + bosses | Static GIF reference only |
| **Sprite import/export** | PNG I/O with palette quantization | No sprite editing |
| **Boss sprite assembly** | Phantoon (5 blocks), Kraid (full body) — rendered from ROM | Not available |
| **IPS patch export** | Generate .ips from original vs patched ROM | Full ROM only |
| **Project system** | .smedit JSON with versioned export naming | Single ROM session |
| **Pattern library** | 22 built-in patterns + CRE/URE separation | User patterns only |
| **Undo/redo** | Full operation stack (tiles, PLMs, enemies, doors, scrolls, FX) | Limited tile undo |
| **Custom graphics pipeline** | Base64 tile/sprite overrides in project | Not available |
| **SPC audio playback** | Native SPC emulator, WAV rendering | No audio features |
| **Modern theme system** | Multiple themes, configurable font sizes | Fixed Windows UI |
| **Live emulator sync** | Follow player room in editor | Not available |
| **Boss defeated flags** | GUI toggles with ASM hook generation | Manual hex only |
| **Version-based export** | major.minor + build name in filename | N/A |

---

## 2. AREAS WHERE SMILE EXCEEDS SMEDIT

### 2.1 CRITICAL GAPS (High-impact features SMILE has that we lack)

#### A. Samus Physics Editor
**SMILE:** Full GUI for jump heights, running speed, air control, gravity, walljump, knockback, momentum — all per-state (ground, air, water, lava, morphball).
**SMEDIT:** No physics editing at all.
**Impact:** High — Kaizo/challenge hacks need physics tuning. This is a fundamental editor feature.
**Data:** Bank $91 scattered addresses, ~40 individual values.

#### B. Palette Editor
**SMILE:** Full 256-color tileset palette editing (16-color rows), enemy palette RGB sliders, palette import/export (.pal), palette blending modes.
**SMEDIT:** Can view palettes (renderPaletteImage) but cannot edit them.
**Impact:** High — visual customization is a huge part of ROM hacking. Every serious hack modifies palettes.
**Data:** Tileset palettes in bank $8F (tileset table entries), enemy palettes in bank $A0/B4.

#### C. Tileset/Metatile Editor
**SMILE:** Define 16x16 metatiles from 4 8x8 tiles, palette assignment per sub-tile, flip controls, BTS assignment, collision/slope data overlay.
**SMEDIT:** Can view and export/import tile sheets (raw 4bpp), but no metatile composition UI.
**Impact:** Medium-High — needed for creating truly custom tilesets, not just recoloring existing ones.
**Data:** Compressed metatile tables in banks $C0-$CE.

#### D. Room Header Editor
**SMILE:** Edit room index, area assignment, minimap X/Y position, dimensions, up/down scrollers, CRE graphics flag, door-out table pointer.
**SMEDIT:** Displays all header fields but they're read-only.
**Impact:** Medium — needed for creating new rooms or reassigning rooms to different areas.
**Data:** 11-byte room header in bank $8F.

#### E. Multi-State Room Editing (Full)
**SMILE:** Full state selector with per-state editing of ALL data: level data, enemies, PLMs, scrolls, music, FX, BG scrolling. Supports E5E6, E612, E629, E5FF, E5EB, E652, E669, E640, E678.
**SMEDIT:** Parses all state types and displays state list. Can select states. But only default state tiles/enemies are editable.
**Impact:** Medium-High — boss rooms, event-triggered rooms, and story progression all need multi-state editing.

#### F. Music/Song Editor
**SMILE:** Room music selection dropdown (36 sets x 2-3 tracks), per-door music change triggers, event-triggered music.
**SMEDIT:** Has SPC playback but no UI to change room music assignments. State data editor has tileset/music fields but they're basic hex.
**Impact:** Medium — music changes are common in hacks.
**Data:** Room state data offsets +4/+5 (music data/track).

#### G. Door Cloning Tool
**SMILE:** Click screen edge to auto-calculate all door properties (cap position, spawn point, direction, distance). Massively speeds up door creation.
**SMEDIT:** Door editing exists but no auto-calculation from visual placement.
**Impact:** Medium — quality of life for level designers.

#### H. Layer 2 / Background Scrolling Editor
**SMILE:** Parallax mode selection (fixed, follow, custom scroll rates), BG data pointer editing, BG tileset selection.
**SMEDIT:** Can override bgScrolling value in state data, but no dedicated visual editor.
**Impact:** Medium — background effects are important for atmosphere.
**Data:** Room state data offset +12 (BG scroll) and +22 (BG data pointer).

---

### 2.2 MODERATE GAPS (Important but less critical)

#### I. Samus Pose/Animation Editor
**SMILE:** `Poses1.frm` — configure Samus animation poses for different equipment states.
**SMEDIT:** Not available.
**Impact:** Low-Medium — niche feature for advanced hacks.

#### J. Enemy Vulnerability/Resistance Editor
**SMILE:** 22-byte damage multiplier table per weapon (Wave, Ice, Spazer, Plasma, Charge, Missile, Super, PB — 0-F scale). Freeze/kill modes. Per-species editing.
**SMEDIT:** Edits HP and contact damage, but NOT per-weapon resistances.
**Impact:** Medium — custom enemy behavior requires resistance editing.
**Data:** Bank $B4, 22-byte resistance block per species.

#### K. Enemy Drop Rate Editor
**SMILE:** 6 entries per species: small energy, large energy, missile, gap, super missile, power bomb.
**SMEDIT:** Not available.
**Impact:** Medium — drop rates affect game balance significantly.
**Data:** Bank $B4, stored per species header.

#### L. Enemy AI Pointer Editor
**SMILE:** Edit Init, Main, Shot, Hurt, Touch, PB, Grapple, X-Ray, Frozen AI routine pointers.
**SMEDIT:** Not available (we edit stats, not AI pointers).
**Impact:** Low-Medium — advanced feature for custom enemy behaviors.
**Data:** Enemy species headers in bank $A0 (64 bytes each).

#### M. Enemy Graphics/Layer Priority
**SMILE:** Tile data pointer, size, layer priority (front/behind/background) per species.
**SMEDIT:** Not available.
**Impact:** Low-Medium — needed for visual layering fixes.

#### N. Save Station Spawn Point Editor
**SMILE:** `SPI1.frm` / `LoadPoints1.frm` — dedicated spawn coordinate configuration per save station.
**SMEDIT:** Save stations can be placed as PLM patterns, but spawn point fine-tuning isn't exposed.
**Impact:** Medium — spawn points affect player experience after death.

#### O. ROM Data Export/Import (Arbitrary)
**SMILE:** Export/import BIN chunks at any LoROM address/size. Data watcher for live ROM inspection.
**SMEDIT:** Not available (we have IPS and ROM export but not arbitrary address I/O).
**Impact:** Low — power user feature.

#### P. Hotkey Configuration
**SMILE:** `HotKeys1.frm` — customizable keyboard shortcuts.
**SMEDIT:** Fixed keyboard shortcuts.
**Impact:** Low — quality of life.

---

### 2.3 MINOR GAPS (Nice-to-have)

| Feature | SMILE | SMEDIT Status |
|---------|-------|---------------|
| PLM index duplicate scanner | Scans all rooms for conflicting PLM IDs | Not available |
| Door consistency validator | Verifies destinations exist, coords valid | Not available |
| Item bitflag uniqueness check | Ensures no two items share collection bit | Not available |
| Free space tracker | Monitor available bytes in shared banks | Not available |
| Room state validity checker | Verify state conditions are sensible | Not available |
| Music validity checker | Verify song set/track pairs exist | Not available |
| Mapshot tool | Render entire room/area to image file | Not available |
| Magnifier/zoom tool | Pixel-level zoom into tile/sprite graphics | We have zoom on canvas |
| Exception rooms list | Known quirky rooms documented | Not available |
| Game behavior toggles | Infinite missiles, god mode for testing | We have patches for these |
| Room-from-map creator | Generate room layout from map geometry | Not available |
| Plugin system | Custom tool integration | Not available |
| Language/localization | Multiple language support | English only |

---

## 3. FEATURES AT PARITY

These areas are functionally equivalent or close:

| Feature | Status |
|---------|--------|
| Room tile editing (paint, fill, erase, sample) | Parity |
| LZ5 decompression/compression | Parity |
| Block type system (16 types) | Parity |
| BTS editing for shot blocks | Parity (SMILE has more sub-types for other block types — see plan.md) |
| PLM parsing and display | Parity |
| PLM placement (items, doors, gates, stations) | Parity via patterns |
| Enemy population parsing | Parity |
| Enemy placement (add/remove/move) | Parity |
| Door parsing and display | Parity |
| Door property editing | Parity (basic — SMILE has auto-clone) |
| Scroll data editing (Red/Blue/Green) | Parity |
| FX editing (16 types, liquid, blend) | Parity |
| Tile graphics rendering (2bpp/4bpp) | Parity |
| CRE tile handling | Parity |
| Room state parsing (all 9 condition types) | Parity |
| LoROM address conversion | Parity |
| Undo/redo | We exceed (broader scope) |
| Pattern copy/paste | Parity (we have more built-ins) |

---

## 4. FEATURE PRIORITY MATRIX

Sorted by: (Community Impact x Implementation Feasibility)

### Tier 1 — HIGH IMPACT, ACHIEVABLE (Next Sprint)

| # | Feature | Impact | Effort | Why |
|---|---------|--------|--------|-----|
| 1 | **Samus Physics Editor** | Very High | Medium | Every Kaizo hack needs physics tuning. ~40 values in bank $91. GUI with sliders. |
| 2 | **Palette Editor** | Very High | Medium | Visual customization is #1 requested feature. RGB sliders for 16-color rows. Import/export .pal. |
| 3 | **Enemy Vulnerability Editor** | High | Low | 22-byte table per species. Extend existing enemy stats UI with weapon multiplier grid. |
| 4 | **Enemy Drop Rate Editor** | High | Low | 6 values per species. Add to enemy stats panel. |
| 5 | **BTS Sub-Types** (plan.md Phase 1) | High | Low | Already designed — just expand btsOptionsForBlockType(). |

### Tier 2 — HIGH IMPACT, MORE EFFORT (Near-term)

| # | Feature | Impact | Effort | Why |
|---|---------|--------|--------|-----|
| 6 | **Room Header Editor** | High | Medium | Make header fields editable. Area reassignment, minimap position, dimensions. |
| 7 | **Multi-State Room Editing** | High | High | Full per-state editing. Major architectural work but essential for boss rooms. |
| 8 | **Music/Song Selector** | Medium-High | Low | Dropdown of song sets/tracks. Write to state data offsets +4/+5. |
| 9 | **Tileset/Metatile Composer** | Medium-High | High | Define 16x16 from 4 8x8. Palette/flip per sub-tile. Complex but powerful. |
| 10 | **Save Station Spawn Editor** | Medium | Low | Expose spawn X/Y/screen in PLM param editor. |

### Tier 3 — MEDIUM IMPACT (Mid-term)

| # | Feature | Impact | Effort | Why |
|---|---------|--------|--------|-----|
| 11 | **Door Cloning Tool** | Medium | Medium | Auto-calculate door properties from screen edge click. |
| 12 | **Layer 2/BG Scrolling Editor** | Medium | Medium | Parallax mode selector + BG pointer editing. |
| 13 | **Validation Suite** | Medium | Medium | PLM index scanner, door validator, item bitflag checker, GFX limit warnings. |
| 14 | **Enemy AI Pointer Editor** | Low-Medium | Low | Expose AI routine pointers in species editor. |
| 15 | **Samus Pose Editor** | Low-Medium | Medium | Animation pose configuration. |

### Tier 4 — LOW PRIORITY (Backlog)

| # | Feature | Impact | Effort | Why |
|---|---------|--------|--------|-----|
| 16 | ROM data export/import (arbitrary) | Low | Low | Hex address + size BIN I/O |
| 17 | Mapshot/room render to file | Low | Low | Already render rooms — just add "Save as PNG" |
| 18 | Hotkey configuration | Low | Medium | Custom keyboard shortcut mapping |
| 19 | Free space tracker | Low | Medium | Monitor bank usage |
| 20 | Plugin system | Low | High | Extensibility framework |

---

## 5. DETAILED IMPLEMENTATION NOTES

### 5.1 Samus Physics Editor

**ROM addresses (bank $91):**
- Jump height: $91:B3B0 (normal), $91:B3B2 (hi-jump), $91:B3B4 (underwater), etc.
- Run speed: $91:B3C0 (max speed), $91:B3C2 (acceleration)
- Gravity: $91:B3D0 (normal), $91:B3D2 (underwater)
- Air control: $91:B3E0+ (various)
- Walljump: $91:B3F0+
- Knockback: $91:B400+

**Implementation:** Create a config patch (like boss stats) with ~40 named fields. GUI with labeled sliders + reset-to-default. Group by category (Jump, Run, Air, Gravity, Special).

**Reference:** SMILE's `SamusForm.frm` and `SamusForm2.frm`.

### 5.2 Palette Editor

**Data structure:** Each tileset has 8 sub-palettes of 16 colors (128 colors total). Colors are 15-bit BGR555 (2 bytes each). Total per tileset: 256 bytes.

**Implementation:**
1. Add "Palette" tab to tileset editor
2. Display 8 rows x 16 color swatches
3. Click swatch → RGB slider popup (convert BGR555 ↔ RGB)
4. Store overrides in project customGfx as base64 palette data
5. Apply on export (write palette bytes to tileset table entry)
6. Import/export .pal files (raw 256-byte palette dumps)

**Enemy palettes:** Similar but 32 bytes per species (16 colors). Edit via enemy stats panel.

### 5.3 Enemy Vulnerability Editor

**Data structure:** 22-byte table per species in bank $B4. Layout:
```
Bytes 0-1:  Normal beam damage multiplier
Bytes 2-3:  Wave beam
Bytes 4-5:  Ice beam
Bytes 6-7:  Spazer
Bytes 8-9:  Plasma
Bytes 10-11: Charged beam
Bytes 12-13: Missile
Bytes 14-15: Super Missile
Bytes 16-17: Power Bomb
Bytes 18-19: Speed Booster / Screw Attack
Bytes 20-21: Freeze duration / special
```
Scale: 0x0000 = immune, 0x0002 = 1x damage, 0x0004 = 2x, etc.

**Implementation:** Grid in enemy stats panel. Column per weapon, row shows multiplier (0-F dropdown or spinner). "Immune" / "Weak" / "Normal" quick presets.

### 5.4 Music/Song Selector

**Implementation:** Dropdown in room properties panel with known song sets:
```
03:05 Title Screen
06:05 Empty Crateria
0C:05 Crateria
0F:05 Green Brinstar
12:05 Red Brinstar
15:05 Upper Norfair
18:05 Lower Norfair
1B:05 Maridia (East)
1B:06 Maridia (West)
1E:05 Tourian
2A:05 Mini-boss
24:05 Boss fight
24:07 Escape
27:05 Pre-boss tense
21:05 Mother Brain
3C:05 Credits
```
Write musicData/musicTrack to state data change. Already have setStateDataChange() — just need UI.

---

## 6. WHAT SMILE WILL NEVER HAVE (Our Permanent Advantages)

1. **Cross-platform** — SMILE is VB6 Windows-only, forever
2. **Embedded emulator** — Live testing without leaving the editor
3. **Boss AI tuning** — Phantoon behavior config with 30+ parameters
4. **78 ROM patches** — One-click gameplay modifications
5. **Sprite pixel editing** — Direct ARGB editing with PNG I/O
6. **Modern project system** — JSON-based, versioned, multi-file
7. **SPC audio** — Native music playback
8. **Active development** — SMILE hasn't been updated in 15+ years
9. **IPS export** — Universal patch distribution
10. **Emulator sync** — Follow player room in real-time

---

## 7. SMILE REFERENCE DATA

### Enemy Species with GIF Sprites (146 total)
Located at `~/code/super_metroid/smile/files/Enemies/`
Format: `{hex_id}.GIF` + `{hex_id}.txt`

### PLM Reference Images (200+)
Located at `~/code/super_metroid/smile/files/PLM/`
Format: `{hex_id}.gif` + `{hex_id}.txt`

### SMILE Source Forms (42 total)
Key forms for reference implementation:
- `Smile.frm` (5067 lines) — Main editor, BTS menus, tile editing
- `DoorForm1.frm` — Door editor (9 properties + clone)
- `PLMForm.frm` — PLM editor (all types)
- `EnemyMiscellaneousEdit1.frm` — Enemy population editor
- `SpeciesForm.frm` — Enemy species global editor
- `FX1_1.frm` — FX editor (16 types)
- `SamusForm.frm` / `SamusForm2.frm` — Physics editor
- `Palette1.frm` — Palette editor
- `GraphicForm.frm` — Tileset/metatile editor
- `States1.frm` — Room state selector
- `RoomHeader1.frm` — Room header editor
- `VulnerabilitiesForm1.frm` — Enemy resistances
- `Layer3Editor.frm` — BG scrolling
- `Poses1.frm` — Samus animations
- `Mapper1.frm` — Visual room map
- `HotKeys1.frm` — Hotkey config
