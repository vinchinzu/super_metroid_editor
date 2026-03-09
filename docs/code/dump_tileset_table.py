#!/usr/bin/env python3
"""Dump the raw tileset table entries to verify pointer reading."""

ROM_PATH = "test-resources/Super Metroid (JU) [!].smc"

def snes_to_pc(snes_addr):
    bank = (snes_addr >> 16) & 0xFF
    addr = snes_addr & 0xFFFF
    return ((bank & 0x7F) * 0x8000) + (addr & 0x7FFF)

def main():
    with open(ROM_PATH, "rb") as f:
        rom = f.read()
    rom_start = 0x200 if len(rom) % 0x8000 == 0x200 else 0

    table_pc = snes_to_pc(0x8FE6A2) + rom_start
    print(f"Tileset table at PC=0x{table_pc:06x}")
    print()

    # Also check the FULL raw data around the table to look for any other table
    print("Raw bytes at tileset table:")
    for ts_id in range(29):
        off = table_pc + ts_id * 9
        raw = rom[off:off+9]
        tt = raw[0] | (raw[1] << 8) | (raw[2] << 16)
        gfx = raw[3] | (raw[4] << 8) | (raw[5] << 16)
        pal = raw[6] | (raw[7] << 8) | (raw[8] << 16)
        print(f"  TS {ts_id:2d}: raw={raw.hex()} → tileTable=0x{tt:06x} gfx=0x{gfx:06x} pal=0x{pal:06x}")

    # Now check: what if the table has a DIFFERENT structure?
    # What if each entry is 3 pointers of 2 bytes (SNES bank:offset)?
    # Or what if the entries are indexed differently?
    print()

    # Check if there's another table nearby
    # Look for the CRE bitflag table or GFX control table
    print("Searching for CRE/graphics control table...")
    # The game might have a separate table that controls CRE loading behavior
    # Let me search around the tileset table for any related data

    # Check bytes before the tileset table
    pre_bytes = rom[table_pc - 20:table_pc]
    print(f"  20 bytes before table: {pre_bytes.hex()}")

    # Check bytes after the table (29 entries * 9 = 261 bytes)
    post_off = table_pc + 29 * 9
    post_bytes = rom[post_off:post_off + 20]
    print(f"  20 bytes after table: {post_bytes.hex()}")

if __name__ == "__main__":
    main()
