#!/usr/bin/env python3
"""
Verify LZ5 decompression for Ceres tileset GFX data.
Compare working tileset 16 vs broken tileset 18.
"""
import sys
import struct

ROM_PATH = "test-resources/Super Metroid (JU) [!].smc"

def snes_to_pc(snes_addr):
    bank = (snes_addr >> 16) & 0xFF
    addr = snes_addr & 0xFFFF
    return ((bank & 0x7F) * 0x8000) + (addr & 0x7FFF)

def decompress_lz5(rom, start_pc):
    """Independent LZ5 decompressor based on aremath/sm_rando."""
    dst = bytearray()
    pos = start_pc

    while pos < len(rom):
        cmd = rom[pos]
        if cmd == 0xFF:
            pos += 1
            break

        top_bits = (cmd >> 5) & 7
        if top_bits == 7:
            cmd_code = (cmd >> 2) & 7
            high = cmd & 0x03
            low = rom[pos + 1]
            length = (high << 8 | low) + 1
            pos += 2
        else:
            cmd_code = top_bits
            length = (cmd & 0x1F) + 1
            pos += 1

        if cmd_code == 0:  # Direct copy
            dst.extend(rom[pos:pos+length])
            pos += length
        elif cmd_code == 1:  # Byte fill
            dst.extend(bytes([rom[pos]]) * length)
            pos += 1
        elif cmd_code == 2:  # Word fill
            b1, b2 = rom[pos], rom[pos+1]
            pos += 2
            for i in range(length):
                dst.append(b1 if i % 2 == 0 else b2)
        elif cmd_code == 3:  # Increasing fill
            b = rom[pos]
            pos += 1
            for i in range(length):
                dst.append((b + i) & 0xFF)
        elif cmd_code == 4:  # Absolute copy
            addr = rom[pos] | (rom[pos+1] << 8)
            pos += 2
            for i in range(length):
                src_idx = addr + (i % max(1, len(dst) - addr)) if addr < len(dst) else 0
                if src_idx < len(dst):
                    dst.append(dst[src_idx])
                else:
                    dst.append(0)
        elif cmd_code == 5:  # XOR absolute copy
            addr = rom[pos] | (rom[pos+1] << 8)
            pos += 2
            for i in range(length):
                src_idx = addr + (i % max(1, len(dst) - addr)) if addr < len(dst) else 0
                if src_idx < len(dst):
                    dst.append(dst[src_idx] ^ 0xFF)
                else:
                    dst.append(0xFF)
        elif cmd_code == 6:  # Relative copy
            rel = rom[pos]
            pos += 1
            src_addr = len(dst) - rel
            for i in range(length):
                src_idx = src_addr + (i % max(1, rel))
                if 0 <= src_idx < len(dst):
                    dst.append(dst[src_idx])
                else:
                    dst.append(0)
        elif cmd_code == 7:  # Relative XOR copy
            rel = rom[pos]
            pos += 1
            src_addr = len(dst) - rel
            for i in range(length):
                src_idx = src_addr + (i % max(1, rel))
                if 0 <= src_idx < len(dst):
                    dst.append(dst[src_idx] ^ 0xFF)
                else:
                    dst.append(0xFF)

    return bytes(dst), pos - start_pc

def dump_tile(data, tile_idx):
    """Dump a single 4bpp tile's bitplane data."""
    off = tile_idx * 32
    if off + 32 > len(data):
        print(f"  Tile {tile_idx}: OUT OF RANGE")
        return

    print(f"  Tile {tile_idx} (offset 0x{off:04x}):")
    print(f"    BP0/1 (rows 0-7):")
    for row in range(8):
        bp0 = data[off + row * 2]
        bp1 = data[off + row * 2 + 1]
        # Decode pixels
        pixels = []
        for bit in range(7, -1, -1):
            v = ((bp0 >> bit) & 1) | (((bp1 >> bit) & 1) << 1)
            pixels.append(v)
        print(f"      row {row}: bp0={bp0:02x} bp1={bp1:02x} => {''.join(str(p) for p in pixels)}")

    print(f"    BP2/3 (rows 0-7):")
    for row in range(8):
        bp2 = data[off + row * 2 + 16]
        bp3 = data[off + row * 2 + 17]
        pixels = []
        for bit in range(7, -1, -1):
            v = (((bp2 >> bit) & 1) << 2) | (((bp3 >> bit) & 1) << 3)
            pixels.append(v)
        print(f"      row {row}: bp2={bp2:02x} bp3={bp3:02x} => {''.join(hex(p)[2:] for p in pixels)}")

def main():
    with open(ROM_PATH, "rb") as f:
        rom = f.read()

    # Check for SMC header (512 bytes)
    rom_start = 0x200 if len(rom) % 0x8000 == 0x200 else 0
    print(f"ROM size: {len(rom)} bytes, header offset: {rom_start}")

    # Tileset table at $8F:E6A2
    table_pc = snes_to_pc(0x8FE6A2) + rom_start
    print(f"Tileset table PC: 0x{table_pc:06x}")

    # Dump all Ceres tilesets (15-20) + tileset 0 for reference
    for ts_id in [0, 15, 16, 17, 18, 19, 20]:
        entry_off = table_pc + ts_id * 9
        tile_table_ptr = rom[entry_off] | (rom[entry_off+1] << 8) | (rom[entry_off+2] << 16)
        gfx_ptr = rom[entry_off+3] | (rom[entry_off+4] << 8) | (rom[entry_off+5] << 16)
        pal_ptr = rom[entry_off+6] | (rom[entry_off+7] << 8) | (rom[entry_off+8] << 16)

        gfx_pc = snes_to_pc(gfx_ptr) + rom_start
        print(f"\nTileset {ts_id}: tileTable=0x{tile_table_ptr:06x} gfx=0x{gfx_ptr:06x} (PC=0x{gfx_pc:06x}) pal=0x{pal_ptr:06x}")

        # Decompress GFX
        gfx_data, consumed = decompress_lz5(rom, gfx_pc)
        print(f"  Decompressed GFX: {len(gfx_data)} bytes ({len(gfx_data) // 32} tiles), consumed {consumed} ROM bytes")

        # Dump first few raw bytes
        print(f"  First 64 bytes: {gfx_data[:64].hex()}")

        # Dump tiles 0, 1, 2, 3
        for t in [0, 1, 2, 3]:
            dump_tile(gfx_data, t)

        # Also dump tiles at the boundary areas
        if len(gfx_data) >= 0x5000 + 32:
            print(f"  Tile 640 (CRE boundary, offset 0x5000):")
            dump_tile(gfx_data, 640)

if __name__ == "__main__":
    main()
