"""Discrete PPO action map vendored for the editor bridge."""

from __future__ import annotations


_B = 0
_Y = 1
_SELECT = 2
_START = 3
_UP = 4
_DOWN = 5
_LEFT = 6
_RIGHT = 7
_A = 8
_X = 9
_L = 10
_R = 11


DISCRETE_ACTIONS = [
    {_LEFT: 1}, {_RIGHT: 1},
    {_LEFT: 1, _X: 1}, {_RIGHT: 1, _X: 1},
    {_X: 1},
    {_UP: 1, _X: 1}, {_UP: 1, _LEFT: 1, _X: 1}, {_UP: 1, _RIGHT: 1, _X: 1},
    {_A: 1, _X: 1}, {_A: 1}, {_A: 1, _LEFT: 1}, {_A: 1, _RIGHT: 1},
    {_B: 1, _LEFT: 1}, {_B: 1, _RIGHT: 1},
    {_DOWN: 1}, {_DOWN: 1, _X: 1}, {_DOWN: 1, _LEFT: 1}, {_DOWN: 1, _RIGHT: 1},
    {_A: 1, _UP: 1, _X: 1}, {_A: 1, _LEFT: 1, _X: 1}, {_A: 1, _RIGHT: 1, _X: 1},
    {_B: 1, _LEFT: 1, _X: 1}, {_B: 1, _RIGHT: 1, _X: 1},
    {_B: 1, _A: 1, _LEFT: 1}, {_B: 1, _A: 1, _RIGHT: 1},
    {},
]
