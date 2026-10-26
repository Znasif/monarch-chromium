"""Scene 8 — Testing & Evaluation: 7 Real Sites, Exact Checks & Judge (32 seconds).

Visualizes test-sites/cases/*.json, test-sites/judge.py, and the test suites across Steps 0–4:
1. All 7 benchmark cases and their synthetic fixtures.
2. Two-layer evaluation in test-sites/judge.py: deterministic exact_checks (9/9 on xkcd-327)
   + LLM rubric grading (claude-sonnet-4-6 locally, gemini-4-argon in sandbox).
3. Automated test results across Steps 0–4 (16 JUnit 5 tests, 13 Playwright tests, 6 Tier-2 tests).
"""
from __future__ import annotations

import sys
from pathlib import Path
from manim import (
    Scene, VGroup, Text, RoundedRectangle,
    FadeIn, FadeOut,
    UP, DOWN, LEFT, RIGHT, WHITE, GREY_A, GREY_B
)

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from scripts.video.style import (
    PANEL_BG, PIN_ON_CANVAS, PIN_ON_CAPTION, PIN_SEP_COLOR, FOCUS_COLOR, FINGER_COLOR,
    setup_3b1b_scene, make_header, make_card
)


class Scene08TestingAndJudge(Scene):
    def construct(self):
        setup_3b1b_scene(self)

        header = make_header(
            "SCENE 8",
            "Testing & Evaluation: 7 Benchmark Cases, Exact Checks & Judge",
            "test-sites/cases/*.json · test-sites/judge.py (exact_checks + Sonnet/Argon rubric) · Steps 0–4"
        )
        self.play(FadeIn(header, run_time=0.8))

        # Left Card: test-sites/judge.py Two-Layer Evaluation
        judge_card = make_card(
            width=6.15,
            height=4.75,
            title="test-sites/judge.py — Two-Layer Evaluation",
            lines=[
                "Layer 1: Deterministic run_exact_checks(case, actual)",
                "  ✓ textView.headingsInclude & linksInclude",
                "  ✓ exploreButtons[0].label == \"Explore tactile image: ...\"",
                "  ✓ canvasFill <= 0.25 (Tier-1: 0.1682, Tier-2: 0.1932)",
                "  ✓ separatorRow35Clear == true",
                "  ✓ textComponentsPinFree == true",
                "  ✓ panelCount == 4 & componentIoU >= 0.65 (all 4 panels)",
                "  ✓ touchHit at (12,15), (32,15), (58,15), (82,15)",
                "  -> Result: 9/9 exact checks PASS on xkcd-327",
                "",
                "Layer 2: 5-Criterion Model Rubric (0–10 each)",
                "  • Local: claude-sonnet-4-6 (Anthropic Messages API)",
                "  • Sandbox: gemini-4-argon (Interactions API, low thinking)",
            ],
            accent_color=PIN_ON_CAPTION,
            title_size=15,
            body_size=11,
        ).move_to(LEFT * 3.25 + DOWN * 0.15)

        # Right Top Card: The 7 Benchmark Cases
        cases_card = make_card(
            width=6.15,
            height=2.20,
            title="7 Real Web Benchmark Cases (test-sites/cases/*.json)",
            lines=[
                "1. xkcd-327 (Exploits of a Mom, 4 panels, 666×205)",
                "2. wikipedia-heart (Heart diagram, 9 anatomical parts)",
                "3. archive-org-alice-1898 (BookReader 1898 Alice page 8)",
                "4. google-books-alice-1898 (Viewport page scan)",
                "5. gutenberg-alice-rackham (13 Arthur Rackham plates)",
                "6. openstax-biology-fig-40-11 (Pulmonary/systemic circuits)",
                "7. loc-newspaper-1912 (OpenSeadragon 1912 Tribune front page)",
            ],
            accent_color=PIN_ON_CANVAS,
            title_size=14,
            body_size=11,
        ).move_to(RIGHT * 3.25 + UP * 1.12)

        # Right Bottom Card: Steps 0–4 Green Test Suites
        steps_card = make_card(
            width=6.15,
            height=2.35,
            title="Agent Steps 0–4 — Automated Verification Suites",
            lines=[
                "• Step 0 (agent/step-0): Alignment & STEP0_PLAN.md",
                "• Step 1 (agent/step-1): tactile-core model, HitGrid,",
                "  ComponentGraph, Braille — 9/9 JUnit 5 tests green",
                "• Step 2 (agent/step-2): Tier-1 TactileCompiler,",
                "  EditableScene, KeyBRF & SVG — 16/16 JUnit 5 tests green",
                "• Step 3 (agent/step-3): bridge.js + extract.js —",
                "  13/13 Playwright Chromium tests across all 7 sites",
                "• Step 4 (agent/step-4): Tier-2 Gemini segmenter,",
                "  SHA-256 DiskCache & StrokeRenderer — 6/6 tests green",
            ],
            accent_color=FOCUS_COLOR,
            title_size=14,
            body_size=11,
        ).move_to(RIGHT * 3.25 + DOWN * 1.35)

        self.play(
            FadeIn(judge_card, run_time=1.0),
            FadeIn(cases_card, run_time=1.0),
        )
        self.wait(8.0)

        self.play(FadeIn(steps_card, run_time=0.9))
        self.wait(21.3)
