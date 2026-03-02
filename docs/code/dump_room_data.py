#!/usr/bin/env python3
"""
Dump detailed room data from a Super Metroid ROM for a specific room.

Shows: room header, all state selectors, state data blocks, door entries
with cap flag analysis, PLM sets (highlighting door caps), and level data
door block positions.

Usage:
    python3 dump_room_data.py <rom_path> <room_id>

Examples:
    python3 dump_room_data.py vanilla.smc 0x91F8   # Landing Site
    python3 dump_room_data.py edited.smc 0xDD58     # Mother Brain
    python3 dump_room_data.py edited.smc 0xDE7A     # Tourian Escape 2

References:
    - Room header: 11 bytes at $8F:<roomId>
    - State data: 26 bytes per state
    - DoorDef: 12 bytes per door in bank $83
    - PLM entry: 6 bytes (id, x, y, param)
    - Level data: LZ-compressed, block type 0x9 = door
"""
import json
import struct
import sys
import os
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent.parent.parent
ROOM_MAPPING = SCRIPT_DIR / "shared/src/commonMain/resources/room_mapping_complete.json"

DIRECTIONS = {0: "Right", 1: "Left", 2: "Down", 3: "Up"}

# Door cap PLM color names
def door_cap_info(plm_id):
    if 0xC842 <= plm_id < 0xC85A: return "Grey-Opening"
    if 0xC85A <= plm_id < 0xC872: return "Yellow-Opening"
    if 0xC872 <= plm_id < 0xC88A: return "Green-Opening"
    if 0xC88A <= plm_id < 0xC8A2: return "Red-Opening"
    if 0xC8A2 <= plm_id < 0xC8BA: return "Blue-Opening"
    if 0xC8BA <= plm_id < 0xC8CE: return "Blue-Closing"
    return None

STATE_CODE_NAMES = {
    0xE5E6: "Default",
    0xE5EB: "Door",
    0xE5FF: "TourianBoss01",
    0xE612: "IsEventSet",
    0xE629: "IsBossDead",
    0xE640: "UNUSED_E640",
    0xE652: "MorphBallMissiles",
    0xE669: "PowerBombs",
    0xE678: "UNUSED_E678",
}


def snes_to_pc(snes_addr, has_header=False):
    bank = (snes_addr >> 16) & 0xFF
    offset = snes_addr & 0xFFFF
    header = 0x200 if has_header else 0
    return ((bank & 0x7F) * 0x8000) + (offset & 0x7FFF) + header


