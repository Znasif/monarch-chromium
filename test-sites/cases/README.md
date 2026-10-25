# Site test cases

One JSON file per real page. These files are the shared expectations for both the
local short-term app and the managed agent's long-term modules. Page snapshots are
captured locally with `node capture.mjs <id>` into `snapshots/` (git-ignored, never
committed, for licensing).

## Conventions

- **Boxes** are in source-image pixels, `[x0, y0, x1, y1]`, measured on the 1x
  image named in `images`, with the tolerance given in `tactile.coordinateSpace`.
  Code that stores normalized `[ymin, xmin, ymax, xmax]` (DECISIONS.md D-02)
  converts in the test, not in the case file.
- **Pins:** canvas rows 0-34 (96x35), row 35 blank, rows 36-39 caption.
- **Fill** is raised pins / 3,360 canvas pins.
- **Labels** in `components` are the expected meaning, not exact strings. Tests
  match them loosely (case- and punctuation-insensitive, key words present).
  `embeddedText` is matched exactly, ignoring case and punctuation.
- **deviceCheck** items can only be checked by a person on the Monarch.

## Changing a case

A case changes only by human decision. If code and case disagree, record it in
`docs/agent/DECISIONS.md` and ask; do not edit the case to make a test pass.

## Grading

`../judge.py <case-id> <result.json>` runs exact checks locally, then asks
`claude-sonnet-5-5` (via `claude -p`, using the local Claude Code login) to grade the remaining items within these tolerances, using
the source image and a picture of the generated pins. Add `--no-llm` for exact
checks only. Reports go to `reports/` (git-ignored).
