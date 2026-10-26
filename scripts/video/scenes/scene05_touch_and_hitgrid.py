"""Scene 5 — Touch & HitGrid: Smallest Wins, Canvas Never Moves (32 seconds).

Visualizes HitGrid.kt and FingerPoll.kt on the real bundled xkcd #327 scene:
1. HitGrid colour-coded 1-pin halos + text-only interior boxes.
2. Smallest pinBox area wins on overlap: p3_mom (80 pins) wins over panel_3 (1,008 pins).
3. 40 Hz FingerPoll: moving finger updates speech + rows 36–39 Braille caption while
   rows 0–34 canvas pins never move under a resting hand.
4. Tactile cutaway of the front-edge Action double-press.
"""
from __future__ import annotations

import sys
from pathlib import Path
from manim import (
    Scene, VGroup, Text, RoundedRectangle, Circle, Dot, Arrow,
    FadeIn, FadeOut, MoveAlongPath, Line,
    UP, DOWN, LEFT, RIGHT, WHITE, GREY_A, GREY_B
)

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from scripts.video.style import (
    PANEL_BG, PIN_ON_CANVAS, PIN_ON_CAPTION, PIN_SEP_COLOR, PIN_DENSE_BAD,
    FOCUS_COLOR, FINGER_COLOR, HALO_PALETTE,
    setup_3b1b_scene, make_header, make_card, PinGridMobject
)
from scripts.video.graphic_scene_py import GraphicScenePy, write_braille_caption


