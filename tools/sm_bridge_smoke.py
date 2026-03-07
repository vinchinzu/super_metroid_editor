#!/usr/bin/env python3
"""Build and smoke-test the SM editor bridge with a 2-player session."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import select
import subprocess
import sys
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
BRIDGE = ROOT / "runtime" / "sm_bridge" / "editor_bridge.py"
DEFAULT_PYTHON = ROOT.parent / ".venv" / "bin" / "python"
ACTIONS_PER_PLAYER = 12


class SmokeFailure(RuntimeError):
    pass


def _print_step(message: str) -> None:
    print(f"[smoke] {message}")


def _check(condition: bool, message: str) -> None:
    if not condition:
        raise SmokeFailure(message)


def _resolve_sm_root(explicit: Path | None = None) -> Path:
    candidates: list[Path] = []
    if explicit is not None:
        candidates.append(explicit)
    env_path = os.environ.get("SM_RUNTIME_DIR")
    if env_path:
        candidates.append(Path(env_path))
    candidates.extend([ROOT.parent / "sm", ROOT / "sm"])
    for candidate in candidates:
        resolved = candidate.expanduser().resolve()
        if (resolved / "Makefile").exists():
            return resolved
    raise SmokeFailure(
        "could not locate reverse-SM runtime; set --sm-root or SM_RUNTIME_DIR"
    )


def _run_sm_build(sm_root: Path) -> None:
    _print_step(f"building reverse-SM runtime at `{sm_root}`")
    result = subprocess.run(
        ["make", "-C", str(sm_root), "-j4"],
        cwd=ROOT,
        text=True,
        capture_output=True,
    )
    if result.returncode != 0:
        raise SmokeFailure(result.stderr.strip() or result.stdout.strip() or "sm build failed")
    _print_step("sm build passed")


class BridgeClient:
    def __init__(self, python_path: Path) -> None:
        self._next_id = 1
        self.proc = subprocess.Popen(
            [str(python_path), str(BRIDGE), "--stdio"],
            cwd=ROOT,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )

    def close(self) -> None:
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)

    def request(self, command: str, timeout: float = 90.0, **payload: Any) -> dict[str, Any]:
        _check(self.proc.stdin is not None, "bridge stdin unavailable")
        _check(self.proc.stdout is not None, "bridge stdout unavailable")
        if self.proc.poll() is not None:
            raise SmokeFailure(f"bridge exited early with code {self.proc.returncode}: {self._drain_stderr()}")
        request = {"id": f"smoke-{self._next_id}", "command": command}
        request.update(payload)
        self._next_id += 1
        self.proc.stdin.write(json.dumps(request))
        self.proc.stdin.write("\n")
        self.proc.stdin.flush()
        ready, _, _ = select.select([self.proc.stdout], [], [], timeout)
        if not ready:
            raise SmokeFailure(f"timeout waiting for bridge response to `{command}`: {self._drain_stderr()}")
        line = self.proc.stdout.readline()
        if not line:
            raise SmokeFailure(f"bridge closed stdout during `{command}`: {self._drain_stderr()}")
        response = json.loads(line)
        if not response.get("ok", False):
            raise SmokeFailure(f"`{command}` failed: {response.get('error')}")
        return response

    def _drain_stderr(self) -> str:
        _check(self.proc.stderr is not None, "bridge stderr unavailable")
        chunks: list[str] = []
        while True:
            ready, _, _ = select.select([self.proc.stderr], [], [], 0)
            if not ready:
                break
            chunk = self.proc.stderr.readline()
            if not chunk:
                break
            chunks.append(chunk.rstrip())
        return " | ".join(chunk for chunk in chunks if chunk)


def _choose_state(states: list[dict[str, str]], requested: str | None) -> str:
    names = [entry["name"] for entry in states]
    for candidate in (requested, "AfterMissiles", "Bomb Torizo Room", names[0] if names else None):
        if candidate and candidate in names:
            return candidate
    raise SmokeFailure("no published save states available for smoke test")


def _cleanup_recording(path_str: str | None) -> None:
    if not path_str:
        return
    path = Path(path_str)
    if not path.exists():
        return
    paths = {path}
    if path.suffix == ".json":
        try:
            summary = json.loads(path.read_text(encoding="utf-8"))
            log_path = summary.get("log_path")
            raw_path = summary.get("raw_path")
            if log_path:
                paths.add(Path(log_path))
            if raw_path:
                paths.add(Path(raw_path))
        except Exception:
            pass
    for item in paths:
        if item.exists():
            item.unlink()


def run_smoke(*, python_path: Path, player_count: int, state_name: str | None, keep_recording: bool) -> None:
    expected_action_len = ACTIONS_PER_PLAYER * player_count
    action = [0] * expected_action_len
    action[7] = 1
    if player_count > 1:
        action[ACTIONS_PER_PLAYER + 7] = 1
        action[ACTIONS_PER_PLAYER + 9] = 1
        action[ACTIONS_PER_PLAYER + 11] = 1

    client = BridgeClient(python_path)
    recording_path: str | None = None
    try:
        _print_step("requesting bridge hello")
        hello = client.request("hello")
        capabilities = hello["capabilities"]
        _check(capabilities["supportsFrames"], "bridge should report frame support")
        _check(capabilities["maxPlayers"] == 2, "bridge should advertise maxPlayers=2")
        _check(capabilities["defaultPlayerCount"] == 2, "bridge should default to 2 players")

        _print_step("discovering published states")
        discover = client.request("discover", playerCount=player_count)
        states = discover["states"]
        state = _choose_state(states, state_name)
        _print_step(f"using state `{state}`")

        _print_step("configuring bridge for 2-player smoke")
        configured = client.request("configure", playerCount=player_count)
        _check(configured["session"]["playerCount"] == player_count, "configure should persist playerCount")

        _print_step("starting session")
        started = client.request(
            "start_session",
            state=state,
            playerCount=player_count,
            includeFrame=False,
            includeTrace=False,
        )
        session = started["session"]
        snapshot = started["snapshot"]
        _check(session["active"], "session should be active after start_session")
        _check(session["playerCount"] == player_count, "session playerCount mismatch")
        _check(len(snapshot["lastAction"]) == expected_action_len, "initial lastAction length mismatch")
        _check(len(snapshot["lastRequestedAction"]) == expected_action_len, "initial requested action length mismatch")

        _print_step("starting recording")
        recording = client.request("start_recording")
        _check(recording["session"]["recording"], "recording should be active after start_recording")

        _print_step("stepping session with 2-player action")
        stepped = client.request(
            "step",
            action=action,
            repeat=2,
            includeFrame=False,
            includeTrace=False,
        )
        step_session = stepped["session"]
        step_snapshot = stepped["snapshot"]
        _check(step_session["frameCounter"] >= session["frameCounter"] + 2, "frame counter should advance by repeat")
        _check(len(step_snapshot["lastAction"]) == expected_action_len, "step lastAction length mismatch")
        _check(len(step_snapshot["lastRequestedAction"]) == expected_action_len, "step requested action length mismatch")

        _print_step("stopping recording")
        stopped = client.request("stop_recording")
        recording_path = stopped.get("recordingPath")
        _check(recording_path is not None, "stop_recording should return a recording path")
        summary_path = Path(recording_path)
        _check(summary_path.exists(), "recording summary was not written")
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        metadata = summary["metadata"]
        _check(metadata["player_count"] == player_count, "recording summary player_count mismatch")
        _check(metadata["action_stride"] == ACTIONS_PER_PLAYER, "recording summary action_stride mismatch")

        _print_step("closing session")
        closed = client.request("close_session")
        _check(not closed["session"]["active"], "session should be inactive after close_session")
        _print_step("bridge smoke passed")
    finally:
        try:
            client.close()
        finally:
            if recording_path and not keep_recording:
                _cleanup_recording(recording_path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", type=Path, default=DEFAULT_PYTHON if DEFAULT_PYTHON.exists() else Path(sys.executable))
    parser.add_argument("--sm-root", type=Path, help="Path to the reverse-SM runtime checkout")
    parser.add_argument("--state", help="Optional published save state name to use")
    parser.add_argument("--player-count", type=int, default=2)
    parser.add_argument("--skip-sm-build", action="store_true")
    parser.add_argument("--keep-recording", action="store_true")
    args = parser.parse_args(argv)

    try:
        sm_root = _resolve_sm_root(args.sm_root)
        if not args.skip_sm_build:
            _run_sm_build(sm_root)
        run_smoke(
            python_path=args.python,
            player_count=max(1, min(int(args.player_count), 2)),
            state_name=args.state,
            keep_recording=args.keep_recording,
        )
    except SmokeFailure as exc:
        print(f"[smoke] FAILED: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
