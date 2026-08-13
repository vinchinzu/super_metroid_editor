# Route Editor Panel

The Route Editor Panel is a tool for creating, editing, and playing back TAS (Tool-Assisted Speedrun) movies in Super Metroid. It provides per-frame control over inputs and visualizes trajectory traces on the room map.

## Features

- **Record Movies**: Capture button inputs and Samus positions frame-by-frame while playing
- **Per-Frame Editing**: 12-button grid showing all frames - click to toggle individual buttons
- **Playback**: Play back recorded movies with frame-perfect accuracy
- **Frame Stepping**: Step forward/backward, jump by 10, or scrub to any frame
- **Timeline Grid**: Scrollable list of all frames with visual button states
- **Trace Visualization**: Pink trajectory overlay on room map with gold playhead cursor
- **Movie Library**: Save and load movies as `.tasmovie.json` files (`smedit-tas-1` format)

## Getting Started

### Opening the Route Editor

The Route Editor panel is integrated into the Emulator workspace. It appears in the right column below the Global World Planner.

### Prerequisites

Before using the Route Editor, you need:
1. A connected emulator bridge (see `emulator_workspace.md`)
2. An active emulator session (boot a save state)

## Recording a Movie

1. **Start a Session**: Boot a save state in the emulator
2. **Click "Record"**: The Route Editor will start capturing your inputs
3. **Play Normally**: Use keyboard controls to play Super Metroid
4. **Stop Recording**: Click "Stop Recording" when finished

The route editor captures:
- **Button inputs**: All 12 SNES buttons encoded as 12-char mnemonics (e.g., `"B...U...A..."`)
- **Trace points**: Samus's X/Y coordinates, subpixels, pose, and room ID (sparse array)
- **Frame count**: Total number of frames recorded

### Button Mapping

| Keyboard | SNES Button | Mnemonic |
|----------|-------------|----------|
| Z        | B           | `B`      |
| A        | Y           | `Y`      |
| Shift/Tab| Select      | `.` (Select) |
| Enter    | Start       | `.` (Start)  |
| ↑        | Up          | `U`      |
| ↓        | Down        | `D`      |
| ←        | Left        | `L`      |
| →        | Right       | `R`      |
| X        | A           | `A`      |
| S        | X           | `X`      |
| Q        | L shoulder  | `.` (L)  |
| W        | R shoulder  | `.` (R)  |

## Editing Frames

### RouteTimelinePanel: Per-Frame Grid

After recording (or loading) a movie, the timeline shows all frames in a scrollable list:

- **Frame number**: 5-digit frame index (e.g., `00042`)
- **12-button grid**: One cell per SNES button (B, Y, Sel, Sta, U, D, L, R, A, X, L, R)
  - **Green cell**: Button pressed
  - **Gray cell**: Button released
- **Click a button**: Toggle between pressed/released
- **Click the frame number**: Seek to that frame

### Editing Operations

- **Toggle button**: Click any button cell to change its state
- **Truncate**: Click "Truncate here" to remove all frames after the current frame
- **Jump frames**: Use "+10" / "-10" buttons to skip ahead/back
- **Scrub**: Drag the slider or click frame numbers to navigate

Changes take effect immediately and persist in `currentMovie`.

## Playing Back a Movie

1. **Load a Movie**: Select a saved movie from the Movie Library
2. **Reset to Start State**: Use "Reset" in the emulator toolbar to return to the movie's starting state
3. **Click "Play"**: The emulator will play back the movie frame-by-frame

During playback:
- The emulator follows the recorded inputs automatically
- The current frame indicator shows progress
- The **pink trace overlay** shows the recorded trajectory on the room map
- The **gold playhead cursor** tracks the current frame position

### Playback Controls

- **Play**: Start playing back the movie
- **Pause**: Pause playback at the current frame
- **Resume**: Continue playback from current position
- **Stop**: Stop playback and return to idle state
- **◀ Step**: Move back one frame
- **Step ▶**: Move forward one frame

