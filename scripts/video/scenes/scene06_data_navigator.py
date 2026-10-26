"""Scene 6 — Data Navigator: Levels, Siblings, Isolation, Zoom & Pan (36 seconds).

Visualizes ComponentGraph.kt and DetailActivity.kt on bundled xkcd #327:
1. Tree diagram of rootIds (panel_1..4) and children of panel_3 (p3_text_phone, p3_mom, p3_text_mom).
2. atCurrentLevel(id=12 -> id=3): double-pressing Action while touching p3_mom at root level
   opens Panel 3 first and selects p3_mom inside it.
3. Full 96x40 PinGridMobject transition from 4-panel overview (19.2% fill) to isolated Panel 3
   (656 / 3,360 pins = 19.5% fill).
4. Left D-pad sibling cycle, 2x zoom, and Right D-pad pan.
"""
from __future__ import annotations

import sys
from pathlib import Path
from manim import (
    Scene, VGroup, Text, RoundedRectangle, Line, Arrow,
    FadeIn, FadeOut,
    UP, DOWN, LEFT, RIGHT, WHITE, GREY_A, GREY_B
)

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from scripts.video.style import (
    PANEL_BG, PIN_ON_CANVAS, PIN_ON_CAPTION, PIN_SEP_COLOR, FOCUS_COLOR, FINGER_COLOR,
    setup_3b1b_scene, make_header, make_card, PinGridMobject
)
from scripts.video.graphic_scene_py import GraphicScenePy


def make_tree_node(label: str, sub: str, color: str, width: float = 1.22, height: float = 0.56) -> VGroup:
    box = RoundedRectangle(
        width=width, height=height, corner_radius=0.08,
        stroke_color=color, stroke_width=2.0,
        fill_color=PANEL_BG, fill_opacity=0.96
    )
    t1 = Text(label, font="DejaVu Sans Mono", font_size=11, color=color, weight="BOLD")
    t2 = Text(sub, font="DejaVu Sans", font_size=9, color=GREY_B)
    grp = VGroup(t1, t2).arrange(DOWN, buff=0.04).move_to(box.get_center())
    return VGroup(box, grp)


