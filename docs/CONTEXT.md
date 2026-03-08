SM Source / Binary Documentation:
- Review .md files in this folder + .txt files for more context
- Review "Super Metroid Mod 3.0.80/SMMM_black.html"
- ~/code/super_metroid/smile/
- ~/code/super_metroid/sm/ (documented C code/structs)

## Available Docs in docs/

| File | Contents |
|------|----------|
| `rom_data_format.md` | Authoritative reference: LoROM address mapping, room header layout, state data format (26 bytes), PLM set terminator, state condition codes (E5E6 default, E629 boss dead). Use before modifying any ROM parsing. |
| `phantoon.md` | Phantoon species IDs ($E4BF body, $E4FF/$E53F/$E57F flames), room $CD13, AI bank $A7, all behavior data table addresses (eye open/closed timers, flame rain positions, figure-8 speeds), AI routine addresses, what is safe to patch vs. requires ASM. |
| `kraid.md` | Kraid species IDs ($E2BF main body, $E2FF upper body, $E33F/$E37F/$E3BF belly spikes, $E3FF/$E43F/$E47F claws), room $A59F, AI bank $A7, HP=1000, contact damage=20, sub-enemy damage. Verified from room enemy set. Note: $D2BF is Squeept, NOT Kraid. |
| `hex_edits.txt` | Extensive list of raw hex edit recipes for physics, beams, missiles, morph ball, suits, doors, HUD, FX, sounds, and more. |
| `Super Metroid Mod 3.0.80/SMMM_black.html` | Authoritative community reference for all ROM data (enemy species IDs, room layouts, ASM, etc.). Ground truth for species IDs when code and SMMM conflict. |

## Key ROM Conventions (quick ref)

- **LoROM formula**: `PC = ((bank & 0x7F) * 0x8000) + (snesOffset - 0x8000)`
- **Enemy species headers**: 64 bytes at `$A0:xxxxxx`; HP at +4 (u16 LE), damage at +6, width at +8, height at +10
- **Boss AI**: Kraid + Phantoon in bank `$A7`; Ridley + Draygon in other banks
- **Enemy graphics sets**: Pointed to by `enemyGfxPtr` in room state data (offset +10); species list in bank `$B4`
- **Pre-rendered enemy sprite PNGs**: `desktopApp/src/jvmMain/resources/enemies/<speciesId>.png`

## Enemy Species Header (64-byte DNA at bank $A0)

Key fields for the sprite editor:

| Offset | Size | Field | Notes |
|--------|------|-------|-------|
| +$00 | 2 | tileDataSize | Decompressed tile bytes needed for this species |
| +$02 | 2 | palPtr | Palette pointer (16-bit, used with aiBank) |
| +$04 | 2 | HP | |
| +$06 | 2 | Damage | |
| +$08 | 2 | Width | Hitbox half-width in pixels |
| +$0A | 2 | Height | Hitbox half-height in pixels |
| +$0C | 1 | aiBank | Bank for AI routines, palette, AND spritemap data |
| +$12 | 2 | initAI | Init function pointer (in aiBank) — sets up instruction list |
| +$14 | 2 | parts | Number of sub-pieces (0 = 1 part) |
| +$36 | 2 | GRAPHADR offset | 16-bit LE offset of compressed tile data |
| +$38 | 1 | GRAPHADR bank | Bank byte for compressed tile data |
| +$39 | 1 | Layer control | 02=front, 05=behind Samus, 0B=behind BG |
| +$3A | 2 | Drop chances ptr | Bank $B4 |
| +$3C | 2 | Resistances ptr | Bank $B4 |
| +$3E | 2 | Name ptr | Bank $B4 |

### Palette loading
The game's `ProcessEnemyTilesets` ($A0:8D64) loads exactly 32 bytes (one 16-color palette row)
from `$(bank):$(palPtr)` — always row 0, directly at the pointer address:
```c
memcpy(&target_palettes[(LOBYTE(ET->vram_dst) + 8) * 16],
       RomPtrWithBank(ED->bank, ED->palette_ptr), 32);
```
**Do NOT try to auto-detect row 0 vs row 1** — the game always uses row 0.
The `vram_dst` low byte + 8 selects the CGRAM destination row (8-15 = OBJ palettes).

See `EnemySpriteGraphics.readEnemyPalette()` for implementation.

### Tile data loading
**Bit 15 of `tileDataSize` is a flag**, not part of the size. The game masks it off:
`actual_size = tile_data_size & 0x7FFF`. When bit 15 is set, the VRAM offset calculation
changes (`(vram_dst & 0x3000) >> 3` instead of sequential), but the tile count is the same.

