# ADR: Muscle view renders as an overlay on the octopus body (FB27)

## Status
Accepted (2026-10-14; arm-plate rotation sign corrected on-device)

## Context
The dashboard's Muscles toggle previously swapped the octopus-man figure for a
standalone poster-écorché anatomical figure. The user preferred the octopus
body art and wanted the same body visible in muscle mode — just dulled so the
muscle layer reads.

## Decision
- OctopusBodyView gained muscleMode/stretchedMuscles/onMuscleTap params. In
  muscle mode it draws the normal body, then a dark scrim (DullScrim,
  0xFF10161F @ 55%) clipped to the figure silhouette (regions + finger
  tentacles), then composites the muscle plates from MuscleBodyGeometry on top
  (both share the 1000x1500 viewBox so plates align with the octo-man). The PPQ
  head art draws AFTER the scrim so the face stays bright.
- Tap routing: muscle plates win first (inscribed-ellipse spindleHit), then
  body-part regions fall through.
- MuscleBodyView.kt (MuscleFigureView) was deleted; its palette moved into
  OctopusBodyView.kt (muscleHueShades).
- Arm plates regenerated as rotated ellipse spindles aligned to the octopus
  tentacle-arm axis: shoulder cap x260-404/y470-590, upper arm
  (383,548)->(299,728), forearm (298,734)->(264,892). CRITICAL SIGN NOTE: the
  arms SPLAY OUTWARD going down, so plate rotation is POSITIVE (+24/+25/+12°)
  — top end INWARD (toward x=500), bottom end OUTWARD. A negative rotation
  (tried first) leans the plates the wrong way; verified via adb screencap.

## Consequences
- One figure, two looks: no duplicate body art; the écorché silhouette is gone.
- MuscleBodyGeometry remains the plate data source; arm plate coordinates are
  octopus-arm-calibrated (positive splay rotation) — keep the sign if
  gen_muscle_geometry.py is ever re-run.
- Plates render under the translucent hair-tentacle art (consistent with the
  body itself), which slightly washes them near the shoulders.