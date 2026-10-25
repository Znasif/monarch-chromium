STEP 2 — Tier-1 tactile compiler.

Branch: create agent/step-2 from origin/agent/step-1.

Follow docs/agent/STEP0_PLAN.md section C for Step 2. In tactile-core/compile/:
- SvgCompiler: parse path, rect, circle, ellipse, line, polyline, polygon (with
  transforms and viewBox) into 1-pin outlines fitted to the 96x35 canvas, grouped
  into Components (use <title>, aria-label, id for labels).
- RasterContourCompiler: fallback for bitmaps, adaptive edge contours, optionally
  masked to given component boxes.
- Density control: raised-pin fill <= 25% at every zoom level.
- Zoom/pan viewport (1x to 8x) re-rasterizing from vectors, not from pins.
Golden-file tests with small fixture SVGs and PNGs (text-based PBM or ASCII pin
dumps so diffs are readable). Pass/fail: ./gradlew :tactile-core:test green,
including fill-density assertions. Compile time per fixture < 35 ms on the JVM
(report the measurement).

Push progress as you go. When green, open a draft PR into agent/step-1, write
docs/agent/STEP2_REPORT.md. Reply with a 5-line summary and the PR URL.
