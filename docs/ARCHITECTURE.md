# Octo — Architecture & Research (Phase 1 of 8)

**Date:** 2026-10-03
**Status:** COMPLETE — all 8 phases implemented, verified on-device (Phase 8 final sweep 2026-10-03).
**Scope of this document:** template audit, Tail integration spec (from Wags), image-gen findings (small_scripts), YouTube share/download/transcript decisions, LLM spec, body-part taxonomy, package structure, phase checklist.

---

## 1. Octo Template Audit (current state)

Fresh Android Studio Compose template, untouched.

| Item | Value | Source |
|---|---|---|
| AGP | **9.4.1** | [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) |
| Kotlin | **2.2.10** + `org.jetbrains.kotlin.plugin.compose` | [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) |
| Compose BOM | **2026.02.01** (material3, ui, ui-graphics, tooling) | [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) |
| compileSdk / targetSdk | **37** | [`app/build.gradle.kts`](../app/build.gradle.kts) |
| minSdk | **26** | [`app/build.gradle.kts`](../app/build.gradle.kts) |
| Java | 11 (source/target compat) | [`app/build.gradle.kts`](../app/build.gradle.kts) |
| Gradle daemon JVM | toolchain 25, foojay resolver | [`gradle/gradle-daemon-jvm.properties`](../gradle/gradle-daemon-jvm.properties) |
| Repos | google + mavenCentral, `FAIL_ON_PROJECT_REPOS` | [`settings.gradle.kts`](../settings.gradle.kts) |
| Existing deps | core-ktx 1.10.1, lifecycle-runtime-ktx 2.6.1, activity-compose 1.8.0, junit/espresso | [`app/build.gradle.kts`](../app/build.gradle.kts) |
| Code | [`MainActivity.kt`](../app/src/main/java/com/example/octo/MainActivity.kt) (Greeting), [`ui/theme/`](../app/src/main/java/com/example/octo/ui/theme/) (OctoTheme/Color/Type) | |
| Manifest | launcher activity only, no permissions, no SEND filter | [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) |
| Release build | R8 optimization enabled, `packageScope = androidx/kotlin*` | [`app/build.gradle.kts`](../app/build.gradle.kts) |

**Missing (to add in later phases):** Hilt (or manual DI), Room, Navigation-Compose, Media3, WorkManager, Retrofit/OkHttp, kotlinx-serialization, Coil, DataStore, NewPipeExtractor (+ **JitPack repo** in `dependencyResolutionManagement`).

---

## 2. Tail Integration Spec (reverse-engineered from Wags — authoritative)

Wags sends habit data to Tail over two IPC channels, both guarded by a **signature-level permission**. Reference implementation (client side) lives entirely in one file in Wags:

- `/home/twain/AndroidStudioProjects/wags/app/src/main/java/com/example/wags/data/ipc/HabitIntegrationRepository.kt`
- Server-side declarations: `/home/twain/AndroidStudioProjects/tail/app/src/main/AndroidManifest.xml`

### 2.1 Constants (exact values)

```kotlin
// Package of the habit app
const val HABIT_APP_PACKAGE = "com.example.tail"

// Signature permission declared by Tail (protectionLevel="signature")
const val PERMISSION_TAIL = "com.example.tail.permission.TAIL_INTEGRATION"

// Content Provider (habit discovery; read-only)
val HABITS_CONTENT_URI: Uri = Uri.parse("content://com.example.tail.provider/habits")
const val COL_HABIT_ID   = "habit_id"     // Int index
const val COL_HABIT_NAME = "habit_name"   // String — this is the stable habit ID for writes

// Broadcast action #1 — increment
const val ACTION_INCREMENT = "com.example.tail.ACTION_INCREMENT_HABIT"

// Broadcast action #2 — backfill / SET (idempotent replace)
const val ACTION_SET_HABIT_VALUES = "com.example.tail.ACTION_SET_HABIT_VALUES"

// Extras
const val EXTRA_HABIT_ID   = "EXTRA_HABIT_ID"        // String habit name (or Int index; we use name)
const val EXTRA_SLOT       = "wags_slot"             // informational sender slot name
const val EXTRA_MINUTES    = "EXTRA_MINUTES"         // Int, protocol v2
const val EXTRA_SESSIONS   = "EXTRA_SESSIONS"        // Int, protocol v3 (atomic sessions+minutes)
const val EXTRA_VALUES_JSON = "EXTRA_VALUES_JSON"    // String, {"yyyy-MM-dd": minutes, ...}

// Storage-key prefixes Tail understands when prefixed onto EXTRA_HABIT_ID
const val SECONDARY_VALUE_PREFIX = "secondary_value:"  // → Tail key secondary_value:<habit>
const val MINUTES_SLOT_PREFIX    = "minutes:"          // → Tail key minutes:<habit>

// Rounding convention used by all Wags call sites
fun secondsToMinutes(seconds: Int) = round(seconds / 60.0).toInt().coerceAtLeast(1)
fun millisToMinutes(millis: Long)  = round(millis / 60_000.0).toInt().coerceAtLeast(1)
```

