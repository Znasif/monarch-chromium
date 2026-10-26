"""Scene 9 — What's Next: Wiring Tier-2 & Bridge into Android, Chords & Hard Maps (24 seconds).

Explicitly distinguishes Built Today vs. Planned Next Steps:
1. Built Today: android-app/ on Monarch SDK 0.1.131, tactile-core/, bridge/, tier2/.
2. Next Steps: wiring tier2/ + bridge/ into android-app/ behind BrowserEngine,
   commands.xml chords + ContextMenuActivity + KeyBRF export, and LONG_TERM_PLAN.md §7/§10.
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


class Scene09NextSteps(Scene):
    def construct(self):
        setup_3b1b_scene(self)

        header = make_header(
            "SCENE 9",
            "Built Today vs. What's Next on the Roadmap",
            "Clear boundary between verified code in the repo today and upcoming integration work"
        )
        self.play(FadeIn(header, run_time=0.8))

        built_card = make_card(
            width=6.15,
            height=4.75,
            title="BUILT & VERIFIED TODAY (In This Repo)",
            lines=[
                "✓ android-app/ on Physical Monarch (SDK 0.1.131):",
                "  BrowserActivity, DetailActivity, GraphicScene.kt,",
                "  GraphicView.kt, SightedOverlay.kt, BrailleTranslator.kt",
                "✓ tactile-core/ (Pure Kotlin JVM, 16 JUnit 5 tests):",
                "  HitGrid, ComponentGraph, Braille, TactileCompiler,",
                "  StrokeRenderer, EditableScene, KeyBrfExporter, SvgExporter",
                "✓ bridge/ (13 Playwright Chromium tests across 7 sites):",
                "  bridge.js Explore buttons + extract.js Text View JSON",
                "✓ tier2/ (6 unit tests + recorded xkcd-327 fixture):",
                "  768px white-canvas normalizer, Gemini segmenter, SHA-256 cache",
            ],
            accent_color=PIN_ON_CAPTION,
            title_size=15,
            body_size=11,
        ).move_to(LEFT * 3.25 + DOWN * 0.15)

        next_card = make_card(
            width=6.15,
            height=4.75,
            title="PLANNED NEXT STEPS (Roadmap §6, §7, §10)",
            lines=[
                "1. Wire tier2/ & bridge/ into android-app/:",
                "   Connect live WebViewEngine image bytes -> Tier2Segmenter",
                "   -> StrokeRenderer -> DetailActivity (with Tier-1 fallback)",
                "2. Hardware Chords & On-Device KeyBRF Export:",
                "   Add companion ContextMenuActivity for commands.xml chords",
                "   and wire EditableScene + KeyBrfExporter (.brf) to hardware",
                "3. Hard Map & Canvas Sites (LONG_TERM_PLAN.md §7):",
                "   Vector/WebGL/tile extractors for OpenStreetMap, USGS,",
                "   Google Maps, Windy & Flightradar24",
                "4. Shared Tactile Index (LONG_TERM_PLAN.md §10):",
                "   Optional SHA-256 community cache of human-edited graphics",
            ],
            accent_color=PIN_ON_CANVAS,
            title_size=15,
            body_size=11,
        ).move_to(RIGHT * 3.25 + DOWN * 0.15)

        self.play(
            FadeIn(built_card, run_time=1.0),
            FadeIn(next_card, run_time=1.0),
        )
        self.wait(21.2)
