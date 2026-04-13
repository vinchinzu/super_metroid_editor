#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────
# Reproducible ROM hack: Enlarge Landing Site + dig a hole
#
# What it does:
#   1. Enlarges Landing Site (0x91F8) from 9x5 → 9x15 (10 rows taller)
#   2. Fills a floor platform in the new area
#   3. Carves a tunnel/hole in that platform
#   4. Exports patched .sfc + .ips
#   5. Launches in emulator (if available)
#
# Usage:
#   ./tools/enlarge_landing_site.sh [rom.sfc]
# ─────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."

ROM="${1:-custom_integrations/SuperMetroid-Snes/rom.sfc}"
OUTPUT_DIR="build/romhacks"
OUTPUT_BASE="$OUTPUT_DIR/landing_site_enlarged"

if [[ ! -f "$ROM" ]]; then
    echo "ROM not found: $ROM"
    echo "Usage: $0 [path/to/rom.sfc]"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

echo "=== Landing Site Enlargement ==="
echo "ROM:    $ROM"
echo "Output: ${OUTPUT_BASE}.sfc / .ips"
echo ""

# Landing Site is 9 wide x 5 tall (screens) = 45 screen area.
# SM engine max is 50 screens, so max for this room is 10x5 = 50.
# We widen by 1 screen (9→10), adding 16 block columns on the right.
#
# New area: columns 144-159 (block x), full height (80 blocks tall).
# We'll:
#   - Fill a solid floor platform across the new column at y=40 (mid-height)
#   - Carve a pit/hole below that floor for Samus to fall into
#
# This gives a playable extension: walk right into new area, fall down hole.

echo "Running enlarge command..."
./gradlew --console=plain -q :cli:runCli -Pargs="--rom $(realpath "$ROM") enlarge \
    --room 91F8 \
    --width 10 \
    --fill 144,38,16,2 \
    --fill 144,60,16,2 \
    --carve 148,40,8,20 \
    -o $(realpath -m "$OUTPUT_BASE")"

echo ""
echo "=== Done ==="
echo ""

if [[ -f "${OUTPUT_BASE}.sfc" ]]; then
    echo "Patched ROM: $(realpath ${OUTPUT_BASE}.sfc)"
    echo "IPS patch:   $(realpath ${OUTPUT_BASE}.ips)"
    echo ""
    echo "To test in emulator:"
    echo "  retroarch -L /usr/lib/libretro/snes9x_libretro.so ${OUTPUT_BASE}.sfc"
    echo ""
    echo "Or with the built-in launcher:"
    echo "  ./tools/run_libretro.sh ${OUTPUT_BASE}.sfc"
fi
