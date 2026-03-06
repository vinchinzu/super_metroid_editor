#!/usr/bin/env python3
"""Small stdio bridge between SMEDIT and the Python stable-retro runtime.

This is intentionally narrow:
- discover published save states and PPO checkpoints
- start/close a single live emulator session
- snapshot or step the session while returning raw RGB frames
- save/load states inside custom_integrations
- record a light JSONL trace for editor sessions

The bridge speaks one JSON object per line on stdin/stdout.
"""

from __future__ import annotations

import gzip
import json
import os
from pathlib import Path
import time
from typing import Any

import sys

SCRIPT_DIR = Path(__file__).resolve().parent
RUNTIME_DIR = SCRIPT_DIR
EDITOR_DIR = RUNTIME_DIR.parent.parent

from editor_pathing import EditorRoutePlan, RouteTracker, load_route_plan
from editor_runtime import BUTTON_ORDER, NOOP, ResolvedActionInfo, SB3ActionDriver, SeedRouteDriver, Session

os.environ.setdefault("SDL_VIDEODRIVER", "dummy")
os.environ.setdefault("SDL_AUDIODRIVER", "dummy")
os.environ.setdefault("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", "1")

GAME = "SuperMetroid-Snes"
CONTROLLER_RESCAN_SECONDS = 1.0
DEFAULT_NAV_EXPORT_DIR = EDITOR_DIR / "export" / "sm_nav"


def _json_response(
    *,
    request_id: str | None,
    ok: bool = True,
    error: str | None = None,
    message: str | None = None,
    **extra: Any,
) -> dict[str, Any]:
    payload: dict[str, Any] = {"id": request_id, "ok": ok}
    if error:
        payload["error"] = error
    if message:
        payload["message"] = message
    payload.update(extra)
    return payload


def _write(payload: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(payload))
    sys.stdout.write("\n")
    sys.stdout.flush()


def _published_states() -> list[dict[str, str]]:
    states_dir = EDITOR_DIR / "custom_integrations" / GAME
    return sorted(
        [
            {"name": path.stem, "path": str(path)}
            for path in states_dir.glob("*.state")
        ],
        key=lambda entry: entry["name"].lower(),
    )


def _published_models() -> list[dict[str, str]]:
    models_dir = EDITOR_DIR / "models"
    models: list[dict[str, str]] = []
    if not models_dir.exists():
        return models
    for suffix, fmt in (("*.zip", "sb3_zip"), ("*.pth", "torch_pth")):
        for path in models_dir.glob(suffix):
            models.append({"name": path.name, "path": str(path), "format": fmt})
    return sorted(models, key=lambda entry: entry["name"].lower())


def _room_lookup(nav_export_dir: Path | None) -> dict[int, tuple[str, str]]:
    if nav_export_dir is None:
        return {}
    nav_graph = nav_export_dir / "nav_graph.json"
    if not nav_graph.exists():
        return {}
    try:
        data = json.loads(nav_graph.read_text())
    except Exception:
        return {}
    result: dict[int, tuple[str, str]] = {}
    for node in data.get("nodes", []):
        room_id = int(node.get("roomId", 0))
        result[room_id] = (str(node.get("name", f"0x{room_id:04X}")), str(node.get("areaName", "?")))
    return result


def _runtime_imports():
    import numpy as np
    from retro_env import make_env, save_state

    return np, make_env, save_state


def _input_imports():
    import pygame
    from controls import controller_action, init_controller, sanitize_action

    return pygame, controller_action, init_controller, sanitize_action


def _load_expected_route(
    *,
    nav_export_dir: Path,
    state_name: str,
    room_id: int | None = None,
) -> EditorRoutePlan | None:
    return load_route_plan(
        nav_export_dir=nav_export_dir,
        state_name=state_name,
        room_id=room_id,
    )


