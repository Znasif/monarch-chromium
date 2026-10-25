# Monarch Tactile Browser — Long-Term Plan

This is the hand-off document for any agent (local or managed) working on this repo.
Read it fully before writing code. Read `AGENTS.md` for hardware and SDK rules.

## 1. Goal

A durable, performant web browser for the HumanWare Monarch (Android 13, API 33,
arm64-v8a, 96×40 pin array, KeySoft screen reader). Blind users get what a browser
gives sighted users: they read pages in Braille, and they explore images by touch.

The browser is built properly, not hacked together:

1. **Pluggable engine.** A `BrowserEngine` interface with a GeckoView adapter and a
   system WebView adapter. The system WebView is frozen at Chromium 109.5414.
2. **Exact geometry before pixels.** Read vector geometry from SVG DOM, Canvas2D
   draw calls, PDF.js text/graphics layers, and map vector data (GeoJSON, MVT tiles,
   OSM URL state). Use raster contours only when no vector source exists.
3. **Two-tier tactile compiler.**
   - Tier 1 runs on the device, needs no network, and raises pins in < 35 ms.
     It must work in Monarch Exam Mode (no network).
   - Tier 2 is optional and asynchronous: a vision model (today `gemini-4-argon`)
     adds semantic components and labels, then hot-swaps them into the live view.
     Tier 2 sits behind a provider interface. The browser never depends on it.
4. **O(1) touch hit-test.** A pre-stamped `IntArray(96*40)` component-id grid with a
   1-pin touch halo. No allocation and no polygon math in the 40 Hz finger loop.
5. **Component graph.** A Data Navigator–style graph (directional edges up/down/
   left/right, hierarchical edges enter/exit) for any image, not only charts.
6. **KeySoft SDK and KeyBRF integration** as described in `README.md` §2 and
   `AGENTS.md` §2.
7. **Hard sites later:** Google Maps/Earth, OpenStreetMap, Mapillary (see §7).

## 2. Ethics and site-policy rules (must follow)

- The browser only acts on pages the user opens, at the moment the user opens them.
  No crawling, prefetching of other pages, or bulk download.
- No DRM, paywall, or login circumvention. No stripping of access controls.
- Render only content the site already delivers to the user's own device. Cloud
  (Tier 2) processing of an image happens only on an explicit user action, and the
  user is told the image leaves the device.
- Tests use local fixture pages, not live third-party sites. Live sites are for
  manual checks by a human only.

## 3. Evidence so far

**Engine accessibility test (`engine-test/`, 2026-10-25, real Monarch).**
`uiautomator dump` of the same fixture page in both engines (KeySoft is the only
enabled accessibility service):

| Element | WebView 109 | GeckoView 158 |
|---|---|---|
| Headings, paragraphs, links | exposed | exposed, but each heading and link appears twice (container node + text node) |
| `<button>` | Button | Button |
| `<img alt>`, SVG `<title>`, `role="img"` | Image ×3 | Image ×3 |
| Text field | label exposed | label exposed, node appears twice |
| Cross-origin iframe content | exposed | exposed |

**Open question:** does KeySoft read GeckoView's duplicate nodes twice, or does it
skip them with `H`/`L` quick-nav? A human tests this on the device. Until the answer
is known, all code stays engine-neutral behind `BrowserEngine`.

**Build quirks (Windows SDK, built from WSL via `cmd.exe /c gradlew.bat`):**

- GeckoView `158.0.20261021001945` needs `compileSdk` 37.2:
  `compileSdk { version = release(37) { minorApiLevel = 2 } }`.
- AGP `9.4.1`, Gradle `9.6.0`, JDK = Android Studio's bundled JBR.
- GeckoView depends on Kotlin stdlib 2.4.20; `engine-test` uses Java to avoid
  Kotlin compiler version friction.
- `ndk.abiFilters += "arm64-v8a"` does not shrink the GeckoView APK (198 MB debug).

## 4. Repository layout (target)

