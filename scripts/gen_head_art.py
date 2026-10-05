#!/usr/bin/env python3
"""Generate the head-only Octo mascot art via the PPQ Grok-Imagine images API.

Phase 10: the launcher icon must be ONLY the octopus head with its 8 tentacles
(bust/portrait style — no torso, no legs). This script generates that art:
  - Output #1: docs/art/octo_head_raw.png     (raw API result, kept for reference)
  - Output #2: app/src/main/res/drawable-nodpi/octo_mascot.png (same image, used in-app)

API key is READ from /home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt
(read-only; this script never writes outside the Octo repo).
Stdlib only (urllib). On failure it exits non-zero; make_icon_art.py then falls
back to cropping the head off the existing full-figure art.
"""
from __future__ import annotations

import json
import sys
import urllib.request
from pathlib import Path

OCTO_ROOT = Path(__file__).resolve().parent.parent
KEY_PATH = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")
RAW_OUT = OCTO_ROOT / "docs" / "art" / "octo_head_raw.png"
RES_OUT = OCTO_ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "octo_mascot.png"

URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"

PROMPT = """Character portrait bust, centered square composition: ONLY the head of \
a stylized man whose head is an octopus — smooth rounded octopus mantle as the \
skull with two calm cute friendly eyes, and eight short octopus tentacles \
cascading from where hair would be, four draping over each side of the head, \
tips curling, no human body, no shoulders, no torso, no legs — head and \
tentacles only. Flat cel-shaded vector illustration, clean uniform color \
regions, minimal shading, deep-sea teal and purple color palette with soft \
bioluminescent glow accents, plain dark deep-sea background, generous margin \
around the head, cute and serene mood, no text, no letters."""


def http_post_json(url: str, key: str, body: dict, timeout: int = 180) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_get_bytes(url: str, timeout: int = 120) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "octo-art-gen/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def main() -> int:
    if not KEY_PATH.exists():
        print(f"ABORT: key file missing: {KEY_PATH}", file=sys.stderr)
        return 2
    key = KEY_PATH.read_text(encoding="utf-8").strip()
    if not key:
        print("ABORT: key file empty", file=sys.stderr)
        return 2

    RAW_OUT.parent.mkdir(parents=True, exist_ok=True)
    RES_OUT.parent.mkdir(parents=True, exist_ok=True)

    payload = {
        "model": MODEL,
        "prompt": PROMPT,
        "quality": "medium",
        "resolution": "1k",
        "aspect_ratio": "1:1",
        "n": 1,
    }

    last_err: Exception | None = None
    for attempt in range(1, 4):
        try:
            print(f"[{attempt}/3] POST {URL} model={MODEL} ...")
            data = http_post_json(URL, key, payload)
            items = data.get("data") or []
            image_url = (items[0] or {}).get("url") if items else None
            if not image_url:
                b64 = (items[0] or {}).get("b64_json") if items else None
                if b64:
                    import base64

                    png = base64.b64decode(b64)
                else:
                    raise RuntimeError(f"no image in response: {str(data)[:300]}")
            else:
                print(f"downloading {image_url}")
                png = http_get_bytes(image_url)
            if len(png) < 1024:
                raise RuntimeError(f"suspiciously small image ({len(png)} bytes)")

            RAW_OUT.write_bytes(png)
            RES_OUT.write_bytes(png)
            print(f"OK: {len(png) / 1024:.0f} KiB -> {RAW_OUT}")
            print(f"OK: {len(png) / 1024:.0f} KiB -> {RES_OUT}")
            if "cost" in data:
                print(f"cost: {data['cost']}")
            return 0
        except Exception as err:  # noqa: BLE001 — graceful skip after tries
            last_err = err
            print(f"attempt {attempt} failed: {err}", file=sys.stderr)

    print(f"SKIPPED after 3 attempts: {last_err}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
