#!/usr/bin/env python3
"""Phase 17 — head-only launcher icon derived from the app's own vector man.

Continuity contract: this script imports gen_body_geometry — the SINGLE source
of truth that also emits OctopusBodyGeometry.kt — and renders ONLY the head:
  - the octopus dome (the "neck" region path, exact same numbers the app parses),
  - the face (dot eyes, pupils, highlights, blush, smile — same coordinates),
  - the 8 decorative head tentacles + their suction cups (exact same paths).
Colors are the app's idle palette from OctopusBodyView.kt. Only icon-specific
legibility tweaks are applied (slightly bolder borders / cups / smile); the
geometry is identical to the on-screen figure, so launcher and dashboard show
the same character.

Outputs (all inside the Octo repo):
  docs/art/octo_head_vector.png
      1024px transparent master tile (content fitted around its own center).
  docs/art/screenshots/icon_continuity_icon_circle.png
      circle-masked adaptive-icon preview (fg over the abyss background).
  docs/art/screenshots/icon_continuity_side_by_side.png
      on-screen man's head crop vs the icon head — continuity check.

scripts/make_icon_art.py consumes the master for the adaptive foreground PNG,
the legacy density webps and the in-app octo_mascot.png.
"""
from __future__ import annotations

import math
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_body_geometry as G  # noqa: E402  (same module that emits the Kotlin)

from PIL import Image, ImageDraw, ImageFont  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs" / "art"

# ── Supersampling: render the 1000x1500 geometry canvas at 4x ──
SS = 4

# ── Idle-state palette, copied from OctopusBodyView.kt ──
ABYSS = (8, 28, 34)      # #081C22 background plate
DOME = (67, 88, 95)      # RegionIdle #43585F
TENT = (50, 68, 76)      # TentacleDeco #32444C
FOAM = (216, 232, 230)   # #D8E8E6 borders / eye whites / cups
INK = (13, 34, 40)       # AbyssInk #0D2228 pupils + smile
BLUSH = (156, 112, 200)  # #9C70C8
SHEEN = (130, 217, 205)  # #82D9CD dome sheen
WHITE = (255, 255, 255)

# ── Icon-only legibility tweaks (screen values in comments) ──
BORDER_U = 8.0     # screen 2.5 units — bolder outline survives 48dp
SMILE_U = 8.0      # screen 5 units
SHEEN_U = 12.0     # screen 9 units
BORDER_A = 0.40    # screen 0.20 (BorderIdle) — stronger on the launcher
SHEEN_A = 0.70     # matches screen
BLUSH_A = 0.65     # matches screen
CUP_A = 0.95       # screen 0.90
CUP_BOOST = 1.3    # cups read clearly at small sizes
BLUSH_BOOST = 1.15

# Adaptive-icon safe area: content inside the central 66dp circle of 108dp.
SAFE_RADIUS_FRAC = (66.0 / 108.0) / 2.0
# Content radius fraction inside the 1024px master tile (see content_tile).
MASTER_SIZE = 1024
MASTER_R_FRAC = 480.0 / 1024.0


# ─────────────────────────────────────────────────────────────────────────────
# Head content (exact geometry reuse)
# ─────────────────────────────────────────────────────────────────────────────

def head_cups() -> list[tuple[float, float, float]]:
    """Suction cups of the 8 head tentacles only (left + mirrored right).

    Same ts/radii/side as gen_body_geometry's SUCTION_L head section, computed
    from the very same centerlines — body cups (fingers, calves) are excluded.
    """
    cups: list[tuple[float, float, float]] = []
    for pts in G._head_cl_lines:
        cups += G.cups_along(pts, [0.30, 0.50, 0.68], side=-1, r=[7, 6, 5])
    return cups + [(G.W - x, y, r) for (x, y, r) in cups]


def content_extents() -> tuple[tuple[float, float, float, float],
                               tuple[float, float], float]:
    """Bbox, center and max radius of all head content (geometry units).

    Suction cups are expanded to their drawn radius (CUP_BOOST) + half the
    border stroke, so the fitted tile truly contains every painted pixel.
    """
    grow = BORDER_U / 2
    pts: list[tuple[float, float]] = []
    for cmds in G.HEAD_TENTACLES:
        for sub in G.sample_path(cmds, steps=24):
            pts += sub
    for sub in G.sample_path(G.NECK_L, steps=24):
        pts += sub
    for x, y, rr in head_cups():
        er = rr * CUP_BOOST + grow
        pts += [(x - er, y - er), (x + er, y - er),
                (x - er, y + er), (x + er, y + er)]
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    r = max(math.hypot(p[0] - cx, p[1] - cy) for p in pts)
    return (min(xs), min(ys), max(xs), max(ys)), (cx, cy), r


# ─────────────────────────────────────────────────────────────────────────────
# Renderer (PIL, supersampled)
# ─────────────────────────────────────────────────────────────────────────────

