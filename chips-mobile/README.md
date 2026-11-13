# CHIPS Android

The app opens to Today, with History and Settings available from the bottom navigation. Assistant enrollment and microphone access are optional for browsing. Settings can enable fictional dashboard previews; preview taps never persist or contact the backend.

## Build

Use Android Studio with support for Android Gradle Plugin 9.4.1 and Android SDK Platform 36. The checked-in Gradle wrapper uses Gradle 9.6.0. CI uses JDK 17; the local build also passed with Android Studio's bundled JBR 25. Kotlin and the Compose compiler use version 2.2.10.

The Gradle and AGP versions include the updates applied during Android Studio setup. Keep the [Compose compiler version aligned with Kotlin](https://kotlinlang.org/docs/compose-compiler-migration-guide.html).

Open this directory in Android Studio, let it create `local.properties` with your `sdk.dir`, then run:

```sh
bash ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
bash ./gradlew :app:connectedDebugAndroidTest
```

The second command requires an emulator or USB-connected device with debugging enabled. The app supports API 26 and later and currently targets API 36. CI builds, lints, runs unit tests, and runs navigation/preview instrumentation on an API 35 emulator. M1 passed CI and the owner’s Poco acceptance checks; Poco large-text testing was explicitly waived. Minimum/target API coverage remains a later release gate.

## Daily logging and synchronization

The Activities card opens Workout, Beat Saber, Drawing and Japanese. Japanese reviews/drawing imports save their linked activities; Workout explicitly confirms manual logging and Beat Saber has Log completed session. History, Today and streaks use those same records. Opening a feature never changes completion. The Undo snackbar removes only the manual session just added. History supports editing the activity, date and note, and confirmed deletion.

The calendar date and IANA timezone are captured with each session. Travel does not reassign old sessions to another date. Today follows the phone’s current local date and updates while the app stays open. A streak counts consecutive completed dates ending today, or yesterday if today is incomplete. An explicit date correction in History changes completion/streak attribution without changing the original capture timestamp.

Local logging needs no network or enrollment. Sync requires an operator-enrolled device token and an HTTPS endpoint ending in `/api/v1`. Enter both in Settings → Synchronization. See [service setup and contract](../docs/ACTIVITY_SYNC.md). Pending/synced/failed states appear in History; retries run through persistent WorkManager jobs, with a manual Retry sync action. A force-stopped app resumes its work after it is opened again. Disconnect pauses synchronization and cancels background jobs while keeping the encrypted URL/token and all activities. The paused state survives app restarts; Reconnect resumes with the saved enrollment. Saving sync settings also reconnects.

## Beat Saber

Normal-mode Today → Beat Saber → Explore scores opens the real-data explorer. Enter a BeatLeader player ID; the phone calls public BeatLeader/BeatSaver APIs directly. No CHIPS server, enrollment or Tailscale is needed. Imports/map details need internet; saved-score browsing, reopening and local removal work offline. The explorer puts the chart first: tap metric names to change axes, pinch to zoom, or use zoom/pan buttons and Reset. Profile/import details and chart help stay tucked away. A point tap highlights/previews its song and temporarily puts it first in the list; a second tap, preview tap or song-card tap opens details. Sort/filter/axis/view changes clear the temporary ordering. Sort saved songs by BPM/date/stars/accuracy/PP, and open ratings plus exact-version map details. BPM sorting reuses imported/cached metadata without per-song API calls; unknown BPM stays last. See [the API/storage contract](../docs/BEAT_SABER.md). M4 follows the owner's local-first rule; server use is optional for sync or compute that actually needs it.

## Voice service

Android now requires the [version-1 voice service contract](../docs/VOICE_CONTRACT.md). The existing demo/plain-text server needs an update; server work is deferred. Android checks authenticated `/voice-capabilities` before sending audio, and requires real structured transcript/answer responses and advertised request idempotency. Settings → Synchronization supplies the HTTPS endpoint/device token. STT and LLM model/provider selection is independently configured on the service; PC and explicitly selected API providers are supported by the contract. Provider keys stay on the service.

To use the overlay, select CHIPS as the device assistant from Settings. To use voice without changing that selection, grant microphone access and tap Ask CHIPS on Today. Lockscreen voice/replies and feature screens are enabled by default. Settings → Assistant security → Allow assistant while locked can require unlocking instead. Settings explains that personal information may be visible to anyone holding the phone; changing the switch itself requires an unlocked phone.

Captures use MPEG-4/AAC (`.m4a`, `audio/mp4`), reject clips shorter than 400 ms, and stop/discard at 60 seconds. Cancelled hold gestures discard audio. Closing/backgrounding cancels recording/network work. Uploading, processing and saving have separate visible states. Retry request reuses the recording and request UUID; Retry saving only repeats local storage. Discard/close removes retry audio. A cold app start clears clips abandoned by process death.

Successful real transcripts/replies are saved to SQLite before success is shown and appear in History → Conversations. Read conversation expands both texts; deletion removes the local copy after confirmation. Server copies have separate retention/deletion. Malformed, mismatched, demo and legacy responses are never saved as personal conversations. Activity history and synchronization are unaffected.

## Manual acceptance checks

- Fresh launch opens Today without a permission prompt or assistant requirement.
- All dashboard sections scroll; only bottom navigation stays pinned. The Activities card replaces the duplicated rhythm/Quick Log controls. Activity buttons use a two-column grid at normal text size and stack at larger text sizes. Verify both gesture/button navigation on the Poco X6 Pro.
- Normal mode has no fictional scores. Settings → Show preview data explicitly enables sample values and charts.
- Activities and streak preview actions share completion state and never create history. Turning preview off clears those completions.
- Ask CHIPS without microphone permission opens Settings. Denial leaves normal navigation usable; permanent denial has an App settings route.
- Returning from Android settings refreshes permission/assistant status. Check in-app and system-overlay recording separately.
- Choose device assistant opens the system assistant-settings screen, with a Default apps fallback if the manufacturer omits that screen. The assistant-button regression test intercepts the outgoing intent and never changes the selected assistant.
- Background/dismiss during recording releases the microphone and discards the clip; dismissal during a request cancels the HTTP call. Cancel a hold gesture and verify nothing is submitted. Try a very short hold, microphone refusal, unavailable service and repeated presses; each should allow a new recording. Emulator regression coverage exists for recorder cleanup, gesture cancellation and transport cancellation. Actual emulator system invocation and secure-lock privacy checks passed. Real transcription and physical-device microphone contention/revocation and system/lock-screen acceptance remain M3 gates.

## Validation status

Source restored from GitHub commit `46f0c8510f0bdf2d1c55a779b23a62bb9cd9d857` on 19 October 2026. After Android Studio installation, the local debug build, lint and all four unit tests passed on 20 October with Gradle 9.6.0, AGP 9.4.1 and bundled JBR 25. Lint reported zero errors and 29 warnings; those warnings remain follow-up work.

All three instrumentation tests also passed locally on the Android 17 / API 37 emulator using the standard `connectedDebugAndroidTest` task. Espresso was updated to 3.7.0 and AndroidX JUnit to 1.3.0 after the old Espresso version failed on a removed platform method. PR #1’s latest head passed build and API 35 UI jobs in CI run 39483379703. The owner confirmed the M1 Poco checks and waived large-text testing; M1 is closed. Physical-phone lock-screen invocation remains M3 acceptance work. The completed Android client implementation and actual emulator system/lock checks are recorded in `docs/ANDROID_PROGRESS.md`.

## M2 device acceptance

1. Turn off Wi-Fi/mobile data. Open Japanese/Drawing/Beat Saber from Activities and confirm opening never creates/deletes completion. Complete a review/import and confirm Today, History and streaks agree. Save two manual Workout sessions, then Undo the second and confirm the earlier remains. Use History for explicit editing/deletion.
2. Force-stop CHIPS from Android App settings, reopen it, and confirm the first session remains. Repeat for the other activities.
3. In History, edit an activity/date/note, then delete it. Restart while offline and confirm each change persists.
4. Enroll the device against the migrated HTTPS service. Reconnect, retry repeatedly and restart during sync. Each stable ID must appear once on the server, with the latest revision and no restored deletions.
5. Observe Today across local midnight, including sessions around midnight; confirm yesterday keeps a streak alive only until the next incomplete day. Changing timezone must retain each session’s recorded date.
6. Revoke the device token on the server. Read/write requests must fail; local logging remains usable. Rotate the token for the same device UUID and retry pending changes.

These are M2 acceptance checks, separate from the completed M1 Poco checks. See the progress ledger for executed evidence.

## M3 Android acceptance

The client implementation passes debug/release assembly, lint, 24 JVM tests and 29 API 37 instrumentation tests. A normal emulator installation passed actual system invocation and secure-lock privacy checks. A real force-stop/relaunch retained an isolated synthetic saved conversation and removed abandoned audio. See [final evidence](../docs/evidence/m3-android-client-acceptance.json) and the [repeat test report](../docs/evidence/m3-pr4-retest.json). GitHub CI for tested implementation head `b4804b3` also [passed](https://github.com/Ido-Riko/Chips/actions/runs/43114913852).

Full M3 still requires the deferred compatible service and Poco acceptance. Once the service is ready, verify ten representative spoken utterances, real transcripts/answers retained after restart, independent PC/API STT and LLM configuration, unavailable-PC recovery, permission denial/revocation/contention, interrupted uploads/processing, rapid invocation, same-ID retries and unlocked/locked system invocation. Server deduplication/cancellation cannot be certified by client tests alone.

## Japanese and drawing

Normal-mode Today → Japanese → Study opens 30 attributed offline kanji, answer reveal and saved self-rated reviews (up to three new cards/day). Today → Open sketchbook imports images through the photo picker, retaining local images/thumbnails/notes. Send for feedback queues server upload/analysis; saved drawings remain usable offline. Server storage retains the normalized JPEG and configured AI feedback. Each feature links completion to activity history without duplicate retries. See [behavior, source attribution and server setup](../docs/JAPANESE_DRAWING.md). The server is offline at implementation time, so deployment and real-model acceptance are still required.

## Pantry

Today → Open pantry supports local groceries, barcode lookup/scanning, receipt drafts and stock adjustments. Server sync retains the immutable inventory ledger in PostgreSQL and can restore it. Photo transcription requires the configured server vision model; offline manual entry works. See [pantry contract and acceptance](../docs/PANTRY.md).

## Connected activity shortcuts

The single Activities card and streak rings open Japanese practice, drawing history or Beat Saber. Real reviews/imports update the same saved activities and streaks; opening a feature never toggles completion. Workout offers an explicit manual log, and Beat Saber has Log completed session with Undo. Ask CHIPS and Android’s digital assistant expose the same shortcuts plus Pantry without microphone permission or a server. The system assistant hands off to the requested screen, including when the app is already open; lockscreen access follows the Assistant security switch (default on).
