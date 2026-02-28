# Super Metroid ROM Limits & Constraints

Quick reference for practical editing limits, verified against the ROM format
documentation (`rom_data_format.md`), parser code (`RomParser.kt`), and export
logic (`EditorState.exportToRom()`).

---

## Per-Room Limits

| Resource | Hard limit | Practical limit | Entry size | Notes |
|----------|-----------|-----------------|------------|-------|
| **PLMs** (items, doors, save stations, gates…) | Terminated by `0x0000` | **256** (parser safety cap) | 6 bytes | Total set = `(N × 6) + 2` bytes |
| **Enemies** | Terminated by `0xFFFF` | ~60 per set | 16 bytes | Bank `$A1` free space is the real cap |
| **FX entries** | Terminated by `doorSelect == 0` | **16** (parser safety cap) | 16 bytes | Bank `$83` |
| **Room scrolls** | 1 byte per screen | **50 bytes** (max 50 screens) | 1 byte | Bank `$8F` |
| **Room dimensions** | 0x0F × 0x0F (16 × 16 screens) | **50 screens** max area | — | `width × height ≤ 50` |
| **Door-out entries** | No terminator; count derived from next pointer | ~20 | 2 bytes (ptr) | DDB is 12 bytes in bank `$83` |

## Scroll Values

| Value | Color | Meaning |
|-------|-------|---------|
| `0x00` | Red | Hidden — screen does not scroll into view |
| `0x01` | Blue | Explorable — normal scrolling, revealed on map |
| `0x02` | Green | Show floor — shows the bottom of the screen when entering from above |

Special scroll pointers: `0x0000` = all Blue, `0x0001` = all Green.

## Bank Free Space

These banks are shared across all rooms. Expanding data past its original
footprint requires relocating to free space within the same bank.

| Bank | Range | Size | Contents |
|------|-------|------|----------|
| `$8F` | `$8F8000`–`$8FFFFF` | 32 KB | PLM sets, scroll data, BG data, Main/Setup ASM, door-out tables |
| `$83` | `$838000`–`$83FFFF` | 32 KB | FX entries, door data blocks |
| `$A1` | `$A18000`–`$A1FFFF` | 32 KB | Enemy population sets |
| `$B4` | `$B48000`–`$B4FFFF` | 32 KB | Enemy graphics sets |
| `$C0`–`$CE` | `$xx8000`–`$xxFFFF` each | 32 KB × 15 | Compressed level data (tiles) |

## Export Behavior

When our editor writes data back to ROM:

1. **Fits in place** → overwritten at original location, remainder zeroed.
2. **Grew larger** → relocated to trailing `0xFF` free space in the appropriate bank.
   All room state pointers that referenced the old location are updated.
3. **No free space** → a warning is printed and that room's data is **skipped**.

This applies to: level data (tiles), PLM sets, enemy sets, and (once implemented)
scroll data and FX data.

## Layer 2 / BG Scrolling

The 2-byte value at state data offset +12 controls layer 2 behavior:

| Value | Meaning |
|-------|---------|
| `0x0000` | Layer 2 fixed (no scroll, same as layer 1) |
| `0x0001` | Layer 2 follows layer 1 |
| `0x00xx` / `0xYYxx` | Y/X scroll rates — varies per room |

## FX Types

| Byte | Type |
|------|------|
| `0x00` | None |
| `0x02` | Fog |
| `0x04` | Water |
| `0x06` | Lava |
| `0x08` | Acid |
| `0x0A` | Rain |
| `0x0C` | Spores |
| `0x0E` | Haze |
| `0x10` | Dense fog |
| `0x12` | Ceres water |
| `0x16` | Firefleas |
| `0x18` | Lightning |
| `0x1A` | Smoke |
| `0x1C` | Heat shimmer |
| `0x24` | BG3 transparent |
| `0x26` | Sandstorm |
| `0x28` | Dark visor |
| `0x2A` | Darker visor |
| `0x2C` | Black |

## Sources

- `shared/src/commonMain/kotlin/com/supermetroid/editor/rom/RomParser.kt` — parser safety caps
- `desktopApp/src/jvmMain/kotlin/com/supermetroid/editor/ui/EditorState.kt` — free space scanning + export
- `docs/rom_data_format.md` — byte-level format reference
