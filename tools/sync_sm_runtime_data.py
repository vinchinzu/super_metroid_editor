#!/usr/bin/env python3
"""Sync Super Metroid runtime data into the editor repo."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


GAME = "SuperMetroid-Snes"
IGNORED_STATE_NAMES = {
    "EditorQuickSave.state",
    "EditorCheckpoint01.state",
    "EditorCheckpoint02.state",
    "EditorCheckpoint03.state",
    "EditorCheckpoint04.state",
}


def _ignore(_src: str, names: list[str]) -> set[str]:
    ignored = {"__pycache__"}
    ignored.update(name for name in names if name in IGNORED_STATE_NAMES)
    return ignored


def sync_runtime_data(*, editor_dir: Path, source_game_dir: Path) -> None:
    source_integrations = source_game_dir / "custom_integrations" / GAME
    target_integrations = editor_dir / "custom_integrations" / GAME
    if not source_integrations.is_dir():
        raise RuntimeError(f"Missing source integrations: {source_integrations}")

    target_integrations.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source_integrations, target_integrations, dirs_exist_ok=True, symlinks=False, ignore=_ignore)


def main() -> int:
    script_dir = Path(__file__).resolve().parent
    editor_dir = script_dir.parent
    default_source_game_dir = editor_dir.parent

    parser = argparse.ArgumentParser(description="Sync Super Metroid editor runtime data")
    parser.add_argument("--source-game-dir", default=str(default_source_game_dir))
    args = parser.parse_args()

    sync_runtime_data(
        editor_dir=editor_dir,
        source_game_dir=Path(args.source_game_dir).expanduser().resolve(),
    )
    print(f"Synced runtime data into {editor_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
