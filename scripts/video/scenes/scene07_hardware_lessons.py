"""Scene 7 — What We Learned on Physical Monarch Hardware (30 seconds).

Covers the 5 real hardware lessons discovered on Monarch SDK 0.1.131 (origin/local/app):
1. Physical key routing (Left D-pad vs Right D-pad SHIFT prefix within 300ms, front Action = ENTER,
   Point-and-Click intercepted by OS, commands.xml requires ContextMenuActivity).
2. Zero X offset on onDoubleTapAtBraillePosition(row, col) on SDK 0.1.131 (commit 8cb1c44).
3. No vibration motor (all feedback is tactile pins + TTS speech).
4. KeySoft Translator (BrailleTranslator.kt) -> 8-dot Unicode Braille on rows 36–39 (32 cells).
5. SightedOverlay.kt (alpha=0.02f so GraphicView gets touch events; NumLock toggles HDMI view).
"""
from __future__ import annotations

import sys
from pathlib import Path
from manim import (
    Scene, VGroup, Text, RoundedRectangle, Rectangle, Line, Dot,
    FadeIn, FadeOut,
    UP, DOWN, LEFT, RIGHT, WHITE, GREY_A, GREY_B
)

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from scripts.video.style import (
    PANEL_BG, PIN_ON_CANVAS, PIN_ON_CAPTION, PIN_SEP_COLOR, PIN_DENSE_BAD,
    FOCUS_COLOR, FINGER_COLOR,
    setup_3b1b_scene, make_header, make_card
)


class Scene07HardwareLessons(Scene):
    def construct(self):
        setup_3b1b_scene(self)

        header = make_header(
            "SCENE 7",
            "What We Learned on Physical Monarch Hardware (SDK 0.1.131)",
            "Key routing · Zero X offset (8cb1c44) · No vibration · KeySoft Translator · SightedOverlay (NumLock)"
        )
        self.play(FadeIn(header, run_time=0.8))

        # Card 1 (top-left): Which keys reach the app vs intercepted by OS
        keys_card = make_card(
            width=6.15,
            height=2.25,
            title="1. Physical Key Routing (DetailActivity.kt)",
            lines=[
                "• Left D-pad -> DPAD_UP/DOWN/LEFT/RIGHT (steps siblings)",
                "• Right D-pad -> DPAD_* prefixed by SHIFT within 300 ms (pans)",
                "• Front Action key -> KEYCODE_ENTER (1x=zoom, 2x <500ms=isolate)",
                "• Point-and-Click -> intercepted by OS cursor (never reaches app)",
                "• commands.xml chords -> require companion ContextMenuActivity",
            ],
            accent_color=PIN_ON_CANVAS,
            title_size=15,
            body_size=11,
        ).move_to(LEFT * 3.25 + UP * 1.25)

        # Card 2 (top-right): Zero X offset + No vibration motor
        touch_card = make_card(
            width=6.15,
            height=2.25,
            title="2. Zero X Offset & No Vibration Motor",
            lines=[
                "• SDK 0.1.131 onDoubleTapAtBraillePosition(row, col):",
                "  Delivers exact 0..95 column & 0..39 row — ZERO X offset!",
                "  (Commit 8cb1c44 removed stale +3 column offset assumption)",
                "• Vibrator.hasVibrator() == false on Monarch hardware:",
                "  All non-visual feedback uses tactile pins + Android TTS speech",
            ],
            accent_color=FOCUS_COLOR,
            title_size=15,
            body_size=11,
        ).move_to(RIGHT * 3.25 + UP * 1.25)

        self.play(FadeIn(keys_card, run_time=0.9), FadeIn(touch_card, run_time=0.9))
        self.wait(6.5)

        # Card 3 (bottom-left): KeySoft Translator for rows 36–39 Braille caption
        braille_card = make_card(
            width=6.15,
            height=2.25,
            title="3. KeySoft Translator on Rows 36–39 (BrailleTranslator.kt)",
            lines=[
                "• Binds HumanWare KeySoft Translator service when installed",
                "• Translates component label to 8-dot Unicode Braille (U+2800..FF)",
                "• Braille.writeCaption() plots 32 cells × 3-pin stride (cols 0–95)",
                "• Fallback: built-in Grade-1 table + number indicator (dots 3456)",
                "• Row 35 stays permanently lowered as a 1-pin tactile gutter",
            ],
            accent_color=PIN_ON_CAPTION,
            title_size=15,
            body_size=11,
        ).move_to(LEFT * 3.25 + DOWN * 1.45)

        # Card 4 (bottom-right): SightedOverlay for HDMI viewers (NumLock toggle)
        hdmi_card = make_card(
            width=6.15,
            height=2.25,
            title="4. SightedOverlay for HDMI Viewers (SightedOverlay.kt)",
            lines=[
                "• GraphicView must stay uncovered to receive raw touch events",
                "• SightedOverlay sits at alpha = 0.02f by default (invisible)",
                "• Pressing KEYCODE_NUM_LOCK toggles full-opacity HDMI overlay:",
                "  - Raised pin dots (white on canvas, green on caption row)",
                "  - Coloured component boxes, green finger ring & status bar",
            ],
            accent_color=PIN_SEP_COLOR,
            title_size=15,
            body_size=11,
        ).move_to(RIGHT * 3.25 + DOWN * 1.45)

        self.play(FadeIn(braille_card, run_time=0.9), FadeIn(hdmi_card, run_time=0.9))
        self.wait(18.0)
