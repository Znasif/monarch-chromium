STEP 5 — Long 3Blue1Brown-style explainer video (code + Gemini Omni), linked from GitHub.

This video goes in the repository README as a link. It is NOT the hackathon
submission (the human records that separately on the device). Started by the human.

Branch: create agent/step-5 from origin/local/app (it has the Android app and today's
on-device fixes). Do not change anything under android-app/, tactile-core/, tier2/
or bridge/: this step only adds docs/video/, scripts/video/ and README links.

## What the video explains

Length 4-6 minutes, 8-12 scenes, calm narration, 3Blue1Brown style (dark
background, one idea per scene, shapes that move and transform, equations or
diagrams built up step by step). It walks through real kinds of sites and shows
exactly which parts were built and how they connect:

1. The problem: a 96x40 pin display, 25% fill limit, rows 0-34 canvas, row 35
   blank, rows 36-39 Braille caption. Why sighted pictures do not survive.
2. Browser: any page → bridge.js adds "Explore tactile image: <label>" after every
   picture, labels from alt / caption / figcaption / filename (no site rules).
   Use the test cases: xkcd #327, Wikipedia Heart, archive.org 1898 Alice page 8,
   Gutenberg Rackham plates, OpenStax Figure 40.11, LOC Tribune 1912.
3. Structured tactile graphic: 768 px copy → gemini-4-argon → component tree with
   centerline strokes (docs/LONG_TERM_PLAN.md §9); outline-only colour; text parts
   are touch regions without pins. Cached under SHA-256 of the image bytes.
4. Renderer: Bresenham 1-pin lines, panel frames with a blank pin column between
   frames (the xkcd Panel 3/4 bug and its fix), fill ≤ 25% by dropping the smallest
   parts first, aspect-correct view boxes.
5. Touch and navigation: HitGrid (smaller parts win), Data Navigator levels —
   finger reads (speech + Braille caption, canvas never moves), Action double-press
   opens the part at the current level, D-pad steps, Back exits; zoom and pan.
6. What we learned on the hardware (from the app commits and README §4): which
   keys reach the app, no X offset on SDK 0.1.131, no vibration, KeySoft
   translator for the caption, the sighted overlay for HDMI viewers.
7. Testing: the 7 site cases, judge.py exact checks + model grading
   (Sonnet locally, Argon in the sandbox), the agent steps 0-4 and their tests.
8. Next: wiring tier2/ and bridge/ into the app, hard map sites (§7), KeyBRF export.

Every claim must come from this repo (README, LONG_TERM_PLAN, STEP*_REPORT.md,
DECISIONS.md, git log, source). Mark anything not built as "next". Keep a
claim → source table in docs/video/CLAIMS.md.

## How to make it

1. docs/video/SCRIPT.md: scenes with duration, narration (about 140 words per
   minute), visuals, and for each scene whether it is Manim (code) or Omni.
2. Manim (Community Edition) for every diagram scene: pin grids drawn from real
   data in the repo (e.g. render the bundled xkcd graphic
   android-app/app/src/main/assets/graphics/*.json through the same rules as
   GraphicScene.kt, written in Python), the component tree, the Data Navigator
   graph, the HitGrid. Code in scripts/video/scenes/*.py. Install manim, ffmpeg and
   their system libraries in the sandbox (apt + pip; see AGENTS.md §3 for the
   proxy certificate).
3. Gemini Omni (gemini-omni-1.1-flash, exactly as
   /workspace/ref/skills/gemini-omni-flash-api.md shows) only for at most 3 short
   scenes (≤ 10 s each) that code cannot draw well, such as a hand exploring a pin
   display. No real brands, people or site screenshots in generated footage.
4. Narration: generate speech with the Gemini API text-to-speech model the skill
   files recommend; one audio file per scene, mixed under the scene with ffmpeg.
5. Assemble with ffmpeg into docs/video/out/monarch-explainer.mp4 at 1920x1080 and
   burn in captions (the narration text) so it is accessible without sound.
6. Do not commit the .mp4 (too large for git). Upload it as an asset on a GitHub
   release named "explainer-v1" (REST API: create release on tag explainer-v1 from
   agent/step-5, then upload the asset). Put the release link and a 3-line summary
   in README.md under a new "Explainer video" heading, and link SCRIPT.md.
7. Budget: at most 3 Omni calls and 12 TTS calls. Stop and report if a call fails
   twice; never loop on paid calls. Commit and push after each finished scene.
8. Reply with: video length, scene list, which scenes used Omni, the release URL
   and the draft PR URL.
