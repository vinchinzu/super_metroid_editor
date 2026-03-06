"""
Environment setup utilities for stable-retro games.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Optional

import stable_retro as retro


def add_custom_integrations(game_dir: str | Path) -> Path:
    """Add custom integrations path for a game directory.

    Args:
        game_dir: Path to the game directory containing custom_integrations/

    Returns:
        Path to the custom_integrations directory
    """
    game_dir = Path(game_dir).resolve()
    integrations_path = game_dir / "custom_integrations"
    if integrations_path.exists():
        retro.data.Integrations.add_custom_path(str(integrations_path))
    return integrations_path


def make_env(
    game: str,
    state: str,
    game_dir: str | Path,
    render_mode: str = "rgb_array",
    players: Optional[int] = None,
    **kwargs,
) -> retro.RetroEnv:
    """Create a stable-retro environment with custom integrations.

    This automatically:
    - Adds the custom_integrations path for the game
    - Uses CUSTOM_ONLY inttype to find ROMs in custom_integrations

    Args:
        game: Game identifier (e.g., "DonkeyKongCountry-Snes")
        state: State name (e.g., "1Player.CongoJungle.JungleHijinks.Level1")
        game_dir: Path to the game directory containing custom_integrations/
        render_mode: Render mode ("rgb_array" or "human")
        **kwargs: Additional arguments passed to retro.make()

    Returns:
        Configured RetroEnv instance
    """
    add_custom_integrations(game_dir)

    # Handle special state values
    if state == "NONE":
        state = retro.State.NONE

    make_kwargs = dict(
        game=game,
        state=state,
        render_mode=render_mode,
        inttype=retro.data.Integrations.CUSTOM_ONLY,
        **kwargs,
    )
    if players is not None:
        make_kwargs["players"] = players

    try:
        return retro.make(**make_kwargs)
    except TypeError:
        # Fallback for retro versions without players arg
        make_kwargs.pop("players", None)
        return retro.make(**make_kwargs)


def get_available_states(game: str, game_dir: str | Path) -> list[str]:
    """List available save states for a game.

    Args:
        game: Game identifier
        game_dir: Path to the game directory

    Returns:
        List of state names (without .state extension)
    """
    game_dir = Path(game_dir)
    integrations_path = game_dir / "custom_integrations" / game

    if not integrations_path.exists():
        return []

    states = []
    for state_file in integrations_path.glob("*.state"):
        states.append(state_file.stem)
    return sorted(states)


def save_state(env: retro.RetroEnv, game_dir: str | Path, game: str, name: str) -> Path:
    """Save current emulator state to the game's custom integrations directory.

    Args:
        env: active RetroEnv
        game_dir: Path to game directory
        game: Game identifier (e.g., "DonkeyKongCountry-Snes")
        name: State base name (without .state)

    Returns:
        Path to the saved state in custom_integrations
    """
    import gzip

    state_data = env.em.get_state()
    filename = f"{name}.state"
    game_dir = Path(game_dir)
    save_path = game_dir / "custom_integrations" / game / filename
    save_path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(save_path, "wb") as f:
        f.write(state_data)
    return save_path
