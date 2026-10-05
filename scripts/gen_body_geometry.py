#!/usr/bin/env python3
"""Octo body-map geometry — single source of truth for the 16 BodyPart regions.

Phase 10 anatomy: like the icon art, the man's head IS an octopus — the dome
carries the face and EIGHT decorative tentacles drape around it like hair,
flowing down beside the shoulders. Every limb (arms, legs) is a tapered
tentacle that ends in a cluster of tiny curling tentacle-fingers with
suction-cup dots. Head tentacles + finger clusters are DECORATIVE (never
regions): the 16 tappable regions keep the exact Phase-6 taxonomy.

Emits:
  1. app/src/main/java/com/example/octo/ui/body/OctopusBodyGeometry.kt
     (SVG path strings + decoration data, parsed at runtime with
     androidx.core.graphics.PathParser — identical numbers to the preview)
  2. docs/art/body_map_preview.png (visual check before committing to the UI)

Coordinate space: 1000 (w) x 1500 (h), figure centered on x=500.
All regions are defined for the LEFT half (or center-symmetric) and mirrored
across x=500. Commands: M/C/L/Z only (SVG path syntax subset).
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path

W, H = 1000.0, 1500.0

# ─────────────────────────────────────────────────────────────────────────────
# Command helpers
# ─────────────────────────────────────────────────────────────────────────────

def M(x, y): return ("M", x, y)
def C(x1, y1, x2, y2, x, y): return ("C", x1, y1, x2, y2, x, y)
def L(x, y): return ("L", x, y)
Z = ("Z",)


def mirror(cmds):
    out = []
    for c in cmds:
        if c[0] == "Z":
            out.append(Z)
        else:
            # c[1:] alternates x, y, x, y … → even indices are X, odd are Y.
            out.append((c[0], *[W - v if i % 2 == 0 else v for i, v in enumerate(c[1:])]))
    return out


def to_svg(cmds):
    parts = []
    for c in cmds:
        if c[0] == "Z":
            parts.append("Z")
        else:
            parts.append(c[0] + " " + " ".join(f"{v:.1f}" for v in c[1:]))
    return " ".join(parts)


def combine(*cmd_lists):
    out = []
    for cl in cmd_lists:
        out.extend(cl)
    return out


# ─────────────────────────────────────────────────────────────────────────────
# Tentacle builder: sample a cubic centerline, append a tip curl, emit a
# tapered filled polygon (M L … Z — PathParser/Region-friendly).
# ─────────────────────────────────────────────────────────────────────────────

def _cubic(p0, p1, p2, p3, n):
    pts = []
    for i in range(1, n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
        y = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def sample_centerline(cmds, steps=30):
    """Flatten M/C/L command list to a polyline (single subpath expected)."""
    pts, pos = [], (0.0, 0.0)
    for c in cmds:
        if c[0] == "M":
            if pts:
                pts = []  # keep last subpath only
            pos = c[1:3]
            pts.append(pos)
        elif c[0] == "L":
            pos = c[1:3]
            pts.append(pos)
        elif c[0] == "C":
            pts.extend(_cubic(pos, c[1:3], c[3:5], c[5:7], steps))
            pos = c[5:7]
    return pts


def spiral_pts(p_last, p_prev, r0, sweep_deg, dirsign, steps=20):
    """Curl continuation: shrinking arc on one side of the travel direction."""
    tx, ty = p_last[0] - p_prev[0], p_last[1] - p_prev[1]
    m = math.hypot(tx, ty) or 1.0
    tx, ty = tx / m, ty / m
    nx, ny = -ty * dirsign, tx * dirsign
    cx, cy = p_last[0] + nx * r0, p_last[1] + ny * r0
    a0 = math.atan2(p_last[1] - cy, p_last[0] - cx)
    sweep = math.radians(sweep_deg)
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        ang = a0 + dirsign * sweep * t
        r = r0 * (1 - 0.72 * t)
        out.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
    return out


def tapered_polygon(pts, w0, w1):
    """Offset polygon around a centerline, width w0 → w1."""
    n = len(pts)
    left, right = [], []
    for i, p in enumerate(pts):
        if i == 0:
            a, b = pts[1], pts[0]
        elif i == n - 1:
            a, b = pts[n - 2], pts[n - 1]
        else:
            a, b = pts[i - 1], pts[i + 1]
        tx, ty = b[0] - a[0], b[1] - a[1]
        m = math.hypot(tx, ty) or 1.0
        nx, ny = -ty / m, tx / m
        t = i / (n - 1)
        w = w0 + (w1 - w0) * t
        left.append((p[0] + nx * w / 2, p[1] + ny * w / 2))
        right.append((p[0] - nx * w / 2, p[1] - ny * w / 2))
    poly = left + right[::-1] + [left[0]]
    return [M(*poly[0])] + [L(x, y) for x, y in poly[1:]] + [Z]


def tentacle(cmds, w0, w1, curl=None, steps=30):
    """Full decorative tentacle: centerline + optional tip curl + tapered fill.
    curl = (radius, sweep_deg, dirsign). Returns (cmds, centerline_pts)."""
    pts = sample_centerline(cmds, steps)
    if curl:
        r0, sweep, dirsign = curl
        pts = pts + spiral_pts(pts[-1], pts[-2], r0, sweep, dirsign)
    return tapered_polygon(pts, w0, w1), pts


def finger(base, ang_deg, length, bow=22, w0=15, w1=3.5, curl_dir=-1, curl_r=15):
    """A tiny tentacle-finger from `base` at `ang_deg` (screen coords: 90°=down),
    gently bowed, ending in a tight curl."""
    a = math.radians(ang_deg)
    dx, dy = math.cos(a), math.sin(a)
    p1 = (base[0] + dx * length * 0.33 - dy * bow, base[1] + dy * length * 0.33 + dx * bow)
    p2 = (base[0] + dx * length * 0.70 + dy * bow * 0.6, base[1] + dy * length * 0.70 - dx * bow * 0.6)
    p3 = (base[0] + dx * length, base[1] + dy * length)
    cl = [M(*base), C(*p1, *p2, *p3)]
    return tentacle(cl, w0, w1, curl=(curl_r, 250, curl_dir), steps=26)


# ─────────────────────────────────────────────────────────────────────────────
# Region geometry (left half / center-symmetric)
# ─────────────────────────────────────────────────────────────────────────────

# NECK = the whole octopus head (dome + neck column) — §5 "Octopus head / mantle joint".
# The 8 head tentacles are DECORATIVE and emerge from behind this dome (see below).
NECK_L = [
    M(500, 64),
    C(610, 64, 700, 148, 704, 258),
    C(707, 342, 668, 410, 606, 442),
    C(592, 452, 584, 466, 582, 482),
    L(582, 502),
    L(418, 502),
    L(418, 482),
    C(416, 466, 408, 452, 394, 442),
    C(332, 410, 293, 342, 296, 258),
    C(300, 148, 390, 64, 500, 64),
    Z,
]

# SHOULDERS — left deltoid cap (mirrored); sits high, bridging arm zone & torso top
SHOULDER_L = [
    M(404, 498),
    C(376, 476, 340, 470, 310, 480),
    C(282, 490, 262, 512, 260, 540),
    C(258, 562, 270, 578, 292, 584),
    C(314, 590, 338, 584, 354, 570),
    C(362, 544, 372, 522, 388, 508),
    C(394, 502, 398, 500, 404, 498),
    Z,
]

# UPPER_BACK — left crescent peeking outside the front torso (mirrored)
UPPER_BACK_L = [
    M(404, 592),
    C(378, 598, 356, 616, 346, 644),
    C(340, 666, 340, 688, 346, 706),
    L(378, 712),
    C(373, 684, 374, 650, 382, 624),
    C(388, 610, 396, 600, 404, 592),
    Z,
]

# LATS — left side panel under the armpit (mirrored); slight outward flare
LATS_L = [
    M(380, 718),
    C(354, 724, 341, 746, 343, 772),
    C(345, 794, 354, 810, 370, 820),
    L(398, 814),
    C(389, 792, 384, 770, 384, 746),
    C(384, 735, 382, 726, 380, 718),
    Z,
]

# CHEST — center pec plate
CHEST = [
    M(428, 516),
    C(452, 504, 478, 502, 500, 512),
    C(522, 502, 548, 504, 572, 516),
    C(586, 552, 584, 598, 568, 632),
    C(552, 662, 524, 676, 500, 668),
    C(476, 676, 448, 662, 432, 632),
    C(416, 598, 414, 552, 428, 516),
    Z,
]

# OBLIQUES — left strip flanking the core (mirrored)
OBLIQUES_L = [
    M(392, 690),
    C(384, 738, 384, 790, 396, 840),
    L(432, 846),
    C(424, 794, 424, 740, 432, 694),
    C(418, 690, 404, 690, 392, 690),
    Z,
]

# CORE — center abs plate
CORE = [
    M(436, 692),
    C(462, 684, 480, 684, 500, 690),
    C(520, 684, 538, 684, 564, 692),
    C(568, 742, 568, 792, 560, 842),
    C(540, 856, 520, 860, 500, 858),
    C(480, 860, 460, 856, 440, 842),
    C(432, 792, 432, 742, 436, 692),
    Z,
]

# LOWER_BACK — left crescent below lats, tapering toward the hip (mirrored)
LOWER_BACK_L = [
    M(374, 828),
    C(356, 838, 348, 858, 350, 882),
    C(352, 904, 360, 920, 372, 930),
    L(396, 922),
    C(388, 904, 384, 884, 384, 862),
    C(384, 850, 380, 838, 374, 828),
    Z,
]

# HIPS_GLUTES — center pelvis plate
HIPS = [
    M(398, 866),
    C(436, 880, 464, 884, 500, 882),
    C(536, 884, 564, 880, 602, 866),
    C(624, 888, 634, 916, 632, 946),
    C(630, 978, 612, 1000, 584, 1008),
    C(556, 1016, 526, 1012, 500, 1000),
    C(474, 1012, 444, 1016, 416, 1008),
    C(388, 1000, 370, 978, 368, 946),
    C(366, 916, 376, 888, 398, 866),
    Z,
]

# GROIN_ADDUCTORS — center V plate (drawn on top of hips)
GROIN = [
    M(452, 948),
    C(468, 970, 484, 984, 500, 994),
    C(516, 984, 532, 970, 548, 948),
    C(540, 990, 522, 1020, 500, 1030),
    C(478, 1020, 460, 990, 452, 948),
    Z,
]

# ── Limbs: tapered tentacle columns (region-split along the centerline) ──

# Left arm: shoulder (384,546) → elbow (299,728) → wrist (264,894).
BICEPS_L, _ = tentacle(
    [M(384, 546), C(356, 584, 326, 638, 308, 690), C(303, 708, 300, 718, 299, 728)],
    w0=64, w1=46,
)
FOREARM_L, _ = tentacle(
    [M(298, 732), C(288, 770, 278, 812, 270, 856), C(267, 872, 265, 884, 264, 894)],
    w0=44, w1=24,
)
LEFT_WRIST = (264, 894)

# Left leg: hip (466,1008) → knee (450,1192) → ankle (450,1412).
QUADS_L, _ = tentacle(
    [M(466, 1008), C(458, 1070, 452, 1134, 450, 1192)],
    w0=76, w1=52,
)
HAMSTRINGS_L, _ = tentacle(
    [M(428, 1012), C(424, 1072, 422, 1132, 424, 1190)],
    w0=28, w1=20,
)
CALVES_L, _ = tentacle(
    [M(448, 1202), C(446, 1264, 446, 1326, 448, 1378)],
    w0=54, w1=30,
)
ANKLES_L, _ = tentacle(
    [M(449, 1384), C(450, 1392, 450, 1399, 450, 1406)],
    w0=28, w1=22,
)
LEFT_ANKLE = (450, 1408)


@dataclass
class RegionDef:
    part_id: str
    cmds: list  # full command list (already combined L+R when mirrored)
    draw_order: int


# Draw order = back → front. Hit-testing iterates REVERSED (front-most wins).
REGION_DEFS: list[RegionDef] = [
    RegionDef("upper_back", combine(UPPER_BACK_L, mirror(UPPER_BACK_L)), 0),
    RegionDef("lower_back", combine(LOWER_BACK_L, mirror(LOWER_BACK_L)), 1),
    RegionDef("lats", combine(LATS_L, mirror(LATS_L)), 2),
    RegionDef("hamstrings", combine(HAMSTRINGS_L, mirror(HAMSTRINGS_L)), 3),
    RegionDef("quads", combine(QUADS_L, mirror(QUADS_L)), 4),
    RegionDef("calves", combine(CALVES_L, mirror(CALVES_L)), 5),
    RegionDef("ankles_feet", combine(ANKLES_L, mirror(ANKLES_L)), 6),
    RegionDef("shoulders", combine(SHOULDER_L, mirror(SHOULDER_L)), 7),
    RegionDef("obliques", combine(OBLIQUES_L, mirror(OBLIQUES_L)), 8),
    RegionDef("core", CORE, 9),
    RegionDef("chest", CHEST, 10),
    RegionDef("hips_glutes", HIPS, 11),
    RegionDef("groin_adductors", GROIN, 12),
    RegionDef("biceps_triceps", combine(BICEPS_L, mirror(BICEPS_L)), 13),
    RegionDef("wrists_forearms", combine(FOREARM_L, mirror(FOREARM_L)), 14),
    RegionDef("neck", NECK_L, 15),  # head drawn last (front-most)
]

# ─────────────────────────────────────────────────────────────────────────────
# BACK VIEW (Phase 20) — same silhouette, seen from behind.
#
# Side map (NOTES.md §24):
#   FRONT only : chest, core, groin_adductors, quads
#   BACK only  : (none — every back part also exists on the front figure)
#   BOTH       : neck, shoulders (rear delts), upper_back, lower_back, lats,
#                obliques (flanks), hips_glutes (glutes), hamstrings (leading
#                thigh column from behind), calves, ankles_feet (heels),
#                biceps_triceps (triceps side), wrists_forearms.
# Front-only parts are simply ABSENT from the back figure — no dimmed ghost.
# ─────────────────────────────────────────────────────────────────────────────

# Upper back from behind: big trapezius/shoulder-blade plate (chest slot)
UPPER_BACK_BK = [
    M(418, 514),
    C(448, 500, 470, 496, 500, 506),
    C(530, 496, 552, 500, 582, 514),
    C(600, 552, 604, 610, 592, 662),
    C(578, 700, 544, 714, 500, 708),
    C(456, 714, 422, 700, 408, 662),
    C(396, 610, 400, 552, 418, 514),
    Z,
]

# Lower back from behind: center plate tapering to the hips (core slot)
LOWER_BACK_BK = [
    M(432, 716),
    C(462, 708, 538, 708, 568, 716),
    C(576, 762, 576, 806, 566, 846),
    C(546, 862, 522, 866, 500, 862),
    C(478, 866, 454, 862, 434, 846),
    C(424, 806, 424, 762, 432, 716),
    Z,
]

# Lats from behind: the star of the back — wide panels armpit → waist
LATS_BK_L = [
    M(424, 702),
    C(384, 706, 350, 728, 340, 764),
    C(332, 796, 342, 826, 366, 842),
    C(390, 856, 414, 852, 430, 840),
    C(420, 800, 418, 754, 426, 712),
    Z,
]

# Obliques from behind: flank strips bridging lats → glutes
OBLIQUES_BK_L = [
    M(388, 846),
    C(382, 880, 384, 914, 396, 944),
    L(430, 938),
    C(422, 908, 420, 876, 426, 848),
    C(412, 843, 398, 843, 388, 846),
    Z,
]

# Glutes from behind: the pelvis silhouette dominates (hips plate reuse)
GLUTES_BK = HIPS

# Hamstrings from behind: the leading thigh column (quad-sized)
HAMSTRINGS_BK_L, _ = tentacle(
    [M(466, 1008), C(458, 1070, 452, 1134, 450, 1192)],
    w0=76, w1=52,
)

BACK_REGION_DEFS: list[RegionDef] = [
    RegionDef("hamstrings", combine(HAMSTRINGS_BK_L, mirror(HAMSTRINGS_BK_L)), 0),
    RegionDef("calves", combine(CALVES_L, mirror(CALVES_L)), 1),
    RegionDef("ankles_feet", combine(ANKLES_L, mirror(ANKLES_L)), 2),
    RegionDef("lats", combine(LATS_BK_L, mirror(LATS_BK_L)), 3),
    RegionDef("obliques", combine(OBLIQUES_BK_L, mirror(OBLIQUES_BK_L)), 4),
    RegionDef("lower_back", LOWER_BACK_BK, 5),
    RegionDef("upper_back", UPPER_BACK_BK, 6),
    RegionDef("hips_glutes", GLUTES_BK, 7),
    RegionDef("shoulders", combine(SHOULDER_L, mirror(SHOULDER_L)), 8),
    RegionDef("biceps_triceps", combine(BICEPS_L, mirror(BICEPS_L)), 9),
    RegionDef("wrists_forearms", combine(FOREARM_L, mirror(FOREARM_L)), 10),
    RegionDef("neck", NECK_L, 11),  # head drawn last (front-most)
]

# Back detail strokes: spine groove, shoulder-blade curves, glute split.
SPINE = combine(
    [M(500, 514), C(505, 590, 495, 680, 500, 718), C(502, 762, 498, 810, 500, 856)]
)
SHOULDER_BLADE_L = [M(444, 556), C(468, 572, 480, 600, 476, 634)]
GLUTE_SPLIT = combine([M(500, 878), C(500, 920, 500, 968, 500, 1008)])
BACK_MUSCLE_LINES = [SPINE, SHOULDER_BLADE_L, mirror(SHOULDER_BLADE_L), GLUTE_SPLIT]

# ─────────────────────────────────────────────────────────────────────────────
# Decorations
# ─────────────────────────────────────────────────────────────────────────────

# Face: eyes / pupils / highlights / blush (circles), smile (stroke path)
EYES = [(444, 262, 30), (556, 262, 30)]
PUPILS = [(448, 268, 15), (552, 268, 15)]
HIGHLIGHTS = [(440, 258, 6), (544, 258, 6)]
BLUSH = [(404, 320, 14), (596, 320, 14)]
SMILE = [M(474, 328), C(490, 342, 510, 342, 526, 328)]
# Mantle sheen: soft ellipse-ish arc on the upper-left of the dome
DOME_SHEEN = [M(372, 128), C(414, 96, 470, 88, 520, 100)]

# Muscle detail strokes
AB_LINES = [
    combine([M(500, 696), C(500, 744, 500, 800, 500, 852)]),
    combine([M(448, 750), C(468, 758, 532, 758, 552, 750)]),
    combine([M(448, 802), C(468, 810, 532, 810, 552, 802)]),
]
PEC_LINE = combine([M(500, 516), C(500, 560, 500, 616, 500, 662)])

# ── 8 head tentacles ("octopus hair"): 4 on the left, mirrored to the right.
# Drawn BEHIND the regions; bases hide under the dome, tips curl by the shoulders.
_HEAD_TENTACLES_L = [
    # (centerline cmds, w0, w1, curl radius) — curl toward the body (+x side)
    ([M(452, 88), C(400, 110, 346, 150, 322, 210), C(306, 252, 302, 300, 304, 350),
      C(306, 420, 312, 480, 322, 540)], 32, 8, 22),
    ([M(410, 78), C(348, 96, 286, 150, 262, 220), C(246, 272, 242, 330, 246, 390),
      C(250, 460, 258, 520, 268, 590)], 30, 7, 24),
    ([M(372, 96), C(300, 120, 236, 190, 216, 270), C(202, 330, 200, 394, 206, 454),
      C(212, 530, 222, 600, 236, 672)], 28, 7, 26),
    ([M(340, 130), C(280, 168, 234, 232, 222, 300), C(214, 352, 212, 404, 218, 452),
      C(224, 520, 234, 580, 248, 635)], 26, 7, 22),
]

HEAD_TENTACLES = []        # filled deco paths (behind regions)
_head_cl_lines = []        # centerlines, for suction-cup placement
for cl, w0, w1, cr in _HEAD_TENTACLES_L:
    cmds, pts = tentacle(cl, w0, w1, curl=(cr, 260, -1))
    HEAD_TENTACLES.append(cmds)
    HEAD_TENTACLES.append(mirror(cmds))
    _head_cl_lines.append(pts)

# ── Tentacle-finger clusters at the limb ends (3 fingers each hand/foot).
# Drawn IN FRONT of the regions (they extend past the wrist/ankle region tips).
FINGER_TENTACLES = []
_finger_cl_lines = []
for ang in (76, 102, 128):                     # left hand: fan downward
    cmds, pts = finger(LEFT_WRIST, ang, length=112, bow=20, curl_dir=-1)
    FINGER_TENTACLES.append(cmds)
    FINGER_TENTACLES.append(mirror(cmds))
    _finger_cl_lines.append(pts)
for ang in (60, 90, 120):                      # left foot: fan downward
    cmds, pts = finger(LEFT_ANKLE, ang, length=66, bow=12, w0=13, curl_dir=-1, curl_r=12)
    FINGER_TENTACLES.append(cmds)
    FINGER_TENTACLES.append(mirror(cmds))
    _finger_cl_lines.append(pts)

# ── Suction cups: along the outer edge of the head tentacles + each finger.
def cups_along(pts, ts, side=-1, r=6):
    """Cups at relative arc positions ts, offset to one side of the centerline."""
    out = []
    n = len(pts)
    for t, rr in zip(ts, r if isinstance(r, list) else [r] * len(ts)):
        i = min(n - 2, int(t * (n - 1)))
        p, q = pts[i], pts[i + 1]
        tx, ty = q[0] - p[0], q[1] - p[1]
        m = math.hypot(tx, ty) or 1.0
        nx, ny = -ty / m * side, tx / m * side
        out.append((p[0] + nx * 14, p[1] + ny * 14, rr))
    return out

SUCTION_L = []
for pts in _head_cl_lines:
    SUCTION_L += cups_along(pts, [0.30, 0.50, 0.68], side=-1, r=[7, 6, 5])
for pts in _finger_cl_lines:
    SUCTION_L += cups_along(pts, [0.55], side=-1, r=4)
# a couple of cups on the visible calf front
SUCTION_L += [(430, 1268, 6), (434, 1300, 6), (440, 1330, 5)]


def mirror_circles(circles):
    return circles + [(W - x, y, r) for (x, y, r) in circles]


# ─────────────────────────────────────────────────────────────────────────────
# Preview renderer (PIL) — same numbers the Kotlin side will parse
# ─────────────────────────────────────────────────────────────────────────────

def sample_path(cmds, steps=60):
    """Returns list of subpaths; each subpath is a list of (x, y)."""
    subs, cur = [], []
    pos = (0.0, 0.0)

    for c in cmds:
        if c[0] == "M":
            if cur:
                subs.append(cur)
            cur = [c[1:3]]
            pos = c[1:3]
        elif c[0] == "L":
            cur.append(c[1:3])
            pos = c[1:3]
        elif c[0] == "C":
            cur.extend(_cubic(pos, c[1:3], c[3:5], c[5:7], steps))
            pos = c[5:7]
        elif c[0] == "Z":
            if cur:
                cur.append(cur[0])
                subs.append(cur)
                cur = []
    if cur:
        subs.append(cur)
    return subs


def render_preview(out_path: Path, stretched: set[str], back: bool = False):
    """Render the front (default) or back figure to a PNG sanity preview."""
    from PIL import Image, ImageDraw

    scale = 0.8
    img = Image.new("RGB", (int(W * scale), int(H * scale)), (8, 28, 34))
    dr = ImageDraw.Draw(img)

    IDLE = (58, 74, 84)
    STRETCH = (43, 189, 154)

    def fill_poly(cmds, color, outline=(216, 232, 230)):
        for sub in sample_path(cmds, steps=20):
            pts = [(x * scale, y * scale) for x, y in sub]
            if len(pts) >= 3:
                dr.polygon(pts, fill=color, outline=outline)

    # Head tentacles behind the figure.
    for cmds in HEAD_TENTACLES:
        fill_poly(cmds, (50, 64, 72))

    region_defs = BACK_REGION_DEFS if back else REGION_DEFS
    for rd in sorted(region_defs, key=lambda r: r.draw_order):
        color = STRETCH if rd.part_id in stretched else IDLE
        fill_poly(rd.cmds, color)

    # Finger tentacles in front of the limb tips.
    for cmds in FINGER_TENTACLES:
        fill_poly(cmds, (50, 64, 72))

    def circle(cx, cy, r, fill):
        dr.ellipse(
            [(cx - r) * scale, (cy - r) * scale, (cx + r) * scale, (cy + r) * scale],
            fill=fill,
        )

    if not back:
        for x, y, r in EYES:
            circle(x, y, r, (247, 251, 250))
        for x, y, r in PUPILS:
            circle(x, y, r, (13, 34, 40))
        for x, y, r in HIGHLIGHTS:
            circle(x, y, r, (255, 255, 255))
        for x, y, r in BLUSH:
            circle(x, y, r, (156, 112, 200))

    def stroke(cmds, width, fill):
        for sub in sample_path(cmds):
            dr.line([(x * scale, y * scale) for x, y in sub], fill=fill, width=width)

    if back:
        # Back of the head: no face — spine/blade/glute detail strokes instead.
        for ln in BACK_MUSCLE_LINES:
            stroke(ln, 4, (28, 52, 60))
    else:
        stroke(SMILE, 6, (13, 34, 40))
        stroke(DOME_SHEEN, 10, (130, 217, 205))
        for ln in AB_LINES:
            stroke(ln, 4, (28, 52, 60))
        stroke(PEC_LINE, 4, (28, 52, 60))
    for x, y, r in mirror_circles(SUCTION_L):
        circle(x, y, r, (216, 232, 230))

    img.save(out_path)
    print(f"preview -> {out_path}")

    # Numeric sanity checks: everything inside the canvas, regions non-empty.
    all_cmds = []
    for rd in region_defs:
        all_cmds += rd.cmds
    for cmds in HEAD_TENTACLES + FINGER_TENTACLES:
        all_cmds += cmds
    xs = [c[i] for c in all_cmds if c[0] in ("M", "L", "C") for i in (1, 3, 5) if i < len(c)]
    ys = [c[i] for c in all_cmds if c[0] in ("M", "L", "C") for i in (2, 4, 6) if i < len(c)]
    print(f"bounds check: x [{min(xs):.0f},{max(xs):.0f}] y [{min(ys):.0f},{max(ys):.0f}] "
          f"(canvas {W:.0f}x{H:.0f})")
    assert min(xs) >= 0 and max(xs) <= W and min(ys) >= 0 and max(ys) <= H, "out of canvas!"
    print(f"regions: {len(region_defs)}, head tentacles: {len(HEAD_TENTACLES)}, "
          f"finger tentacles: {len(FINGER_TENTACLES)}, suction cups: {len(mirror_circles(SUCTION_L))}")


# ─────────────────────────────────────────────────────────────────────────────
# Kotlin emitter
# ─────────────────────────────────────────────────────────────────────────────

KOTLIN_TEMPLATE = '''package com.example.octo.ui.body

import com.example.octo.data.local.BodyPart

/**
 * GENERATED by scripts/gen_body_geometry.py — do not hand-edit path data.
 * Coordinate space: [CANVAS_WIDTH] x [CANVAS_HEIGHT], figure centered.
 *
 * Phase 10 anatomy: octopus-hair head (8 decorative tentacles drape around the
 * dome like hair) + tapered tentacle limbs ending in tiny finger-tentacle
 * clusters. Head tentacles and finger clusters are DECORATIVE — the 16 tappable
 * regions keep the original taxonomy.
 *
 * Each [RegionSpec.pathData] is an SVG path string (M/C/L/Z) parsed once with
 * androidx.core.graphics.PathParser; mirrored halves are baked into the string.
 * [drawOrder] is back-to-front; hit-testing must iterate reversed so the
 * front-most region wins (e.g. the groin plate over the pelvis).
 *
 * Phase 18 correction: the ON-SCREEN head is the PPQ art (R.drawable.octo_head_ppq,
 * composited by OctopusBodyView over the dome). The view therefore SKIPS
 * [headTentacles] and the face decorations (eyeWhites/pupils/eyeHighlights/
 * blush/smile/domeSheen) and the head-zone suction cups — but this geometry
 * file stays COMPLETE (source of truth for the vector man archive + the 16
 * region paths, incl. the NECK dome used for hit-testing under the head art).
 *
 * [headTentacles] must be drawn BEHIND the regions; [fingerTentacles] IN FRONT
 * of the regions (they extend past the wrist/ankle region tips).
 */
object OctopusBodyGeometry {{

    const val CANVAS_WIDTH = {W}f
    const val CANVAS_HEIGHT = {H}f

    data class RegionSpec(
        val part: BodyPart,
        val pathData: String,
        val drawOrder: Int,
    )

    data class DecoCircle(val cx: Float, val cy: Float, val radius: Float)
    data class DecoPath(val pathData: String, val stroke: Boolean)

    /** Which side of the figure to render (Phase 20). */
    enum class Side {{ FRONT, BACK }}

    /** Regions visible on [side] (front-only parts are absent from the back set). */
    fun regionsFor(side: Side): List<RegionSpec> =
        if (side == Side.BACK) backRegions else regions

    /** Front detail strokes (abs lines, pec split) or back ones (spine, blades). */
    fun muscleLinesFor(side: Side): List<DecoPath> =
        if (side == Side.BACK) backMuscleLines else muscleLines

    val regions: List<RegionSpec> = listOf(
{region_entries}
    )

    /** Front-most first (reverse draw order) — for tap hit-testing. */
    val hitTestOrder: List<RegionSpec> = regions.asReversed()

    /**
     * Phase 20 BACK VIEW — same silhouette from behind: 12 regions (front-only
     * chest/core/groin/quads are absent), hamstrings become the leading thigh
     * column, glutes dominate the pelvis. Head/tentacle decorations shared.
     */
    val backRegions: List<RegionSpec> = listOf(
{back_region_entries}
    )

    val backHitTestOrder: List<RegionSpec> = backRegions.asReversed()

    // ── Decorative tentacles (octopus hair + finger clusters) ──
    val headTentacles: List<DecoPath> = listOf(
{head_tentacle_entries}
    )
    val fingerTentacles: List<DecoPath> = listOf(
{finger_tentacle_entries}
    )

    // ── Decorations (face, muscle lines, suction cups) ──
    val eyeWhites = listOf({eyes})
    val pupils = listOf({pupils})
    val eyeHighlights = listOf({highlights})
    val blush = listOf({blush})
    val smile = DecoPath("{smile}", stroke = true)
    val domeSheen = DecoPath("{sheen}", stroke = true)
    val muscleLines: List<DecoPath> = listOf(
{muscle_entries}
    )
    val backMuscleLines: List<DecoPath> = listOf(
{back_muscle_entries}
    )
    val suctionCups = listOf({suction})
}}
'''


def emit_kotlin(out_path: Path):
    def region_block(defs):
        out = []
        for rd in defs:
            enum_name = rd.part_id.upper()
            out.append(
                '        RegionSpec(BodyPart.%s, "%s", %d),' % (enum_name, to_svg(rd.cmds), rd.draw_order)
            )
        return "\n".join(out)

    region_entries = region_block(REGION_DEFS)
    back_region_entries = region_block(BACK_REGION_DEFS)

    def deco_entries(paths):
        return "\n".join('        DecoPath("%s", stroke = false),' % to_svg(p) for p in paths)

    def circles(circles):
        return ", ".join(f"DecoCircle({x:.0f}f, {y:.0f}f, {r:.0f}f)" for x, y, r in circles)

    muscle_entries = []
    for ln in AB_LINES + [PEC_LINE]:
        muscle_entries.append('        DecoPath("%s", stroke = true),' % to_svg(ln))
    back_muscle_entries = []
    for ln in BACK_MUSCLE_LINES:
        back_muscle_entries.append('        DecoPath("%s", stroke = true),' % to_svg(ln))

    suction = ", ".join(
        f"DecoCircle({x:.0f}f, {y:.0f}f, {r:.0f}f)" for x, y, r in mirror_circles(SUCTION_L)
    )

    kt = KOTLIN_TEMPLATE.format(
        W=int(W), H=int(H),
        region_entries=region_entries,
        back_region_entries=back_region_entries,
        back_muscle_entries="\n".join(back_muscle_entries),
        head_tentacle_entries=deco_entries(HEAD_TENTACLES),
        finger_tentacle_entries=deco_entries(FINGER_TENTACLES),
        eyes=circles(EYES),
        pupils=circles(PUPILS),
        highlights=circles(HIGHLIGHTS),
        blush=circles(BLUSH),
        smile=to_svg(SMILE),
        sheen=to_svg(DOME_SHEEN),
        muscle_entries="\n".join(muscle_entries),
        suction=suction,
    )
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(kt, encoding="utf-8")
    print(f"kotlin  -> {out_path} ({len(kt) / 1024:.0f} KiB)")


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    render_preview(root / "docs" / "art" / "body_map_preview.png", stretched=set())
    render_preview(
        root / "docs" / "art" / "body_map_preview_back.png",
        stretched=set(), back=True,
    )
    emit_kotlin(
        root / "app/src/main/java/com/example/octo/ui/body/OctopusBodyGeometry.kt"
    )
