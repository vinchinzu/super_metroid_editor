#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if command -v mise >/dev/null 2>&1 && [[ -f "$ROOT/.mise.toml" ]]; then
  mise trust -y "$ROOT/.mise.toml" >/dev/null 2>&1 || true
fi

if command -v python >/dev/null 2>&1; then
  PYTHON_BIN=python
elif command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN=python3
else
  echo "python is required for BizHawk bridge validation" >&2
  exit 1
fi

if command -v luac >/dev/null 2>&1; then
  LUAC_BIN=luac
elif command -v luac5.4 >/dev/null 2>&1; then
  LUAC_BIN=luac5.4
else
  echo "luac is required for BizHawk Lua syntax checks" >&2
  exit 1
fi

"$PYTHON_BIN" -m py_compile runtime/sm_bridge/*.py
"$LUAC_BIN" -p runtime/bizhawk/json.lua
"$LUAC_BIN" -p runtime/bizhawk/bridge.lua

if command -v mise >/dev/null 2>&1 && mise ls java 2>/dev/null | grep -q '^java  \+17'; then
  mise exec java@17 -- ./gradlew :shared:jvmTest :desktopApp:jvmTest
else
  ./gradlew :shared:jvmTest :desktopApp:jvmTest
fi
