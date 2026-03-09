#!/usr/bin/env python3
"""
Trace LZ5 decompression commands for Ceres GFX to verify correctness.
Compare with a known-good tileset.
"""

ROM_PATH = "test-resources/Super Metroid (JU) [!].smc"

def snes_to_pc(snes_addr):
    bank = (snes_addr >> 16) & 0xFF
    addr = snes_addr & 0xFFFF
    return ((bank & 0x7F) * 0x8000) + (addr & 0x7FFF)

CMD_NAMES = {0: "DIRECT", 1: "BYTE_FILL", 2: "WORD_FILL", 3: "INC_FILL",
             4: "ABS_COPY", 5: "XOR_ABS", 6: "REL_COPY", 7: "XOR_REL"}

def trace_lz5(rom, start_pc, max_cmds=50):
    """Trace LZ5 commands and show what they produce."""
    dst = bytearray()
    pos = start_pc
    cmd_count = 0

    while pos < len(rom) and cmd_count < max_cmds:
        cmd_pos = pos
        cmd = rom[pos]
        if cmd == 0xFF:
            print(f"  [{cmd_pos:06x}] END (0xFF)")
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

        dst_before = len(dst)

        if cmd_code == 0:  # Direct copy
            data = rom[pos:pos+length]
            dst.extend(data)
            pos += length
            preview = data[:16].hex() + ("..." if length > 16 else "")
            print(f"  [{cmd_pos:06x}] {CMD_NAMES[cmd_code]:10s} len={length:4d} => dst[{dst_before:5d}] data={preview}")
        elif cmd_code == 1:  # Byte fill
            b = rom[pos]
            dst.extend(bytes([b]) * length)
            pos += 1
            print(f"  [{cmd_pos:06x}] {CMD_NAMES[cmd_code]:10s} len={length:4d} => dst[{dst_before:5d}] byte=0x{b:02x}")
        elif cmd_code == 2:  # Word fill
            b1, b2 = rom[pos], rom[pos+1]
            pos += 2
            for i in range(length):
                dst.append(b1 if i % 2 == 0 else b2)
            print(f"  [{cmd_pos:06x}] {CMD_NAMES[cmd_code]:10s} len={length:4d} => dst[{dst_before:5d}] word=0x{b1:02x}{b2:02x}")
        elif cmd_code == 3:  # Increasing fill
            b = rom[pos]
            pos += 1
            for i in range(length):
                dst.append((b + i) & 0xFF)
            print(f"  [{cmd_pos:06x}] {CMD_NAMES[cmd_code]:10s} len={length:4d} => dst[{dst_before:5d}] start=0x{b:02x}")
        elif cmd_code == 4:  # Absolute copy
            addr = rom[pos] | (rom[pos+1] << 8)
            pos += 2
            start_len = len(dst)
            for i in range(length):
                if addr < start_len:
                    src_idx = addr + i
                    # Handle wrapping: if src_idx >= start_len, wrap
                    if src_idx >= start_len:
                        wrap_len = start_len - addr
                        src_idx = addr + (i % wrap_len) if wrap_len > 0 else addr
                    dst.append(dst[src_idx])
                else:
                    dst.append(0)
            print(f"  [{cmd_pos:06x}] {CMD_NAMES[cmd_code]:10s} len={length:4d} => dst[{dst_before:5d}] from_addr={addr}")
        elif cmd_code == 5:  # XOR absolute copy
            addr = rom[pos] | (rom[pos+1] << 8)
            pos += 2
            start_len = len(dst)
            for i in range(length):
                if addr < start_len:
                    src_idx = addr + i
                    if src_idx >= start_len:
                        wrap_len = start_len - addr
                        src_idx = addr + (i % wrap_len) if wrap_len > 0 else addr
                    dst.append(dst[src_idx] ^ 0xFF)
                else:
                    dst.append(0xFF)
            print(f"  [{cmd_pos:06x}] {CMD_NAMES[cmd_code]:10s} len={length:4d} => dst[{dst_before:5d}] from_addr={addr} (XOR)")
        elif cmd_code == 6:  # Relative copy
            rel = rom[pos]
            pos += 1
            src_addr = len(dst) - rel
            start_len = len(dst)
            for i in range(length):
                src_idx = src_addr + (i % rel) if rel > 0 else src_addr
                if 0 <= src_idx < len(dst):
                    dst.append(dst[src_idx])
                else:
                    dst.append(0)
            print(f"  [{cmd_pos:06x}] {CMD_NAMES[cmd_code]:10s} len={length:4d} => dst[{dst_before:5d}] rel=-{rel} (src={src_addr})")
        elif cmd_code == 7:  # XOR relative copy
            rel = rom[pos]
            pos += 1
            src_addr = len(dst) - rel
            start_len = len(dst)
            for i in range(length):
                src_idx = src_addr + (i % rel) if rel > 0 else src_addr
                if 0 <= src_idx < len(dst):
                    dst.append(dst[src_idx] ^ 0xFF)
                else:
                    dst.append(0xFF)
            print(f"  [{cmd_pos:06x}] {CMD_NAMES[cmd_code]:10s} len={length:4d} => dst[{dst_before:5d}] rel=-{rel} (src={src_addr}) (XOR)")

        cmd_count += 1

    print(f"\n  Total decompressed: {len(dst)} bytes after {cmd_count} commands")
    return bytes(dst)

def main():
    with open(ROM_PATH, "rb") as f:
        rom = f.read()

    rom_start = 0x200 if len(rom) % 0x8000 == 0x200 else 0

    # Working tileset 15 GFX
    print("=== Tileset 15 GFX (WORKING, 0xC0B004) ===")
    pc15 = snes_to_pc(0xC0B004) + rom_start
    data15 = trace_lz5(rom, pc15)

    print("\n=== Tileset 17 GFX (BROKEN, 0xC0E22A) ===")
    pc17 = snes_to_pc(0xC0E22A) + rom_start
    data17 = trace_lz5(rom, pc17)

    # Compare first 64 bytes
    print("\n=== First 64 bytes comparison ===")
    print(f"TS15: {data15[:64].hex()}")
    print(f"TS17: {data17[:64].hex()}")

    # Check for the suspicious pattern in TS17
    print("\n=== Pattern analysis for TS17 ===")
    print("Odd-positioned bytes (bp1 for each row):")
    odd_bytes = [data17[i] for i in range(1, 64, 2)]
    print(f"  {' '.join(f'{b:02x}' for b in odd_bytes)}")
    print("Even-positioned bytes (bp0 for each row):")
    even_bytes = [data17[i] for i in range(0, 64, 2)]
    print(f"  {' '.join(f'{b:02x}' for b in even_bytes)}")

if __name__ == "__main__":
    main()
