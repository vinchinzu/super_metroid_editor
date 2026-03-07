#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

resolve_python() {
  if [[ -n "${PYTHON:-}" ]] && command -v "${PYTHON}" >/dev/null 2>&1; then
    command -v "${PYTHON}"
    return 0
  fi
  local candidates=(
    "$ROOT/../.venv/bin/python"
    "$ROOT/.venv/bin/python"
  )
  local candidate=""
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  if command -v python >/dev/null 2>&1; then
    command -v python
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    command -v python3
    return 0
  fi
  return 1
}

PYTHON_BIN="$(resolve_python)" || {
  echo "python is required for stable-retro bridge validation" >&2
  exit 1
}

"$PYTHON_BIN" -m py_compile \
  runtime/sm_bridge/*.py \
  tools/sm_bridge_smoke.py \
  tools/sync_sm_runtime_data.py

SOURCE_GAME_DIR="${SM_SOURCE_GAME_DIR:-$ROOT/..}"
if [[ -d "$SOURCE_GAME_DIR/custom_integrations/SuperMetroid-Snes" ]]; then
  "$PYTHON_BIN" tools/sync_sm_runtime_data.py --source-game-dir "$SOURCE_GAME_DIR" >/dev/null
fi

"$PYTHON_BIN" - <<'PY'
import importlib.util
import sys

required = ["stable_retro", "pygame", "numpy"]
missing = [name for name in required if importlib.util.find_spec(name) is None]
if missing:
    print("Missing Python deps: " + ", ".join(missing), file=sys.stderr)
    raise SystemExit(1)
PY

SMOKE_ARGS=()
if [[ "${SM_BRIDGE_SKIP_SM_BUILD:-0}" == "1" ]]; then
  SMOKE_ARGS+=("--skip-sm-build")
fi
if [[ -n "${SM_BRIDGE_STATE:-}" ]]; then
  SMOKE_ARGS+=("--state" "${SM_BRIDGE_STATE}")
fi

"$PYTHON_BIN" tools/sm_bridge_smoke.py --player-count 2 "${SMOKE_ARGS[@]}"