`GRAPHADR = $(bank at +$38):(offset at +$36-37)` points to an LZ5-compressed block.
The game decompresses the full block but only loads the first `tileDataSize & 0x7FFF` bytes.
Multiple enemies can share the same GRAPHADR block with different tileDataSizes.
Bosses (Phantoon, etc.) use separate DMA-based tile loading instead of GRAPHADR.

## Room Scroll System

Super Metroid room scrolling has **three layers** that interact at runtime:

### 1. Static Scroll Data (what our editor edits)

Each room state has a `roomScrollsPtr` (offset +14 in 26-byte state data) pointing to data in bank $8F.
One byte per screen (width * height), left-to-right, top-to-bottom order.

| Value | Name | Behavior |
|-------|------|----------|
| $00 | Red | Camera CANNOT scroll to this screen (hard boundary) |
| $01 | Blue | Camera CAN scroll to this screen normally |
| $02 | Green | Camera CAN scroll, but conventionally used as "PLM-gated" — scroll PLMs toggle these |

Special pointers: `$0000` = all Blue, `$0001` = all Green.

**When loaded:** Static data is copied to RAM `$7E:CD20..CD51` at room load, BEFORE door ASM runs.

### 2. Door ASM (runtime override on entry)

Each door has a 2-byte `entryCode` (bytes 10-11 of the 12-byte door data block in bank $83).
This is an ASM routine pointer in bank $8F. If non-zero, it runs AFTER static scrolls load.

Door ASM typically writes directly to `$7E:CD20+X` to override specific screen scroll values,
allowing different initial scroll states depending on which door Samus enters from.

### 3. Scroll PLMs (runtime triggers during gameplay)

PLM ID `$B703` (normal scroll trigger). Each places a "treadmill" block (BTS $46) at its position.
When Samus walks over the treadmill block, the scroll PLM's command list fires.

**Scroll command format** (bank $8F, pointed to by PLM param):
```
[screen_index, scroll_value] pairs, terminated by byte >= $80

Example:  03 01 04 01 80
  Screen[3] → Blue, Screen[4] → Blue, done
```

