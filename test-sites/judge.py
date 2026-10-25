"""Grade a generated result against a site test case.

Exact checks (pin fill, box overlap, counts) run in code. Items that need
judgement (does this pin drawing read as a stick figure? is this label close
enough?) go to a model grader with the case's tolerances, the source image and a
rendering of the generated pins: Claude Sonnet 5.5 locally (--grader sonnet,
default), Gemini 4 Argon in the agent sandbox and production (--grader argon).
Both use the same prompt and verdict schema.

Usage:
    .venv/bin/python test-sites/judge.py <case-id> <result.json> [--grader sonnet|argon] [--no-llm]

Result file format (written by the local app and by the agent's modules):
{
  "caseId": "xkcd-327",
  "image": "comic.png",                       # file in snapshots/<case>/images/
  "textView": {"buttons": [{"label": "...", "src": "..."}]},
  "components": [{"id": "p1", "label": "Panel 1", "box": [x0, y0, x1, y1],
                  "parentId": null, "embeddedText": "..."}],   # source pixels
  "pins": {"overview1x": ["0110...", ...],     # 40 strings of 96 chars, '1' = raised
           "isolate:p3": [...], "zoom2x": [...]}
}

Sonnet grading uses the local Claude Code login (`claude` CLI); Argon needs GEMINI_API_KEY.
"""

import argparse
import base64
import io
import json
import subprocess
import sys
from pathlib import Path

from dotenv import load_dotenv
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CANVAS_ROWS = 35
COLS = 96
SONNET_MODEL = "claude-sonnet-5-5"  # local grading via `claude -p`
ARGON_MODEL = "gemini-4-argon"  # agent sandbox and production grading

VERDICTS_SCHEMA = {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "section": {"type": "string"},
                    "item": {"type": "string"},
                    "verdict": {"type": "string", "enum": ["pass", "fail", "uncertain"]},
                    "reason": {"type": "string"},
                },
                "required": ["section", "item", "verdict", "reason"],
                "additionalProperties": False,
            },
        },
        "summary": {"type": "string"},
    },
    "required": ["items", "summary"],
    "additionalProperties": False,
}

SYSTEM = """You grade tactile-graphics output for blind readers on a 96x40 pin display
(rows 0-34 canvas, row 35 blank, rows 36-39 Braille caption).

You get: a test case (the ground truth, written by a person), exact measurements
already computed by code, the source image, and pictures of the generated pin
layouts (black dot = raised pin).

Grade every expectation in the case that the result can be checked against.
Tolerance rules:
- Labels match in meaning, not wording ("Mom holding the phone" ~ "woman on phone").
- embeddedText must match the words exactly, ignoring case and punctuation.
- Boxes: use the provided overlap numbers; IoU >= 0.5 or within the case tolerance passes.
- Pin drawings: pass if a person exploring by touch would recognise the described
  shape (closed outline, one-pin lines, separate parts, no clutter). Do not demand
  pixel accuracy.
- Trust the exact measurements over your own visual estimate.
- If the result has no data for an item, mark it "uncertain" with reason "not in result".
Be strict about clutter, doubled lines and text drawn as pins."""


def fill(rows):
    canvas = rows[:CANVAS_ROWS]
    return sum(r.count("1") for r in canvas) / (CANVAS_ROWS * COLS)


def iou(a, b):
    x0, y0 = max(a[0], b[0]), max(a[1], b[1])
    x1, y1 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, x1 - x0) * max(0, y1 - y0)
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union else 0.0


def flatten(components, parent=None):
    for c in components:
        yield c, parent
        yield from flatten(c.get("children", []), c)


def exact_checks(case, result):
    checks = {}
    max_fill = case.get("tactile", {}).get("overview1x", {}).get("maxFill", 0.25)
    for view, rows in result.get("pins", {}).items():
        f = fill(rows)
        checks[f"fill:{view}"] = {"value": round(f, 3), "limit": max_fill, "pass": f <= max_fill}

    generated = [c for c in result.get("components", []) if c.get("box")]
    for expected, _ in flatten(case.get("components", [])):
        if not expected.get("box"):
            continue
        best = max(generated, key=lambda g: iou(g["box"], expected["box"]), default=None)
        score = iou(best["box"], expected["box"]) if best else 0.0
        checks[f"box:{expected['label']}"] = {
            "bestMatch": best["label"] if best else None, "iou": round(score, 2), "pass": score >= 0.5}

    top_expected = len(case.get("components", []))
    top_generated = sum(1 for c in result.get("components", []) if not c.get("parentId"))
    checks["topLevelCount"] = {"expected": top_expected, "generated": top_generated,
                               "pass": top_expected == top_generated}
    return checks


def pins_png(rows, scale=8):
    image = Image.new("L", (COLS * scale, len(rows) * scale), 255)
    pixels = image.load()
    for y, row in enumerate(rows):
        for x, cell in enumerate(row[:COLS]):
            if cell == "1":
                for dy in range(1, scale - 1):
                    for dx in range(1, scale - 1):
                        pixels[x * scale + dx, y * scale + dy] = 0
    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    return buffer.getvalue()


