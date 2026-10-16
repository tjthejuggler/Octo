# Octo 🐙

## 2026-10-16 — FB27: double-tap seek + "done today" library badge
- **Player double-tap seek (YouTube-style)** ([`PlayerScreen.kt`](app/src/main/java/com/example/octo/ui/player/PlayerScreen.kt),
  [`PlayerViewModel.kt`](app/src/main/java/com/example/octo/ui/player/PlayerViewModel.kt)): the
  fullscreen video surface now uses `pointerInput` + `detectTapGestures` instead of plain
  `clickable` — single tap still toggles play/pause + reveals controls (with the standard
  ~300 ms double-tap disambiguation delay), double tap on the LEFT half seeks −10 s, on the
  RIGHT half +10 s via new `PlayerViewModel.seekBy`. A transient Replay10/Forward10 badge on
  the tapped half confirms the action (auto-hides after 700 ms).
- **Library "done today" badge** ([`LibraryScreen.kt`](app/src/main/java/com/example/octo/ui/library/LibraryScreen.kt),
  [`LibraryViewModel.kt`](app/src/main/java/com/example/octo/ui/library/LibraryViewModel.kt)):
  cards whose video has ANY session started today (pending or confirmed — "I watched this
  today") show a small primary-tinted check dot on the thumbnail corner. Driven by a new
  `watchedToday: StateFlow<Set<String>>` derived from the existing `lastPlayedPerVideo()`
  query — no schema/DAO changes.

## 2026-10-05 — Phase 25: Phase 24 audit & completion (provenance UX + FB25 card polish)
- **Audit** of the interrupted Phase 24: data/gesture layers were done (v3→v4
  `partSource` migration + pipeline stamping + `applyManualParts` transaction +
  drag-tracking flip). Gaps fixed: legacy pre-v4 rows (DONE, `partSource=NULL`)
  now derive HEURISTIC and can be re-analyzed; the Edit-parts affordance shows for
  empty DONE sets; the mislabeled fb5 evidence was replaced with real captures.
- **FB25 user feedback** ([`LibraryScreen.kt`](app/src/main/java/com/example/octo/ui/library/LibraryScreen.kt),
  [`VideoDetailScreen.kt`](app/src/main/java/com/example/octo/ui/video/VideoDetailScreen.kt)):
  NO provenance chips anywhere (user rejected "Quick scan" et al.); "Downloaded"
  text → tiny CheckCircle icon; part tags across two rows (second row scrolls);
  video-detail chips keep only the Edit pencil.
- **Edit-parts chain verified on device**: pencil → 16-checkbox dialog → save
  stamps `partSource=MANUAL` + replaced cross-refs in one transaction; Library
  "Analyze" on a MANUAL row confirms before replacing edits; past sessions keep
  their credited snapshots.
- **Centering pixel-verified**: front +1px / back +0px from screen center
  (1080×2316) via [`OctopusBodyView.sideCenteringOffsetX`](app/src/main/java/com/example/octo/ui/body/OctopusBodyView.kt).
- Screenshots: [`docs/art/screenshots/fb25_*.png|jpg`](docs/art/screenshots/).
  Details: [`docs/NOTES.md` §28](docs/NOTES.md).

## 2026-10-04 — Phase 14 (feedback round 3): dashboard cleanup
- **Bottom nav labels** ([`OctoBottomBar.kt`](app/src/main/java/com/example/octo/ui/navigation/OctoBottomBar.kt)):
  fixed 88.dp bar → intrinsic content height (+6.dp vertical padding); label keeps
  11.sp but drops the tight 13.sp lineHeight (descender clipping) — `maxLines=1,
  softWrap=false`. Verified uncropped on device.
- **Legend → Parts sheet** ([`BodyScreen.kt`](app/src/main/java/com/example/octo/ui/body/BodyScreen.kt)):
  the always-visible 2×8 legend grid is gone. A discreet tag icon in the title row
  opens a Material3 `ModalBottomSheet` with all 16 parts (state dot + minutes, same
  `octoRegionColor` mapping); tap → `part/{bodyPartId}`; backdrop tap dismisses.
- **Stat squares → corner glyphs** ([`BodyScreen.kt`](app/src/main/java/com/example/octo/ui/body/BodyScreen.kt)):
  the 4-chip stat row is removed. % stays only on the ring badge; sessions counter
  dropped; minutes (clock icon) + streak (flame icon) render as small unboxed
  teal glyphs at the figure-area bottom-right, hidden at 0.
- Screenshots: [`docs/art/screenshots/fb3_dashboard_*.png`](docs/art/screenshots/) (empty,
  seeded, parts sheet) — device DB wiped back to clean after capture (Phase 16 handles
  the final fresh-install wipe).

Stretch-tracking Android app: share a YouTube yoga video, download it offline, play with a
play-time session timer, and watch your octopus-man mascot light up tentacle by tentacle.

## Features (complete after Phase 8)

- **Share ingestion** — share any YouTube watch/Shorts/youtu.be link from the YouTube app (or
  paste it via the Library ＋ dialog); Octo queues it and downloads an offline MP4 via
  NewPipeExtractor (muxed-first, dual-track fallback).
- **Transcript + body-part analysis** — three-tier transcript fetch (timedtext scrape →
  extractor subtitles → title/description), then LLM classification (any OpenAI-compatible
  endpoint; defaults to z.ai `glm-4.6` since Phase 15) with a keyword-heuristic fallback
  when no API key is configured.
- **Player** — Media3 offline playback (muxed or merged dual-track), timer counts *playing
  time only*, Finish/Save-and-leave/back-out semantics; one Tail broadcast per completed
  session. Reachable via the video detail screen's Maximize button.
- **Video detail** — tap a video (Library Play button, a body-part screen's video list, or
  a region on the mini map) for a small 16:9 player plus a mini octopus-man showing today's
  stretched regions (green) vs. the regions *this* video will stretch (amber).
- **Body dashboard** — vector octopus-man (octopus arms for hair, fingers and toes!) with
  16 canonical regions, % ring, minutes / sessions / streak cards, day navigation; tap any
  region or legend chip for a per-part screen (videos, window stats, trend chart, history).
- **Stats** — range-selectable totals, trend chart with 7-day rolling average, per-part
  frequency bars with sparklines (pure Canvas, unit-tested math).
- **Tail habit integration** — signature-permission protocol v3 broadcasts
  (`+sessions`, `+minutes`) to the companion Tail app; graceful no-op when absent.
- **Library backup** — Settings → *Library backup*: export all video URLs to JSON via SAF,
  import restores stream-only rows (metadata re-fetched on demand).

## How to use

1. Share a YouTube video → Octo (or tap ＋ in Library and paste a URL). Wait for
   *Downloaded*.
2. Optional: Settings → LLM → enter base URL / API key / model → Save → Test LLM. Without a
   key the heuristic classifier runs instead.
3. Play → the timer runs only while the video plays → **Finish Session** (or back out:
   ≤ 30 s discards, longer asks Save-and-leave).
4. Body tab shows today's stretched regions; Stats shows history. Install
   [Tail](/home/twain/AndroidStudioProjects/tail) (same debug keystore) to mirror sessions
   into a habit.

## How to build

```bash
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # JVM unit tests (StatsMathTest + ExampleUnitTest)
./gradlew installDebug           # needs adb device
```

Requirements: JDK 21 toolchain (Gradle daemon JVM pinned in `gradle/gradle-daemon-jvm.properties`),
Android SDK 37. JitPack is required for NewPipeExtractor (declared in `settings.gradle.kts`).

Release notes: R8 keep rules for NewPipeExtractor/JSoup/kotlinx-serialization live in
`app/src/main/keepRules/rules.keep`; release builds must be signed with Tail's keystore for
the IPC permission to match (docs/ARCHITECTURE.md §2.6).

## Phase 6 — Body map & dashboard (2026-10-03)

- **Octopus-man body map** ([`ui/body/OctopusBodyView.kt`](app/src/main/java/com/example/octo/ui/body/OctopusBodyView.kt)):
  Compose Canvas vector figure with exactly 16 [`BodyPart`](app/src/main/java/com/example/octo/data/local/BodyPart.kt)
  regions (ARCHITECTURE.md §5). Each region = one SVG path (parsed via `PathParser`), mirrored
  halves baked in by the generator. Regions glow teal→green (`animateColorAsState`, pulse halo)
  when stretched; intensity scales with minutes (cap 10). Tap = exact `Region` hit-test → dialog.
- **Geometry single-source-of-truth**: [`scripts/gen_body_geometry.py`](scripts/gen_body_geometry.py)
  renders a PIL preview ([`docs/art/body_map_preview.png`](docs/art/body_map_preview.png)) and
  emits [`OctopusBodyGeometry.kt`](app/src/main/java/com/example/octo/ui/body/OctopusBodyGeometry.kt)
  (GENERATED — do not hand-edit path data).
- **Dashboard** ([`ui/body/BodyScreen.kt`](app/src/main/java/com/example/octo/ui/body/BodyScreen.kt)):
  brand header, animated % ring (stretchedRegions/16), minutes/sessions/streak cards, 16 legend
  chips, Yesterday/Today date navigation (read-only history), motivational empty state.
- **ViewModel** ([`ui/body/BodyViewModel.kt`](app/src/main/java/com/example/octo/ui/body/BodyViewModel.kt)):
  `BodyPartDao.forDay` per selected date + `forRange` 60-day window → streak (consecutive days
  with ≥1 region, today-optional). No DAO changes needed.
- **Brand art via PPQ** (`grok-imagine-image-2`, one-off):
  [`scripts/generate_brand_art.py`](scripts/generate_brand_art.py) →
  [`docs/art/octo_brand_raw.png`](docs/art/octo_brand_raw.png) +
  `res/drawable-nodpi/octo_mascot.png` (dashboard header).
- **Launcher icon**: [`scripts/make_icon_art.py`](scripts/make_icon_art.py) → adaptive foreground
  PNG (`drawable-nodpi/ic_launcher_foreground.png`), all `mipmap-*` webps, deep-abyss background
  vector. Adaptive-icon XML structure untouched.
- **Debug seed hook**: `adb shell am start -n com.example.octo/.debug.DevSeedActivity --ei days 3 --ei parts_per_day 4`
  ([`debug/DevSeedActivity.kt`](app/src/main/java/com/example/octo/debug/DevSeedActivity.kt)) —
  writes real `BodyPartStretchEntity` aggregates; `BuildConfig.DEBUG` guard, no-op in release.
  Also fixes a pre-existing nav-bar race ("setGraph() before getGraph()") in
  [`MainActivity.kt`](app/src/main/java/com/example/octo/MainActivity.kt).
- **Verified on-device** (SM-S918U1, dark + light): renders, seeds light up (25% / 4 regions),
  ring sweep animates, tap dialogs work, Yesterday/Today navigation works.
  Screenshots: [`docs/art/screenshots/`](docs/art/screenshots/).

## 2026-10-04 — Phase 22 (feedback round 4, FINAL): explicit stretch confirmation + undo
- **Playback no longer records anything**: finishing a session saves it PENDING
  (`sessions.confirmed = 0`, Room v2→v3 migration + `Migration2To3Test`); no aggregate
  increments, no Tail broadcast, man stays grey at 0%.
- **Explicit confirm** on three surfaces: PlayerScreen confirm card ("Did you complete the
  stretches? Yes, log it / Not now"), video-detail "⏳ Unlogged session" banner (Log it /
  Discard), Body-screen "unlogged stretch session · Review" banner → review sheet with
  per-row Log it/Discard + Log all. Confirm credits the session's START date and stores a
  per-part credited-minutes snapshot (`creditedBodyPartsJson`) for exact undo.
- **Undo per day**: history icon in the Body date-nav row (shown when the viewed day has
  logged sessions) → sheet listing confirmed sessions with Undo (confirm dialog, exact
  aggregate reversal via snapshot, clamped at 0). Tail limitation noted in UI copy.
- **Back-view head fix**: `BackHeadArt.domeH` corrected (525→621 effective) so the back
  figure renders the head/crown tentacles at the SAME scale as the front (pixel-verified:
  470 vs 473 px width at widest row).
- **Tests**: JVM `CreditedPartsTest` (codec + clamp math); instrumented `Migration2To3Test`
  + updated `Migration1To2Test` (full 1→2→3 chain) — 3/3 pass, 0 failures on device.
- E2E verified on SM-S918U1: pending (0%) → Log it (6%, colored) → Undo (0%, clean, fresh DB).
  Screenshots: `docs/art/screenshots/fb4_confirm_*.png`. Details: docs/NOTES.md §26.
