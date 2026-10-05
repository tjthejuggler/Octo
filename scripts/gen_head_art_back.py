#!/usr/bin/env python3
"""Generate the BACK-view head art for the octopus man via the PPQ Grok-Imagine API.

Phase 21 addendum (user request): the on-screen man must look different from the
front and the back. The front keeps the Phase 10 PPQ head (face visible); the
back gets its own art — same character seen from behind: smooth mantle, NO face,
tentacles cascading forward over where the shoulders would be.

Outputs:
  - docs/art/octo_head_back_raw.png (raw API result, archived)
Only writes inside the Octo repo; key is read-only (same file as gen_head_art.py).
"""
from __future__ import annotations

import json
import sys
import urllib.request
from pathlib import Path

OCTO_ROOT = Path(__file__).resolve().parent.parent
KEY_PATH = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")
RAW_OUT = OCTO_ROOT / "docs" / "art" / "octo_head_back_raw.png"

URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"

PROMPT = """Character portrait bust, centered square composition, viewed strictly \
from BEHIND: ONLY the back of the head of a stylized man whose head is an octopus \
— a LARGE smooth rounded octopus mantle as the skull seen from the back, the big \
smooth teal mantle dome filling the upper two thirds of the frame, NO face, no \
eyes, no mouth, completely smooth rounded mantle back, and eight short octopus \
tentacles cascading from where hair would be, draping down over each side of the \
mantle, tips curling, no human body, no shoulders, no torso, no legs — head and \
tentacles only. Flat cel-shaded vector illustration, clean uniform color \
regions, minimal shading, deep-sea teal and purple color palette with soft \
bioluminescent glow accents, plain dark deep-sea background, cute and serene \
mood, no text, no letters."""


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
            print(f"OK: {len(png) / 1024:.0f} KiB -> {RAW_OUT}")
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