def _fill(dr: ImageDraw.ImageDraw, cmds, color, s: int) -> None:
    for sub in G.sample_path(cmds, steps=24):
        pts = [(x * s, y * s) for x, y in sub]
        if len(pts) >= 3:
            dr.polygon(pts, fill=color)


def _stroke(dr: ImageDraw.ImageDraw, cmds, color, width_u: float, s: int,
            alpha: float = 1.0) -> None:
    col = color + (int(round(255 * alpha)),)
    w = max(1, int(round(width_u * s)))
    for sub in G.sample_path(cmds, steps=24):
        pts = [(x * s, y * s) for x, y in sub]
        if len(pts) >= 2:
            dr.line(pts, fill=col, width=w, joint="curve")


def render_head_canvas() -> Image.Image:
    """Head-only render on transparent, supersampled geometry canvas.

    Draw order mirrors OctopusBodyView: tentacles BEHIND the dome, face and
    cups on top.
    """
    s = SS
    img = Image.new("RGBA", (int(G.W * s), int(G.H * s)), (0, 0, 0, 0))
    dr = ImageDraw.Draw(img, "RGBA")

    for cmds in G.HEAD_TENTACLES:                      # behind the dome
        _fill(dr, cmds, TENT, s)
    for cmds in G.HEAD_TENTACLES:
        _stroke(dr, cmds, FOAM, BORDER_U, s, alpha=BORDER_A)

    _fill(dr, G.NECK_L, DOME, s)                       # octopus dome = head
    _stroke(dr, G.NECK_L, FOAM, BORDER_U, s, alpha=BORDER_A)

    def circle(cx, cy, r, fill):
        dr.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s],
                   fill=fill)

    for x, y, r in G.BLUSH:                            # face — same coords
        circle(x, y, r * BLUSH_BOOST, BLUSH + (int(255 * BLUSH_A),))
    for x, y, r in G.EYES:
        circle(x, y, r, FOAM)
    for x, y, r in G.PUPILS:
        circle(x, y, r, INK)
    for x, y, r in G.HIGHLIGHTS:
        circle(x, y, r, WHITE)
    _stroke(dr, G.SMILE, INK, SMILE_U, s)
    _stroke(dr, G.DOME_SHEEN, SHEEN, SHEEN_U, s, alpha=SHEEN_A)
    for x, y, r in head_cups():
        circle(x, y, r * CUP_BOOST, FOAM + (int(255 * CUP_A),))
    return img


def content_tile(target_radius_px: float) -> Image.Image:
    """Head cropped tight to content, resized so max content radius ==
    target_radius_px (the returned tile is therefore ~2*target wide/tall)."""
    img = render_head_canvas()
    bbox, _, r = content_extents()
    pad = 14  # geometry units of breathing room for anti-aliased borders
    box = (int((bbox[0] - pad) * SS), int((bbox[1] - pad) * SS),
           int((bbox[2] + pad) * SS), int((bbox[3] + pad) * SS))
    img = img.crop(box)
    # img is supersampled: content radius r is in geometry units → r * SS px.
    scale = target_radius_px / (r * SS)
    out = img.resize((max(1, int(img.width * scale)),
                      max(1, int(img.height * scale))), Image.LANCZOS)
    return out


