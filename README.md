# Monarch Tactile Browser: Universal 96×40 Web, Book & Image Explorer

> **Hackathon Ideation & Architecture Document.** The Android app lives in
> [`android-app/`](android-app/) (build and key list below); the long-term plan is in
> [`docs/LONG_TERM_PLAN.md`](docs/LONG_TERM_PLAN.md). Rows marked *(planned)* are not built yet.
> **100% New Hackathon Work:** Everything in this repository—including all scaffolding, Android/Kotlin code, JavaScript document-start bridges, Gemini 4 Argon spatial/text decomposers, `Data Navigator` spatial graph engine, and verification harnesses—is created from scratch during the hackathon event.

---

## 1. Core Design Philosophy: Site-Generalizable & Open-Source Permissive

1. **Zero Hardcoded Site Presets (`No Code Names a Site`)**:
   - The browser starts with a clean, accessible URL / Search bar—never a hardcoded wall of demo tiles.
   - Every capability relies strictly on standard web signals (`<img>`, `<svg>`, `<canvas>`, `<figure>`, ARIA roles, and viewport capture) so the exact same pipeline generalizes across **online books (`archive.org`, Google Books, Project Gutenberg, OpenStax), Wikipedia, newspapers, scientific articles, and webcomics**.
   - Real-world sites (Internet Archive, Google Books Public Domain, Wikipedia, webcomics, newspapers) are used strictly for **testing and validation**.
2. **Built on Permissive Open-Source Foundations + Native Monarch KeySoft SDK**:
   - **Web Engine**: Android Jetpack `androidx.webkit` (`WebView` + `DOCUMENT_START_SCRIPT`, Apache-2.0) paired with clutter-free DOM normalization inspired by `@mozilla/readability` (Apache-2.0).
   - **Spatial & Hierarchical Component Navigation**: Generalizes Frank Elavsky's open-source [`data-navigator`](https://www.frank.computer/projects/) (MIT License) from charts to **any image, diagram, scanned book page, or multi-panel webcomic**.
   - **Monarch Hardware & KeySoft SDK (0.1.131)**: Uses `SelfBraillingWidget`, `SelfBraillingManager` (`displayDots`, 40 Hz `touchedPinCoordinates`, `announceText`) and `Translator` (user-profile Braille). *(planned)* `ContextMenuActivity`, `commands.xml` chord registration, `.brf` export for **KeyBRF**.

---

## 2. Leveraging Built-in Monarch SDK & KeyBRF vs. Our Custom Implementation

### 2.1 What We Leverage Directly from the Built-in Monarch SDK & Ecosystem

| Built-in Monarch / KeySoft Capability | SDK Class / System Feature | How Our Browser Uses It |
|---|---|---|
| **Multi-Line Braille Reading & HTML Quick-Nav** | KeySoft Screen Reader + `WebView` + `KeysoftIME` | In **Text View**, `SelfBraillingWidget` is hidden (`View.GONE`) so KeySoft renders the webpage in 10×32 multi-line Braille for free, supports Pan Up/Down keys, handles Perkins Braille typing via `KeysoftIME`, and provides built-in single-letter HTML quick-nav (`B` = Button, `G` = Graphic, `H` = Heading, `L` = Link). |
| **Hardware Key Routing (`ZoomIn/Out`, `RightDpad`, `Panning`; chords planned)** | `WriteCommandsXmlFileToInternalMemoryStorageExecutor` + `res/raw/commands.xml` | Registers `<context viewIdResourceName="SelfBraillingWidget">` key substitutions on startup so hardware `ZoomIn` (`168`), `ZoomOut` (`169`), Left D-Pad (`19..22`), Right D-Pad (`59|19..22`), `PanningUp/Down` (`92/93`), `Dots8` (`66`), and Braille chord shortcuts reach our activity. |
| **Native Space+M Context Menu** *(planned)* | `ContextMenuActivity` (`com.humanware.keysoftsdk.contextmenu`) | Provides the native Monarch `Space+M` menu listing all browser & tactile viewer commands with their Braille chord shortcuts. |
| **Continuous 40Hz Finger Pin Polling + Point-and-Click** | `SelfBraillingManager.touchedPinCoordinates` + `SelfBraillingWidget` | Drives the 96×40 physical pins (`displayDots`), polls live finger coordinates `(x, y)` at 40Hz for **Finger-as-Cursor**, and receives `onDoubleTapAtBraillePosition` (finger + Action double-press). With SDK 0.1.131 the double-press point matches the polled finger, so no X offset is applied. |
| **User-Profile Braille Translation** | `Translator` + `TranslationQuery` (`BrailleGrade.GRADE_UNSPECIFIED`) | Translates component labels into Unicode Braille (`\u2800..\u28FF`) using the user's active KeySoft language profile for our 4-row bottom **Braille Caption Bar** (rows 36–39) and **KeyBRF** exports. |
| **Boundary Audio** | Android `ToneGenerator` | A short beep at the first/last part and when a press hits nothing. No vibration: touch only reads. |
| **KeyBRF (`.brf`) Offline Study Export** *(planned)* | `Translator` + `.brf` Formatter + Android Intent | Exports the current webpage/book page text + **Gemini 4 Argon's hierarchical spatial breakdown of every image** into a 32-cell-wide `.brf` file that opens directly in **KeyBRF** (`Space+S`). |
| **Ecosia & Tactile Viewer Shortcut Muscle-Memory** *(planned)* | `commands.xml` Chord Bindings | Preserves standard Monarch shortcuts: `Enter+O` (Open URL/Search), `Backspace+O` (Open current image/viewport in Tactile Viewer), `Backspace+D` (AI Spatial Overview), `Space+Dots 1..6` (9-point grid jumps), `Space+Dots 1-3` / `4-6` (Decrease/Increase detail). |