## Saving and Loading Movies

### Saving

1. **Record or edit a movie**
2. **Click "Save Movie"**
3. The movie is saved to `routes/<timestamp>.tasmovie.json`

Movie files include:
- Metadata (start state, creation timestamp)
- Button order (SNES standard 12-button layout)
- All input frames (12-char mnemonics)
- Trace array (position samples with x, y, subX, subY, pose, roomId)

### Loading

1. **Browse the Movie Library** in the right panel
2. **Click a movie name** to load it
3. The loaded movie appears in the editor

### Movie File Format: `smedit-tas-1`

Movies are saved as JSON files in the `smedit-tas-1` format (canonical TasMovie from `tas_foundation`):

```json
{
  "format": "smedit-tas-1",
  "meta": {
    "startState": "landing_site",
    "createdAtEpochMs": 1723500000000
  },
  "buttonOrder": ["B","Y","Select","Start","Up","Down","Left","Right","A","X","L","R"],
  "frames": [
    "............",
    "B.......A...",
    "B...U...A...",
    "....U......."
  ],
  "trace": [
    {"frame": 0, "x": 100, "y": 200, "roomId": 37368},
    {"frame": 2, "x": 110, "y": 195, "subX": 32768, "subY": 16384, "pose": 1, "roomId": 37368},
    {"frame": 3, "x": 115, "y": 190, "roomId": 37368}
  ]
}
```

#### TasTracePoint Fields

| Field   | Type    | Required | Description |
|---------|---------|----------|-------------|
| `frame` | Int?    | No       | Frame index (omit for dense traces) |
| `x`     | Int     | **Yes**  | Samus X position (pixels) |
| `y`     | Int     | **Yes**  | Samus Y position (pixels) |
| `subX`  | Int?    | No       | Subpixel X (0-65535) |
| `subY`  | Int?    | No       | Subpixel Y (0-65535) |
| `pose`  | Int?    | No       | Pose/animation state |
| `roomId`| Int?    | No       | Current room ID |

Trace points are **sparse** - you don't need a point for every frame.

## Trace Visualization

The route editor adds trajectory overlays to the room map:

- **Pink polyline** (first candidateTrack): Recorded trace from the loaded movie (truth for visualization)
- **Purple polyline** (second candidateTrack, thinner): Predicted hop overlay (HINT TRACK ONLY — NOT EMULATOR-LEGAL)
- **Gold circle**: Playhead cursor showing the most recent trace point at or before the current frame
- **Green line**: Live trace from current emulator session
- **Orange line**: Planned route from the planner

**Truth vs Hint**: Desktop snes9x is UI playback only. Recorded snes9x traces are **truth for visualization**. Predicted / MiniStep / hop_short overlays are **HINT TRACKS — NOT EMULATOR-LEGAL**. The hop_short overlay notes say "HINT TRACK ONLY — NOT EMULATOR-LEGAL."

The overlay updates automatically when:
- Recording a new movie
- Loading a different movie
- Scrubbing to a different frame
- Switching rooms

### candidateTracks: Multi-Track Support

`candidateTracks` is a `List<List<LocalRoomPoint>>`, allowing multiple trajectory overlays:
1. **First track** (pink): Recorded movie trace (truth for visualization)
2. **Second track** (purple, thinner): Predicted hop from physics plugin (HINT TRACK — NOT EMULATOR-LEGAL)

Future uses: A/B testing, RL candidates, greenzone visualization.

## Physics Plugin Slot & Residual Analysis

The route editor includes a **swappable physics prediction plugin** that provides:
1. **Hop overlays**: Short trajectory predictions (HINT TRACKS — NOT EMULATOR-LEGAL)
2. **Residual analysis**: Frame-by-frame trust metrics comparing predicted vs **SuperMetroidEnv** harness observations

### Plugin Design

