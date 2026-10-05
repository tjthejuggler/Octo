#!/usr/bin/env python3
"""Build Octo launcher icon + in-app mascot assets from the PPQ head art.

Phase 18 CORRECTION (reverts the Phase 17 direction): the user liked the
PPQ-generated octopus-head art (Phase 10, docs/art/octo_head_raw.png) as the
app icon — Phase 17 wrongly replaced it with a vector render of the on-screen
man. This script restores the PPQ art everywhere the icon/mascot is used AND
additionally emits a transparent cutout that OctopusBodyView composites as the
head of the vector man, so launcher icon and on-screen character are one face.

Source of truth: docs/art/octo_head_raw.png (1408px PPQ grok-imagine render,
dark navy background — NOT transparent). Background removal is done locally
(numpy + scipy flood-fill chroma keying); no PPQ re-generation is needed.

Outputs (all inside the Octo repo):
  - docs/art/octo_head_cutout.png              (transparent master, archived)
  - app/src/main/res/drawable-nodpi/octo_head_ppq.png
      (same cutout — composited onto the vector man by OctopusBodyView.kt)
  - app/src/main/res/drawable-nodpi/ic_launcher_foreground.png
      (adaptive-icon foreground: transparent, cutout inside the 66dp safe
       circle over the abyss+glow vector background layer)
  - app/src/main/res/mipmap-*/ic_launcher.webp / ic_launcher_round.webp
      (legacy square = FULL-BLEED raw PPQ plate; round = circle mask of it)
  - app/src/main/res/drawable-nodpi/octo_mascot.png
      (256px FULL-BLEED raw PPQ plate — exactly the pre-Phase-17 mascot bytes
       the user liked; used by stats header + dashboard title bar)
Adaptive icon XMLs and drawable/ic_launcher_background.xml are untouched.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
RAW = ROOT / "docs" / "art" / "octo_head_raw.png"
CUTOUT = ROOT / "docs" / "art" / "octo_head_cutout.png"

# 108dp adaptive canvas at 4x for crispness = 432 px
FG_SIZE = 432
# mipmap launcher sizes per density
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# octo_mascot.png: largest in-app usage is 72dp @ ~3.5x → 256px is crisp.
MASCOT_SIZE = 256

# ── Background-removal tuning (measured on octo_head_raw.png) ──
# BG is near-uniform navy (13,34,65) ±3 per channel; character outline starts
# at color distance ≈20 from BG. 14 keeps the outline, eats the flat BG.
BG = np.array([13.0, 34.0, 65.0])
BG_TOL = 14.0
# Soft-edge band: pixels adjacent to removed BG get alpha ramped by color
# distance between these thresholds (feathers the anti-aliased rim).
EDGE_LO, EDGE_HI = 6.0, 30.0
EDGE_BAND_PX = 2
KEEP_FG_MIN_SIZE = 5000   # px — figure parts ≫ this, glow dots ≪ this
KEEP_FG_MIN_DIST = 100.0  # color distance — figure ≫ this, glow ≤ 50
POCKET_ROW_FRAC = 0.52    # holes centered below this row fraction are sea
POCKET_MAX_DIST = 40.0    # sea-like brightness ceiling for pocket pixels
POCKET_MIN_SIZE = 150     # px — real gaps are ≥ ~200px; detail cracks are not
CREVICE_MAX_DIST = 24.0   # crevice shadow ≈16; tentacle outlines ≈28 → safe
DOME_H_FRAC = 0.581       # teal-dome bottom row / cutout height (measured)
TENTACLE_ALPHA = 0.40     # tentacles over the body keep 40% opacity


def load_raw() -> Image.Image:
    if not RAW.exists():
        sys.exit(f"missing {RAW} — PPQ head art is the source of truth")
    return Image.open(RAW).convert("RGB")


def remove_background(rgb: Image.Image) -> Image.Image:
    """Cut the figure out of the flat navy sea, feathered rim.

    The dark eye pupils/brows are nearly the SAME color as the background
    (dist ≈11), so pure chroma keying punches holes in the face. Strategy:
    silhouette-first, then classify interior holes by geometry.

    1. seed fg = components of (~flat-sea) that are LARGE and HIGH-CONTRAST —
       the figure's two components (dome ≈342k px, tentacles ≈282k px, max
       color distance >290) beat bokeh glow dots (≤3.7k px, ≤50);
    2. binary_fill_holes → solid silhouette (pupils/brows come back, enclosed
       sea pockets between curled tentacles get filled too);
    3. classify each filled hole: sea pockets sit in the LOWER half, are big
       and sea-colored → punched back out; pupils/brows sit in the UPPER half
       → kept opaque;
    4. 2px feather band with smoothstep alpha from color distance.
    """
    arr = np.asarray(rgb).astype(np.float64)
    dist = np.sqrt(((arr - BG) ** 2).sum(axis=2))
    bg_mask = dist < BG_TOL  # flat sea + pupils + brows (similar colors!)

    fg_seed = ~bg_mask
    flabels, fn = ndimage.label(fg_seed)
    if fn > 1:
        sizes = ndimage.sum(fg_seed, flabels, index=range(1, fn + 1))
        maxdists = ndimage.maximum(dist, flabels, index=range(1, fn + 1))
        keep = (sizes >= KEEP_FG_MIN_SIZE) & (maxdists >= KEEP_FG_MIN_DIST)
        fg_seed = np.concatenate([[False], keep])[flabels]  # 0 = BG pixels

    solid = ndimage.binary_fill_holes(fg_seed)
    holes = solid & ~fg_seed
    hlabels, hn = ndimage.label(holes)
    removed = np.zeros_like(bg_mask)
    if hn:
        hcents = ndimage.center_of_mass(holes, hlabels, index=range(1, hn + 1))
        hsizes = ndimage.sum(holes, hlabels, index=range(1, hn + 1))
        hmeans = ndimage.mean(dist, hlabels, index=range(1, hn + 1))
        half_h = dist.shape[0] * POCKET_ROW_FRAC
        for j in range(1, hn + 1):
            in_sea_zone = hcents[j - 1][0] >= half_h
            is_sea_colored = hmeans[j - 1] < POCKET_MAX_DIST
            if in_sea_zone and is_sea_colored and hsizes[j - 1] >= POCKET_MIN_SIZE:
                removed |= hlabels == j

    # Shadow crevices between the central tentacles connect to the figure
    # through its dark outlines, so fill_holes never sees them as holes.
    # They read as sea (dist ≲24, brighter than flat BG) and sit below the
    # face — remove directly. Face features (pupils/brows, rows < 0.52·H) are
    # excluded by the same row gate; tentacle outlines are dist ≈28 → survive.
    crevice = solid & (dist < CREVICE_MAX_DIST)
    crevice[: int(dist.shape[0] * POCKET_ROW_FRAC), :] = False
    removed |= crevice

    opaque = solid & ~removed
    alpha = np.where(opaque, 255.0, 0.0)
    # Feather: band = within EDGE_BAND_PX of the removed region.
    cut = bg_mask | removed
    near = ndimage.binary_dilation(cut, iterations=EDGE_BAND_PX) & ~cut & opaque
    ramp = np.clip((dist - EDGE_LO) / (EDGE_HI - EDGE_LO), 0.0, 1.0)
    alpha[near] = 255.0 * ramp[near] ** 1.5

    out = np.dstack([arr, alpha]).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def cutout() -> Image.Image:
    """Transparent head-bust cutout, cropped to content + 8px margin."""
    if CUTOUT.exists():
        return Image.open(CUTOUT).convert("RGBA")
    img = remove_background(load_raw())
    bbox = img.split()[3].getbbox()
    pad = 8
    box = (max(0, bbox[0] - pad), max(0, bbox[1] - pad),
           min(img.width, bbox[2] + pad), min(img.height, bbox[3] + pad))
    img = img.crop(box)
    img.save(CUTOUT)
    print(f"wrote {CUTOUT} ({img.width}x{img.height}, transparent)")
    return img


def make_on_body_head(head: Image.Image) -> Image.Image:
    """On-body variant: tentacles go semi-transparent so the vector
    neck/shoulders show through where they drape over the torso.

    Only the in-app octo_head_ppq.png gets this treatment — the launcher
    icon/mascot assets stay fully opaque. The dome itself (above the dome's
    bottom edge) is untouched; below it, alpha ramps 255 → 100 with a short
    feather so there is no hard seam across the tentacle roots.
    """
    img = head.copy()
    a = np.asarray(img).astype(np.float64)
    alpha = a[..., 3]
    # Dome bottom from the teal-pixel row scan (see head placement constants —
    # dome occupies the top ~58% of the cutout height).
    dome_bottom = int(alpha.shape[0] * DOME_H_FRAC)
    feather = 60  # px of gradual onset right below the dome
    rows = np.arange(alpha.shape[0], dtype=np.float64)
    # 0 inside the dome zone → 1 past the feather band
    ramp = np.clip((rows - dome_bottom) / feather, 0.0, 1.0)
    scale = 1.0 - ramp * (1.0 - TENTACLE_ALPHA)
    a[..., 3] = alpha * scale[:, None]
    return Image.fromarray(a.astype(np.uint8), "RGBA")


def _fit(img: Image.Image, target_w: int, target_h: int) -> Image.Image:
    s = min(target_w / img.width, target_h / img.height)
    return img.resize((max(1, round(img.width * s)),
                       max(1, round(img.height * s))), Image.LANCZOS)


def make_foreground(head: Image.Image) -> Image.Image:
    """Adaptive foreground: cutout's bounding circle inside the 66/108 safe
    circle, transparent elsewhere so any mask keeps the abyss bg visible."""
    fg = Image.new("RGBA", (FG_SIZE, FG_SIZE), (0, 0, 0, 0))
    safe_d = FG_SIZE * (66.0 / 108.0)
    fitted = _fit(head, int(safe_d), int(safe_d))
    fg.alpha_composite(fitted, ((FG_SIZE - fitted.width) // 2,
                                (FG_SIZE - fitted.height) // 2))
    return fg


def full_bleed(size: int) -> Image.Image:
    """The raw PPQ plate itself (its own navy BG + bokeh) — the pre-Phase-17
    look the user explicitly liked."""
    return load_raw().resize((size, size), Image.LANCZOS)


def circle_mask(img: Image.Image) -> Image.Image:
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse((0, 0, img.size[0] - 1, img.size[1] - 1), fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out


def main() -> None:
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)

    head = cutout()

    # In-app head used by OctopusBodyView: tentacles semi-transparent so the
    # vector neck/shoulders read through the drape (user feedback, Phase 18).
    on_body = make_on_body_head(head)
    on_body.save(nodpi / "octo_head_ppq.png")
    print(f"wrote {nodpi / 'octo_head_ppq.png'} ({on_body.width}x{on_body.height}, "
          f"transparent, tentacles @ {int(TENTACLE_ALPHA * 100)}% over the body)")

    fg = make_foreground(head)
    fg.save(nodpi / "ic_launcher_foreground.png")
    print(f"wrote {nodpi / 'ic_launcher_foreground.png'} ({FG_SIZE}px, "
          f"PPQ head in 66dp safe circle)")

    mascot = full_bleed(MASCOT_SIZE)
    mascot.convert("RGB").save(nodpi / "octo_mascot.png")
    print(f"wrote {nodpi / 'octo_mascot.png'} ({MASCOT_SIZE}px, raw PPQ plate)")

    square = full_bleed(FG_SIZE)
    round_img = circle_mask(square)
    for dpi, size in DENSITIES.items():
        folder = RES / f"mipmap-{dpi}"
        folder.mkdir(exist_ok=True)
        square.resize((size, size), Image.LANCZOS).convert("RGB").save(
            folder / "ic_launcher.webp")
        round_img.resize((size, size), Image.LANCZOS).save(
            folder / "ic_launcher_round.webp")
    print("wrote mipmaps:", ", ".join(sorted(DENSITIES)))


if __name__ == "__main__":
    main()
