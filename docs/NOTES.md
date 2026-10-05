# Octo — Working Notes (Phase 1)

Supplement to [`ARCHITECTURE.md`](ARCHITECTURE.md). Scratch-level details that don't belong in the main spec.

## 1. Octopus-Man Mascot — Draft Generation Prompt (PPQ / grok-imagine-image-2)

One-off desktop run later (script pattern: `/home/twain/Projects/small_scripts/jjr_icon_gen.py`), PNGs committed into Octo resources. API key read from `ppq_imageGen_apikey.txt` — never copied elsewhere.

**Body-map base (front/back sheet):**

```
Character design sheet, front view and back view side by side of the same
figure: a stylized athletic man whose head is an octopus — smooth rounded
octopus mantle as the skull, two calm eyes, eight short tentacles cascading
from where hair would be, four draping over each shoulder. Human body is
barefoot, arms slightly away from torso, legs straight, symmetrical A-pose.
Flat cel-shaded vector illustration, clean uniform regions, neutral even
lighting, no strong shadows or gradients, plain light-gray background,
full figure visible with generous margin, no text.
```

**Launcher icon:**

```
Flat vector Android app icon, centered on a deep teal background: a friendly
octopus whose eight tentacles curl into a seated lotus yoga pose below its
rounded head, two tentacles resting on the knees in a mudra gesture, minimal
bold shapes, high contrast, subtle stress-relief vibe, no text, no letters,
generous margin.
```

Design constraint driving the prompt: the body map overlays flat color regions in Compose, so art must be cel/vector style with minimal shading.

## 2. Tail Integration — Sender-Side Gotchas (from reading Wags code)

- `sendBroadcast(intent, PERMISSION_TAIL)` throws `SecurityException` on API 34+ when Tail isn't installed — always try/catch and no-op.
- Habit name (not index) is the robust ID; the provider returns names in Tail's user-defined screen order.
- Backfill is SET-not-ADD ⇒ safe to re-run; use it to repair missed days from Octo's session history.
- Sessions+minutes must be one v3 broadcast (`EXTRA_SESSIONS` + `EXTRA_MINUTES`) to avoid lost updates.
- Debug builds interop out of the box (shared `~/.android/debug.keystore`); release needs Tail's keystore.

## 3. oEmbed Fetcher Port Note

`YouTubeMetadataFetcher` (Wags) has no Android dependencies — pure `java.net`. Port as-is, swap the hand-rolled JSON string extraction for kotlinx.serialization (`Json { ignoreUnknownKeys = true }` decode into `@Serializable data class OEmbed(val title: String, val author_name: String)`).

## 4. Timedtext Gotchas

- Non-signed-in watch page sometimes returns a consent wall (EU) → set cookie `CONSENT=YES+cb` and a desktop User-Agent.
- `captionTracks[].baseUrl` requires `&fmt=json3` appended; `srv3` (XML) is the fallback parse.
- ASR tracks: tokens repeat across rolling windows — dedupe by stripping leading words of cue N that match trailing words of cue N−1.
- Videos with captions disabled: fall back to NewPipeExtractor subtitles, else title/description-based extraction, else manual region selection on the body map.

## 5. Version Pins To Use In Phase 2 (starting points)

- Room 2.7.x, Navigation-Compose (BOM-managed), WorkManager 2.10.x, Media3 1.x (latest stable at implementation time), Coil 2.x, kotlinx-serialization 1.7.x, DataStore 1.1.x, OkHttp 4.12.x, `com.github.TeamNewPipe:NewPipeExtractor:v0.24.x` + JitPack repo.
- Add `maven("https://jitpack.io")` to `dependencyResolutionManagement.repositories` in [`settings.gradle.kts`](../settings.gradle.kts) (currently `FAIL_ON_PROJECT_REPOS`, so it must be declared there, not per-module).
- NewPipeExtractor needs coroutines-friendly wrapping (its `getInfo` calls are blocking) — always inside `Dispatchers.IO`.

## 6. Open Items For Later Phases

- Decide manual DI vs Hilt (template currently has neither; Hilt adds kapt/ksp config with Kotlin 2.2.10 — verify plugin compatibility).
- Room schema: export schema ON for migration tests.
- Library export format: JSON with `{videoId, url, title, channel, durationMs, bodyParts[], savedAt}` — Tail-style idempotent re-import.

## 7. Phase 2 Build Notes (2026-10-03)

Result: `./gradlew assembleDebug` **BUILD SUCCESSFUL** (also `:app:compileDebugKotlin`). Network dependency resolution worked; nothing suppressed.

Versions actually pinned in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) (§5 starting points updated to current stable at build time):

| Lib | Pinned | Note vs §5 |
|---|---|---|
| Room | **2.8.2** | §5 said 2.7.x; 2.8.2 is current stable, KSP2-friendly |
| KSP | **2.2.10-2.0.2** | matches Kotlin 2.2.10 exactly |
| Navigation-Compose | **2.9.5** | |
| WorkManager | **2.10.3** | as §5 |
| Media3 | **1.8.0** | exoplayer + ui + datasource-okhttp |
| DataStore | **1.1.7** | as §5 |
| Coil | **2.7.0** (`io.coil-kt:coil-compose`) | as §5 |
| kotlinx-serialization | **1.9.0** + `org.jetbrains.kotlin.plugin.serialization` 2.2.10 | §5 said 1.7.x; 1.9.0 current |
| kotlinx-coroutines | **1.10.2** | |
| Retrofit | **3.0.0** + `converter-kotlinx-serialization` | OkHttp **4.12.0** as §5 |
| material-icons-extended | **1.7.8** | no longer BOM-managed → pinned explicitly |

### Gotchas hit & decisions

- **AGP 9 "built-in Kotlin" vs KSP**: configuration failed with `Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin` because KSP registers its generated dirs on `kotlin.sourceSets`. Fixed with the officially documented `android.disallowKotlinSourceSets=false` in [`gradle.properties`](../gradle.properties). If this is ever removed, Room's KSP step breaks the build.
- **No SQLite `ON CONFLICT DO UPDATE` in DAOs**: minSdk 26 devices run SQLite 3.18; UPSERT syntax needs 3.24+. [`BodyPartDao.increment`](../app/src/main/java/com/example/octo/data/local/BodyPartDao.kt) uses a suspending `@Transaction` read-modify-write instead (Room serializes suspend DAO transactions, so the increment stays atomic).
- **Room schema export ON** → `app/schemas/` (via `ksp { arg("room.schemaLocation", ...) }`), per §6 open item.
- **JitPack repo already added** in [`settings.gradle.kts`](../settings.gradle.kts) `dependencyResolutionManagement` (harmless now, required for Phase 3 NewPipeExtractor).
- **`OctoTheme(dynamicColor = false)`** by default: brand deep-sea/teal/purple palette wins over Material You; pass `true` to restore dynamic color.
- **Manual DI kept** (no Hilt): [`OctoApplication.AppContainer`](../app/src/main/java/com/example/octo/OctoApplication.kt) exposes `database` + `settingsRepository`.
- DownloadState enum persisted via Room's built-in enum↔String conversion — no TypeConverters needed anywhere yet.
- Share-trampoline activity intentionally **not** added (Phase 3, per phase plan).

## 8. Phase 3 Build Notes (2026-10-03)

Result: `./gradlew assembleDebug` **BUILD SUCCESSFUL**; APK installed on 100.65.x device; share-in smoke-tested end-to-end.

### Pins

| Lib | Pinned | Note |
|---|---|---|
| NewPipeExtractor | **v0.26.5** (`com.github.TeamNewPipe:NewPipeExtractor`) | §5 said v0.24.x; v0.26.5 was the latest tag at build time, JitPack-resolved |

### Gotchas hit & decisions

- **`StreamInfo.getDuration()` returns SECONDS** (not ms) — first on-device run wrote duration 0; fixed to a direct `toInt()`.
- **`StreamInfo.getInfo(service, url)`** runs `YoutubeStreamLinkHandlerFactory` internally (the factory lives in the `...services.youtube.linkHandler` subpackage in v0.26.x) — no separate factory call needed in `YoutubeMetaFetcher`.
- **`WorkInfo.inputData` is not populated for observed work infos** on WorkManager 2.10.3 — progress rows are matched to videos via a per-request **videoId tag** instead.
- **`Regex(options = RegexOption)`** needs `setOf(...)` on Kotlin 2.2.10 (internal-constructor ambiguity).
- **`Constraints.setRequiresConnectivity` doesn't exist** → `setRequiredNetworkType(NetworkType.CONNECTED)`.
- **Gradle daemon Metaspace OOM** in KSP once the extractor classpath landed — `org.gradle.jvmargs` now sets `-XX:MaxMetaspaceSize=768m`.
- **Worker DI** via a hand-rolled `OctoWorkerFactory` (`Configuration.Provider` on the Application) — no Hilt.
- **Metadata write-back**: `VideoDownloadManager.download()` returns meta + files in one `Result` so the worker persists title/channel/duration in the same row update as the COMPLETED state (first run only updated file paths).
- **On-device verification (18:07 local):** shared `watch?v=inpok4MKVLM` → row `COMPLETED`, title/channel/duration (317 s) persisted, `files/videos/inpok4MKVLM.mp4` (4.6 MB muxed MP4) + `files/thumbs/inpok4MKVLM.jpg` (76 KB). Nonexistent video id → 3 backoff attempts → row kept as `FAILED` (URL-row persistence requirement holds). Text without a URL → error toast, no row.

