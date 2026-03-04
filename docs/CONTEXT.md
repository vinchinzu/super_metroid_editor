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

## External References

- **Kejardon's docs**: https://patrickjohnston.org/ASM/ROM%20data/Super%20Metroid/Kejardon's%20docs/
- **Patrick Johnston bank logs**: https://patrickjohnston.org/bank/8F (also /B4, /A0, etc.)
- **Metroid Construction wiki**: https://wiki.metroidconstruction.com/
- **EnemyData.txt** (Kejardon): Full 64-byte species header format
- **VG Resource SM ripping project**: https://archive.vg-resource.com/thread-23505.html — GRAPHADR discovery, tilemap format, frame pointer arrays
- **SM decompilation (snesrev/sm)**: ~/code/sm/ — C structs for DoorDef, PlmSetup, etc.
- **SPC sound data**: `SpcData.KNOWN_TRACKS` maps songSet+playIndex to track names
