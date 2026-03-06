"""Editor-focused route planning and path comparison helpers.

This keeps the route math isolated from the bridge so the editor can reuse
the same planned path, autoplay seed, and planned-vs-actual comparison data.
"""

from __future__ import annotations

from dataclasses import dataclass
import json
import math
from pathlib import Path


LANDING_SITE_ROOM_ID = 0x91F8
PARLOR_ROOM_ID = 0x92FD
LANDING_SITE_ENTRY = (1100, 900)
EXPECTED_ROUTE_DIRNAME = "expected_routes"


@dataclass(frozen=True)
class RouteTracePoint:
    room_id: int
    x: int
    y: int
    frame: int

    def to_dict(self) -> dict[str, int]:
        return {
            "frame": self.frame,
            "roomId": self.room_id,
            "x": self.x,
            "y": self.y,
        }


@dataclass(frozen=True)
class EditorRoutePlan:
    route_id: str
    label: str
    source: str
    start_state: str
    start_room_id: int
    target_room_id: int
    waypoints: list[tuple[float, float]]
    trace_points: list[RouteTracePoint]
    autoplay_buttons: list[list[int]]

    def expected_trace(self) -> list[dict[str, int]]:
        return [point.to_dict() for point in self.trace_points]


@dataclass(frozen=True)
class RouteMetrics:
    progress: float
    max_progress: float
    completion: float
    error_px: float
    best_error_px: float
    route_completed: bool

    def to_dict(self) -> dict[str, float | bool]:
        return {
            "progress": self.progress,
            "maxProgress": self.max_progress,
            "completion": self.completion,
            "errorPx": self.error_px,
            "bestErrorPx": self.best_error_px,
            "routeCompleted": self.route_completed,
        }


class RouteTracker:
    """Track position against a dense planned path."""

    def __init__(self, trace_points: list[RouteTracePoint]) -> None:
        if len(trace_points) < 2:
            raise ValueError("Need at least 2 trace points")
        self._trace_points = trace_points
        self._segment_lengths: list[float] = []
        self._cumulative_lengths = [0.0]
        total = 0.0
        for index in range(len(trace_points) - 1):
            current = trace_points[index]
            nxt = trace_points[index + 1]
            if current.room_id != nxt.room_id:
                seg_len = 0.0
            else:
                seg_len = math.hypot(nxt.x - current.x, nxt.y - current.y)
            self._segment_lengths.append(seg_len)
            total += seg_len
            self._cumulative_lengths.append(total)
        self._total_length = max(total, 1.0)
        self._max_progress = 0.0
        self._best_error_px = math.inf
        self._final_room_id = trace_points[-1].room_id

    @property
    def total_length(self) -> float:
        return self._total_length

    def supports_room(self, room_id: int) -> bool:
        return any(point.room_id == room_id for point in self._trace_points)

    def update(self, room_id: int, x: int | float, y: int | float) -> RouteMetrics:
        px = float(x)
        py = float(y)
        best_along = 0.0
        best_error = math.inf

        for index in range(len(self._trace_points) - 1):
            current = self._trace_points[index]
            nxt = self._trace_points[index + 1]
            if current.room_id != room_id or nxt.room_id != room_id:
                continue
            ax = float(current.x)
            ay = float(current.y)
            bx = float(nxt.x)
            by = float(nxt.y)
            dx = bx - ax
            dy = by - ay
            seg_len_sq = dx * dx + dy * dy
            if seg_len_sq <= 0.0:
                proj_t = 0.0
            else:
                proj_t = ((px - ax) * dx + (py - ay) * dy) / seg_len_sq
            proj_t = max(0.0, min(1.0, proj_t))
            proj_x = ax + dx * proj_t
            proj_y = ay + dy * proj_t
            error = math.hypot(px - proj_x, py - proj_y)
            if error < best_error:
                best_error = error
                best_along = self._cumulative_lengths[index] + (self._segment_lengths[index] * proj_t)

        if math.isinf(best_error):
            for index, point in enumerate(self._trace_points):
                if point.room_id != room_id:
                    continue
                error = math.hypot(px - float(point.x), py - float(point.y))
                if error < best_error:
                    best_error = error
                    best_along = self._cumulative_lengths[min(index, len(self._cumulative_lengths) - 1)]

        progress = best_along / self._total_length
        self._max_progress = max(self._max_progress, progress)
        self._best_error_px = min(self._best_error_px, best_error)
        return RouteMetrics(
            progress=progress,
            max_progress=self._max_progress,
            completion=self._max_progress,
            error_px=best_error,
            best_error_px=self._best_error_px,
            route_completed=self._max_progress >= 0.995 and best_error <= 24.0 and room_id == self._final_room_id,
        )


