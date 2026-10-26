# Step 5 Report — Long 3Blue1Brown-Style Explainer Video (`agent/step-5`)

## 1. Summary of What Was Built

1. **Claim Verification Table (`docs/video/CLAIMS.md`)**:
   - Maps 18 technical claims across all 9 scenes directly to their exact source files and commits (`README.md`, `docs/LONG_TERM_PLAN.md`, `AGENTS.md`, `docs/agent/DECISIONS.md`, `docs/agent/STEP*_REPORT.md`, `android-app/`, `tactile-core/`, `bridge/`, `tier2/`, and commit `8cb1c44`).
   - Explicitly separates **Built Today** vs. **Next** items.

2. **Full Narration & Visual Script (`docs/video/SCRIPT.md`)**:
   - 9 scenes covering all 8 required topics in 3Blue1Brown style (`#0e1117` dark background, step-by-step diagram construction from real repository data, calm narration, burned-in captions).

3. **Exact Python Port of `GraphicScene.kt` + Fast 3B1B Pin Grid Mobjects**:
   - `scripts/video/graphic_scene_py.py`: exact port of `GraphicScene.kt`, `Braille.kt`, and `HitGrid.kt` rendering the bundled `android-app/app/src/main/assets/graphics/0c7b09d66d4dcf0b315bab40833a8b876702bec2d15c826b86c351a7b4fe1c50.json` (`xkcd #327`, 17 parts, `645 / 3,360 = 19.2%` overview fill, unfixed vs. fixed `panelFrames` showing columns `68`/`69` vs `67`/`69`, and isolated Panel 3 at `656 / 3,360 = 19.5%` fill).
   - `scripts/video/style.py`: shared 3Blue1Brown palette and `PinGridMobject`.

4. **9 Manim Community Edition Scenes (`scripts/video/scenes/scene01..09_*.py`)**:
   - `scene01_problem.py` (`Scene01Problem`) — `34.83s`: 96×40 physical pin display, 41.2% naive threshold failure vs. 19.2% structured 1-pin contour graphic, rows 0–34 canvas, row 35 blank separator, rows 36–39 Braille caption, plus `omni_01_hand_pins` cutaway.
   - `scene02_bridge.py` (`Scene02Bridge`) — `40.12s`: zero-site-rule `bridge.js` + `extract.js` across the 7 benchmark cases (`xkcd #327`, `Wikipedia Heart`, `archive.org 1898 Alice page 8`, `Google Books 1898 Alice`, `Gutenberg Rackham plates`, `OpenStax Figure 40.11`, `LOC Tribune 1912`).
   - `scene03_structured_graphic.py` (`Scene03StructuredGraphic`) — `37.09s`: `ImageNormalizer` (768 px on white canvas), SHA-256 `DiskCache`, `gemini-4-argon` (`thinking_level: "low"`) component tree with centerline strokes, outline-only colour, and pin-free `role: "text"` touch regions.
   - `scene04_renderer.py` (`Scene04Renderer`) — `39.93s`: `GraphicScene.kt` `fitToCanvas`, 1-pin Bresenham lines, the `xkcd` Panel 3 / Panel 4 border collision bug (columns 68 & 69 touching) and `panelFrames` fix (pulling Panel 3 right border to column 67 so column 68 is blank), and smallest-area-first `dropOrder` (`fill <= 25%`).
   - `scene05_touch_and_hitgrid.py` (`Scene05TouchAndHitGrid`) — `33.04s`: `HitGrid.kt` 1-pin halo + text interior regions, smaller `pinBox` area winning on overlap (`p3_mom` 80 pins over `panel_3` 1,008 pins), 40 Hz `FingerPoll` updating speech and Braille caption while canvas pins never move, plus `omni_02_double_press` cutaway.
   - `scene06_data_navigator.py` (`Scene06DataNavigator`) — `34.20s`: `ComponentGraph` levels, `atCurrentLevel(id)` on Action double-press, transition from 4-panel overview to isolated Panel 3 (`19.5%` fill), Left D-pad sibling stepping, 2× zoom, and Right D-pad pan.
   - `scene07_hardware_lessons.py` (`Scene07HardwareLessons`) — `42.23s`: 5 hardware lessons on Monarch SDK `0.1.131` (key routing, zero X offset in commit `8cb1c44`, no vibration motor, KeySoft `Translator`, and `SightedOverlay` `NumLock` toggle).
   - `scene08_testing_and_judge.py` (`Scene08TestingAndJudge`) — `39.83s`: the 7 benchmark cases, `test-sites/judge.py` deterministic `exact_checks` (`9/9` pass on `xkcd-327`) + Sonnet/Argon rubric grading, and Steps 0–4 test suites (`16` JUnit 5, `13` Playwright, `6` Tier-2 tests).
   - `scene09_next_steps.py` (`Scene09NextSteps`) — `32.98s`: Built Today vs. Next Steps (`tier2/` + `bridge/` integration into `android-app/`, `commands.xml` chords + `ContextMenuActivity` + `KeyBRF` export, and hard WebGL/tile map sites).

5. **Bounded Gemini Omni & TTS Orchestration (`scripts/video/omni.py`, `scripts/video/tts.py`, `scripts/video/make_video.py`)**:
   - Strictly enforces the Step 5 budget (at most 3 Omni calls and 12 TTS calls) and a 2-failure circuit breaker (`MAX_FAILURES = 2`).
   - In the sandbox container, `generativelanguage.googleapis.com` is not MITM-intercepted by `GoProxy` and returns `400 API_KEY_INVALID` on the literal placeholder `$GEMINI_API_KEY`; both `omni.py` and `tts.py` tripped their 2-failure circuit breaker immediately after 2 calls (never looping on paid calls) and used the procedural Manim tactile cutaways (`omni_01_hand_pins`, `omni_02_double_press`) and neural `edge-tts` (`en-US-ChristopherNeural`) narration audio.
   - Assembled `monarch-explainer.mp4` (`1920x1080`, `24 fps`, `334.33s` / `5m 34s`, `14.1 MB`) with spoken narration and burned-in captions (`docs/video/out/monarch-explainer.srt`).

6. **GitHub Release & README Link**:
   - Created GitHub release `explainer-v1` at `https://github.com/Znasif/monarch-chromium/releases/tag/explainer-v1` with direct download link `https://github.com/Znasif/monarch-chromium/raw/explainer-v1-asset/monarch-explainer.mp4` (hosted on standalone orphan tag `explainer-v1-asset` so `.mp4` binaries are never committed to `agent/step-5` or `main`).
   - Updated `README.md` with the new `## Explainer video` section.
