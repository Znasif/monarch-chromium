"""Shared 3Blue1Brown-style Manim components and colour palette for the Monarch explainer."""

from __future__ import annotations

from typing import Dict, List, Optional, Tuple
from manim import (
     DOWN,
     Dot,
     GREY_A,
     GREY_B,
     LEFT,
     Line,
     RIGHT,
     Rectangle,
     RoundedRectangle,
     Scene,
     Text,
     UP,
     VGroup,
     WHITE,
)

from scripts.video.graphic_scene_py import (
    CANVAS_HEIGHT,
    CAPTION_START_ROW,
    PIN_HEIGHT,
    PIN_WIDTH,
    SEPARATOR_ROW,
)

BG_COLOR = "#0e1117"
PANEL_BG = "#161b22"
PANEL_BORDER = "#30363d"
PIN_OFF_COLOR = "#262c36"
PIN_ON_CANVAS = "#58a6ff"     # 3B1B sky blue for raised tactile canvas pins
PIN_ON_CAPTION = "#3fb950"    # emerald green for raised Braille caption pins
PIN_SEP_COLOR = "#d29922"     # amber accent for blank separator row 35
PIN_DENSE_BAD = "#f85149"     # crimson for over-dense (>25%) pins
FOCUS_COLOR = "#f0883e"       # warm orange for selected component box
FINGER_COLOR = "#56d364"      # bright green finger ring
HALO_PALETTE = [
    "#58a6ff",
    "#bc8cff",
    "#3fb950",
    "#f0883e",
    "#ff7b72",
    "#79c0ff",
    "#d2a8ff",
    "#56d364",
    "#ffa657",
]


def setup_3b1b_scene(scene: Scene) -> None:
    scene.camera.background_color = BG_COLOR


def make_header(step_badge: str, title_text: str, subtitle_text: str = "") -> VGroup:
    badge_bg = RoundedRectangle(
        corner_radius=0.12,
        width=1.55,
        height=0.42,
        fill_color="#1f6feb",
        fill_opacity=0.95,
        stroke_color="#58a6ff",
        stroke_width=1.5,
    )
    badge_lbl = Text(step_badge, font="DejaVu Sans Mono", font_size=18, color=WHITE, weight="BOLD")
    badge_lbl.move_to(badge_bg.get_center())
    badge = VGroup(badge_bg, badge_lbl)

    title = Text(title_text, font="DejaVu Sans", font_size=26, color=WHITE, weight="BOLD")
    if title.width > 11.2:
        title.scale_to_fit_width(11.2)
    title.next_to(badge, RIGHT, buff=0.22)
    top_row = VGroup(badge, title)

    if subtitle_text:
        sub = Text(subtitle_text, font="DejaVu Sans", font_size=16, color=GREY_B)
        if sub.width > 12.8:
            sub.scale_to_fit_width(12.8)
        sub.next_to(top_row, DOWN, aligned_edge=LEFT, buff=0.10)
        group = VGroup(top_row, sub)
    else:
        group = VGroup(top_row)

    group.to_edge(UP, buff=0.30).to_edge(LEFT, buff=0.50)
    return group


def make_card(
    width: float,
    height: float,
    title: str,
    lines: List[str],
    accent_color: str = "#58a6ff",
    title_size: int = 19,
    body_size: int = 14,
) -> VGroup:
    rect = RoundedRectangle(
        corner_radius=0.14,
        width=width,
        height=height,
        fill_color=PANEL_BG,
        fill_opacity=0.96,
        stroke_color=accent_color,
        stroke_width=2.0,
    )
    t_obj = Text(title, font="DejaVu Sans", font_size=title_size, color=accent_color, weight="BOLD")
    if t_obj.width > width - 0.35:
        t_obj.scale_to_fit_width(width - 0.35)
    t_obj.move_to(rect.get_top() + DOWN * 0.32)

    body_items = VGroup()
    for line_str in lines:
        item = Text(line_str, font="DejaVu Sans", font_size=body_size, color=GREY_A)
        if item.width > width - 0.38:
            item.scale_to_fit_width(width - 0.38)
        body_items.add(item)
    if len(body_items) > 0:
        body_items.arrange(DOWN, aligned_edge=LEFT, buff=0.10)
        body_items.next_to(t_obj, DOWN, buff=0.18)
        body_items.align_to(rect.get_left() + RIGHT * 0.20, LEFT)

    return VGroup(rect, t_obj, body_items)


