# ADR-031: Legend removed from video detail; percent ring = tier rainbow sweep

Date: 2026-10-16

## Status
Accepted (implemented, deployed via installDebug)

## Context
Two UI refinements requested after ADR-030: (1) the video-detail legend ("Not in this video" / "This video" dots) was redundant — the minis are self-explanatory; (2) the dashboard PercentRing used a static teal→green gradient, disconnected from the new tier system.

## Decision
1. [`MiniBodyMapCard`](VideoDetailScreen.kt): legend row deleted; `previewTargetColor`/`LegendDot` helpers removed; legend strings (`video_detail_legend_today/others/video`) dropped from strings.xml.
2. [`PercentRing`](BodyScreen.kt): track arc = translucent idle red; progress arc uses a sweepGradient with the SAME tier stops (red→orange→green→blue→pink→yellow→white) rotated +25% so red starts at the 12 o'clock arc origin. At 0% the ring shows only the faint red track; as % grows, the visible sweep morphs through the tier progression (≈14.3% per tier stop). Sweep animates (900ms tween) as before.

## Consequences
- Ring, body-map regions, and plates now share one color language (todayTierColor stops).
- sweepGradient linearly interpolates between stops, giving the smooth "rainbow oval" morph the user described.
- Files touched: VideoDetailScreen.kt, strings.xml, BodyScreen.kt; compile + installDebug green.