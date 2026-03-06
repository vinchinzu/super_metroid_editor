"""
Shared input handling for SNES games via stable-retro.

Provides consistent keyboard and controller mappings across all games.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Optional

if TYPE_CHECKING:
    import pygame

# SNES Button Map: [B, Y, Select, Start, Up, Down, Left, Right, A, X, L, R]
SNES_B = 0
SNES_Y = 1
SNES_SELECT = 2
SNES_START = 3
SNES_UP = 4
SNES_DOWN = 5
SNES_LEFT = 6
SNES_RIGHT = 7
SNES_A = 8
SNES_X = 9
SNES_L = 10
SNES_R = 11

# Xbox-style controller mapping -> SNES buttons
CONTROLLER_MAP = {
    0: SNES_B,      # A -> B
    1: SNES_A,      # B -> A
    2: SNES_Y,      # X -> Y
    3: SNES_X,      # Y -> X
    4: SNES_L,      # LB -> L
    5: SNES_R,      # RB -> R
    6: SNES_SELECT, # Back -> Select
    7: SNES_START,  # Start -> Start
}

# Fallback mapping for controllers with shifted indices (e.g., some SDL layouts)
CONTROLLER_MAP_FALLBACK = {
    8: SNES_SELECT,  # Guide/Select alternative
    9: SNES_START,   # Start alternative
    10: SNES_SELECT, # Alt select (some SDL layouts: Share/Capture)
    11: SNES_START,  # Alt start (some SDL layouts: Options/Menu)
    12: SNES_SELECT, # Extra select (some SDL layouts: touchpad/extra)
    13: SNES_START,  # Extra start (some SDL layouts: options/extra)
    14: SNES_SELECT, # Extra select (additional SDL variants)
    15: SNES_START,  # Extra start (additional SDL variants)
}


def init_controller(pygame) -> Optional["pygame.joystick.Joystick"]:
    """Initialize first available controller."""
    pygame.joystick.init()
    if pygame.joystick.get_count() > 0:
        joy = pygame.joystick.Joystick(0)
        joy.init()
        return joy
    return None


def init_controllers(pygame) -> list["pygame.joystick.Joystick"]:
    """Initialize all available controllers."""
    pygame.joystick.init()
    controllers: list["pygame.joystick.Joystick"] = []
    for i in range(pygame.joystick.get_count()):
        joy = pygame.joystick.Joystick(i)
        joy.init()
        controllers.append(joy)
    return controllers


def _apply_controller_action(
    joystick: Optional["pygame.joystick.Joystick"],
    action: list[int],
    offset: int = 0,
    controller_map: Optional[dict[int, int]] = None,
    fallback_map: dict[int, int] = CONTROLLER_MAP_FALLBACK,
    start_override: Optional[int] = None,
    select_override: Optional[int] = None,
    start_action_index: Optional[int] = None,
    select_action_index: Optional[int] = None,
    axis_map: Optional[dict[int, int]] = None,
    deadzone: float = 0.5,
) -> None:
    """Update action array from controller input with optional offset."""
    if joystick is None:
        return

    # D-pad via hat
    if joystick.get_numhats() > 0:
        hat = joystick.get_hat(0)
        if hat[0] < 0:
            action[offset + SNES_LEFT] = 1
        if hat[0] > 0:
            action[offset + SNES_RIGHT] = 1
        if hat[1] > 0:
            action[offset + SNES_UP] = 1
        if hat[1] < 0:
            action[offset + SNES_DOWN] = 1

    # D-pad via analog stick
    if joystick.get_numaxes() >= 2:
        axis_x = joystick.get_axis(0)
        axis_y = joystick.get_axis(1)
        if axis_x < -deadzone:
            action[offset + SNES_LEFT] = 1
        if axis_x > deadzone:
            action[offset + SNES_RIGHT] = 1
        if axis_y < -deadzone:
            action[offset + SNES_UP] = 1
        if axis_y > deadzone:
            action[offset + SNES_DOWN] = 1

    # Buttons
    active_map = controller_map or CONTROLLER_MAP
    for joy_btn, snes_btn in active_map.items():
        if joy_btn < joystick.get_numbuttons() and joystick.get_button(joy_btn):
            target = snes_btn
            if snes_btn == SNES_START and start_action_index is not None:
                target = start_action_index
            elif snes_btn == SNES_SELECT and select_action_index is not None:
                target = select_action_index
            action[offset + target] = 1
    for joy_btn, snes_btn in fallback_map.items():
        if joy_btn < joystick.get_numbuttons() and joystick.get_button(joy_btn):
            target = snes_btn
            if snes_btn == SNES_START and start_action_index is not None:
                target = start_action_index
            elif snes_btn == SNES_SELECT and select_action_index is not None:
                target = select_action_index
            action[offset + target] = 1
    if start_override is not None:
        if start_override < joystick.get_numbuttons() and joystick.get_button(start_override):
            target = start_action_index if start_action_index is not None else SNES_START
            action[offset + target] = 1
    if select_override is not None:
        if select_override < joystick.get_numbuttons() and joystick.get_button(select_override):
            target = select_action_index if select_action_index is not None else SNES_SELECT
            action[offset + target] = 1
    if axis_map:
        for axis_idx, snes_btn in axis_map.items():
            if axis_idx < joystick.get_numaxes():
                if joystick.get_axis(axis_idx) > deadzone:
                    action[offset + snes_btn] = 1


def controller_action(
    joystick: Optional["pygame.joystick.Joystick"],
    action: list[int],
    offset: int = 0,
    start_override: Optional[int] = None,
    select_override: Optional[int] = None,
    start_action_index: Optional[int] = None,
    select_action_index: Optional[int] = None,
    controller_map: Optional[dict[int, int]] = None,
    axis_map: Optional[dict[int, int]] = None,
) -> None:
    """Update action array from controller input."""
    _apply_controller_action(
        joystick,
        action,
        offset=offset,
        start_override=start_override,
        select_override=select_override,
        start_action_index=start_action_index,
        select_action_index=select_action_index,
        controller_map=controller_map,
        axis_map=axis_map,
    )


def keyboard_action(
    keys,
    action: list[int],
    pygame,
    start_action_index: Optional[int] = None,
    select_action_index: Optional[int] = None,
) -> None:
    """Update action array from keyboard input.

    Default mapping:
    - Arrow keys: D-pad
    - Z: B (run/cancel)
    - X: A (jump/confirm)
    - A: Y (alternate action)
    - S: X (alternate action)
    - Q: L shoulder
    - W: R shoulder
    - Enter: Start
    - Shift: Select
    """
    if keys[pygame.K_RIGHT]:
        action[SNES_RIGHT] = 1
    if keys[pygame.K_LEFT]:
        action[SNES_LEFT] = 1
    if keys[pygame.K_DOWN]:
        action[SNES_DOWN] = 1
    if keys[pygame.K_UP]:
        action[SNES_UP] = 1

    if keys[pygame.K_RETURN]:
        start_idx = start_action_index if start_action_index is not None else SNES_START
        action[start_idx] = 1
    if keys[pygame.K_RSHIFT] or keys[pygame.K_LSHIFT]:
        select_idx = select_action_index if select_action_index is not None else SNES_SELECT
        action[select_idx] = 1

    if keys[pygame.K_z]:
        action[SNES_B] = 1
    if keys[pygame.K_x]:
        action[SNES_A] = 1
    if keys[pygame.K_a]:
        action[SNES_Y] = 1
    if keys[pygame.K_s]:
        action[SNES_X] = 1
    if keys[pygame.K_q]:
        action[SNES_L] = 1
    if keys[pygame.K_w]:
        action[SNES_R] = 1


def sanitize_action_offset(action: list[int], offset: int = 0) -> None:
    """Remove conflicting directional inputs for a player slice."""
    if action[offset + SNES_LEFT] and action[offset + SNES_RIGHT]:
        action[offset + SNES_LEFT] = 0
        action[offset + SNES_RIGHT] = 0
    if action[offset + SNES_UP] and action[offset + SNES_DOWN]:
        action[offset + SNES_UP] = 0
        action[offset + SNES_DOWN] = 0


def sanitize_action(action: list[int]) -> None:
    """Remove conflicting directional inputs."""
    sanitize_action_offset(action, offset=0)


def sanitize_action_multi(action: list[int], players: int = 2, stride: int = 12) -> None:
    """Remove conflicting directional inputs across multiple players."""
    for idx in range(players):
        start = idx * stride
        if start + stride <= len(action):
            sanitize_action_offset(action, offset=start)
