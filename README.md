# Super Metroid Editor (SMEDIT)


The MS Paint of Super Metroid editing. A native desktop ROM editor built with Kotlin/Compose.

<p>
  <img src="screenshots/smedit_01.png" width="49%" alt="Room Editor" />
  <img src="screenshots/smedit_02.png" width="49%" alt="Tileset Editor" />
</p>

## What it does

- Load and parse Super Metroid `.smc` / `.sfc` ROMs
- Browse all 263 rooms by area (Crateria, Brinstar, Norfair, etc.)
- Full tile-accurate room rendering with real tileset graphics
- Paint, fill, and sample tiles with multi-tile brush support
- Right-click any block to edit block type and BTS properties
- Place and remove items/powerups with correct PLM IDs
- Tileset browser with all 29 tilesets, palette visualization, and per-tile default editing
- Toggleable overlays: solid, slope, door, spike, bomb, crumble, grapple, speed, shot blocks (beam/super/PB), items, enemies
- Enemy and item markers rendered on the map with names
- Undo/redo, project save/load (`.smedit` JSON), export patched ROM
- Pinch-to-zoom, middle-click pan, keyboard shortcuts

## Building

Requires JDK 17+ and Gradle 8.5+.

```bash
# Desktop editor
./gradlew :desktopApp:run

# CLI export tool (headless, no GUI dependency)
JAVA_HOME=$(mise where java@17) ./gradlew :cli:runCli -Pargs="--rom path/to/rom.smc rooms"
```

## CLI Export Tool

The `cli` module provides headless export of structured JSON data from a Super Metroid ROM. It reuses all ROM parsing from the `shared` module with no Compose/GUI dependency.

### Commands

| Command | Output | Description |
|---------|--------|-------------|
| `rooms` | stdout JSON | List all 262 rooms with area, map position, dimensions |
| `room <id\|handle>` | stdout JSON | Single room with collision grid, BTS, doors, items, enemies, PLMs |
| `graph` | stdout JSON | Navigation graph: 262 nodes + ~516 edges with door cap requirements |
| `export -o <dir>` | files | Full export: `rooms.json`, `nav_graph.json`, `rooms/*.json` |

### Options

- `--rom <path>` — Path to Super Metroid ROM (`.smc`, required)
- `--compact` — Compact JSON output (no indentation)

### Examples

```bash
# List all rooms
./gradlew -q :cli:runCli -Pargs="--rom rom.smc rooms"

# Export single room by handle or hex ID
./gradlew -q :cli:runCli -Pargs="--rom rom.smc room landingSite"
./gradlew -q :cli:runCli -Pargs="--rom rom.smc room 0x91F8"

# Navigation graph
./gradlew -q :cli:runCli -Pargs="--rom rom.smc graph"

# Full export to directory
./gradlew -q :cli:runCli -Pargs="--rom rom.smc export -o /tmp/sm_export"
```

### Per-Room JSON Schema

Each room file contains:

- `collision: int[row][col]` — Block types: 0=air, 1=slope, 8=solid, 9=door, 0xA=spike, 0xB=crumble, 0xC=shot, 0xE=grapple, 0xF=bomb
- `bts: int[row][col]` — BTS (Block Type Sensitivity) bytes
- `doors` — Destination room, direction, elevator flag, door cap color, required ability
- `items` — Item name, PLM ID, block coordinates
- `enemies` — Enemy name/ID, pixel and block coordinates
- `plms` — All PLMs with category (item / door\_cap / other)

### Navigation Graph

Door cap colors map to abilities: blue=beam, red=missile, green=super\_missile, yellow=power\_bomb, grey=boss\_event. Null = freely passable.

Door validation filters over-read entries by cross-checking parsed doors against physical type-9 blocks in the collision grid. Elevator doors bypass this check.

### Visualization

Generate an interactive HTML map with `cli/visualize_graph.py`:

```bash
python3 cli/visualize_graph.py /tmp/sm_export/nav_graph.json -o graph.html
```

## Roadmap

- Direct tileset image editing and tile swapping
- Custom tileset importing
- Enemy placement and editing
- FX / scrolling / background layer editing
- Room creation, resizing, and state management
- Binary-level ROM patching (free space management, pointer rewriting, bank expansion)
- IPS/BPS patch export
- Sprite viewer and palette editor

## References

- [Metroid Construction Wiki](https://wiki.metroidconstruction.com/)
- [Kejardon's SM Documentation](https://patrickjohnston.org/ASM/ROM%20data/Super%20Metroid/Kejardon's%20docs/)
- [SMILE Editor](https://wiki.metroidconstruction.com/doku.php?id=sm:editor_utility_guides:smile2.5)
