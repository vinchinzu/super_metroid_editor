"""Python client for driving the SM editor bridge from agents or scripts."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
from typing import Any


RUNTIME_DIR = Path(__file__).resolve().parent
EDITOR_DIR = RUNTIME_DIR.parent.parent


class EditorAgentApi:
    """Thin wrapper around ``editor_bridge.py --stdio`` for agent control."""

    def __init__(
        self,
        *,
        python_executable: str | None = None,
        runtime_dir: Path | None = None,
    ) -> None:
        self.runtime_dir = (runtime_dir or RUNTIME_DIR).resolve()
        self.python_executable = python_executable or self._detect_python()
        self.process = subprocess.Popen(
            [self.python_executable, str(self.runtime_dir / "editor_bridge.py"), "--stdio"],
            cwd=EDITOR_DIR,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )

    def close(self) -> None:
        if self.process.stdin:
            self.process.stdin.close()
        if self.process.stdout:
            self.process.stdout.close()
        if self.process.poll() is None:
            self.process.terminate()

    def __enter__(self) -> "EditorAgentApi":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

    def request(
        self,
        command: str,
        *,
        state: str | None = None,
        save_name: str | None = None,
        nav_export_dir: str | None = None,
        control_mode: str | None = None,
        selected_model: str | None = None,
        action: list[int] | None = None,
        repeat: int = 1,
        include_frame: bool = True,
        include_trace: bool = True,
    ) -> dict[str, Any]:
        payload = {
            "id": str(uuid.uuid4()),
            "command": command,
            "state": state,
            "saveName": save_name,
            "navExportDir": nav_export_dir,
            "controlMode": control_mode,
            "selectedModel": selected_model,
            "action": action or [],
            "repeat": repeat,
            "includeFrame": include_frame,
            "includeTrace": include_trace,
        }
        if self.process.stdin is None or self.process.stdout is None:
            raise RuntimeError("Bridge process is not available")
        self.process.stdin.write(json.dumps(payload))
        self.process.stdin.write("\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            stderr = self.process.stderr.read() if self.process.stderr else ""
            raise RuntimeError(f"Bridge closed unexpectedly: {stderr.strip()}")
        response = json.loads(line)
        if not response.get("ok", False):
            raise RuntimeError(str(response.get("error") or "bridge error"))
        return response

    def configure(
        self,
        *,
        nav_export_dir: str | None = None,
        control_mode: str | None = None,
        selected_model: str | None = None,
    ) -> dict[str, Any]:
        return self.request(
            "configure",
            nav_export_dir=nav_export_dir,
            control_mode=control_mode,
            selected_model=selected_model,
            include_frame=False,
            include_trace=False,
        )

    def start_session(
        self,
        state: str,
        *,
        nav_export_dir: str | None = None,
        control_mode: str = "manual",
        selected_model: str | None = None,
        include_frame: bool = True,
        include_trace: bool = True,
    ) -> dict[str, Any]:
        return self.request(
            "start_session",
            state=state,
            nav_export_dir=nav_export_dir,
            control_mode=control_mode,
            selected_model=selected_model,
            include_frame=include_frame,
            include_trace=include_trace,
        )

    def watch_snapshot(
        self,
        *,
        include_frame: bool = False,
        include_trace: bool = True,
    ) -> dict[str, Any]:
        return self.request("snapshot", include_frame=include_frame, include_trace=include_trace)

    def step(
        self,
        action: list[int],
        *,
        repeat: int = 1,
        include_frame: bool = False,
        include_trace: bool = True,
    ) -> dict[str, Any]:
        return self.request(
            "step",
            action=action,
            repeat=repeat,
            include_frame=include_frame,
            include_trace=include_trace,
        )

    def _detect_python(self) -> str:
        for candidate_dir in (self.runtime_dir, EDITOR_DIR, EDITOR_DIR.parent, EDITOR_DIR.parent.parent):
            venv_python = candidate_dir / ".venv" / "bin" / "python"
            if venv_python.is_file() and os.access(venv_python, os.X_OK):
                return str(venv_python)
        configured = os.getenv("PYTHON")
        if configured:
            return configured
        return sys.executable
