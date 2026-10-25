# Step 3 Report — `bridge/` JavaScript (`STEP3_REPORT.md`)

## 1. What Was Built

1. **Engine-Neutral Document-Start Script (`bridge/src/bridge.js`)**:
   - **Image-to-Button Promotion (`promoteImages`)**:
     - Scans `<img>`, `<svg>`, `<canvas>`, `<figure>`, and `[role="img"]` elements and inserts focusable `<button type="button" class="monarch-tactile-btn">Explore tactile image: ...</button>` elements before each meaningful visual target while keeping the original DOM element in place with `aria-hidden="true"` (`D-06`).
     - **Hover `title` Exposure (`xkcd-327`)**: When an element carries both a primary label (`alt`) and a distinct `title` attribute (hover text), exposes the `title` text in `button.textContent` (`Explore tactile image: <label> — <titleText>`), `button.title`, `data-monarch-title`, and `exportTextView()`.
     - **Encyclopedia & Textbook Caption Resolution (`wikipedia-heart`, `openstax-bio-heart`, `D-15`)**: Extracts labels from `<figcaption>` or surrounding thumbnail caption containers (`[class*="thumbcaption"]`, `[class*="caption"]`) when `alt=""` is empty; when both a concise `<figcaption>` and a long descriptive `alt` (`> 110` chars) exist, uses `<figcaption>` for the button label and offers the long `alt` text as `description` (`data-monarch-description` / `aria-description`). Falls back to a humanized filename (`500px-Heart_anterior_exterior_view.png` → `"Heart anterior exterior view"`) for large content images (`>= 64×64` px) lacking `alt` and captions.
     - **Drop-Cap Single-Letter Merging (`gutenberg-alice-rackham`, `D-16`)**: Detects single-letter drop-cap images (`alt="A"` before `"lice"`), hides the decorative `<img>` from accessibility, and merges the letter into the adjacent text node so paragraphs read as `"Alice..."` instead of `"A"` + `"lice"`.
     - **Page-Scan Reader Enrichment (`archive-alice-1898`, `googlebooks-alice-1898`)**: Enriches generic viewer labels (`"Book page image"`, `"Page"`) using either the viewer's visible page indicator (`page 8`) + document title or the image's 1-based position in a continuous-scroll reader (`"Page (page 2 of 3 loaded)"`).
     - **Lazy-Loaded Images & Dynamic DOM (`MutationObserver`, `D-17`)**: Promotes lazy-loaded images (`loading="lazy"`, `data-src`, `data-srcset`), triggers eager loading when a button is activated, and automatically promotes newly scrolled/inserted images via `MutationObserver`.
   - **Geometry Capture (`installCanvasInterceptor`, `captureElementGeometry`)**:
     - Serializes inline `<svg>` (and `data:image/svg+xml` URIs) with `viewBox` and normalized component bounding boxes while respecting `<switch systemLanguage>`.
     - Intercepts `CanvasRenderingContext2D` draw calls at document start (`beginPath`, `moveTo`, `lineTo`, `quadraticCurveTo`, `bezierCurveTo`, `rect`, `arc`, `ellipse`, `closePath`, `stroke`, `fill`, `strokeRect`, `fillRect`, `clearRect`, `fillText`, `strokeText`) and 2D affine transforms (`save`, `restore`, `scale`, `rotate`, `translate`, `transform`, `setTransform`, `resetTransform`), resetting on full-canvas `clearRect` and capping at `2,048` operations per canvas.
   - **Zero Site-Specific Code & Zero Network Calls**: Verified by static policy tests and Playwright network route interception.

2. **Cross-Browser Playwright Test Suite (`bridge/test/bridge.test.mjs` & `bridge/test/fixtures/*.html`)**:
   - Local fixtures: `xkcd-327.html`, `wikipedia-heart.html`, `gutenberg-openstax-books.html`, `svg-and-canvas.html`, plus `engine-test/app/src/main/assets/test.html`.
   - Executed in both **headless Chromium** (`Google Chrome 156.0`) and **headless Firefox** (`Playwright Firefox 157.0`).

---

## 2. Baseline vs. Final Pass/Fail Check Output

### Baseline (Before Implementation)
- Command: `cd bridge && npm test`
- Result: `cd: bridge: No such file or directory` (exit code 1).

### Final Check (`cd bridge && npm test` & `./gradlew :tactile-core:test --no-daemon`)
- Command: `cd bridge && npm test`
- Result: **13/13 tests PASSED** (1 static policy test + 6 Chromium tests + 6 Firefox tests in `10.05 s`):
  - `Static Policy & Generalization Checks on bridge/src/bridge.js` — PASSED
  - `[chromium] xkcd-327 fixture` — PASSED
  - `[chromium] wikipedia-heart fixture` — PASSED
  - `[chromium] books fixture (Gutenberg, OpenStax, Archive.org, Google Books)` — PASSED
  - `[chromium] svg-and-canvas fixture` — PASSED
  - `[chromium] promoteImages idempotence, 2048 Canvas2D command cap, and data:image/svg+xml URI capture` — PASSED
  - `[chromium] engine-test/test.html compatibility` — PASSED
  - `[firefox] xkcd-327 fixture` — PASSED
  - `[firefox] wikipedia-heart fixture` — PASSED
  - `[firefox] books fixture (Gutenberg, OpenStax, Archive.org, Google Books)` — PASSED
  - `[firefox] svg-and-canvas fixture` — PASSED
  - `[firefox] promoteImages idempotence, 2048 Canvas2D command cap, and data:image/svg+xml URI capture` — PASSED
  - `[firefox] engine-test/test.html compatibility` — PASSED
- Regression check (`./gradlew :tactile-core:test --no-daemon`): **16/16 JUnit 5 tests PASSED** (`xkcd-327` warm compile time `7.40 ms`).
- Exact judge verification (`python3 test-sites/judge.py xkcd-327 ... --no-llm`): **9/9 exact checks PASSED** (saved to `docs/agent/grades/STEP3-xkcd-327.json`).

---

## 3. What Is Left & Known Risks

- **Remaining Scope for Step 4 (`tier2/`)**:
  - Implement `:tier2` (`ComponentProvider`, `GeminiArgonProvider` using `gemini-4-argon` via the Interactions API with `thinking_level: "low"` and structured JSON schema matching `LONG_TERM_PLAN.md` §9, and `Tier2SceneMerger.hotSwap`), verified with recorded-fixture JUnit 5 tests (`./gradlew :tier2:test`).
- **Known Risks**:
  - Cross-origin `<iframe>` elements without `allow-same-origin` require engine-level script injection into subframes (`WebExtension` in GeckoView / frame injection in WebView) when `app/` integrates `bridge/src/bridge.js`.
