#!/usr/bin/env python3
"""Forwarding entry point to scripts/video/make_video.py."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from scripts.video.make_video import main

if __name__ == "__main__":
    raise SystemExit(main())
