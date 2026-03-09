#!/usr/bin/env python3
"""
Systematically try all plausible 4bpp byte arrangements for Ceres GFX.
"""
from PIL import Image

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

def parse_bgr555_palette(data):
    palettes = []
    for row in range(8):
        pal = []
        for col in range(16):
            off = (row * 16 + col) * 2
            if off + 1 < len(data):
                bgr = data[off] | (data[off+1] << 8)
                r = ((bgr & 0x1F) * 255 + 15) // 31
                g = (((bgr >> 5) & 0x1F) * 255 + 15) // 31
                b = (((bgr >> 10) & 0x1F) * 255 + 15) // 31
                pal.append((r, g, b, 255 if col > 0 else 0))
            else:
                pal.append((0, 0, 0, 0))
        palettes.append(pal)
    return palettes

def parse_tile_table(data, num=1024):
    metatiles = []
    for i in range(num):
        off = i * 8
        words = [data[off+j*2] | (data[off+j*2+1] << 8) for j in range(4)]
        metatiles.append(words)
    return metatiles

def decode_tile(data, tile_off, palette, pal_row, bp_fn):
    """Generic tile decoder. bp_fn(data, tile_off, row) returns (bp0, bp1, bp2, bp3)."""
    pixels = []
    for y in range(8):
        bp0, bp1, bp2, bp3 = bp_fn(data, tile_off, y)
        for x in range(8):
            bit = 7 - x
            idx = ((bp0 >> bit) & 1) | (((bp1 >> bit) & 1) << 1) | \
                  (((bp2 >> bit) & 1) << 2) | (((bp3 >> bit) & 1) << 3)
            pixels.append(palette[pal_row][idx])
    return pixels

# Format 0: Standard SNES 4bpp [bp0,bp1 interleaved] [bp2,bp3 interleaved]
def fmt_standard(data, off, row):
    return (data[off+row*2], data[off+row*2+1], data[off+row*2+16], data[off+row*2+17])

# Format 1: Non-interleaved [bp0×8][bp1×8][bp2×8][bp3×8]
def fmt_noninterleaved(data, off, row):
    return (data[off+row], data[off+8+row], data[off+16+row], data[off+24+row])

# Format 2: Byte de-interleaved (even→bp0/bp1, odd→bp2/bp3)
def fmt_byte_deinterleave(data, off, row):
    return (data[off+row*2], data[off+row*2+2] if row < 7 else data[off+14],
            data[off+row*2+1], data[off+row*2+3] if row < 7 else data[off+15])

# Format 3: Word de-interleave (even words→bp0/bp1, odd words→bp2/bp3)
def fmt_word_deinterleave(data, off, row):
    # Even words: positions 0,1, 4,5, 8,9, 12,13 → bp0,bp1 rows 0-3 (first 4 rows)
    # And 16,17, 20,21, 24,25, 28,29 → bp0,bp1 rows 4-7
    if row < 4:
        bp01_off = off + row * 4
    else:
        bp01_off = off + 16 + (row-4) * 4
    if row < 4:
        bp23_off = off + row * 4 + 2
    else:
        bp23_off = off + 16 + (row-4) * 4 + 2
    return (data[bp01_off], data[bp01_off+1], data[bp23_off], data[bp23_off+1])

# Format 4: All 4 bp for each row together [bp0,bp1,bp2,bp3]×8
def fmt_row_grouped(data, off, row):
    return (data[off+row*4], data[off+row*4+1], data[off+row*4+2], data[off+row*4+3])

# Format 5: [bp0,bp2,bp1,bp3] interleaved per row (VRAM low/high split)
def fmt_low_high_split(data, off, row):
    return (data[off+row*2], data[off+row*2+16], data[off+row*2+1], data[off+row*2+17])

