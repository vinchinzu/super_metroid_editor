/*
 * sm_spc_render: Render Super Metroid music using blargg's snes_spc.
 *
 * Usage: sm_spc_render <base_ram.bin> <output.wav> [seconds] [play_index] [song_blocks.bin]
 *
 * base_ram.bin:   65536-byte SPC RAM with engine loaded (song set 0x00)
 * song_blocks.bin: Optional transfer blocks file (size16_le + dest16_le + data, repeated)
 * play_index:     Track ID / play index for port 0 command (default 5)
 *
 * Protocol (from PJBoy's SM-SPC disassembly):
 *   - Port 0 ($F4) = music command: track ID to play, $F0=pause, $FF=load data
 *   - Port 1 ($F5) = sound effect commands (not used here)
 *   - Song table at $581E: tracker pointer = [$581E + track_id * 2]
 *
 * Strategy:
 *   1. Build SPC file from base engine RAM, load into emulator
 *   2. Skip 2s for engine initialization
 *   3. Patch emulator RAM with song set transfer blocks (updates $5828+ song data)
 *   4. Write track ID to port 0 (the music command port)
 *   5. Render audio
 */
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include "snes_spc/SNES_SPC.h"
#include "snes_spc/SPC_Filter.h"

static void write_le16(FILE *f, unsigned v) {
    fputc(v & 0xFF, f);
    fputc((v >> 8) & 0xFF, f);
}
static void write_le32(FILE *f, unsigned v) {
    fputc(v & 0xFF, f);
    fputc((v >> 8) & 0xFF, f);
    fputc((v >> 16) & 0xFF, f);
    fputc((v >> 24) & 0xFF, f);
}

static const int SAMPLE_RATE = 32000;

/* Apply transfer blocks to raw SPC RAM.
 * Format: repeated { uint16_le size, uint16_le dest, uint8[size] data }
 * Terminated by size == 0. */
static int apply_transfer_blocks(uint8_t *spc_ram, const uint8_t *blocks, long blocks_size) {
    long pos = 0;
    int count = 0;
    while (pos + 4 <= blocks_size) {
        int size = blocks[pos] | (blocks[pos + 1] << 8);
        if (size == 0) break;
        int dest = blocks[pos + 2] | (blocks[pos + 3] << 8);
        pos += 4;
        if (pos + size > blocks_size) break;
        int len = size;
        if (dest + len > 0x10000) len = 0x10000 - dest;
        memcpy(spc_ram + dest, blocks + pos, len);
        fprintf(stderr, "  Patched $%04X-%04X (%d bytes)\n", dest, dest + len - 1, len);
        pos += size;
        count++;
    }
    return count;
}

