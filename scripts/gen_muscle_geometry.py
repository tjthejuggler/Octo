#!/usr/bin/env python3
"""Generates MuscleBodyGeometry.kt — the anatomical muscle figure (v13).

v13 = full-coverage écorché in the style of a classic anatomy diagram poster:
  - muscles TILE the whole body: every limb is fully covered top-to-bottom by
    adjacent full-width plates, the torso is fully covered by pecs/abs/obliques,
    the pelvis by glutes/groin. No bare silhouette gaps.
  - each muscle carries a HUE (poster-style group color: red pecs/lats,
    green abs, yellow delts, violet triceps, blue quads/calves/glutes,
    orange biceps/forearms, teal shins, sand neck...). MuscleFigureView maps
    hue -> idle gradient; stretched muscles override with teal glow.
  - silhouettes under the plates only peek at hands/feet.

Emits app/src/main/java/com/example/octo/ui/body/MuscleBodyGeometry.kt.
Coordinate space: 1000x1500, figure centered on x=500 (same as the octopus man).
"""
from __future__ import annotations
import math
from pathlib import Path

W = 1000.0

def to_svg(cmds) -> str:
    parts = []
    for c in cmds:
        if c[0] == "Z":
            parts.append("Z")
        else:
            parts.append(c[0] + " " + " ".join(f"{v:.1f}" for v in c[1:]))
    return " ".join(parts)

def mirror(cmds):
    out = []
    for c in cmds:
        if c[0] == "Z":
            out.append(("Z",))
        else:
            out.append((c[0], *[W - v if i % 2 == 0 else v for i, v in enumerate(c[1:])]))
    return out

def ellipse(cx, cy, rx, ry):
    k = 0.5523
    return [
        ("M", cx - rx, cy),
        ("C", cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry),
        ("C", cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy),
        ("C", cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry),
        ("C", cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy),
        ("Z",),
    ]

