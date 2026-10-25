# Monarch Tactile Browser: Universal 96×40 Web, Book & Image Explorer

> **Hackathon Ideation & Architecture Document**
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
   - **Monarch Hardware & KeySoft SDK**: Leverages `SelfBraillingWidget`, `SelfBraillingManager` (including 40Hz `touchedPinCoordinates`), `Translator` (user-profile Grade 1/2/UEB Braille), `ContextMenuActivity`, `WriteCommandsXmlFileToInternalMemoryStorageExecutor`, `VibrationUtils`, `SoundUtils`, `DialogCreator`, and `.brf` export for **KeyBRF**.

---

## 2. Leveraging Built-in Monarch SDK & KeyBRF vs. Our Custom Implementation

### 2.1 What We Leverage Directly from the Built-in Monarch SDK & Ecosystem

| Built-in Monarch / KeySoft Capability | SDK Class / System Feature | How Our Browser Uses It |
|---|---|---|
| **Multi-Line Braille Reading & HTML Quick-Nav** | KeySoft Screen Reader + `WebView` + `KeysoftIME` | In **Text View**, `SelfBraillingWidget` is hidden (`View.GONE`) so KeySoft renders the webpage in 10×32 multi-line Braille for free, supports Pan Up/Down keys, handles Perkins Braille typing via `KeysoftIME`, and provides built-in single-letter HTML quick-nav (`B` = Button, `G` = Graphic, `H` = Heading, `L` = Link). |
| **Hardware Key Routing (`ZoomIn/Out`, `RightDpad`, `Panning`, Braille Chords)** | `WriteCommandsXmlFileToInternalMemoryStorageExecutor` + `res/raw/commands.xml` | Registers `<context viewIdResourceName="SelfBraillingWidget">` key substitutions on startup so hardware `ZoomIn` (`168`), `ZoomOut` (`169`), Left D-Pad (`19..22`), Right D-Pad (`59|19..22`), `PanningUp/Down` (`92/93`), `Dots8` (`66`), and Braille chord shortcuts reach our activity. |
| **Native Space+M Context Menu** | `ContextMenuActivity` (`com.humanware.keysoftsdk.contextmenu`) | Provides the native Monarch `Space+M` menu listing all browser & tactile viewer commands with their Braille chord shortcuts. |
| **Continuous 40Hz Finger Pin Polling + Point-and-Click** | `SelfBraillingManager.touchedPinCoordinates` + `SelfBraillingWidget` | Drives the 96×40 physical pins (`displayDots`), polls live finger coordinates `(x, y)` at 40Hz for **Finger-as-Cursor**, and receives `onSingleTapAtBraillePosition` / `onDoubleTapAtBraillePosition` (with `-3` X fingertip offset). |
| **User-Profile Braille Translation** | `Translator` + `TranslationQuery` (`BrailleGrade.GRADE_UNSPECIFIED`) | Translates component labels into Unicode Braille (`\u2800..\u28FF`) using the user's active KeySoft language profile for our 4-row bottom **Braille Caption Bar** (rows 36–39) and **KeyBRF** exports. |
| **Haptic Radar & Boundary Audio** | `VibrationUtils.startVibration` + `SoundUtils.errorBeep` | Fires a 30ms haptic pulse when the user's finger crosses into a new semantic component on the 96×36 canvas, and plays the standard KeySoft boundary beep at viewport/graph edges. |
| **KeyBRF (`.brf`) Offline Study Export** | `Translator` + `.brf` Formatter + Android Intent | Exports the current webpage/book page text + **Gemini 4 Argon's hierarchical spatial breakdown of every image** into a 32-cell-wide `.brf` file that opens directly in **KeyBRF** (`Space+S`). |
| **Ecosia & Tactile Viewer Shortcut Muscle-Memory** | `commands.xml` Chord Bindings | Preserves standard Monarch shortcuts: `Enter+O` (Open URL/Search), `Backspace+O` (Open current image/viewport in Tactile Viewer), `Backspace+D` (AI Spatial Overview), `Space+Dots 1..6` (9-point grid jumps), `Space+Dots 1-3` / `4-6` (Decrease/Increase detail). |

