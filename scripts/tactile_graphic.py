"""Ask gemini-4-argon for a structured tactile graphic of one image (plan §9).

The same rule for any image on any site: a 768 px copy goes to the model and the
components / centerline strokes come back as JSON (normalized 0..1 coordinates).

Usage:
    .venv/bin/python scripts/tactile_graphic.py <image> <out.json> [--title "alt or caption"]
"""

import argparse
import base64
import io
import json
from pathlib import Path

from dotenv import load_dotenv
from google import genai
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
MODEL = "gemini-4-argon"
MAX_SIDE = 768

POINT = {"type": "array", "items": {"type": "number"}}
SCHEMA = {
    "type": "object",
    "properties": {
        "components": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "label": {"type": "string"},
                    "parentId": {"type": ["string", "null"]},
                    "role": {"type": "string", "enum": ["panel", "figure", "object", "text", "arrow", "other"]},
                    "box": POINT,
                    "strokes": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {"points": {"type": "array", "items": POINT}, "closed": {"type": "boolean"}},
                            "required": ["points", "closed"],
                        },
                    },
                    "embeddedText": {"type": "string"},
                    "description": {"type": "string"},
                },
                "required": ["id", "label", "parentId", "role", "box", "strokes", "embeddedText", "description"],
            },
        }
    },
    "required": ["components"],
}

PROMPT = """Turn this image into a tactile graphic for a blind reader using a 96x40 pin
display. Return its parts as a component tree.

- Top level: the image's main regions (comic panels, figure panels, or one
  component for a single picture). Children: the figures, objects and text areas
  inside them, ordered left to right, top to bottom.
- strokes: polylines tracing each ink line along its CENTER (one line per stroke,
  never the two edges of a thick line). Coordinates are normalized 0..1 of the
  image, origin top-left. Use enough points to keep shapes recognisable (a head is
  a closed loop of 8-12 points; a limb is 2-3 points). Panel borders are closed
  rectangles.
- Draw outlines only: no shading, hatching, texture or colour fill. Put meaningful
  colour into the label instead.
- Lettering is never drawn: make a role "text" component with its box and the
  exact words in embeddedText (reading order), and no strokes.
- Drop decoration that would clutter touch (signatures, watermarks, tiny marks).
- box: [x0, y0, x1, y1] normalized. Children lie inside their parent's box.
- label: short and specific ("Mom on the phone, holding a mug"); description: one
  sentence."""


def low_res_png(path):
    image = Image.open(path).convert("RGBA")
    background = Image.new("RGBA", image.size, "white")  # transparent images: labels stay visible
    image = Image.alpha_composite(background, image).convert("RGB")
    image.thumbnail((MAX_SIDE, MAX_SIDE))
    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    return buffer.getvalue()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("image")
    parser.add_argument("out")
    parser.add_argument("--title", default="")
    args = parser.parse_args()

    load_dotenv(ROOT / ".env")
    client = genai.Client()
    text = PROMPT + (f"\n\nThe page describes this image as: {args.title}" if args.title else "")
    interaction = client.interactions.create(
        model=MODEL,
        input=[
            {"type": "text", "text": text},
            {"type": "image", "data": base64.b64encode(low_res_png(args.image)).decode(), "mime_type": "image/png"},
        ],
        generation_config={"thinking_level": "low"},
        response_format={"type": "text", "mime_type": "application/json", "schema": SCHEMA},
    )
    graphic = json.loads(interaction.output_text)
    Path(args.out).write_text(json.dumps(graphic, indent=1, ensure_ascii=False))
    strokes = sum(len(c["strokes"]) for c in graphic["components"])
    print(f"{len(graphic['components'])} components, {strokes} strokes -> {args.out}")


if __name__ == "__main__":
    main()
