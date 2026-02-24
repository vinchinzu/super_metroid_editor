/*
 * spc2wav: Convert an SPC file to WAV using blargg's snes_spc library.
 * Usage: spc2wav <input.spc> <output.wav> [seconds]
 *
 * Reads a standard .spc file, emulates the SPC700 for the given duration
 * (default 120s), and writes 32kHz 16-bit stereo WAV output.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "snes_spc/spc.h"

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

int main(int argc, char *argv[]) {
    if (argc < 3) {
        fprintf(stderr, "Usage: spc2wav <input.spc> <output.wav> [seconds]\n");
        return 1;
    }

    const char *spc_path = argv[1];
    const char *wav_path = argv[2];
    int seconds = 120;
    if (argc > 3) seconds = atoi(argv[3]);
    if (seconds < 1) seconds = 1;
    if (seconds > 600) seconds = 600;

    /* Read SPC file */
    FILE *fin = fopen(spc_path, "rb");
    if (!fin) { fprintf(stderr, "Cannot open %s\n", spc_path); return 1; }
    fseek(fin, 0, SEEK_END);
    long spc_size = ftell(fin);
    fseek(fin, 0, SEEK_SET);
    unsigned char *spc_data = (unsigned char *)malloc(spc_size);
    if (!spc_data) { fclose(fin); return 1; }
    fread(spc_data, 1, spc_size, fin);
    fclose(fin);

    /* Create emulator and load SPC */
    SNES_SPC *spc = spc_new();
    if (!spc) { free(spc_data); return 1; }

    spc_err_t err = spc_load_spc(spc, spc_data, spc_size);
    free(spc_data);
    if (err) { fprintf(stderr, "SPC load error: %s\n", err); spc_delete(spc); return 1; }
    spc_clear_echo(spc);

    /* Create filter for nicer output */
    SPC_Filter *filter = spc_filter_new();
    if (!filter) { spc_delete(spc); return 1; }
    spc_filter_clear(filter);

    /* Generate audio */
    int total_samples = spc_sample_rate * seconds * 2; /* stereo */
    short *buf = (short *)malloc(total_samples * sizeof(short));
    if (!buf) { spc_filter_delete(filter); spc_delete(spc); return 1; }

    /* Render in chunks */
    int chunk = 4096;
    int pos = 0;
    while (pos < total_samples) {
        int n = total_samples - pos;
        if (n > chunk) n = chunk;
        spc_set_output(spc, buf + pos, n);
        err = spc_play(spc, n, buf + pos);
        if (err) { fprintf(stderr, "Play error: %s\n", err); break; }
        spc_filter_run(filter, buf + pos, n);
        pos += n;
    }

    spc_filter_delete(filter);
    spc_delete(spc);

    /* Write WAV */
    FILE *fout = fopen(wav_path, "wb");
    if (!fout) { free(buf); return 1; }

    int data_size = pos * 2; /* 16-bit samples = 2 bytes each */
    int channels = 2;
    int bits = 16;
    int byte_rate = spc_sample_rate * channels * (bits / 8);
    int block_align = channels * (bits / 8);

    /* RIFF header */
    fwrite("RIFF", 1, 4, fout);
    write_le32(fout, 36 + data_size);
    fwrite("WAVE", 1, 4, fout);

    /* fmt chunk */
    fwrite("fmt ", 1, 4, fout);
    write_le32(fout, 16);
    write_le16(fout, 1); /* PCM */
    write_le16(fout, channels);
    write_le32(fout, spc_sample_rate);
    write_le32(fout, byte_rate);
    write_le16(fout, block_align);
    write_le16(fout, bits);

    /* data chunk */
    fwrite("data", 1, 4, fout);
    write_le32(fout, data_size);
    fwrite(buf, 2, pos, fout);

    fclose(fout);
    free(buf);

    fprintf(stderr, "OK: %d samples (%d seconds) -> %s\n", pos / 2, seconds, wav_path);
    return 0;
}
