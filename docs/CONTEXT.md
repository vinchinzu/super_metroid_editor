SM Source / Binary Documentation:
- Review .md files in this folder + .txt files for more context
- Review "Super Metroid Mod 3.0.80/SMMM_black.html"
- ~/code/super_metroid/smile/
- ~/code/sm/ (documented C code/structs)

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
| +$0C | 1 | aiBank | Bank for AI routines AND palette data |
| +$14 | 2 | parts | Number of sub-pieces (0 = 1 part) |
| +$36 | 2 | GRAPHADR offset | 16-bit LE offset of compressed tile data |
| +$38 | 1 | GRAPHADR bank | Bank byte for compressed tile data |
| +$39 | 1 | Layer control | 02=front, 05=behind Samus, 0B=behind BG |
| +$3A | 2 | Drop chances ptr | Bank $B4 |
| +$3C | 2 | Resistances ptr | Bank $B4 |
| +$3E | 2 | Name ptr | Bank $B4 |

### Palette formula
`palette_data = $(aiBank):(palPtr + 0x20)` — 32 bytes of BGR555 colors.

### Tile data loading
`GRAPHADR = $(bank at +$38):(offset at +$36-37)` points to an LZ5-compressed block.
The game decompresses the full block but only loads the first `tileDataSize` bytes for this species.
Multiple enemies can share the same GRAPHADR block with different tileDataSizes.
Bosses (Phantoon, etc.) may use separate DMA-based tile loading instead of GRAPHADR.

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

Related PLMs:
- `$B63B` — rightwards extension (copies treadmill block rightward from the B703 PLM)
- `$B647` — upwards extension (copies treadmill block upward)

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
scroll PLMs in the PLM list (IDs B703, B63B, B647) but does not yet decode their commands inline.

### Scroll diagnostic test

`ScrollSystemTest.kt` can dump all scroll data, scroll PLMs, their decoded commands, and door ASM
for any room in any ROM. Use it to debug scroll issues.

## External References

- **Kejardon's docs**: https://patrickjohnston.org/ASM/ROM%20data/Super%20Metroid/Kejardon's%20docs/
- **Patrick Johnston bank logs**: https://patrickjohnston.org/bank/8F (also /B4, /A0, etc.)
- **Metroid Construction wiki**: https://wiki.metroidconstruction.com/
- **EnemyData.txt** (Kejardon): Full 64-byte species header format
- **VG Resource SM ripping project**: https://archive.vg-resource.com/thread-23505.html — GRAPHADR discovery, tilemap format, frame pointer arrays
- **SM decompilation (snesrev/sm)**: ~/code/sm/ — C structs for DoorDef, PlmSetup, etc.
- **SPC sound data**: `SpcData.KNOWN_TRACKS` maps songSet+playIndex to track names
