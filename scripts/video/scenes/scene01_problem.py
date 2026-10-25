"""Scene 1 — The Problem: A 96x40 Pin Display & the 25% Fill Ceiling."""

from __future__ import annotations

import random
from manim import (
    FadeOut,
    Circle,
    Create,
    DOWN,
    FadeIn,
    LEFT,
    Line,
    RIGHT,
    Scene,
    Text,
    Transform,
    UP,
    VGroup,
)

from scripts.video.graphic_scene_py import (
    CANVAS_HEIGHT,
    CAPTION_START_ROW,
    PIN_HEIGHT,
    PIN_WIDTH,
    SEPARATOR_ROW,
    GraphicScenePy,
)
from scripts.video.style import (
    FINGER_COLOR,
    PIN_DENSE_BAD,
    PIN_ON_CANVAS,
    PIN_ON_CAPTION,
    PIN_SEP_COLOR,
    PinGridMobject,
    make_card,
    make_header,
    setup_3b1b_scene,
)


class Scene01Problem(Scene):
    def construct(self) -> None:
        setup_3b1b_scene(self)
        header = make_header(
            "SCENE 01",
            "The 96×40 Tactile Pin Display & the 25% Fill Limit",
            "Why sighted pictures fail on physical pins without semantic centerline compilation",
        )
        self.play(FadeIn(header, shift=DOWN * 0.2), run_time=1.2)

        xkcd = GraphicScenePy.from_xkcd_asset()
        rendered = xkcd.render(caption_text="Panel 1: Exploits of a Mom")

        empty_grid = PinGridMobject(pitch=0.082, show_frame=True)
        empty_grid.shift(LEFT * 1.65 + DOWN * 0.35)
        self.play(FadeIn(empty_grid), run_time=1.5)

        p_top = empty_grid.pin_point(PIN_WIDTH - 1, 0)
        p_cbot = empty_grid.pin_point(PIN_WIDTH - 1, CANVAS_HEIGHT - 1)
        p_sep = empty_grid.pin_point(PIN_WIDTH - 1, SEPARATOR_ROW)
        p_cap_top = empty_grid.pin_point(PIN_WIDTH - 1, CAPTION_START_ROW)
        p_cap_bot = empty_grid.pin_point(PIN_WIDTH - 1, PIN_HEIGHT - 1)

        canvas_card = make_card(
            width=4.4,
            height=1.35,
            title="Rows 0–34: Tactile Canvas",
            lines=[
                "96 × 35 = 3,360 physical pins",
                "1-pin outlines; max 25% fill (<= 840 pins)",
            ],
            accent_color=PIN_ON_CANVAS,
            title_size=18,
            body_size=14,
        )
        sep_card = make_card(
            width=4.4,
            height=0.95,
            title="Row 35: Blank Separator",
            lines=["Always 0 (dots[35].fill(0)); isolates halos"],
            accent_color=PIN_SEP_COLOR,
            title_size=17,
            body_size=14,
        )
        cap_card = make_card(
            width=4.4,
            height=1.25,
            title="Rows 36–39: Braille Caption Bar",
            lines=[
                "96 × 4 pins (3-col stride = 32 Braille cells)",
                "KeySoft Translator user grade (U+2800..U+28FF)",
            ],
            accent_color=PIN_ON_CAPTION,
            title_size=18,
            body_size=14,
        )
        cards = VGroup(canvas_card, sep_card, cap_card).arrange(DOWN, buff=0.22)
        cards.to_edge(RIGHT, buff=0.45).shift(DOWN * 0.35)

        conn1 = Line(
            [p_top[0] + 0.18, 0.5 * (p_top[1] + p_cbot[1]), 0],
            canvas_card.get_left(),
            color=PIN_ON_CANVAS,
            stroke_width=2.0,
        )
        conn2 = Line(
            [p_sep[0] + 0.18, p_sep[1], 0],
            sep_card.get_left(),
            color=PIN_SEP_COLOR,
            stroke_width=2.0,
        )
        conn3 = Line(
            [p_cap_top[0] + 0.18, 0.5 * (p_cap_top[1] + p_cap_bot[1]), 0],
            cap_card.get_left(),
            color=PIN_ON_CAPTION,
            stroke_width=2.0,
        )

        self.play(FadeIn(canvas_card, shift=LEFT * 0.2), Create(conn1), run_time=1.4)
        self.play(FadeIn(sep_card, shift=LEFT * 0.2), Create(conn2), run_time=1.2)
        self.play(FadeIn(cap_card, shift=LEFT * 0.2), Create(conn3), run_time=1.4)
        self.wait(1.5)

        rng = random.Random(327)
        dither_full = [[False for _ in range(PIN_WIDTH)] for _ in range(PIN_HEIGHT)]
        color_dither = {}
        for y in range(CANVAS_HEIGHT):
            for x in range(PIN_WIDTH):
                if rendered.canvas_pins[y][x] or rng.random() < 0.44:
                    dither_full[y][x] = True
                    color_dither[(x, y)] = PIN_DENSE_BAD
        for y in range(CAPTION_START_ROW, PIN_HEIGHT):
            for x in range(PIN_WIDTH):
                dither_full[y][x] = rendered.full_pins[y][x]

        dither_grid = PinGridMobject(
            pitch=0.082,
            show_frame=True,
            raised_full=dither_full,
            color_by_part=color_dither,
        )
        dither_grid.move_to(empty_grid.get_center())

        bad_banner = make_card(
            width=8.2,
            height=0.85,
            title="Naive Pixel Threshold / Dithering: 54.2% Fill (1,821 / 3,360 pins)",
            lines=["Shading, halftone & thick edges fuse into an unreadable tactile wall of bumps"],
            accent_color=PIN_DENSE_BAD,
            title_size=17,
            body_size=14,
        )
        bad_banner.to_edge(DOWN, buff=0.25).shift(LEFT * 1.65)

        self.play(FadeOut(empty_grid), FadeIn(dither_grid), FadeIn(bad_banner, shift=UP * 0.15), run_time=2.0)
        self.wait(3.0)

        clean_grid = PinGridMobject(
            pitch=0.082,
            show_frame=True,
            raised_full=rendered.full_pins,
        )
        clean_grid.move_to(empty_grid.get_center())

        good_banner = make_card(
            width=8.2,
            height=0.85,
            title=f"Monarch 1-Pin Centerline Compiler: {rendered.fill_ratio * 100:.1f}% Fill ({rendered.raised_canvas_count} / 3,360 pins <= 25%)",
            lines=["4 panel frames with 1-pin blank separators, Mom stick figures, and Braille caption on rows 36–39"],
            accent_color=PIN_ON_CANVAS,
            title_size=17,
            body_size=14,
        )
        good_banner.move_to(bad_banner.get_center())

        self.play(
            FadeOut(dither_grid), FadeIn(clean_grid),
            FadeOut(bad_banner), FadeIn(good_banner),
            run_time=2.2,
        )
        self.wait(2.0)

        finger_ring = Circle(radius=0.26, stroke_color=FINGER_COLOR, stroke_width=4.0, fill_color=FINGER_COLOR, fill_opacity=0.18)
        finger_lbl = Text("Finger explores 1-pin contours", font="DejaVu Sans", font_size=15, color=FINGER_COLOR, weight="BOLD")

        p_start = clean_grid.pin_point(9, 18)
        finger_ring.move_to(p_start)
        finger_lbl.next_to(finger_ring, UP, buff=0.12)
        self.play(FadeIn(finger_ring), FadeIn(finger_lbl), run_time=0.8)

        waypoints = [(16, 22), (33, 18), (52, 18), (78, 18)]
        for (wx, wy) in waypoints:
            pt = clean_grid.pin_point(wx, wy)
            self.play(
                finger_ring.animate.move_to(pt),
                finger_lbl.animate.next_to(pt, UP, buff=0.18),
                run_time=1.35,
            )

        self.wait(2.5)
