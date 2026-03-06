#!/usr/bin/env python3
"""Build editor-committed expected-route assets from verified replay segments."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import numpy as np


SCRIPT_DIR = Path(__file__).resolve().parent
EDITOR_DIR = SCRIPT_DIR.parent
GAME_DIR = EDITOR_DIR.parent
REPO_ROOT = GAME_DIR.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from retro_harness.env import make_env


GAME = "SuperMetroid-Snes"
BUTTON_ORDER = ["B", "Y", "Select", "Start", "Up", "Down", "Left", "Right", "A", "X", "L", "R"]
NOOP = [0] * 12


def _trace_entry(frame: int, info: dict[str, int]) -> dict[str, int]:
    return {
        "frame": frame,
        "roomId": int(info.get("room_id", 0) or 0),
        "x": int(info.get("samus_x", 0) or 0),
        "y": int(info.get("samus_y", 0) or 0),
    }


def _apply_select_workaround(env, buttons: list[int], select_state: dict[str, int | bool | None]) -> None:
    select_pressed = len(buttons) > 2 and bool(buttons[2])
    if not select_pressed:
        select_state["prev"] = False
        return
    if bool(select_state.get("prev")):
        return
    if select_state.get("has_selected_item") is None:
        try:
            env.unwrapped.data.lookup_value("selected_item")
            select_state["has_selected_item"] = True
        except Exception:
            select_state["has_selected_item"] = False
    if not select_state.get("has_selected_item"):
        select_state["prev"] = True
        return
    select_state["value"] = 0 if int(select_state.get("value", 0) or 0) else 1
    try:
        env.unwrapped.data.set_value("selected_item", int(select_state["value"]))
    except Exception:
        pass
    select_state["prev"] = True


def build_expected_route(
    *,
    segment_files: list[Path],
    output_file: Path,
    start_state: str,
    route_id: str,
    label: str,
    target_room_id: int,
    include_room_ids: list[int] | None = None,
    stop_before_room_id: int | None = None,
    stop_on_door_transition_room_id: int | None = None,
) -> None:
    if not segment_files:
        raise RuntimeError("Need at least one segment file")

    raw_buttons: list[list[int]] = []
    metadata = {}
    for index, segment_file in enumerate(segment_files):
        segment = json.loads(segment_file.read_text(encoding="utf-8"))
        if index == 0:
            metadata = segment.get("metadata", {})
        segment_buttons = segment.get("raw_buttons", [])
        if not segment_buttons:
            raise RuntimeError(f"No raw buttons found in {segment_file}")
        raw_buttons.extend(segment_buttons)

    start_room_id = int(str(metadata.get("room_id", "0")), 0)
    allowed_room_ids = set(include_room_ids or [start_room_id])
    env = make_env(GAME, start_state, str(GAME_DIR), render_mode="rgb_array")
    env.reset()

    frame_counter = 0
    trace: list[dict[str, int]] = []
    executed_buttons: list[list[int]] = []
    _, _, terminated, truncated, info = env.step(np.array(NOOP, dtype=np.int8))
    frame_counter += 1
    if int(info.get("room_id", 0) or 0) != start_room_id:
        raise RuntimeError(
            f"Expected start room 0x{start_room_id:04X}, got 0x{int(info.get('room_id', 0) or 0):04X}"
        )
    trace.append(_trace_entry(frame_counter, info))

    select_state: dict[str, int | bool | None] = {"prev": False, "value": 0, "has_selected_item": None}
    stable_rooms_seen: set[int] = set()
    for buttons in raw_buttons:
        action = [int(value) for value in list(buttons)[:12]]
        if len(action) < 12:
            action.extend([0] * (12 - len(action)))
        _apply_select_workaround(env, action, select_state)
        _, _, terminated, truncated, info = env.step(np.array(action, dtype=np.int8))
        frame_counter += 1
        room_id = int(info.get("room_id", 0) or 0)
        door_transition = bool(info.get("door_transition", 0))

        if stop_before_room_id is not None and room_id == stop_before_room_id:
            break
        if (
            stop_on_door_transition_room_id is not None
            and room_id == stop_on_door_transition_room_id
            and door_transition
            and stop_on_door_transition_room_id in stable_rooms_seen
        ):
            break
        if room_id not in allowed_room_ids:
            break

        executed_buttons.append(action)
        if door_transition:
            continue
        stable_rooms_seen.add(room_id)
        entry = _trace_entry(frame_counter, info)
        if (
            trace[-1]["roomId"] != entry["roomId"]
            or trace[-1]["x"] != entry["x"]
            or trace[-1]["y"] != entry["y"]
        ):
            trace.append(entry)
        if terminated or truncated:
            break
    env.close()

    if len(trace) < 2:
        raise RuntimeError("Replay did not produce enough in-room trace points")
    if not executed_buttons:
        raise RuntimeError("Replay did not produce any usable autoplay buttons")

    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_text(
        json.dumps(
            {
                "routeId": route_id,
                "label": label,
                "source": "verified_segment_chain_replay" if len(segment_files) > 1 else "verified_segment_replay",
                "startState": start_state,
                "startRoomId": start_room_id,
                "targetRoomId": target_room_id,
                "segmentFile": str(segment_files[0].relative_to(REPO_ROOT)),
                "segmentFiles": [str(segment_file.relative_to(REPO_ROOT)) for segment_file in segment_files],
                "buttonOrder": BUTTON_ORDER,
                "rawButtons": executed_buttons,
                "tracePoints": trace,
                "frames": len(executed_buttons),
                "traceFrames": len(trace),
                "terminated": terminated,
                "truncated": truncated,
                "includeRoomIds": sorted(allowed_room_ids),
                "stopBeforeRoomId": stop_before_room_id,
                "stopOnDoorTransitionRoomId": stop_on_door_transition_room_id,
            },
            indent=2,
        ),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Build editor expected-route assets from replay segments")
    parser.add_argument(
        "--segment-file",
        action="append",
        dest="segment_files",
        help="Segment JSON with raw_buttons",
    )
    parser.add_argument(
        "--output",
        default=str(EDITOR_DIR / "export" / "expected_routes" / "sm_landing_site.json"),
        help="Output JSON path",
    )
    parser.add_argument("--start-state", default="ZebesStart")
    parser.add_argument("--route-id", default="sm_landing_site")
    parser.add_argument("--label", default="Landing Site Ship -> Left Door")
    parser.add_argument("--target-room-id", default="0x92FD")
    parser.add_argument(
        "--include-room-id",
        action="append",
        dest="include_room_ids",
        help="Room id to keep in the expected trace (repeatable)",
    )
    parser.add_argument("--stop-before-room-id", default=None)
    parser.add_argument("--stop-on-door-transition-room-id", default=None)
    args = parser.parse_args()

    segment_files = args.segment_files or [
        str(GAME_DIR / "optimizer" / "runs" / "sm_landing_site" / "segments" / "seg00_landing_site.json")
    ]
    build_expected_route(
        segment_files=[Path(segment_file).expanduser().resolve() for segment_file in segment_files],
        output_file=Path(args.output).expanduser().resolve(),
        start_state=args.start_state,
        route_id=args.route_id,
        label=args.label,
        target_room_id=int(str(args.target_room_id), 0),
        include_room_ids=[int(str(room_id), 0) for room_id in args.include_room_ids] if args.include_room_ids else None,
        stop_before_room_id=int(str(args.stop_before_room_id), 0) if args.stop_before_room_id else None,
        stop_on_door_transition_room_id=(
            int(str(args.stop_on_door_transition_room_id), 0)
            if args.stop_on_door_transition_room_id
            else None
        ),
    )
    print(f"Wrote expected route asset: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
