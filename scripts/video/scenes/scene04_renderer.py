"""Scene 4 — The Renderer: Bresenham, Panel Gaps & the 25% Budget (38 seconds).

Visualizes GraphicScene.kt on the real bundled xkcd #327 asset:
1. fitToCanvas aspect-correct projection onto 95x34 pins + 1-pin Bresenham lines.
2. The real Panel 3 / Panel 4 border collision bug (columns 68 and 69 touching)
   and the panelFrames fix (pulling Panel 3's right border to column 67 so column 68
   is a guaranteed 1-pin blank separator).
3. Smallest-area-first dropOrder enforcing fill <= 25% (645 / 3,360 = 19.2%).
"""
from __future__ import annotations

import sys
from pathlib import Path
from manim import (
    Scene, VGroup, Text, RoundedRectangle, Dot,
    FadeIn, FadeOut,
    UP, DOWN, LEFT, RIGHT, WHITE, GREY_A, GREY_B
)

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from scripts.video.style import (
    PANEL_BG, PIN_ON_CANVAS, PIN_ON_CAPTION, PIN_SEP_COLOR, PIN_DENSE_BAD, FOCUS_COLOR,
    setup_3b1b_scene, make_header, make_card, PinGridMobject
)
from scripts.video.graphic_scene_py import GraphicScenePy


def make_zoomed_seam_callout(rendered_scene, title_str: str, accent_col: str) -> VGroup:
    """Builds a magnified pin callout of columns 65..71, rows 8..14 around the Panel 3/4 seam."""
    box = RoundedRectangle(
        width=5.3, height=2.45, corner_radius=0.12,
        stroke_color=accent_col, stroke_width=2.0,
        fill_color=PANEL_BG, fill_opacity=0.96
    )
    hdr = Text(title_str, font="DejaVu Sans Mono", font_size=12, color=accent_col, weight="BOLD")
    if hdr.width > 5.0:
        hdr.scale_to_fit_width(5.0)
    hdr.move_to(box.get_top() + DOWN * 0.24)

    cols = list(range(65, 72))
    rows = list(range(8, 15))
    pitch = 0.22
    grid_group = VGroup()
    ox = box.get_center()[0] - (len(cols) - 1) * pitch / 2.0
    oy = box.get_center()[1] + 0.16 + (len(rows) - 1) * pitch / 2.0

    for ci, c in enumerate(cols):
        cx = ox + ci * pitch
        col_color = GREY_B
        if c in (67, 68, 69):
            col_color = accent_col if c == 68 else WHITE
        clbl = Text(str(c), font="DejaVu Sans Mono", font_size=11, color=col_color)
        clbl.move_to([cx, oy + 0.24, 0])
        grid_group.add(clbl)

    for ri, r in enumerate(rows):
        cy = oy - ri * pitch
        for ci, c in enumerate(cols):
            cx = ox + ci * pitch
            on = rendered_scene.full_pins[r][c]
            if on:
                if c == 67:
                    pcol = FOCUS_COLOR
                elif c == 68:
                    pcol = PIN_DENSE_BAD
                elif c == 69:
                    pcol = PIN_ON_CANVAS
                else:
                    pcol = PIN_ON_CAPTION
                d = Dot(point=[cx, cy, 0], radius=0.075, color=pcol)
            else:
                d = Dot(point=[cx, cy, 0], radius=0.032, color="#2d3545")
                if c == 68:
                    ring = Dot(point=[cx, cy, 0], radius=0.068, color=PIN_ON_CAPTION, fill_opacity=0.22)
                    grid_group.add(ring)
            grid_group.add(d)

    return VGroup(box, hdr, grid_group)