class Scene05TouchAndHitGrid(Scene):
    def construct(self):
        setup_3b1b_scene(self)

        header = make_header(
            "SCENE 5",
            "Touch & HitGrid: Smallest Wins, Canvas Never Moves",
            "IntArray(96×40) O(1) lookup · 1-pin stroke halo · smaller pinBox wins · 40 Hz FingerPoll"
        )
        self.play(FadeIn(header, run_time=0.8))

        gs = GraphicScenePy.from_xkcd_asset()
        rendered = gs.render(fix_panel_gap=True, caption_text="Panel 3")

        # Left: PinGridMobject with HitGrid halo overlay enabled
        grid_halo = PinGridMobject(
            pitch=0.072,
            raised_full=rendered.full_pins,
            hit_grid=rendered.hit_grid,
        ).move_to(LEFT * 3.15 + DOWN * 0.20)

        grid_lbl = Text(
            "HitGrid overlay on xkcd #327: 1-pin halos + text interior regions (0 raised pins)",
            font="DejaVu Sans Mono", font_size=11, color=GREY_B
        ).next_to(grid_halo, DOWN, buff=0.15)

        rules_card = make_card(
            width=5.3,
            height=2.05,
            title="HitGrid.fromComponents() Precedence Rules",
            lines=[
                "1. Sort components largest pinBox area -> smallest",
                "2. Stamp 1-pin halo around strokes; stamp text boxes",
                "3. Stamp direct raised pins on top of halos",
                "4. Smaller children overwrite larger parents:",
                "   • panel_3 pinBox = 48×21 (1,008 pins)",
                "   • p3_mom  pinBox =  8×10 (   80 pins) -> WINS!",
            ],
            accent_color=PIN_ON_CANVAS,
            title_size=15,
            body_size=12,
        ).move_to(RIGHT * 3.85 + UP * 1.50)

        self.play(
            FadeIn(grid_halo, run_time=1.1),
            FadeIn(grid_lbl, run_time=0.8),
            FadeIn(rules_card, run_time=0.9),
        )
        self.wait(4.5)

        # Phase 2: Simulated finger trace across Panel 3 -> p3_mom -> p3_text_mom
        # Compute coordinates on grid_halo for three real pin coordinates:
        # 1) (50, 20) -> inside panel_3 background
        # 2) (60, 20) -> on p3_mom (Mrs. Roberts at her desk)
        # 3) (55, 11) -> inside p3_text_mom ("Did you really name your son...")
        pt_panel3 = grid_halo.pin_point(50, 20)
        pt_mom = grid_halo.pin_point(60, 20)
        pt_text = grid_halo.pin_point(55, 11)

        finger_ring = Circle(radius=0.22, stroke_color=FINGER_COLOR, stroke_width=3.5, fill_color=FINGER_COLOR, fill_opacity=0.18)
        finger_dot = Dot(radius=0.05, color=WHITE)
        finger = VGroup(finger_ring, finger_dot).move_to(pt_panel3)

        readout_card = make_card(
            width=5.3,
            height=1.95,
            title="40 Hz FingerPoll (25 ms debounce) — Touch Readout",
            lines=[
                "• Pin (50, 20) -> id=3 (panel_3)",
                "• Speech: \"Panel 3: Mrs. Roberts at her desk\"",
                "• Braille rows 36–39: \"Panel 3\"",
                "• Canvas rows 0–34: 0 pins changed (NEVER moves!)",
            ],
            accent_color=FINGER_COLOR,
            title_size=14,
            body_size=12,
        ).move_to(RIGHT * 3.85 + DOWN * 0.65)

        self.play(FadeIn(finger, run_time=0.6), FadeIn(readout_card, run_time=0.7))
        self.wait(3.8)

        # Move finger onto p3_mom (60, 20)
        readout_mom = make_card(
            width=5.3,
            height=1.95,
            title="Smallest Wins: Finger Reaches Pin (60, 20)",
            lines=[
                "• Pin (60, 20) -> id=12 (p3_mom, area=80 < 1,008)",
                "• Speech: \"Mrs. Roberts sitting at her desk\"",
                "• Braille rows 36–39: \"Mrs. Roberts\"",
                "• Canvas rows 0–34: UNCHANGED under resting hand",
            ],
            accent_color=FOCUS_COLOR,
            title_size=14,
            body_size=12,
        ).move_to(RIGHT * 3.85 + DOWN * 0.65)

        self.play(
            finger.animate.move_to(pt_mom),
            FadeOut(readout_card, run_time=0.5),
            FadeIn(readout_mom, run_time=0.6),
            run_time=1.0
        )
        self.wait(4.5)

        # Move finger onto p3_text_mom (55, 11) — 0 raised pins!
        readout_text = make_card(
            width=5.3,
            height=1.95,
            title="Text Touch Region: Pin (55, 11) — 0 Raised Pins",
            lines=[
                "• Pin (55, 11) -> id=13 (p3_text_mom, role=\"text\")",
                "• Speech: \"Did you really name your son Robert');...\"",
                "• Braille rows 36–39: \"Did you really name...\"",
                "• Zero raised lettering clutter on the tactile canvas!",
            ],
            accent_color=PIN_SEP_COLOR,
            title_size=14,
            body_size=12,
        ).move_to(RIGHT * 3.85 + DOWN * 0.65)

        self.play(
            finger.animate.move_to(pt_text),
            FadeOut(readout_mom, run_time=0.5),
            FadeIn(readout_text, run_time=0.6),
            run_time=1.0
        )
        self.wait(4.5)

        # Phase 3: Front-edge Action button double-press cutaway (Omni / procedural cutaway)
        cutaway_card = make_card(
            width=5.3,
            height=1.40,
            title="Tactile Golden Rule: Touch Reads, Action Isolates",
            lines=[
                "• Resting fingers explore without triggering layout jumps",
                "• Double-press front Action key (or double-tap pin):",
                "  drills into atCurrentLevel(id) -> opens Panel 3!",
            ],
            accent_color=PIN_ON_CAPTION,
            title_size=14,
            body_size=12,
        ).move_to(RIGHT * 3.85 + DOWN * 2.55)

        # Pulse ring at finger position to represent double-tap / Action double-press
        pulse1 = Circle(radius=0.38, stroke_color=FOCUS_COLOR, stroke_width=3.0).move_to(pt_text)
        pulse2 = Circle(radius=0.54, stroke_color=PIN_ON_CAPTION, stroke_width=2.5).move_to(pt_text)

        self.play(
            FadeIn(cutaway_card, run_time=0.8),
            FadeIn(pulse1, run_time=0.4),
            FadeIn(pulse2, run_time=0.6),
        )
        self.wait(8.0)
