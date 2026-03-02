# Rogue Door Caps — Analysis and Fix

## What you see

- **Landing Site (0x91F8)**: A blue door cap appears floating in the air on the right side of the room (e.g. at block position 1,1 or similar). It can be opened but there is no door transition behind it.
- **Tourian Escape Room 2 (0xDE7A)**: A door cap on the right side facing in, same behavior — openable but no door behind it.

## Cause

In Super Metroid, **door caps** (the colored shields you shoot to open) are **PLMs** (Post Load Modifications) in the room’s PLM set. Each door cap PLM has:

- A **PLM ID** (e.g. 0xC8A2 Blue Left, 0xC8A8 Blue Right)
- **Block coordinates (x, y)** where the cap is drawn

The game draws a door cap at that (x, y) regardless of the tile underneath. So:

- If a **door cap PLM** is placed at a block that is **not** a door block (block type 9) in the level data, you get a “rogue” cap: it looks and opens like a door, but there is no door transition there.

So the cause is: **a door cap PLM exists in the room’s PLM set at a position where there is no door block.**

Typical ways this can happen:

1. **Wrong coordinates when adding a door**  
   A door pattern or “add door cap” action ends up adding the PLM at (1,1) or another wrong position (e.g. default or buggy coordinates).

2. **Shared PLM set**  
   Two rooms can point to the same PLM set (e.g. different state of the same room). Edits from one context can add a door cap that only makes sense in the other, or at a position that isn’t a door in this room.

3. **Paste / duplicate**  
   Pasting a door or copying room data can copy a door cap PLM with coordinates that are valid in the source room but not in the current room (no door block there).

It is **not** caused by “another room also exiting to this room.” The door list and room exits only define transitions; they don’t create extra door cap PLMs. The rogue cap is always due to a **door cap PLM at (x,y) where block type ≠ 9** in that room’s level data.

## How the editor addresses it

- **On export**, when writing PLM sets, the editor checks each door cap PLM against the **current** level data (after tile patches and any relocation):
  - It uses the level data pointer from the room state in the ROM.
  - For each door cap PLM at `(plm.x, plm.y)` it checks the block type at that position.
  - If the block type is **not** 9 (door), the editor **skips** that PLM (does not write it) and prints a line to the console, for example:
    - `Room 0x91F8: skipping rogue door cap PLM 0xc8a8 at (1,1) — block type 0 (not door 9)`
- So rogue door caps are **not** written to the ROM, and the in-game “floating” or “wrong” door cap disappears after re-export.

## How to fix existing projects

1. **Re-export the ROM**  
   With the new logic, any door cap PLM not on a door block is skipped and logged. Re-export and test; the rogue caps should be gone.

2. **Remove the PLM in the editor**  
   The editor now loads PLMs from every room state (default, E629, E612, etc.), so rogue door caps in e.g. Mother Brain (0x92FD) or Tourian escape (0xDE7A) are visible. If you want to clean the project:
   - Open the affected room (e.g. Landing Site 0x91F8 or Tourian 0xDE7A).
   - Open the PLM list and look for a **door cap** at a suspicious position (e.g. (1,1) or a block that’s not a door).
   - Remove that PLM so it no longer appears in the room’s PLM set.

3. **Check room and state**  
   If a room has multiple states (e.g. E629, E612) with different PLM sets, make sure you’re not adding door caps in a state where that position isn’t a door.

## Summary

| What you see       | Cause                                      | Fix                                      |
|--------------------|--------------------------------------------|------------------------------------------|
| Rogue door cap     | Door cap PLM at (x,y) where block ≠ door 9 | Export (editor skips it) or remove PLM   |
| “Another room exits here” | Not the cause of extra caps          | N/A                                      |

The editor now prevents rogue door caps from being written to the ROM by only writing door cap PLMs that sit on door blocks (type 9) in the current level data.