The `PhysicsPredictPlugin` interface mirrors `EmulatorBackend` design: a factory-swappable plugin that provides prediction without replacing the emulator as truth. This exists so a broken hydrate/load-state implementation (sm_rev_predict now, future Haskell port) can be replaced without ripping the timeline.

**Available plugins**:
- `NullPhysicsPlugin`: No-op default, all frames UNMEASURED
- `SmRevPredictPlugin` (default): File-based plugin that loads `hop_short.tasmovie.json` as a HINT track

**Auto-load**: If `routes/hop_short.tasmovie.json` exists, the editor automatically loads it on startup.

**CRITICAL CONSTRAINT**: Desktop snes9x (SMEDIT's embedded emulator) is **UI playback only**. Do NOT compute residuals against its traces. Residual computation is **only valid against SuperMetroidEnv harness observations**. Without harness observations, residual is unmeasured (all fd_* null, all FrameTrust UNMEASURED).

### Residual Profile: R(τ)

The residual profile compares **predicted traces** (from plugin) against **SuperMetroidEnv harness observations** (NOT desktop snes9x):

**R(τ) = (fd_σ+, fd_σ, fd_π, Oπ)**
- **fd_σ+**: First frame with subpixel+pixel disagreement (nullable, "n.m." when unmeasured)
- **fd_σ**: First frame with pixel-only disagreement (nullable, "n.m." when unmeasured)
- **fd_π**: First frame with pose disagreement (nullable, "n.m." when unmeasured)
- **Oπ**: First frame with roomId ($079B) disagreement (nullable, "n.m." when unmeasured)

Note: **fd_†** refers to O† (energy/death) and lag desync, not roomId.

The residual readout shows:
- R(τ) tuple with frame indices (or "n.m." if no observation or unmeasured)
- First differing field name (e.g., "roomId", "x/y", "subX/subY")
- Human-readable cause (e.g., "$079B roomId mismatch" or "No SuperMetroidEnv harness observation available")

### Frame Trust Coloring

The timeline colors each frame by trust level:

- **Default gray** (TRUSTWORTHY): Oσ/Oπ holding (pixel + pose match), safe to keep editing
- **Light yellow** (SPOT_CHECK): Pure subpixel disagreement only (fd_σ set, fd_π None), mostly safe
- **Light orange** (NEEDS_EMU): Oπ broke — pixel x/y mismatch and/or pose $0A1C mismatch (kinematics drift, not death)
- **Light red** (DEAD): $079B roomId mismatch, O† energy/death, or lag desync — critical failure, drop back to emu
- **Default gray** (UNMEASURED): No SuperMetroidEnv harness observation available (this is the default state)

**When to trust residual colors**:
- **TRUSTWORTHY (Oσ/Oπ)**: Keep editing, prediction matches SuperMetroidEnv (pixel + pose)
- **SPOT_CHECK**: Minor subpixel drift only, unlikely to affect gameplay
- **NEEDS_EMU**: Oπ kinematics drift (pixel position or pose mismatch) — not death, but needs emulator verification
- **DEAD**: Critical failure ($079B roomId mismatch, O†, or lag desync) — stop editing immediately, drop back to emu
- **UNMEASURED**: No harness observation; cannot verify prediction accuracy

### Truth vs Hint

**Desktop snes9x (SMEDIT's embedded emulator) is UI playback only.** Recorded snes9x traces are **truth for visualization**. Predicted / MiniStep / hop_short overlays are **HINT TRACKS ONLY — NOT EMULATOR-LEGAL**.

The hop_short overlay is labeled "HINT TRACK ONLY — NOT EMULATOR-LEGAL" in predictHop results. Do not treat it as final or emulator-legal.

### Legality Note

Legality is **RetroRL stable-retro** (SuperMetroidEnv). BK2 import may exist; do not treat BizHawk sync as Mini-legal. The physics plugin provides hints for editing; only **SuperMetroidEnv observations** are used for residual validation.

### Swapping Plugins

To swap the physics plugin (for testing or advanced use):

```kotlin
routeEditorState.setPhysicsPlugin(NullPhysicsPluginFactory)  // or SmRevPredictPluginFactory
```

This is a one-liner factory swap, not a timeline rewrite.

### Default State: Unmeasured

The default state for residual is **UNMEASURED**. Without SuperMetroidEnv harness observations, all fd_* values are null ("n.m."), all FrameTrust values are UNMEASURED, and the cause is "No SuperMetroidEnv harness observation available". This is correct and expected until CLI --load-state ships and harness observations become available.

## Integration with Emulator

The route editor integrates seamlessly with the emulator:

### During Recording
- Captures inputs from keyboard in real-time
- Records trace points from emulator bridge telemetry
- Frame counter syncs with emulator session

### During Playback
- Overrides keyboard inputs with movie frames
- Emulator executes recorded button presses
- Trace overlay shows expected trajectory

### During Editing
- Changes to button states update `currentMovie` immediately
- No emulator interaction until you click "Play"

## Tips and Best Practices

1. **Use save states**: Always record from a consistent save state for reproducible movies
2. **Step through difficult sections**: Use frame stepping to analyze and debug problematic segments
3. **Edit carefully**: Toggle buttons in the grid to fix mistakes without re-recording
4. **Truncate to rerecord**: Use "Truncate here" to cut off the end and rerecord from that point
5. **Check the trace**: The pink track and gold cursor show if your trajectory is smooth
6. **Save frequently**: Movies are only saved when you click "Save Movie"

## Limitations

- Movies are stored locally in the `routes/` directory
- Trace points depend on bridge telemetry availability
- Playback requires the exact same starting save state
- Editing frames does not update trace points (trace is immutable after recording)
- No automatic trace interpolation when inserting frames

## Troubleshooting

### Movie doesn't play back correctly
- Ensure you reset to the same save state used during recording
- Check that the movie's `startState` matches your current state
- Verify the emulator session is active (not paused)

### Pink trace doesn't appear
- Check that the movie has trace points (not just frames)
- Verify you're viewing the correct room (trace is room-filtered)
- Reload the movie if switching between editor workspaces

### Recording doesn't capture trace
- Ensure the emulator bridge is connected
- Check that telemetry is enabled in bridge configuration
- Verify the snapshot includes `roomId`, `samusX`, and `samusY` fields

### Playhead cursor doesn't move
- The cursor shows the most recent trace point at or before `currentFrame`
- If no trace points exist before the current frame, no cursor appears
- Sparse traces will show the cursor "jumping" between trace points

### Residual shows all DEAD frames
- Residual computation requires SuperMetroidEnv harness observations
- Desktop snes9x traces are NOT used for residual (UI playback only)
- Without harness observations, residual will be unmeasured (all UNMEASURED)
- Check that you're passing SuperMetroidEnv observations to computeResidual()

### Residual shows all UNMEASURED frames
- This is the correct default state without SuperMetroidEnv harness observations
- Desktop snes9x is UI playback only; its traces are not used for residual
- Unmeasured residual is expected until CLI --load-state ships and harness observations become available

### hop_short.tasmovie.json not loading
- Check that the file exists in `routes/hop_short.tasmovie.json`
- Verify the file is valid JSON in `smedit-tas-1` format
- Check the status message on startup for load errors
- Remember: hop_short is a HINT TRACK ONLY — NOT EMULATOR-LEGAL

## Future Enhancements

Planned features for future releases:
- Frame insertion with automatic trace interpolation
- BK2 format export (already supported by TasMovie, UI pending)
- Trace point editing (drag x/y on map)
- Run-length compression for hold-runs
- Automated route optimization via RetroRL agents
- Full sm_rev_predict physics engine (currently stub)
- Haskell MiniStep port for legality checking
- Dashed line rendering for predicted hop overlay
- Greenzone visualization (frame trust as timeline bar)
