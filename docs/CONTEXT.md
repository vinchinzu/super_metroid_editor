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
- **Enemy graphics sets**: Pointed to by `enemyGfxPtr` in room state data (offset +10)
- **Pre-rendered enemy sprite PNGs**: `desktopApp/src/jvmMain/resources/enemies/<speciesId>.png`