class Scene04Renderer(Scene):
    def construct(self):
        setup_3b1b_scene(self)

        header = make_header(
            "SCENE 4",
            "Deterministic Tactile Renderer (GraphicScene.kt)",
            "fitToCanvas aspect-correct projection · 1-pin Bresenham lines · panelFrames gap · <= 25% fill budget"
        )
        self.play(FadeIn(header, run_time=0.8))

        gs = GraphicScenePy.from_xkcd_asset()
        scene_unfixed = gs.render(fix_panel_gap=False, caption_text="Panel 3/4 col 68+69")
        scene_fixed = gs.render(fix_panel_gap=True, caption_text="xkcd 327 fill 19.2%")

        # Highlight columns 68 & 69 in red on the unfixed grid so the seam collision stands out
        unfixed_colors = {}
        for r in range(6, 30):
            if scene_unfixed.full_pins[r][68]:
                unfixed_colors[(68, r)] = PIN_DENSE_BAD
            if scene_unfixed.full_pins[r][69]:
                unfixed_colors[(69, r)] = PIN_DENSE_BAD

        pin_unfixed = PinGridMobject(
            pitch=0.072,
            raised_full=scene_unfixed.full_pins,
            color_by_part=unfixed_colors,
        ).move_to(LEFT * 3.15 + DOWN * 0.25)

        pin_fixed = PinGridMobject(
            pitch=0.072,
            raised_full=scene_fixed.full_pins,
        ).move_to(LEFT * 3.15 + DOWN * 0.25)

        grid_caption = Text(
            "GraphicScene.build() on bundled xkcd #327 (666×205 -> 96×35 active canvas)",
            font="DejaVu Sans Mono", font_size=11, color=GREY_B
        ).next_to(pin_unfixed, DOWN, buff=0.16)

        algo_card = make_card(
            width=5.3,
            height=1.80,
            title="1. Aspect-Correct Projection & Bresenham",
            lines=[
                "• fitToCanvas(): scales [0,1000] box onto 95×34 pins",
                "  preserving source aspect ratio (666:205 -> 3.25:1)",
                "• line(x0,y0,x1,y1): integer 1-pin Bresenham step",
                "• Never anti-aliases or blurs into 2-pin thick walls",
            ],
            accent_color=PIN_ON_CANVAS,
            title_size=15,
            body_size=12,
        ).move_to(RIGHT * 3.85 + UP * 1.55)

        self.play(
            FadeIn(pin_unfixed, run_time=1.2),
            FadeIn(grid_caption, run_time=0.8),
            FadeIn(algo_card, run_time=0.9),
        )
        self.wait(4.8)

        # Phase 2: Panel 3 / Panel 4 border collision bug (columns 68 & 69 touching)
        callout_bug = make_zoomed_seam_callout(
            scene_unfixed,
            "BUG: Panel 3 right (col 68) touches Panel 4 left (col 69)",
            PIN_DENSE_BAD
        ).move_to(RIGHT * 3.85 + DOWN * 0.65)
        bug_note = Text(
            "Under a fingertip, cols 68+69 merge into one thick ridge!",
            font="DejaVu Sans", font_size=11, color=PIN_DENSE_BAD
        ).move_to(callout_bug.get_bottom() + UP * 0.20)

        self.play(FadeIn(callout_bug, run_time=0.9), FadeIn(bug_note, run_time=0.7))
        self.wait(6.8)

        # Phase 3: Apply the panelFrames fix -> pull Panel 3 right border to col 67, leaving col 68 blank
        callout_fix = make_zoomed_seam_callout(
            scene_fixed,
            "FIX: panelFrames pulls Panel 3 to col 67 (col 68 blank!)",
            PIN_ON_CAPTION
        ).move_to(RIGHT * 3.85 + DOWN * 0.65)
        fix_note = Text(
            "if (b[0] in a[2]..(a[2]+1)) a[2] = b[0] - 2  -> 1-pin gap",
            font="DejaVu Sans Mono", font_size=11, color=PIN_ON_CAPTION
        ).move_to(callout_fix.get_bottom() + UP * 0.20)

        self.play(
            FadeOut(pin_unfixed, run_time=0.6),
            FadeIn(pin_fixed, run_time=0.6),
            FadeOut(callout_bug, run_time=0.5),
            FadeOut(bug_note, run_time=0.5),
            FadeIn(callout_fix, run_time=0.7),
            FadeIn(fix_note, run_time=0.7),
        )
        self.wait(7.5)

        # Phase 4: Smallest-area-first dropOrder for <= 25% fill budget
        fill_pct = scene_fixed.fill_ratio * 100.0
        raised_cnt = scene_fixed.raised_canvas_count
        budget_card = make_card(
            width=5.3,
            height=1.50,
            title="3. Density Budget Enforcement (fill <= 25%)",
            lines=[
                "• dropOrder sorts non-panel strokes smallest (w×h) first",
                "• Drops tiny detail until active canvas <= 840 pins (25%)",
                f"• xkcd #327 overview: {raised_cnt} / 3,360 pins = {fill_pct:.1f}% (0 dropped)",
            ],
            accent_color=PIN_SEP_COLOR,
            title_size=15,
            body_size=12,
        ).move_to(RIGHT * 3.85 + DOWN * 2.65)

        self.play(FadeIn(budget_card, run_time=0.9))
        self.wait(11.3)