### 2.2 What We Implement Ourselves (100% Original Hackathon Contributions)

1. **Universal Text-View Image-Button & Scanned-Page Bridge (`bridge.js`)**:
   - In **Text View**, transforms every meaningful `<img>`, `<svg>`, `<canvas>`, and `<figure>` on any website into a focusable **`<button>` (`[Explore Tactile Image: <Label>]`)** so users can jump straight to any image using KeySoft's **`B`** button shortcut and press **Enter (Dot 8)** to open the 96×40 Tactile Viewer.
   - **Scanned Book Page / Webcomic Text Injection**: When a page is a scanned book image (e.g., on `archive.org` or Google Books Public Domain) or a webcomic with embedded dialogue/text, `gemini-4-argon` extracts both the **readable text blocks/speech bubbles** (injected directly into the DOM for KeySoft multi-line Braille reading) and the **cropped visual figures/panels** as tactile image buttons.
2. **Hybrid Gemini 4 Argon + Component-Snapped 1-Pin Tactile Compiler**:
   - **Standard `<svg>` Fast-Path**: Parses vector shapes (`<path>`, `<rect>`, `<circle>`, `<line>`, `<polygon>`, `<polyline>`) directly into crisp 1-pin tactile contours and groups them into navigable components.
   - **Universal Raster Image Path (`gemini-4-argon` + Adaptive Component-Masked Contours)**:
     - Calls `gemini-4-argon` to decompose any photo, illustration, diagram, or comic panel into a **hierarchical component tree** (`id`, `label`, `description`, `embeddedText`, `bbox: [ymin, xmin, ymax, xmax]`, `contourPolygon`, `lodTier`, `parentId`, `childIds`).
     - Runs an on-device adaptive Canny/contour edge extractor **masked and snapped to Argon's component bounding regions**—suppressing background noise and keeping raised-pin density **below 20%** for uncluttered tactile legibility.
3. **Generalized `Data Navigator` Spatial & Hierarchical Graph Engine**:
   - Adapts Frank Elavsky's [`data-navigator`](https://www.frank.computer/projects/) model to arbitrary visual scenes:
     - **Directional Edges (`up`, `down`, `left`, `right`)**: Computed from component centroids and spatial adjacency.
     - **Hierarchical Edges (`enter` / `exit`)**: Pressing **Enter (Dot 8)** on a component (e.g., *Panel 2* of a webcomic, the *Heart* in an anatomy diagram, or a *Figure* on a scanned book page) **isolates and auto-fits that single component** onto the 96×36 pin array and exposes its child sub-components!
4. **MonarchIO-Style 96×40 Tactile Image Viewer**:
   - **Rows 0–34 (`96×35` Tactile Canvas)** + **Row 35 (Blank Separator)** + **Rows 36–39 (`96×4` `BrailleCaptionRegion`)**.
   - **First-Finger Ownership (`FingerOwnership`)**: 40Hz polling of `manager.touchedPinCoordinates` that locks onto the first finger, ignores a resting second hand, vibrates via `VibrationUtils` on component entry, speaks the component label, and updates the bottom Braille caption bar.
   - **Dual Left D-Pad Modes**: Toggleable between **Spatial Cursor Mode** (1-pin cursor steps) and **Component Jump Mode** (`Data Navigator` node-to-node jumps + `Enter` to isolate/drill in).
   - **Right D-Pad Panning & `+`/`−` Progressive LOD Zoom (`1×` to `8×`)**: Smoothly pans and zooms the 96×35 viewport, revealing deeper component contours as you zoom in.

---

## 3. End-to-End User Journeys Across the Web (Validation Scenarios)

