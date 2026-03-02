# Super Metroid Diagnostic Scripts

Python scripts for analyzing and debugging Super Metroid ROMs.
Designed for iterative development — run these to validate changes
rather than re-deriving ROM internals from scratch.

## Scripts

### `scan_state_selectors.py`
Scans ALL rooms and catalogs every state selector code encountered.
Validates entry sizes, state data pointers, and checks for codes
our Kotlin parser doesn't handle.

```bash
python3 scan_state_selectors.py <rom_path> [--verbose]
python3 scan_state_selectors.py "test-resources/Super Metroid (JU) [!].smc"
```

### `compare_doors.py`
Compares door entries and PLM sets between two ROMs. Identifies
changes in DoorDef structures, orientation/cap flags, and PLM sets.

```bash
python3 compare_doors.py <vanilla_rom> <modified_rom> [room_ids...]
python3 compare_doors.py vanilla.smc edited.smc 0x92FD 0xDD58
```

### `dump_room_data.py`
Dumps detailed room data for a specific room: header, state selectors,
state data blocks, door entries with cap flag analysis, and PLM sets.

```bash
python3 dump_room_data.py <rom_path> <room_id>
python3 dump_room_data.py vanilla.smc 0x91F8   # Landing Site
python3 dump_room_data.py edited.smc 0xDD58     # Mother Brain
```

## Dependencies

- Python 3.8+
- No external packages (stdlib only)
- Requires `room_mapping_complete.json` from the editor's resources

## Key References

- `~/code/sm/` — snesrev Super Metroid C decompilation
- `~/code/sm/src/sm_8f.c` — Room state selector dispatch
- `~/code/sm/src/sm_82.c` — Door transition engine, SpawnDoorClosingPLM
- `~/code/sm/src/ida_types.h` — DoorDef, RoomState structures
- `~/code/sm/assets/restool.py` — Binary ROM parser (room/state format)
- `~/code/sm/assets/names.txt` — Function/symbol addresses
- `~/code/MapRandomizer/` — Rust-based SM randomizer (room reconnection)
