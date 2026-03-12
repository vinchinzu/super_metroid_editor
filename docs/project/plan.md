# SM Editor — SMILE Feature Parity Plan

Gap analysis and implementation plan derived from studying the SMILE (Super Metroid Integrated Level Editor) source code at `~/code/super_metroid/smile/` and comprehensive cross-reference of all features.

**See also:** `smile_parity.md` for the complete feature-by-feature comparison matrix.

---

## Gap Analysis: Our Editor vs SMILE

### CRITICAL GAPS (Features SMILE has that we completely lack)

#### 1. Samus Physics Editor
SMILE edits ~40 physics constants: jump heights (normal, hi-jump, walljump, underwater, lava), running speed/acceleration, air control, gravity, fall speed, knockback, momentum. All per-movement-state.
**Data:** Bank $91 scattered addresses.
**Plan:** Config patch with ~40 named fields. GUI with labeled sliders + reset-to-default. Group by category.

#### 2. Palette Editor
SMILE edits 256-color tileset palettes (16-color rows), enemy palettes (RGB sliders), import/export .pal files, palette blending modes.
**Data:** Tileset palettes in bank $8F (tileset table entries), enemy palettes in bank $A0/B4.
**Plan:** Add "Palette" tab to tileset editor. 8 rows x 16 swatches, click for RGB slider. Store as base64 in customGfx. Import/export .pal.

#### 3. Enemy Vulnerability/Resistance Editor
SMILE edits a 22-byte damage multiplier table per weapon per species (Wave, Ice, Spazer, Plasma, Charge, Missile, Super, PB — 0-F scale). Freeze/kill modes.
**Data:** Bank $B4, 22-byte block per species.
**Plan:** Grid in enemy stats panel. Column per weapon, multiplier dropdown. "Immune/Weak/Normal" presets.

#### 4. Enemy Drop Rate Editor
SMILE edits 6 drop entries per species: small energy, large energy, missile, gap, super missile, power bomb.
**Data:** Bank $B4, per species header.
**Plan:** Add to enemy stats panel as 6-field editor.

#### 5. Room Header Editor (Currently Read-Only)
SMILE edits: room index, area assignment, minimap X/Y, dimensions, up/down scrollers, CRE flag, door-out pointer.
**Data:** 11-byte room header in bank $8F.
**Plan:** Make all header fields editable in room properties panel.

#### 6. Multi-State Room Editing (Full)
SMILE fully edits all data per room state: tiles, enemies, PLMs, scrolls, music, FX, BG. All 9 condition types.
**Current:** We parse all states and can select them, but only default state tiles/enemies are editable.
**Plan:** Per-state enemy/PLM/scroll/FX editing. Major architectural work.

#### 7. Tileset/Metatile Composer
SMILE defines 16x16 metatiles from 4 8x8 tiles with palette assignment, flip controls, BTS assignment per sub-tile.
**Current:** We can view and import/export tile sheets but can't compose metatiles.
**Plan:** Dedicated metatile editor with 4-quadrant sub-tile picker, palette row selector, flip toggles.

---

### MODERATE GAPS

#### 8. BTS Sub-Types Missing (Crumble, Speed, Grapple, Bomb, Spike)

| Block Type | Our BTS Options | SMILE's BTS Options |
|---|---|---|
| **0xB Crumble** | Just "Normal" (0x00) | 0x00=reform, 0x04=permanent, 0x0E=Speed Booster reform, 0x0F=Speed Booster permanent, 0x0B=Barrier |
| **0x3 Speed** | None | 0x08=Left, 0x09=Right, 0x81/82/83/85=Down variants |
| **0xE Grapple** | None | 0x00=Normal, 0x01=Crumble reform, 0x02=Crumble permanent |
| **0xF Bomb** | Just "Normal" (0x00) | 0x00=reform, 0x04=permanent |
| **0xA Spike** | None | 0x00=Normal spike, 0x0F=Grinder |

**Plan:** Expand `btsOptionsForBlockType()` in MapCanvas.kt. Quick win.

#### 9. Door Configuration Editing
We can edit door properties but lack SMILE's auto-clone tool (click screen edge → auto-calculate all properties).
**Plan:** Add "Clone Door" button that derives cap/spawn/direction from visual placement.

