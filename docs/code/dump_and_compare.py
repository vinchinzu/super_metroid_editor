#!/usr/bin/env python3
"""Compare Kotlin and Python LZ5 decompression byte-by-byte."""

ROM_PATH = "test-resources/Super Metroid (JU) [!].smc"

def snes_to_pc(snes_addr):
    bank = (snes_addr >> 16) & 0xFF
    addr = snes_addr & 0xFFFF
    return ((bank & 0x7F) * 0x8000) + (addr & 0x7FFF)

def decompress_lz5(rom, start_pc):
    dst = bytearray()
    pos = start_pc
    while pos < len(rom):
        cmd = rom[pos]
        if cmd == 0xFF: break
        top_bits = (cmd >> 5) & 7
        if top_bits == 7:
            cmd_code = (cmd >> 2) & 7
            length = ((cmd & 0x03) << 8 | rom[pos + 1]) + 1
            pos += 2
        else:
            cmd_code = top_bits
            length = (cmd & 0x1F) + 1
            pos += 1
        if cmd_code == 0:
            dst.extend(rom[pos:pos+length]); pos += length
        elif cmd_code == 1:
            dst.extend(bytes([rom[pos]]) * length); pos += 1
        elif cmd_code == 2:
            b1, b2 = rom[pos], rom[pos+1]; pos += 2
            for i in range(length): dst.append(b1 if i % 2 == 0 else b2)
        elif cmd_code == 3:
            b = rom[pos]; pos += 1
            for i in range(length): dst.append((b + i) & 0xFF)
        elif cmd_code == 4:
            addr = rom[pos] | (rom[pos+1] << 8); pos += 2
            sl = len(dst)
            for i in range(length):
                si = addr + i
                if si < sl: dst.append(dst[si])
                elif addr < sl: dst.append(dst[addr + ((si - addr) % (sl - addr))])
                else: dst.append(0)
        elif cmd_code == 5:
            addr = rom[pos] | (rom[pos+1] << 8); pos += 2
            sl = len(dst)
            for i in range(length):
                si = addr + i
                if si < sl: dst.append(dst[si] ^ 0xFF)
                elif addr < sl: dst.append(dst[addr + ((si - addr) % (sl - addr))] ^ 0xFF)
                else: dst.append(0xFF)
        elif cmd_code == 6:
            rel = rom[pos]; pos += 1; sa = len(dst) - rel
            for i in range(length):
                si = sa + (i % rel) if rel > 0 else sa
                dst.append(dst[si] if 0 <= si < len(dst) else 0)
        elif cmd_code == 7:
            rel = rom[pos]; pos += 1; sa = len(dst) - rel
            for i in range(length):
                si = sa + (i % rel) if rel > 0 else sa
                dst.append((dst[si] ^ 0xFF) if 0 <= si < len(dst) else 0xFF)
    return bytes(dst)

def main():
    with open(ROM_PATH, "rb") as f:
        rom = f.read()
    rom_start = 0x200 if len(rom) % 0x8000 == 0x200 else 0

    for ts_id in [15, 17]:
        table_pc = snes_to_pc(0x8FE6A2) + rom_start
        entry = table_pc + ts_id * 9
        gfx_ptr = rom[entry+3] | (rom[entry+4] << 8) | (rom[entry+5] << 16)
        gfx_data = decompress_lz5(rom, snes_to_pc(gfx_ptr) + rom_start)

        # Load Kotlin output
        kotlin_file = f"/tmp/kotlin_gfx_ts{ts_id}.bin"
        try:
            kotlin_data = open(kotlin_file, "rb").read()
        except FileNotFoundError:
            print(f"Tileset {ts_id}: Kotlin file not found")
            continue

        print(f"\nTileset {ts_id}: Python={len(gfx_data)}, Kotlin={len(kotlin_data)}")

        if len(gfx_data) != len(kotlin_data):
            print(f"  SIZE MISMATCH!")

        # Compare byte by byte
        mismatches = 0
        first_mismatch = -1
        for i in range(min(len(gfx_data), len(kotlin_data))):
            if gfx_data[i] != kotlin_data[i]:
                if first_mismatch == -1:
                    first_mismatch = i
                mismatches += 1
                if mismatches <= 10:
                    print(f"  MISMATCH at byte {i}: Python=0x{gfx_data[i]:02x}, Kotlin=0x{kotlin_data[i]:02x}")

        if mismatches == 0:
            print(f"  PERFECT MATCH ({len(gfx_data)} bytes)")
        else:
            print(f"  Total mismatches: {mismatches} (first at byte {first_mismatch})")

if __name__ == "__main__":
    main()
