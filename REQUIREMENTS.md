# Echo — Voice Diary Android App

## Goal

A single-purpose Android app to lower the friction of keeping a personal/work
diary, so it actually gets used daily. Flow: tap Record, speak, tap Stop, tap
Send. The app transcribes on-device and pushes both the transcript and the
raw audio to an existing private GitHub diary repo.

The diary also doubles as a personal legacy — the audio is kept
intentionally so the transcript's owner's children can one day hear these
entries in their own voice, not just read text.

Reviewing entries with an LLM (e.g. Claude) to surface to-dos and reminders
is the eventual *use* of this diary, but is explicitly **out of scope** for
this app — that happens later, in a separate session, reading the repo.

## Platform & Stack

- **Android only.** No Windows/Linux client. (An existing manual Linux
  workflow — write `.md`, `git push` by hand — continues to exist
  independently and is unaffected by this app.)
- **Native app**: Kotlin + Jetpack Compose.
- Target device: Android 14+. `minSdk` 33 (Android 13), `targetSdk` latest
  stable. No support burden for older Android versions — this app runs on
  one person's own phone.

## Existing Repo & Conventions (already established, must be matched)

- Repo: `https://github.com/maximilianharr/zettels-private` (private).
- Diary text files live in `journals/`.
- Existing filename convention: `YYYYMMDDHHMMSS.md` (14-digit timestamp, no
  separators) — e.g. `20260918143514.md`.
- Existing content convention: starts with a `# ` heading, then unstructured
  notes. No frontmatter.
- The `journals/` folder's own README states it should contain *only*
  journal zettels — other file types belong elsewhere. This is why audio
  gets its own subfolder (see below), not the same folder as the `.md`
  files.

## UX Flow

Single screen. No history/browse screen — the GitHub repo itself is the
browsable history.

1. App opens to a screen with a **Record** button. (Recording does **not**
   auto-start on launch — avoids accidental recordings from a stray tap on
   the app icon.)
2. Tap Record → recording starts, a **Stop** button appears.
3. Tap Stop → recording ends, **Discard** and **Send** buttons appear.
4. **Discard** deletes the temp audio, returns to step 1. No confirmation
   dialog needed for now.
5. **Send** triggers on-device transcription, then the save/upload flow
   below.
6. No review/edit step for the transcript before sending — accepted
   trade-off for zero-friction use. Minor transcription errors are
   acceptable; the retained audio is the fallback source of truth.
7. If there are entries still queued/retrying in the background, show a
   small non-blocking indicator, e.g. "2 entries waiting to sync." No tap
   target, no list — just a status hint.

## Audio Recording

- `MediaRecorder`, AAC codec in an `.m4a` container.
- Mono, ~96 kbps, 44.1 kHz. (Good, clear voice quality; keeps years of daily
  entries from bloating the repo or hitting GitHub's per-file/repo size
  practicalities.)
- No artificial max recording length.

## Transcription

- Android's on-device `SpeechRecognizer` API. No cloud STT, no API key.
- Default locale: **German (`de-DE`)**, matching the existing diary's
  language. Exposed as a simple setting (one dropdown/toggle) to switch
  session language if needed — no auto-detection.
- Transcription happens after Stop, triggered by Send (not live/streaming
  during recording).

## File Output

For a given entry with timestamp `T` = `YYYYMMDDHHMMSS`:

- **Transcript**: `journals/T.md`
  ```
  # YYYY MM DD

  <transcribed text>
  ```
  (Heading is date-only, generated from `T`; no time in the heading, no
  frontmatter, no link back to the audio file — audio is correlated purely
  by matching basename.)
- **Audio**: `journals/audio/T.m4a`

## GitHub Push

- **GitHub REST Contents API** (`PUT /repos/maximilianharr/zettels-private/contents/<path>`),
  not a full git clone/JGit implementation. The app only ever *adds* new
  files, never edits existing ones from the phone, so there's no merge
  scenario to handle — the API's simplicity has no real downside here.
- **Two separate commits per entry** — one Contents API call for the `.md`,
  one for the `.m4a`. Simpler than building a single atomic commit via the
  Git Data API, and lets the `.md` and audio retry independently if one
  upload fails and the other succeeds.

## Auth

- **Fine-grained GitHub Personal Access Token**, scoped to only this repo,
  with only "Contents: read/write" permission.
- Entered once in a Settings screen, stored via Android
  Keystore-backed `EncryptedSharedPreferences`.

## Reliability — Save Then Upload

1. On Send, immediately write the transcript and audio file to app-private
   local storage. This is the point of no data loss — nothing is lost even
   if upload fails or the app is force-closed.
2. Attempt the GitHub push (both files) right away.
3. On failure (no network, API error), leave the entry queued locally;
   **WorkManager** retries in the background automatically when conditions
   allow (e.g. network available), per-file independently.
4. Once a file is confirmed pushed, delete its local app-private copy (the
   GitHub repo is now the source of truth; no need to also hold a permanent
   local copy on the phone).

## Permissions

- `RECORD_AUDIO`
- `INTERNET`
- `POST_NOTIFICATIONS` (Android 13+, if a retry/failure notification is
  ever shown — otherwise not needed beyond the in-app pending indicator)

## Explicitly Out of Scope

- Windows/Linux client or port.
- Cloud-based transcription (no API keys, no per-entry cost).
- Review/edit screen before send.
- In-app history/browsing of past entries.
- Atomic single-commit-per-entry (Git Data API).
- Multi-language auto-detection.
- Any LLM/to-do-reminder integration — this app's job ends at "entry is on
  GitHub."
