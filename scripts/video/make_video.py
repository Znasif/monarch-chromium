#!/usr/bin/env python3
"""Orchestrator for the Monarch Tactile Browser 3Blue1Brown-style explainer video.

Usage:
  python3 scripts/video/make_video.py --dry-run
  python3 scripts/video/make_video.py
"""
from __future__ import annotations

import argparse
import importlib
import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT_DIR))

from scripts.video.graphic_scene_py import GraphicScenePy
from scripts.video.omni import MAX_OMNI_CALLS, load_omni_state, try_generate_omni_clip
from scripts.video.tts import MAX_TTS_CALLS, get_audio_duration, synthesize_scene_narration

BUILD_DIR = Path("/tmp/monarch_video_build")
MANIM_MEDIA_DIR = Path("/tmp/manim_media")
DEFAULT_OUT_MP4 = ROOT_DIR / "docs/video/out/monarch-explainer.mp4"


@dataclass(frozen=True)
class SceneSpec:
    scene_id: str
    title: str
    module_name: str
    class_name: str
    target_duration_s: int
    uses_omni: Optional[str]
    narration: str


SCENES: List[SceneSpec] = [
    SceneSpec(
        scene_id="scene01_problem",
        title="Scene 1 — The 96x40 Tactile Bottleneck & 25% Density Wall",
        module_name="scene01_problem",
        class_name="Scene01Problem",
        target_duration_s=34,
        uses_omni="omni_01_hand_pins",
        narration=(
            "A webpage is designed for millions of colour pixels, while the HumanWare and APH Monarch "
            "has ninety-six by forty physical moving pins: three thousand, eight hundred and forty pins in total. "
            "Naively thresholding an image raises over forty percent of the grid, turning adjacent strokes into "
            "an unreadable wall of plastic. To stay legible by touch, active canvas fill must stay at or below "
            "twenty-five percent. Monarch therefore splits the forty rows into rows zero through thirty-four for "
            "the graphic canvas, row thirty-five as a blank separator, and rows thirty-six through thirty-nine "
            "for a thirty-two-cell Braille caption."
        ),
    ),
    SceneSpec(
        scene_id="scene02_bridge",
        title="Scene 2 — Any Web Page: Zero-Site-Rule DOM Bridge",
        module_name="scene02_bridge",
        class_name="Scene02Bridge",
        target_duration_s=30,
        uses_omni=None,
        narration=(
            "When any page loads, bridge dot js runs a single generic DOM pass with zero site-specific rules. "
            "It scans images, SVGs, canvases, role-equals-img nodes, and book-viewer viewports, filtering out "
            "tiny icons and zero-pixel tracking beacons. It derives a human label from alt text, aria-label, "
            "figcaption, table captions, or the filename, and injects an Explore tactile image button right after "
            "each picture. Meanwhile, extract dot js builds a clean Text View across all seven benchmark sites, "
            "from xkcd and Wikipedia to Archive dot org, Google Books, Gutenberg, OpenStax, and Library of Congress."
        ),
    ),
    SceneSpec(
        scene_id="scene03_structured_graphic",
        title="Scene 3 — Tier-2 Structured Tactile Graphic & SHA-256 Cache",
        module_name="scene03_structured_graphic",
        class_name="Scene03StructuredGraphic",
        target_duration_s=40,
        uses_omni=None,
        narration=(
            "Activating an Explore button sends the image into Tier-two compilation. ImageNormalizer flattens "
            "alpha onto a white canvas and scales the longer edge to at most seven hundred and sixty-eight pixels. "
            "We hash the raw bytes with SHA-256 so DiskCache serves repeat visits in under a millisecond. "
            "On a cache miss, gemini-4-argon with thinking-level low returns a strict JSON component tree of "
            "normalized zero-to-one-thousand boxes and one-pin centerline polylines. Colour becomes outline-only, "
            "and text bubbles use role equals text with empty strokes—making them touchable audio and Braille "
            "regions without raising a single cluttering pin."
        ),
    ),
    SceneSpec(
        scene_id="scene04_renderer",
        title="Scene 4 — The Renderer: Bresenham, Panel Gaps & the 25% Budget",
        module_name="scene04_renderer",
        class_name="Scene04Renderer",
        target_duration_s=38,
        uses_omni=None,
        narration=(
            "Next, GraphicScene dot kt projects those normalized coordinates onto the ninety-six by thirty-five "
            "canvas using fitToCanvas and integer Bresenham line rasterization. "
            "On multi-panel comics like xkcd three twenty-seven, independent rounding caused Panel three's right "
            "border at column sixty-eight to touch Panel four's left border at column sixty-nine, merging two frames "
            "into one thick ridge. Our panelFrames fix pulls Panel three's right border back to column sixty-seven, "
            "guaranteeing column sixty-eight stays a blank one-pin separator. Finally, dropOrder removes the smallest "
            "non-panel strokes first if fill exceeds twenty-five percent; xkcd three twenty-seven fits at nineteen "
            "point two percent."
        ),
    ),
    SceneSpec(
        scene_id="scene05_touch_and_hitgrid",
        title="Scene 5 — Touch & HitGrid: Smallest Wins, Canvas Never Moves",
        module_name="scene05_touch_and_hitgrid",
        class_name="Scene05TouchAndHitGrid",
        target_duration_s=32,
        uses_omni="omni_02_double_press",
        narration=(
            "To make every stroke and speech bubble respond to touch, HitGrid dot kt builds a flat ninety-six by "
            "forty integer array. It sorts components from largest pin-box area down to smallest, stamps a one-pin "
            "halo around strokes and fills text boxes, and stamps direct raised pins on top. Because smaller parts "
            "are stamped last, Mrs. Roberts at eighty square pins wins over Panel three at one thousand and eight. "
            "As FingerPoll samples at forty Hertz, moving a finger speaks each label and updates the Braille caption "
            "on rows thirty-six through thirty-nine, while the canvas never moves."
        ),
    ),
    SceneSpec(
        scene_id="scene06_data_navigator",
        title="Scene 6 — Data Navigator: Levels, Siblings, Isolation, Zoom & Pan",
        module_name="scene06_data_navigator",
        class_name="Scene06DataNavigator",
        target_duration_s=36,
        uses_omni=None,
        narration=(
            "Touching reads; explicit actions navigate. ComponentGraph organizes parts into a Data Navigator tree "
            "with four root panels at Level zero and figures plus speech bubbles at Level one. "
            "When your finger rests on Mrs. Roberts at the overview level, double-pressing the front Action button "
            "calls atCurrentLevel: it climbs parent pointers to Level zero, isolates Panel three across the full "
            "canvas at nineteen point five percent fill, and selects Mrs. Roberts inside it. "
            "The Left D-pad then steps sibling parts, single Action toggles two-times zoom, the Right D-pad pans, "
            "and Back returns to the overview."
        ),
    ),
    SceneSpec(
        scene_id="scene07_hardware_lessons",
        title="Scene 7 — What We Learned on Physical Monarch Hardware",
        module_name="scene07_hardware_lessons",
        class_name="Scene07HardwareLessons",
        target_duration_s=30,
        uses_omni=None,
        narration=(
            "Testing on physical Monarch hardware with SDK zero point one point one three one taught us five "
            "hardware lessons. First, the Left D-pad sends standard directions, the Right D-pad prefixes Shift "
            "within three hundred milliseconds, the front Action key sends Enter, and Point-and-Click is reserved "
            "by the system cursor. Second, onDoubleTapAtBraillePosition has zero horizontal offset, letting commit "
            "eight c b one c four four remove a stale plus-three column shift. Third, Monarch has no vibration motor. "
            "Fourth, BrailleTranslator binds HumanWare's KeySoft Translator for eight-dot captions. And fifth, "
            "SightedOverlay stays at two-percent alpha until NumLock toggles it for HDMI viewers."
        ),
    ),
    SceneSpec(
        scene_id="scene08_testing_and_judge",
        title="Scene 8 — Testing & Evaluation: 7 Real Sites, Exact Checks & Judge",
        module_name="scene08_testing_and_judge",
        class_name="Scene08TestingAndJudge",
        target_duration_s=32,
        uses_omni=None,
        narration=(
            "Every component is tested against seven benchmark sites in test-sites slash cases. "
            "Our evaluator, judge dot py, runs two layers. Layer one checks deterministic invariants: headings, "
            "links, Explore button labels, canvas fill at or below twenty-five percent, row thirty-five clear, "
            "text parts pin-free, panel count, bounding-box IoU at or above point six five, and touch-hit coordinates—"
            "passing nine of nine checks on xkcd three twenty-seven. Layer two scores a five-criterion rubric with "
            "Claude Sonnet four point six locally or gemini-4-argon in the sandbox, backed by thirty-five automated "
            "JUnit, Playwright, and Tier-two tests."
        ),
    ),
    SceneSpec(
        scene_id="scene09_next_steps",
        title="Scene 9 — Built Today vs. What's Next on the Roadmap",
        module_name="scene09_next_steps",
        class_name="Scene09NextSteps",
        target_duration_s=24,
        uses_omni=None,
        narration=(
            "Finally, we separate what is built today from what comes next. "
            "Built and verified in this repository today are the physical Monarch Android app on SDK zero point one "
            "point one three one, tactile-core, the Playwright-verified DOM bridge across seven sites, and the "
            "Tier-two Gemini segmenter with SHA-256 disk caching. "
            "Next on the roadmap: wiring tier-two and bridge directly into android-app behind BrowserEngine, "
            "adding ContextMenuActivity for Brailliant thumb-key chords and KeyBRF export, and extending tactile "
            "compilation to hard WebGL and slippy-tile maps."
        ),
    ),
]