class BridgeRuntime:
    def __init__(self) -> None:
        self.session: Session | None = None
        self.nav_export_dir = DEFAULT_NAV_EXPORT_DIR
        self.room_lookup = _room_lookup(self.nav_export_dir)
        self.control_mode = "manual"
        self.selected_model: str | None = None
        self._pygame = None
        self._controller = None
        self._controller_action = None
        self._init_controller = None
        self._sanitize_action = None
        self._controller_name: str | None = None
        self._last_controller_scan_at = 0.0

    def _apply_config(
        self,
        *,
        nav_export_dir: str | None = None,
        control_mode: str | None = None,
        selected_model: str | None = None,
    ) -> None:
        if nav_export_dir is not None:
            nav_dir = Path(nav_export_dir).expanduser()
            self.nav_export_dir = nav_dir
            self.room_lookup = _room_lookup(nav_dir)
        if control_mode:
            self.control_mode = control_mode
        if selected_model is not None:
            self.selected_model = selected_model or None
        if self.session is not None:
            self.session.control_mode = self.control_mode
            self.session.selected_model = self.selected_model
            self._prepare_session_plan(
                self.session,
                room_id=int(self.session.last_info.get("room_id", 0) or 0) if self.session.last_info else None,
            )
            self._prepare_agent_driver(self.session)

    def hello(self) -> dict[str, Any]:
        return {
            "capabilities": {
                "game": GAME,
                "gameDir": str(EDITOR_DIR),
                "bridgeVersion": "0.1.0",
                "supportsFrames": True,
                "supportsRecording": True,
                "supportsKeyboardInput": True,
                "supportsControllerInput": True,
                "supportsAgentControl": True,
                "supportsHotConfig": True,
            },
            "message": "SM bridge ready",
        }

    def _ensure_input_runtime(self) -> None:
        if self._pygame is not None:
            return
        try:
            pygame, controller_action, init_controller, sanitize_action = _input_imports()
            pygame.init()
        except Exception:
            return
        self._pygame = pygame
        self._controller_action = controller_action
        self._init_controller = init_controller
        self._sanitize_action = sanitize_action
        self._refresh_controller(force=True)

    def _refresh_controller(self, *, force: bool = False) -> None:
        if self._pygame is None or self._init_controller is None:
            return
        now = time.time()
        if not force and now - self._last_controller_scan_at < CONTROLLER_RESCAN_SECONDS:
            return
        self._last_controller_scan_at = now
        try:
            self._pygame.event.pump()
        except Exception:
            pass
        controller = self._init_controller(self._pygame)
        self._controller = controller
        self._controller_name = controller.get_name() if controller is not None else None

    def _controller_status(self) -> tuple[bool, str | None]:
        self._ensure_input_runtime()
        self._refresh_controller()
        return self._controller is not None, self._controller_name

    def _prepare_session_plan(self, session: Session, *, room_id: int | None = None) -> None:
        route_plan = _load_expected_route(
            nav_export_dir=self.nav_export_dir,
            state_name=session.current_state,
            room_id=room_id,
        )
        session.route_plan = route_plan
        session.route_tracker = RouteTracker(route_plan.trace_points) if route_plan and len(route_plan.trace_points) >= 2 else None
        session.route_metrics = None
        session.seed_driver = SeedRouteDriver(route_plan.autoplay_buttons) if route_plan and route_plan.autoplay_buttons else None

    def _load_model_driver(self, selected_model: str | None) -> SB3ActionDriver | None:
        if not selected_model or not selected_model.endswith(".zip"):
            return None
        model_path = EDITOR_DIR / "models" / selected_model
        if not model_path.is_file():
            return None
        try:
            return SB3ActionDriver(model_path)
        except Exception:
            return None

    def _prepare_agent_driver(self, session: Session) -> None:
        session.model_driver = self._load_model_driver(session.selected_model)
        if session.model_driver is not None:
            try:
                session.model_driver.reset(session.last_obs)
            except Exception:
                session.model_driver = None
        if session.seed_driver is not None:
            session.seed_driver.reset()

    def _prime_select_workaround(self, session: Session) -> None:
        if session.has_selected_item is not None:
            return
        try:
            session.select_val = int(session.env.unwrapped.data.lookup_value("selected_item"))
            session.has_selected_item = True
        except Exception:
            session.select_val = 0
            session.has_selected_item = False

    def _apply_select_workaround(self, session: Session, action: list[int]) -> None:
        select_pressed = len(action) > 2 and bool(action[2])
        if not select_pressed:
            session.select_prev = False
            return
        if session.select_prev:
            return

        self._prime_select_workaround(session)
        if not session.has_selected_item:
            session.select_prev = True
            return

        try:
            current_value = int(session.env.unwrapped.data.lookup_value("selected_item"))
        except Exception:
            current_value = int(session.select_val)
        session.select_val = 0 if current_value else 1
        try:
            session.env.unwrapped.data.set_value("selected_item", session.select_val)
        except Exception:
            pass
        session.select_prev = True

    def _resolve_action(self, session: Session, action: list[int]) -> ResolvedActionInfo:
        requested = [int(v) for v in action[:12]]
        if len(requested) < 12:
            requested.extend([0] * (12 - len(requested)))

        if self.control_mode == "watch":
            return ResolvedActionInfo(
                requested=requested,
                pre_sanitize=list(NOOP),
                applied=list(NOOP),
                source="watch",
            )

        if self.control_mode == "agent":
            if session.model_driver is not None and session.last_obs is not None:
                try:
                    predicted, action_index = session.model_driver.next_action(session.last_obs)
                    applied = list(predicted)
                    if self._sanitize_action is not None:
                        self._sanitize_action(applied)
                    return ResolvedActionInfo(
                        requested=list(predicted),
                        pre_sanitize=list(predicted),
                        applied=applied,
                        source="agent_model",
                        model_action_index=action_index,
                    )
                except Exception:
                    session.model_driver = None
            if session.seed_driver is not None:
                predicted, action_index = session.seed_driver.next_action()
                applied = list(predicted)
                if self._sanitize_action is not None:
                    self._sanitize_action(applied)
                return ResolvedActionInfo(
                    requested=list(predicted),
                    pre_sanitize=list(predicted),
                    applied=applied,
                    source="agent_seed_route",
                    model_action_index=action_index,
                )
            return ResolvedActionInfo(
                requested=requested,
                pre_sanitize=list(NOOP),
                applied=list(NOOP),
                source="agent_noop",
            )

        resolved = list(requested)
        self._ensure_input_runtime()
        if self.control_mode == "manual" and self._pygame is not None:
            try:
                self._pygame.event.pump()
            except Exception:
                pass
            self._refresh_controller()
            if self._controller is not None and self._controller_action is not None:
                try:
                    self._controller_action(self._controller, resolved)
                except Exception:
                    self._controller = None
                    self._controller_name = None
        pre_sanitize = list(resolved)
        if self._sanitize_action is not None:
            self._sanitize_action(resolved)
        return ResolvedActionInfo(
            requested=requested,
            pre_sanitize=pre_sanitize,
            applied=resolved,
            source="manual",
        )

    def configure(
        self,
        *,
        nav_export_dir: str | None = None,
        control_mode: str | None = None,
        selected_model: str | None = None,
    ) -> dict[str, Any]:
        self._apply_config(
            nav_export_dir=nav_export_dir,
            control_mode=control_mode,
            selected_model=selected_model,
        )
        return {
            "session": self.session_state(),
            "message": "Bridge configuration updated",
        }

    def discover(self) -> dict[str, Any]:
        return {
            "states": _published_states(),
            "models": _published_models(),
            "session": self.session_state(),
            "message": "Discovered save states and models",
        }

    def session_state(self) -> dict[str, Any]:
        if self.session is None:
            return {
                "active": False,
                "paused": True,
                "currentState": None,
                "frameCounter": 0,
                "recording": False,
                "controlMode": self.control_mode,
                "selectedModel": self.selected_model,
            }
        return {
            "active": True,
            "paused": False,
            "currentState": self.session.current_state,
            "frameCounter": self.session.frame_counter,
            "recording": self.session.recording,
            "controlMode": self.session.control_mode,
            "selectedModel": self.session.selected_model,
        }

    def start_session(
        self,
        state_name: str,
        *,
        nav_export_dir: str | None = None,
        control_mode: str | None = None,
        selected_model: str | None = None,
        include_frame: bool = True,
        include_trace: bool = True,
    ) -> dict[str, Any]:
        np, make_env, _ = _runtime_imports()
        self._ensure_input_runtime()
        self._apply_config(
            nav_export_dir=nav_export_dir,
            control_mode=control_mode,
            selected_model=selected_model,
        )
        if self.session is not None:
            if self.session.recording:
                self.stop_recording()
            self.session.close()
            self.session = None
        env = make_env(GAME, state_name, EDITOR_DIR, render_mode="rgb_array")
        env.reset()
        obs, _, terminated, truncated, info = env.step(np.array(NOOP, dtype=np.int8))
        self.session = Session(
            env=env,
            current_state=state_name,
            frame_counter=1,
            control_mode=self.control_mode,
            selected_model=self.selected_model,
        )
        self.session.last_obs = obs
        self.session.last_info = info
        self.session.last_action = list(NOOP)
        self.session.last_requested_action = list(NOOP)
        self.session.last_action_pre_sanitize = list(NOOP)
        self.session.last_action_source = "boot"
        self.session.last_model_action_index = None
        self.session.select_prev = False
        self.session.has_selected_item = None
        self._prepare_session_plan(self.session, room_id=int(info.get("room_id", 0) or 0))
        self._prepare_agent_driver(self.session)
        self.session.append_trace(info)
        self.session.update_route_metrics(info)
        self._prime_select_workaround(self.session)
        controller_connected, controller_name = self._controller_status()
        return {
            "session": self.session_state(),
            "snapshot": self.session.build_snapshot(
                self.room_lookup,
                include_frame=include_frame,
                include_trace=include_trace,
                terminated=terminated,
                truncated=truncated,
                controller_connected=controller_connected,
                controller_name=controller_name,
            ),
            "message": f"Session started from {state_name}",
        }

    def require_session(self) -> Session:
        if self.session is None:
            raise RuntimeError("No active session")
        return self.session

    def snapshot(self, *, include_frame: bool = True, include_trace: bool = True) -> dict[str, Any]:
        session = self.require_session()
        controller_connected, controller_name = self._controller_status()
        return {
            "session": self.session_state(),
            "snapshot": session.build_snapshot(
                self.room_lookup,
                include_frame=include_frame,
                include_trace=include_trace,
                controller_connected=controller_connected,
                controller_name=controller_name,
            ),
            "message": "Snapshot refreshed",
        }

    def step(
        self,
        action: list[int],
        repeat: int,
        *,
        include_frame: bool = True,
        include_trace: bool = True,
    ) -> dict[str, Any]:
        np, _, _ = _runtime_imports()
        session = self.require_session()
        repeat = max(1, min(int(repeat or 1), 8))
        shared_resolved = None if self.control_mode == "agent" else self._resolve_action(session, action)
        terminated = False
        truncated = False
        for _ in range(repeat):
            resolved = shared_resolved or self._resolve_action(session, action)
            session.last_requested_action = list(resolved.requested)
            session.last_action_pre_sanitize = list(resolved.pre_sanitize)
            session.last_action = list(resolved.applied)
            session.last_action_source = resolved.source
            session.last_model_action_index = resolved.model_action_index
            self._apply_select_workaround(session, resolved.applied)
            obs, _, terminated, truncated, info = session.env.step(np.array(resolved.applied, dtype=np.int8))
            session.frame_counter += 1
            session.last_obs = obs
            session.last_info = info
            session.append_trace(info)
            session.update_route_metrics(info)
            session.write_record(resolved, terminated, truncated)
            if terminated or truncated:
                break
        controller_connected, controller_name = self._controller_status()
        return {
            "session": self.session_state(),
            "snapshot": session.build_snapshot(
                self.room_lookup,
                include_frame=include_frame,
                include_trace=include_trace,
                terminated=terminated,
                truncated=truncated,
                controller_connected=controller_connected,
                controller_name=controller_name,
            ),
        }

    def save_state(self, save_name: str) -> dict[str, Any]:
        _, _, save_state = _runtime_imports()
        session = self.require_session()
        if not save_name:
            raise RuntimeError("Missing save name")
        path = save_state(session.env, EDITOR_DIR, GAME, save_name)
        return {
            "session": self.session_state(),
            "message": f"Saved {save_name} -> {path.name}",
        }

    def load_state(
        self,
        state_name: str,
        *,
        include_frame: bool = True,
        include_trace: bool = True,
    ) -> dict[str, Any]:
        np, _, _ = _runtime_imports()
        state_path = EDITOR_DIR / "custom_integrations" / GAME / f"{state_name}.state"
        if not state_path.exists():
            raise RuntimeError(f"State not found: {state_path.name}")
        session = self.require_session()
        with gzip.open(state_path, "rb") as fh:
            data = fh.read()
        session.env.em.set_state(data)
        obs, _, terminated, truncated, info = session.env.step(np.array(NOOP, dtype=np.int8))
        session.current_state = state_name
        session.trace.clear()
        session.pending_trace_point = None
        session.frame_counter += 1
        session.last_obs = obs
        session.last_info = info
        session.last_action = list(NOOP)
        session.last_requested_action = list(NOOP)
        session.last_action_pre_sanitize = list(NOOP)
        session.last_action_source = "load_state"
        session.last_model_action_index = None
        session.select_prev = False
        session.has_selected_item = None
        self._prepare_session_plan(session, room_id=int(info.get("room_id", 0) or 0))
        self._prepare_agent_driver(session)
        session.append_trace(info)
        session.update_route_metrics(info)
        self._prime_select_workaround(session)
        controller_connected, controller_name = self._controller_status()
        return {
            "session": self.session_state(),
            "snapshot": session.build_snapshot(
                self.room_lookup,
                include_frame=include_frame,
                include_trace=include_trace,
                terminated=terminated,
                truncated=truncated,
                controller_connected=controller_connected,
                controller_name=controller_name,
            ),
            "message": f"Loaded {state_name}",
        }

    def _finalize_recording(self, session: Session) -> tuple[Path | None, Path | None]:
        if session.record_file is not None:
            session.record_file.close()
            session.record_file = None
        if session.recording_path is None:
            return None, None

        log_path = session.recording_path
        raw_path = log_path.with_name(f"{log_path.stem}_raw.json")
        summary_path = log_path.with_name(f"{log_path.stem}_summary.json")
        metadata = {
            "source": "editor_bridge",
            "state": session.current_state,
            "control_mode": session.control_mode,
            "selected_model": session.selected_model,
            "route_id": session.route_plan.route_id if session.route_plan is not None else None,
            "route_label": session.route_plan.label if session.route_plan is not None else None,
            "button_order": BUTTON_ORDER,
            "raw_buttons_note": "raw_buttons are the applied env inputs after sanitize/controller merge.",
            "raw_buttons_pre_sanitize_note": "raw_buttons_pre_sanitize are captured before directional conflict sanitization.",
            "requested_buttons_note": "requested_buttons are the UI or agent buttons before controller merge.",
        }
        raw_path.write_text(
            json.dumps(
                {
                    "raw_buttons": session.recorded_raw_buttons,
                    "raw_buttons_pre_sanitize": session.recorded_raw_buttons_pre_sanitize,
                    "requested_buttons": session.recorded_requested_buttons,
                    "action_sources": session.recorded_action_sources,
                    "model_action_indices": session.recorded_model_action_indices,
                    "metadata": metadata,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        summary_path.write_text(
            json.dumps(
                {
                    "metadata": metadata,
                    "log_path": str(log_path),
                    "raw_path": str(raw_path),
                    "frames": len(session.recorded_raw_buttons),
                    "expected_trace": session.expected_trace,
                    "actual_trace": list(session.trace),
                    "route_metrics": session.route_metrics.to_dict() if session.route_metrics is not None else None,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        session.record_summary_path = summary_path
        session.record_raw_path = raw_path
        return raw_path, summary_path

    def start_recording(self) -> dict[str, Any]:
        session = self.require_session()
        if session.recording:
            return {
                "session": self.session_state(),
                "recordingPath": str(session.recording_path),
                "message": "Recording already active",
            }
        recordings_dir = EDITOR_DIR / "editor_recordings"
        recordings_dir.mkdir(parents=True, exist_ok=True)
        route_tag = session.route_plan.route_id if session.route_plan is not None else session.current_state.lower()
        route_tag = route_tag.replace(" ", "_")
        session.recording_path = recordings_dir / f"editor_session_{route_tag}_{session.control_mode}_{int(time.time())}.jsonl"
        session.record_file = session.recording_path.open("w", encoding="utf-8")
        session.record_summary_path = None
        session.record_raw_path = None
        session.recorded_requested_buttons.clear()
        session.recorded_raw_buttons.clear()
        session.recorded_raw_buttons_pre_sanitize.clear()
        session.recorded_action_sources.clear()
        session.recorded_model_action_indices.clear()
        return {
            "session": self.session_state(),
            "recordingPath": str(session.recording_path),
            "message": "Recording started",
        }

    def stop_recording(self) -> dict[str, Any]:
        session = self.require_session()
        raw_path, summary_path = self._finalize_recording(session)
        return {
            "session": self.session_state(),
            "recordingPath": str(summary_path or raw_path or session.recording_path) if session.recording_path else None,
            "message": "Recording stopped",
        }

    def close_session(self) -> dict[str, Any]:
        if self.session is not None:
            recording_path: Path | None = None
            if self.session.recording:
                _, recording_path = self._finalize_recording(self.session)
            self.session.close()
            self.session = None
            return {
                "session": self.session_state(),
                "recordingPath": str(recording_path) if recording_path is not None else None,
                "message": "Session closed",
            }
        return {
            "session": self.session_state(),
            "message": "Session closed",
        }


def _run_stdio() -> int:
    runtime = BridgeRuntime()
    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        request_id: str | None = None
        try:
            data = json.loads(line)
            request_id = data.get("id")
            command = data.get("command")
            nav_export_dir = data.get("navExportDir")
            control_mode = data.get("controlMode")
            selected_model = data.get("selectedModel")
            include_frame = bool(data.get("includeFrame", True))
            include_trace = bool(data.get("includeTrace", True))
            if command == "hello":
                _write(_json_response(request_id=request_id, **runtime.hello()))
            elif command == "configure":
                _write(_json_response(
                    request_id=request_id,
                    **runtime.configure(
                        nav_export_dir=nav_export_dir,
                        control_mode=control_mode,
                        selected_model=selected_model,
                    ),
                ))
            elif command == "discover":
                runtime._apply_config(
                    nav_export_dir=nav_export_dir,
                    control_mode=control_mode,
                    selected_model=selected_model,
                )
                _write(_json_response(request_id=request_id, **runtime.discover()))
            elif command == "start_session":
                _write(_json_response(
                    request_id=request_id,
                    **runtime.start_session(
                        str(data.get("state") or ""),
                        nav_export_dir=nav_export_dir,
                        control_mode=control_mode,
                        selected_model=selected_model,
                        include_frame=include_frame,
                        include_trace=include_trace,
                    ),
                ))
            elif command == "snapshot":
                _write(_json_response(
                    request_id=request_id,
                    **runtime.snapshot(include_frame=include_frame, include_trace=include_trace),
                ))
            elif command == "step":
                _write(_json_response(
                    request_id=request_id,
                    **runtime.step(
                        list(data.get("action") or []),
                        int(data.get("repeat", 1)),
                        include_frame=include_frame,
                        include_trace=include_trace,
                    ),
                ))
            elif command == "save_state":
                _write(_json_response(request_id=request_id, **runtime.save_state(str(data.get("saveName") or ""))))
            elif command == "load_state":
                _write(_json_response(
                    request_id=request_id,
                    **runtime.load_state(
                        str(data.get("state") or ""),
                        include_frame=include_frame,
                        include_trace=include_trace,
                    ),
                ))
            elif command == "start_recording":
                _write(_json_response(request_id=request_id, **runtime.start_recording()))
            elif command == "stop_recording":
                _write(_json_response(request_id=request_id, **runtime.stop_recording()))
            elif command == "close_session":
                _write(_json_response(request_id=request_id, **runtime.close_session()))
            else:
                _write(_json_response(request_id=request_id, ok=False, error=f"Unknown command: {command}"))
        except Exception as exc:  # pragma: no cover - exercised in live runtime
            _write(_json_response(request_id=request_id, ok=False, error=str(exc)))
    return 0


def main(argv: list[str] | None = None) -> int:
    args = argv if argv is not None else sys.argv[1:]
    if "--stdio" not in args:
        sys.stderr.write("editor_bridge.py only supports --stdio\n")
        return 2
    return _run_stdio()


if __name__ == "__main__":
    raise SystemExit(main())
