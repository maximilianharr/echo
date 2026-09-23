# Echo — Voice Diary Android App

## Stack

- Kotlin + Jetpack Compose, native Android app.
- `minSdk` 33, `targetSdk` latest stable.
- Distribution: no Play Store. Installed via `adb install` over USB from
  Linux. No auto-update mechanism — new builds are reinstalled manually.

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
- On submit, validate by calling the GitHub API (e.g. fetch repo/contents
  metadata) with the given PAT. On failure, show an inline error
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
- Default locale `de-DE`, changeable via a setting (dropdown/toggle). No
  auto-detection.
- Runs after Stop, triggered by Send.

## File Output

For timestamp `T` = `YYYYMMDDHHMMSS`:

- Transcript: `journals/T.md`
  ```
  # YYYY MM DD

  <transcribed text>
  ```
- Audio: `journals/audio/T.m4a`

## GitHub Push

- GitHub REST Contents API: `PUT /repos/<configured owner/repo>/contents/<path>`.
- No git clone / JGit — app only ever adds new files, never edits existing
  ones.
- Two separate API calls/commits per entry (`.md`, then `.m4a`) so each can
  retry independently.

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
- `POST_NOTIFICATIONS` (Android 13+, only if a retry/failure notification is
  shown)

## Out of Scope

- Windows/Linux client.
- Cloud transcription.
- Review/edit screen before send.
- In-app history/browsing.
- Atomic single-commit-per-entry (Git Data API).
- Multi-language auto-detection.
- LLM/to-do-reminder integration.
