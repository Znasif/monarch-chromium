"""Exact Python port of GraphicScene.kt, Braille.kt, HitGrid.kt, and ComponentGraph.kt.

Used by the Manim scenes in scripts/video/scenes/*.py to render the bundled real data
in android-app/app/src/main/assets/graphics/0c7b09d66d4dcf0b315bab40833a8b876702bec2d15c826b86c351a7b4fe1c50.json
onto the 96x40 Monarch pin array.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
XKCD_JSON_PATH = (
    ROOT_DIR
    / "android-app/app/src/main/assets/graphics/0c7b09d66d4dcf0b315bab40833a8b876702bec2d15c826b86c351a7b4fe1c50.json"
)
XKCD_WIDTH = 666
XKCD_HEIGHT = 205

PIN_WIDTH = 96
PIN_HEIGHT = 40
CANVAS_HEIGHT = 35
SEPARATOR_ROW = 35
CAPTION_START_ROW = 36
CANVAS_PIN_COUNT = PIN_WIDTH * CANVAS_HEIGHT  # 3360


@dataclass(frozen=True)
class NormalizedBox:
    ymin: float
    xmin: float
    ymax: float
    xmax: float

    @property
    def width(self) -> float:
        return max(0.0, self.xmax - self.xmin)

    @property
    def height(self) -> float:
        return max(0.0, self.ymax - self.ymin)

    @property
    def area(self) -> float:
        return self.width * self.height

    @property
    def center_x(self) -> float:
        return 0.5 * (self.xmin + self.xmax)

    @property
    def center_y(self) -> float:
        return 0.5 * (self.ymin + self.ymax)


@dataclass(frozen=True)
class SceneViewport:
    isolated_box: NormalizedBox

    def normalized_to_exact_pin_x(self, norm_x: float) -> float:
        b = self.isolated_box
        w = max(b.width, 1e-6)
        return ((norm_x - b.xmin) / w) * (PIN_WIDTH - 1)

    def normalized_to_exact_pin_y(self, norm_y: float) -> float:
        b = self.isolated_box
        h = max(b.height, 1e-6)
        return ((norm_y - b.ymin) / h) * (CANVAS_HEIGHT - 1)

    def normalized_to_pin_x(self, norm_x: float) -> int:
        val = round(self.normalized_to_exact_pin_x(norm_x))
        return max(0, min(PIN_WIDTH - 1, int(val)))

    def normalized_to_pin_y(self, norm_y: float) -> int:
        val = round(self.normalized_to_exact_pin_y(norm_y))
        return max(0, min(CANVAS_HEIGHT - 1, int(val)))


@dataclass
class GraphicPart:
    key: str
    label: str
    parent_key: Optional[str]
    role: str
    box: NormalizedBox
    strokes: List[Tuple[List[Tuple[float, float]], bool]]
    embedded_text: str
    description: str


@dataclass
class RenderedComponent:
    id: int
    key: str
    label: str
    role: str
    description: str
    embedded_text: str
    bbox: NormalizedBox
    pin_box: Tuple[int, int, int, int]  # (min_x, min_y, max_x, max_y)
    parent_id: Optional[int]
    child_ids: List[int]
    raised_pins: List[Tuple[int, int]]


@dataclass
class RenderedScene:
    canvas_pins: List[List[bool]]  # 35 x 96
    full_pins: List[List[bool]]    # 40 x 96 (includes separator row 35 + caption rows 36..39)
    components: List[RenderedComponent]
    root_ids: List[int]
    viewport: SceneViewport
    hit_grid: List[List[int]]      # 40 x 96 component IDs (0 = empty, -1 = caption)
    panel_frames: Dict[int, Tuple[int, int, int, int]]

    @property
    def raised_canvas_count(self) -> int:
        return sum(1 for y in range(CANVAS_HEIGHT) for x in range(PIN_WIDTH) if self.canvas_pins[y][x])

    @property
    def fill_ratio(self) -> float:
        return self.raised_canvas_count / float(CANVAS_PIN_COUNT)

    def component_by_id(self, comp_id: int) -> Optional[RenderedComponent]:
        for c in self.components:
            if c.id == comp_id:
                return c
        return None

    def component_by_key(self, key: str) -> Optional[RenderedComponent]:
        for c in self.components:
            if c.key == key:
                return c
        return None


# --- Braille.kt port (rows 36..39, 3-column cell stride = 32 cells) ---

_DECADE = ["1", "12", "14", "145", "15", "124", "1245", "125", "24", "245"]


def _braille_dots(ch: str) -> str:
    if "a" <= ch <= "j":
        return _DECADE[ord(ch) - ord("a")]
    if "k" <= ch <= "t":
        return _DECADE[ord(ch) - ord("k")] + "3"
    if ch == "w":
        return "2456"
    if ch in "uvxyz":
        return _DECADE["uvxyz".index(ch)] + "36"
    if "1" <= ch <= "9":
        return _DECADE[ord(ch) - ord("1")]
    if ch == "0":
        return _DECADE[9]
    punct = {",": "2", ".": "256", "?": "236", "!": "235", "'": "3", "-": "36", ":": "25"}
    return punct.get(ch, "")


def write_braille_caption(full_pins: List[List[bool]], text: str) -> None:
    """Writes 6-dot Braille caption onto rows 36..39 (exact port of Braille.writeCaption)."""
    for r in range(CAPTION_START_ROW, PIN_HEIGHT):
        for c in range(PIN_WIDTH):
            full_pins[r][c] = False

    def put_cell(cell_idx: int, dots: str) -> int:
        x0 = cell_idx * 3
        for d in dots:
            n = ord(d) - ord("1")
            col = x0 + (n // 3)
            row = CAPTION_START_ROW + (n % 3)
            if 0 <= col < PIN_WIDTH and CAPTION_START_ROW <= row < PIN_HEIGHT:
                full_pins[row][col] = True
        return cell_idx + 1

    cell = 0
    in_number = False
    max_cells = PIN_WIDTH // 3  # 32
    for raw in text.lower():
        if cell >= max_cells:
            break
        if raw.isdigit() and not in_number:
            cell = put_cell(cell, "3456")
            in_number = True
        if not raw.isdigit():
            in_number = False
        if cell >= max_cells:
            break
        cell = put_cell(cell, _braille_dots(raw))


# --- GraphicScene.kt port ---

class GraphicScenePy:
    """Exact Python port of android-app/app/src/main/java/org/monarch/browser/GraphicScene.kt."""

    def __init__(self, json_text: str, image_width: int = XKCD_WIDTH, image_height: int = XKCD_HEIGHT):
        self.image_width = image_width
        self.image_height = image_height
        self.parts: List[GraphicPart] = self._parse(json_text)
        self.id_by_key: Dict[str, int] = {p.key: i + 1 for i, p in enumerate(self.parts)}

    @classmethod
    def from_xkcd_asset(cls) -> "GraphicScenePy":
        return cls(XKCD_JSON_PATH.read_text(encoding="utf-8"), XKCD_WIDTH, XKCD_HEIGHT)

    @property
    def overview(self) -> NormalizedBox:
        return self.fit_to_canvas(NormalizedBox(0.0, 0.0, 1.0, 1.0))

    def fit_to_canvas(self, box: NormalizedBox, margin: float = 0.04) -> NormalizedBox:
        canvas_aspect = (PIN_WIDTH - 1.0) / (CANVAS_HEIGHT - 1.0)
        w = box.width * (1.0 + margin)
        h = box.height * (1.0 + margin)
        aspect = (w * self.image_width) / max(h * self.image_height, 1e-6)
        if aspect > canvas_aspect:
            h = (w * self.image_width) / (canvas_aspect * self.image_height)
        else:
            w = (h * self.image_height * canvas_aspect) / self.image_width
        cx = 0.5 * (box.xmin + box.xmax)
        cy = 0.5 * (box.ymin + box.ymax)
        return NormalizedBox(
            ymin=cy - h / 2.0,
            xmin=cx - w / 2.0,
            ymax=cy + h / 2.0,
            xmax=cx + w / 2.0,
        )

    def render(
        self,
        view: Optional[NormalizedBox] = None,
        max_fill: float = 0.25,
        fix_panel_gap: bool = True,
        caption_text: str = "Panel 1",
    ) -> RenderedScene:
        active_view = view if view is not None else self.overview
        viewport = SceneViewport(isolated_box=active_view)
        frames = self._panel_frames(viewport, fix_panel_gap=fix_panel_gap)

        drop_order = sorted(
            [i for i, p in enumerate(self.parts) if p.strokes and p.role != "panel"],
            key=lambda idx: self.parts[idx].box.width * self.parts[idx].box.height,
        )
        dropped: Set[int] = set()
        while True:
            pins_by_part: List[List[Tuple[int, int]]] = [
                [] if i in dropped else self._rasterize(p, viewport, frames.get(i))
                for i, p in enumerate(self.parts)
            ]
            unique_raised = {pt for part_pins in pins_by_part for pt in part_pins}
            if len(unique_raised) <= max_fill * CANVAS_PIN_COUNT or len(dropped) == len(drop_order):
                break
            dropped.add(drop_order[len(dropped)])

        canvas_pins = [[False for _ in range(PIN_WIDTH)] for _ in range(CANVAS_HEIGHT)]
        full_pins = [[False for _ in range(PIN_WIDTH)] for _ in range(PIN_HEIGHT)]

        components: List[RenderedComponent] = []
        for i, p in enumerate(self.parts):
            for (px, py) in pins_by_part[i]:
                canvas_pins[py][px] = True
                full_pins[py][px] = True
            x0 = viewport.normalized_to_pin_x(p.box.xmin)
            y0 = viewport.normalized_to_pin_y(p.box.ymin)
            x1 = viewport.normalized_to_pin_x(p.box.xmax)
            y1 = viewport.normalized_to_pin_y(p.box.ymax)
            pin_box = (min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
            components.append(
                RenderedComponent(
                    id=i + 1,
                    key=p.key,
                    label=p.label,
                    role=p.role,
                    description=p.description,
                    embedded_text=p.embedded_text,
                    bbox=p.box,
                    pin_box=pin_box,
                    parent_id=self.id_by_key.get(p.parent_key) if p.parent_key else None,
                    child_ids=[j + 1 for j, ch in enumerate(self.parts) if ch.parent_key == p.key],
                    raised_pins=pins_by_part[i],
                )
            )

        write_braille_caption(full_pins, caption_text)
        hit_grid = self._build_hit_grid(components, halo_radius=1)
        root_ids = [c.id for c in components if c.parent_id is None]

        return RenderedScene(
            canvas_pins=canvas_pins,
            full_pins=full_pins,
            components=components,
            root_ids=root_ids,
            viewport=viewport,
            hit_grid=hit_grid,
            panel_frames=frames,
        )

    def _panel_frames(
        self, viewport: SceneViewport, fix_panel_gap: bool = True
    ) -> Dict[int, Tuple[int, int, int, int]]:
        panels = [i for i, p in enumerate(self.parts) if p.role == "panel"]
        rects: Dict[int, List[int]] = {}
        for i in panels:
            b = self.parts[i].box
            rects[i] = [
                viewport.normalized_to_pin_x(b.xmin),
                viewport.normalized_to_pin_y(b.ymin),
                viewport.normalized_to_pin_x(b.xmax),
                viewport.normalized_to_pin_y(b.ymax),
            ]
        if fix_panel_gap:
            by_left = sorted(panels, key=lambda idx: rects[idx][0])
            for k in range(len(by_left) - 1):
                a = rects[by_left[k]]
                b = rects[by_left[k + 1]]
                overlap_y = min(a[3], b[3]) > max(a[1], b[1])
                if overlap_y and (b[0] - a[2] < 2) and (a[2] >= b[0] - 2):
                    a[2] = b[0] - 2
        return {k: (v[0], v[1], v[2], v[3]) for k, v in rects.items()}

    @staticmethod
    def _is_frame(points: List[Tuple[float, float]], closed: bool, box: NormalizedBox) -> bool:
        if not closed or len(points) > 5 or not points:
            return False
        xs = [pt[0] for pt in points]
        ys = [pt[1] for pt in points]
        w = max(xs) - min(xs)
        h = max(ys) - min(ys)
        return w > 0.8 * box.width and h > 0.8 * box.height

    def _rasterize(
        self,
        part: GraphicPart,
        viewport: SceneViewport,
        frame: Optional[Tuple[int, int, int, int]],
    ) -> List[Tuple[int, int]]:
        if part.role == "text":
            return []
        out: List[Tuple[int, int]] = []
        seen: Set[Tuple[int, int]] = set()

        def plot(px: int, py: int) -> None:
            if 0 <= px < PIN_WIDTH and 0 <= py < CANVAS_HEIGHT:
                if (px, py) not in seen:
                    seen.add((px, py))
                    out.append((px, py))

        def line(a: Tuple[int, int], b: Tuple[int, int]) -> None:
            x0, y0 = a
            x1, y1 = b
            dx = abs(x1 - x0)
            dy = -abs(y1 - y0)
            sx = 1 if x0 < x1 else -1
            sy = 1 if y0 < y1 else -1
            err = dx + dy
            for _ in range(max(dx, -dy) + 1):
                plot(x0, y0)
                e2 = 2 * err
                if e2 >= dy:
                    err += dy
                    x0 += sx
                if e2 <= dx:
                    err += dx
                    y0 += sy

        if frame is not None:
            x0, y0, x1, y1 = frame
            line((x0, y0), (x1, y0))
            line((x1, y0), (x1, y1))
            line((x1, y1), (x0, y1))
            line((x0, y1), (x0, y0))

        for points, closed in part.strokes:
            if frame is not None and self._is_frame(points, closed, part.box):
                continue
            path = points + [points[0]] if (closed and len(points) > 2) else points
            pins = [
                (
                    int(round(viewport.normalized_to_exact_pin_x(pt[0]))),
                    int(round(viewport.normalized_to_exact_pin_y(pt[1]))),
                )
                for pt in path
            ]
            if len(pins) == 1:
                plot(pins[0][0], pins[0][1])
            for k in range(len(pins) - 1):
                line(pins[k], pins[k + 1])

        return out

    @staticmethod
    def _build_hit_grid(components: List[RenderedComponent], halo_radius: int = 1) -> List[List[int]]:
        """Port of HitGrid.kt: direct pins beat halo pins; smaller pinBox area wins on overlap."""
        grid = [[0 for _ in range(PIN_WIDTH)] for _ in range(PIN_HEIGHT)]
        for r in range(CAPTION_START_ROW, PIN_HEIGHT):
            for c in range(PIN_WIDTH):
                grid[r][c] = -1

        def box_area(c: RenderedComponent) -> int:
            x0, y0, x1, y1 = c.pin_box
            return max(1, (x1 - x0 + 1) * (y1 - y0 + 1))

        # Sort from largest area to smallest area so smaller children overwrite larger parents
        ordered = sorted(components, key=lambda c: (-box_area(c), c.id))

        # 1. Stamp 1-pin halos and text-component interior boxes
        for comp in ordered:
            if comp.role == "text":
                x0, y0, x1, y1 = comp.pin_box
                for y in range(max(0, y0), min(CANVAS_HEIGHT - 1, y1) + 1):
                    for x in range(max(0, x0), min(PIN_WIDTH - 1, x1) + 1):
                        grid[y][x] = comp.id
            else:
                for (px, py) in comp.raised_pins:
                    for dy in range(-halo_radius, halo_radius + 1):
                        for dx in range(-halo_radius, halo_radius + 1):
                            nx, ny = px + dx, py + dy
                            if 0 <= nx < PIN_WIDTH and 0 <= ny < CANVAS_HEIGHT:
                                grid[ny][nx] = comp.id

        # 2. Stamp direct raised pins on top of halos (smaller components last so they win)
        for comp in ordered:
            for (px, py) in comp.raised_pins:
                if 0 <= px < PIN_WIDTH and 0 <= py < CANVAS_HEIGHT:
                    grid[py][px] = comp.id

        return grid

    @staticmethod
    def _parse(json_text: str) -> List[GraphicPart]:
        raw = json.loads(json_text)
        out: List[GraphicPart] = []
        for c in raw.get("components", []):
            b = c["box"]
            strokes_raw = c.get("strokes") or []
            strokes: List[Tuple[List[Tuple[float, float]], bool]] = []
            for s in strokes_raw:
                pts = [(float(pt[0]), float(pt[1])) for pt in s.get("points", [])]
                strokes.append((pts, bool(s.get("closed", False))))
            out.append(
                GraphicPart(
                    key=str(c["id"]),
                    label="" if c.get("label") is None else str(c.get("label", "")),
                    parent_key=c.get("parentId") if c.get("parentId") else None,
                    role=str(c.get("role") or "other"),
                    box=NormalizedBox(
                        ymin=float(b[1]),
                        xmin=float(b[0]),
                        ymax=float(b[3]),
                        xmax=float(b[2]),
                    ),
                    strokes=strokes,
                    embedded_text="" if c.get("embeddedText") is None else str(c.get("embeddedText", "")),
                    description="" if c.get("description") is None else str(c.get("description", "")),
                )
            )
        return out
