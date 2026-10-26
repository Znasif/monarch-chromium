# Monarch Tactile Browser — 3Blue1Brown-Style Explainer Script (`SCRIPT.md`)

- **Target Duration:** ~4 minutes 56 seconds (`296 s`) across 9 scenes
- **Speaking Rate:** ~140 words per minute (~685 words total)
- **Visual Style:** 3Blue1Brown mathematical/diagrammatic explainer (`#0e1117` dark slate background, step-by-step geometric construction, real repository data from `android-app/app/src/main/assets/graphics/0c7b09d66d4dcf0b315bab40833a8b876702bec2d15c826b86c351a7b4fe1c50.json`, burned-in accessible captions at `1920×1080`)
- **Generators:**
  - **Manim Community Edition (`v0.22.0`)**: All 9 scenes (`scripts/video/scenes/scene01_problem.py` .. `scene09_next_steps.py`)
  - **Gemini Omni (`gemini-omni-1.1-flash`)**: Used for at most 2 short physical-hand B-roll cutaways (`<= 8 s` each, embedded inside Scene 1 and Scene 5 via `scripts/video/omni.py`), with deterministic Manim fallback when run offline or without a live video endpoint key.
  - **Narration (`gemini-3.8-flash-tts`)**: One audio file per scene (`scripts/video/tts.py`, voice `"Kore"`), with neural TTS fallback when run without a live audio endpoint key.

---

## Scene Summary Table

| Scene | Title | Duration | Generator | Code File |
|---|---|---|---|---|
| **1** | The Problem: A 96×40 Pin Display & the 25% Fill Ceiling | `32 s` | **Manim + Omni** (`omni_01_hand_pins.mp4`, `6 s`) | `scripts/video/scenes/scene01_problem.py` |
| **2** | The Browser & `bridge.js`: Zero Site Rules Across 7 Real Cases | `34 s` | **Manim** | `scripts/video/scenes/scene02_bridge.py` |
| **3** | Structured Tactile Graphics: 768 px → `gemini-4-argon` → SHA-256 Cache | `35 s` | **Manim** | `scripts/video/scenes/scene03_structured_graphic.py` |
| **4** | The Renderer (`GraphicScene.kt`): 1-Pin Lines & the xkcd Panel 3/4 Bug Fix | `36 s` | **Manim** (real `xkcd-327` JSON) | `scripts/video/scenes/scene04_renderer.py` |
| **5** | Touch & `HitGrid`: O(1) 40 Hz Polling & Static Canvas Reading | `34 s` | **Manim + Omni** (`omni_02_double_press.mp4`, `6 s`) | `scripts/video/scenes/scene05_touch_and_hitgrid.py` |
| **6** | `Data Navigator` Levels: Action Double-Press, Panel Isolation, Zoom & Pan | `34 s` | **Manim** (real `xkcd-327` Panel 3) | `scripts/video/scenes/scene06_data_navigator.py` |
| **7** | Hardware Lessons on the Physical Monarch (KeySoft SDK 0.1.131) | `33 s` | **Manim** | `scripts/video/scenes/scene07_hardware_lessons.py` |
| **8** | Verification: The 7 Site Cases, `judge.py`, and Agent Steps 0–4 | `30 s` | **Manim** | `scripts/video/scenes/scene08_testing_and_judge.py` |
| **9** | What Is Built Today vs. What Comes Next | `28 s` | **Manim** | `scripts/video/scenes/scene09_next_steps.py` |

---

## Scene-by-Scene Script, Visuals & Narration

### Scene 1 — The Problem: A 96×40 Pin Display & the 25% Fill Ceiling
- **Duration:** `32 s`
- **Generator:** **Manim** (`scripts/video/scenes/scene01_problem.py`) + **Gemini Omni** (`omni_01_hand_pins.mp4`, `6 s` cutaway of fingers exploring a physical array of raised tactile pins, no logos or brands)
- **Visuals:**
  1. Construct a `96 × 40` matrix of `3,840` pins on a dark background.
  2. Brace-label rows `0–34` in cyan (`96 × 35 = 3,360` tactile canvas pins), highlight row `35` in amber (`dots[35].fill(0)` blank separator row), and highlight rows `36–39` in green (`96 × 4` Braille caption bar = `32` 8-dot cells at 3-column pitch).
  3. Side-by-side comparison: naive pixel thresholding/dithering of an image (`54%` raised fill = unreadable tactile wall of bumps) vs. sparse 1-pin centerline outlines (`19.2%` fill <= `25%` ceiling = `840` pins max).
  4. Insert the `6 s` Omni cutaway showing fingertips lightly tracing sparse raised pins.