def densify_path(
    waypoints: list[tuple[float, float]],
    *,
    step_px: float = 16.0,
) -> list[tuple[int, int]]:
    """Interpolate a waypoint polyline into a dense trace."""
    if not waypoints:
        return []
    dense: list[tuple[int, int]] = [(int(round(waypoints[0][0])), int(round(waypoints[0][1])))]
    for index in range(len(waypoints) - 1):
        ax, ay = waypoints[index]
        bx, by = waypoints[index + 1]
        dx = bx - ax
        dy = by - ay
        dist = math.hypot(dx, dy)
        if dist <= 0.0:
            continue
        segments = max(1, int(math.ceil(dist / step_px)))
        for segment in range(1, segments + 1):
            t = segment / segments
            point = (int(round(ax + dx * t)), int(round(ay + dy * t)))
            if point != dense[-1]:
                dense.append(point)
    return dense


def _parse_int(value: object, default: int) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value, 0)
        except ValueError:
            return default
    return default


def _load_published_route_plan(
    *,
    nav_export_dir: Path,
    route_id: str,
    state_name: str,
    start_room_id: int,
    target_room_id: int,
    fallback_label: str,
) -> EditorRoutePlan | None:
    route_asset = nav_export_dir.parent / EXPECTED_ROUTE_DIRNAME / f"{route_id}.json"
    if not route_asset.is_file():
        return None
    try:
        data = json.loads(route_asset.read_text(encoding="utf-8"))
    except Exception:
        return None

    asset_state_name = str(data.get("startState") or state_name)
    if asset_state_name != state_name:
        return None

    asset_start_room_id = _parse_int(data.get("startRoomId"), start_room_id)
    raw_trace_points = data.get("tracePoints") or []
    trace_points: list[RouteTracePoint] = []
    for point in raw_trace_points:
        point_frame = len(trace_points) + 1
        point_room_id = asset_start_room_id
        if isinstance(point, dict):
            point_room_id = _parse_int(point.get("roomId"), asset_start_room_id)
            point_frame = _parse_int(point.get("frame"), point_frame)
            x = point.get("x")
            y = point.get("y")
        elif isinstance(point, (list, tuple)) and len(point) >= 2:
            x, y = point[0], point[1]
        else:
            continue
        try:
            parsed_point = RouteTracePoint(
                room_id=point_room_id,
                x=int(x),
                y=int(y),
                frame=point_frame,
            )
        except (TypeError, ValueError):
            continue
        if not trace_points or trace_points[-1] != parsed_point:
            trace_points.append(parsed_point)
    if len(trace_points) < 2:
        return None

    autoplay_buttons = [
        [int(value) for value in list(frame)[:12]]
        for frame in data.get("rawButtons", [])
        if isinstance(frame, (list, tuple))
    ]
    return EditorRoutePlan(
        route_id=str(data.get("routeId") or route_id),
        label=str(data.get("label") or fallback_label),
        source=str(data.get("source") or f"published_route:{route_asset.name}"),
        start_state=asset_state_name,
        start_room_id=asset_start_room_id,
        target_room_id=_parse_int(data.get("targetRoomId"), target_room_id),
        waypoints=[(float(point.x), float(point.y)) for point in trace_points],
        trace_points=trace_points,
        autoplay_buttons=autoplay_buttons,
    )


def load_route_plan(
    *,
    nav_export_dir: str | Path,
    state_name: str,
    room_id: int | None = None,
) -> EditorRoutePlan | None:
    """Resolve the current editor route plan.

    The editor-local bridge currently relies on committed expected-route assets.
    """
    if state_name != "ZebesStart" and room_id != LANDING_SITE_ROOM_ID:
        return None
    nav_dir = Path(nav_export_dir).expanduser()
    for route_id, target_room_id, fallback_label in (
        ("sm_opening_bronze", PARLOR_ROOM_ID, "Bronze Opening Ship -> Parlor Bottom Door"),
        ("sm_landing_site", PARLOR_ROOM_ID, "Landing Site Ship -> Left Door"),
    ):
        published = _load_published_route_plan(
            nav_export_dir=nav_dir,
            route_id=route_id,
            state_name="ZebesStart",
            start_room_id=LANDING_SITE_ROOM_ID,
            target_room_id=target_room_id,
            fallback_label=fallback_label,
        )
        if published is not None:
            return published
    return None
