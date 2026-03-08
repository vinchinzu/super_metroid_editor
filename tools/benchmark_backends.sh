#!/usr/bin/env bash
# Benchmark emulator backends: libretro vs stable-retro (gym-retro).
#
# Usage:
#   ./tools/benchmark_backends.sh                           # both backends, 600 frames
#   ./tools/benchmark_backends.sh --libretro-only           # libretro only
#   ./tools/benchmark_backends.sh --gym-retro-only          # gym-retro only
#   ./tools/benchmark_backends.sh --frames 1200             # custom frame count
#   SMEDIT_ROM_PATH=/path/to/rom.sfc ./tools/benchmark_backends.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

# Defaults
BACKENDS="libretro,gym-retro"
FRAMES="${BENCH_FRAMES:-600}"
WARMUP="${BENCH_WARMUP_FRAMES:-60}"

# Parse args
while [[ $# -gt 0 ]]; do
    case "$1" in
        --libretro-only)  BACKENDS="libretro"; shift ;;
        --gym-retro-only) BACKENDS="gym-retro"; shift ;;
        --frames)         FRAMES="$2"; shift 2 ;;
        --warmup)         WARMUP="$2"; shift 2 ;;
        *)
            if [[ -f "$1" ]]; then
                export SMEDIT_ROM_PATH="$1"
            else
                echo "Unknown arg: $1"
                exit 1
            fi
            shift ;;
    esac
done

# Default ROM
export SMEDIT_ROM_PATH="${SMEDIT_ROM_PATH:-custom_integrations/SuperMetroid-Snes/rom.sfc}"

if [[ ! -f "$SMEDIT_ROM_PATH" ]]; then
    echo "ROM not found: $SMEDIT_ROM_PATH"
    echo "Set SMEDIT_ROM_PATH or pass as argument"
    exit 1
fi

# Check libretro core if benchmarking it
if [[ "$BACKENDS" == *"libretro"* ]]; then
    CORE=$(find /usr/lib/libretro ~/.config/retroarch/cores ./cores 2>/dev/null \
           -name 'snes9x_libretro.so' -o -name 'bsnes_libretro.so' 2>/dev/null | head -1)
    if [[ -z "${SMEDIT_LIBRETRO_CORE:-}" && -z "${CORE:-}" ]]; then
        echo "WARNING: No SNES libretro core found. libretro benchmark will fail."
        echo "Install:  sudo pacman -S libretro-snes9x"
        echo ""
    fi
fi

# Check gym-retro python env if benchmarking it
if [[ "$BACKENDS" == *"gym-retro"* ]]; then
    VENV=""
    DIR="$PWD"
    for i in $(seq 1 6); do
        if [[ -f "$DIR/.venv/bin/python" ]]; then
            VENV="$DIR/.venv/bin/python"
            break
        fi
        DIR="$(dirname "$DIR")"
    done
    if [[ -z "$VENV" ]]; then
        echo "WARNING: No .venv/bin/python found. gym-retro benchmark may fail."
        echo ""
    fi
fi

export BENCH_BACKENDS="$BACKENDS"
export BENCH_FRAMES="$FRAMES"
export BENCH_WARMUP_FRAMES="$WARMUP"

echo "Starting benchmark..."
echo ""

exec ./gradlew -q :desktopApp:benchmark
