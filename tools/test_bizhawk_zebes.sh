#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Usage:
  ./test_bizhawk.sh /path/to/SuperMetroid.sfc [BizHawk/EmuHawk path]
  tools/test_bizhawk_zebes.sh /path/to/SuperMetroid.sfc [BizHawk/EmuHawk path]

What it does:
  - opens SMEDIT directly into the emulator workspace
  - forces the BizHawk backend
  - auto-starts the session with boot state `ZebesStart`
  - loads the selected Super Metroid ROM

Notes:
  - this looks for <rom dir>/editor_states/ZebesStart.state first
  - it also checks ./editor_states, repo ancestors, and SMEDIT_STATE_DIR
  - set SMEDIT_NAV_EXPORT_DIR if you want a non-default nav export
  - set SMEDIT_BIZHAWK_PORT to override the default port
  - set SMEDIT_APP_BIN to force a packaged desktop launcher binary
  - set SMEDIT_STATE_DIR to force a specific savestate directory
EOF
}

find_local_app_bin() {
  local app_root="$ROOT/desktopApp/build/compose/binaries/main/app"
  [[ -d "$app_root" ]] || return 1

  while IFS= read -r candidate; do
    if [[ -x "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done < <(
    find "$app_root" -mindepth 3 -maxdepth 5 -type f -path '*/bin/*' -print 2>/dev/null | LC_ALL=C sort
  )

  return 1
}

run_editor() {
  if [[ -n "${SMEDIT_APP_BIN:-}" ]]; then
    exec "$SMEDIT_APP_BIN"
  fi

  local local_app_bin=""
  if local_app_bin="$(find_local_app_bin)"; then
    exec "$local_app_bin"
  fi

  if command -v supermetroideditor >/dev/null 2>&1; then
    exec supermetroideditor
  fi

  if command -v mise >/dev/null 2>&1 && [[ -f "$ROOT/.mise.toml" ]]; then
    mise trust -y "$ROOT/.mise.toml" >/dev/null 2>&1 || true
    exec mise exec java@17 -- ./gradlew :desktopApp:run
  fi

  exec ./gradlew :desktopApp:run
}

normalize_dir() {
  local dir="$1"
  (cd "$dir" && pwd -P)
}

find_state_dir() {
  local boot_state="$1"
  local current=""
  local candidate=""
  local -a candidates=()

  if [[ -n "${SMEDIT_STATE_DIR:-}" ]]; then
    candidates+=("${SMEDIT_STATE_DIR}")
  fi

  candidates+=(
    "$ROM_DIR/editor_states"
    "$ROM_DIR"
    "$ROOT/editor_states"
    "$ROOT"
  )

  current="$ROOT"
  for _ in 1 2 3 4; do
    current="$(dirname "$current")"
    candidates+=("$current/editor_states" "$current")
  done

  for candidate in "${candidates[@]}"; do
    [[ -d "$candidate" ]] || continue
    if [[ -f "$candidate/$boot_state.state" ]]; then
      normalize_dir "$candidate"
      return 0
    fi
  done

  return 1
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

ROM_INPUT="${1:-${SMEDIT_ROM_PATH:-}}"
if [[ -z "$ROM_INPUT" ]]; then
  usage >&2
  exit 1
fi

if [[ ! -f "$ROM_INPUT" ]]; then
  echo "ROM not found: $ROM_INPUT" >&2
  exit 1
fi

ROM_DIR="$(cd "$(dirname "$ROM_INPUT")" && pwd -P)"
ROM_PATH="$ROM_DIR/$(basename "$ROM_INPUT")"
BOOT_STATE="${SMEDIT_BOOT_STATE:-ZebesStart}"
STATE_DIR="$(find_state_dir "$BOOT_STATE" || true)"
STATE_PATH=""

if [[ -z "$STATE_DIR" ]]; then
  echo "Expected BizHawk state not found for: $BOOT_STATE.state" >&2
  echo "Checked ROM dir, editor repo state dirs, repo ancestors, and SMEDIT_STATE_DIR." >&2
  echo "Put it in one of those places or export SMEDIT_STATE_DIR=/path/to/state-dir." >&2
  exit 1
fi

STATE_PATH="$STATE_DIR/${BOOT_STATE}.state"

NAV_EXPORT_DIR="${SMEDIT_NAV_EXPORT_DIR:-$ROOT/export/sm_nav}"
ROOM_HANDLE="${SMEDIT_ROOM_HANDLE:-landingSite}"
CONTROL_MODE="${SMEDIT_CONTROL_MODE:-manual}"
EMUHAWK_PATH="${2:-${SMEDIT_BIZHAWK_PATH:-}}"

if [[ -n "$EMUHAWK_PATH" && ! -f "$EMUHAWK_PATH" ]]; then
  echo "BizHawk/EmuHawk not found: $EMUHAWK_PATH" >&2
  exit 1
fi

export SMEDIT_EMULATOR_BACKEND="bizhawk"
export SMEDIT_OPEN_EMU="1"
export SMEDIT_AUTO_START="1"
export SMEDIT_ROM_PATH="$ROM_PATH"
export SMEDIT_BOOT_STATE="$BOOT_STATE"
export SMEDIT_NAV_EXPORT_DIR="$NAV_EXPORT_DIR"
export SMEDIT_ROOM_HANDLE="$ROOM_HANDLE"
export SMEDIT_CONTROL_MODE="$CONTROL_MODE"
export SMEDIT_STATE_DIR="$STATE_DIR"

mkdir -p "$NAV_EXPORT_DIR"

if [[ -n "$EMUHAWK_PATH" ]]; then
  export SMEDIT_BIZHAWK_PATH="$EMUHAWK_PATH"
fi

echo "[test_bizhawk_zebes] ROM:        $ROM_PATH"
echo "[test_bizhawk_zebes] Boot state: $BOOT_STATE"
echo "[test_bizhawk_zebes] State dir:  $STATE_DIR"
echo "[test_bizhawk_zebes] State file: $STATE_PATH"
echo "[test_bizhawk_zebes] Nav export: $NAV_EXPORT_DIR"
if [[ -n "${SMEDIT_BIZHAWK_PATH:-}" ]]; then
  echo "[test_bizhawk_zebes] EmuHawk:    $SMEDIT_BIZHAWK_PATH"
fi
if [[ -n "${SMEDIT_APP_BIN:-}" ]]; then
  echo "[test_bizhawk_zebes] App bin:    $SMEDIT_APP_BIN"
fi

run_editor
