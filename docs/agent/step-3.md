STEP 3 — bridge/ JavaScript.

Branch: create agent/step-3 from origin/agent/step-2.

Follow docs/agent/STEP0_PLAN.md section C for Step 3. Build bridge/ as plain
JavaScript that runs at document start in any engine (no engine-specific APIs):
- Promote meaningful <img>, <svg>, <canvas>, <figure>, role="img" to focusable
  <button> elements labelled "Explore tactile image: <label>" (label from alt,
  aria-label, <title>, <figcaption>; skip decorative and tiny images).
- Capture geometry: serialize inline SVG; record Canvas2D draw calls (paths,
  rects, arcs, text) per canvas; return JSON matching tactile-core's model.
- No site-specific code. No network requests of its own.
Tests: local fixture pages under bridge/test/fixtures, run in headless Firefox and
Chromium (Playwright). Pass/fail: npm test green in both browsers.

Push progress as you go. When green, open a draft PR into agent/step-2, write
docs/agent/STEP3_REPORT.md. Reply with a 5-line summary and the PR URL.
