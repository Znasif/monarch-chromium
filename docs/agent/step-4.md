STEP 4 — Tier-2 provider.

Branch: create agent/step-4 from origin/agent/step-3.

Follow docs/agent/STEP0_PLAN.md section C for Step 4. Build tier2/ (pure JVM
Kotlin):
- ComponentProvider interface: image bytes + optional page context -> component
  tree (id, label, description, embeddedText, bbox [ymin,xmin,ymax,xmax],
  parentId, childIds). Async, cancellable, never required by Tier 1.
- GeminiArgonProvider: gemini-4-argon via the Interactions API REST endpoint,
  structured JSON output, thinking_level "low" for interactive use. Follow
  /workspace/ref/skills/gemini-api-dev.md; no deprecated parameters.
- Merge logic that hot-swaps Tier-2 components into a Tier-1 TactileScene.
Tests use recorded JSON fixtures only; no live API calls and no API key in the
sandbox. Pass/fail: ./gradlew :tier2:test green.

Push progress as you go. When green, open a draft PR into agent/step-3, write
docs/agent/STEP4_REPORT.md. Reply with a 5-line summary and the PR URL.

ADDITION (human decision, see LONG_TERM_PLAN.md §9): GeminiArgonProvider returns
the "structured tactile graphic" schema in §9 (components with normalized
centerline strokes), using structured JSON output and a 768 px downscaled image.
Add a StrokeRenderer in tactile-core that rasterizes these strokes to 1-pin lines
at any zoom/pan, applies the fill rule, and builds HitGrid/ComponentGraph from the
components. Write results in the format documented in test-sites/judge.py so the
human can grade them. Tests use recorded JSON fixtures shaped like xkcd-327.