#### 10. Music/Song Selector
SMILE has room music dropdown (36 sets x 2-3 tracks), per-door music triggers.
**Current:** We have stateDataChange for music but no friendly dropdown UI.
**Plan:** Song set/track dropdown in room properties with known track names.

#### 11. Layer 2/BG Scrolling Editor
SMILE edits parallax modes (fixed, follow, custom rates), BG data pointers, BG tileset selection.
**Current:** Can override bgScrolling value but no visual editor.
**Plan:** Parallax mode selector + BG pointer in room properties.

#### 12. Save Station Spawn Point Editor
SMILE has dedicated spawn X/Y/screen configuration per save station.
**Plan:** Expose in PLM param editor when save station PLM is selected.

#### 13. Enemy Names (40 vs 123+)
SMILE has 123 .txt files with enemy names. We have ~40.
**Plan:** Merge SMILE names into ENEMY_NAMES map. Use English where we have them.

#### 14. Enemy Sprites on Map (146 GIF Images)
SMILE shows static sprite images on map. We show diamond markers.
**Plan:** Convert GIFs → PNGs, store in resources, draw at enemy positions.

---

### VALIDATION GAPS (SMILE safety checks we lack)

| Validation | Description | Priority |
|---|---|---|
| PLM Index Scanner | Find duplicate Main PLM Variables across rooms | Medium |
| Door Consistency Validator | Verify destinations exist, coordinates valid | Medium |
| Item Bitflag Uniqueness | Ensure no two items share collection bit | Medium |
| Enemy GFX Limit Warning | Warn when room exceeds 4-entry hardware limit | Medium |
| Free Space Tracker | Monitor available bytes in shared banks | Low |
| Room State Validator | Check state conditions are sensible | Low |
| Music Validator | Verify song set/track pairs exist | Low |

---

## Implementation Phases (Updated)

### Phase 1: Quick Wins (Low Effort, High Value)
1. **BTS Sub-Types** — Expand btsOptionsForBlockType() with all SMILE values
2. **Enemy Drop Rates** — 6-field editor in enemy stats panel
3. **Music Selector** — Dropdown in room properties with named tracks
4. **Enemy Names** — Merge 123 SMILE names into ENEMY_NAMES map
5. **Enemy Sprites on Map** — Convert 146 GIFs to PNGs, render on canvas

### Phase 2: Major New Editors
6. **Samus Physics Editor** — Config patch with ~40 sliders
7. **Palette Editor** — RGB color editing for tilesets and enemies
8. **Enemy Vulnerability Editor** — Per-weapon damage multiplier grid
9. **Room Header Editor** — Make all 11 bytes editable

### Phase 3: Infrastructure
10. **Multi-State Room Editing** — Per-state editing of all data
11. **Tileset/Metatile Composer** — 16x16 from 4 8x8 with full controls
12. **Validation Suite** — PLM scanner, door validator, GFX limit warnings

### Phase 4: Polish
13. **Door Cloning Tool** — Auto-calculate from screen edge click
14. **Layer 2/BG Editor** — Parallax modes + BG pointer
15. **Save Station Spawn Editor** — Fine-tune spawn coordinates
16. **Samus Pose Editor** — Animation configuration
17. **Mapshot Tool** — Render room/area to PNG

---

## Reference Data

### SMILE File Locations
- Enemy definitions: `~/code/super_metroid/smile/files/Enemies/*.txt`
- Enemy sprites: `~/code/super_metroid/smile/files/Enemies/*.GIF`
- PLM definitions: `~/code/super_metroid/smile/files/PLM/*.txt`
- PLM images: `~/code/super_metroid/smile/files/PLM/*.gif`
- Source code: `~/code/super_metroid/smile/source/*.frm`, `*.bas`

### Key SMILE Source Files
- `Smile.frm` (5067 lines) — Main editor, BTS menu, tile editing
- `DoorForm1.frm` — Door editor (9 properties + clone)
- `PLMForm.frm` — PLM placement and editing
- `SamusForm.frm` / `SamusForm2.frm` — Physics editor
- `Palette1.frm` — Palette editor
- `VulnerabilitiesForm1.frm` — Enemy resistances
- `SpeciesForm.frm` — Enemy species global editor