- **Narration (76 words):**
  > "How do you browse a visual web page by touch? The HumanWare Monarch gives a blind reader a grid of ninety-six by forty physical pins: rows zero through thirty-four form the tactile canvas, row thirty-five stays blank as a separator, and rows thirty-six through thirty-nine hold a thirty-two-cell Braille caption bar. If you dump a sighted picture directly onto those pins, shading and dithering raise more than half the grid into an unreadable wall of bumps. To stay readable to a fingertip, tactile graphics must use one-pin outlines and keep raised-pin fill at or below twenty-five percent."

---

### Scene 2 — The Browser & `bridge.js`: Zero Site Rules Across 7 Real Cases
- **Duration:** `34 s`
- **Generator:** **Manim** (`scripts/video/scenes/scene02_bridge.py`)
- **Visuals:**
  1. Show the core rule banner: `No Code Names a Site` (`AGENTS.md` §1.1).
  2. Animate a DOM tree (`<img>`, `<svg>`, `<canvas>`, `<figure>`) flowing through `bridge.js` label resolution priority: `alt` → `aria-label` → `<figcaption>` / `.caption` → humanized filename.
  3. Show `<button>Explore tactile image: <label></button>` inserted after the image so KeySoft's `B` quick-nav key jumps straight to every figure in Text View.
  4. Animate cards for the 7 real test cases from `test-sites/cases/*.json`: `xkcd #327`, `Wikipedia Heart`, `archive.org 1898 Alice (p. 8)`, `Google Books 1898 Alice`, `Gutenberg Rackham plates` (with single-letter drop-cap `"A" + "lice" -> "Alice"` splicing), `OpenStax Figure 40.11`, and `LOC New-York Tribune (1912)`.
- **Narration (82 words):**
  > "In Text View, KeySoft reads the page natively in multi-line Braille. Our injected script, bridge dot J S, uses zero site-specific rules. On any page, it inspects standard DOM signals—alt text, aria-label, figcaption, nearby caption containers, or the filename—and inserts an 'Explore tactile image' button right after every meaningful picture. Pressing B jumps between image buttons across webcomics like xkcd 327, Wikipedia SVG diagrams, OpenStax biology figures, Gutenberg Rackham plates, scanned 1898 Alice pages, and the 1912 New-York Tribune."

---

### Scene 3 — Structured Tactile Graphics: 768 px → `gemini-4-argon` → SHA-256 Cache
- **Duration:** `35 s`
- **Generator:** **Manim** (`scripts/video/scenes/scene03_structured_graphic.py`)
- **Visuals:**
  1. Flow diagram of `GraphicStore.kt` & `scripts/tactile_graphic.py`: raw image bytes → `SHA-256` hash (`0c7b09d66d4dcf0b...`) → check local cache (`filesDir/graphics/<sha256>.json` and bundled `assets/graphics/<sha256>.json`).
  2. On cache miss: composite transparent pixels onto white (`#FFFFFF` so black labels on transparent textbook PNGs stay visible), downscale longest side to `768 px`, and send to `gemini-4-argon` (`thinking_level: "low"`).
  3. Display the `LONG_TERM_PLAN.md` §9 JSON schema tree: `role: panel | figure | object | text | arrow`, normalized `box [x0, y0, x1, y1]`, centerline `strokes`, `embeddedText`, and `description`. Highlight the two key rules: colour meaning goes into labels (never fill textures), and `role == "text"` parts are touch regions with zero raised pins at `1x`.
- **Narration (83 words):**
  > "When you open an image, GraphicStore hashes its raw bytes with SHA-256 so the same picture is never sent to the model twice. On a cache miss, it composites transparent backgrounds onto white, scales the longest side to seven hundred sixty-eight pixels, and asks gemini-4-argon at low thinking level for a structured tactile graphic. Argon returns a component tree with normalized bounding boxes and centerline polylines. Colour meaning becomes labels rather than fill texture, and text regions carry embeddedText with zero raised pins."

