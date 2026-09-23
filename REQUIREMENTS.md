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
- Record screen is inaccessible until both are set and validated.
- Values editable later from a Settings entry point (re-validated on
  change).

## UX Flow

Single screen (post-setup), no history/list screen.

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

## Permissions

- `RECORD_AUDIO`
- `INTERNET`
- No notifications (and no `POST_NOTIFICATIONS`); the in-app "waiting to
  sync" text is the only sync status.

## Out of Scope

- Windows/Linux client.
- Cloud transcription.
- Review/edit screen before send.
- In-app history/browsing.
- Atomic single-commit-per-entry (Git Data API).
- Multi-language auto-detection.
- LLM/to-do-reminder integration.