def pc_to_snes(pc_addr, has_header=False):
    pc = pc_addr - (0x200 if has_header else 0)
    bank = (pc // 0x8000) | 0x80
    offset = (pc % 0x8000) + 0x8000
    return (bank << 16) | offset


def read_u16(rom, pc):
    return struct.unpack_from('<H', rom, pc)[0]


def detect_header(rom_data):
    return len(rom_data) % 0x8000 == 0x200


def load_room_name(room_id):
    try:
        with open(ROOM_MAPPING) as f:
            data = json.load(f)
        rooms = data.get('rooms', data)
        for handle, info in rooms.items():
            rid_str = info.get('id', '')
            try:
                if int(rid_str, 16) == room_id:
                    return info.get('name', handle)
            except (ValueError, TypeError):
                pass
    except FileNotFoundError:
        pass
    return f"Room_0x{room_id:04X}"


def dump_room(rom, room_id, has_header):
    name = load_room_name(room_id)
    pc = snes_to_pc(0x8F0000 | room_id, has_header)

    print(f"{'='*70}")
    print(f"ROOM: 0x{room_id:04X} — {name}")
    print(f"{'='*70}")

    if pc < 0 or pc + 11 > len(rom):
        print("ERROR: Room header out of bounds")
        return

    # ── Room Header ─────────────────────────────────────────────────
    print(f"\n--- Room Header (PC 0x{pc:06X}, SNES $8F:{room_id:04X}) ---")
    header = {
        'index': rom[pc], 'area': rom[pc+1],
        'x': rom[pc+2], 'y': rom[pc+3],
        'w': rom[pc+4], 'h': rom[pc+5],
        'up_scroller': rom[pc+6], 'down_scroller': rom[pc+7],
        'special_gfx': rom[pc+8],
        'door_out': read_u16(rom, pc+9),
    }
    print(f"  Index={header['index']}, Area={header['area']}, "
          f"MapPos=({header['x']},{header['y']}), Size={header['w']}x{header['h']}")
    print(f"  Scrollers: up=0x{header['up_scroller']:02X}, down=0x{header['down_scroller']:02X}")
    print(f"  Special GFX: 0x{header['special_gfx']:02X}")
    print(f"  DoorOut ptr: 0x{header['door_out']:04X}")
    raw = ' '.join(f'{rom[pc+i]:02X}' for i in range(11))
    print(f"  Raw bytes: {raw}")

    # ── State Selector List ─────────────────────────────────────────
    print(f"\n--- State Selector List (PC 0x{pc+11:06X}) ---")
    pos = pc + 11
    max_pos = min(pos + 300, len(rom) - 1)
    state_offsets = []
    state_idx = 0

    while pos + 1 < max_pos:
        code = read_u16(rom, pos)
        code_name = STATE_CODE_NAMES.get(code, f"UNKNOWN_0x{code:04X}")

        if code == 0xE5E6:
            state_pc = pos + 2
            state_offsets.append(state_pc)
            raw = ' '.join(f'{rom[pos+i]:02X}' for i in range(2))
            print(f"  [{state_idx}] 0x{code:04X} ({code_name}) at PC 0x{pos:06X}")
            print(f"       → inline state data at PC 0x{state_pc:06X}")
            print(f"       Raw: {raw}")
            break
        elif code == 0xE5EB:
            door_ptr = read_u16(rom, pos+2)
            state_ptr = read_u16(rom, pos+4)
            spc = snes_to_pc(0x8F0000 | state_ptr, has_header)
            state_offsets.append(spc)
            raw = ' '.join(f'{rom[pos+i]:02X}' for i in range(6))
            print(f"  [{state_idx}] 0x{code:04X} ({code_name}) at PC 0x{pos:06X}")
            print(f"       door_ptr=0x{door_ptr:04X}, state_ptr=0x{state_ptr:04X} → PC 0x{spc:06X}")
            print(f"       Raw: {raw}")
            pos += 6
        elif code in (0xE612, 0xE629):
            flag = rom[pos+2]
            state_ptr = read_u16(rom, pos+3)
            spc = snes_to_pc(0x8F0000 | state_ptr, has_header)
            state_offsets.append(spc)
            raw = ' '.join(f'{rom[pos+i]:02X}' for i in range(5))
            print(f"  [{state_idx}] 0x{code:04X} ({code_name}) flag=0x{flag:02X} at PC 0x{pos:06X}")
            print(f"       state_ptr=0x{state_ptr:04X} → PC 0x{spc:06X}")
            print(f"       Raw: {raw}")
            pos += 5
        elif code in (0xE5FF, 0xE640, 0xE652, 0xE669, 0xE678):
            state_ptr = read_u16(rom, pos+2)
            spc = snes_to_pc(0x8F0000 | state_ptr, has_header)
            state_offsets.append(spc)
            raw = ' '.join(f'{rom[pos+i]:02X}' for i in range(4))
            print(f"  [{state_idx}] 0x{code:04X} ({code_name}) at PC 0x{pos:06X}")
            print(f"       state_ptr=0x{state_ptr:04X} → PC 0x{spc:06X}")
            print(f"       Raw: {raw}")
            pos += 4
        else:
            raw = ' '.join(f'{rom[pos+i]:02X}' for i in range(min(16, len(rom)-pos)))
            print(f"  [{state_idx}] UNKNOWN code 0x{code:04X} at PC 0x{pos:06X}")
            print(f"       Raw: {raw}")
            break
        state_idx += 1

    # ── State Data Blocks ───────────────────────────────────────────
    print(f"\n--- State Data Blocks ({len(state_offsets)} states) ---")
    for si, spc in enumerate(state_offsets):
        if spc + 26 > len(rom):
            print(f"  State {si}: OUT OF BOUNDS (PC 0x{spc:06X})")
            continue
        sd = {
            'level_data': read_u16(rom, spc),
            'tileset': rom[spc+2],
            'music_data': rom[spc+3],
            'music_track': rom[spc+4],
            'fx_ptr': read_u16(rom, spc+6),
            'enemy_set': read_u16(rom, spc+8),
            'enemy_gfx': read_u16(rom, spc+10),
            'bg_scroll': read_u16(rom, spc+12),
            'scrolls': read_u16(rom, spc+14),
            'special': read_u16(rom, spc+16),
            'main_asm': read_u16(rom, spc+18),
            'plm_set': read_u16(rom, spc+20),
            'bg_data': read_u16(rom, spc+22),
            'setup_asm': read_u16(rom, spc+24),
        }
        raw = ' '.join(f'{rom[spc+i]:02X}' for i in range(26))
        print(f"  State {si} at PC 0x{spc:06X}:")
        print(f"    level_data=0x{sd['level_data']:04X} tileset={sd['tileset']} "
              f"music={sd['music_data']:02X}/{sd['music_track']:02X}")
        print(f"    fx=0x{sd['fx_ptr']:04X} enemies=0x{sd['enemy_set']:04X} "
              f"enemy_gfx=0x{sd['enemy_gfx']:04X}")
        print(f"    bg_scroll=0x{sd['bg_scroll']:04X} scrolls=0x{sd['scrolls']:04X} "
              f"special=0x{sd['special']:04X}")
        print(f"    main_asm=0x{sd['main_asm']:04X} plm_set=0x{sd['plm_set']:04X} "
              f"bg_data=0x{sd['bg_data']:04X} setup_asm=0x{sd['setup_asm']:04X}")
        print(f"    Raw: {raw}")

    # ── Door Entries ────────────────────────────────────────────────
    door_out = header['door_out']
    print(f"\n--- Door Entries (door_out=0x{door_out:04X}) ---")
    if door_out == 0 or door_out == 0xFFFF:
        print("  No door-out list")
    else:
        door_list_pc = snes_to_pc(0x8F0000 | door_out, has_header)
        doors = []
        for i in range(20):
            if door_list_pc + 1 >= len(rom):
                break
            ptr = read_u16(rom, door_list_pc)
            # Vanilla door pointers in bank $83 are typically 0x8000+.
            # Terminate on zero, 0xFFFF, or suspiciously low pointers
            # (which indicate we've read past the end of the door-out list).
            if ptr == 0 or ptr == 0xFFFF or ptr < 0x8000:
                break
            entry_pc = snes_to_pc(0x830000 | ptr, has_header)
            if entry_pc + 11 >= len(rom):
                break
            # Additional sanity: dest_room should be a plausible $8F pointer
            dest_check = read_u16(rom, entry_pc)
            if dest_check < 0x9000 or dest_check > 0xF000:
                print(f"  Door {i}: SKIPPED — dest=0x{dest_check:04X} looks invalid (ptr=0x{ptr:04X})")
                break
            d = rom[entry_pc:entry_pc+12]
            dest = read_u16(rom, entry_pc)
            orient = d[3]
            direction = DIRECTIONS.get(orient & 3, '?')
            cap_flag = bool(orient & 4)
            x_plm = d[4]
            y_plm = d[5]
            raw = ' '.join(f'{b:02X}' for b in d)

            dest_name = load_room_name(dest)
            print(f"  Door {i} [bank$83 ptr=0x{ptr:04X}, PC=0x{entry_pc:06X}]:")
            print(f"    dest=0x{dest:04X} ({dest_name})")
            print(f"    orient={orient} ({direction}, cap_flag={cap_flag})")
            print(f"    plm_pos=({x_plm},{y_plm}) screen=({d[6]},{d[7]})")
            print(f"    dist=0x{read_u16(rom, entry_pc+8):04X} entry_code=0x{read_u16(rom, entry_pc+10):04X}")
            print(f"    Raw: {raw}")
            doors.append({'idx': i, 'orient': orient, 'cap_flag': cap_flag,
                         'x_plm': x_plm, 'y_plm': y_plm})
            door_list_pc += 2

    # ── PLM Sets (all states) ──────────────────────────────────────
    plm_ptrs_seen = set()
    for si, spc in enumerate(state_offsets):
        if spc + 26 > len(rom):
            continue
        plm_ptr = read_u16(rom, spc + 20)
        if plm_ptr in plm_ptrs_seen or plm_ptr == 0 or plm_ptr == 0xFFFF:
            continue
        plm_ptrs_seen.add(plm_ptr)

        plm_pc = snes_to_pc(0x8F0000 | plm_ptr, has_header)
        print(f"\n--- PLM Set 0x{plm_ptr:04X} (state {si}, PC 0x{plm_pc:06X}) ---")
        plm_idx = 0
        while plm_pc + 5 < len(rom):
            plm_id = read_u16(rom, plm_pc)
            if plm_id == 0:
                print(f"  (end of PLM list, {plm_idx} entries)")
                break
            x = rom[plm_pc + 2]
            y = rom[plm_pc + 3]
            param = read_u16(rom, plm_pc + 4)
            cap_info = door_cap_info(plm_id)
            tag = f" *** {cap_info} ***" if cap_info else ""
            print(f"  [{plm_idx}] PLM 0x{plm_id:04X} at ({x},{y}) param=0x{param:04X}{tag}")
            plm_pc += 6
            plm_idx += 1

    print()


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <rom_path> <room_id>")
        print(f"  room_id examples: 0x91F8 (Landing Site), 0x92FD (Parlor)")
        sys.exit(1)

    rom_path = sys.argv[1]
    room_id = int(sys.argv[2], 16) if sys.argv[2].startswith('0x') else int(sys.argv[2])

    with open(rom_path, 'rb') as f:
        rom = f.read()

    has_header = detect_header(rom)
    print(f"ROM: {rom_path} ({len(rom)} bytes, header={'yes' if has_header else 'no'})")
    dump_room(rom, room_id, has_header)


if __name__ == '__main__':
    main()