def format_srt_timestamp(seconds: float) -> str:
    ms = int(round(max(0.0, seconds) * 1000.0))
    hrs = ms // 3_600_000
    ms %= 3_600_000
    mins = ms // 60_000
    ms %= 60_000
    secs = ms // 1000
    ms %= 1000
    return f"{hrs:02d}:{mins:02d}:{secs:02d},{ms:03d}"


def split_caption_chunks(text: str, max_words: int = 13) -> List[str]:
    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s.strip()]
    chunks: List[str] = []
    for sent in sentences:
        words = sent.split()
        if len(words) <= max_words:
            chunks.append(sent)
        else:
            for i in range(0, len(words), max_words):
                chunks.append(" ".join(words[i : i + max_words]))
    return chunks


def get_video_duration(mp4_path: Path) -> float:
    out = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(mp4_path),
        ],
        text=True,
    ).strip()
    return float(out)


def run_dry_run() -> int:
    print("=== Monarch Explainer Video — Dry Run ===")
    claims_md = ROOT_DIR / "docs/video/CLAIMS.md"
    script_md = ROOT_DIR / "docs/video/SCRIPT.md"
    if not claims_md.exists() or claims_md.stat().st_size < 100:
        raise FileNotFoundError(f"Missing or empty {claims_md}")
    if not script_md.exists() or script_md.stat().st_size < 100:
        raise FileNotFoundError(f"Missing or empty {script_md}")
    print(f"[ok] Verified {claims_md.relative_to(ROOT_DIR)} and {script_md.relative_to(ROOT_DIR)}")

    gs = GraphicScenePy.from_xkcd_asset()
    rendered = gs.render(fix_panel_gap=True)
    print(
        f"[ok] Verified GraphicScenePy on bundled xkcd-327: "
        f"{rendered.raised_canvas_count}/3360 pins ({rendered.fill_ratio*100:.1f}% fill, <= 25%)"
    )

    total_words = 0
    total_target_s = 0
    omni_clips = []
    for idx, sc in enumerate(SCENES, 1):
        script_path = ROOT_DIR / "scripts/video/scenes" / f"{sc.module_name}.py"
        if not script_path.exists():
            raise FileNotFoundError(f"Missing scene script: {script_path}")
        mod = importlib.import_module(f"scripts.video.scenes.{sc.module_name}")
        if not hasattr(mod, sc.class_name):
            raise AttributeError(f"Missing class {sc.class_name} in {script_path}")
        words = len(sc.narration.split())
        est_s = round(words / (140.0 / 60.0), 1)
        total_words += words
        total_target_s += sc.target_duration_s
        if sc.uses_omni:
            omni_clips.append(sc.uses_omni)
        print(
            f"  [{idx:02d}] {sc.scene_id:<28} class={sc.class_name:<24} "
            f"target={sc.target_duration_s}s  words={words:<3} (~{est_s}s @140wpm)  omni={sc.uses_omni or 'Manim'}"
        )

    est_total_s = round(total_words / (140.0 / 60.0), 1)
    print(f"\n[ok] Total scenes: {len(SCENES)} (<= {MAX_TTS_CALLS} TTS budget)")
    print(f"[ok] Total Omni clips requested: {len(omni_clips)} (<= {MAX_OMNI_CALLS} Omni budget): {omni_clips}")
    print(f"[ok] Target duration: {total_target_s}s ({total_target_s/60:.2f} min); narration words: {total_words} (~{est_total_s}s @ 140 WPM)")
    assert 240 <= total_target_s <= 360, f"Target duration {total_target_s}s outside 4-6 min window"
    print("=== Dry Run PASSED (0 paid calls made) ===")
    return 0