| Website Category | What Happens in Text View | What Happens When Clicking `[Explore Tactile Image]` (or Pressing `Backspace+O`) |
|---|---|---|
| **1. Scanned Books (`archive.org`, Google Books Public Domain)** | User opens a scanned page (`page.jpg`) and triggers *Decompose Scanned Page* (or clicks the page image). `gemini-4-argon` transcribes the printed page text into clean DOM paragraphs for KeySoft Braille reading and creates `[Explore Tactile Figure]` buttons for every illustration on the page. | Opens the cropped book illustration in 96×40: 1× overview shows the main subjects; Left D-Pad jumps between objects; `Enter` isolates a single object; `Space+S` exports the page text + tactile component guide to **KeyBRF (`.brf`)**. |
| **2. Webcomics (e.g., `xkcd.com`, multi-panel strips)** | Comic image becomes `[Explore Tactile Image: <Title/Alt>]`. When decomposed, panel dialogues and narration are extracted in reading order. | Top-level `Data Navigator` nodes represent **Panels 1..N** (or main characters). Pressing `Enter` on a panel isolates that panel across the full 96×35 canvas, reads its dialogue, and lets the user feel the characters and props inside that panel. |
| **3. Wikipedia, OpenStax & Newspapers** | Clean prose and headings read natively in KeySoft Braille. Every diagram (`<svg>`) and photo (`<img>`) is a focusable `<button>` reachable with KeySoft's **`B`** key. | SVGs render via direct 1-pin vector geometry; photos/diagrams render via `gemini-4-argon` component-snapped contours with Finger-as-Cursor, Right D-Pad pan, and `+`/`−` zoom. |

---

## 4. Hardware Control & Shortcut Summary

| Control / Shortcut | Behavior in Text View | Behavior in 96×40 Tactile Image Viewer |
|---|---|---|
| **Finger Glide (1st Finger)** | Standard KeySoft Braille line reading | **40Hz Finger-as-Cursor**: Vibrates on component boundary, speaks label, updates Rows 36–39 Braille Caption Bar |
| **Finger + Double-Press Action Button** | Point-and-click on HTML link or `[Explore Tactile Image]` button | Snaps cursor to `(x - 3, y)`, speaks full component detail & spatial relationships |
| **Left D-Pad** | Standard KeySoft navigation | **Spatial Cursor Mode**: Moves cursor 1 pin<br>**Component Jump Mode**: Jumps `up`/`down`/`left`/`right` in `Data Navigator` graph |
| **Enter (Dot 8)** | Activates focused link or `[Explore Tactile Image]` button | **Isolates & auto-fits** selected component onto 96×35 canvas (drills into child nodes) |
| **Right D-Pad** | Standard KeySoft navigation | **Pans viewport** (`North`, `South`, `East`, `West`) |
| **`+` / `−` Zoom Buttons** | Adjusts text/braille spacing | **Zooms viewport (`1×` → `8×`)** with progressive LOD contour reveal |
| **`Space + Dots 1..6` / `Space + Dots 2-5`** | Standard KeySoft chords | **Tactile Viewer 9-Grid Jumps** (Top-Left, Center, Bottom-Right, etc.) |
| **`Space + Dots 1-3` / `Space + Dots 4-6`** | Previous / Next element | **Decrease / Increase Tactile Detail Threshold** |
| **`Backspace + O`** | Opens focused image or current viewport in 96×40 Tactile Viewer | Re-centers / resets view to 1× Overview |
| **`Backspace + D`** | Decomposes current scanned book page into Braille text + figure buttons | Speaks & displays full Gemini 4 Argon AI Spatial Scene Summary |
| **`Space + S`** | Exports page text + tactile image breakdown to `.brf` (KeyBRF) | Exports current image's spatial component breakdown to `.brf` (KeyBRF) |
| **Back (Triangle / `Space + E`)** | Browser history Back | Steps out from isolated component → 1× image overview → returns to exact button in Text View |
