"""Narration speech synthesis for the Monarch Tactile Browser explainer video.

Primary model: `gemini-3.8-flash-tts` via the official `google-genai` Interactions API
(as recommended in `/workspace/ref/skills/gemini-api-dev.md`).
Enforces Rule 7:
  - Budget: at most 12 TTS calls total.
  - Circuit breaker: stops calling the paid Gemini TTS endpoint immediately if a call
    fails twice (`MAX_FAILURES = 2`), never looping on paid calls, and falls back to
    neural TTS (`edge-tts` `en-US-ChristopherNeural` / `gTTS` / `espeak-ng`) so every
    scene has clear spoken narration in the sandbox.
"""

from __future__ import annotations

import base64
import json
import os
import subprocess
from pathlib import Path
from typing import Dict, Optional

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
AUDIO_DIR = Path("/tmp/monarch_video_build/audio")
TTS_STATE_FILE = Path("/tmp/monarch_video_build/tts_budget_state.json")

GEMINI_TTS_MODEL = "gemini-3.8-flash-tts"
GEMINI_TTS_VOICE = "Kore"
GEMINI_TTS_STYLE = "calm, clear, measured 3Blue1Brown mathematical explainer narration"
MAX_TTS_CALLS = 12
MAX_FAILURES = 2


def _load_budget_state() -> Dict[str, int | bool | str]:
    if TTS_STATE_FILE.exists():
        try:
            return json.loads(TTS_STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"calls": 0, "failures": 0, "circuit_tripped": False, "last_error": ""}


def _save_budget_state(state: Dict[str, int | bool | str]) -> None:
    TTS_STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    TTS_STATE_FILE.write_text(json.dumps(state, indent=2), encoding="utf-8")


def _try_gemini_tts(text: str, out_wav: Path) -> bool:
    """Calls gemini-3.8-flash-tts via google-genai Interactions API with strict 2-failure circuit breaker."""
    state = _load_budget_state()
    if state.get("circuit_tripped"):
        return False
    if int(state.get("calls", 0)) >= MAX_TTS_CALLS:
        print(f"[tts] Gemini TTS budget ({MAX_TTS_CALLS}) reached; skipping paid call.")
        return False

    # Prevent httpx InvalidURL on IPv6 ::1 in NO_PROXY (AGENTS.md §3.4)
    os.environ["NO_PROXY"] = "localhost,127.0.0.1"
    os.environ["no_proxy"] = "localhost,127.0.0.1"

    state["calls"] = int(state.get("calls", 0)) + 1
    _save_budget_state(state)

    try:
        from google import genai

        client = genai.Client()
        interaction = client.interactions.create(
            model=GEMINI_TTS_MODEL,
            input=[
                {
                    "type": "user_input",
                    "content": [
                        {
                            "type": "text",
                            "text": text,
                            "annotations": [
                                {
                                    "type": "speech_metadata",
                                    "style": GEMINI_TTS_STYLE,
                                }
                            ],
                        }
                    ],
                }
            ],
            response_format={"type": "audio"},
            generation_config={
                "speech_config": [
                    {"voice": GEMINI_TTS_VOICE},
                ]
            },
        )
        audio_obj = getattr(interaction, "output_audio", None)
        if audio_obj and getattr(audio_obj, "data", None):
            raw_bytes = base64.b64decode(audio_obj.data)
            tmp_raw = out_wav.with_suffix(".gemini_raw.wav")
            tmp_raw.write_bytes(raw_bytes)
            _normalize_wav(tmp_raw, out_wav)
            tmp_raw.unlink(missing_ok=True)
            print(f"[tts] Generated {out_wav.name} via {GEMINI_TTS_MODEL} (call #{state['calls']}).")
            return True
        raise RuntimeError("Empty output_audio in Gemini TTS response")
    except Exception as exc:
        failures = int(state.get("failures", 0)) + 1
        state["failures"] = failures
        state["last_error"] = str(exc)[:300]
        if failures >= MAX_FAILURES:
            state["circuit_tripped"] = True
            print(
                f"[tts] STOP PAID CALLS: {GEMINI_TTS_MODEL} failed {failures} times "
                f"(last error: {state['last_error']}). Circuit breaker tripped per Rule 7."
            )
        _save_budget_state(state)
        return False


def _normalize_wav(src_audio: Path, dst_wav: Path) -> None:
    """Converts any audio file to 24 kHz mono PCM WAV with gentle loudness normalization."""
    dst_wav.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(src_audio),
            "-af",
            "loudnorm=I=-16:TP=-1.5:LRA=11,apad=pad_dur=0.35",
            "-ar",
            "24000",
            "-ac",
            "1",
            str(dst_wav),
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def synthesize_scene_narration(scene_id: str, text: str, force: bool = False) -> Path:
    """Synthesizes one narration WAV file for `scene_id`."""
    AUDIO_DIR.mkdir(parents=True, exist_ok=True)
    out_wav = AUDIO_DIR / f"{scene_id}.wav"
    if out_wav.exists() and not force and out_wav.stat().st_size > 1024:
        return out_wav

    # 1. Try Gemini 3.8 Flash TTS (strictly bounded by MAX_TTS_CALLS and 2-failure circuit breaker)
    if _try_gemini_tts(text, out_wav):
        return out_wav

    # 2. Fallback: neural edge-tts (`en-US-ChristopherNeural`) via sandbox proxy
    tmp_mp3 = AUDIO_DIR / f"{scene_id}.tmp.mp3"
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY") or ""
    edge_cmd = [
        "edge-tts",
        "--voice",
        "en-US-ChristopherNeural",
        "--rate=-4%",
        "--text",
        text,
        "--write-media",
        str(tmp_mp3),
    ]
    if proxy:
        edge_cmd.extend(["--proxy", proxy])
    res = subprocess.run(edge_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if res.returncode == 0 and tmp_mp3.exists() and tmp_mp3.stat().st_size > 512:
        _normalize_wav(tmp_mp3, out_wav)
        tmp_mp3.unlink(missing_ok=True)
        print(f"[tts] Synthesized {out_wav.name} via neural voice (en-US-ChristopherNeural).")
        return out_wav

    # 3. Secondary fallback: gTTS
    try:
        from gtts import gTTS

        tts = gTTS(text=text, lang="en", slow=False)
        tts.save(str(tmp_mp3))
        _normalize_wav(tmp_mp3, out_wav)
        tmp_mp3.unlink(missing_ok=True)
        print(f"[tts] Synthesized {out_wav.name} via gTTS.")
        return out_wav
    except Exception:
        pass

    # 4. Offline fallback: espeak-ng
    tmp_espeak = AUDIO_DIR / f"{scene_id}.espeak.wav"
    subprocess.run(
        ["espeak-ng", "-v", "en-us", "-s", "145", "-w", str(tmp_espeak), text],
        check=True,
    )
    _normalize_wav(tmp_espeak, out_wav)
    tmp_espeak.unlink(missing_ok=True)
    return out_wav


def get_audio_duration(audio_path: Path) -> float:
    out = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(audio_path),
        ],
        text=True,
    ).strip()
    return float(out)