def master() -> Image.Image:
    """1024px transparent master tile, content centered."""
    tile = content_tile(MASTER_SIZE * MASTER_R_FRAC)
    img = Image.new("RGBA", (MASTER_SIZE, MASTER_SIZE), (0, 0, 0, 0))
    img.alpha_composite(tile, ((MASTER_SIZE - tile.width) // 2,
                               (MASTER_SIZE - tile.height) // 2))
    return img


# ─────────────────────────────────────────────────────────────────────────────
# Verification previews
# ─────────────────────────────────────────────────────────────────────────────

def icon_preview(fg: Image.Image, mask: str = "circle",
                 safe_ring: bool = False) -> Image.Image:
    """Composite fg over a faithful echo of ic_launcher_background.xml and
    apply a realistic launcher mask (visible viewport = central 72dp of the
    108dp adaptive canvas) — a stand-in for the launcher rendering."""
    size = fg.size[0]
    bg = Image.new("RGBA", (size, size), ABYSS + (255,))
    dr = ImageDraw.Draw(bg, "RGBA")
    k = size / 108.0
    dr.ellipse([(54 - 34) * k, (20 - 34) * k, (54 + 34) * k, (20 + 34) * k],
               fill=(18, 58, 66, 255))
    dr.ellipse([(54 - 28) * k, (26 - 28) * k, (54 + 28) * k, (26 + 28) * k],
               fill=(11, 37, 45, 255))
    bg.alpha_composite(fg)
    if safe_ring:
        # 66dp adaptive-icon safe circle — content must stay inside it.
        r = 33 * k
        dr.ellipse([(54 * k - r, 54 * k - r), (54 * k + r, 54 * k + r)],
                   outline=(220, 80, 80, 255), width=max(1, int(0.7 * k)))
    m = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(m)
    if mask == "circle":
        r = 36 * k                       # Pixel-style circle: 72dp diameter
        md.ellipse([(54 * k - r, 54 * k - r), (54 * k + r, 54 * k + r)],
                   fill=255)
    else:                                # rounded-square launcher mask
        inset = (54 - 36) * k
        md.rounded_rectangle((inset, inset, size - inset, size - inset),
                             radius=14 * k, fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(bg, (0, 0), m)
    return out


def on_screen_head_crop() -> Image.Image:
    """Head crop of the on-screen man (same renderer that produced the app's
    geometry preview) — the reference side of the continuity check."""
    tmp = Path(tempfile.mkstemp(suffix=".png")[1])
    try:
        G.render_preview(tmp, stretched=set())
        full = Image.open(tmp).convert("RGB")
    finally:
        tmp.unlink(missing_ok=True)
    sc = 0.8  # preview scale used by gen_body_geometry.render_preview
    return full.crop((int(170 * sc), int(40 * sc), int(830 * sc), int(720 * sc)))


def _label(text: str, width: int, height: int = 32) -> Image.Image:
    font = None
    for cand in ("DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/"
                 "DejaVuSans-Bold.ttf"):
        try:
            font = ImageFont.truetype(cand, 22)
            break
        except OSError:
            continue
    if font is None:
        font = ImageFont.load_default()
    img = Image.new("RGB", (width, height), ABYSS)
    dr = ImageDraw.Draw(img)
    tw = dr.textlength(text, font=font)
    dr.text((max(4, (width - tw) // 2), 3), text, fill=FOAM, font=font)
    return img


def side_by_side(out: Path) -> None:
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    tile = content_tile(432 * SAFE_RADIUS_FRAC)
    fg.alpha_composite(tile, ((432 - tile.width) // 2, (432 - tile.height) // 2))
    icon = icon_preview(fg).convert("RGB").resize((560, 560), Image.LANCZOS)

    man = on_screen_head_crop()
    w = int(man.width * 520 / man.height)
    man = man.resize((min(w, 560), 520), Image.LANCZOS)

    gap, lbl_h = 24, 36
    canvas = Image.new("RGB", (man.width + icon.width + gap * 3,
                               520 + lbl_h * 2 + gap), ABYSS)
    canvas.paste(_label("on-screen man (OctopusBodyView idle)",
                        man.width), (gap, gap))
    canvas.paste(man, (gap, gap + lbl_h))
    x2 = gap * 2 + man.width
    canvas.paste(_label("launcher icon — same geometry + palette",
                        icon.width), (x2, gap))
    canvas.paste(icon, (x2, gap + lbl_h))
    canvas.save(out)
    print(f"side-by-side -> {out}")


def mask_matrix(out: Path) -> None:
    """The same foreground under common launcher masks + the 66dp safe ring."""
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    tile = content_tile(432 * SAFE_RADIUS_FRAC)
    fg.alpha_composite(tile, ((432 - tile.width) // 2, (432 - tile.height) // 2))
    cell, gap = 420, 24
    canvas = Image.new("RGB", (cell * 2 + gap * 3, cell + gap * 2 + 20), ABYSS)
    for i, (shape, ring) in enumerate((("circle", True), ("squircle", True))):
        im = icon_preview(fg, mask=shape, safe_ring=ring)
        canvas.paste(im.convert("RGB"), (gap + i * (cell + gap), gap + 10),
                      im.split()[3])
    canvas.save(out)
    print(f"mask matrix -> {out}")


# ─────────────────────────────────────────────────────────────────────────────

def main() -> None:
    (DOCS / "screenshots").mkdir(parents=True, exist_ok=True)
    bbox, center, r = content_extents()
    print(f"head content bbox x[{bbox[0]:.0f},{bbox[2]:.0f}] "
          f"y[{bbox[1]:.0f},{bbox[3]:.0f}]  center=({center[0]:.0f},"
          f"{center[1]:.0f})  R={r:.0f}u")
    m = master()
    m.save(DOCS / "octo_head_vector.png")
    print(f"master  -> {DOCS / 'octo_head_vector.png'} ({m.size[0]}px, "
          f"content R={MASTER_SIZE * MASTER_R_FRAC:.0f}px)")
    shots = DOCS / "screenshots"
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    tile = content_tile(432 * SAFE_RADIUS_FRAC)
    fg.alpha_composite(tile, ((432 - tile.width) // 2, (432 - tile.height) // 2))
    icon_preview(fg).convert("RGB").save(shots / "icon_continuity_icon_circle.png")
    print(f"preview -> {shots / 'icon_continuity_icon_circle.png'}")
    side_by_side(shots / "icon_continuity_side_by_side.png")
    mask_matrix(shots / "icon_continuity_mask_matrix.png")


if __name__ == "__main__":
    main()
