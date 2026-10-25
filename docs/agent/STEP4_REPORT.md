# Step 4 Report — `tier2/` Provider + `StrokeRenderer`

**Branch:** `agent/step-4` (merged with `origin/main` `f26407d`)
**Date:** 2026-10-25

---

## 1. Baseline & Pass/Fail Verification

### Baseline (before Step 4 implementation)
Running `./gradlew :tier2:test --no-daemon` on the freshly branched `agent/step-4` failed with:
```text
Project with path ':tier2' could not be found in root project 'monarch-chromium'.
```

### Final Verification (after Step 4 implementation)
```bash
export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=10.100.0.1 -Dhttp.proxyPort=8081 -Dhttps.proxyHost=10.100.0.1 -Dhttps.proxyPort=8081"
./gradlew :tactile-core:test :tier2:test --no-daemon
```
- **`:tactile-core:test`**: `16/16` JUnit 5 tests passed.
- **`:tier2:test`**: `6/6` JUnit 5 tests passed (recorded JSON fixtures only; zero live API calls).
- **Exact + Judge Evaluation (`test-sites/judge.py`)**:
  - Command: `python3 test-sites/judge.py xkcd-327 tier2/build/judge-results/step4-xkcd-327-result.json --no-llm`
  - Result: **`xkcd-327: exact 9/9 pass`**
    - `fill:overview1x`: `0.192 <= 0.25` (`645 / 3,360` pins)
    - `fill:isolate:p3`: `0.020 <= 0.25` (`68 / 3,360` pins)
    - `fill:zoom2x`: `0.149 <= 0.25` (`500 / 3,360` pins)
    - `fill:zoom4x`: `0.106 <= 0.25` (`355 / 3,360` pins)
    - `box:Panel 1..4`: `IoU = 1.000` on all 4 panels
    - `topLevelCount`: `4 == 4`
  - Saved to `docs/agent/grades/STEP4-xkcd-327.json`.

---

## 2. What Was Built

### A. `StrokeRenderer` (`tactile-core/src/main/kotlin/org/monarch/tactile/compile/StrokeRenderer.kt`)
- **Schema Models (`LONG_TERM_PLAN.md` §9):**
  - `StructuredStroke(points: List<NormalizedPoint>, closed: Boolean)` with `normalizedLength()` and JSON parsing.
  - `StructuredComponent(id, label, parentId, role, box, strokes, embeddedText, description)` supporting `[xmin, ymin, xmax, ymax]` normalized `0..1` coordinates.
  - `StructuredTactileGraphic(sceneSummary, components)` with `fromJson(json)` and `toComponentHints()`.
- **1-Pin Centerline Rendering & Viewport Projection:**
  - Projects normalized `[0, 1]` polyline vertices onto the `96×35` canvas (`rows 0..34`) under any `Viewport(zoom = 1x..8x, panX, panY, isolatedComponentBox)` using Bresenham rasterization + Liang-Barsky segment clipping + 1-pin diagonal corner-peel thinning so every rendered stroke is strictly 1-pin wide.
  - Preserves aspect-ratio-centered fitting (~29 rows tall) when isolating a panel (`isolate:p3`) that contains visual child figures/objects and speech zones (`D-14`).
  - Never renders `role == "text"` or `"speech"` components as raised pins at `1x` overview (`textAsPins: none`); speech zones remain navigable via `ComponentGraph` and touchable via `HitGrid` so `Enter` reads `embeddedText` on the 4-row Braille caption bar (`rows 36..39`) and TTS.
- **Priority- & Size-Ordered Density Pruning (`D-18`):**
  - Ranks strokes by role priority (`panel = 4`, `figure = 3`, `arrow = 2`, `object = 2`, `other = 1`) and `normalizedLength()`.
  - When canvas fill exceeds `25%` (`840 / 3,360` pins), drops the smallest, lowest-priority interior strokes whole before invoking `DensityController.enforceLimit`.
- **Scene Assembly:**
  - Builds a complete `TactileScene` (`PinGrid`, hierarchical `Component` list with `baseNormalizedPins` for lossless zoom/pan re-projection, `HitGrid` where smaller children win on overlap, and `ComponentGraph`).

### B. `tier2/` Module (`tier2/src/main/kotlin/org/monarch/tier2/`)
- **`Tier2Models.kt`:**
  - `ComponentProvider` interface (`decomposeAsync(imageBytes, mimeType, context): CompletableFuture<Tier2ComponentTree>`): asynchronous, cancellable, and never required by Tier 1.
  - `ImagePreprocessor`: decodes PNG/JPEG images via `javax.imageio.ImageIO`, composites transparent pixels (`alpha < 255`) onto solid WHITE (`#FFFFFF`) so dark strokes on transparent backgrounds (e.g. `openstax-bio-heart.json`) never vanish into black, and downscales with bicubic interpolation so `max(width, height) <= 768 px` (`LONG_TERM_PLAN.md` §9).
- **`GeminiArgonProvider.kt`:**
  - Targets `gemini-4-argon` (default) or `gemini-3.8-flash` (companion), explicitly rejecting deprecated `gemini-2.5-*`, `gemini-2.0-*`, and `gemini-1.5-*` models.
  - Calls the Interactions API REST endpoint (`POST https://generativelanguage.googleapis.com/v1beta/interactions`) with `x-goog-api-key`, `generation_config: {"thinking_level": "low"}`, and `response_format` carrying `STRUCTURED_TACTILE_GRAPHIC_SCHEMA_JSON`.
  - Contains zero deprecated parameters (`temperature`, `top_p`, `top_k`, `thinking_budget`, `cached_content`).
  - Supports cancellation via `CompletableFuture.cancel(true)` and pluggable `InteractionsHttpTransport` so unit tests run deterministically against recorded JSON fixtures without network calls.
- **`Tier2SceneMerger.kt` (`D-08`, `D-19`):**
  - `hotSwap(tier1Scene, tier2Tree, focusedComponentId, bitmap, viewport)` upgrades a coarse Tier-1 `TactileScene` to a rich Tier-2 `TactileScene` while preserving:
    1. The active `Viewport` (`zoom`, `panX`, `panY`).
    2. All user-drawn and user-erased pins (`drawingLayer`), automatically remapping Tier-1 component IDs to their reconciled Tier-2 counterparts before calling `reprojectScene()`.
    3. The user's focused component ID by exact key match, highest bounding-box IoU (`>= 0.25`), or nearest Euclidean centroid.

### C. Recorded Interactions API Fixtures & Tests (`tier2/src/test/`)
- `tier2/src/test/resources/fixtures/xkcd-327-argon-response.json`: recorded `gemini-4-argon` Interactions API response for `xkcd-327` (4 panels, 4 Mom stick-figure stroke sets, 2 tables, and 7 speech zones in reading order).
- `tier2/src/test/resources/fixtures/wikipedia-heart-argon-response.json`: recorded `gemini-4-argon` Interactions API response for `wikipedia-heart` (4 anatomical groups, chambers, valves, vessels, and directional blood-flow arrows with colour meaning encoded in labels rather than fill textures).
- `tier2/src/test/kotlin/org/monarch/tier2/Tier2ProviderAndStrokeRendererTest.kt`: 6 JUnit 5 tests covering `ImagePreprocessor`, `GeminiArgonProvider` request validation, `decomposeAsync` + cancellation, `StrokeRenderer` (`1x`, `isolate:p3`, `2x`, `4x`), density pruning (`<= 25%`), and `Tier2SceneMerger.hotSwap` focus + user-drawing preservation.
