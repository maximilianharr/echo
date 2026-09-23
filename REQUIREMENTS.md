# Echo — Voice Diary Android App

## Stack

- Kotlin + Jetpack Compose, native Android app.
- `minSdk` 33, `targetSdk` latest stable.
- Distribution: no Play Store. Installed via `adb install` over USB from
  Linux. No auto-update mechanism — new builds are reinstalled manually.
- Build & install (debug-signed; reinstall keeps app data):
  ```
  ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
  Requires Android SDK path in `local.properties` (`sdk.dir=...`) and a
  JDK 17 toolchain (Gradle auto-detects one in `~/.gradle/jdks`).

## Repo Conventions (fixed, regardless of configured repo)

- Text files: `journals/YYYYMMDDHHMMSS.md` (14-digit timestamp, no
  separators), hardcoded path.
- Audio files: `journals/audio/YYYYMMDDHHMMSS.m4a`, hardcoded path.
- No frontmatter.

## Setup (mandatory, gated)

- On first launch, before the Record screen is reachable, show a setup
  screen requiring:
  - GitHub repo, as `owner/repo` (e.g. `maximilianharr/zettels-private`).
  - Fine-grained GitHub PAT (see Auth).
- On submit, validate by calling `GET /repos/<owner/repo>` with the given
  PAT (must return 200; write permission is not checked). On failure, show an inline error
  immediately and keep the user on the setup screen — do not proceed until
  validation succeeds.
- Also on this screen: transcription locale (see Transcription) and daily
  reminder (see Reminder).
- Record screen is inaccessible until both are set and validated.
- Values editable later from a Settings entry point (re-validated on
  change). Settings additionally has a **Sync** button (see Heatmap).

## UX Flow

Single screen (post-setup), no history/list screen; only the heatmap (see
Heatmap) summarizes past entries.

1. Screen opens showing a **Record** button (no auto-start on launch).
2. Tap Record → recording starts → **Stop** button shown.
3. Tap Stop → **Discard** and **Send** buttons shown.
4. Discard → delete temp audio, return to step 1. No confirmation dialog.
5. Send → run transcription, then save/upload flow below.
6. No transcript review/edit step before send.
7. If entries are queued/retrying, show small non-blocking text, e.g.
   "2 entries waiting to sync." No tap target.

## Audio Recording

- `MediaRecorder`, AAC codec, `.m4a` container.
- Mono, ~96 kbps, 44.1 kHz.
- No max recording length.
- Recording continues until Stop, also while the screen is locked or the app
  is in the background (foreground service of type `microphone`). Closing the
  app (Back / swiping it away) while recording stops the recording.
- Always the default mic source; no special handling of connected headsets.

## Transcription

- Android on-device `SpeechRecognizer` API (no cloud STT).
- Default locale `de-DE`, switchable to `en-US` in Setup/Settings. No
  auto-detection.
- Runs after Stop, triggered by Send.
- The recorded `.m4a` is decoded to 16 kHz mono PCM and fed to the
  on-device recognizer (`EXTRA_AUDIO_SOURCE`, segmented session), with
  punctuation/capitalization formatting enabled.
- If transcription fails or yields no text, only the audio is uploaded (no
  `.md`). A missing language pack triggers its download for later entries.

## File Output

For timestamp `T` = `YYYYMMDDHHMMSS` (local time when Record was tapped):

- Transcript: `journals/T.md`
  ```
  # YYYY MM DD        (e.g. "# 2026 09 23")

  <transcribed text>
  ```
- Audio: `journals/audio/T.m4a`

## GitHub Push

- GitHub REST Contents API: `PUT /repos/<configured owner/repo>/contents/<path>`.
- No git clone / JGit — app only ever adds new files, never edits existing
  ones.
- Two separate API calls/commits per entry (`.md`, then `.m4a`) so each can
  retry independently. Commit message: `Add <path>`, default branch.
- HTTP 422 (file already exists, e.g. an earlier push succeeded but its
  response was lost) counts as success.

## Auth

- Fine-grained GitHub PAT, scoped to the configured repo only, "Contents:
  read/write" permission.
- Entered at setup (see Setup), stored in Android Keystore-backed
  `EncryptedSharedPreferences`.

## Reliability

1. On Send, write transcript + audio to app-private local storage first.
2. Attempt GitHub push immediately.
3. On failure, keep queued locally; WorkManager retries in background,
   per-file independently.
4. On confirmed push, delete the local app-private copy of that file.

## Heatmap

- Shows which days of the last 53 weeks have at least one entry.
- Days are kept locally (set of `YYYYMMDD`).
- Rebuilt from GitHub after a successful Setup save when the repo is new or
  changed, and by the **Sync** button in Settings. Rebuild = days of all
  `journals/audio/*.m4a` in the repo (via
  `GET /repos/<owner/repo>/git/trees/HEAD:journals/audio`; the Contents API
  is capped at 1000 entries) plus entries still waiting in the local queue.
  Missing directory or empty repo = no days.
- After each Send, that entry's day is added locally. No other GitHub
  fetches.

## Reminder

- Optional daily notification, configured in Setup/Settings: on/off switch
  (default off) and time (default 20:00, 24h picker).
- Switching it on requests `POST_NOTIFICATIONS`; if denied, it stays off.
- At the chosen time, notify "Time for your diary" only if today has no
  entry yet (per the heatmap days). Tapping opens the app.
- Exact alarm (`AlarmManager.setExactAndAllowWhileIdle`), rescheduled for the
  next day after each firing, on app start and after reboot.
- Sync status is never notified; the in-app "waiting to sync" text is the
  only sync status.

## Design

- Very simple and lean; no decoration beyond what is listed here.
- Two colours only:
  - Light (default): bright caramel background `#F3DCB0`, dark brown
    `#3E2415` for text, icons and buttons.
  - Dark (system dark mode): dark brown background `#23160D`, caramel
    `#F3DCB0` for text, icons and buttons.
  - Tints of the foreground colour on the background are used for secondary
    elements (field outlines, empty heatmap cells, dialogs).
- Default Material 3 typography.
- App icon: adaptive icon, dark brown record button (ring around a filled
  dot) on caramel. Notification icon: same glyph, monochrome.
- Record screen, top to bottom:
  1. Gear icon (Settings), top right.
  2. Heatmap: one column per week, rows Monday→Sunday, 10dp rounded
     squares with 2dp gaps. Filled cell = foreground colour, empty cell =
     foreground at ~12% alpha; no intermediate shades, no labels. Days after
     today are not drawn. Scrolls horizontally, initially scrolled to today.
  3. Centre: large round button with a short label below:
     - Idle: 96dp circle with a dot — "Record".
     - Recording: 96dp circle with a rounded square — "Stop".
     - Stopped: two 72dp circles, ✕ "Discard" and → "Send".
     - Sending: spinner + "Transcribing…".
  4. Bottom: "N entries waiting to sync" (small text, only if N > 0).
- Recording visualizer: microphone loudness (`MediaRecorder` max amplitude
  every 50 ms, −50…0 dBFS mapped to 0…1), smoothly animated. The Stop button
  scales up to ~1.15× and a soft radial glow behind it grows with loudness.
- While recording, an ongoing low-priority notification "Recording…" (record
  glyph, no actions; tap opens the app) — required by Android for the
  foreground service. Hidden if notifications are not permitted.
- Setup/Settings: plain form in the same colours (text fields, locale chips,
  reminder switch + time, Save / Sync / Cancel).

## Permissions

- `RECORD_AUDIO`
- `INTERNET`
- `POST_NOTIFICATIONS` (requested only when the reminder is switched on)
- `USE_EXACT_ALARM` (granted at install; fine since not on Play Store)
- `RECEIVE_BOOT_COMPLETED` (reschedule reminder after reboot)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` (record while locked)

## Out of Scope

- Windows/Linux client.
- Cloud transcription.
- Review/edit screen before send.
- In-app history/browsing.
- Atomic single-commit-per-entry (Git Data API).
- Multi-language auto-detection.
- LLM/to-do-reminder integration.
