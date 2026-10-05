# ADR — Phase 25: Part-provenance UX (no visible chips) + library card density

Date: 2026-10-05 (Phase 25, completing Phase 24 audit)

## Decision
1. Part provenance (PartSource: HEURISTIC/LLM/MANUAL on videos.partSource, migration v3→v4)
   is DB-LEVEL ONLY. No visible provenance chips anywhere in the UI ("Quick scan" /
   "AI-analyzed" / "Edited" labels were rejected by the user as confusing noise).
   Provenance drives exactly one behavior: the Library overflow "Analyze" shows the
   "Re-analyze and replace edits?" confirm dialog when analysisState=DONE AND
   partSource=MANUAL.
2. Legacy pre-v4 rows (DONE, partSource=NULL) derive HEURISTIC in both Library and
   VideoDetail ViewModels, and canAnalyze includes DONE+NULL so such rows can be
   re-analyzed (stamping the definitive value) — previously they were permanently
   un-stampable because the Analyze menu was hidden.
3. Library card state: "Downloaded" text chip replaced by a tiny CheckCircle icon
   (downloaded is the expected state; text label remains only for
   downloading/failed/queued/stream-only). Part tags render across two rows
   (halves split); the second row scrolls horizontally; the "+n" overflow is gone.
4. Edit-parts affordance on video detail is keyed on isAnalysisDone (not
   bodyParts.isNotEmpty()) so an empty DONE set stays editable.

## Consequences
- saveManualParts (VideoDao.applyManualParts) still commits links+DONE+MANUAL in one
  Room transaction; confirmed sessions keep their creditedBodyPartsJson snapshots —
  manual edits affect only FUTURE confirmations (undo stays snapshot-authoritative).
- Flip centering constants (OctopusBodyView FRONT +27u / BACK +39u) verified by
  pixel-scan: front +1px, back +0px from screen center on 1080×2316. They live in the
  shared view, so the video-detail side-by-side card inherits the fix. A dedicated
  video-detail layout pass remains for the next phase.
- Drag-tracking flip (0.9°/dp live rotationY, ±90° content swap, flick-biased settle,
  touch-slop taps, animated pill toggle) stands as implemented in Phase 24.
