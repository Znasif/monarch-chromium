STEP 1 — tactile-core model, HitGrid, ComponentGraph.

Branch: create agent/step-1 from origin/agent/step-0.

Follow docs/agent/STEP0_PLAN.md section C for Step 1. Build tactile-core/ as a pure
JVM Kotlin library (no Android imports) with JUnit 5 tests:
- Model: PinGrid (96x40; canvas rows 0-34, row 35 blank, rows 36-39 caption),
  Component (id, label, bbox, parentId, childIds), TactileScene.
- HitGrid: IntArray(96*40) component ids, 1-pin halo stamping, O(1) lookup,
  no allocation in lookup.
- ComponentGraph: directional edges (up/down/left/right from centroids and
  adjacency) and hierarchical edges (enter/exit).
Pass/fail: ./gradlew :tactile-core:test is green. Record the baseline first.

Push progress as you go. When green, open a draft PR into agent/step-0 titled
"Agent step 1: tactile-core model, HitGrid, ComponentGraph", and write
docs/agent/STEP1_REPORT.md. Reply with a 5-line summary and the PR URL.

ADDITION — editing is a core requirement (human decision):
- Editable component tree: rename, split, merge, and re-parent operations on
  TactileScene, each producing a new immutable scene plus an undo record, with
  HitGrid and ComponentGraph rebuilt from the result.
- Pin drawing layer: user-drawn raised/erased pins stored in normalized image
  coordinates and attached to a component id, so drawings scale and pan with zoom.
- Persistence: edits and drawings serialized to JSON keyed by the SHA-256 of the
  source image bytes, so the same image on another page reuses them.
- Tests: every item under "editing" in test-sites/cases/xkcd-327.json, plus the
  "navigation" items, using a synthetic 4-panel scene.