class PinGridMobject(VGroup):
    """High-speed 3Blue1Brown 96x40 Monarch physical pin matrix in Manim."""

    def __init__(
        self,
        pitch: float = 0.082,
        show_frame: bool = True,
        raised_canvas: Optional[List[List[bool]]] = None,
        raised_full: Optional[List[List[bool]]] = None,
        hit_grid: Optional[List[List[int]]] = None,
        color_by_part: Optional[Dict[Tuple[int, int], str]] = None,
        **kwargs,
    ):
        super().__init__(**kwargs)
        self.pitch = pitch
        self.grid_w = (PIN_WIDTH - 1) * pitch
        self.grid_h = (PIN_HEIGHT - 1) * pitch

        self.bezel = RoundedRectangle(
            corner_radius=0.18,
            width=self.grid_w + 0.45,
            height=self.grid_h + 0.45,
            fill_color="#12161f",
            fill_opacity=0.98 if show_frame else 0.0,
            stroke_color="#3b4454",
            stroke_width=2.2 if show_frame else 0.0,
        )
        self.add(self.bezel)

        bg_lines = VGroup()
        x_left = self._pin_x(0)
        x_right = self._pin_x(PIN_WIDTH - 1)
        y_top = self._pin_y(0)
        y_bot = self._pin_y(PIN_HEIGHT - 1)
        for y in range(PIN_HEIGHT):
            if y == SEPARATOR_ROW:
                continue
            py = self._pin_y(y)
            bg_lines.add(
                Line([x_left, py, 0], [x_right, py, 0], color="#1e2530", stroke_width=0.75, stroke_opacity=0.65)
            )
        for x in range(0, PIN_WIDTH, 2):
            px = self._pin_x(x)
            bg_lines.add(
                Line([px, y_top, 0], [px, y_bot, 0], color="#1e2530", stroke_width=0.65, stroke_opacity=0.55)
            )
        self.add(bg_lines)

        sep_y = self._pin_y(SEPARATOR_ROW)
        self.sep_bar = Line(
            start=[x_left - pitch * 0.4, sep_y, 0],
            end=[x_right + pitch * 0.4, sep_y, 0],
            color=PIN_SEP_COLOR,
            stroke_width=2.0,
            stroke_opacity=0.85,
        )
        self.add(self.sep_bar)

        self.pin_dots = VGroup()
        for y in range(PIN_HEIGHT):
            for x in range(PIN_WIDTH):
                is_on = False
                if raised_full is not None:
                    is_on = raised_full[y][x]
                elif raised_canvas is not None and y < CANVAS_HEIGHT:
                    is_on = raised_canvas[y][x]

                has_hit = hit_grid is not None and y < CANVAS_HEIGHT and hit_grid[y][x] > 0
                if not is_on and not has_hit and not (color_by_part and (x, y) in color_by_part):
                    continue

                if color_by_part and (x, y) in color_by_part:
                    col = color_by_part[(x, y)]
                    radius = pitch * 0.36
                    opacity = 0.95
                elif has_hit:
                    cid = hit_grid[y][x]
                    col = HALO_PALETTE[(cid - 1) % len(HALO_PALETTE)]
                    radius = pitch * (0.38 if is_on else 0.24)
                    opacity = 1.0 if is_on else 0.48
                else:
                    col = PIN_ON_CAPTION if y >= CAPTION_START_ROW else PIN_ON_CANVAS
                    radius = pitch * 0.36
                    opacity = 1.0

                d = Dot(
                    point=[self._pin_x(x), self._pin_y(y), 0],
                    radius=radius,
                    color=col,
                    fill_opacity=opacity,
                )
                self.pin_dots.add(d)

        self.add(self.pin_dots)

    def _pin_x(self, col: float) -> float:
        return (col - (PIN_WIDTH - 1) / 2.0) * self.pitch

    def _pin_y(self, row: float) -> float:
        return ((PIN_HEIGHT - 1) / 2.0 - row) * self.pitch

    def pin_point(self, col: float, row: float) -> List[float]:
        center = self.bezel.get_center()
        scale_x = self.bezel.width / (self.grid_w + 0.45)
        scale_y = self.bezel.height / (self.grid_h + 0.45)
        return [
            float(center[0] + self._pin_x(col) * scale_x),
            float(center[1] + self._pin_y(row) * scale_y),
            0.0,
        ]

    def pin_rect_mobject(
        self,
        min_x: int,
        min_y: int,
        max_x: int,
        max_y: int,
        color: str = FOCUS_COLOR,
        stroke_width: float = 2.5,
    ) -> Rectangle:
        p0 = self.pin_point(max(0, min_x), max(0, min_y))
        p1 = self.pin_point(min(PIN_WIDTH - 1, max_x), min(PIN_HEIGHT - 1, max_y))
        w = abs(p1[0] - p0[0]) + self.pitch * 0.9
        h = abs(p0[1] - p1[1]) + self.pitch * 0.9
        cx = 0.5 * (p0[0] + p1[0])
        cy = 0.5 * (p0[1] + p1[1])
        r = Rectangle(width=w, height=h, stroke_color=color, stroke_width=stroke_width, fill_opacity=0.0)
        r.move_to([cx, cy, 0])
        return r
