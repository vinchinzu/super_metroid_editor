# Phantom Blue Doors — Root Cause and Fix

## Symptom

Blue door cap shields appear at door positions during gameplay, even though the room's
PLM set has no blue door cap entries. These are most visible in large hack rooms
(e.g., Spike Olympics) where the persistent blue caps are obvious.

## Root Cause: door_orientation Bit 2 (Cap Flag)

The DoorDef's `door_orientation` byte (offset 3) controls whether a blue closing cap
is spawned when entering through that door:

- **Bit 2 clear (directions 0-3)**: `kDoorClosingPlmIds[0-3]` = 0x0000 → NO cap spawned
- **Bit 2 set (directions 4-7)**: `kDoorClosingPlmIds[4-7]` = blue cap PLM → cap IS spawned

When the editor changes a door's destination, the original `door_orientation` (with cap
flag) and `x_pos_plm`/`y_pos_plm` are preserved. But these positions were valid for the
ORIGINAL destination room — not the new one. Result: blue caps spawn at wrong/invalid
positions in the new destination.

### Why Vanilla SM Also Has "Rogue" Caps

Even in vanilla SM, many DoorDef positions don't have matching PLM caps:
- **Landing Site** ($91F8): Doors 0, 2, 3 have cap flag but no PLM caps at those positions
- **Mother Brain** ($92FD): 7 doors all have cap flag; only 5 have caps in escape state

This is normal vanilla behavior: blue closing caps appear when entering through those
doors. It's just very visible in custom hacks with modified room layouts.

## The Fix: Clear Cap Flag on Edited Doors

During export, the editor clears bit 2 of `door_orientation` for all edited DoorDefs:

```
orientation = orientation & 0xFB  // clear bit 2
```

This makes the game read `kDoorClosingPlmIds[0-3]` (all zero) instead of
`kDoorClosingPlmIds[4-7]` (blue cap PLMs), preventing SpawnDoorClosingPLM from
spawning any cap.

**No side effects**: The door direction bits 0-1 (Right/Left/Down/Up) are preserved.
All transition code uses `door_direction & 3` or `door_direction & 2` for direction
checks, which produce the same result regardless of bit 2.

### Previous Fix Attempt (PLM Propagation — Ineffective)

We previously tried propagating blue opening caps (C8A2-C8B4) to PLM sets that lacked
door caps present in other states. This was ineffective because:
1. Adding blue opening caps still shows blue doors (CheckIfColoredDoorCapSpawned
   switches them to closing animation mode)
2. Only helped rooms with multi-state cap divergence — couldn't fix rooms with 1 state
   or doors with no cap in ANY state
3. DoorDef positions often don't match PLM cap positions (different coordinate systems)

## kDoorClosingPlmIds Table ($8F:E68A)

| Index | Direction   | PLM ID | Effect |
|-------|-------------|--------|--------|
| 0     | Right       | 0x0000 | No cap |
| 1     | Left        | 0x0000 | No cap |
| 2     | Down        | 0x0000 | No cap |
| 3     | Up          | 0x0000 | No cap |
| 4     | Right + cap | 0xC8BE | Blue closing cap |
| 5     | Left + cap  | 0xC8BA | Blue closing cap |
| 6     | Down + cap  | 0xC8C6 | Blue closing cap |
| 7     | Up + cap    | 0xC8C2 | Blue closing cap |

## Source References

- `~/code/sm/src/sm_82.c:4257` — `SpawnDoorClosingPLM` implementation
- `~/code/sm/src/sm_82.c:4270` — `CheckIfColoredDoorCapSpawned` implementation
- `~/code/sm/src/sm_8f.c:715` — `RoomDefStateSelect_IsEventSet` (5-byte entry format)
- `~/code/sm/src/sm_8f.c:708` — `RoomDefStateSelect_TourianBoss01` (4-byte, no param)
- `~/code/sm/src/ida_types.h:260` — `DoorDef` structure
- `~/code/sm/src/ida_types.h:1847` — `addr_kDoorClosingPlmIds = 0xE68A`
- Vanilla ROM: `kDoorClosingPlmIds` at PC 0x07E68A (8 uint16 entries)
