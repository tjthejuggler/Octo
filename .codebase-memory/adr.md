# ADR: Anatomical muscle figure (écorché) as the dashboard's "Muscles" view

## Status
Accepted (2026-10-08)

## Context
The body dashboard's octopus-man has 16 tappable regions. The muscle-detail
layer (48 canonical muscles, vision-LLM analyzed) needed a figure-level view:
when the user toggles "Muscles", the whole centerpiece becomes an anatomical
human diagram, front + back, with the mascot head preserved.

## Decision
1. Geometry is GENERATED: scripts/gen_muscle_geometry.py emits
   ui/body/MuscleBodyGeometry.kt (silhouette + per-muscle band paths in the
   same 1000x1500 viewBox as OctopusBodyGeometry). Never hand-edit numbers.
2. Every muscle is a full-width BAND/plate of its limb segment (écorché
   tiling) — floating ellipses ("slivers") were rejected after on-device
   review. Deep/no-surface muscles are intentionally absent from the figure.
3. Rendering (MuscleFigureView) is two-pass: (1) each band inflated ~5% in a
   flat base tone with dark contour so neighbors merge into one continuous
   anatomy mass; (2) normal-size gradient detail pass; stretched-today
   muscles glow teal. Everything is clipped to the silhouette union so no
   band can ever overflow the body.
4. The SAME PPQ octopus head art (and placement constants, made internal) is
   composited over the neck dome in both modes — the mascot is continuous.
5. Toggle state (muscleMode) is shared by the figure pill AND the parts
   sheet's Parts/Muscles segment; the octopus man remains the default.

## Consequences
- Art changes go through the Python script, then regenerate + rebuild.
- The silhouette clip makes band-position regressions safe by construction.
- Front/back reuse the existing flip machinery (side enum mapped across).