```
engine-test/            # existing: WebView vs GeckoView accessibility probe app
tactile-core/           # pure JVM library, no Android imports (Steps 1–2)
  model/                # TactileScene, Component, PinGrid, ComponentGraph
  compile/              # SvgCompiler, RasterContourCompiler, density control
  hit/                  # HitGrid (IntArray 96*40) + halo stamping
  nav/                  # ComponentGraph builder (directional + hierarchical edges)
bridge/                 # browser-side JS (Step 3)
  src/                  # image→button promoter, SVG/Canvas2D capture
  test/                 # fixture pages + headless Firefox and Chromium checks
tier2/                  # provider interface + gemini-4-argon provider (Step 4)
app/                    # Android browser app (later, needs the device)
  engine/               # BrowserEngine interface, GeckoEngine, SystemWebViewEngine
  tactile/              # viewer: SelfBraillingWidget, finger ownership, caption bar
docs/
  LONG_TERM_PLAN.md     # this file
  agent/                # one prompt file per agent step + the agent's own notes
scripts/agent.py        # managed-agent launcher (one step per interaction)
```

## 5. Step-by-step roadmap

Each step is one managed-agent interaction. Each step has a pass/fail check that
runs in a Linux sandbox without the Monarch.

| Step | Branch | Scope | Pass/fail check |
|---|---|---|---|
| 0 | `agent/step-0` | Align: questions, risks, module plan → `docs/agent/STEP0_PLAN.md`. No code. | file pushed |
| 1 | `agent/step-1` | `tactile-core` model + `HitGrid` + `ComponentGraph`, JUnit 5 tests | `./gradlew :tactile-core:test` green |
| 2 | `agent/step-2` | `SvgCompiler` (SVG → 96×35 1-pin outlines, fill ≤ 25%) + raster contour fallback, golden-file tests | tests green, fill-density assertions pass |
| 3 | `agent/step-3` | `bridge/` JS: image→button promotion, SVG/Canvas2D geometry capture | headless Firefox + Chromium tests on fixture pages green |
| 4 | `agent/step-4` | `tier2/`: provider interface, `gemini-4-argon` provider, recorded-fixture tests | tests green, no live API calls in tests |
| 5 | `agent/step-5` | Video storyboard + local `scripts/make_video.py` (Omni). Human-triggered after merge and recording. | `make_video.py --dry-run` works |
| later | — | `app/` on the device, hard sites | human-run |

### Rules for every step (managed agent)

- **Progress must survive the sandbox.** Each step starts in a fresh sandbox. Only
  what is pushed to GitHub carries over.
- Branch `agent/step-N` starts from `agent/step-(N-1)` (step 0 starts from `main`).
- Commit and push at least every 20 minutes of work and at every green test run:
  `git push -u origin agent/step-N`. Never push to `main`. Never force-push.
- When the step's check passes, open a draft PR from `agent/step-N` into
  `agent/step-(N-1)` (step 0: into `main`) with the GitHub REST API.
- Record a baseline of the pass/fail check first, then iterate until it passes with
  no regressions (Builder Guide pattern 2).
- Decide open questions with the most conservative reasonable default and record
  each in `docs/agent/DECISIONS.md` (question, choice, reason). Do not wait for the
  human.
- Write any non-obvious build or setup lesson into `AGENTS.md` (pattern 5).
- End each step with `docs/agent/STEP<N>_REPORT.md`: what was built, the check
  output, what is left, and known risks. Commit and push it.

## 6. Using the Gemini 4 Argon toolkit efficiently

From the Builder Guide (provided to the managed agent as a sandbox file, not in
this repo):

- **Lead model:** `gemini-4-argon` for architecture and build steps. Leaf tasks
  (boilerplate tests, fixtures) may go to `gemini-3.8-flash` subagents.
- **Tier 2 provider:** `gemini-4-argon` through the Interactions API, structured
  JSON output (component tree: `id`, `label`, `description`, `embeddedText`,
  `bbox [ymin,xmin,ymax,xmax]`, `parentId`, `childIds`). Use `thinking_level`.
  Do not use `temperature`, `top_p`, `top_k`, `thinking_budget`, or explicit
  `cached_content`.