Related PLMs (treadmill extensions — widen a B703 trigger's hitbox):
- `$B63B` — rightward extension
- `$B63F` — leftward extension
- `$B643` — downward extension
- `$B647` — upward extension

### Room Load Order

1. Static scroll data → RAM $7E:CD20
2. PLMs created (treadmill blocks placed, but not triggered yet)
3. **Door ASM executes** (overrides scroll values)
4. Gameplay begins — scroll PLMs trigger as Samus moves

### Common Pitfall: Scroll PLMs fighting static data

**If you change static scroll data (e.g., make screens Blue) but leave vanilla scroll PLMs in
the room, those PLMs will dynamically set screens back to Red/Green at runtime!**

Example (Parlor $92FD in a hack): Static data sets row 0 all Blue. But vanilla PLM at (52,14)
has command `$93A5` which sets Screen 4 to RED. When Samus walks over that block, screen 4
becomes un-scrollable — even though the editor shows it as Blue.

**Fix:** Remove or modify scroll PLMs when changing a room's scroll behavior. Our editor shows
scroll PLMs on a dedicated "Scroll Triggers" overlay (orange badges), lets you remove them via
the tile properties panel, and decodes their scroll commands inline (e.g., "Screen (4,0) → Red (lock)").
The "Add Scroll Trigger" dropdown lets you reuse existing commands or add treadmill extensions.

### Scroll diagnostic test

`ScrollSystemTest.kt` can dump all scroll data, scroll PLMs, their decoded commands, and door ASM
for any room in any ROM. Use it to debug scroll issues.

## Enemy Population Export

### Data format (bank $A1)
16 bytes per entry (8 × 16-bit words), terminated by `FFFF` + 1-byte kill count.
See `rom_data_format.md` §Enemy Population Set for field layout.

### Properties word (offset 8-9 of each entry)
- **Bit 13 (0x2000)**: CRITICAL — assigns initial spritemap pointer during
  `InitializeEnemies`. Without it, `spritemap_pointer` stays 0 → reads garbage
  from WRAM → crash or severe graphical corruption. Our export forces this bit on.
- **Bit 11 (0x0800)**: `kEnemyProps_ProcessedOffscreen` — process instructions off-screen.
- **Bit 10 (0x0400)**: `kEnemyProps_Tangible` — can be hit by Samus.
- **Bit 0 (0x0001)**: Used by some enemies for orientation.
- Default for new enemies: `0x2800` (bits 13 + 11).
- Vanilla entries commonly use `0x2800`, `0x2001`, or `0x2000`.

Engine source (`sm_a0.c:InitializeEnemies`):
```c
E->spritemap_pointer = 0;
if ((E->properties & 0x2000) != 0) {
    E->spritemap_pointer = addr_kSpritemap_Nothing_A4;
}
```

### Kill count byte
One byte after the `FFFF` terminator. The game reads it to determine how many enemies
must be killed for room-clear events. Must be preserved during relocation.

### Enemy GFX set (bank $B4) — HARD LIMIT: 4 entries

The `enemyGfxPtr` (state data offset +10) points directly to entries (past the 7-byte debug name).
Each entry: `species_id(2) + vram_dst(2)`. Terminated by `FFFF`.

**Hardware constraint**: `ProcessEnemyTilesets` in `sm_a0.c` writes to fixed 4-slot arrays
(`enemy_def_ptr[4]`, `enemy_gfxdata_tiles_index[4]`, `enemy_gfxdata_vram_ptr[4]`). A 5th
entry overflows these arrays and corrupts adjacent RAM — guaranteed crash.

The `vram_dst` low byte is the palette index: `LOBYTE(vram_dst) + 8` selects the CGRAM row
for the enemy's palette (rows 8-15 are OBJ palettes on SNES). The high bits of `vram_dst`
can contain VRAM offset flags for enemies with `tile_data_size & 0x8000`.

**Export safeguards**:
1. GFX entries are only added for species in the FINAL population (after all add/remove
   changes are applied), not for species that were added then removed.
2. The 4-entry limit is enforced; species beyond the limit are skipped with a warning.
3. `LoadEnemyGfxIndexes` handles missing GFX entries gracefully (palette defaults to row 5,
   tiles index defaults to 0) — the enemy draws with wrong graphics but doesn't crash.

**Free space allocation**: Bank $B4 free space is scanned ONCE before the room loop and tracked
with a persistent `gfxFreePtr` that increments after each allocation (same pattern as bank $A1
`enemyFreePtr`). The backward scan also absorbs the last vanilla GFX set's `FFFF` terminator
(both bytes are 0xFF), so a +2 guard is applied after the scan to preserve it. Without this,
the engine reads past the terminator into our new data, loading garbage species and VRAM values.

**Vanilla species without GFX entries**: Some species exist in a room's vanilla population but
intentionally have NO GFX entry (e.g. Elevator in room $9938, Phantoon in $CD13). They work via
`LoadEnemyGfxIndexes` defaults (palette row 13, tiles index 0). The export only adds GFX entries
for species the user is adding — `neededSpecies = (finalSpecies - vanillaSpecies) - existingGfx`.
Adding unneeded entries causes `ProcessEnemyTilesets` to decompress + DMA tile data to VRAM $7000+,
corrupting the room's designed VRAM layout.

## OAM Spritemap Assembly (EnemySpritemap.kt)

Regular enemies use OAM spritemaps (unlike Phantoon which uses BG2 tilemaps). The
`EnemySpritemap` class finds and parses these, assembling tiles into the final sprite.

### Spritemap data format
Each spritemap has a 2-byte entry count, then 5 bytes per OAM entry:

| Byte(s) | Format | Contents |
|---------|--------|----------|
| 0-1 | LE word | `s_______ XXXXXXXX` — bit 15 = size (1=16x16, 0=8x8), bits 8-0 = signed 9-bit X offset |
| 2 | signed byte | Y offset from enemy center |
| 3-4 | LE word | `VHooPPPn cccccccc` — V/H flip, priority, palette row, name table select, tile number |

### Finding spritemaps from species headers
The init function at `$(aiBank):$(initAI)` sets up the instruction list pointer (`$0F92,x`).
`EnemySpritemap.findInstructionListPointer()` traces the init code using pattern matching:
- `LDA #imm; STA $0F92,x` — direct pointer
- `LDA abs,y; STA $0F92,x` — direction table lookup (first entry used)
- `TYA; STA $0F92,x` — value from Y register
- JSR following — scans subroutine calls for the patterns above
- **Cross-function trace** — when a JSR target loads from an enemy instance variable
  (`BF addr,x` or `BD addr,x`) then stores to `$0F92,x`, the scanner traces back to
  the caller to find where that variable was set (via `STA long,x` / `STA abs,x`),
  then resolves the source value. This handles enemies like Sidehopper whose init
  stores the instruction list to $7E:7800,x via a table lookup, then calls a helper
  that copies it to $0F92,x.

The instruction list uses 4-byte entries `[word0, word1]`. Two formats exist:
- **Standard**: word0 < 0x8000 → `[timer, spritemap_ptr]`
- **Handler-based**: word0 >= 0x8000 → `[handler_ptr, timer]` (handler parsed as spritemap)

### Tile numbering
Tile numbers in spritemaps include the SNES name table select bit (bit 8). For rendering:
`local_tile_index = tile_number & 0xFF` maps into the decompressed tile data.
16x16 sprites use a 2x2 grid: tiles `[N, N+1, N+16, N+17]` in the 16-tile-wide VRAM layout.

### Kraid "Body (full height)" view
The $A7:A0C8 tilemap references tiles loaded dynamically during the Kraid fight as he rises.
These tiles are not present in the static room tileset or the Kraid tile block at $B9:FA38.
Other body views (initial, rising 1, rising 2) render accurately from static data.

### Current coverage
Working: Zoomer, Zeela, Sidehopper, Skree, Cacatac (auto-detected via init tracing).
Phantoon uses BG2 tilemaps (PhantoonSpritemap.kt), not OAM spritemaps.
Kraid uses BG2 tilemaps + room tileset injection (KraidSpritemap.kt).

## Enemy Sprite Tests

- `EnemySpriteRenderTest.kt` — palette auto-detection, tile data decompression, render validation,
  known stats verification for all editor enemies. Also validates Phantoon palette via `readEnemyPalette`.
- `EnemyExportDiagTest.kt` — enemy population roundtrip, GFX set parsing, properties bit 0x2000
  enforcement, kill count byte verification, GFX set final-population logic (vs. raw change list),
  4-entry GFX hardware limit enforcement, B4 free space terminator guard, vanilla species skip,
  multi-room relocation overlap prevention.
- `EnemySpritemapTest.kt` — OAM spritemap parsing, instruction list tracing, assembled sprite
  rendering (Zoomer, Zeela, Sidehopper, Skree, Cacatac), animation frame extraction, OAM entry validation.
- `EnemyTileScanTest.kt` — GRAPHADR decompression, tileDataSize 0x7FFF mask, palette row 0 verification
  against game's ProcessEnemyTilesets, spritemap assembly for all editor enemies (including Sidehopper
  cross-function trace), fill rate and dimension validation.