### 2.2 What We Implement Ourselves (100% Original Hackathon Contributions)

1. **Universal Text-View Image-Button & Scanned-Page Bridge (`bridge.js`)**:
   - In **Text View**, transforms every meaningful `<img>`, `<svg>`, `<canvas>`, and `<figure>` on any website into a focusable **`<button>` (`[Explore Tactile Image: <Label>]`)** so users can jump straight to any image using KeySoft's **`B`** button shortcut and press **Enter (Dot 8)** to open the 96×40 Tactile Viewer.
   - **Scanned Book Page / Webcomic Text Injection**: When a page is a scanned book image (e.g., on `archive.org` or Google Books Public Domain) or a webcomic with embedded dialogue/text, `gemini-4-argon` extracts both the **readable text blocks/speech bubbles** (injected directly into the DOM for KeySoft multi-line Braille reading) and the **cropped visual figures/panels** as tactile image buttons.
2. **Hybrid Gemini 4 Argon + Component-Snapped 1-Pin Tactile Compiler**:
   - **Standard `<svg>` Fast-Path**: Parses vector shapes (`<path>`, `<rect>`, `<circle>`, `<line>`, `<polygon>`, `<polyline>`) directly into crisp 1-pin tactile contours and groups them into navigable components.
   - **Universal Raster Image Path (`gemini-4-argon` + Adaptive Component-Masked Contours)**:
     - Sends a 768 px copy of any photo, illustration, diagram, or comic panel to `gemini-4-argon` and gets back a **structured tactile graphic**: a component tree (`id`, `label`, `parentId`, `role`, `box: [x0, y0, x1, y1]`, `strokes` as centerline polylines, `embeddedText`, `description`; `docs/LONG_TERM_PLAN.md` §9).
     - Draws strokes as 1-pin lines, outlines only (no texture); text parts are touch regions without pins; every panel gets a frame with a blank pin column between frames; fill stays **≤ 25%** by dropping the smallest parts' strokes first.
     - Each result is stored under the SHA-256 of the image bytes, so the same image is never sent to Argon twice.
3. **Generalized `Data Navigator` Spatial & Hierarchical Graph Engine**:
   - Adapts Frank Elavsky's [`data-navigator`](https://www.frank.computer/projects/) model to arbitrary visual scenes:
     - **Directional Edges (`up`, `down`, `left`, `right`)**: Computed from component centroids and spatial adjacency.
     - **Hierarchical Edges (`enter` / `exit`)**: Pressing **Enter (Dot 8)** on a component (e.g., *Panel 2* of a webcomic, the *Heart* in an anatomy diagram, or a *Figure* on a scanned book page) **isolates and auto-fits that single component** onto the 96×36 pin array and exposes its child sub-components!
4. **MonarchIO-Style 96×40 Tactile Image Viewer**:
   - **Rows 0–34 (`96×35` Tactile Canvas)** + **Row 35 (Blank Separator)** + **Rows 36–39 (`96×4` `BrailleCaptionRegion`)**.
   - **Finger tracking**: 40 Hz polling of `manager.touchedPinCoordinates` off the UI thread, following one finger when a second hand comes down. Entering a part speaks it and puts its name in the Braille caption; **the canvas pins never change while you touch**.
   - **Left D-Pad**: previous/next part at the current level (`Data Navigator` reading order); `Enter` / Action double-press opens a part. *(planned)* a 1-pin Spatial Cursor Mode.
   - **Right D-Pad Panning & `+`/`−` Zoom**: 2× per press on the selected part; pans a quarter of the view.

---

## 3. End-to-End User Journeys Across the Web (Validation Scenarios)

