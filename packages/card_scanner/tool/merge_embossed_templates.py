#!/usr/bin/env python3
"""Merge embossed-digit calibration files into the SDK's built-in templates.

Usage:
  python3 tool/merge_embossed_templates.py calib1.json calib2.json ...

Each input comes from "Calibrate embossed reader (dev)" in the harness
(adb pull /sdcard/Android/data/<app id>/files/embossed_calibration_*.json).
Inputs contain only averaged digit shapes, never card numbers.

Digits are averaged across files, weighted by frames, then re-normalised
(zero mean, unit norm), and written to
android/src/main/assets/card_scanner_embossed_templates.json.
"""
import json
import math
import os
import sys

FORMAT = "gradmag-16x24-v1"
SIZE = 16 * 24
OUT = os.path.join(os.path.dirname(__file__), "..", "android", "src", "main",
                   "assets", "card_scanner_embossed_templates.json")


def normalise(v):
    mean = sum(v) / len(v)
    v = [x - mean for x in v]
    norm = math.sqrt(sum(x * x for x in v))
    return [x / norm for x in v] if norm > 1e-9 else v


def main(paths):
    sums = {d: [0.0] * SIZE for d in range(10)}
    weights = {d: 0.0 for d in range(10)}
    total_frames = 0
    for path in paths:
        with open(path) as f:
            data = json.load(f)
        if data.get("format") != FORMAT:
            sys.exit(f"{path}: unsupported format {data.get('format')}")
        frames = max(1, int(data.get("frames", 1)))
        total_frames += frames
        for d, values in data["digits"].items():
            if len(values) != SIZE:
                sys.exit(f"{path}: digit {d} has {len(values)} values")
            d = int(d)
            for i, v in enumerate(values):
                sums[d][i] += v * frames
            weights[d] += frames

    digits = {str(d): [round(x, 5) for x in normalise(sums[d])]
              for d in range(10) if weights[d] > 0}
    missing = [d for d in range(10) if weights[d] == 0]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump({"format": FORMAT, "frames": total_frames, "digits": digits}, f,
                  separators=(",", ":"))
    print(f"wrote {os.path.normpath(OUT)}: {len(digits)}/10 digits from "
          f"{len(paths)} file(s), {total_frames} frames")
    if missing:
        print(f"missing digits (calibrate a card that contains them): {missing}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
