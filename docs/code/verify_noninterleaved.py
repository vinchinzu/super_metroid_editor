#!/usr/bin/env python3
"""
Verify non-interleaved 4bpp format theory for Ceres tilesets 17-20.
Render tileset 18 grid using non-interleaved decode and compare.
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
            dst.extend(rom[pos:pos+length])
            pos += length
        elif cmd_code == 1:
            dst.extend(bytes([rom[pos]]) * length)
            pos += 1
        elif cmd_code == 2:
            b1, b2 = rom[pos], rom[pos+1]
            pos += 2
            for i in range(length):
                dst.append(b1 if i % 2 == 0 else b2)
        elif cmd_code == 3:
            b = rom[pos]; pos += 1
            for i in range(length):
                dst.append((b + i) & 0xFF)
        elif cmd_code == 4:
            addr = rom[pos] | (rom[pos+1] << 8); pos += 2
            sl = len(dst)
            for i in range(length):
                si = addr + i
                if si < sl:
                    dst.append(dst[si])
                elif addr < sl:
                    dst.append(dst[addr + ((si - addr) % (sl - addr))])
                else:
                    dst.append(0)
        elif cmd_code == 5:
            addr = rom[pos] | (rom[pos+1] << 8); pos += 2
            sl = len(dst)
            for i in range(length):
                si = addr + i
                if si < sl:
                    dst.append(dst[si] ^ 0xFF)
                elif addr < sl:
                    dst.append(dst[addr + ((si - addr) % (sl - addr))] ^ 0xFF)
                else:
                    dst.append(0xFF)
        elif cmd_code == 6:
            rel = rom[pos]; pos += 1
            sa = len(dst) - rel
            for i in range(length):
                si = sa + (i % rel) if rel > 0 else sa
                dst.append(dst[si] if 0 <= si < len(dst) else 0)
        elif cmd_code == 7:
            rel = rom[pos]; pos += 1
            sa = len(dst) - rel
            for i in range(length):
                si = sa + (i % rel) if rel > 0 else sa
                dst.append((dst[si] ^ 0xFF) if 0 <= si < len(dst) else 0xFF)
    return bytes(dst)

def parse_bgr555_palette(data):
    """Parse BGR555 palette data → array of 8×16 RGBA tuples."""
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

def decode_tile_standard(data, tile_off, palette, pal_row):
    """Standard SNES 4bpp decode."""
    pixels = []
    for y in range(8):
        bp0 = data[tile_off + y * 2]
        bp1 = data[tile_off + y * 2 + 1]
        bp2 = data[tile_off + y * 2 + 16]
        bp3 = data[tile_off + y * 2 + 17]
        for x in range(8):
            bit = 7 - x
            idx = ((bp0 >> bit) & 1) | (((bp1 >> bit) & 1) << 1) | \
                  (((bp2 >> bit) & 1) << 2) | (((bp3 >> bit) & 1) << 3)
            pixels.append(palette[pal_row][idx])
    return pixels

def decode_tile_noninterleaved(data, tile_off, palette, pal_row):
    """Non-interleaved 4bpp: [bp0×8][bp1×8][bp2×8][bp3×8]."""
    pixels = []
    for y in range(8):
        bp0 = data[tile_off + y]
        bp1 = data[tile_off + 8 + y]
        bp2 = data[tile_off + 16 + y]
        bp3 = data[tile_off + 24 + y]
        for x in range(8):
            bit = 7 - x
            idx = ((bp0 >> bit) & 1) | (((bp1 >> bit) & 1) << 1) | \
                  (((bp2 >> bit) & 1) << 2) | (((bp3 >> bit) & 1) << 3)
            pixels.append(palette[pal_row][idx])
    return pixels

def parse_tile_table(data, num_metatiles=1024):
    """Parse metatile table: 4 words (8 bytes) per metatile."""
    metatiles = []
    for i in range(num_metatiles):
        off = i * 8
        words = []
        for j in range(4):
            w = data[off + j*2] | (data[off + j*2 + 1] << 8)
            words.append(w)
        metatiles.append(words)
    return metatiles

def render_metatile(gfx_data, palette, metatile_words, decode_fn):
    """Render a 16×16 metatile using the given decode function."""
    pixels = [(0,0,0,0)] * 256
    positions = [(0,0), (8,0), (0,8), (8,8)]  # TL, TR, BL, BR
    for q in range(4):
        word = metatile_words[q]
        tile_num = word & 0x3FF
        pal_row = (word >> 10) & 7
        hflip = (word >> 14) & 1
        vflip = (word >> 15) & 1

        if tile_num * 32 + 32 > len(gfx_data):
            continue

        tile_pixels = decode_fn(gfx_data, tile_num * 32, palette, pal_row)
        bx, by = positions[q]
        for py in range(8):
            for px in range(8):
                sx = 7 - px if hflip else px
                sy = 7 - py if vflip else py
                src = tile_pixels[sy * 8 + sx]
                if src[3] > 0:  # not transparent
                    pixels[(by + py) * 16 + (bx + px)] = src
    return pixels

def render_tileset_grid(gfx_data, palette, metatiles, decode_fn):
    """Render 32×32 grid of metatiles → 512×512 image."""
    img = Image.new("RGBA", (512, 512), (12, 12, 24, 255))
    for i in range(min(1024, len(metatiles))):
        col, row = i % 32, i // 32
        meta_pixels = render_metatile(gfx_data, palette, metatiles[i], decode_fn)
        for py in range(16):
            for px in range(16):
                pixel = meta_pixels[py * 16 + px]
                if pixel[3] > 0:
                    img.putpixel((col * 16 + px, row * 16 + py), pixel)
    return img

def main():
    with open(ROM_PATH, "rb") as f:
        rom = f.read()
    rom_start = 0x200 if len(rom) % 0x8000 == 0x200 else 0

    # Read tileset 18 pointers
    table_pc = snes_to_pc(0x8FE6A2) + rom_start
    entry = table_pc + 18 * 9
    tile_table_ptr = rom[entry] | (rom[entry+1] << 8) | (rom[entry+2] << 16)
    gfx_ptr = rom[entry+3] | (rom[entry+4] << 8) | (rom[entry+5] << 16)
    pal_ptr = rom[entry+6] | (rom[entry+7] << 8) | (rom[entry+8] << 16)

    print(f"Tileset 18: tile_table=0x{tile_table_ptr:06x} gfx=0x{gfx_ptr:06x} pal=0x{pal_ptr:06x}")

    # Decompress
    gfx_data = decompress_lz5(rom, snes_to_pc(gfx_ptr) + rom_start)
    tile_table = decompress_lz5(rom, snes_to_pc(tile_table_ptr) + rom_start)
    pal_data = decompress_lz5(rom, snes_to_pc(pal_ptr) + rom_start)

    print(f"GFX: {len(gfx_data)} bytes, TileTable: {len(tile_table)} bytes, Palette: {len(pal_data)} bytes")

    palette = parse_bgr555_palette(pal_data)
    metatiles = parse_tile_table(tile_table)

    # Render with standard 4bpp
    print("Rendering with standard 4bpp...")
    img_std = render_tileset_grid(gfx_data, palette, metatiles, decode_tile_standard)
    img_std.save("/tmp/ceres_ts18_standard.png")
    print("Saved /tmp/ceres_ts18_standard.png")

    # Render with non-interleaved 4bpp
    print("Rendering with non-interleaved 4bpp...")
    img_ni = render_tileset_grid(gfx_data, palette, metatiles, decode_tile_noninterleaved)
    img_ni.save("/tmp/ceres_ts18_noninterleaved.png")
    print("Saved /tmp/ceres_ts18_noninterleaved.png")

    # Also render tileset 20 (Ceres Ridley area)
    entry20 = table_pc + 20 * 9
    gfx_ptr20 = rom[entry20+3] | (rom[entry20+4] << 8) | (rom[entry20+5] << 16)
    pal_ptr20 = rom[entry20+6] | (rom[entry20+7] << 8) | (rom[entry20+8] << 16)
    gfx_data20 = decompress_lz5(rom, snes_to_pc(gfx_ptr20) + rom_start)
    pal_data20 = decompress_lz5(rom, snes_to_pc(pal_ptr20) + rom_start)
    palette20 = parse_bgr555_palette(pal_data20)

    print(f"\nTileset 20: gfx=0x{gfx_ptr20:06x} ({len(gfx_data20)} bytes)")
    img_std20 = render_tileset_grid(gfx_data20, palette20, metatiles, decode_tile_standard)
    img_std20.save("/tmp/ceres_ts20_standard.png")
    img_ni20 = render_tileset_grid(gfx_data20, palette20, metatiles, decode_tile_noninterleaved)
    img_ni20.save("/tmp/ceres_ts20_noninterleaved.png")
    print("Saved ts20 standard and non-interleaved")

if __name__ == "__main__":
    main()