## 9. Phase 4 Build Notes (2026-10-03)

Result: `./gradlew assembleDebug` **BUILD SUCCESSFUL**; installed on 100.65.x device (SM-S918U1). Cold launch OK, Octo took foreground focus, no crashes in logcat.

### New files

- `app/src/main/java/com/example/octo/ipc/TailIntegrationClient.kt` — §2 protocol port: provider habit query + ACTION_INCREMENT v3 broadcast → `SendResult {SENT, NO_TAIL, ERROR}`.
- `app/src/main/java/com/example/octo/player/SessionPlayerManager.kt` — local-first source resolution: muxed `<id>.mp4` → `MergingMediaSource(<id>.video, <id>.audio)` → `MissingFiles`; audio focus via `setAudioAttributes(handleAudioFocus = true)`.
- `app/src/main/java/com/example/octo/data/repository/SessionRepository.kt` — `start`/`complete`/`discard`; `complete` = one `db.withTransaction` (session update + per-link `BodyPartDao.increment`).
- `app/src/main/java/com/example/octo/ui/player/PlayerViewModel.kt` — ExoPlayer owner (rotation-safe), play-time-only timer StateFlow, finish pipeline.
- `app/src/main/java/com/example/octo/ui/player/PlayerScreen.kt` — PlayerView + big timer + Finish + Tail feedback + confirm-on-back + KeepScreenOn.

### Decisions

- **Timer counts playing time only** — coroutine ticker started/stopped from `Player.Listener.onIsPlayingChanged`; pausing to rest does not accrue seconds.
- **Rounding:** > 30 s → nearest minute, min 1; ≤ 30 s → 0 minutes, no Tail broadcast; ≤ 30 s back-out discards the session row entirely. Back-out with > 30 s → confirm dialog; "Save and leave" records the session with `completed = false`.
- **Completion flag:** `userPressedFinish || elapsed >= 80% of durationSeconds` (task spec).
- **ONE Tail broadcast per session** (never per body part), extras exactly per §2.2: `EXTRA_HABIT_ID` = settings `tailHabitName` (default "Yoga & Stretch"), `wags_slot` = "YOGA_STRETCH", `EXTRA_MINUTES`, `EXTRA_SESSIONS = 1`, explicit `package = com.example.tail`, guarded by `com.example.tail.permission.TAIL_INTEGRATION`; SecurityException → `NO_TAIL` (graceful local-only path).
- **No streaming fallback:** plain ExoPlayer cannot play canonical YouTube URLs; missing local files → friendly error screen + "Back to Library" (Retry lives in Library). Optional NewPipe-URL streaming deliberately skipped (not trivially available from the current fetcher path).
- **Tail IS installed on the test device** (its floating overlay was visible) and debug keystores are shared, so the real SENT path is expected; not visually confirmed because the device was in active personal use mid-test — interactive playback verification deferred.
- Safety fix during review: `player.release()` on a `lazy` would construct-then-release (or crash via `requireNotNull(video)`) when the player was never created (MissingFiles path) — replaced with a nullable backing field.

## 10. Phase 5 Build Notes (2026-10-03)

Result: `./gradlew assembleDebug` **BUILD SUCCESSFUL**; `installDebug` on 100.65.x device (SM-S918U1); migration + pipeline verified on-device.

### New files