# Format 6: De-interleave at 0x4000 byte level: first half = low bytes, second half = high bytes
# Within first half, standard tile layout for bp0+bp2; within second half, bp1+bp3
def fmt_global_deinterleave(data, off, row):
    # This only works on the full data, not per-tile
    # Tile N: low bytes at N*16 in first half, high bytes at 0x4000 + N*16
    tile_num = off // 32
    base_low = tile_num * 16
    base_high = 0x4000 + tile_num * 16
    if base_high + 16 > len(data):
        return (0, 0, 0, 0)
    bp0 = data[base_low + row] if row < 8 else 0
    bp2 = data[base_low + 8 + row] if row < 8 else 0
    bp1 = data[base_high + row] if row < 8 else 0
    bp3 = data[base_high + 8 + row] if row < 8 else 0
    return (bp0, bp1, bp2, bp3)

# Format 7: Same as 6 but bp0/bp1 in low half, bp2/bp3 in high half
def fmt_global_bp_split(data, off, row):
    tile_num = off // 32
    base_low = tile_num * 16
    base_high = 0x4000 + tile_num * 16
    if base_high + 16 > len(data):
        return (0, 0, 0, 0)
    bp0 = data[base_low + row * 2]
    bp1 = data[base_low + row * 2 + 1]
    bp2 = data[base_high + row * 2]
    bp3 = data[base_high + row * 2 + 1]
    return (bp0, bp1, bp2, bp3)

def render_grid(gfx_data, palette, metatiles, bp_fn, max_metatiles=256):
    """Render first N metatiles in a grid."""
    cols = 16
    rows = (max_metatiles + cols - 1) // cols
    img = Image.new("RGBA", (cols*16, rows*16), (12, 12, 24, 255))
    for i in range(min(max_metatiles, len(metatiles))):
        words = metatiles[i]
        for q in range(4):
            word = words[q]
            tile_num = word & 0x3FF
            pal_row = (word >> 10) & 7
            hflip = (word >> 14) & 1
            vflip = (word >> 15) & 1
            if tile_num * 32 + 32 > len(gfx_data):
                continue
            try:
                tp = decode_tile(gfx_data, tile_num * 32, palette, pal_row, bp_fn)
            except (IndexError, ValueError):
                continue
            bx = (i % cols) * 16 + (8 if q % 2 else 0)
            by = (i // cols) * 16 + (8 if q >= 2 else 0)
            for py in range(8):
                for px in range(8):
                    sx = 7 - px if hflip else px
                    sy = 7 - py if vflip else py
                    pixel = tp[sy * 8 + sx]
                    if pixel[3] > 0:
                        img.putpixel((bx + px, by + py), pixel)
    return img

def main():
    with open(ROM_PATH, "rb") as f:
        rom = f.read()
    rom_start = 0x200 if len(rom) % 0x8000 == 0x200 else 0

    table_pc = snes_to_pc(0x8FE6A2) + rom_start
    entry = table_pc + 18 * 9
    tile_table_ptr = rom[entry] | (rom[entry+1] << 8) | (rom[entry+2] << 16)
    gfx_ptr = rom[entry+3] | (rom[entry+4] << 8) | (rom[entry+5] << 16)
    pal_ptr = rom[entry+6] | (rom[entry+7] << 8) | (rom[entry+8] << 16)

    gfx_data = decompress_lz5(rom, snes_to_pc(gfx_ptr) + rom_start)
    tile_table = decompress_lz5(rom, snes_to_pc(tile_table_ptr) + rom_start)
    pal_data = decompress_lz5(rom, snes_to_pc(pal_ptr) + rom_start)
    palette = parse_bgr555_palette(pal_data)
    metatiles = parse_tile_table(tile_table)

    formats = [
        ("standard", fmt_standard),
        ("noninterleaved", fmt_noninterleaved),
        ("row_grouped", fmt_row_grouped),
        ("low_high_split", fmt_low_high_split),
        ("global_deinterleave", fmt_global_deinterleave),
        ("global_bp_split", fmt_global_bp_split),
    ]

    for name, fn in formats:
        print(f"Rendering format: {name}")
        try:
            img = render_grid(gfx_data, palette, metatiles, fn, 256)
            img.save(f"/tmp/ceres_fmt_{name}.png")
            print(f"  Saved /tmp/ceres_fmt_{name}.png")
        except Exception as e:
            print(f"  Error: {e}")

if __name__ == "__main__":
    main()