int main(int argc, char *argv[]) {
    if (argc < 3) {
        fprintf(stderr,
            "Usage: sm_spc_render <base_ram.bin> <output.wav> [seconds] [play_index] [song_blocks.bin]\n"
            "  base_ram.bin:   65536-byte base SPC RAM (engine only, song set 0x00)\n"
            "  seconds:        render duration (default 120)\n"
            "  play_index:     song play index (default 5)\n"
            "  song_blocks.bin: transfer blocks for the song set\n");
        return 1;
    }

    const char *base_ram_path = argv[1];
    const char *wav_path = argv[2];
    int seconds = argc > 3 ? atoi(argv[3]) : 120;
    int play_index = argc > 4 ? atoi(argv[4]) : 5;
    const char *blocks_path = argc > 5 ? argv[5] : nullptr;
    if (seconds < 1) seconds = 1;
    if (seconds > 600) seconds = 600;

    /* Read base SPC RAM */
    FILE *fin = fopen(base_ram_path, "rb");
    if (!fin) { fprintf(stderr, "Cannot open %s\n", base_ram_path); return 1; }
    unsigned char base_ram[0x10000];
    size_t nread = fread(base_ram, 1, 0x10000, fin);
    fclose(fin);
    if (nread != 0x10000) {
        fprintf(stderr, "Base RAM should be 65536 bytes, got %zu\n", nread);
        return 1;
    }

    /* Read song set transfer blocks if provided */
    unsigned char *blocks_data = nullptr;
    long blocks_size = 0;
    if (blocks_path) {
        fin = fopen(blocks_path, "rb");
        if (fin) {
            fseek(fin, 0, SEEK_END);
            blocks_size = ftell(fin);
            fseek(fin, 0, SEEK_SET);
            blocks_data = (unsigned char *)malloc(blocks_size);
            fread(blocks_data, 1, blocks_size, fin);
            fclose(fin);
            fprintf(stderr, "Loaded %ld bytes of transfer blocks\n", blocks_size);
        }
    }

    /* Build synthetic SPC file from base engine RAM */
    unsigned char *spc = (unsigned char *)calloc(1, 0x10200);
    if (!spc) { fprintf(stderr, "Out of memory\n"); return 1; }

    /* Signature */
    static const char sig[] = "SNES-SPC700 Sound File Data v0.30";
    memcpy(spc, sig, 33);
    spc[33] = 0x1A;
    spc[34] = 0x1A;
    spc[35] = 26;  /* has_id666 */
    spc[36] = 30;  /* version */

    /* CPU: start at engine entry point $1500 */
    spc[37] = 0x00; /* PCL */
    spc[38] = 0x15; /* PCH = $1500 */
    spc[39] = 0x00; /* A */
    spc[40] = 0x00; /* X */
    spc[41] = 0x00; /* Y */
    spc[42] = 0x02; /* PSW */
    spc[43] = 0xCF; /* SP */

    /* Copy BASE engine RAM (without song set patches) */
    memcpy(spc + 0x100, base_ram, 0x10000);

    /* Clear port registers to avoid false triggers during init */
    spc[0x100 + 0xF0] = 0x0A; /* TEST */
    spc[0x100 + 0xF1] = 0x80; /* CONTROL: ROM enabled */
    spc[0x100 + 0xF4] = 0x00; /* Port 0 */
    spc[0x100 + 0xF5] = 0x00; /* Port 1 */
    spc[0x100 + 0xF6] = 0x00; /* Port 2 */
    spc[0x100 + 0xF7] = 0x00; /* Port 3 */

    /* DSP registers: minimal setup, engine init will configure the rest */
    unsigned char *dsp = spc + 0x10100;
    dsp[0x5D] = 0x6C; /* DIR */
    dsp[0x6C] = 0x20; /* FLG: mute (engine clears when ready) */

    /* IPL ROM */
    static const unsigned char ipl_rom[64] = {
        0xCD, 0xEF, 0xBD, 0xE8, 0x00, 0xC6, 0x1D, 0xD0,
        0xFC, 0x8F, 0xAA, 0xF4, 0x8F, 0xBB, 0xF5, 0x78,
        0xCC, 0xF4, 0xD0, 0xFB, 0x2F, 0x19, 0xEB, 0xF4,
        0xD0, 0xFC, 0x7E, 0xF4, 0xD0, 0x0B, 0xE4, 0xF5,
        0xCB, 0xF4, 0xD7, 0x00, 0xFC, 0xD0, 0xF3, 0xAB,
        0x01, 0x10, 0xEF, 0x7E, 0xF4, 0x10, 0xEB, 0xBA,
        0xF6, 0xDA, 0x00, 0xBA, 0xF4, 0xC4, 0xF4, 0xDD,
        0x5D, 0xD0, 0xDB, 0x1F, 0x00, 0x00, 0xC0, 0xFF
    };
    memcpy(spc + 0x101C0, ipl_rom, 64);

    /* Load into emulator */
    SNES_SPC emu;
    if (emu.init()) { fprintf(stderr, "Emulator init failed\n"); return 1; }
    emu.init_rom(ipl_rom);

    auto err = emu.load_spc(spc, 0x10200);
    free(spc);
    if (err) { fprintf(stderr, "SPC load error: %s\n", err); return 1; }
    emu.clear_echo();

    fprintf(stderr, "SPC loaded, PC=$1500\n");

    /* Let the engine initialize (2 seconds) */
    fprintf(stderr, "Engine init (2s skip)...\n");
    err = emu.play(SAMPLE_RATE * 2 * 2, nullptr);
    if (err) fprintf(stderr, "Init play error: %s\n", err);

    /* Check engine state */
    int echo0 = emu.read_port(0, 0);
    int echo1 = emu.read_port(0, 1);
    fprintf(stderr, "After init: port0_echo=0x%02X, port1_echo=0x%02X\n", echo0, echo1);

    /* NOW apply song set transfer blocks directly to the emulator's live RAM */
    if (blocks_data && blocks_size > 0) {
        fprintf(stderr, "Applying song set transfer blocks to live RAM...\n");
        uint8_t *live_ram = emu.smp_ram();
        int n = apply_transfer_blocks(live_ram, blocks_data, blocks_size);
        fprintf(stderr, "Applied %d transfer blocks\n", n);
        free(blocks_data);
        blocks_data = nullptr;
    }

    /* Send play command: write track ID to port 0 (the music command port).
     * The engine at $1793 reads port 0, compares with previous value,
     * and if changed, calls $1740 which loads the tracker from $581E + track_id*2. */
    emu.write_port(0, 0, play_index);

    fprintf(stderr, "Sent play command: port0=%d (track ID)\n", play_index);

    /* Let the command take effect and music start (1 second warmup) */
    {
        short *warmup = new short[SAMPLE_RATE * 2]; // 1 second stereo
        err = emu.play(SAMPLE_RATE * 2, warmup);
        delete[] warmup;
    }
    if (err) fprintf(stderr, "Post-command play error: %s\n", err);

    echo0 = emu.read_port(0, 0);
    fprintf(stderr, "After command: port0_echo=0x%02X\n", echo0);

    /* Render the actual audio */
    fprintf(stderr, "Rendering %d seconds...\n", seconds);

    int total_stereo = SAMPLE_RATE * seconds * 2;
    short *output = (short *)malloc(total_stereo * sizeof(short));
    if (!output) { fprintf(stderr, "Out of memory\n"); return 1; }

    SPC_Filter filter;
    filter.clear();

    int pos = 0;
    while (pos < total_stereo) {
        int chunk = total_stereo - pos;
        if (chunk > 4096) chunk = 4096;
        err = emu.play(chunk, output + pos);
        if (err) {
            fprintf(stderr, "Play error at sample %d: %s\n", pos / 2, err);
            break;
        }
        filter.run(output + pos, chunk);
        pos += chunk;
    }

    int peak = 0;
    for (int i = 0; i < pos; i++) {
        int v = abs(output[i]);
        if (v > peak) peak = v;
    }
    fprintf(stderr, "Peak amplitude: %d (%d sample pairs)\n", peak, pos / 2);

    /* Write WAV */
    FILE *fout = fopen(wav_path, "wb");
    if (!fout) { free(output); return 1; }

    int data_bytes = pos * 2;
    fwrite("RIFF", 1, 4, fout);
    write_le32(fout, 36 + data_bytes);
    fwrite("WAVE", 1, 4, fout);
    fwrite("fmt ", 1, 4, fout);
    write_le32(fout, 16);
    write_le16(fout, 1);
    write_le16(fout, 2);
    write_le32(fout, SAMPLE_RATE);
    write_le32(fout, SAMPLE_RATE * 4);
    write_le16(fout, 4);
    write_le16(fout, 16);
    fwrite("data", 1, 4, fout);
    write_le32(fout, data_bytes);
    fwrite(output, 2, pos, fout);
    fclose(fout);
    free(output);
    if (blocks_data) free(blocks_data);

    fprintf(stderr, "OK: %s (%d bytes)\n", wav_path, 44 + data_bytes);
    return 0;
}
