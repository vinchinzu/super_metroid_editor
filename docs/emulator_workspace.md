# Emulator Workspace

First vertical slice for embedding the Super Metroid runtime into SMEDIT.

## What Landed

- New `Emu` workspace tab in the desktop editor.
- Python stdio bridge at [`runtime/sm_bridge/editor_bridge.py`](../runtime/sm_bridge/editor_bridge.py).
- Embedded frame viewport fed by the existing `stable_retro` runtime.
- Save-state discovery plus live `start`, `step`, `snapshot`, `save`, `load`, and `close`.
- Lightweight JSONL recording for editor sessions.
- Global world planner that reads `nav_graph.json` from the SMEDIT export directory.
- Live room-following so the editor room map can track the emulator room.
- Trail / center-of-gravity overlay on the planner using live `room_id + samus_x/y`.

## Architecture

- UI host: Kotlin Compose desktop (`desktopApp`).
- Runtime host: vendored Python bridge/runtime under `runtime/sm_bridge/`.
- Transport: newline-delimited JSON over stdio.
- Frame transport: raw RGB bytes encoded as base64.
- Planner source: exported `nav_graph.json`, not an ad hoc duplicate graph.

This keeps the emulator backend swappable later. If we move to an internal JVM backend or Electron shell, the bridge contract is still the seam.

## Current Limits

- Keyboard-only inside the embedded viewport right now.
- PPO models are inventoried in the UI, but not yet executed through the bridge.
- Planner routing is room-level click-to-link, not fine-grained intra-room authoring.
- Live room names/areas come from the editor-local nav export; without that export, the bridge falls back to raw room IDs.

## Next Slices

1. Controller input passthrough from the editor viewport.
2. PPO model loading/execution in the bridge with explicit observation wrappers per checkpoint family.
3. Toggle between manual play, assist mode, and bot ownership.
4. Rich planner annotations: arbitrary pins, labels, and per-segment colors.
5. BK2-compatible recording/export instead of JSONL-only editor traces.
