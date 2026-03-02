#!/usr/bin/env python3
"""
Compare door entries and PLM sets between two Super Metroid ROMs.

Identifies differences in DoorDef structures, door_orientation cap flags,
PLM sets, and level data door blocks across all rooms or specific rooms.

Usage:
    python3 compare_doors.py <vanilla_rom> <modified_rom> [room_ids...]

Examples:
    python3 compare_doors.py vanilla.smc edited.smc
    python3 compare_doors.py vanilla.smc edited.smc 0x92FD 0xDD58 0xDE7A

References:
    - ~/code/sm/src/ida_types.h:260  DoorDef structure (12 bytes)
    - ~/code/sm/src/sm_82.c:4257     SpawnDoorClosingPLM
    - Door cap PLM IDs: 0xC842-0xC8CD (opening), 0xC8BA-0xC8C6 (closing)
"""
import json
import struct
import sys
import os
from pathlib import Path
from collections import defaultdict

SCRIPT_DIR = Path(__file__).parent.parent.parent
ROOM_MAPPING = SCRIPT_DIR / "shared/src/commonMain/resources/room_mapping_complete.json"

# Door cap PLM ranges
DOOR_CAP_PLMS = set(range(0xC842, 0xC8CE))
OPENING_CAPS = set(range(0xC842, 0xC8BA))
CLOSING_CAPS = set(range(0xC8BA, 0xC8CE))

DOOR_CAP_COLORS = {
    range(0xC842, 0xC85A): "Grey",
    range(0xC85A, 0xC872): "Yellow",
    range(0xC872, 0xC88A): "Green",
    range(0xC88A, 0xC8A2): "Red",
    range(0xC8A2, 0xC8BA): "Blue-Opening",
    range(0xC8BA, 0xC8C7): "Blue-Closing",
    range(0xC8C7, 0xC8CE): "Blue-Closing",
}

DIRECTIONS = {0: "Right", 1: "Left", 2: "Down", 3: "Up"}


def snes_to_pc(snes_addr, has_header=False):
    bank = (snes_addr >> 16) & 0xFF
    offset = snes_addr & 0xFFFF
    header = 0x200 if has_header else 0
    return ((bank & 0x7F) * 0x8000) + (offset & 0x7FFF) + header


def read_u16(rom, pc):
    return struct.unpack_from('<H', rom, pc)[0]


def detect_header(rom_data):
    return len(rom_data) % 0x8000 == 0x200


def get_door_cap_color(plm_id):
    for r, color in DOOR_CAP_COLORS.items():
        if plm_id in r:
            return color
    return None


def load_room_ids(mapping_path):
    with open(mapping_path) as f:
        data = json.load(f)
    rooms = data.get('rooms', data)
    result = {}
    for handle, info in rooms.items():
        rid_str = info.get('id', '')
        name = info.get('name', handle)
        try:
            rid = int(rid_str, 16)
            result[rid] = name
        except (ValueError, TypeError):
            pass
    return result


def parse_room_header(rom, room_id, has_header):
    pc = snes_to_pc(0x8F0000 | room_id, has_header)
    if pc < 0 or pc + 11 > len(rom):
        return None
    return {
        'index': rom[pc],
        'area': rom[pc + 1],
        'x': rom[pc + 2],
        'y': rom[pc + 3],
        'w': rom[pc + 4],
        'h': rom[pc + 5],
        'up_scroller': rom[pc + 6],
        'down_scroller': rom[pc + 7],
        'special_gfx': rom[pc + 8],
        'door_out': read_u16(rom, pc + 9),
    }


def parse_door_list(rom, door_out_ptr, has_header, max_doors=20):
    """Parse door-out list (2-byte pointers to bank $83 DoorDef entries)."""
    if door_out_ptr == 0 or door_out_ptr == 0xFFFF:
        return []
    pc = snes_to_pc(0x8F0000 | door_out_ptr, has_header)
    doors = []
    for i in range(max_doors):
        if pc + 1 >= len(rom):
            break
        ptr = read_u16(rom, pc)
        if ptr == 0 or ptr == 0xFFFF or ptr < 0x8000:
            break
        entry_pc = snes_to_pc(0x830000 | ptr, has_header)
        if entry_pc + 11 >= len(rom):
            break
        dest_check = read_u16(rom, entry_pc)
        if dest_check < 0x9000 or dest_check > 0xF000:
            break
        door = {
            'ptr': ptr,
            'dest_room': read_u16(rom, entry_pc),
            'bitflag': rom[entry_pc + 2],
            'orientation': rom[entry_pc + 3],
            'x_pos_plm': rom[entry_pc + 4],
            'y_pos_plm': rom[entry_pc + 5],
            'screen_x': rom[entry_pc + 6],
            'screen_y': rom[entry_pc + 7],
            'dist_from_door': read_u16(rom, entry_pc + 8),
            'entry_code': read_u16(rom, entry_pc + 10),
        }
        door['direction'] = DIRECTIONS.get(door['orientation'] & 3, '?')
        door['cap_flag'] = bool(door['orientation'] & 4)
        doors.append(door)
        pc += 2
    return doors


