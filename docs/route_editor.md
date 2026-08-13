# Route Editor Panel

The Route Editor Panel is a tool for creating, editing, and playing back TAS (Tool-Assisted Speedrun) routes in Super Metroid. It provides frame-by-frame control over inputs and visualizes the resulting path.

## Features

- **Record Routes**: Capture button inputs and Samus positions frame-by-frame while playing
- **Playback**: Play back recorded routes with frame-perfect accuracy
- **Frame Stepping**: Step forward and backward through routes one frame at a time
- **Timeline View**: Visual timeline showing input changes across frames
- **Input List**: Detailed list of all input frames with button states
- **Track Overlay**: Purple path visualization on the room map showing recorded positions
- **Route Library**: Save and load routes as JSON files

## Getting Started

### Opening the Route Editor

The Route Editor panel is integrated into the Emulator workspace. It appears in the right column below the Global World Planner.

### Prerequisites

Before using the Route Editor, you need:
1. A connected emulator bridge (see `emulator_workspace.md`)
2. An active emulator session (boot a save state)

## Recording a Route

1. **Start a Session**: Boot a save state in the emulator
2. **Click "Record"**: The Route Editor will start capturing your inputs
3. **Play Normally**: Use keyboard controls to play Super Metroid
4. **Stop Recording**: Click "Stop Recording" when finished

The route editor captures:
- **Button inputs**: All 12 SNES buttons (B, Y, Select, Start, D-pad, A, X, L, R)
- **Position samples**: Samus's X/Y coordinates and room ID per frame
- **Frame count**: Total number of frames recorded

### Button Mapping

| Keyboard | SNES Button |
|----------|-------------|
| Z        | B           |
| X        | A           |
| A        | Y           |
| S        | X           |
| Q        | L           |
| W        | R           |
| Arrows   | D-pad       |
| Enter    | Start       |
| Shift/Tab| Select      |

## Playing Back a Route

1. **Load a Route**: Select a saved route from the Route Library
2. **Reset to Start State**: Use "Reset" in the emulator toolbar to return to the route's starting state
3. **Click "Play"**: The emulator will play back the route frame-by-frame

During playback:
- The emulator follows the recorded inputs automatically
- The current frame indicator shows progress
- The purple track overlay shows the recorded path on the room map

### Playback Controls

- **Play**: Start playing back the route
- **Pause**: Pause playback at the current frame
- **Resume**: Continue playback from current position
- **Stop**: Stop playback and return to idle state
- **◀ Step**: Move back one frame
- **Step ▶**: Move forward one frame

## Viewing Routes

### Timeline View

The timeline shows:
- **Cyan lines**: Input changes (when buttons are pressed/released)
- **Red line**: Current playback/edit position
- **Slider**: Drag to scrub through the route

### Input List

The input list displays each frame where inputs change:
- **Frame number**: 5-digit frame count (e.g., "F00042")
- **Button states**: Names of pressed buttons (e.g., "B Right A")
- **Highlight**: Current frame is highlighted in blue

Click any frame in the list to jump to that position.

## Saving and Loading Routes

### Saving

1. **Record or load a route**
2. **Click "Save Route"**
3. The route is saved to `routes/<route_name>.json`

Route files include:
- Name and description
- Starting save state name
- All input frames
- All position samples
- Custom metadata

### Loading

1. **Browse the Route Library** in the right panel
2. **Click a route name** to load it
3. The loaded route appears in the editor

### Route File Format

Routes are saved as JSON files with this structure:

```json
{
  "name": "route_1723500000000",
  "description": "",
  "startStateName": "landing_site",
  "frameCount": 1200,
  "inputs": [
    {"frame": 0, "buttons": [0,0,0,0,0,0,1,0,0,0,0,0]},
    {"frame": 42, "buttons": [1,0,0,0,0,0,1,0,0,0,0,0]}
  ],
  "positions": [
    {"frame": 0, "roomId": 37368, "x": 100, "y": 200},
    {"frame": 1, "roomId": 37368, "x": 102, "y": 200}
  ],
  "metadata": {}
}
```

## Track Overlay

The route editor adds a **purple path overlay** to the room map showing recorded Samus positions:

- **Purple line**: Connects all position samples in the current route
- **Green line**: Live trace from current emulator session
- **Orange line**: Planned route from the planner

The overlay updates automatically when:
- Recording a new route
- Loading a different route
- Switching rooms

## Integration with Emulator

The route editor integrates seamlessly with the emulator:

### During Recording
- Captures inputs from keyboard in real-time
- Records position samples from emulator bridge telemetry
- Frame counter syncs with emulator session

### During Playback
- Overrides keyboard inputs with route inputs
- Emulator executes recorded button presses
- Position tracking shows how closely playback matches recording

## Tips and Best Practices

1. **Name your routes descriptively**: The route name defaults to a timestamp - rename it before saving
2. **Use save states**: Always record from a consistent save state for reproducible routes
3. **Step through difficult sections**: Use frame stepping to analyze and debug problematic segments
4. **Check the overlay**: The purple track shows if your route is smooth and efficient
5. **Save frequently**: Routes are only saved when you click "Save Route"

## Limitations

- Routes are stored locally in the `routes/` directory
- Position samples depend on bridge telemetry availability
- Playback requires the exact same starting save state
- No editing of individual frames (yet) - only record and trim
- No frame-by-frame position editing (planned for future)

## Troubleshooting

### Route doesn't play back correctly
- Ensure you reset to the same save state used during recording
- Check that the route's `startStateName` matches your current state
- Verify the emulator session is active (not paused)

### Purple track doesn't appear
- Check that the route has position samples (not just inputs)
- Verify you're viewing the correct room
- Reload the route if switching between editor workspaces

### Recording doesn't capture positions
- Ensure the emulator bridge is connected
- Check that telemetry is enabled in bridge configuration
- Verify the snapshot includes `roomId`, `samusX`, and `samusY` fields

## Future Enhancements

Planned features for future releases:
- Frame-by-frame input editing
- Run-length encoding for compact storage
- BK2 format export for compatibility with other TAS tools
- Route comparison and diff visualization
- Physics prediction integration
- Automated route optimization via RL agents
