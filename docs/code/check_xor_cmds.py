#!/usr/bin/env python3
"""Check if Ceres GFX data uses XOR LZ5 commands (cmd 5 or 7)."""

ROM_PATH = "test-resources/Super Metroid (JU) [!].smc"

def snes_to_pc(snes_addr):
    bank = (snes_addr >> 16) & 0xFF
    addr = snes_addr & 0xFFFF
    return ((bank & 0x7F) * 0x8000) + (addr & 0x7FFF)

def trace_lz5_xor(rom, start_pc, label):
    """Check for XOR commands and wrapping in LZ5 stream."""
    dst_len = 0
    pos = start_pc
    cmd_count = 0
    xor_wraps = []

    while pos < len(rom):
        cmd = rom[pos]
        if cmd == 0xFF:
            break
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
            pos += length
            dst_len += length
        elif cmd_code == 1:
            pos += 1; dst_len += length
        elif cmd_code == 2:
            pos += 2; dst_len += length
        elif cmd_code == 3:
            pos += 1; dst_len += length
        elif cmd_code == 4:
            addr = rom[pos] | (rom[pos+1] << 8); pos += 2
            avail = dst_len - addr if addr < dst_len else 0
            if length > avail:
                print(f"  [{label}] cmd 4 (ABS_COPY) at dst={dst_len}, addr={addr}, len={length}, avail={avail} → WRAPS")
            dst_len += length
        elif cmd_code == 5:
            addr = rom[pos] | (rom[pos+1] << 8); pos += 2
            avail = dst_len - addr if addr < dst_len else 0
            wraps = length > avail
            print(f"  [{label}] cmd 5 (XOR_ABS) at dst={dst_len}, addr={addr}, len={length}, avail={avail} → {'WRAPS!' if wraps else 'ok'}")
            if wraps:
                xor_wraps.append(('cmd5', dst_len, addr, length, avail))
            dst_len += length
        elif cmd_code == 6:
            rel = rom[pos]; pos += 1
            if length > rel:
                pass  # non-XOR wrapping is OK
            dst_len += length
        elif cmd_code == 7:
            rel = rom[pos]; pos += 1
            wraps = length > rel
            print(f"  [{label}] cmd 7 (XOR_REL) at dst={dst_len}, rel={rel}, len={length} → {'WRAPS!' if wraps else 'ok'}")
            if wraps:
                xor_wraps.append(('cmd7', dst_len, dst_len - rel, length, rel))
            dst_len += length

        cmd_count += 1

    print(f"  Total: {dst_len} bytes, {cmd_count} commands, {len(xor_wraps)} XOR wraps")
    return xor_wraps

def main():
    with open(ROM_PATH, "rb") as f:
        rom = f.read()
    rom_start = 0x200 if len(rom) % 0x8000 == 0x200 else 0

    table_pc = snes_to_pc(0x8FE6A2) + rom_start

    for ts_id in [0, 15, 16, 17, 18, 19, 20]:
        entry = table_pc + ts_id * 9
        gfx_ptr = rom[entry+3] | (rom[entry+4] << 8) | (rom[entry+5] << 16)
        gfx_pc = snes_to_pc(gfx_ptr) + rom_start
        print(f"\nTileset {ts_id} GFX at 0x{gfx_ptr:06x} (PC=0x{gfx_pc:06x}):")
        xw = trace_lz5_xor(rom, gfx_pc, f"ts{ts_id}")

if __name__ == "__main__":
    main()
