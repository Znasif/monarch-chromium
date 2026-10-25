# Step 2 Report (`STEP2_REPORT.md`)

## 1. What Was Built
- **Tier-1 Compiler Models & Density Control (`org.monarch.tactile.compile.CompileModels.kt`):**
  - `Viewport(zoom, panX, panY, isolatedComponentBox, preserveAspectRatio)` supporting `1x..8x` re-rasterization from normalized vector/centerline geometry.
  - `VectorStroke`, `ComponentHint`, `GrayscaleBitmap` (with thick 3–4 px line/rect/circle drawing helpers and ASCII `P1` PBM import/export), `VectorStrokeRasterizer`, and `DensityController` (enforcing `canvasFillRatio() <= 0.25f` across all zoom levels by dropping lowest-salience/minor roles before figures and panels).
- **SVG Vector Compiler (`org.monarch.tactile.compile.SvgCompiler.kt`):**
  - Parses `<path>` (`M, m, L, l, H, h, V, v, C, c, S, s, Q, q, T, t, A, a, Z, z`), `<rect>`, `<circle>`, `<ellipse>`, `<line>`, `<polyline>`, `<polygon>`, nested `<g>` groups, `viewBox`, and 2D affine `transform` attributes (`translate`, `scale`, `rotate`, `matrix`, `skewX`, `skewY`).
  - Evaluates `<switch systemLanguage>` (selecting English `"en"` labels in multi-language SVGs like `wikipedia-heart.json`) and defers dashed leader/valve lines (`stroke-dasharray`) to `4x` zoom.
- **Raster Centerline Compiler (`org.monarch.tactile.compile.RasterContourCompiler.kt`):**
  - Converts 3–4 px (or thicker) ink strokes into single 1-pin centerlines using adaptive foreground thresholding + sparse **Zhang-Suen morphological skeletonization** in source pixel space followed by connected centerline projection and 1-pin canvas thinning—never tracing doubled edges of a stroke.
  - Suppresses dialogue lettering glyphs and tiny steam/speckle marks at `1x` overview (`textAsPins: none`), clears background pins outside panels (`backgroundPins: none`), preserves `>= 1` blank pin column between adjacent panels and between Mom and the tables, and auto-fits visual figure bounds to ~28–30 pins tall with aspect-ratio preservation when isolating a panel (`isolatePanel3`).
- **Judge Result Exporter (`org.monarch.tactile.compile.JudgeResultWriter.kt`):**
  - Serializes compiled scenes and multi-view pin grids (`overview1x`, `isolate:p3`, `zoom2x`, `zoom4x`) into the exact JSON format consumed by `test-sites/judge.py`.
- **Golden Fixtures & JUnit 5 Tests (`SvgCompilerAndRasterTest.kt`, `Xkcd327TactileCompilerTest.kt`, `src/test/resources/golden/*.txt`):**
  - Readable 35×96 ASCII golden pin dumps (`xkcd-327-overview1x.txt`, `xkcd-327-isolate-p3.txt`, `heart-overview1x.txt`) plus PBM round-trip and `xkcd-327.json` `"tactile"`, `"strokes"`, `"drawings"`, `"isolatePanel3"`, and `"zoom"` assertions.

## 2. Pass/Fail Check Output & Compile Time Measurements
- **JVM Warm Compile Times (Requirement: `< 35 ms` per fixture):**
  - `SvgCompiler` primitives SVG fixture: **`3.52 ms`**
  - `SvgCompiler` multi-chamber heart diagram SVG fixture: **`7.42 ms`**
  - `RasterContourCompiler` synthetic `xkcd-327` (`666×205`, 3–4 px strokes) bitmap: **`12.56 – 16.18 ms`**
- **Fill Density Measurements (Requirement: `<= 0.25`):**
  - `xkcd-327` `1x` overview: **`0.180`** (`606 / 3,360` pins)
  - `xkcd-327` `isolatePanel3`, `2x`, `4x`, and `heart` `1x` / `4x`: all verified **`<= 0.25`**.
- **Test Run Output (`./gradlew :tactile-core:test --no-daemon`):**
  ```
  > Task :tactile-core:test
  SvgCompilerAndRasterTest > SvgCompiler golden ASCII canvas output matches expected 1-pin geometry() PASSED
  SvgCompilerAndRasterTest > DensityController enforces 25 percent ceiling even on ultra-dense inputs() PASSED
  SvgCompilerAndRasterTest > SvgCompiler parses primitives, transforms, viewBox, and systemLanguage switch within 35ms() PASSED
  SvgCompilerAndRasterTest > RasterContourCompiler PBM round-trip and thick stroke skeletonization produce 1-pin centerline() PASSED
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
  Xkcd327TactileCompilerTest > xkcd-327 overview1x tactile, strokes, drawings, and under 35ms compile time pass() PASSED
  Xkcd327TactileCompilerTest > xkcd-327 isolatePanel3 and 2x and 4x zoom re-rasterization pass() PASSED

  BUILD SUCCESSFUL
  ```

## 3. What Is Left
- **Step 3:** Implement `bridge/` JavaScript (`promoteImages`, SVG/Canvas2D geometry capture, lazy-image and caption/filename label resolution) and pass headless Firefox + Chromium Playwright tests.
- **Step 4:** Implement `tier2/` (`ComponentProvider`, `GeminiArgonProvider` returning structured tactile graphics with centerline `strokes`, `StrokeRenderer`, `Tier2SceneMerger`) and pass `./gradlew :tier2:test`.

## 4. Known Risks
- Unhinted continuous-tone photographs without `ComponentHint` boxes rely on Sobel gradient thresholding in Exam Mode (offline); when online, `LONG_TERM_PLAN.md` §9 delegates raster shape extraction to `gemini-4-argon` (`thinking_level: "low"`).
