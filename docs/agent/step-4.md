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