### 2.2 Increment broadcast (Protocol v3 — atomic sessions + minutes)

```kotlin
val intent = Intent(ACTION_INCREMENT).apply {
    `package` = HABIT_APP_PACKAGE                      // explicit → reliable on API 26+
    putExtra(EXTRA_HABIT_ID, habitName)                // String habit name from provider
    putExtra(EXTRA_SLOT, slot.name)                    // informational
    putExtra(EXTRA_MINUTES, minutes.coerceAtLeast(0))
    putExtra(EXTRA_SESSIONS, sessions.coerceAtLeast(0))
}
context.sendBroadcast(intent, PERMISSION_TAIL)         // receiverPermission guards delivery
```

Tail's `HabitIncrementReceiver` (declared `exported="true"`, `android:permission="com.example.tail.permission.TAIL_INTEGRATION"`) does one atomic read-modify-write: `+sessions` on the habit's primary key and `+minutes` on its `minutes:<habit>` slot. Older Tail versions fall back to v2 behaviour (primary += minutes).

Minutes-only signed delta variant: same action with `EXTRA_SESSIONS = 0` and negative `EXTRA_MINUTES` (Tail clamps day totals at zero).

### 2.3 Backfill broadcast (SET, idempotent)

```kotlin
val json = """{"2026-01-15":10,"2026-01-16":5}"""      // ISO dates → minutes
val intent = Intent(ACTION_SET_HABIT_VALUES).apply {
    `package` = HABIT_APP_PACKAGE
    putExtra(EXTRA_HABIT_ID, habitName)                // optionally prefixed, see §2.2 keys
    putExtra(EXTRA_SLOT, slot.name)
    putExtra(EXTRA_VALUES_JSON, json)
}
context.sendBroadcast(intent, PERMISSION_TAIL)
```

Handled by Tail's `HabitValueSetReceiver` — **replaces** per-date values, so re-sending is safe.

### 2.4 Habit discovery (ContentProvider query)

```kotlin
context.contentResolver.query(
    HABITS_CONTENT_URI,
    arrayOf(COL_HABIT_NAME),
    null, null, null            // Tail returns habits in user-defined screen order
)?.use { cursor ->
    val nameIdx = cursor.getColumnIndexOrThrow(COL_HABIT_NAME)
    while (cursor.moveToNext()) results += cursor.getString(nameIdx)
}
```

Wags stores the **habit name as the ID** (receiver accepts name or index; name is stable across reorders). Slot→habit mapping persisted in SharedPreferences as `habit_id_<slot>` / `habit_name_<slot>` pairs.

### 2.5 Manifest requirements for Octo

From Wags' manifest (exact pattern to replicate):

```xml
<uses-permission android:name="com.example.tail.permission.TAIL_INTEGRATION" />

<queries>
    <package android:name="com.example.tail" />
    <provider android:authorities="com.example.tail.provider" />
</queries>
```

### 2.6 Signing caveat (critical)

`protectionLevel="signature"` ⇒ Octo must be signed with the **same keystore as Tail**. In practice:
- **Debug:** all Android Studio projects share `~/.android/debug.keystore`, so debug builds of Octo and Tail already satisfy the check.
- **Release:** Octo must be signed with Tail's release keystore. Note this in the release checklist.

Failure mode when Tail is absent: `sendBroadcast(..., permission)` throws `SecurityException` on API 34+ — catch it and degrade silently (Wags does exactly this).

### 2.7 Octo integration design