def find_all_state_data_offsets(rom, room_id, has_header):
    """Parse all state data offsets for a room (same logic as RomParser.kt)."""
    pc = snes_to_pc(0x8F0000 | room_id, has_header)
    if pc < 0 or pc + 11 > len(rom):
        return []
    pos = pc + 11
    max_pos = min(pos + 300, len(rom) - 1)
    results = []

    while pos + 1 < max_pos:
        code = read_u16(rom, pos)
        if code == 0xE5E6:
            state_pc = pos + 2
            if state_pc + 26 <= len(rom):
                results.append(state_pc)
            return results
        elif code == 0xE5EB:
            if pos + 5 < len(rom):
                sp = read_u16(rom, pos + 4)
                spc = snes_to_pc(0x8F0000 | sp, has_header)
                if spc + 26 <= len(rom):
                    results.append(spc)
            pos += 6
        elif code in (0xE612, 0xE629):
            if pos + 4 < len(rom):
                sp = read_u16(rom, pos + 3)
                spc = snes_to_pc(0x8F0000 | sp, has_header)
                if spc + 26 <= len(rom):
                    results.append(spc)
            pos += 5
        elif code in (0xE5FF, 0xE640, 0xE652, 0xE669, 0xE678):
            if pos + 3 < len(rom):
                sp = read_u16(rom, pos + 2)
                spc = snes_to_pc(0x8F0000 | sp, has_header)
                if spc + 26 <= len(rom):
                    results.append(spc)
            pos += 4
        else:
            return results
    return results


def parse_plm_set(rom, plm_set_ptr, has_header):
    """Parse PLM entries. Each is 6 bytes: id(2), x(1), y(1), param(2). Terminated by id=0."""
    if plm_set_ptr == 0 or plm_set_ptr == 0xFFFF:
        return []
    pc = snes_to_pc(0x8F0000 | plm_set_ptr, has_header)
    plms = []
    for _ in range(200):
        if pc + 5 >= len(rom):
            break
        plm_id = read_u16(rom, pc)
        if plm_id == 0:
            break
        x = rom[pc + 2]
        y = rom[pc + 3]
        param = read_u16(rom, pc + 4)
        plms.append({'id': plm_id, 'x': x, 'y': y, 'param': param})
        pc += 6
    return plms


def read_state_data(rom, state_pc):
    """Read the 26-byte state data block."""
    return {
        'level_data': read_u16(rom, state_pc),
        'tileset': rom[state_pc + 2],
        'music_data': rom[state_pc + 3],
        'music_track': rom[state_pc + 4],
        'fx_ptr': read_u16(rom, state_pc + 6),
        'enemy_set_ptr': read_u16(rom, state_pc + 8),
        'enemy_gfx_ptr': read_u16(rom, state_pc + 10),
        'bg_scrolling': read_u16(rom, state_pc + 12),
        'room_scrolls_ptr': read_u16(rom, state_pc + 14),
        'room_special_ptr': read_u16(rom, state_pc + 16),
        'main_asm_ptr': read_u16(rom, state_pc + 18),
        'plm_set_ptr': read_u16(rom, state_pc + 20),
        'bg_data_ptr': read_u16(rom, state_pc + 22),
        'setup_asm_ptr': read_u16(rom, state_pc + 24),
    }


