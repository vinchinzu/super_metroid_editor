"""Shared runtime helpers for the SM editor bridge."""

from __future__ import annotations

import base64
from collections import deque
from dataclasses import dataclass
import json
from pathlib import Path
from typing import Any

from editor_pathing import EditorRoutePlan, RouteMetrics, RouteTracker


TRACE_LIMIT = 4096
NOOP = [0] * 12
BUTTON_ORDER = ["B", "Y", "Select", "Start", "Up", "Down", "Left", "Right", "A", "X", "L", "R"]


def _room_name(room_lookup: dict[int, tuple[str, str]], room_id: int | None) -> str | None:
    if room_id is None:
        return None
    return room_lookup.get(room_id, (f"0x{room_id:04X}", "?"))[0]


def _area_name(room_lookup: dict[int, tuple[str, str]], room_id: int | None) -> str | None:
    if room_id is None:
        return None
    return room_lookup.get(room_id, (f"0x{room_id:04X}", "?"))[1]


@dataclass
class ResolvedActionInfo:
    requested: list[int]
    pre_sanitize: list[int]
    applied: list[int]
    source: str
    model_action_index: int | None = None


class SeedRouteDriver:
    def __init__(self, buttons: list[list[int]]) -> None:
        self._buttons = [list(frame[:12]) + [0] * max(0, 12 - len(frame)) for frame in buttons]
        self._index = 0

    def reset(self) -> None:
        self._index = 0

    def next_action(self) -> tuple[list[int], int]:
        index = self._index
        self._index += 1
        if index < len(self._buttons):
            return list(self._buttons[index]), index
        return list(NOOP), index


class SB3ActionDriver:
    def __init__(self, model_path: Path) -> None:
        from stable_baselines3 import PPO
        from action_space import DISCRETE_ACTIONS

        self._action_map = DISCRETE_ACTIONS
        self._model = PPO.load(str(model_path), device="cpu")
        self._frames = None

    def reset(self, obs: Any | None) -> None:
        if obs is None:
            self._frames = None
            return
        import numpy as np

        frame = self._obs_to_frame(obs)
        self._frames = np.concatenate([frame] * 4, axis=0)

    def next_action(self, obs: Any) -> tuple[list[int], int]:
        import numpy as np

        frame = self._obs_to_frame(obs)
        if self._frames is None:
            self._frames = np.concatenate([frame] * 4, axis=0)
        else:
            self._frames = np.roll(self._frames, shift=-3, axis=0)
            self._frames[-3:] = frame
        action_idx, _ = self._model.predict(self._frames, deterministic=True)
        idx = int(action_idx)
        buttons = [0] * 12
        for button_idx, pressed in self._action_map[idx].items():
            buttons[button_idx] = int(pressed)
        return buttons, idx

    @staticmethod
    def _obs_to_frame(obs: Any) -> Any:
        return obs[::2, ::2, :].transpose(2, 0, 1)


