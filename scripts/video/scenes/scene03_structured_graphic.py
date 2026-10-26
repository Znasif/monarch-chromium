"""Scene 3 — Structured Tactile Graphics: 768 px -> gemini-4-argon -> SHA-256 Cache."""

from __future__ import annotations

from manim import (
    Arrow,
    Create,
    DOWN,
    FadeIn,
    GREY_A,
    GREY_B,
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
    PANEL_BG,
    PIN_DENSE_BAD,
    PIN_ON_CANVAS,
    PIN_ON_CAPTION,
    PIN_SEP_COLOR,
    make_card,
    make_header,
    setup_3b1b_scene,
)


class Scene03StructuredGraphic(Scene):
    def construct(self) -> None:
        setup_3b1b_scene(self)
        header = make_header(
            "SCENE 03",
            "Structured Tactile Graphic (LONG_TERM_PLAN.md §9)",
            "768 px on white -> gemini-4-argon (thinking_level: low) -> SHA-256 indexed JSON",
        )
        self.play(FadeIn(header, shift=DOWN * 0.2), run_time=1.2)

        # Left column: 3-stage pipeline (SHA-256 Cache -> 768px White Composite -> Gemini 4 Argon)
        step1 = make_card(
            width=4.6,
            height=1.35,
            title="1. SHA-256 Content Cache (GraphicStore)",
            lines=[
                "key = sha256(imageBytes)  # 0c7b09d6...json",
                "Lookup: filesDir -> assets/graphics -> Argon",
                "Same image is never sent to Argon twice",
            ],
            accent_color=PIN_SEP_COLOR,
            title_size=16,
            body_size=12,
        )
        step2 = make_card(
            width=4.6,
            height=1.35,
            title="2. 768 px White-Canvas Preprocessor",
            lines=[
                "Composite alpha onto #FFFFFF white background",
                "(Dark labels on transparent PNGs stay visible)",
                "Scale longest side <= 768 px",
            ],
            accent_color=PIN_ON_CANVAS,
            title_size=16,
            body_size=12,
        )
        step3 = make_card(
            width=4.6,
            height=1.35,
            title="3. gemini-4-argon (thinking_level: low)",
            lines=[
                "Returns component tree + centerline polylines",
                "Normalized 0..1 coordinates (origin top-left)",
                "Validated JSON before saving to <sha256>.json",
            ],
            accent_color=PIN_ON_CAPTION,
            title_size=16,
            body_size=12,
        )

        left_col = VGroup(step1, step2, step3).arrange(DOWN, buff=0.28)
        left_col.to_edge(LEFT, buff=0.50).shift(DOWN * 0.35)
        a12 = Arrow(step1.get_bottom(), step2.get_top(), buff=0.04, color=WHITE, stroke_width=2.2)
        a23 = Arrow(step2.get_bottom(), step3.get_top(), buff=0.04, color=WHITE, stroke_width=2.2)

        self.play(FadeIn(step1, shift=RIGHT * 0.15), run_time=1.2)
        self.play(Create(a12), FadeIn(step2, shift=RIGHT * 0.15), run_time=1.2)
        self.play(Create(a23), FadeIn(step3, shift=RIGHT * 0.15), run_time=1.2)
        self.wait(1.0)

        # Right column: Structured JSON Schema + Two Tactile Rules
        schema_bg = RoundedRectangle(
            corner_radius=0.14,
            width=7.8,
            height=2.85,
            fill_color=PANEL_BG,
            fill_opacity=0.98,
            stroke_color="#58a6ff",
            stroke_width=2.0,
        )
        schema_lines = [
            '{ "components": [',
            '    { "id": "panel_3", "label": "Panel 3: The SQL injection name",',
            '      "parentId": null, "role": "panel", "box": [0.464, 0.0, 0.72, 1.0],',
            '      "strokes": [{"points": [[0.465,0.005],[0.719,0.005],...], "closed": true}] },',
            '    { "id": "p3_mom", "label": "Mom drinking from her mug",',
            '      "parentId": "panel_3", "role": "figure", "box": [0.482, 0.49, 0.53, 0.957],',
            '      "strokes": [{"points": [[0.514,0.519],[0.519,0.552],...], "closed": false}] },',
            '    { "id": "p3_text_phone", "parentId": "panel_3", "role": "text",',
            '      "strokes": [], "embeddedText": "DID YOU REALLY NAME YOUR SON Robert\');..." }',
            '  ] }',
        ]
        code_vg = VGroup()
        for idx, ln in enumerate(schema_lines):
            col = WHITE
            if '"role": "panel"' in ln:
                col = PIN_ON_CANVAS
            elif '"role": "figure"' in ln:
                col = FINGER_COLOR
            elif '"role": "text"' in ln or '"strokes": []' in ln:
                col = FOCUS_COLOR
            t = Text(ln, font="DejaVu Sans Mono", font_size=12, color=col)
            if t.width > 7.4:
                t.scale_to_fit_width(7.4)
            code_vg.add(t)
        code_vg.arrange(DOWN, aligned_edge=LEFT, buff=0.07)
        schema_group = VGroup(schema_bg, code_vg)
        schema_group.to_edge(RIGHT, buff=0.45).shift(UP * 0.48)
        code_vg.move_to(schema_bg.get_center())

        self.play(FadeIn(schema_group, shift=LEFT * 0.2), run_time=1.8)
        self.wait(1.5)

        rule1 = make_card(
            width=3.8,
            height=1.55,
            title="Rule A: Colour Is Never Texture",
            lines=[
                "Regions are 1-pin outlines only (no dot fill)",
                "Colour meaning goes into component labels",
                "(e.g. 'Oxygen-rich' vs 'Oxygen-poor' blood)",
            ],
            accent_color=PIN_ON_CAPTION,
            title_size=15,
            body_size=12,
        )
        rule2 = make_card(
            width=3.8,
            height=1.55,
            title="Rule B: Text Has Zero Pins at 1x",
            lines=[
                "role='text' has empty strokes: []",
                "Exact words stored in embeddedText",
                "Touchable region -> read via Braille & TTS",
            ],
            accent_color=FOCUS_COLOR,
            title_size=15,
            body_size=12,
        )
        rules_row = VGroup(rule1, rule2).arrange(RIGHT, buff=0.20)
        rules_row.next_to(schema_group, DOWN, buff=0.22)

        self.play(FadeIn(rule1, shift=UP * 0.15), FadeIn(rule2, shift=UP * 0.15), run_time=1.4)
        self.wait(3.2)