| Website Category | What Happens in Text View | What Happens When Clicking `[Explore Tactile Image]` (or Pressing `Backspace+O`) |
|---|---|---|
| **1. Scanned Books (`archive.org`, Google Books Public Domain)** | User opens a scanned page (`page.jpg`) and triggers *Decompose Scanned Page* (or clicks the page image). `gemini-4-argon` transcribes the printed page text into clean DOM paragraphs for KeySoft Braille reading and creates `[Explore Tactile Figure]` buttons for every illustration on the page. | Opens the cropped book illustration in 96×40: 1× overview shows the main subjects; Left D-Pad jumps between objects; `Enter` isolates a single object; `Space+S` exports the page text + tactile component guide to **KeyBRF (`.brf`)**. |
| **2. Webcomics (e.g., `xkcd.com`, multi-panel strips)** | Comic image becomes `[Explore Tactile Image: <Title/Alt>]`. When decomposed, panel dialogues and narration are extracted in reading order. | Top-level `Data Navigator` nodes represent **Panels 1..N** (or main characters). Pressing `Enter` on a panel isolates that panel across the full 96×35 canvas, reads its dialogue, and lets the user feel the characters and props inside that panel. |
| **3. Wikipedia, OpenStax & Newspapers** | Clean prose and headings read natively in KeySoft Braille. Every diagram (`<svg>`) and photo (`<img>`) is a focusable `<button>` reachable with KeySoft's **`B`** key. | SVGs render via direct 1-pin vector geometry; photos/diagrams render via `gemini-4-argon` component-snapped contours with Finger-as-Cursor, Right D-Pad pan, and `+`/`−` zoom. |

---

## 4. Keys and Shortcuts

Single keys only for now; no chord is needed. Every key press is logged
(`adb logcat -s MonarchTactile`), so a key that does nothing can be checked.

### Tactile Image Viewer

| Shortcut (Monarch) | USB keyboard | Action |
|---|---|---|
| Finger on the pins | none | Hear the part under the finger; its name appears in Braille on rows 36–39. The canvas does not change. Open space is silent. |
| Finger + double-press the Action button | none | Open the part under the finger at the current level: on the whole image, the panel or figure the finger is in; inside it, the part under the finger. A part with nothing inside is read out (label, description, text). |
| Left D-Pad left or up, or Pan Up | ← or ↑, Page Up | Previous part at the same level |
| Left D-Pad right or down, or Pan Down | → or ↓, Page Down | Next part at the same level |
| Enter (dot 8) or Space | Space (Enter is kept by KeySoft) | Open the selected part (the one last touched or stepped to) |
| Back (triangle) | Esc | Out one level; from the whole image, back to the web page |
| Zoom + (oval, bottom right) | `+` or `=` | Zoom 2× on the selected part |
| Zoom − (oval, bottom left) | `-` | Zoom out one step (same as Back while zoomed) |
| Right D-Pad | Shift + arrow, keypad 8/2/4/6 | Pan a quarter of the view (when zoomed) |

The zoom ovals, D-Pads, Pan keys and dot 8 reach the viewer because the app registers
them with KeySoft in `res/raw/commands.xml` (as MonarchIO does), scoped to the pin
widget so web-page reading keys are unchanged.

KeySoft may take some keys before the app sees them (MonarchTempest
`docs/PHASE0-HARDWARE-FINDINGS.md`): Esc and character keys from a USB keyboard, and
left/right on the left D-Pad in some modes. Use the Monarch key in the same row instead.

### Browser (Text View)

| Shortcut (Monarch) | USB keyboard | Action |
|---|---|---|
| KeySoft reading keys, `B` quick-nav | same | Read the page; every picture is followed by a button **Explore tactile image: \<label\>** |
| Enter (dot 8) on that button | Enter | Open the picture in the Tactile Image Viewer |
| Back (triangle) | Esc | Previous page |

### Planned chords *(not built yet)*

| Shortcut | Action |
|---|---|
| Space+M (dots 1-3-4-9) | Context menu listing every command |
| Enter+O | Open URL / search |
| Backspace+O | Open the focused image in the Tactile Image Viewer |
| Backspace+D | Spoken overview of the whole image |
| Space+S (dots 2-3-4-9) | Export page text and image breakdown to KeyBRF (`.brf`) |
| Space+dots 1-3 / 4-6 | Less / more detail |

## 5. Build and Install

```bash
cd android-app
# KeySoftSDK-0.1.131.aar goes in app/libs/ (proprietary, git-ignored)
cmd.exe /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& gradlew.bat :app:assembleDebug"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug build reads `GEMINI_API_KEY` from the repo's `.env` so new images can be sent to
Argon; do not share that APK.