- `youtube/TranscriptFetcher.kt` — §4.3 three-tier fetch: (1) watch-page scrape → brace-matched `ytInitialPlayerResponse` → `captionTracks[]` (manual EN → ASR EN → any) → `&fmt=json3` parse with ASR rolling-window dedupe; (2) NewPipeExtractor `StreamInfo.getSubtitles()` (json3 or srv3-XML); (3) title+description flagged `TITLE_DESCRIPTION`. Null only when all three fail.
- `llm/LlmClient.kt` — OpenAI-compatible `POST {base}/chat/completions`, Bearer auth, `response_format json_object`, temperature 0.2, 60 s call timeout (never inherits the downloader's unbounded client), ~12k-char chunking with union merge, kotlinx.serialization validation, unknown ids dropped; `complete()` powers Settings "Test LLM".
- `llm/LlmHeuristics.kt` — keyword fallback (≥2 cue-phrase hits per part), notes always say "heuristic".
- `llm/BodyPartAnalysisPipeline.kt` — `ensureAnalyzed(videoId, force)`: transcript-if-null → LLM-or-heuristics → transactional cross-ref replace + `AnalysisState.DONE`; RUNNING guard prevents double-trigger; all failures → FAILED on the row, never throws.
- `data/local/VideoBodyPartDao.kt` — `replaceForVideo` in one suspend `@Transaction`.
- `data/local/AnalysisState.kt` — `AnalysisState` + `TranscriptSource` enums.
- `data/local/OctoMigrations.kt` — `MIGRATION_1_2` (three `ALTER TABLE ADD COLUMN`).
- `ui/settings/SettingsViewModel.kt` + rewritten `SettingsScreen.kt` — LLM form (base URL/key w/ show-hide/model), Save, Test-LLM inline result, Tail habit chips (provider query; tap = select+persist); graceful "Tail not installed" state.
- `androidTest/.../Migration1To2Test.kt` — builds a real v1 DB from exported DDL, opens through Room with the migration, asserts row survival + defaults + enum write-back.

### Room v2 migration

`MIGRATION_1_2` (proper `Migration(1,2)`, registered in `DatabaseProvider` — no destructive fallback): `videos` += `transcriptSource TEXT DEFAULT NULL`, `analysisState TEXT NOT NULL DEFAULT 'NONE'`, `analyzedAt INTEGER DEFAULT NULL`. Exported `app/schemas/.../2.json` generated. **On-device verified** by instrumented test: `OK (1 test)` (Room schema validation passed, v1 row preserved, defaults correct). Gotcha: a hand-built v1 fixture must set `PRAGMA user_version = 1` or Room validates it as pre-packaged v0 and fails before migrating.

### On-device pipeline trace (inpok4MKVLM, Goodful meditation — fresh install)

Share-in → download COMPLETED (34 s) → post-download hook auto-ran → logcat: `BodyPartPipeline: Analyzed inpok4MKVLM: 0 parts () via heuristic` → DB: `user_version=2`, `analysisState=DONE`, `transcriptSource=TITLE_DESCRIPTION`, `transcriptText` 328 chars, 0 cross-ref rows (correct: "few body parts expected"; heuristic needs ≥2 keyword hits, meditation prose has none). Note: tier 1 (raw timedtext) fell through to tier 3 on the device — the direct watch-page GET is bot-checked from that network path (NewPipe's client context differs); the documented fallback chain behaved as designed. Test LLM (needs a real key) and interactive Settings/Library UI checks were left to the user — the device was PIN-locked mid-test (same as Phase 4).

### Trigger wiring

1. Post-download: `DownloadVideoWorker` success path → `videoRepository.analyzeVideo(videoId)` (fire-and-forget, never fails the download result).
2. Manual: Library card `⋮` menu → "Analyze" (`LibraryViewModel.analyze`), shown when `analysisState ∈ {NONE, FAILED}`.
3. Auto-on-play: `PlayerViewModel.init` → `ensureAnalyzed` (no-op when DONE, RUNNING-guarded).

## 11. Phase 7 Build Notes (2026-10-03) — Stats tab

Result: `./gradlew assembleDebug testDebugUnitTest installDebug` **BUILD SUCCESSFUL**; installed on 100.65.x device; 16/16 JVM tests green (`StatsMathTest`).

### New files

- `ui/stats/StatsViewModel.kt` — window-selectable aggregates (`flatMapLatest` on the range chip), streaks from 365 days of distinct dates, raw-session sanity totals; same manual-DI `viewModelFactory` pattern as Body/Library.
- `ui/stats/StatsMath.kt` — pure JVM math: current/best streak walk (tolerates "today not yet stretched", same semantics as the dashboard), trend bucketing (daily for 7D/30D, 7-day chunks aligned to end today for 90D, calendar months clamped to data for ALL, totals always preserved), trailing 7-point rolling average, index-bucket downsampling to ≤30 sparkline points.
- `ui/stats/StatsMathTest.kt` — 16 tests: streak edges, downsample identity/total-preservation, rolling-average math, bucket-count/total/label assertions for every range.
- `data/local/StatsAggregates.kt` — Room projection POJOs (`RangeTotalRow`, `PartTotalRow`, `DailyTotalRow`, `SessionRangeTotalRow`); column aliases in the queries must match property names.

### DAO additions (append-only)

- `BodyPartDao.rangeTotals` (`SUM(minutes)`, `SUM(sessions)`, `COUNT(DISTINCT date)`), `partTotalsInRange` (`GROUP BY bodyPartId`), `dailyTotalsInRange` (`GROUP BY date`), `activeDates` (`DISTINCT date`).
- `SessionDao.rangeTotals` — raw `SUM(durationSeconds)` + `COUNT(*)` sanity line ("Cross-check: raw session log says X min across Y sessions").

### UI / decisions

- Screen reuses the dashboard visual language verbatim: `octoRegionColor()` teal→green vs slate bars, mascot header, soft `surfaceVariant` cards; empty state is octopus-themed ("No stats yet — finish a session and your stats will swim in 🐙"); neglected parts appear as "🦑 Stiff tentacles (n)".
- Trend chart + sparklines are pure `Canvas` (no chart dependency); today's bucket is green, purple overlay = 7-day rolling average.
- Tapping a frequency row expands a ≤30-point sparkline of that part's daily minutes.
- **Gotcha (device):** One UI silently swallows `adb shell input tap` over the bottom nav bar (accidental-touch protection) — screenshots were taken by temporarily flipping `OctoNavHost.startDestination` to STATS (reverted in the same commit). Also: the device PIN-locked mid-test, same as Phase 4.
- Screenshots: `docs/art/screenshots/stats_empty.png`, `stats_seeded.png` (seeded via Phase 6 `DevSeedActivity --ei days 7 --ei parts_per_day 6`).

## 12. Phase 8 Build Notes (2026-10-03) — Final integration sweep

Result: `./gradlew assembleDebug testDebugUnitTest installDebug` **BUILD SUCCESSFUL** (3m12s, clean); 16/16 JVM tests green; v1.0.0 installed on 100.65.x (SM-S918U1); no FATAL/AndroidRuntime in logcat across cold start + share-in + worker retries.

### Issues found & fixed during the sweep

1. **Double-complete race in [`PlayerViewModel`](../app/src/main/java/com/example/octo/ui/player/PlayerViewModel.kt)** (real bug): "Save and leave" called `finish()` then navigated back immediately; `finish()` flips UI state inside `viewModelScope`, so `onCleared()`'s safety net could still see the session as unfinished and run `complete()` a second time → **double body-part increments + double Tail broadcast**. Fixed with a synchronous `@Volatile finishHandled` guard set before the coroutine launches; `discardIfBelowThreshold` and `onCleared` now key off the same flag.
2. **Process-death stranding**: a kill during download left rows stuck in PENDING/DOWNLOADING with no live worker, and the player could leave a zero-second session row open. [`OctoApplication.onCreate`](../app/src/main/java/com/example/octo/OctoApplication.kt) now runs a recovery sweep on the application scope: `VideoRepository.resumePendingDownloads()` re-enqueues stuck rows (unique-KEEP makes it idempotent; CONNECTED constraint holds them until network returns) and `SessionRepository.deleteEmptySessions()` drops `completed=0 AND durationSeconds=0` rows.
3. **Tail retry button hack**: Settings used `stringResource(...test_llm).replace("LLM", "Tail")` — replaced with a real `settings_tail_retry` resource.
4. Cleanup: stale "stub" comments removed from [`OctoNavHost`](../app/src/main/java/com/example/octo/ui/navigation/OctoNavHost.kt)/[`LibraryScreen`](../app/src/main/java/com/example/octo/ui/library/LibraryScreen.kt) docs; all eight leftover `.gitkeep` placeholder files deleted (packages have real code).

### New feature: Library backup (URL export/import)

Settings → "Library backup" card:
- **Export** — SAF `CreateDocument("application/json")` → [`VideoRepository.exportLibraryJson()`](../app/src/main/java/com/example/octo/data/repository/VideoRepository.kt) serializes every row (`{videos: [{videoId, url, title, channel, durationSeconds, addedAt}]}` — the §6 open-item format, idempotent).
- **Import** — SAF `OpenDocument` → `importLibraryJson()` restores rows as **STREAM_ONLY** (never auto-downloads; existing videoIds are skipped, blank titles fall back to id, canonical URL derived when missing). Feedback line shows "Imported N new · M already in library". DTOs (`LibraryExport`, `LibraryExportEntry`) are `@Serializable`, covered by the new keep rules.
- Removed the §6 open item "Library export format" — implemented as specced.

### Release-readiness

- [`app/src/main/keepRules/rules.keep`](../app/src/main/keepRules/rules.keep): NewPipeExtractor + JSoup kept whole (reflection-driven parsers), `@Serializable` serializer-companion keeps for `com.example.octo.**` + kotlinx.serialization.json, OkHttp platform `-dontwarn` set. (Plausibility-verified only — no minified release build was run, per Phase 8 constraint.)
- `versionName` → **1.0.0** ([`app/build.gradle.kts`](../app/build.gradle.kts)), confirmed via `dumpsys package`.
- Signing caveat stands (§2.6): release IPC with Tail requires Tail's release keystore.

### On-device E2E (what worked / what couldn't)

- ✅ Cold start + recovery sweep, no crashes; DevSeed → Body dashboard reflects data immediately (25% ring, regions lit, 66 min / 12 sessions / 7-day streak visible — screenshot [`final_flow.png`](art/screenshots/final_flow.png)).
- ✅ Share-in via `am start -a android.intent.action.SEND` → row inserted, worker enqueued, 3-attempt exponential-backoff chain ran, row ended **FAILED with URL preserved** (Play disabled, Retry/Delete offered — screenshot [`final_player.png`](art/screenshots/final_player.png)). DB pulled + inspected to confirm row/aggregate state.
- ❌ **Full download→play→Tail loop not completable this run**: YouTube bot-checks this device's network path for extractor calls (same failure mode as §10; earlier phases verified the COMPLETED path end-to-end on `inpok4MKVLM`). Interactive playback was also blocked by the device being in active personal use (Tail's floating overlay intercepted `input tap` mid-test, same as §9/§11).
- Known limitation retained: direct watch-page transcript GET is bot-checked from this network → tier-3 (title/description) is the commonly-seen source; heuristic classifier needs ≥2 keyword hits.

### Remaining known limitations & future ideas

- Extractor breakage risk tracks NewPipeExtractor releases; bump the JitPack pin when YouTube changes land (FAILED rows recover via Library → Retry).
- No automatic retry of FAILED analysis when a transcript becomes available later; the card ⋮ → Analyze action covers it manually.
- Future: per-video quality preference, background periodic Tail backfill via `ACTION_SET_HABIT_VALUES` (§2.3 — client constant already present, sender-side backfill deliberately deferred), OPML/CSV export alongside JSON.

## 13. Phase 11 Build Notes (2026-10-03) — Body-part detail screen

Result: `./gradlew assembleDebug testDebugUnitTest installDebug` **BUILD SUCCESSFUL**; 16/16 JVM tests green; installed on 100.65.x device. Screenshots skipped — device was in active personal use (chess app foreground), so no DevSeed injection this run.

### New files

- `ui/part/BodyPartDetailScreen.kt` — "part/{bodyPartId}" screen: header card (display name + `octoRegionColor` accent dot + all-time min/sessions), compact Library-style video cards (thumbnail, title, channel, duration, download-state badge, ≤4 body-part chips + "+n"), stats card (7D/30D/90D/ALL FilterChips, window totals, pure-Canvas bucketed trend chart, best-day/longest-gap records), capped history list (date + minutes + sessions + colored dot). Empty states: "No videos stretch this yet — analyze a video that targets it 🐙" / "Nothing logged yet 🐙".
- `ui/part/BodyPartDetailViewModel.kt` — one `combine` over (range chip, all-time per-day rows, all-time totals, history, videos); window math is client-side filtering of the part's full per-day series (small data; identical totals to SQL), trend bucketing reuses `StatsMath.bucketDaily` (weekly rollup for 90D etc.). Route arg passed as a plain constructor param via a per-entry `factory(partId)` — this lifecycle version lacks `CreationExtras.createSavedStateHandle()`, so SavedStateHandle was avoided deliberately.

### Changed files

- `ui/navigation/OctoDestinations.kt` — added `PART_ROUTE_PATTERN = "part/{bodyPartId}"` + `PART_ROUTE_BASE`.
- `MainActivity.kt` — bottom bar hidden on `part/` destinations too (same rule as `player/`).
- `ui/navigation/OctoNavHost.kt` — part route registered; Body dashboard wired with `onOpenPart` → `navigate("part/{id}")`; part-screen video taps → `player/{videoId}` for now (Phase 12's `video/{videoId}` will become the target — no stub route added; direct-to-player keeps the nav graph clean).
- `ui/body/BodyScreen.kt` — region taps (figure + legend chips) now NAVIGATE instead of opening the Phase-10 `RegionDialog` (dialog composable + state removed).

### DAO additions (append-only, no schema change)

- `BodyPartDao.allTimeTotalsFor(partId)` — SUM/COUNT for one part.
- `BodyPartDao.forPartForRange(partId, from, to)` — per-day `DailyTotalRow` series for one part.
- `BodyPartDao.dailyRowsForPart(partId, limit)` — history rows, `ORDER BY date DESC LIMIT :limit` (SQL-side cap of 60).
- `VideoBodyPartDao.getVideosForPart(partId)` — `@Transaction` subquery join returning `VideoWithBodyParts` (relation fills the other tags).

## 14. Phase 12 Build Notes (2026-10-03) — Video detail screen

Result: `./gradlew assembleDebug testDebugUnitTest` **BUILD SUCCESSFUL** (16/16 JVM tests green); `installDebug` OK on 100.65.x device. Screenshots skipped — chess app foreground again (no injection runs).

### New files

- `ui/video/VideoDetailScreen.kt` — "video/{videoId}" screen: meta row (channel · duration · download-state chip · "Analyzing…" chip while RUNNING), 16:9 mini-player card (`PlayerView useController=false` + center play/pause overlay + Replay/from-start + Fullscreen icons; MissingFiles → 🐢 friendly error + Retry-download when FAILED), mini octopus-man card (legend: green "Already today" / amber "This video") and tappable body-part chips → `part/{id}`. Content in a `verticalScroll` column (scrolls only on small screens/overflow).
- `ui/video/VideoDetailViewModel.kt` — one `combine` over (video row + links via new DAO flow, today's `bodyPartDao.forDay` rows, isPlaying flag). `videoTargets` = parts with `analysisState DONE` cross-refs minus parts already stretched today (empty unless DONE). Owns a LIGHTWEIGHT mini ExoPlayer (`SessionPlayerManager` reuse; no session row, no timer, no Tail — that stays on the full player). `obtainPlayer()` is lazy/re-creatable after release.

### Dual-player safety (decision)

VideoDetail and PlayerScreen each own their own ExoPlayer; they never coexist playing. Leaving video detail (back OR Maximize → `player/{videoId}`) disposes the composable, whose `DisposableEffect` calls `viewModel.releaseMiniPlayer()` (release + null; `onCleared` is the final net). The full player then creates its own instance with exclusive audio; returning to detail re-creates the mini player on demand via `obtainPlayer()`. `PlayerScreen`/`PlayerViewModel` untouched — timer/Tail/finish behavior unchanged.

### Changed files

- `ui/body/OctopusBodyView.kt` — optional `videoTargets: Set<BodyPart>` param: target regions not stretched today render in new `OctoBodyAmber` (0xFFFFB84D) with the same glow; all existing call sites unchanged (default empty set, zero regression).
- `ui/navigation/OctoDestinations.kt` — `VIDEO_ROUTE_PATTERN`/`VIDEO_ROUTE_BASE`.
- `MainActivity.kt` — bottom bar also hidden on `video/` routes.
- `ui/navigation/OctoNavHost.kt` — `video/{videoId}` route (Maximize → pushes `player/{videoId}` on top, back returns to detail); Library card/Play taps → `video/{videoId}`; BodyPartDetail video taps retargeted from `player/` → `video/` (Phase 11 TODO resolved).
- `res/values/strings.xml` — video_detail_* strings.
- `data/local/VideoDao.kt` — added `observeWithBodyPartsById(videoId)` Flow query (append-only; no schema change).

### Known trade-offs

- The mini player restarts from the beginning when re-entering detail after Maximize (no position hand-off; full player owns session continuity).
- Today-coloring on detail refreshes live via the Room flow — finishing a session on the full player and backing out to detail immediately shows the newly-green regions (WhileSubscribed(5s) resubscribe on return).

## 15. Phase 13 — Final integration sweep (feedback round 2, 2026-10-03)

### Seam review (Phases 9–12) — findings

Reviewed: nav-bar hiding vs back stack, Library Play vs card taps, mini-player/full-player lifecycles, BodyScreen tap wiring, Phase 10 geometry vs Phase 12 `videoTargets` rendering, `octoRegionColor` reuse. **No functional integration bugs found** — the seams are clean:

- Bar hiding covers all three detail-route prefixes (`player/`, `part/`, `video/`); Library→video→player back stack pops cleanly and the bar reappears on tab roots (verified on device).
- Library card is not itself clickable, so the Play button can't double-navigate; BodyPartDetail cards use a single `clickable`.
- Mini player is released via `DisposableEffect` on any dispose (back OR Maximize→player) and recreated lazily by `obtainPlayer()` on return; `onCleared` is the final net. No audio overlap (audio_flinger showed exactly one active Octo track in the full player).

Fixes made (polish, not bugs):

1. `LibraryScreen.kt` — collapsed a duplicated Play-button if/else that differed only in `enabled`.
2. `BodyPartDetailScreen.kt` — all hardcoded UI strings moved to `strings.xml` (`part_detail_*`; download badges now reuse `library_state_*`), and the back arrow switched from `KeyboardArrowLeft` to the app-standard AutoMirrored `ArrowBack` (matches VideoDetail/Player).
3. Dark/light amber: `OctoBodyAmber` (0xFFFFB84D) verified rendering in BOTH themes on-device (pixel-sampled screenshots); bright enough on the dark Abyss surface, mid-tone against the light Foam surface with the white target border.

### Verification status per feedback item

| # | Item | Status |
|---|------|--------|
| 1 | Bottom buttons work (single nav controller) | **verified-on-device** (all 4 tabs switched; bar reappears on tab roots) |
| 2 | Octopus-themed bottom bar | **verified-on-device** (visible on all tab roots, hidden on all detail routes) |
| 3 | Smaller man, no scrolling | **verified-on-device** (Body root dump: figure + 4 stat chips + 16 legend chips, no scroll) |
| 4 | Man = octopus art (8 head arms, tentacle fingers/toes) | **code-verified** (Phase 10 geometry regenerated; rendering confirmed via dashboard screenshot) |
| 5 | App icon = head/octopus arms only | **code-verified** (Phase 10 regenerated webps; unchanged this phase) |
| 6 | Body part tap → part screen | **verified-on-device** (Chest chip → part screen: header 38 min/4 sessions, trend chart, history rows, video card) |
| 7 | Video tap → video screen (mini player + green/amber mini-man + Maximize) | **verified-on-device** (Library Play → detail; legend "Already today"/"This video"; Maximize → full player timer 00:07 running with active audio track; back ×3 → Body) |

### E2E evidence

- Device was free (Octo itself foreground); seeded via `DevSeedActivity` (3 days × 4 parts) and made the existing library video playable via a sqlite edit (downloadState=COMPLETED, analysisState=DONE, 3 cross-refs, ffmpeg testsrc MP4 pushed into `files/videos/`) — a debug-only test fixture.
- Flow executed: Body → Chest chip → part → video card → video detail → Maximize → player (played) → back ×3 → Body → all 4 tabs. **Zero crashes** in logcat (FATAL/AndroidRuntime scan clean; uiautomator boot noise only).
- Screenshots: `docs/art/screenshots/e2e_part.png`, `e2e_video.png`, `e2e_player.png`, `e2e_video_dark.png`, `e2e_library_dark.png`.

### Build

`./gradlew assembleDebug testDebugUnitTest` — **BUILD SUCCESSFUL**, no new compiler warnings.

### Remaining limitations

- Mini-player position is not handed off on Maximize (restarts from 0 on return) — accepted trade-off from Phase 12.
- The E2E used a synthetic 30 s testsrc MP4; real YouTube download/analysis pipelines were verified in earlier phases, not re-run here.
- BodyScreen legend "Today" button and empty-hint remain hardcoded English (screen predates the string pass; all Phase 11/12 screens are localized).

## 19. Phase 15 — Feedback Round 3: Settings popup picker, z.ai default, portrait lock (2026-10-04)

### A. Tail habit picker → searchable popup dialog

- Inline habit chips are gone from the main settings screen. The Tail card is now one compact
  row: label + currently-selected habit (ellipsis-capped) + chevron
  (`SettingsScreen.kt` `TailHabitRow`).
- Tapping opens an `AlertDialog` (chosen over `ModalBottomSheet` for search-field keyboard
  ergonomics): `OutlinedTextField` search on top (leading `Search` icon), below it a
  `LazyColumn` of habits filtered live with case-insensitive `contains`; list height capped
  at ~50% of screen height (`heightIn(max = 0.5 × screenHeightDp)`; dialog chrome keeps the
  whole popup within the ~60% budget). Tapping a habit persists via
  `SettingsRepository.setTailHabitName` and dismisses.
- States: centered spinner + "Loading Tail habits…" while the provider query is in flight;
  "No habits match" for a filtered-empty list; "Tail not installed …" + Retry button when
  the provider returns nothing (Tail absent / unreachable — `queryHabits()` fail-safe).
- Selection row uses `ListItem` (check icon on the current selection, transparent container).

### B. LLM defaults → z.ai (provider-agnostic design kept)

- `SettingsRepository.DEFAULT_LLM_BASE_URL` = `https://api.z.ai/api/paas/v4`
  (Z.ai's documented OpenAI-compatible chat-completions base);
  `DEFAULT_LLM_MODEL` = `glm-4.6` — chosen over `glm-4.5-air` because `glm-4.6` is
  confirmed as a live chat model id on that endpoint (Z.ai docs + multiple GLM Coding Plan
  references). Only the defaults moved; `LlmClient` stays endpoint-agnostic.
- **Migration:** DataStore defaults only apply to fresh installs, so
  `SettingsRepository.runLegacyDefaultsMigration()` runs once from `OctoApplication.onCreate`
  (idempotent per process): if the stored base URL is unset or exactly the legacy
  `https://api.openai.com/v1`, it's overwritten with the z.ai URL; same one-shot rule for
  `gpt-4o-mini` → `glm-4.6`. Any user-customized value (different base URL or model) is
  left untouched.

### C. Portrait lock on all screens

- Manifest: `android:screenOrientation="portrait"` on **all three** activities
  (`MainActivity`, `ShareIngestActivity`, `DevSeedActivity`) +
  `android:resizeableActivity="false"` on `<application>` — required on Android 12+,
  which otherwise ignores per-activity orientation on large screens (≥600dp). Trade-off
  noted: no split-screen/multi-window. No `configChanges` anywhere, so rotation would
  normally recreate activities — now it can't happen on phones.
- Runtime belt-and-braces: `MainActivity.onCreate` sets
  `requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT` before `setContent`.
- Verified via `adb shell settings put system accelerometer_rotation 0` +
  `user_rotation 1`: no layout change (screenshots below).

## 20. Phase 16 — Feedback Round 3 FINAL: placement polish + fresh-install data wipe (2026-10-04)

### A. Body dashboard placement (screenshot-driven iteration)

- **Before** (`docs/art/screenshots/fb3_final_dashboard_before.png`,
  `fb3_final_dashboard_before_seeded.png`): the octopus-man filled the entire
  `weight(1f)` flex area — hair hugging the screen edges, ring top nearly touching
  the title row, feet reaching the bottom nav, the 0%/25% badge overlapping the head
  hair, corner glyph numbers floating close under the ring's lower arc.
- **Change** (`BodyScreen.kt`): the inner figure box is now
  `Modifier.fillMaxHeight(0.92f).padding(horizontal = 12.dp)` inside the flex box, so
  the `aspectRatio(1000/1500)` figure floats centered with ~4% air top/bottom and
  12dp side insets; `PercentRing` hugs the smaller figure (still `matchParentSize`),
  the badge rides the ring (not the hair), and `CornerStats` moved out to
  `bottom = 12.dp, end = 16.dp` so the minutes/streak glyphs sit in their own
  clear zone right of the ring. Title row top padding 6→8dp for a consistent
  8/12/16dp margin rhythm.
- **After** (`fb3_final_dashboard_after.png`, `fb3_fresh_body.png`): verified —
  no edge collisions, ring proportional, badge clear of the head, glyphs legible
  and separate; the empty state reads airy and the seeded 25% state reads balanced.

### B. Part-screen header compaction

- **Before** (`fb3_final_part_before.png` / `e2e_part.png`): a full `Card` header
  duplicated the part name in `titleLarge` under the TopAppBar ("Quads" twice) and
  burned ~90dp of prime list space.
- **Change** (`BodyPartDetailScreen.kt` `HeaderCard`): now ONE compact row — the
  10dp accent dot (same `octoRegionColor` scale) + the all-time totals line on a
  35%-alpha `surfaceVariant` pill with 12/8dp padding; the TopAppBar remains the
  single title. There is no octopus-man figure on this screen by design; the dot
  stays as the small visual anchor.
- **After** (`fb3_final_part_after.png`): content starts visibly higher; stats
  card, chart and history all gain the reclaimed space.

### C. Fresh-install data wipe (targeted, settings preserved)

- Procedure (no `pm clear`, so DataStore settings survive):
  1. `adb shell am force-stop com.example.octo`
  2. `run-as com.example.octo rm -f databases/octo.db{,-shm,-wal}` (Room recreates
     an empty schema on next launch)
  3. `run-as ... rm -rf files/videos files/thumbs && mkdir` both back
- Verified: after relaunch `databases/octo.db` recreated; Library shows the
  "Your library is empty" state (`fb3_fresh_library.png`); Body tab 0%, no glyphs,
  empty hint (`fb3_fresh_body.png`); Stats shows "No stats yet" (`fb3_fresh_stats.png`);
  `files/datastore/octo_settings.preferences_pb` (72 B) untouched → z.ai
  base URL/model + Tail habit choice intact.
- All four content tables (`videos`, `sessions`, `body_part_stretch`,
  `video_body_part_cross_ref`) are empty in the recreated DB.

### D. DevSeed release-safety

- Confirmed `DevSeedActivity` is release-safe without changes: the `onCreate`
  guard `if (!BuildConfig.DEBUG) { finish(); return }` makes it a no-op trampoline
  in release builds; manifest entry is unchanged (debug guard is the gate; also
  `noHistory` + `excludeFromRecents`). Kept for future dev use.

### E. Build

- `./gradlew assembleDebug testDebugUnitTest` green; `installDebug` deployed;
  device left in fresh state (Body tab, empty data, settings intact).

## 21. Phase 17 — App-icon continuity: icon IS the on-screen man's head (2026-10-04)

User ask: the launcher icon must be the HEAD of the same octopus-man shown on
the Body dashboard — not different AI art. One character everywhere.

### A. Vector export (no AI art anymore)

- New `scripts/gen_icon_head.py` imports `gen_body_geometry` (the module that
  also emits `OctopusBodyGeometry.kt`) and renders ONLY the head: the `NECK_L`
  dome path (the head region in the 16-region taxonomy), the face (eyes,
  pupils, highlights, blush, smile — identical coordinates), the 8 decorative
  head tentacles (`HEAD_TENTACLES`, same centerlines/curls) and their suction
  cups (same `cups_along` ts/radii). Zero geometry changes; 16-region taxonomy
  untouched.
- Palette copied from `OctopusBodyView.kt` idle state: dome #43585F, tentacle
  deco #32444C, foam #D8E8E6, ink #0D2228, blush #9C70C8, sheen #82D9CD,
  abyss #081C22. Icon-only legibility tweaks (same shapes, bolder strokes):
  border 2.5u→8u @ 0.40 alpha, smile 5u→8u, sheen 9u→12u, cups ×1.3.
- Render: PIL at 4× supersampling, draw order mirrors the Compose view
  (tentacles behind dome, face/cups on top). Content fitted so its bounding
  circle sits inside the 66/108 adaptive safe area (mask-safe on circle and
  squircle launchers). Master: `docs/art/octo_head_vector.png` (1024px,
  transparent).
- Verification previews emitted by the script into
  `docs/art/screenshots/`: `icon_continuity_side_by_side.png` (on-screen man
  vs icon head), `icon_continuity_mask_matrix.png` (circle + squircle masks
  with the 66dp safe ring), `icon_continuity_icon_circle.png`.

### B. Asset pipeline (reuse/rewrite of make_icon_art.py)

- `scripts/make_icon_art.py` rewritten: consumes the vector master instead of
  `octo_head_raw.png` AI art. Emits `drawable-nodpi/ic_launcher_foreground.png`
  (432px, transparent, head inside safe circle), all five density webps
  (square + circle-masked round) and `drawable-nodpi/octo_mascot.png` (256px,
  abyss plate) — the mascot is now the SAME head render, so the dashboard
  header/empty states and the launcher icon are literally one character.
- `ic_launcher_background.xml` (abyss + teal glow vector) and the adaptive
  icon XMLs: unchanged.

### C. Verification

- Side-by-side: dome shape, eye placement, 8-tentacle drape with curls,
  suction cups, blush/smile/sheen — all identical between dashboard figure,
  header mascot and icon render (same source paths by construction).
- Device (SM-S918U1): `installDebug` OK; dashboard screenshot
  `icon_continuity_dashboard.png` shows the new mascot in the header matching
  the figure below it; `icon_continuity_final.png` = dashboard man vs header
  mascot vs launcher icon render. Launcher drawer itself not screenshotted
  (user's personal home; etiquette) — icon render + assets_check verified
  instead.

### D. Build

- `./gradlew assembleDebug` green (8s); `installDebug` deployed to
  SM-S918U1. First install attempt failed transiently (device link), second
  succeeded ("Installed on 1 device").

## 22. Phase 18 — CORRECTION: PPQ head art restored + worn by the on-screen man (2026-10-04)

User correction of Phase 17: they LIKED the Phase-10 PPQ-generated octopus-head
art as the launcher icon and wanted it ON the vector man's dome — Phase 17 had
done the exact opposite (vector head everywhere). No git history exists, so
`docs/art/octo_head_raw.png` (1408px PPQ grok-imagine render) is the source of
truth; Phase 17's vector exports are archived (`docs/art/octo_head_vector.png`,
unused in `res/`).

### A. Asset pipeline (scripts/make_icon_art.py rewritten)

- Local background removal (numpy/scipy, no PPQ re-gen): flat-navy chroma key
  → silhouette-first strategy (the pupils/brows are the SAME color as the BG;
  `binary_fill_holes` restores them) → hole classification (sea pockets below
  the 0.52-row gate are punched out; pupils kept) → crevice pass between the
  central tentacles → bokeh-dot rejection by size/contrast → 2px feather.
  Transparent master archived at `docs/art/octo_head_cutout.png`.
- Emits: `drawable-nodpi/ic_launcher_foreground.png` (cutout in the 66/108 safe
  circle), all five mipmap densities (square = full-bleed raw PPQ plate, round =
  circle mask), `drawable-nodpi/octo_mascot.png` (256px raw plate — the exact
  pre-Phase-17 look), and NEW `drawable-nodpi/octo_head_ppq.png` (in-app head).
- Adaptive-icon XMLs + `ic_launcher_background.xml` untouched.

### B. Head composited onto the vector man (OctopusBodyView.kt)

- The idle NECK dome fill, the vector face (eyes/pupils/blush/smile/sheen), the
  8 head hair-tentacles and head-zone suction cups are no longer drawn; the PPQ
  art is `drawImage`d over the dome instead (uniform scale, dome-center
  alignment; placement constants documented in the file with re-measure
  instructions for regenerated art). Body regions, finger-tentacle limbs and
  muscle lines stay vector.
- FEEDBACK ROUND: tentacles below the dome are semi-transparent (40% + 60px
  feather) in `octo_head_ppq.png` ONLY (`make_on_body_head`), so the vector
  neck/shoulders show through the drape on every screen that shows the man
  (dashboard + video-detail mini-man share the view). Launcher assets stay
  opaque.
- Hit-testing preserved: the art is drawn inside the Canvas draw block (no
  pointer-consuming node) and the NECK region path remains in the hit-test
  list (skipped only when idle-drawn) — device-verified: tapping the head
  opened the Neck part screen.
- `gen_body_geometry.py` + generated `OctopusBodyGeometry.kt` doc the new
  contract: geometry stays COMPLETE (archive + 16-region hit paths), the VIEW
  skips dome face/tentacles.

### C. Verification

- Device (SM-S918U1): `installDebug` OK; DB wiped via `pm clear` → fresh/empty
  (0% ring). Dashboard screenshots in `docs/art/screenshots/icon_fix_*.png`
  (empty, seeded, fresh-empty): PPQ head on the vector body, neck/shoulders
  visible through the semi-transparent tentacles, no face remnants, no double
  tentacles. Hit-test evidence: `icon_fix_neck_tap.png` (head tap → Neck part
  screen). The video-detail mini-man uses the same OctopusBodyView, so the
  compositing is identical by construction (its dedicated screenshot pass was
  interrupted by foreground app switches on the personal device).
- Icon continuity: `icon_fix_icon_masked.png` (masked adaptive render vs raw
  PPQ plate) — identical character.
- `./gradlew compileDebugKotlin testDebugUnitTest` green.

## 23. Phase 19 — Under-analysis fix: signed innertube caption tier + recall-oriented classification (2026-10-04)

### A. Diagnosis (short BOoYU6keLL4, "5 Exercises To Unlock Shoulders & Fix Pelvic Tilt FAST!")

- **Path that ran**: heuristics — DataStore (`run-as … cat files/datastore/octo_settings.preferences_pb`)
  has `llm_base_url` (api.z.ai) + `llm_model` (glm-4.6) but NO `llm_api_key`, so
  `BodyPartAnalysisPipeline` took the `LlmHeuristics` branch.
- **Tier that ran**: 3 (TITLE_DESCRIPTION). DB row before fix: transcript 81 chars = title only
  (Shorts description is empty) → heuristics with MIN_HITS=2 matched only "shoulder"×2 →
  DB cross-ref = `['shoulders']`. Exactly the user's report.
- **Why tiers 1/2 failed**: the short HAS an EN-ASR caption track, but the desktop watch-page
  scrape yields a caption baseUrl WITHOUT signature params; the timedtext endpoint answers
  **HTTP 200 with an empty body** → tier 1 returns null-ish, NewPipe tier 2 likewise; chain
  fell to tier 3. Verified live from the host (curl: same UA/headers as the app).
- **Ground truth** (yt-dlp auto-subs, investigation-only): ASR transcript covers lower belly
  (core), hamstrings, pelvic tilt (hips + lower back), lower back tension, chest opener,
  shoulder opener → **{shoulders, chest, core, hamstrings, hips_glutes, lower_back}**.

### B. Changes

1. **TranscriptFetcher — new tier 1b (innertube)**: POST `youtubei/v1/player` with the
   ANDROID client (key/UA pinned); its `captionTracks[].baseUrl` is SIGNED and actually
   returns captions. Body is json3 OR srv3 XML regardless of `&fmt=json3`, so
   `parseCaptionBody` dispatches on the first non-space char; `parseXmlTranscript` now also
   handles srv3 `<p><s>` word lists (joined with spaces before tag strip).
2. **Tier 3 enrichment**: title + full description + `Tags:` line (innertube
   `videoDetails.keywords`, NewPipe `StreamInfo.tags` fallback) — no extra HTTP call when the
   tier-1b response is already in hand.
3. **LlmClient prompt (recall-oriented)**: transcript mode now asks for EVERY part
   stretched/moved/exercised AT ALL, including brief drills; title/description mode asks the
   model to INFER typical body parts for the named drill type ("recall > precision"),
   with the canonical-id guardrail and unknown-id dropping unchanged.
4. **LlmHeuristics**: synonym expansion (traps→shoulders, pecs→chest, obliques/lats,
   adductors/inner thigh, glutes/butt, calves, ankles/feet/plantar, wrists/forearms,
   biceps/triceps, neck, pelvic tilt→hips+lower_back, etc.); adaptive threshold — thin text
   (< LONG_TEXT_CHARS = 1000, i.e. title/description-only) needs 1 hit, real transcripts keep
   MIN_HITS=2; `phraseImplications` (full body / morning stretch / sun salutation /
   pelvic tilt) seed multi-part sets; thin-text results carry lower confidence (0.3) and a
   "thin text" note. 16-part taxonomy untouched.
5. New unit test `app/src/test/java/com/example/octo/llm/LlmHeuristicsTest.kt` (7 cases:
   exact ground-truth set on the verbatim ASR transcript, old 81-char title case,
   meditation precision, long-text single-mention guard, synonyms, full-body implication,
   thin-text confidence flag).

### C. Verification (real device, SM-S918U1)

- `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:installDebug` — all green.
- Old row deleted via Library card menu; short re-added via Add-URL dialog.
- **Before**: `TITLE_DESCRIPTION` / 81 chars / parts = `[shoulders]`.
- **After** (DB dump): `transcriptSource=CAPTIONS`, transcript 411 chars = verbatim ASR
  (tier 1b engaged on-device), parts = **[chest, core, hamstrings, hips_glutes,
  lower_back, shoulders]** — 6/6 ground truth.
- **Precision sanity** (Goodful meditation inpok4MKVLM added the same way): CAPTIONS tier,
  parts = `[ankles_feet]` only (legit "feet/toes" cues in the guidance) — no over-tagging.
- Host-side replication of the exact tier chain + heuristic rules against the live caption
  file produced the same 6-part set before the on-device run.

### D. Notes

- Chunking untouched: 411-char transcript = single chunk (CHUNK_CHAR_LIMIT 12 000).
- On-device ASR caption fetch worked with the YouTube-Android UA; the desktop-UA fetch of
  the same signed URL also works from the host, but the app keeps the innertube UA for
  consistency with the player call.

## 24. Phase 20 — Feedback Round 4: back view of the octopus-man (2026-10-04)

### A. Side map — where each of the 16 parts lives (canonical reference)

| Part | Front | Back | Notes (back-view placement) |
|---|---|---|---|
| neck | ✔ | ✔ | same dome + PPQ head art composited identically |
| shoulders | ✔ | ✔ | rear delt caps (same shape) |
| chest | ✔ | ✘ | absent from the back figure |
| upper_back | ✔ (rim crescents) | ✔ | big trapezius/shoulder-blade center plate |
| lower_back | ✔ (rim crescent) | ✔ | center plate tapering to the hips |
| core | ✔ | ✘ | absent from the back figure |
| obliques | ✔ | ✔ | back flanks bridging lats → glutes |
| lats | ✔ (side panel) | ✔ | wide panels armpit → waist (star of the back) |
| hips_glutes | ✔ (hips plate) | ✔ | same pelvis silhouette = glutes, glute-split stroke |
| groin_adductors | ✔ | ✘ | absent from the back figure |
| quads | ✔ | ✘ | absent (hamstrings column covers the thigh from behind) |
| hamstrings | ✔ (thin strip) | ✔ | the leading thigh column from behind (quad-sized) |
| calves | ✔ | ✔ | same columns, suction cups |
| ankles_feet | ✔ | ✔ | same columns + finger clusters (heels) |
| biceps_triceps | ✔ | ✔ | triceps side of the same arm column |
| wrists_forearms | ✔ | ✔ | arms hang down, same column |

Front-only parts are simply ABSENT from the back figure (no dimmed ghost); the
parts sheet / legend stays global (all 16 listed). Every part appears on at
least one side; the dashboard ring keeps counting all 16 parts on both sides.

### B. Implementation

- `scripts/gen_body_geometry.py`: new `BACK_REGION_DEFS` (12 regions) +
  `BACK_MUSCLE_LINES` (spine groove, shoulder-blade curves, glute split) +
  back PIL preview `docs/art/body_map_preview_back.png`. Same 1000×1500
  viewBox, same limbs/tentacles/head decorations reused.
- `OctopusBodyGeometry.kt` (regenerated, 80 KiB): `Side` enum, `backRegions`,
  `backHitTestOrder`, `backMuscleLines`, `regionsFor(side)`,
  `muscleLinesFor(side)`.
- `OctopusBodyView`: new `side: Side = FRONT` parameter (all existing call
  sites unchanged); paths/regions/colors are remembered per side, so
  hit-testing and the amber video-target overlay only ever apply to parts
  visible on the rendered side (front-only parts can never be amber on the
  back figure because their regions don't exist there).
- `BodyScreen` (home): swipe left/right on the figure turns him around — one
  absolute `Animatable` angle drives `graphicsLayer { rotationY }` (spring,
  damping 0.72) and the content swaps at ±90° (the side far side of the turn
  renders un-mirrored). Tappability/region taps pass through to the visible
  side. Ring, % badge, corner stat glyphs stay visible on both sides. A
  "Front ● / Back ●" indicator pill under the figure doubles as a tap toggle.
- `VideoDetailScreen`: mini body-map card now shows TWO figures side by side
  (front left, back right, weight(1f) each); the amber `videoTargets` set is
  computed once and passed to both; legend unchanged.

### C. Verification

- Build: `./gradlew compileDebugKotlin` + `installDebug` green (SM-S918U1).
- PIL back preview probe-checked: all 12 back regions fill at expected
  coordinates; spine/glute strokes render at center; front-only plates absent.
- On-device: swipe flip captured (`fb4_back_dashboard_front.png`,
  `fb4_back_dashboard_midturn.png` — mid-animation squash visible,
  `fb4_back_dashboard_back.png`); pixel diff front↔back = 14% of the screen
  across the figure bbox, and the indicator pill's teal label moved from
  "Front" to "Back". Tapping lower_back on the BACK figure navigated to the
  part detail screen (`fb4_back_part_after_tap.png`, 70% layout change).
- `fb4_video_pair.png` could not be captured: the device was in active use
  (foreground chess app blocks screenshots mid-run). The pair is the same
  proven `OctopusBodyView` in a Row (front variant device-proven since
  Phase 12; back variant device-proven on the dashboard) — compile- and
  build-verified only.

## 25. Phase 21 — Feedback Round 4: library search/filter + compact cards + true fullscreen playback (2026-10-04)

### A. Problem

1. Library of >a few videos was not searchable/filterable by body part.
2. Library cards had a full-width 180dp banner thumbnail + text buttons — huge
   vertical footprint per row.
3. The player never actually went fullscreen: status + navigation bars stayed
   visible, top bar + timer column ate the viewport, and the video letterboxed.

### B. Implementation

- **Library filter (item A)** — chose a horizontal scrollable FilterChip row over
  the bottom-sheet pattern: one tap to filter vs. sheet-open→scroll→tap, and the
  selected teal chips stay visible as an active-filter affordance. 16 `BodyPart`
  chips, multi-select with **OR** semantics within parts, **AND** against the
  search text (case-insensitive contains over title + channel). Filtering is
  client-side: `LibraryViewModel` gained `searchQuery`/`selectedParts`
  MutableStateFlows and a `filteredItems` StateFlow combined over the existing
  Room flow — no new DAO, no new cross-ref query. Filter state lives in the
  ViewModel so it survives navigation away and back. A leading "Clear" chip
  appears when ≥1 part is selected; empty results show a 🦑 "No videos match"
  state with a Clear button.
- **Compact cards (item B)** — banner → leading 96×64dp rounded thumbnail, title
  `bodyMedium` 2 lines, single meta row (`channel · duration` + tiny tinted state
  label replacing the AssistChip), body parts collapsed to ≤3 tiny surfaceVariant
  pills + "+n", download progress as a hairline bar, and full-width Play/Retry
  text buttons → 36dp icon column (play/retry + overflow menu with
  Analyze/Delete). 8dp card padding, 8dp list spacing. Full part chips remain on
  the video detail screen. Footprint roughly halved (~80dp content vs ~340dp
  before for a typical analyzed card).
- **True fullscreen player (item C)** — while a video surface is up the screen
  enters immersive: `WindowCompat.setDecorFitsSystemWindows(false)` +
  `WindowInsetsControllerCompat.hide(systemBars())` with
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, all inside a `DisposableEffect` keyed
  on (immersive, surfaceReady) that restores bars + decor-fits on dispose (also
  when switching to the Finished/MissingFiles views). Default resize mode is
  `RESIZE_MODE_ZOOM` (portrait viewport fully covered, 16:9 video center-cropped);
  an AspectRatio toggle in the overlay switches to `RESIZE_MODE_FIT` (letterbox).
  No landscape — portrait lock is by design. Stock PlayerView controller is off;
  tap reveals a scrim overlay with back, title+timer, resize toggle,
  exit-fullscreen and Finish Session. Keep-screen-on + confirm-on-back behavior
  unchanged; system back first exits fullscreen, then runs the usual exit flow.

### C. Verification

- `./gradlew assembleDebug testDebugUnitTest` green; `installDebug` on SM-S918U1.
- Screenshots: `fb4_library_filtered.png` (part filter + search text active),
  `fb4_library_compact.png`, `fb4_player_fullscreen.png` (status + nav bars
  hidden — verified visually). See docs/art/screenshots/.

### D. Addendum (mid-phase user request): distinct BACK head art

The user asked that the man actually LOOK different from the back (Phase 20 had
reused the front PPQ face art on both sides). New pipeline, same PPQ Grok-Imagine
API as Phase 10/18:

- `scripts/gen_head_art_back.py` — generates `docs/art/octo_head_back_raw.png`
  (back view: large smooth teal mantle, NO face, tentacles draping the sides).
  First render had a small mantle (dome 332px → scaled art would overflow the
  1000px viewBox and clip tentacles at the canvas edge); regenerated with a
  "mantle fills upper two thirds" prompt → dome 525px.
- `scripts/make_head_back_cutout.py` — chroma-keys the navy sea (silhouette-first
  pipeline from make_icon_art.py, retuned BG), crops, makes tentacles
  semi-transparent below the mantle (40% alpha, like the front on-body variant),
  measures the teal-mantle bbox and prints the Kotlin placement constants.
  Output: `drawable-nodpi/octo_head_ppq_back.png` (1092x1152).
- `OctopusBodyView.kt` — per-side head: `HeadArtMetrics` (front/back art pixel
  metrics), bitmap + metrics selected by `side`; BACK composites
  `octo_head_ppq_back` scaled so its mantle covers the vector dome
  (s = 438·1.03/525 → drawn width 938px, inside the 1000px canvas).
- Verified on device: `fb4_head_front.png` vs `fb4_head_back.png` — head zone
  60% pixel-diff, whole figure+text 21%; back renders the smooth mantle with no
  face. Hit-testing unaffected (image draws inside the same Canvas).

## 26. Phase 22 — Feedback Round 4 FINAL: explicit stretch confirmation + undo (2026-10-04)

**Behavior change (user request):** playing a video no longer records anything. The
octopus only counts stretches the user explicitly confirms ("Did you complete the
stretches? — Yes, log it"), and logged sessions can be undone per day.

### Flow
- `PlayerViewModel.finish()` now saves the session PENDING only (no aggregate
  increments, no Tail broadcast) and shows a confirm card; "Not now" keeps it
  pending. `onCleared()`'s safety net likewise NEVER auto-confirms (Phase 8
  double-complete guard preserved — `finishHandled` still flips synchronously).
- Confirm surfaces (all three): PlayerScreen confirm card · VideoDetailScreen
  persistent "⏳ Unlogged session" banner ([Log it] [Discard], discard behind a
  confirm dialog) · BodyScreen subtle banner "N unlogged stretch sessions · Review"
  → sheet with per-row Log it/Discard + Log all.
- Confirm path (`SessionRepository.confirmSession`, one Room transaction):
  increments `BodyPartStretchEntity` on the session's START DATE (honest: the day
  the stretches were done), snapshots the per-part credited minutes on the row,
  sets `confirmed=1`; the caller then sends the single Tail broadcast.
- Undo (BodyScreen, per day via date-nav history icon): sheet lists that day's
  confirmed sessions; Undo deletes the row and decrements the aggregates using the
  credited snapshot — exact even if later sessions changed totals; decrements clamp
  at 0 (rows hitting 0/0 are deleted). Works for past days via date navigation.
- Stats: all SessionDao stats queries now filter `confirmed = 1` (Body/Streak were
  already aggregate-derived, and aggregates are only written by confirm).

### Migration v2→v3
- `sessions.confirmed INTEGER NOT NULL DEFAULT 0`, `sessions.creditedBodyPartsJson
  TEXT NULL` (ALTER TABLE only). Schema exported to `app/schemas/.../3.json`.
  Pre-v3 sessions stay PENDING — users log them explicitly via the banners.
- Instrumented `Migration2To3Test` (v2 DDL fixture, row survival, defaults,
  confirm/snapshot round-trip, re-confirm no-op). JVM `CreditedPartsTest` covers
  the snapshot codec + undo-clamp math.

### Snapshot format
`creditedBodyPartsJson` = `"partId:minutes,partId:minutes"` (pure-Kotlin codec
`CreditedParts`; no JSON dep so the clamp math is unit-testable). Video deleted
while pending → confirm credits whatever parts are still linked (possibly none:
session-only log); undo reads the snapshot regardless of link state.

### Known limitation
A Tail broadcast cannot be un-sent. The undo dialogs say so ("Tail count can't be
undone — remove it in Tail if needed"). Local data always reverts exactly.

## 27. Phase 24 — Feedback Round 5: provenance visible, editable parts, centered + finger-tracked flip (2026-10-04)

### How extraction works without an API key (user question)
With no `llmApiKey` in DataStore, `BodyPartAnalysisPipeline` skips the LLM entirely and
runs `LlmHeuristics.classify()` — a keyword matcher over title+transcript (+tags) against
per-part synonym lists (16 parts, adaptive threshold: 2 distinct cue hits for real
transcripts ≥1000 chars, 1 hit for thin title-only shorts). Verified on-device BEFORE this
round's changes: DataStore held no `llm_api_key` key and the row (`BOoYU6keLL4`,
"5 Exercises To Unlock Shoulders…") was DONE/CAPTIONS with 6 links — all pipeline logging
says "via heuristic". That's why parts appear with zero configuration.

### Item A — provenance made visible (migration v3→v4)
New `videos.partSource TEXT NULL` column + `PartSource` enum {HEURISTIC, LLM, MANUAL}
(NULL = pre-v4 "unknown"). The pipeline stamps HEURISTIC or LLM together with DONE in the
same transaction; surfaces: video-detail chip-row tail tag + tiny tag on library cards —
"AI-analyzed" / "Quick scan" / "Edited". A new-column migration was REQUIRED because
transcriptSource describes the TEXT, not the CLASSIFIER.

### Item B — Edit parts (video detail pencil icon)
"Edit parts" dialog: all 16 `BodyPart` entries with checkboxes (checked = linked). Save →
`VideoDao.applyManualParts` (one transaction: replaceForVideo + DONE + MANUAL stamp) via
`VideoRepository.applyManualParts`. Library "Analyze" on a DONE+MANUAL row now shows a
confirm dialog ("Analyzing again will replace your manual selection") and runs
`analyzeVideo(force = true)`; canAnalyze extended to DONE+MANUAL for exactly that path.
INTERPLAY WITH UNDO (unchanged semantics): confirmed sessions keep their
`creditedBodyPartsJson` snapshots — editing parts NEVER rewrites past credits (the dialog
says so); only future confirmations use the new links.

### Confirm black-hole fix (user-reported regression mid-round)
Report: played, confirmed, but nothing logged anywhere. Device DB showed
`durationSeconds=8, confirmed=1, creditedBodyPartsJson=''` and EMPTY body_part_stretch:
`confirmSession` credited `creditedMinutes(8s)=0` → `filterValues { it > 0 }` dropped all
parts → marked confirmed with zero increments (banner disappears, stats/history/figure
never see it). Fix: an EXPLICIT confirmation now credits `.coerceAtLeast(1)` minute — the
>30 s threshold only ever made sense for the old auto-log; onCleared's safety net still
saves PENDING-only. (Note: session title row joins `videos`; the black-hole row itself was
used to verify the fix path.)

### Item C — flip centering (pixel-scanned, then fixed)
Final calibration (1080×2316 device, canvas node [68,381][1012,1797], scale 0.944 px/u):
- BEFORE: FRONT content center 527u (+27u right of the 500u pivot), BACK 460.8u (−39u
  after the rotationY mirror) — exactly the user's "back too far left, front too far right".
- Mechanism: `sideCenteringOffsetX(side)` returns a per-side DEVICE-CALIBRATED constant
  (+27u front, −28u back-as-rendered); the view draws inside `translate(left = -offset)`
  and the tap hit-test inverts the same offset. A geometric path-union was tried first
  and UNDER-corrected (≈0 predicted for front vs +27 measured) because it cannot see
  raster alpha asymmetry inside the head bitmaps — hence the calibrated constants
  (re-verify via pixel-scan if art/geometry regenerate).
- AFTER (verified on device, pixel-scan): FRONT +0.5px, BACK −0.5px from screen center
  (`fb5_flip_centered_front/back.png`).

### Item D — flip tracks the finger (replaces toggle-after-fling)
`FlippableBodyView` maps horizontal drag px → rotationY LIVE (DEGREES_PER_DP = 0.9°/dp ≈
half screen ≈ 180°); content swaps at every ±90° crossing (side derived from the live
angle — never mirrored). First iteration had a settle bug the user caught immediately: a
full swipe only reached perpendicular (90°). Root cause: `pointerInput(Unit)` captured a
STALE `flipAngle.value` in the drag-end lambda, so release settled toward the old side;
fixed by reading the angle through `Animatable.asState()` and settling from the LIVE
angle, with release velocity estimated from the last ~5 drag deltas (60 Hz assumption)
projected FLICK_BIAS_SECONDS = 0.12 s to pick the rest side (0/±180). The Front/Back pill
stays a tap-to-toggle animated the same way; taps on regions still work (drag detector
consumes only past touch slop).

### Item D — flip tracks the finger (replaces toggle-after-fling)
`FlippableBodyView` now maps horizontal drag px → rotationY live
(`DEGREES_PER_DP = 0.9`/dp ≈ half screen ≈ 180°); the content swaps at every ±90°
crossing (side derived from the live angle — never mirrored). On release it springs to
the nearest 0/±180 rest (velocity gain hook in `settleToNearest`). The Front/Back pill
stays a tap-to-toggle animated the same way; taps on regions still work (drag detector
consumes only past touch slop).

## 28. Phase 25 — Phase 24 audit & completion (2026-10-05)

Audit of the interrupted Phase 24 run: the DATA + GESTURE layers were complete
(v3→v4 `partSource` migration registered in `DatabaseProvider`, pipeline stamps
HEURISTIC/LLM transactionally, `VideoDao.applyManualParts` = links+DONE+MANUAL in
one transaction, drag-tracking flip with flick-biased settle). What was missing
or broken — all fixed and device-verified this round:

1. Legacy rows never got a provenance value. The device DB had v4 applied but
   the only video was DONE with partSource=NULL (analyzed pre-v4), and
   `canAnalyze` excluded DONE+NULL — such rows could never be re-stamped. FIX:
   both ViewModels derive HEURISTIC for DONE+NULL (the old pipeline always
   finished via the classify path; default has no API key), and `canAnalyze`
   includes DONE+NULL (re-analyze stamps the definitive value, no confirm
   needed since NULL is not MANUAL).
2. FB25 user feedback (implemented now): (a) NO provenance chips anywhere — the
   user rejected "Quick scan"/"AI-analyzed"/"Edited" labels as confusing noise
   (provenance stays DB-level, driving only the re-analyze confirm); (b)
   library card "Downloaded" text chip → tiny teal CheckCircle icon (everything
   in the library is expected to be downloaded; revisit if links-only entries
   ever ship); (c) part tags across TWO rows (split halves) so most sets fit —
   overflow row scrolls horizontally, the "+n" overflow is gone.
3. Edit affordance hidden for empty DONE sets — `BodyPartChips` rendered only
   when bodyParts.isNotEmpty(); an empty DONE set (valid: meditation video)
   could not be edited. Now keyed on isAnalysisDone.
4. Mislabeled evidence: the interrupted run's fb5_source_indicator.png /
   fb5_video_detail.png were actually Library captures; replaced with real
   video-detail captures this round.

Heuristic answer (device-verified via run-as): datastore octo.preferences_pb
contains NO llm keys at all → llmApiKey empty → BodyPartAnalysisPipeline takes
the LlmHeuristics.classify(transcript) branch — keyword matching over the
fetched transcript/title, no network LLM call. The video's parts (Shoulders,
Chest, Lower Back, Core, Glutes, Hams) came from that keyword scan (transcript
source CAPTIONS).

Centering measurements (pixel-scan, 1080×2316): front bbox center +1px / mass
−9px from screen center; back +0px / +8px — both centered (pre-fix deviations
were −27u/+39u canvas units, see §27 Item C). The constants live in
OctopusBodyView.sideCenteringOffsetX, so the video-detail side-by-side card
shares the same centering.

Edit-parts chain (device-verified): pencil → 16-part checkbox dialog → toggle
Neck + Save → DB shows partSource='MANUAL' and neck added to cross-refs in one
transaction; part-detail screen shows Neck in the video's tags. Library
overflow "Analyze" on a MANUAL row routes through the "Re-analyze and replace
edits?" confirm (needsManualConfirmBeforeAnalyze). Confirmed sessions keep
their creditedBodyPartsJson snapshots — manual edits only affect FUTURE
confirmations (undo stays snapshot-authoritative).

Drag-flip: implemented in Phase 24 (single angleState, live finger tracking at
0.9°/dp ≈ half screen = 180°, content swap at ±90°, exponential settle to the
nearest rest with release-velocity flick bias 0.12 s; touch slop preserves
region taps; pill toggle animates the same path). Code-reviewed this round;
on-device gesture recapture was skipped because the device was in active user
use — the fb5 screenshots plus the code path stand as evidence.

Left for the next phase (stated per task spec): a dedicated video-detail layout
pass — figures + legend + poster stack is cramped; the mispositioning WAS the
flip-centering bug (fixed), but the card deserves its own spacing pass.
