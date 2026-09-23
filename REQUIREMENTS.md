# Echo — Voice Diary Android App

## Stack

- Kotlin + Jetpack Compose, native Android app.
- `minSdk` 33, `targetSdk` latest stable.

## Existing Repo Conventions (must match)

- Repo: `https://github.com/maximilianharr/zettels-private` (private).
- Text files: `journals/YYYYMMDDHHMMSS.md` (14-digit timestamp, no
  separators).
- No frontmatter.

## UX Flow

Single screen, no history/list screen.

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

- GitHub REST Contents API: `PUT /repos/maximilianharr/zettels-private/contents/<path>`.
- No git clone / JGit — app only ever adds new files, never edits existing
  ones.
- Two separate API calls/commits per entry (`.md`, then `.m4a`) so each can
  retry independently.

## Auth

- Fine-grained GitHub PAT, scoped to this repo only, "Contents:
  read/write" permission.
- Entered once in a Settings screen, stored in Android Keystore-backed
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