@dataclass
class Session:
    env: Any
    current_state: str
    frame_counter: int = 0
    recording_path: Path | None = None
    control_mode: str = "manual"
    selected_model: str | None = None
    route_plan: EditorRoutePlan | None = None

    def __post_init__(self) -> None:
        self.trace: deque[dict[str, int]] = deque(maxlen=TRACE_LIMIT)
        self.last_obs = None
        self.last_info: dict[str, Any] = {}
        self.last_action: list[int] = list(NOOP)
        self.last_requested_action: list[int] = list(NOOP)
        self.last_action_pre_sanitize: list[int] = list(NOOP)
        self.last_action_source: str = "manual"
        self.last_model_action_index: int | None = None
        self.record_file = None
        self.record_summary_path: Path | None = None
        self.record_raw_path: Path | None = None
        self.pending_trace_point: dict[str, int] | None = None
        self.select_prev = False
        self.select_val = 0
        self.has_selected_item: bool | None = None
        self.route_tracker: RouteTracker | None = None
        self.route_metrics: RouteMetrics | None = None
        self.seed_driver: SeedRouteDriver | None = None
        self.model_driver: SB3ActionDriver | None = None
        self.recorded_requested_buttons: list[list[int]] = []
        self.recorded_raw_buttons: list[list[int]] = []
        self.recorded_raw_buttons_pre_sanitize: list[list[int]] = []
        self.recorded_action_sources: list[str] = []
        self.recorded_model_action_indices: list[int | None] = []

    @property
    def recording(self) -> bool:
        return self.record_file is not None and self.recording_path is not None

    @property
    def expected_trace(self) -> list[dict[str, int]]:
        if self.route_plan is None:
            return []
        return self.route_plan.expected_trace()

    def close(self) -> None:
        if self.record_file is not None:
            self.record_file.close()
            self.record_file = None
        self.env.close()

    def append_trace(self, info: dict[str, Any]) -> None:
        room_id = int(info.get("room_id", 0) or 0)
        x = int(info.get("samus_x", 0) or 0)
        y = int(info.get("samus_y", 0) or 0)
        point = {"frame": self.frame_counter, "roomId": room_id, "x": x, "y": y}
        if bool(info.get("door_transition", 0)):
            self.pending_trace_point = point
            return

        last_point = self.trace[-1] if self.trace else None
        if self.pending_trace_point is not None:
            pending = self.pending_trace_point
            if pending["roomId"] != room_id:
                self.pending_trace_point = point
                return
            if pending["x"] == x and pending["y"] == y:
                return
            self.pending_trace_point = None
        elif last_point is not None and last_point["roomId"] != room_id:
            self.pending_trace_point = point
            return

        if last_point is not None and last_point["roomId"] == room_id and last_point["x"] == x and last_point["y"] == y:
            return
        self.trace.append(point)

    def update_route_metrics(self, info: dict[str, Any]) -> RouteMetrics | None:
        if self.route_tracker is None:
            return None
        if bool(info.get("door_transition", 0)):
            return self.route_metrics
        room_id = int(info.get("room_id", 0) or 0)
        if self.route_plan is None or not self.route_tracker.supports_room(room_id):
            return self.route_metrics
        x = int(info.get("samus_x", 0) or 0)
        y = int(info.get("samus_y", 0) or 0)
        self.route_metrics = self.route_tracker.update(room_id, x, y)
        return self.route_metrics

    def write_record(
        self,
        resolved: ResolvedActionInfo,
        terminated: bool,
        truncated: bool,
    ) -> None:
        if self.record_file is None:
            return
        route_metrics = self.route_metrics.to_dict() if self.route_metrics is not None else None
        entry = {
            "frame": self.frame_counter,
            "requested_buttons": resolved.requested,
            "raw_buttons_pre_sanitize": resolved.pre_sanitize,
            "raw_buttons": resolved.applied,
            "action_source": resolved.source,
            "model_action_index": resolved.model_action_index,
            "room_id": self.last_info.get("room_id"),
            "samus_x": self.last_info.get("samus_x"),
            "samus_y": self.last_info.get("samus_y"),
            "health": self.last_info.get("health"),
            "game_state": self.last_info.get("game_state"),
            "route_id": self.route_plan.route_id if self.route_plan is not None else None,
            "route_metrics": route_metrics,
            "terminated": terminated,
            "truncated": truncated,
        }
        self.recorded_requested_buttons.append(list(resolved.requested))
        self.recorded_raw_buttons.append(list(resolved.applied))
        self.recorded_raw_buttons_pre_sanitize.append(list(resolved.pre_sanitize))
        self.recorded_action_sources.append(resolved.source)
        self.recorded_model_action_indices.append(resolved.model_action_index)
        self.record_file.write(json.dumps(entry))
        self.record_file.write("\n")
        self.record_file.flush()

    def build_snapshot(
        self,
        room_lookup: dict[int, tuple[str, str]],
        *,
        include_frame: bool = True,
        include_trace: bool = True,
        terminated: bool = False,
        truncated: bool = False,
        controller_connected: bool = False,
        controller_name: str | None = None,
    ) -> dict[str, Any]:
        if self.last_obs is None:
            return {
                "frameCounter": self.frame_counter,
                "traceIncluded": include_trace,
                "trace": list(self.trace) if include_trace else [],
            }
        obs = self.last_obs
        height, width = int(obs.shape[0]), int(obs.shape[1])
        room_id = self.last_info.get("room_id")
        snapshot = {
            "frameCounter": self.frame_counter,
            "roomId": room_id,
            "roomName": _room_name(room_lookup, room_id),
            "areaName": _area_name(room_lookup, room_id),
            "gameState": self.last_info.get("game_state"),
            "health": self.last_info.get("health"),
            "samusX": self.last_info.get("samus_x"),
            "samusY": self.last_info.get("samus_y"),
            "doorTransition": bool(self.last_info.get("door_transition", 0)),
            "terminated": terminated,
            "truncated": truncated,
            "frameWidth": width,
            "frameHeight": height,
            "traceIncluded": include_trace,
            "controllerConnected": controller_connected,
            "controllerName": controller_name,
            "lastAction": list(self.last_action),
            "lastRequestedAction": list(self.last_requested_action),
            "lastActionPreSanitize": list(self.last_action_pre_sanitize),
            "lastActionSource": self.last_action_source,
            "lastModelActionIndex": self.last_model_action_index,
            "expectedTraceLabel": self.route_plan.label if self.route_plan is not None else None,
            "expectedTraceSource": self.route_plan.source if self.route_plan is not None else None,
            "pathProgress": float(self.route_metrics.progress) if self.route_metrics is not None else 0.0,
            "pathProgressMax": float(self.route_metrics.max_progress) if self.route_metrics is not None else 0.0,
            "pathCompletion": float(self.route_metrics.completion) if self.route_metrics is not None else 0.0,
            "pathErrorPx": float(self.route_metrics.error_px) if self.route_metrics is not None else None,
            "pathBestErrorPx": float(self.route_metrics.best_error_px) if self.route_metrics is not None else None,
            "routeCompleted": bool(self.route_metrics.route_completed) if self.route_metrics is not None else False,
            "recordedFrames": len(self.recorded_raw_buttons),
            "expectedTrace": list(self.expected_trace) if include_trace else [],
            "trace": list(self.trace) if include_trace else [],
        }
        if include_frame:
            snapshot["frameRgb24Base64"] = base64.b64encode(obs.tobytes()).decode("ascii")
        return snapshot