def _unit(a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    L = math.hypot(dx, dy)
    return dx / L, dy / L, L

def capsule(x1, y1, x2, y2, width):
    return cone(x1, y1, x2, y2, width / 2, width / 2)

def cone(x1, y1, x2, y2, r1, r2):
    """Capsule with different end radii."""
    ux, uy, L = _unit((x1, y1), (x2, y2))
    px, py = -uy, ux
    k1, k2 = 0.5523 * r1, 0.5523 * r2
    e1 = (x1 - ux * r1, y1 - uy * r1)
    e2 = (x2 + ux * r2, y2 + uy * r2)
    a = (x1 + px * r1, y1 + py * r1)
    b = (x2 + px * r2, y2 + py * r2)
    c = (x2 - px * r2, y2 - py * r2)
    d = (x1 - px * r1, y1 - py * r1)
    return [
        ("M", *a),
        ("L", *b),
        ("C", b[0] + ux * k2, b[1] + uy * k2, e2[0] + px * k2, e2[1] + py * k2, *e2),
        ("C", e2[0] - px * k2, e2[1] - py * k2, c[0] + ux * k2, c[1] + uy * k2, *c),
        ("L", *d),
        ("C", d[0] - ux * k1, d[1] - uy * k1, e1[0] - px * k1, e1[1] - py * k1, *e1),
        ("C", e1[0] + px * k1, e1[1] + py * k1, a[0] - ux * k1, a[1] - uy * k1, *a),
        ("Z",),
    ]

def fusiform(cx, cy, length, width, angle_deg):
    th = math.radians(angle_deg)
    dx, dy = math.sin(th), -math.cos(th)
    px, py = math.cos(th), math.sin(th)
    t1 = (cx - dx * length / 2, cy - dy * length / 2)
    t2 = (cx + dx * length / 2, cy + dy * length / 2)
    w = width / 2
    def ctrl(tip, along, perp):
        return (tip[0] + dx * length * 0.25 * along + px * w * 0.72 * perp,
                tip[1] + dy * length * 0.25 * along + py * w * 0.72 * perp)
    c1 = ctrl(t1, +1, +1)
    c2 = ctrl(t2, -1, +1)
    c3 = ctrl(t2, -1, -1)
    c4 = ctrl(t1, +1, -1)
    return [("M", *t1), ("C", *c1, *c2, *t2), ("C", *c3, *c4, *t1), ("Z",)]

def oval(cx, cy, rx, ry, angle_deg=0.0):
    """Big rounded muscle-belly oval, rotatable — fills its limb zone."""
    th = math.radians(angle_deg)
    cos_t, sin_t = math.cos(th), math.sin(th)
    pts = []
    k = 0.5523
    # axis-aligned oval control points, then rotate about center
    base = [
        ((-rx, -ry * k), (-rx * k, -ry), (0, -ry)),                       # top-left arc
        ((rx * k, -ry), (rx, -ry * k), (rx, 0)),                          # top-right arc
        ((rx, ry * k), (rx * k, ry), (0, ry)),                            # bottom-right arc
        ((-rx * k, ry), (-rx, ry * k), (-rx, 0)),                         # bottom-left arc
    ]
    def rot(pt):
        x, y = pt
        return (cx + x * cos_t - y * sin_t, cy + x * sin_t + y * cos_t)
    m0 = rot((-rx, 0))
    out = [("M", *m0)]
    for c1, c2, end in base:
        out.append(("C", *rot(c1), *rot(c2), *rot(end)))
    out.append(("Z",))
    return out

def fan(px, py, b1, b2, sag=0.18):
    mx, my = (b1[0] + b2[0]) / 2, (b1[1] + b2[1]) / 2
    ux, uy, L = _unit(b1, b2)
    qx, qy = mx + (-uy) * L * sag, my + ux * L * sag
    return [("M", px, py), ("L", *b1), ("Q", qx, qy, *b2), ("Z",)]

# ── Limb axes (left side) ────────────────────────────────────────────────────
SH = (392, 580)
EL = (334, 800)
WR = (306, 990)
HIP = (448, 952)
KNEE = (436, 1188)
ANK = (430, 1378)

def band_along(a, b, t1, t2, w, off=0.0):
    """Fusiform strand along axis a→b, absolute width w, perp shift off (px)."""
    ax, ay = a
    dx, dy = b[0] - ax, b[1] - ay
    L = math.hypot(dx, dy)
    ux, uy = dx / L, dy / L
    px, py = -uy, ux
    p1 = (ax + dx * t1 + px * off, ay + dy * t1 + py * off)
    p2 = (ax + dx * t2 + px * off, ay + dy * t2 + py * off)
    return fusiform((p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2, L * (t2 - t1), w,
                    math.degrees(math.atan2(dx, -dy)))

def plate(x1, y1, x2, y2, w1, w2=None):
    return cone(x1, y1, x2, y2, w1 / 2, (w2 or w1) / 2)

# ── Silhouette (unchanged) ───────────────────────────────────────────────────
BODY_SHAPES = [
    capsule(500, 462, 500, 552, 96),
    capsule(500, 558, 500, 642, 256),
    capsule(500, 682, 500, 802, 196),
    capsule(500, 852, 500, 990, 224),
    ellipse(394, 576, 48, 56),
    capsule(*SH, *EL, 78),
    capsule(*EL, *WR, 60),
    ellipse(300, 1032, 26, 35),
    ellipse(606, 576, 48, 56),
    capsule(1000 - SH[0], SH[1], 1000 - EL[0], EL[1], 78),
    capsule(1000 - EL[0], EL[1], 1000 - WR[0], WR[1], 60),
    ellipse(700, 1032, 26, 35),
    capsule(*HIP, *KNEE, 100),
    capsule(*KNEE, *ANK, 70),
    ellipse(424, 1410, 34, 24),
    capsule(1000 - HIP[0], HIP[1], 1000 - KNEE[0], KNEE[1], 100),
    capsule(1000 - KNEE[0], KNEE[1], 1000 - ANK[0], ANK[1], 70),
    ellipse(576, 1410, 34, 24),
]

# ── FRONT: full-coverage plates, poster hues ─────────────────────────────────
def build_front():
    m = []
    m.append(("sternocleidomastoid", oval(474, 526, 16, 44, 24), "sand", True))
    # deltoids: two big gold ovals capping the shoulder
    m.append(("lateral_deltoid", oval(386, 574, 44, 58, 30), "gold", True))
    m.append(("anterior_deltoid", oval(414, 598, 40, 54, 14), "gold", True))
    # pecs: two big red ovals tiling the chest
    m.append(("pectoralis_major", oval(438, 610, 66, 58, -6), "red", True))
    m.append(("pectoralis_minor", oval(446, 556, 44, 34, -8), "red", True))
    # biceps: one fat oval filling the upper-arm belly
    m.append(("biceps_brachii", oval(346, 690, 37, 66, 14), "rust", True))
    # forearm: two stacked orange ovals
    m.append(("brachioradialis", oval(312, 866, 29, 56, 8), "orange", True))
    m.append(("wrist_flexors", oval(330, 892, 27, 52, 8), "orange", True))
    # abs: big green plate; obliques: tall side ovals
    m.append(("rectus_abdominis", oval(500, 760, 58, 98, 0), "green", False))
    m.append(("external_obliques", oval(442, 750, 30, 66, 6), "green", True))
    # hip flexors + TFL
    m.append(("hip_flexors", oval(474, 938, 24, 50, 8), "salmon", True))
    m.append(("tensor_fasciae_latae", oval(412, 952, 24, 44, 12), "salmon", True))
    # adductors: two inner-thigh ovals
    m.append(("adductor_longus", oval(462, 1006, 26, 56, 4), "salmon", True))
    m.append(("adductor_magnus", oval(446, 1076, 28, 60, 3), "salmon", True))
    # quads: three big blue ovals tiling the thigh
    m.append(("rectus_femoris", oval(450, 1064, 34, 92, 2), "blue", True))
    m.append(("vastus_lateralis", oval(414, 1064, 30, 88, 4), "blue", True))
    m.append(("vastus_medialis", oval(470, 1128, 30, 56, -4), "blue", True))
    # shin: teal oval
    m.append(("tibialis_anterior", oval(440, 1270, 24, 74, 2), "teal", True))
    return m

# ── BACK ─────────────────────────────────────────────────────────────────────
def build_back():
    m = []
    # traps: three sand/red ovals stepping down the spine line
    m.append(("upper_trapezius", oval(466, 540, 38, 46, 24), "sand", True))
    m.append(("middle_trapezius", oval(462, 614, 44, 36, 14), "red", True))
    m.append(("lower_trapezius", oval(500, 686, 52, 46, 0), "red", False))
    m.append(("rhomboids", oval(452, 640, 34, 44, 18), "red", True))
    # posterior deltoid: gold oval
    m.append(("posterior_deltoid", oval(416, 586, 38, 52, 16), "gold", True))
    # lats: big red oval sweeping to the waist
    m.append(("latissimus_dorsi", oval(452, 700, 52, 90, 14), "red", True))
    # erector spinae: tall twin column
    m.append(("erector_spinae", oval(484, 764, 22, 78, 2), "green", True))
    # triceps: violet oval filling the upper-arm back
    m.append(("triceps_brachii", oval(348, 692, 36, 64, 14), "violet", True))
    m.append(("wrist_extensors", oval(318, 886, 26, 52, 8), "orange", True))
    # glutes: two big blue ovals
    m.append(("gluteus_medius", oval(438, 936, 34, 48, 12), "blue", True))
    m.append(("gluteus_maximus", oval(462, 986, 48, 62, 6), "blue", True))
    # hamstrings: two rust ovals tiling the thigh back
    m.append(("biceps_femoris", oval(428, 1070, 32, 88, 4), "rust", True))
    m.append(("semitendinosus", oval(468, 1072, 28, 86, 3), "rust", True))
    # calf: blue oval + teal soleus below
    m.append(("gastrocnemius", oval(442, 1258, 40, 74, 2), "blue", True))
    m.append(("soleus", oval(444, 1330, 30, 44, 2), "teal", True))
    return m

def regions(specs):
    lines = []
    for mid, cmds, hue, mirrored in specs:
        path = to_svg(cmds)
        if mirrored:
            path = path + " " + to_svg(mirror(cmds))
        lines.append(f'        MuscleRegionSpec("{mid}", "{path}", "{hue}"),')
    return "\n".join(lines)

header = '''package com.example.octo.ui.body

/**
 * GENERATED by scripts/gen_muscle_geometry.py — do not hand-edit numbers.
 *
 * Anatomical muscle figure v13 — full-coverage écorché in the style of a
 * classic anatomy poster: muscles TILE the body (full-width limb plates,
 * chest/abs/lats cover the torso, glutes the pelvis; no bare gaps), and every
 * muscle carries a poster-style [hue] (red pecs/lats, green abs, gold delts,
 * violet triceps, blue quads/calves/glutes, orange forearms, rust biceps/
 * hamstrings, salmon groin, teal shins, sand neck). [MuscleFigureView] maps
 * each hue to an idle gradient; stretched-today muscles glow teal.
 * STRICT front/back split. Same 1000x1500 viewBox as [OctopusBodyGeometry].
 * Mirrored regions carry the left path + its x=500 mirror in one string.
 */
object MuscleBodyGeometry {

    enum class Side { FRONT, BACK }

    /** One muscle = one region spec; [pathData] may contain 2 mirrored subpaths. */
    data class MuscleRegionSpec(
        val muscleId: String,
        val pathData: String,
        val hue: String,
    )

    /** Dark silhouette under the plates (hands/feet peek out). */
    val bodyShapes: List<String> = listOf(
'''

body = ",\n".join(f'        "{to_svg(s)}"' for s in BODY_SHAPES)

tail = f''',
    )

    val frontMuscles: List<MuscleRegionSpec> = listOf(
{regions(build_front())}
    )

    val backMuscles: List<MuscleRegionSpec> = listOf(
{regions(build_back())}
    )

    fun musclesFor(side: Side): List<MuscleRegionSpec> =
        if (side == Side.BACK) backMuscles else frontMuscles
}}
'''

out = Path("app/src/main/java/com/example/octo/ui/body/MuscleBodyGeometry.kt")
out.write_text(header + body + tail)
print(f"wrote {out} ({len(build_front())} front, {len(build_back())} back muscles)")
