#!/usr/bin/env bash
# Launch the editor with the libretro backend and auto-start a session.
#
# Usage:
#   ./tools/run_libretro.sh                    # use default ROM path
#   ./tools/run_libretro.sh /path/to/rom.sfc   # explicit ROM
#
set -euo pipefail
cd "$(dirname "$0")/.."

ROM="${1:-custom_integrations/SuperMetroid-Snes/rom.sfc}"

if [[ ! -f "$ROM" ]]; then
    echo "ROM not found: $ROM"
    echo "Usage: $0 [path/to/rom.sfc]"
    exit 1
fi

# Check for libretro core
CORE=$(find /usr/lib/libretro ~/.config/retroarch/cores ./cores 2>/dev/null \
       -name 'snes9x_libretro.so' -o -name 'bsnes_libretro.so' 2>/dev/null | head -1)

if [[ -z "${SMEDIT_LIBRETRO_CORE:-}" && -z "$CORE" ]]; then
    echo "No SNES libretro core found."
    echo "Install one:  sudo pacman -S libretro-snes9x"
    echo "Or set:       export SMEDIT_LIBRETRO_CORE=/path/to/snes9x_libretro.so"
    exit 1
fi

echo "=== Launching SMEDIT with libretro backend ==="
echo "ROM:  $ROM"
echo "Core: ${SMEDIT_LIBRETRO_CORE:-$CORE}"
echo ""

export SMEDIT_EMULATOR_BACKEND=libretro
export SMEDIT_ROM_PATH="$ROM"
export SMEDIT_OPEN_EMU=1
export SMEDIT_AUTO_START=1

exec ./gradlew :desktopApp:run