- **Explainer video (later, human-triggered):** `gemini-omni-1.1-flash` makes a
  1-minute 3Blue1Brown-style explainer of what was built, using real screenshots of
  the browser on real pages and the human's own footage of the Monarch. Order:
  storyboard and narration script (text) → human approval → human supplies footage →
  background generation one scene at a time. Only features built in this repo may
  appear. Agents do not start video generation on their own.
- **Cost:** introductory price is $2 / 1M input tokens and $10 / 1M output tokens.
  An earlier $25 credit ran out mid-session. Keep each step bounded.

## 7. Hard sites (later, after the app runs on the device)

| Site type | Challenge | Approach |
|---|---|---|
| Google Maps / Earth (WebGL) | No DOM geometry; raster/3D only | Tier 2 declutter of a viewport capture into major contours |
| OpenStreetMap | Raster tiles by default | Read `#map=zoom/lat/lon` URL state; render vector data the page itself loads |
| Mapillary / Street View | First-person 3D photos | Ego-centric component graph (crosswalk, curb cut, sign) via Tier 2 |
| All WebGL maps | Expect drag/pinch, ignore keys without focus | Translate Right D-pad / zoom keys into discrete pan/zoom steps, wait for tiles to settle, then refresh pins |

## 8. Sandbox reference files (managed agent only)

Mounted under `/workspace/ref/` by `scripts/agent.py`, not committed:

- `builder-guide.txt` — Gemini 4 Argon hackathon builder guide.
- `ideation-transcript.jsonl` — the original ideation conversation (one JSON object
  per step; user requests are `type == "USER_INPUT"`, answers are
  `type == "PLANNER_RESPONSE"`). Search it with `jq`/`rg` when you need context on
  a past decision; do not read it end to end.
- `skills/gemini-api-dev.md`, `skills/gemini-omni-flash-api.md` — official Gemini
  API skills (current model IDs and SDK usage).

## 9. Human decisions (2026-10-25)

1. **Colour is never a texture.** Regions are outlines only; no fill or dot texture
   inside shapes. Colour meaning (e.g. oxygen-rich vs oxygen-poor) goes into labels.
2. **Shapes for any raster image come from a multimodal model, for any site.** Send
   a low-resolution copy of the image (longest side 768 px) to `gemini-4-argon`
   (`thinking_level: "low"`; benchmark `gemini-3.8-flash` for latency) and get back
   a structured tactile graphic (schema below). Our renderer draws it as 1-pin lines.
   No site-specific rules (no following file-page links, no URL patterns). Inline
   `<svg>` and Canvas2D geometry in the page are still used directly. The local
   raster contour compiler is the offline / Exam Mode fallback only.
3. **Testing:** ground truth is the human-reviewed case files in
   `test-sites/cases/`. `test-sites/judge.py` runs exact checks (fill, box IoU,
   counts) and asks `claude-sonnet-5-5` to grade the rest within the stated
   tolerances, using the source image and a picture of the generated pins. Judge
   runs need `ANTHROPIC_API_KEY`, so they run locally, not in the agent sandbox.
   Modules must be able to write the result format documented in `judge.py`.

**Structured tactile graphic (model output, all coordinates normalized 0..1 of the
source image, origin top-left):**

```json
{
  "components": [
    {
      "id": "p1", "label": "Panel 1", "parentId": null,
      "role": "panel | figure | object | text | arrow | other",
      "box": [x0, y0, x1, y1],
      "strokes": [{"points": [[x, y], ...], "closed": true}],
      "embeddedText": "lettering inside this component, reading order",
      "description": "one sentence"
    }
  ]
}
```

Rules for the renderer: strokes are centerlines (one pin wide); `role: "text"`
components are touch regions with no pins at 1x; smaller children win on overlap;
fill <= 25% at every zoom, dropping the smallest/least important strokes first.