def grading_parts(case, result, checks):
    """Provider-neutral prompt: a list of ("text", str) and ("image", bytes, mime)."""
    parts = []
    source = HERE / "snapshots" / case["id"] / "images" / result.get("image", "")
    if result.get("image") and source.exists() and source.suffix.lower() in (".png", ".jpg", ".jpeg"):
        media = "image/png" if source.suffix.lower() == ".png" else "image/jpeg"
        parts += [("text", "Source image:"), ("image", source.read_bytes(), media)]
    for view, rows in result.get("pins", {}).items():
        parts += [("text", f"Generated pins, view '{view}':"), ("image", pins_png(rows), "image/png")]
    parts.append(("text", (
        "Test case (ground truth):\n" + json.dumps(case, indent=1, ensure_ascii=False)
        + "\n\nGenerated result (without pin arrays):\n"
        + json.dumps({k: v for k, v in result.items() if k != "pins"}, indent=1, ensure_ascii=False)
        + "\n\nExact measurements:\n" + json.dumps(checks, indent=1)
        + "\n\nGrade each checkable expectation.")))
    return parts


def grade_sonnet(parts):
    """Local grading through the Claude Code CLI (`claude -p`), using the local
    Claude Code login; no API key. Images are written to files that the model
    opens with the Read tool, since `claude -p` takes text on stdin."""
    image_dir = HERE / "reports" / "_judge_images"
    image_dir.mkdir(parents=True, exist_ok=True)
    prompt = []
    for n, part in enumerate(parts):
        if part[0] == "text":
            prompt.append(part[1])
        else:
            path = image_dir / f"{n:02d}.{'png' if part[2] == 'image/png' else 'jpg'}"
            path.write_bytes(part[1])
            prompt.append(f"[Open this image with the Read tool: {path}]")
    prompt.append("Open every image listed above before grading.")

    command = ["claude", "-p", "--model", SONNET_MODEL,
               "--append-system-prompt", SYSTEM,
               "--allowedTools", "Read", "--add-dir", str(image_dir),
               "--output-format", "json", "--json-schema", json.dumps(VERDICTS_SCHEMA)]
    last_error = "no attempt"
    for _ in range(2):  # one retry on a failed run or unparsable output
        try:
            run = subprocess.run(command, input="\n\n".join(prompt), capture_output=True,
                                 text=True, timeout=900, cwd=HERE)
        except subprocess.TimeoutExpired:
            last_error = "timed out after 900 s"
            continue
        if run.returncode != 0:
            last_error = f"claude exited {run.returncode}: {run.stderr.strip()[:300]}"
            continue
        try:
            envelope = json.loads(run.stdout)
            verdict = envelope.get("structured_output") or json.loads(envelope["result"])
            if "items" in verdict:
                return verdict
            last_error = "verdict has no items"
        except (json.JSONDecodeError, KeyError, TypeError) as error:
            last_error = f"unparsable output: {error}"
    sys.exit(f"sonnet grader failed: {last_error}")


def grade_argon(parts):
    """Remote (agent sandbox) and production grading (GEMINI_API_KEY)."""
    from google import genai

    contents = [{"type": "text", "text": SYSTEM}]
    for part in parts:
        if part[0] == "text":
            contents.append({"type": "text", "text": part[1]})
        else:
            contents.append({"type": "image", "data": base64.b64encode(part[1]).decode(), "mime_type": part[2]})
    client = genai.Client()  # keep a reference: a temporary client closes its HTTP pool
    interaction = client.interactions.create(
        model=ARGON_MODEL,
        input=contents,
        generation_config={"thinking_level": "medium"},
        response_format={"type": "text", "mime_type": "application/json", "schema": VERDICTS_SCHEMA},
    )
    return json.loads(interaction.output_text)


GRADERS = {"sonnet": grade_sonnet, "argon": grade_argon}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("case_id")
    parser.add_argument("result")
    parser.add_argument("--grader", choices=sorted(GRADERS), default="sonnet",
                        help="sonnet locally (default); argon in the agent sandbox and production")
    parser.add_argument("--no-llm", action="store_true", help="exact checks only")
    args = parser.parse_args()

    load_dotenv(ROOT / ".env")
    case = json.loads((HERE / "cases" / f"{args.case_id}.json").read_text())
    result = json.loads(Path(args.result).read_text())
    checks = exact_checks(case, result)
    report = {"caseId": args.case_id, "exact": checks}
    if not args.no_llm:
        report["grader"] = args.grader
        report["judge"] = GRADERS[args.grader](grading_parts(case, result, checks))

    out = HERE / "reports" / f"{args.case_id}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False))

    failed_exact = [k for k, v in checks.items() if not v["pass"]]
    verdicts = report.get("judge", {}).get("items", [])
    failed_llm = [v for v in verdicts if v["verdict"] == "fail"]
    print(f"{args.case_id}: exact {len(checks) - len(failed_exact)}/{len(checks)} pass; "
          f"judge {sum(v['verdict'] == 'pass' for v in verdicts)}/{len(verdicts)} pass, "
          f"{len(failed_llm)} fail, {sum(v['verdict'] == 'uncertain' for v in verdicts)} uncertain → {out}")
    for name in failed_exact:
        print(f"  FAIL exact {name}: {checks[name]}")
    for v in failed_llm:
        print(f"  FAIL {v['section']}: {v['item']} — {v['reason']}")
    sys.exit(1 if failed_exact or failed_llm else 0)


if __name__ == "__main__":
    main()