class Scene06DataNavigator(Scene):
    def construct(self):
        setup_3b1b_scene(self)

        header = make_header(
            "SCENE 6",
            "Data Navigator: Levels, Siblings, Isolation, Zoom & Pan",
            "ComponentGraph · atCurrentLevel(id) · Left D-pad siblings · Action isolate · Right D-pad pan"
        )
        self.play(FadeIn(header, run_time=0.8))

        gs = GraphicScenePy.from_xkcd_asset()
        overview_scene = gs.render(fix_panel_gap=True, caption_text="Panel 3")

        # Isolated Panel 3 scene
        p3_part = [p for p in gs.parts if p.key == "panel_3"][0]
        p3_view = gs.fit_to_canvas(p3_part.box)
        isolated_p3 = gs.render(view=p3_view, fix_panel_gap=True, caption_text="Mrs. Roberts")

        pin_overview = PinGridMobject(
            pitch=0.072,
            raised_full=overview_scene.full_pins,
        ).move_to(LEFT * 3.15 + DOWN * 0.25)

        pin_isolated = PinGridMobject(
            pitch=0.072,
            raised_full=isolated_p3.full_pins,
        ).move_to(LEFT * 3.15 + DOWN * 0.25)

        grid_lbl_ov = Text(
            f"Level 0 (Overview): 4 panels · {overview_scene.raised_canvas_count}/3,360 pins ({overview_scene.fill_ratio*100:.1f}%)",
            font="DejaVu Sans Mono", font_size=11, color=GREY_B
        ).next_to(pin_overview, DOWN, buff=0.15)

        grid_lbl_iso = Text(
            f"Level 1 (Isolated panel_3): fitToCanvas(panel_3.box) · {isolated_p3.raised_canvas_count}/3,360 pins ({isolated_p3.fill_ratio*100:.1f}%)",
            font="DejaVu Sans Mono", font_size=11, color=PIN_ON_CAPTION
        ).next_to(pin_isolated, DOWN, buff=0.15)

        # Right side top: ComponentGraph tree diagram
        tree_card = RoundedRectangle(
            width=5.35, height=2.35, corner_radius=0.12,
            stroke_color=PIN_ON_CANVAS, stroke_width=2.0,
            fill_color=PANEL_BG, fill_opacity=0.96
        ).move_to(RIGHT * 3.85 + UP * 1.35)
        tree_title = Text(
            "ComponentGraph Hierarchy (atCurrentLevel climbs parentId)",
            font="DejaVu Sans", font_size=13, color=PIN_ON_CANVAS, weight="BOLD"
        ).move_to(tree_card.get_top() + DOWN * 0.24)

        n_p1 = make_tree_node("panel_1", "id=1", PIN_ON_CANVAS, 1.15, 0.48)
        n_p2 = make_tree_node("panel_2", "id=2", PIN_ON_CANVAS, 1.15, 0.48)
        n_p3 = make_tree_node("panel_3", "id=3 (Focus)", FOCUS_COLOR, 1.25, 0.48)
        n_p4 = make_tree_node("panel_4", "id=4", PIN_ON_CANVAS, 1.15, 0.48)
        roots_row = VGroup(n_p1, n_p2, n_p3, n_p4).arrange(RIGHT, buff=0.12)
        roots_row.move_to(tree_card.get_center() + UP * 0.32)

        c_phone = make_tree_node("p3_text_phone", "id=11 (text)", PIN_SEP_COLOR, 1.55, 0.50)
        c_mom = make_tree_node("p3_mom", "id=12 (stroke)", FINGER_COLOR, 1.55, 0.50)
        c_txt = make_tree_node("p3_text_mom", "id=13 (text)", PIN_SEP_COLOR, 1.55, 0.50)
        children_row = VGroup(c_phone, c_mom, c_txt).arrange(RIGHT, buff=0.14)
        children_row.move_to(tree_card.get_center() + DOWN * 0.62)

        edges = VGroup(
            Line(n_p3.get_bottom(), c_phone.get_top(), color=FOCUS_COLOR, stroke_width=1.8),
            Line(n_p3.get_bottom(), c_mom.get_top(), color=FINGER_COLOR, stroke_width=2.4),
            Line(n_p3.get_bottom(), c_txt.get_top(), color=FOCUS_COLOR, stroke_width=1.8),
        )
        tree_group = VGroup(tree_card, tree_title, roots_row, children_row, edges)

        self.play(
            FadeIn(pin_overview, run_time=1.0),
            FadeIn(grid_lbl_ov, run_time=0.7),
            FadeIn(tree_group, run_time=1.0),
        )
        self.wait(4.8)

        # Phase 2: Action double-press on p3_mom -> atCurrentLevel(12) returns panel_3 (id=3) -> isolate Panel 3!
        drill_card = make_card(
            width=5.35,
            height=1.55,
            title="Action Double-Press -> Isolate Panel 3",
            lines=[
                "• Finger rests on p3_mom (id=12) while at Root Level",
                "• atCurrentLevel(12) climbs parentId -> panel_3 (id=3)",
                "• Opens isolated Panel 3 and highlights p3_mom inside it",
            ],
            accent_color=FOCUS_COLOR,
            title_size=14,
            body_size=12,
        ).move_to(RIGHT * 3.85 + DOWN * 0.85)

        self.play(
            FadeOut(pin_overview, run_time=0.6),
            FadeOut(grid_lbl_ov, run_time=0.5),
            FadeIn(pin_isolated, run_time=0.7),
            FadeIn(grid_lbl_iso, run_time=0.6),
            FadeIn(drill_card, run_time=0.8),
        )
        self.wait(6.5)

        # Phase 3: Left D-pad steps siblings inside Panel 3; Zoom 2x + Right D-pad pans; Back exits
        controls_card = make_card(
            width=5.35,
            height=1.55,
            title="Hardware Controls Inside Isolated Panel 3",
            lines=[
                "• Left D-pad Left/Right: steps siblings (11 -> 12 -> 13)",
                "• Zoom 2x + Right D-pad: pans viewport by 25% of span",
                "• Back (Escape / Delete): pops parentStack to 4-panel view",
            ],
            accent_color=PIN_ON_CAPTION,
            title_size=14,
            body_size=12,
        ).move_to(RIGHT * 3.85 + DOWN * 2.55)

        # Highlight bounding box of p3_mom on the isolated pin grid
        mom_comp = isolated_p3.component_by_key("p3_mom")
        if mom_comp is not None:
            x0, y0, x1, y1 = mom_comp.pin_box
            focus_rect = pin_isolated.pin_rect_mobject(x0, y0, x1, y1, color=FOCUS_COLOR, stroke_width=2.8)
            self.play(FadeIn(controls_card, run_time=0.8), FadeIn(focus_rect, run_time=0.7))
        else:
            self.play(FadeIn(controls_card, run_time=0.8))

        self.wait(11.0)