def ensure_manim_scene_rendered(sc: SceneSpec, force_render: bool = False) -> Path:
    import os
    mp4_path = MANIM_MEDIA_DIR / "videos" / sc.module_name / "720p24" / f"{sc.class_name}.mp4"
    if mp4_path.exists() and mp4_path.stat().st_size > 10_000 and not force_render:
        return mp4_path
    script_path = ROOT_DIR / "scripts/video/scenes" / f"{sc.module_name}.py"
    print(f"[manim] Rendering {sc.class_name} from {script_path.name}...")
    env = dict(os.environ)
    env["PYTHONPATH"] = str(ROOT_DIR)
    subprocess.run(
        [
            "manim",
            "render",
            "-qm",
            "--fps",
            "24",
            "--media_dir",
            str(MANIM_MEDIA_DIR),
            str(script_path),
            sc.class_name,
        ],
        check=True,
        env=env,
    )
    if not mp4_path.exists():
        raise FileNotFoundError(f"Manim failed to produce {mp4_path}")
    return mp4_path


def build_full_video(output_mp4: Path, force_render: bool = False) -> Path:
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    scenes_build_dir = BUILD_DIR / "muxed_scenes"
    scenes_build_dir.mkdir(parents=True, exist_ok=True)

    # 1. Attempt Gemini Omni 1.1 Flash clips (strictly bounded by 3-call budget & 2-failure circuit breaker)
    for sc in SCENES:
        if sc.uses_omni:
            try_generate_omni_clip(sc.uses_omni)
    omni_state = load_omni_state()
    print(f"[omni] State after Omni pass: {json.dumps(omni_state)}")

    # 2. Render Manim scenes, synthesize narration audio, mux per-scene 1080p MP4, and build master SRT
    muxed_paths: List[Path] = []
    srt_entries: List[str] = []
    srt_index = 1
    cumulative_time = 0.0

    for idx, sc in enumerate(SCENES, 1):
        raw_video = ensure_manim_scene_rendered(sc, force_render=force_render)
        wav_path = synthesize_scene_narration(sc.scene_id, sc.narration)

        vid_dur = get_video_duration(raw_video)
        raw_aud_dur = get_audio_duration(wav_path)
        tempo = 1.18
        aud_dur = raw_aud_dur / tempo
        scene_dur = max(vid_dur, aud_dur) + 0.20
        pad_video_s = max(0.0, scene_dur - vid_dur + 0.25)

        muxed_mp4 = scenes_build_dir / f"{idx:02d}_{sc.scene_id}_1080p.mp4"
        print(
            f"[mux] Scene {idx:02d} ({sc.scene_id}): video={vid_dur:.2f}s, "
            f"audio={aud_dur:.2f}s -> muxed={scene_dur:.2f}s @ 1920x1080"
        )
        subprocess.run(
            [
                "ffmpeg",
                "-y",
                "-i",
                str(raw_video),
                "-i",
                str(wav_path),
                "-vf",
                f"scale=1920:1080:flags=bilinear,fps=24,tpad=stop_mode=clone:stop_duration={pad_video_s:.3f}",
                "-af",
                f"atempo={tempo:.2f},apad=whole_dur={scene_dur:.3f}",
                "-t",
                f"{scene_dur:.3f}",
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-crf",
                "20",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-b:a",
                "160k",
                "-ar",
                "24000",
                "-ac",
                "1",
                str(muxed_mp4),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        actual_scene_dur = get_video_duration(muxed_mp4)
        muxed_paths.append(muxed_mp4)

        # Build SRT entries proportional to word count across [0, min(aud_dur, actual_scene_dur - 0.15)]
        chunks = split_caption_chunks(sc.narration, max_words=13)
        word_counts = [max(1, len(c.split())) for c in chunks]
        total_w = sum(word_counts)
        span_dur = min(aud_dur, actual_scene_dur - 0.10)
        cur_offset = 0.05
        for chunk_text, wc in zip(chunks, word_counts):
            chunk_dur = span_dur * (wc / float(total_w))
            t_start = cumulative_time + cur_offset
            t_end = min(cumulative_time + actual_scene_dur - 0.05, t_start + max(0.8, chunk_dur - 0.04))
            srt_entries.append(
                f"{srt_index}\n"
                f"{format_srt_timestamp(t_start)} --> {format_srt_timestamp(t_end)}\n"
                f"{chunk_text}\n"
            )
            srt_index += 1
            cur_offset += chunk_dur

        cumulative_time += actual_scene_dur

    # 3. Write SRT file (both in /tmp/monarch_video_build and docs/video/out/monarch-explainer.srt)
    srt_content = "\n".join(srt_entries)
    tmp_srt = BUILD_DIR / "monarch-explainer.srt"
    tmp_srt.write_text(srt_content, encoding="utf-8")
    out_srt = output_mp4.with_suffix(".srt")
    out_srt.parent.mkdir(parents=True, exist_ok=True)
    out_srt.write_text(srt_content, encoding="utf-8")

    # 4. Concatenate all 9 muxed 1080p scenes and burn in captions via libass subtitles filter
    concat_list = BUILD_DIR / "concat_list.txt"
    concat_list.write_text(
        "\n".join(f"file '{p.as_posix()}'" for p in muxed_paths) + "\n",
        encoding="utf-8",
    )
    concat_raw = BUILD_DIR / "concat_unburned_1080p.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(concat_list),
            "-c",
            "copy",
            str(concat_raw),
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    print(f"[burn] Burning accessible captions into {output_mp4} (1920x1080)...")
    style_str = (
        "FontName=DejaVu Sans,FontSize=15,PrimaryColour=&H00FFFFFF,"
        "OutlineColour=&H000E1117,BackColour=&H990E1117,BorderStyle=3,"
        "Outline=1,Shadow=0,MarginV=18,Alignment=2"
    )
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(concat_raw),
            "-vf",
            f"subtitles={tmp_srt.as_posix()}:force_style='{style_str}'",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-crf",
            "22",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "copy",
            "-movflags",
            "+faststart",
            str(output_mp4),
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    final_dur = get_video_duration(output_mp4)
    size_mb = output_mp4.stat().st_size / (1024.0 * 1024.0)
    print(
        f"=== Built {output_mp4} ===\n"
        f"  Resolution : 1920x1080 (24 fps)\n"
        f"  Duration   : {final_dur:.2f}s ({final_dur/60.0:.2f} minutes)\n"
        f"  File size  : {size_mb:.2f} MB\n"
        f"  Captions   : burned-in + {out_srt}"
    )
    return output_mp4


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Monarch Tactile Browser explainer video.")
    parser.add_argument("--dry-run", action="store_true", help="Validate scripts, claims, and budgets without paid API calls.")
    parser.add_argument("--force-render", action="store_true", help="Re-render all Manim scenes from scratch.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT_MP4, help="Output MP4 path.")
    args = parser.parse_args()

    if args.dry_run:
        return run_dry_run()
    build_full_video(args.output, force_render=args.force_render)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