def compare_room(rom1, rom2, room_id, has_header1, has_header2, name):
    """Compare a single room between two ROMs. Returns list of difference strings."""
    diffs = []

    hdr1 = parse_room_header(rom1, room_id, has_header1)
    hdr2 = parse_room_header(rom2, room_id, has_header2)
    if not hdr1 or not hdr2:
        return ["Could not parse room header in one or both ROMs"]

    # Compare door entries
    doors1 = parse_door_list(rom1, hdr1['door_out'], has_header1)
    doors2 = parse_door_list(rom2, hdr2['door_out'], has_header2)

    if len(doors1) != len(doors2):
        diffs.append(f"  Door count differs: {len(doors1)} vs {len(doors2)}")

    for i in range(max(len(doors1), len(doors2))):
        d1 = doors1[i] if i < len(doors1) else None
        d2 = doors2[i] if i < len(doors2) else None
        if d1 is None:
            diffs.append(f"  Door {i}: only in modified ROM — dest=0x{d2['dest_room']:04X}")
            continue
        if d2 is None:
            diffs.append(f"  Door {i}: only in vanilla ROM — dest=0x{d1['dest_room']:04X}")
            continue

        changes = []
        if d1['dest_room'] != d2['dest_room']:
            changes.append(f"dest 0x{d1['dest_room']:04X}→0x{d2['dest_room']:04X}")
        if d1['orientation'] != d2['orientation']:
            changes.append(f"orient {d1['orientation']}→{d2['orientation']} "
                         f"(cap_flag {d1['cap_flag']}→{d2['cap_flag']})")
        if d1['x_pos_plm'] != d2['x_pos_plm'] or d1['y_pos_plm'] != d2['y_pos_plm']:
            changes.append(f"plm_pos ({d1['x_pos_plm']},{d1['y_pos_plm']})→({d2['x_pos_plm']},{d2['y_pos_plm']})")
        if d1['screen_x'] != d2['screen_x'] or d1['screen_y'] != d2['screen_y']:
            changes.append(f"screen ({d1['screen_x']},{d1['screen_y']})→({d2['screen_x']},{d2['screen_y']})")
        if d1['dist_from_door'] != d2['dist_from_door']:
            changes.append(f"dist 0x{d1['dist_from_door']:04X}→0x{d2['dist_from_door']:04X}")
        if d1['entry_code'] != d2['entry_code']:
            changes.append(f"entry 0x{d1['entry_code']:04X}→0x{d2['entry_code']:04X}")
        if d1['bitflag'] != d2['bitflag']:
            changes.append(f"bitflag 0x{d1['bitflag']:02X}→0x{d2['bitflag']:02X}")

        if changes:
            diffs.append(f"  Door {i} [ptr=0x{d1['ptr']:04X}]: {'; '.join(changes)}")

    # Compare PLM sets across all states
    states1 = find_all_state_data_offsets(rom1, room_id, has_header1)
    states2 = find_all_state_data_offsets(rom2, room_id, has_header2)

    if len(states1) != len(states2):
        diffs.append(f"  State count differs: {len(states1)} vs {len(states2)}")

    plm_ptrs_seen = set()
    for si in range(max(len(states1), len(states2))):
        sd1 = read_state_data(rom1, states1[si]) if si < len(states1) else None
        sd2 = read_state_data(rom2, states2[si]) if si < len(states2) else None

        if sd1 and sd2:
            ptr1 = sd1['plm_set_ptr']
            ptr2 = sd2['plm_set_ptr']
            key = (ptr1, ptr2)
            if key in plm_ptrs_seen:
                continue
            plm_ptrs_seen.add(key)

            plms1 = parse_plm_set(rom1, ptr1, has_header1)
            plms2 = parse_plm_set(rom2, ptr2, has_header2)

            caps1 = [p for p in plms1 if p['id'] in DOOR_CAP_PLMS]
            caps2 = [p for p in plms2 if p['id'] in DOOR_CAP_PLMS]

            if len(plms1) != len(plms2):
                diffs.append(f"  State {si} PLM count: {len(plms1)} vs {len(plms2)} "
                           f"(door caps: {len(caps1)} vs {len(caps2)})")

            set1 = {(p['id'], p['x'], p['y'], p['param']) for p in plms1}
            set2 = {(p['id'], p['x'], p['y'], p['param']) for p in plms2}
            added = set2 - set1
            removed = set1 - set2

            for pid, x, y, param in sorted(added):
                color = get_door_cap_color(pid) or ""
                cat = "cap" if pid in DOOR_CAP_PLMS else "plm"
                diffs.append(f"  State {si} PLM ADDED:   {cat} 0x{pid:04X} {color} at ({x},{y}) param=0x{param:04X}")
            for pid, x, y, param in sorted(removed):
                color = get_door_cap_color(pid) or ""
                cat = "cap" if pid in DOOR_CAP_PLMS else "plm"
                diffs.append(f"  State {si} PLM REMOVED: {cat} 0x{pid:04X} {color} at ({x},{y}) param=0x{param:04X}")

    return diffs


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <vanilla_rom> <modified_rom> [room_ids...]")
        sys.exit(1)

    rom1_path = sys.argv[1]
    rom2_path = sys.argv[2]
    room_filter = []
    for arg in sys.argv[3:]:
        try:
            room_filter.append(int(arg, 16) if arg.startswith('0x') else int(arg))
        except ValueError:
            pass

    with open(rom1_path, 'rb') as f:
        rom1 = f.read()
    with open(rom2_path, 'rb') as f:
        rom2 = f.read()

    hdr1 = detect_header(rom1)
    hdr2 = detect_header(rom2)

    print(f"ROM A: {rom1_path} ({len(rom1)} bytes, header={'yes' if hdr1 else 'no'})")
    print(f"ROM B: {rom2_path} ({len(rom2)} bytes, header={'yes' if hdr2 else 'no'})")

    room_names = load_room_ids(ROOM_MAPPING)

    if room_filter:
        room_list = [(rid, room_names.get(rid, f"Room_0x{rid:04X}")) for rid in room_filter]
    else:
        room_list = sorted(room_names.items())

    print(f"Comparing {len(room_list)} rooms...")
    print()

    total_diffs = 0
    rooms_with_diffs = 0

    for room_id, name in room_list:
        diffs = compare_room(rom1, rom2, room_id, hdr1, hdr2, name)
        if diffs:
            rooms_with_diffs += 1
            total_diffs += len(diffs)
            print(f"Room 0x{room_id:04X} ({name}):")
            for d in diffs:
                print(d)
            print()

    if total_diffs == 0:
        print("No door/PLM differences found between the two ROMs.")
    else:
        print(f"{'='*60}")
        print(f"Total: {total_diffs} differences across {rooms_with_diffs} rooms")


if __name__ == '__main__':
    main()