- `PhantoonSpritemapRoundtripTest.kt` — Phantoon-specific: body render pixel-perfect match against
  reference PNG, edit roundtrip (paint → applyEdits → re-render), pixel-to-tile mapping.

## External References

- **Kejardon's docs**: https://patrickjohnston.org/ASM/ROM%20data/Super%20Metroid/Kejardon's%20docs/
- **Patrick Johnston bank logs**: https://patrickjohnston.org/bank/8F (also /B4, /A0, etc.)
- **Metroid Construction wiki**: https://wiki.metroidconstruction.com/
- **EnemyData.txt** (Kejardon): Full 64-byte species header format
- **VG Resource SM ripping project**: https://archive.vg-resource.com/thread-23505.html — GRAPHADR discovery, tilemap format, frame pointer arrays
- **SM decompilation (snesrev/sm)**: ~/code/sm/ — C structs for DoorDef, PlmSetup, etc.
- **SPC sound data**: `SpcData.KNOWN_TRACKS` maps songSet+playIndex to track names

## Item Collection Bits (param field)

Each item PLM's `param` field maps to a bit in `item_bit_array` ($7E:D870, 64 bytes = 512 bits). When collected, the bit is set via `PrepareBitAccess` ($80:818E): `param & 7` → bit position, `param >> 3` → byte index. On room load, items whose bit is already set self-delete.

**Critical constraint**: Every item PLM across the entire ROM must have a unique `param`. If two items share a param, collecting one marks both as collected — the other vanishes on room reload.

Vanilla items use params 0x00–0x50. The editor assigns from 0x51–0x1FF (431 slots). The `autoAssignParam` function must compute used params from the NET state (replaying add/remove history), not from all historical "add" entries — ghost entries from removed items would falsely exhaust the pool.

## Controller Configuration

Default button mapping table at SNES $82:F575 (PC 0x017575), 7 slots × 2 bytes:

| Slot | Action | Default | Bitmask |
|------|--------|---------|---------|
| 0 | Shot | X | 0x0040 |
| 1 | Jump | A | 0x0080 |
| 2 | Dash | B | 0x8000 |
| 3 | Item Select | Select | 0x2000 |
| 4 | Item Cancel | Y | 0x4000 |
| 5 | Angle Down | L | 0x0020 |
| 6 | Angle Up | R | 0x0010 |

Assignable SNES buttons: A(0x0080), B(0x8000), X(0x0040), Y(0x4000), L(0x0020), R(0x0010), Select(0x2000). The WRAM address table (`off_82F54A` at $82:F54A) maps these slots to runtime addresses $7E:09B2–09BE. The options menu reads/writes this table via `OptionsMenuFunc8` and `LoadControllerOptionsFromControllerBindings`.

## IPS Patch Export

IPS format: 5-byte header "PATCH", records of [3-byte offset, 2-byte size, data bytes], 3-byte footer "EOF". RLE records use size=0 followed by [2-byte run length, 1-byte fill value]. Export diffs the patched ROM against the original vanilla ROM to produce only the changed byte ranges.
