# ADR: Library card recency coloring (FB28)

## Status
Accepted (2026-11-17)

## Context
Library video cards used a uniform blue-ish sea-glass tint with no signal of how stale a video's practice was. The app already has a tier color language on the body map (red→orange→green→blue→pink→yellow→white by sets done today).

## Decision
- Cards are tinted by LAST-PLAYED recency using the SAME tier palette anchors as the body map: today=white, yesterday=yellow, 2 days=pink, 3–6 days=blue, 7–13 days=green, 14–20 days=orange, older/never=red.
- The tint lives in `GlassCard(tint=...)` as a lerp into the pane base (0.75 light / 0.55 dark) — sea-glass translucency is preserved, tier hue is clearly identifiable.
- A compact age pill (today/3d/4w/2mo/1y) sits top-right; its background echoes the card's own tier color at 55% alpha.
- Bucketing/marker logic is pure JVM code (`ui/library/LibraryRecency.kt`) with unit tests (`LibraryRecencyTest`); whole-local-day math avoids midnight-edge flicker.
- The overflow-dots menu was removed; long-press (`combinedClickable`) opens a glass menu sheet: Analyze (conditional), Open on YouTube (canonical `VideoEntity.url` — present on every row since PK=videoId), Delete (reuses existing confirm dialog).

## Consequences
- One shared color language across Body + Library; red cards visually beg for attention.
- Future import/share paths keep storing canonical URLs, so YouTube links stay universal — no schema change was needed.