Single class `ipc/TailIntegrationRepository.kt` mirroring Wags' repository, with one Octo slot:
- `Slot.YOGA_STRETCH(idKey="habit_id_yoga", nameKey="habit_name_yoga", label="Yoga / Stretching")`
- On session completion: `ACTION_INCREMENT` with `sessions=1`, `minutes=round(duration)`.
- Optional daily backfill using stored session history via `ACTION_SET_HABIT_VALUES`.

---

## 3. small_scripts Findings — AI image generation

Location: `/home/twain/Projects/small_scripts` (read-only; nothing was run or modified).

### 3.1 Mechanism: PPQ "Grok Imagine" images API (OpenAI-compatible shape)

Reference scripts:
- `/home/twain/Projects/small_scripts/ppq_grok_imagine_test.py` — quality/resolution matrix tester
- `/home/twain/Projects/small_scripts/jjr_icon_gen.py` — generated the JJR app launcher icon (precedent for exactly this use-case)
- API key file: `/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt` (never printed/copied)

```python
URL   = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"
payload = {
    "model": MODEL, "prompt": PROMPT,
    "quality": "medium",           # low | medium
    "resolution": "1k",            # 1k | 2k
    "aspect_ratio": "1:1",         # e.g. "16:9"
    "n": 1,
}
# POST with Authorization: Bearer <key>, Content-Type: application/json
# Response: {"cost": "$...", "data": [{"url": "https://..."}]}  → GET the url, save bytes
```

### 3.2 How we will use it for Octo (no code changes outside Octo)

Generate the mascot/body-map art in a one-off local run (later phase), then commit the PNGs into `app/src/main/res/` or `assets/`:
1. **Launcher icon** (1:1, flat vector style — follow `jjr_icon_gen.py` prompt pattern): "Flat vector Android app icon, centered, octopus head with sleek tentacles curling into a yoga lotus silhouette, deep teal background, minimal, no text."
2. **Body-map base image** (2k, `aspect_ratio` free): front+back view of a stylized man with an octopus head, clean regions, neutral even lighting, ideally comic-vector so regions are flat-color and easy to overlay later.

The region overlay/coloring will be done in Compose (vector overlay anchored to the bitmap), so the generation prompt should request simple anatomy, symmetrical pose, no shading gradients. A working draft prompt is in [`docs/NOTES.md`](NOTES.md).

---

## 4. YouTube Pipeline — Decisions

### 4.1 (a) Receiving a shared video/Short — **ACTION_SEND + text/plain + regex**

The YouTube app's "Share" emits `ACTION_SEND` with `text/plain` containing the URL. Octo declares a transparent trampoline activity:

```xml
<activity
    android:name=".youtube.ShareReceiverActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:taskAffinity=""
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

URL matching — reuse Wags' proven regex verbatim
(source: `/home/twain/AndroidStudioProjects/wags/app/src/main/java/com/example/wags/MainActivity.kt`, companion object):

```kotlin
private val YOUTUBE_URL_REGEX = Regex(
    """https?://(?:[a-z0-9-]+\.)*(?:youtube\.com/(?:watch\?\S+|shorts/\S+|embed/\S+|live/\S+)|youtu\.be/\S+|music\.youtube\.com/watch\?\S+)""",
    RegexOption.IGNORE_CASE
)
```

Video-ID extraction (first match group wins):
- `youtube.com/watch?v=<ID>` → `v` query param
- `youtu.be/<ID>` → first path segment
- `youtube.com/shorts/<ID>`, `/embed/<ID>`, `/live/<ID>` → first path segment

Precedent: Tail's own `ShareTextActivity` uses the identical `ACTION_SEND` + `text/plain` filter pattern (see Tail manifest lines 106–116).

### 4.2 (b) Download & storage — **PRIMARY: NewPipeExtractor (JitPack) → muxed MP4 to app-private storage**

Options evaluated:

| Option | Verdict | Reason |
|---|---|---|
| **NewPipeExtractor via JitPack** | ✅ **Primary** | Pure-Java/Kotlin library, no external process/service; extracts stream URLs + metadata + subtitles; used by NewPipe in production; degrades gracefully; pinned version upgrades fix breakage |
| yt-dlp invocation on Android | ❌ Rejected | Requires bundled Python (Chaquopy/Termux hacks), ~50 MB overhead, brittle, Play-unfriendly |
| URL + thumbnail only, stream via Media3 | ⚠️ Fallback only | Google video URLs are signed & expire (~6 h); library dies offline; violates "library survives offline" requirement |
| YouTube Data API / IFrame | ❌ Rejected | No offline video bytes; API key + quota; IFrame requires YouTube app/web |

**Chosen flow:**
1. `NewPipeExtractor` (`com.github.TeamNewPipe:NewPipeExtractor:v0.24.x` via JitPack — add `maven("https://jitpack.io")` to `dependencyResolutionManagement` in [`settings.gradle.kts`](../settings.gradle.kts)) → `StreamInfo.getInfo(service, url)`.
2. **Download preference:** best *muxed* (progressive) MP4 — itag 18 (360p) / itag 22 (720p) when available → single file, plays directly in Media3, no muxing needed. Yoga/stretching content does not need >720p.
3. **No muxed stream?** → download best video stream ≤720p + best audio (m4a) as two files; play offline with Media3 `MergingMediaSource` (video + side-loaded audio). No ffmpeg.
4. **Always persist** (Room): `videoId`, canonical URL, title, channel, durationMs, thumbnail path, local file path(s), downloadedAt. The URL is the restore key — if files are cleared, the library can re-resolve/re-download from the stored URL; thumbnails come from `https://i.ytimg.com/vi/<id>/hqdefault.jpg` (stable, no auth).
5. Storage in `context.filesDir/videos/` (app-private, no READ_MEDIA permission); expose explicit "Export library" (JSON of rows incl. URLs) in settings.
6. All extraction wrapped in a `YouTubeGateway` interface so a "URL-only mode" fallback (stream-at-playback-time, no download) exists if extraction breaks between releases.

