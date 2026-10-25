# Step 1 Report (`STEP1_REPORT.md`)

## 1. What Was Built
- **Root Gradle 9.6.0 & `:tactile-core` Pure-JVM Kotlin Module:**
  - Created root `settings.gradle.kts`, `build.gradle.kts` (Kotlin `2.4.20`, JVM target `17`), `tactile-core/build.gradle.kts` (JUnit `5.11.4`), and POSIX `./gradlew` script with automatic `HTTPS_PROXY`/`HTTP_PROXY` JVM property forwarding.
- **Model (`org.monarch.tactile.model`):**
  - `PinGrid`: 96×40 pin array model enforcing canvas rows `0..34`, blank separator row `35` (always `0`), and Braille caption rows `36..39`, with `canvasRaisedCount()`, `canvasFillRatio()`, `toByteGrid()`, and `toAsciiCanvas()`.
  - `NormalizedBox`, `PinRect`, `NormalizedPoint`, `SceneViewport`, `Component`, and `TactileScene`: resolution-independent normalized coordinates (`[ymin, xmin, ymax, xmax]`) paired with inward-rounded pin bounds (`toPinRect`) that preserve `>= 1` blank pin column across narrow inter-panel gutters.
- **O(1) Touch Hit-Testing (`org.monarch.tactile.hit.HitGrid`):**
  - Pre-stamped `IntArray(96 * 40)` (`3,840` entries) with 1-pin halo stamping, direct-over-halo and child-over-parent (`pinBox.area`) precedence, strict clamping to rows `0..34` (`NO_COMPONENT = 0` on row `35`, `CAPTION_REGION = -1` on rows `36..39`), and zero-allocation `lookup(x, y)` + `lookupWithFingertipOffset(rawX, rawY, -3)`.
- **Spatial & Hierarchical Component Graph (`org.monarch.tactile.nav.ComponentGraph`):**
  - Generalized Data Navigator graph computing sibling-only `UP`/`DOWN`/`LEFT`/`RIGHT` edges and hierarchical `enter`/`exit` edges (sorting top-level row bands left-to-right and panel children top-to-bottom), plus `stepDirectional`, `stepEnter`, and `stepBack` returning `NavigationFeedback.BOUNDARY_BEEP` at boundaries.
- **Immutable Scene Editing, Normalized Pin Drawing Layer, & SHA-256 Persistence (`org.monarch.tactile.edit`):**
  - `renameComponent`, `splitComponent`, `mergeComponents`, `reParentComponent`, `drawPinLine`, `erasePinLine`, and `undo` on `TactileScene`, each returning an immutable `SceneEditResult(scene, undoRecord, hitGrid, graph)`.
  - `PinDrawingLayer` storing user-drawn raised and erased pins in normalized `[0, 1]` coordinates attached to component IDs/keys so drawings scale and translate with `SceneViewport` zoom and pan (`withViewport`).
  - `SceneEditStore` serializing and deserializing all tree edits and pin drawings to JSON keyed by `sha256Hex(imageBytes)`.
- **JUnit 5 Verification Suite (`TactileCoreModelAndHitGridTest` & `Xkcd327NavigationAndEditingTest`):**
  - 10 JUnit 5 test methods verifying all core invariants plus every check in `test-sites/cases/xkcd-327.json` (`componentChecks`, `navigation`, `editing.tree`, and `editing.pins`) on a synthetic `666×205` 4-panel scene.

## 2. Pass/Fail Check Output
- **Baseline (before implementation):** `./gradlew :tactile-core:test` failed (`./gradlew: No such file or directory`, followed by `Task :tactile-core:test NO-SOURCE` once wrapper was added).
- **Final check (`./gradlew :tactile-core:test --no-daemon`):**
  ```
  > Task :tactile-core:test
  TactileCoreModelAndHitGridTest > NormalizedBox geometry and SceneViewport isolation projection work accurately() PASSED
  TactileCoreModelAndHitGridTest > UndoRecord restores previous TactileScene after split, merge, reparent, and draw() PASSED
  TactileCoreModelAndHitGridTest > PinGrid enforces 96x40 layout with blank row 35 separator and caption rows 36 to 39() PASSED
  TactileCoreModelAndHitGridTest > HitGrid stamps direct pins and 1-pin halo with child precedence and respects row 35 boundary() PASSED
  TactileCoreModelAndHitGridTest > HitGrid falls back to pinBox stamping when components have no explicit raisedPins() PASSED
  TactileCoreModelAndHitGridTest > ComponentGraph builds sibling directional edges and hierarchical enter and exit edges() PASSED
  Xkcd327NavigationAndEditingTest > xkcd-327 navigation expectations pass() PASSED
  Xkcd327NavigationAndEditingTest > xkcd-327 componentChecks and reading order pass() PASSED
  Xkcd327NavigationAndEditingTest > xkcd-327 editing tree operations and SHA-256 persistence pass() PASSED
  Xkcd327NavigationAndEditingTest > xkcd-327 editing pin drawing, erasing, 2x zoom+pan, and persistence pass() PASSED

  BUILD SUCCESSFUL in 17s
  ```

## 3. What Is Left
- **Step 2:** Implement `tactile-core/compile` (`SvgCompiler`, `RasterContourCompiler`, `DensityController`) with golden-file tests, `< 35 ms` compile time assertions, and `xkcd-327.json` `"tactile"` golden assertions (`1x`, `2x`, `4x` zoom and Panel 3 isolation).
- **Step 3:** Implement `bridge/` JavaScript (`promoteImages`, SVG/Canvas2D capture) and Playwright headless Chromium + Firefox tests.
- **Step 4:** Implement `tier2/` (`ComponentProvider`, `GeminiArgonProvider`, `Tier2SceneMerger`) with recorded fixture tests.

## 4. Known Risks
- In Step 2, `RasterContourCompiler` and `SvgCompiler` must suppress small text/lettering strokes at `1x` overview (`textAsPins: none`) while preserving closed 1-pin panel borders and `>= 1` blank pin column gutters.
