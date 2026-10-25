"""Scene 2 — The Browser & bridge.js: Zero Site Rules Across 7 Real Cases."""

from __future__ import annotations

from manim import (
    Arrow,
    Create,
    DOWN,
    FadeIn,
    FadeOut,
    LEFT,
    RIGHT,
    RoundedRectangle,
    Scene,
    Text,
    UP,
    VGroup,
    WHITE,
)

from scripts.video.style import (
    FINGER_COLOR,
    FOCUS_COLOR,
    PIN_ON_CANVAS,
    PIN_ON_CAPTION,
    PIN_SEP_COLOR,
    make_card,
    make_header,
    setup_3b1b_scene,
)


class Scene02Bridge(Scene):
    def construct(self) -> None:
        setup_3b1b_scene(self)
        header = make_header(
            "SCENE 02",
            "Browser & bridge.js: Universal Image-to-Button Promotion",
            "Zero hardcoded site rules ('No Code Names a Site') — standard DOM signals only",
        )
        self.play(FadeIn(header, shift=DOWN * 0.2), run_time=1.2)

        # Top pipeline: DOM element -> Label cascade -> Injected Button -> KeySoft 'B' key
        dom_box = make_card(
            width=3.6,
            height=1.55,
            title="1. Web DOM Element",
            lines=[
                "<img>, <svg>, <canvas>",
                "Skip icons/spacers (< 64×64 px)",
                "Drop-cap 'A' + 'lice' -> 'Alice'",
            ],
            accent_color=PIN_ON_CANVAS,
            title_size=17,
            body_size=13,
        )
        cascade_box = make_card(
            width=4.1,
            height=1.55,
            title="2. General Label Cascade",
            lines=[
                "1) alt / aria-label",
                "2) <figcaption> / .caption / .thumb",
                "3) Humanized src filename",
            ],
            accent_color=PIN_SEP_COLOR,
            title_size=17,
            body_size=13,
        )
        btn_box = make_card(
            width=4.5,
            height=1.55,
            title="3. Injected Tactile Button",
            lines=[
                "<button>Explore tactile image: <label>",
                "KeySoft 'B' quick-nav jumps here",
                "Enter (Dot 8) -> TactileActivity",
            ],
            accent_color=PIN_ON_CAPTION,
            title_size=17,
            body_size=13,
        )

        top_flow = VGroup(dom_box, cascade_box, btn_box).arrange(RIGHT, buff=0.45)
        top_flow.shift(UP * 1.15)

        arr1 = Arrow(dom_box.get_right(), cascade_box.get_left(), buff=0.06, color=WHITE, stroke_width=2.5)
        arr2 = Arrow(cascade_box.get_right(), btn_box.get_left(), buff=0.06, color=WHITE, stroke_width=2.5)

        self.play(FadeIn(dom_box, shift=UP * 0.15), run_time=1.1)
        self.play(Create(arr1), FadeIn(cascade_box, shift=UP * 0.15), run_time=1.2)
        self.play(Create(arr2), FadeIn(btn_box, shift=UP * 0.15), run_time=1.2)
        self.wait(1.0)

        # Bottom grid: The 7 real-site test cases from test-sites/cases/*.json
        cases_data = [
            ("xkcd-327", "Webcomic (666×205)", "alt='Exploits of a Mom' + title tooltip", "#58a6ff"),
            ("wikipedia-heart", "Encyclopedia SVG", ".thumbcaption & infobox diagram + blood-flow arrows", "#3fb950"),
            ("openstax-bio-heart", "Textbook Fig 40.11", "Concise <figcaption> label; long alt -> description", "#d29922"),
            ("gutenberg-alice-rackham", "Illustrated HTML Book", "Rackham plate captions + drop-cap 'A'+'lice' splice", "#bc8cff"),
            ("archive-alice-1898", "Archive.org Scan (p.8)", "BookReader page 8 + Tenniel curtain/door engraving", "#f0883e"),
            ("googlebooks-alice-1898", "Google Books Scan", "Public-domain 1898 viewport scan -> tactile figure", "#79c0ff"),
            ("loc-tribune-1912-04-16", "1912 NY Tribune Scan", "Titanic headline, wreck map, 2 photos, stats table", "#ff7b72"),
        ]

        row1 = VGroup()
        row2 = VGroup()
        for idx, (cid, kind, rule, col) in enumerate(cases_data):
            w = 3.12 if idx < 4 else 4.15
            card = make_card(
                width=w,
                height=1.25,
                title=f"{cid}",
                lines=[kind, rule],
                accent_color=col,
                title_size=15,
                body_size=12,
            )
            if idx < 4:
                row1.add(card)
            else:
                row2.add(card)

        row1.arrange(RIGHT, buff=0.22)
        row2.arrange(RIGHT, buff=0.25)
        cases_grid = VGroup(row1, row2).arrange(DOWN, buff=0.22)
        cases_grid.shift(DOWN * 1.45)

        for card in row1:
            self.play(FadeIn(card, shift=UP * 0.12), run_time=0.65)
        for card in row2:
            self.play(FadeIn(card, shift=UP * 0.12), run_time=0.65)

        # Highlight cursor stepping through buttons with KeySoft 'B' key
        b_badge = RoundedRectangle(
            corner_radius=0.1,
            width=5.4,
            height=0.46,
            fill_color="#1f6feb",
            fill_opacity=0.95,
            stroke_color=WHITE,
            stroke_width=1.5,
        )
        b_txt = Text(
            "KeySoft 'B' Quick-Nav -> Jumps Across All 7 Cases",
            font="DejaVu Sans Mono",
            font_size=14,
            color=WHITE,
            weight="BOLD",
        )
        b_txt.move_to(b_badge.get_center())
        b_group = VGroup(b_badge, b_txt).to_edge(DOWN, buff=0.16)

        self.play(FadeIn(b_group), run_time=0.9)
        self.wait(3.0)