---

### Scene 4 — The Renderer (`GraphicScene.kt`): 1-Pin Lines & the xkcd Panel 3/4 Bug Fix
- **Duration:** `36 s`
- **Generator:** **Manim** (`scripts/video/scenes/scene04_renderer.py` — renders the actual bundled `0c7b09d66d4dcf0b315bab40833a8b876702bec2d15c826b86c351a7b4fe1c50.json` through the exact rules of `GraphicScene.kt`)
- **Visuals:**
  1. Show `fitToCanvas` equation: `canvasAspect = (96 - 1) / (35 - 1) = 95 / 34 ≈ 2.794` preserving source pixel aspect ratio (`666 × 205`) so circles stay round on square-pitch pins.
  2. Plot the real `xkcd-327` (`Exploits of a Mom`) pins from `0c7b09d6...json` using Bresenham 1-pin lines (`line()`).
  3. Zoom into the boundary between **Panel 3** (`xmax = 0.720`) and **Panel 4** (`xmin = 0.735`):
     - **Before fix:** `normalizedToPinX(0.720) = 68` and `normalizedToPinX(0.735) = 69` (`b[0] - a[2] == 1`). Their vertical borders touch on adjacent columns `68` and `69` with zero blank pins between them!
     - **Fix in `GraphicScene.panelFrames()`:** `if (overlapY && b[0] - a[2] < 2 && a[2] >= b[0] - 2) a[2] = b[0] - 2` shifts Panel 3's right border to column `67`, guaranteeing column `68` stays blank (`>= 1` blank pin column between frames).
  4. Show density drop order: non-panel parts sorted by `box.width * box.height` so smallest interior details drop first if raised pins exceed `25%`.
- **Narration (86 words):**
  > "Here is the real bundled xkcd 327 graphic rendered by GraphicScene dot K T. First, fitToCanvas widens the view box to the ninety-five-over-thirty-four canvas aspect ratio so shapes never stretch. Next, Bresenham's algorithm draws one-pin centerlines. Notice Panel 3 and Panel 4: in normalized coordinates, their borders land on adjacent columns sixty-eight and sixty-nine, fusing together under a fingertip. Our panelFrames rule enforces a gap of at least two columns, pulling Panel 3's right border to column sixty-seven so column sixty-eight stays a clean blank separator."

---

### Scene 5 — Touch & `HitGrid`: O(1) 40 Hz Polling & Static Canvas Reading
- **Duration:** `34 s`
- **Generator:** **Manim** (`scripts/video/scenes/scene05_touch_and_hitgrid.py`) + **Gemini Omni** (`omni_02_double_press.mp4`, `6 s` cutaway of a finger tracing pins while a second hand presses the front action button)
- **Visuals:**
  1. Visualize `HitGrid` (`IntArray(96 * 40)`) over the real `xkcd-327` pin grid: colour-coded component IDs with a 1-pin touch halo (`haloRadius = 1`).
  2. Show overlap priority (`D-03`): smaller child components (`p3_mom`, `p3_text_phone`) overwrite larger parent panel containers (`panel_3`) so interior figures and speech zones are directly touchable; row `35` (`0`) and caption rows `36–39` (`-1`) are isolated (`D-04`).
  3. Animate the `40 Hz` (`25 ms`) `FingerPoll` thread (`TactileActivity.kt`) tracking a moving finger across `panel_1` -> `p1_text_phone` -> `p1_mom`: the bottom Braille caption bar (`rows 36–39`) and speech (`announceText`) update immediately, while **not a single pin on rows `0–34` moves**.
  4. Insert the `6 s` Omni cutaway of two-handed tactile interaction.
- **Narration (79 words):**
  > "To make touch instantaneous, HitGrid pre-stamps every component ID and a one-pin halo into a flat ninety-six-by-forty integer array. Because smaller children overwrite larger parents, a finger inside Panel 3 hits Mom or the speech bubble rather than just the outer panel box. Off the UI thread, FingerPoll reads touched pin coordinates forty times a second. As your finger moves, KeySoft speaks each part and updates the Braille caption on rows thirty-six to thirty-nine—while the canvas pins underneath your hand never move."