Tradeoffs to accept: extractor breakage risk on YouTube changes (mitigate: pinned releases, `try/catch` → fallback mode, never crash library UI); ToS considerations are the app owner's; ~30–80 MB per video disk usage (acceptable for short yoga videos; add per-video delete + total-size stats).

### 4.3 (c) Transcript — **PRIMARY: YouTube timedtext captions over plain HTTP (no API key, no LLM)**

1. GET `https://www.youtube.com/watch?v=<ID>` with desktop UA + `Accept-Language: en` (and `CONSENT=YES+` cookie when challenged).
2. Extract `ytInitialPlayerResponse` JSON → `captions.playerCaptionsTracklistRenderer.captionTracks[]`.
3. Prefer track `languageCode == "en"` (kind=manual); else first track; else `kind == "asr"` (auto).
4. GET `baseUrl + "&fmt=json3"` → parse `events[].segs[].utf8`, keep `tStartMs`; join segments into text; for ASR tracks strip duplicated rolling tokens (auto captions repeat the previous line's tail — dedupe by removing a segment's leading words that equal the previous segment's trailing words).
5. Output `List<TranscriptCue>(startMs, text)` → store full text + cues in Room.

Alternatives: NewPipeExtractor's `subtitles` (same data source through the library — use it if the raw scrape is brittle; both are caption-based, **no ASR needed**); on-device ASR (Whisper/Vosk) — ❌ heavy, unnecessary since YouTube captions exist for virtually all yoga content. LLM is **never** used for transcription.

Fallback chain: raw timedtext scrape → NewPipeExtractor subtitles → mark video "no transcript" (muscle extraction then runs on title+description via LLM, or body parts are set manually on the body map).

### 4.4 (d) LLM muscle-group extraction — **provider-agnostic OpenAI-compatible client**

Design: user supplies **Base URL + API key + model name** in Settings (DataStore). One client, three shapes:
- **z.ai (default since Phase 15):** `https://api.z.ai/api/paas/v4`, model `glm-4.6` — Z.ai's OpenAI-compatible chat-completions base; the GLM line is the provider's main chat/coding model family.
- OpenAI: `https://api.openai.com/v1`
- Groq: `https://api.groq.com/openai/v1`
- OpenRouter: `https://openrouter.ai/api/v1`
- Local: `http://<lan-ip>:1234/v1` (LM Studio / ollama-compat)

Migration (Phase 15): fresh installs get the z.ai defaults directly; existing installs keep persisted values, but on first read a one-time SettingsRepository migration overwrites values that match the **old defaults exactly** (`https://api.openai.com/v1` / `gpt-4o-mini`, or unset) — user-customized URLs/models are never clobbered. The design remains provider-agnostic; z.ai is only the out-of-the-box default.

Transport: `POST {baseUrl}/chat/completions`, `response_format: {"type": "json_object"}`, low temperature (0.2). Transcript chunked at ~24k chars if long; chunk results unioned.

**System prompt (canonical):**

```
You are a physiotherapy assistant. Given the transcript of a yoga/stretching
video, identify which body parts are meaningfully stretched or worked.

Rules:
- Output ONLY body-part ids from this canonical list:
  neck, shoulders, chest, upper_back, lower_back, core, obliques,
  hips_glutes, groin_adductors, quads, hamstrings, calves, ankles_feet,
  wrists_forearms, biceps_triceps, lats
- Ignore pleasantries, breath cues, chit-chat.
- Include a part only if the video spends real time on it (not a single mention).
- Respond with JSON only, matching the given schema.
```

**Response JSON schema (validated with kotlinx.serialization, unknown ids dropped):**

```json
{
  "body_parts": ["hamstrings", "lower_back", "calves"],
  "confidence": 0.85,
  "notes": "brief optional rationale"
}
```

Fallback when no key configured: keyword heuristic (e.g. "hamstring fold" → hamstrings) over transcript; user can always tap regions manually on the body map — LLM result only pre-selects them.

---

## 5. Canonical Body-Part Taxonomy (LLM ↔ body-map, 1:1)

16 ids. Each maps to a drawable zone on the octopus-man: **octopus head** (1 zone), **mantle/torso front & back** (6 zones), **8 tentacles** = arms/legs with proximal/distal segments.

| id | Label | Muscles | Octopus-man zone |
|---|---|---|---|
| `neck` | Neck | Cervical extensors/flexors, upper traps | Octopus head / mantle joint |
| `shoulders` | Shoulders | Deltoids, rotator cuff | Tentacle A1/A2 proximal (upper arms L/R) |
| `chest` | Chest | Pectorals | Mantle front upper |
| `upper_back` | Upper Back | Rhomboids, mid traps | Mantle back upper |
| `lower_back` | Lower Back | Erector spinae, QL | Mantle back lower |
| `core` | Core / Abs | Rectus abdominis, transverse | Mantle front lower |
| `obliques` | Obliques | Internal/external obliques | Mantle front side panels |
| `lats` | Lats | Latissimus dorsi | Mantle back side panels |
| `hips_glutes` | Hips & Glutes | Glutes, hip flexors, piriformis | Pelvis / mantle base |
| `groin_adductors` | Groin / Adductors | Adductor group | Inner pelvis |
| `quads` | Quads | Quadriceps | Leg tentacles L/R proximal |
| `hamstrings` | Hamstrings | Biceps femoris group | Leg tentacles L/R posterior |
| `calves` | Calves | Gastrocnemius, soleus | Leg tentacles L/R distal |
| `ankles_feet` | Ankles & Feet | Tibialis, plantar fascia | Leg tentacle tips |
| `wrists_forearms` | Wrists & Forearms | Forearm flexors/extensors | Arm tentacle tips |
| `biceps_triceps` | Biceps & Triceps | Upper-arm flexors/extensors | Arm tentacles L/R mid |

This exact id list is shared by: the LLM prompt, the Room entity (`Set<BodyPart>` stored as CSV/JSON), the body-map overlay `ImageRegion`s, and stats aggregation.

---

## 6. Proposed Module / Package Structure

All under `com.example.octo` in `app/` (single module — small app, no need for multi-module yet):

```
com.example.octo/
├── MainActivity.kt                 # nav host
├── OctoApplication.kt              # DI graph (manual or Hilt), WorkManager init
├── data/
│   ├── db/                         # Room: OctoDatabase, VideoEntity, TranscriptCueEntity, Daos
│   ├── prefs/                      # DataStore: LLM settings, Tail slot, download prefs
│   └── repo/                       # LibraryRepository, SessionRepository
├── network/                        # shared OkHttp client, JSON (kotlinx.serialization)
├── youtube/
│   ├── ShareReceiverActivity.kt    # ACTION_SEND trampoline (§4.1)
│   ├── YouTubeUrl.kt               # regex + video-id extraction
│   ├── YouTubeGateway.kt           # StreamInfo via NewPipeExtractor + URL-only fallback
│   ├── YouTubeMetadataFetcher.kt   # oEmbed title/channel (port of Wags', §3 of NOTES)
│   ├── Thumbnails.kt               # i.ytimg.com fetch/cache
│   └── TranscriptFetcher.kt        # timedtext json3 scrape (§4.3)
├── downloader/                     # WorkManager workers: video download, thumbnail, transcript
├── llm/
│   ├── LlmClient.kt                # OpenAI-compatible chat/completions (base+key+model)
│   ├── MuscleExtractor.kt          # prompt build, schema validation, chunking, heuristic fallback
│   └── BodyParts.kt                # canonical taxonomy (§5) as enum
├── ipc/
│   └── TailIntegrationRepository.kt # port of Wags HabitIntegrationRepository (§2)
├── player/                         # Media3 playback: local file, or muxed dual-source
├── work/                           # session recording, streaks, stats aggregation
└── ui/
    ├── library/                    # video list, add flow, import/export
    ├── player/                     # playback screen + session timer
    ├── body/                       # octopus-man body map + region coloring
    ├── stats/                      # per-body-part history, streaks
    └── settings/                   # LLM config, Tail habit picker, download prefs
```

Manifest additions: `INTERNET`; `<queries>` for Tail (§2.5); `uses-permission TAIL_INTEGRATION`; `ShareReceiverActivity` filter (§4.1).

---

## 7. Phase-by-Phase Implementation Checklist

- **Phase 2 — Skeleton & Share-In:** ✅ deps (manual DI, Room, Navigation, WorkManager, serialization, Coil, DataStore), nav scaffold, `ShareIngestActivity` + regex + video-id parsing, library list screen, NewPipeExtractor metadata fetch.
- **Phase 3 — Download:** ✅ JitPack + NewPipeExtractor v0.26.5, `DownloadVideoWorker` (muxed-first §4.2), progress UI, thumbnails, per-video delete; URL-row persistence verified on-device.
- **Phase 4 — Player:** ✅ Media3 offline playback incl. dual-stream merge fallback, play-time-only session timer, Finish/Save-and-leave semantics, ONE Tail broadcast per session.
- **Phase 5 — Transcript:** ✅ three-tier `TranscriptFetcher` (timedtext json3 + ASR dedupe → extractor subtitles → title/description), cue storage, analysis pipeline with RUNNING guard, Room v2 migration (instrumented-tested).
- **Phase 6 — LLM & Body Map:** ✅ settings screen (base/key/model + Test LLM), `LlmClient` (chunked, schema-validated), heuristic fallback, 16-region octopus-man body map + % ring dashboard.
- **Phase 7 — Stats & Tail:** ✅ range-selectable stats (unit-tested `StatsMath`), streaks, Tail habit picker + session completion broadcast (v3), mascot art via PPQ (one-off).
- **Phase 8 — Polish:** ✅ library URL export/import (SAF, stream-only restore), process-death recovery sweep (re-kick orphaned downloads, drop empty session rows), double-complete race fix in `PlayerViewModel`, R8 keep rules for NewPipeExtractor/JSoup/kotlinx-serialization, versionName 1.0.0, `.gitkeep`/stale-stub cleanup, final E2E + docs.

---

## 8. External References Discovered

| Path | Relevance |
|---|---|
| `wags/app/src/main/java/com/example/wags/data/ipc/HabitIntegrationRepository.kt` | Full Tail client implementation (§2) |
| `tail/app/src/main/AndroidManifest.xml` | Receiver/provider declarations, permission (§2) |
| `wags/app/src/main/java/com/example/wags/MainActivity.kt` | `YOUTUBE_URL_REGEX` (§4.1) |
| `wags/app/src/main/java/com/example/wags/data/repository/YouTubeMetadataFetcher.kt` | oEmbed fetch (§4.1, Phase 2 port) |
| `wags/.../ui/settings/SettingsTailSection.kt` | Habit picker UX precedent (Phase 7) |
| `/home/twain/Projects/small_scripts/ppq_grok_imagine_test.py` | PPQ Grok image API usage (§3) |
| `/home/twain/Projects/small_scripts/jjr_icon_gen.py` | App-icon generation precedent (§3.2) |
| `/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt` | API key location (never copied) |
| `/home/twain/Projects/yt-dlp/` | Local yt-dlp checkout (desktop use only; rejected for on-device) |
