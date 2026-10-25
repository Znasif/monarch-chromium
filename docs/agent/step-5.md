STEP 5 — 1-minute explainer video plan and generator script. Human-triggered only.

Run this step only after the human has merged the local short-term work with the
agent branches and recorded footage. Branch: create agent/step-5 from the branch
the human names in their request (default: origin/main).

The sandbox can read text and image files only, so you do NOT generate video here.
Instead:

1. Read docs/LONG_TERM_PLAN.md §6, /workspace/ref/skills/gemini-omni-flash-api.md,
   every docs/agent/STEP*_REPORT.md, and the README. List only features that are
   actually built and tested in this repo.
2. Write docs/video/STORYBOARD.md: a 60-second, 3Blue1Brown-style explainer,
   4-6 scenes. For each scene: duration, narration text (plain, at most 25 words
   per 10 seconds), on-screen visual, and which human footage or screenshot it
   uses (refer to files as recordings/<name> — the human records them locally).
   Every claim must map to a built feature; list that mapping at the end.
3. Write scripts/make_video.py that the human runs locally:
   - reads GEMINI_API_KEY from .env, footage from a local recordings/ folder
     (git-ignored), and scenes from STORYBOARD.md;
   - uploads footage with the Files API and calls gemini-omni-1.1-flash exactly as
     the skill file shows, one scene per call, in the background with polling;
   - saves each scene to video-out/ (git-ignored) and stops for the human to
     approve each scene before the next one;
   - has a --dry-run flag that prints requests without calling the API.
4. Add recordings/ and video-out/ to .gitignore.
5. Commit, push, open a draft PR. Reply with a 5-line summary and the PR URL.
