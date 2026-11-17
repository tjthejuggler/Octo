# ADR: Home-screen widget (octopus-man, mirrored in Kenga)

**Status:** Accepted (2026-11-08; final form after 4 iterations)

## Context
Users want the dashboard's mascot on the home screen: tap → open the app's main screen. Iteration history: (1) StackView swipe-to-flip — superseded; (2) bare 1×1 figure — clipped/dead space; (3) rounded card — rejected by user ("get rid of background bounding boxes"); (4) **final: bare mascot, transparent background**.

## Decision
- Classic `AppWidgetProvider` (no Glance), default **1 wide × 2 tall cells** (`targetCellWidth 1`, `targetCellHeight 2`), freely resizable, front view only.
- The shipped bitmap is the mascot ONLY: rendered with the dashboard's exact pipeline, cropped to his alpha bounds, transparent background — no card, no padding box.
- **CRITICAL renderer rule:** offscreen `CanvasDrawScope.scale(s, s)` MUST pass `pivot = Offset.Zero`. The default pivot is the canvas CENTER, which shifts the drawing right/down by `500s(1−s)` and clips the figure's right side + feet at widget scale (the dashboard masks this via aspect-matched sizing + calibrated centering constants).
- Delivery: `setImageViewBitmap` with a 160k-pixel binder-safety downscale cap (~1 MB RemoteViews limit). No content URIs (launcher grants failed in the field).
- Tap = `PendingIntent` → `MainActivity`. Refresh: widget add/resize + `MainActivity.onResume()` (`requestUpdate`); tier colors from DB (`bodyPartDao().forDayOnce` / `pbRepository.observeTodaySetCounts`).

## Consequences
- Visual parity via shared geometry/palette; Kenga inherits dashboard changes automatically (direct `drawBodyMap` reuse), Octo's hand-mirrored `OctoWidgetRenderer` must track `OctopusBodyView.drawBody` changes.
- Push-driven refresh; midnight tier resets appear on next app open / resize / re-add.