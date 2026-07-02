#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
editor_dir="$(cd "$script_dir/.." && pwd)"
state_dir="$editor_dir/custom_integrations/SuperMetroid-Snes"

rom_path="${SMEDIT_ROM_PATH:-$state_dir/rom.sfc}"
boot_state="${SMEDIT_BOOT_STATE:-Climb [from Pit Room]}"
auto_record="${SMEDIT_AUTO_RECORD:-1}"
dry_run=0

usage() {
  cat <<'EOF'
Usage: scripts/practice_climb_return_editor.sh [options]

Launch the desktop editor's libretro emulator at the Climb return start state
and start an editor attempt recording.

Options:
  --state NAME        Boot a different project-local .state name
  --ret00            Boot ret00_0x96BA instead of Climb [from Pit Room]
  --no-auto-record   Start the emulator without immediately recording
  --dry-run          Validate paths and print launch settings, then exit
  -h, --help         Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --state)
      if [[ $# -lt 2 ]]; then
        printf 'Missing value for --state\n' >&2
        exit 1
      fi
      boot_state="$2"
      shift 2
      ;;
    --ret00)
      boot_state="ret00_0x96BA"
      shift
      ;;
    --no-auto-record)
      auto_record=0
      shift
      ;;
    --dry-run)
      dry_run=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ ! -f "$rom_path" ]]; then
  printf 'Missing ROM: %s\n' "$rom_path" >&2
  exit 1
fi

if [[ ! -f "$state_dir/$boot_state.state" ]]; then
  printf 'Missing boot state: %s\n' "$state_dir/$boot_state.state" >&2
  exit 1
fi

printf 'Launching editor climb practice\n'
printf '  ROM        : %s\n' "$rom_path"
printf '  Boot state : %s\n' "$boot_state"
printf '  Auto record: %s\n' "$auto_record"
printf '  Recordings : %s/recordings\n' "$state_dir"
printf '\n'
printf 'Use the emulator window controls to stop recording. Saved attempts are .json + .smreplay bundles.\n'

if [[ "$dry_run" == "1" ]]; then
  exit 0
fi

if [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
  printf 'No graphical display found. Run this from your desktop session, or use --dry-run for validation.\n' >&2
  exit 1
fi

cd "$editor_dir"
SMEDIT_ROM_PATH="$rom_path" \
SMEDIT_BOOT_STATE="$boot_state" \
SMEDIT_OPEN_EMU=1 \
SMEDIT_AUTO_START=1 \
SMEDIT_AUTO_RECORD="$auto_record" \
./gradlew :desktopApp:run