---

### Scene 6 — `Data Navigator` Levels: Action Double-Press, Panel Isolation, Zoom & Pan
- **Duration:** `34 s`
- **Generator:** **Manim** (`scripts/video/scenes/scene06_data_navigator.py` — real `xkcd-327` tree + Panel 3 isolated view from `0c7b09d6...json`)
- **Visuals:**
  1. Draw the two-level `ComponentGraph` (`Data Navigator` hierarchy) for `xkcd-327`: Root level (`panel_1`, `panel_2`, `panel_3`, `panel_4`) and Panel 3's children (`p3_text_phone`, `p3_mom`, `p3_text_mom`).
  2. Show `atCurrentLevel(id)` in `TactileActivity.kt`: while viewing the whole strip (`container = null`), touching `p3_mom` and double-pressing the Action button (`onDoubleTapAtBraillePosition`) walks up `parentId` to `panel_3` and opens **Panel 3**.
  3. Morph the `96 × 35` pin grid from the 4-panel overview into the isolated **Panel 3** view (`fitToCanvas(panel_3.box)`), showing Mom raising her mug at full height (`rows 1..33`) with the top and bottom speech touch zones.
  4. Animate Left D-Pad stepping across `p3_text_phone` -> `p3_mom` -> `p3_text_mom`, Zoom `+` (`2×` on Mom's head and mug), Right D-Pad (`Shift + arrow`) panning by `1/4` view, and `Back` returning to the 4-panel overview.
- **Narration (82 words):**
  > "Navigation follows Frank Elavsky's Data Navigator graph model. On the whole-image overview, touching anywhere inside Panel 3 and double-pressing the Action button—or pressing Enter—calls atCurrentLevel, resolves the touch to Panel 3, and isolates that panel across the entire ninety-six-by-thirty-five canvas. Now Mom's stick figure fills thirty-three rows of height, and Left D-Pad steps through Panel 3's children in reading order: the school's SQL injection question, Mom drinking from her mug, and her Bobby Tables reply. Plus and Minus zoom two-X, and Right D-Pad pans."

---

### Scene 7 — Hardware Lessons on the Physical Monarch (KeySoft SDK 0.1.131)
- **Duration:** `33 s`
- **Generator:** **Manim** (`scripts/video/scenes/scene07_hardware_lessons.py`)
- **Visuals:**
  1. Five hardware-verified cards from `origin/local/app` commits (`015364c`..`0d03178`) and `README.md` §4 / `AGENTS.md` §2:
     - **Card 1 — Exact Double-Press Coordinates (`ca1a786`):** On KeySoft SDK `0.1.131`, `onDoubleTapAtBraillePosition(x, y)` matches `touchedPinCoordinates` with **zero X offset** (removing the old `-3` shift that pushed taps off small parts).
     - **Card 2 — No Vibration (`17b01b9`):** Touch only reads (speech + Braille caption); `ToneGenerator` beeps only at graph boundaries or empty double-presses.
     - **Card 3 — Hardware Key Routing (`8860cd0`, `0c8f5fe`):** Right D-Pad sends `KEYCODE_SHIFT_LEFT/RIGHT` within `300 ms` before `DPAD_*`; Left D-Pad and `Pan Up/Down` step siblings.
     - **Card 4 — KeySoft `Translator` (`3fe9192`, `Braille.kt`):** `BrailleGrade.GRADE_UNSPECIFIED` renders user-profile 8-dot Unicode Braille (`U+2800..U+28FF`) on rows `36–39`.
     - **Card 5 — `SightedOverlay` (`1ec009d`):** Renders the bitmap, translucent red pin dots, blue selection rectangle, green finger ring, and print caption for HDMI/`scrcpy` while keeping `SelfBraillingWidget` at `alpha = 0.02f` (`NumLock` toggles plain pin mirror).
- **Narration (81 words):**
  > "Testing on the physical Monarch with KeySoft SDK zero-point-one-point-one-three-one taught us five hardware lessons. First, Action double-press coordinates match the polled finger position exactly, so no X offset is applied. Second, we removed vibration so touch is purely silent reading, using a short tone only at graph boundaries. Third, the Right D-Pad sends a Shift key event just before its arrow key. Fourth, KeySoft's Translator renders the caption bar in the user's own Braille grade. Fifth, SightedOverlay lets HDMI observers see the picture, pins, focus box, and finger live."

---

### Scene 8 — Verification: The 7 Site Cases, `judge.py`, and Agent Steps 0–4
- **Duration:** `30 s`
- **Generator:** **Manim** (`scripts/video/scenes/scene08_testing_and_judge.py`)
- **Visuals:**
  1. Show the `test-sites/judge.py` pipeline: takes a case file (`test-sites/cases/<id>.json`) and a generated result JSON, runs **9 exact checks** (`fill:overview1x <= 0.25`, `fill:isolate:p3 <= 0.25`, `fill:zoom2x <= 0.25`, `fill:zoom4x <= 0.25`, `box:Panel 1..4 IoU >= 0.5`, `topLevelCount == 4`), and grades semantic/tactile rubric items with `claude-sonnet-5-5` locally (`claude -p`) or `gemini-4-argon` in the sandbox (`--grader argon`).
  2. Display the green verification matrix across Agent Steps 0–4:
     - **Step 0 (`STEP0_PLAN.md`):** Architecture & module contracts
     - **Step 1 (`tactile-core` model/hit/nav/edit):** `12/12` JUnit 5 tests green
     - **Step 2 (`SvgCompiler` + `RasterContourCompiler`):** `16/16` JUnit 5 tests green, `< 8 ms` compile time, `9/9` exact checks pass
     - **Step 3 (`bridge/`):** `13/13` Playwright tests green across headless Chromium & Firefox
     - **Step 4 (`tier2/` + `StrokeRenderer` on `agent/step-4`):** `6/6` recorded-fixture tests green, `9/9` exact checks pass
- **Narration (72 words):**
  > "Every module is verified against our seven real-site test cases without scraping live sites in tests. Our judge dot P Y harness runs deterministic checks on pin fill ratios, panel bounding-box IoU, and component counts, then grades tactile legibility with Claude Sonnet locally or Gemini 4 Argon in the sandbox. Across Steps 0 through 4, tactile-core passes sixteen JUnit 5 tests, bridge passes thirteen Playwright tests in both Chromium and Firefox, and tier-two passes six recorded-fixture tests."

---

### Scene 9 — What Is Built Today vs. What Comes Next
- **Duration:** `28 s`
- **Generator:** **Manim** (`scripts/video/scenes/scene09_next_steps.py`)
- **Visuals:**
  1. Two-column architectural roadmap table (**Built in This Repo Today** vs. **Next / Planned**):
     - **Built Today:** `android-app/` (`BrowserActivity`, `TactileActivity`, `GraphicScene`, `GraphicStore`, `Braille`, `SightedOverlay`), `tactile-core/` (`PinGrid`, `HitGrid`, `ComponentGraph`, `SvgCompiler`, `RasterContourCompiler`, `SceneEditStore`), `bridge/` (`bridge.js` + Chromium/Firefox tests), `tier2/` (`GeminiArgonProvider`, `StrokeRenderer`, `Tier2SceneMerger` on `agent/step-4`), and `test-sites/judge.py`.
     - **Next (Planned):**
       1. Wire `tier2/` async hot-swap and full `bridge/src/bridge.js` (SVG/Canvas2D geometry capture, drop-cap splicing, scanned-page OCR text injection) into `android-app/` behind `BrowserEngine`.
       2. Register hardware Braille chords via `res/raw/commands.xml`, `ContextMenuActivity` (`Space+M`), and **KeyBRF (`.brf`)** study export (`Space+S`).
       3. Support hard WebGL and tile map sites (`LONG_TERM_PLAN.md` §7: Google Maps/Earth, OpenStreetMap `#map=zoom/lat/lon`, Mapillary) and shared SHA-256 derived tactile index (`§10`).
- **Narration (68 words):**
  > "What is left to build next? First, wiring the standalone tier-two hot-swap module and the full SVG and Canvas2D geometry bridge directly into the Android app behind the pluggable BrowserEngine interface. Second, registering hardware Braille chords in commands dot X M L for the Space-plus-M context menu and Space-plus-S KeyBRF export. And third, extending our tactile compiler to WebGL maps like OpenStreetMap, Google Maps, and Mapillary."
