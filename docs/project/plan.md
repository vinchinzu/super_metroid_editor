# SM Editor — SMILE Feature Parity Plan

Gap analysis and implementation plan derived from studying the SMILE (Super Metroid Integrated Level Editor) source code at `~/code/smile/source/` and `~/code/smile/files/`.

---

## Gap Analysis: Our Editor vs SMILE

### 1. BTS Sub-Types Missing (Crumble, Speed, Grapple, Bomb, Spike)

Our `btsOptionsForBlockType()` in `MapCanvas.kt` only has detailed BTS options for Shot Blocks (0xC). SMILE defines BTS sub-types for **many** block types that we're completely ignoring:

| Block Type | Our BTS Options | SMILE's BTS Options |
|---|---|---|
| **0xB Crumble** | Just "Normal" (0x00) | 0x00=Crumble (reform), 0x04=Crumble (permanent), 0x0E=Speed Booster crumble (reform), 0x0F=Speed Booster crumble (permanent), 0x0B=Barrier |
| **0x3 Speed** | None | 0x08=Left, 0x09=Right, 0x81/82/83/85=Down variants |
| **0xE Grapple** | None | 0x00=Normal, 0x01=Crumble grapple (reform), 0x02=Crumble grapple (permanent) |
| **0xF Bomb** | Just "Normal" (0x00) | 0x00=Bomb (reform), 0x04=Bomb (permanent) |
| **0xA Spike** | None | 0x00=Normal spike, 0x0F=Grinder |

**Root cause of "speed booster tiles crumble on contact":** If tiles are type 0xB with BTS 0x0E/0x0F (speed booster crumble blocks), they behave like crumble blocks triggered by speed boosting. True speed booster blocks (type 0x3) need directional BTS values (0x08=left, 0x09=right). The editor was showing the "speed" overlay for type 0x3 tiles, but the actual ROM data may have been using type 0xB + BTS 0x0E instead.

### 2. Door Configuration (Currently Read-Only)

Our door display has a dropdown to pick which door index (BTS), shows destination room name, direction, screen coords, and elevator flag. But it's **read-only**. SMILE's DoorForm1 lets you **edit** all door properties:

- **RoomID** — destination room pointer (dropdown of all rooms)
- **BitFlag** — elevator bit, region switch bit
- **Direction** — 0=Right, 1=Left, 2=Down, 3=Up, +4 for bubble/closing door
- **Xi/Yi** — door illusion coordinates on exit
- **X/Y** — spawn screen coordinates
- **Distance** — pixels from door edge (default 0x8000 horizontal, 0x01C0 vertical)
- **ScrollData** — pointer to scroll update ASM
- **DoorASM/EntryCode** — ASM to run on entry
- **Door cloning** — click on a screen edge to auto-calculate door properties

### 3. PLM Stations & Gates (Parsed but Not Editable)

We parse and display "other" PLMs but can't add/edit them. Critical placeable PLMs:

| PLM ID | Type | Status |
|---|---|---|
| **B76F** | Save Point | Displayed, not editable |
| **B6DF** | Energy Refill Station | Displayed, not editable |
| **B6EB** | Missile Refill Station | Displayed, not editable |
| **B6D3** | Mapping Station | Displayed, not editable |
| **B70B** | Elevator Base | Displayed, not editable |
| **C836** | Gate (8 color/direction variants) | Displayed, not editable |
| **C82A** | Gate connector | Displayed, not editable |
| **C842-C854** | Grey Doors (boss/event) | Displayed, not editable |
| **C85A-C89C** | Yellow/Green/Pink Door Shells | Displayed, not editable |
| **DB48-DB60** | Eye Door Pieces | Displayed, not editable |

### 4. Enemy Names (40 vs 123+)

We have ~40 named enemies. SMILE has 123 .txt files with names. Our English names are preferred over SMILE's Japanese-origin names; we merge to get 120+ coverage.

### 5. Enemy Sprites (146 GIF Images Available)

SMILE has 146 enemy GIF files — all static, single-frame, small sprites. Currently we show diamond markers with text labels. Drawing actual sprites would be far more useful.

### 6. Tileset Defaults Are Incomplete

Our `TilesetDefaults` maps only ~20 metatile indices. Should be expanded to cover all common CRE tiles.

---

## Implementation Phases

### Phase 1: BTS Sub-Types (High Impact, Quick Win)

**Files:** `MapCanvas.kt`

Expand `btsOptionsForBlockType()` with all SMILE-documented BTS values:

- Crumble (0xB): reform, permanent, speed booster crumble reform/permanent, barrier
- Speed Booster (0x3): left, right, down variants
- Grapple (0xE): normal, crumble reform, crumble permanent
- Bomb (0xF): reform, permanent
- Spike (0xA): normal, grinder

### Phase 2: Expanded Enemy Names

**Files:** `RomParser.kt`

Merge SMILE's 123 enemy names into `ENEMY_NAMES` map. Use English names where we have them, SMILE names as fallback. Target: 40 → 120+ named enemies.

### Phase 3: Enemy Sprite Images

**Files:** `MapCanvas.kt`, resources

1. Convert 146 GIFs → PNGs (skip animated/unknown, use `unknown.gif` as fallback)
2. Store in `desktopApp/src/jvmMain/resources/enemies/`
3. Load sprites in `MapCanvas.kt`, draw at enemy pixel positions instead of diamonds
4. Fall back to hex ID text for enemies without sprites

### Phase 4: PLM Station/Gate Editing

**Files:** `MapCanvas.kt`, `EditorState.kt`, `RomParser.kt`

Add ability to place/edit non-item PLMs:
- Save Points (B76F), Energy Refill (B6DF), Missile Refill (B6EB)
- Mapping Station (B6D3), Elevator Base (B70B)
- Gates (C836 with 8 variants + C82A connector)
- Grey/colored door shells

### Phase 5: Door Property Editing

**Files:** `MapCanvas.kt`, `EditorState.kt`, `RomParser.kt`, `EditState.kt`

Make door properties editable:
- Destination room dropdown
- Direction selector (Right/Left/Down/Up + bubble)
- Screen X/Y coordinate inputs
- Distance from door
- ScrollData and EntryCode pointers
- Elevator flag toggle
- Auto-calculate Xi/Yi from direction + screen position

### Phase 6: Tileset Default Expansion

**Files:** `EditorState.kt`

Cross-reference SMILE's CRE tile handling. Expand metatile defaults beyond current 20 entries.

---

## Reference Data

### SMILE File Locations
- Enemy definitions: `~/code/smile/files/Enemies/*.txt`
- Enemy sprites: `~/code/smile/files/Enemies/*.GIF`
- PLM definitions: `~/code/smile/files/PLM/*.txt`
- PLM images: `~/code/smile/files/PLM/*.gif`
- Source code: `~/code/smile/source/*.frm`, `*.bas`

### Key SMILE Source Files
- `Smile.frm` — Main editor form, BTS menu, tile editing
- `SmileMod1.bas` — Tile data structures, PropertyPart/PatternByte handling
- `DoorForm1.frm` — Door editor with all 9 properties
- `PLMForm.frm` — PLM placement and editing
