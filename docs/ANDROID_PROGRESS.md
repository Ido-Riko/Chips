# Android implementation progress

## First batch — 19 October 2026

Implemented the M1 app shell: dark theme, Today/History/Settings navigation, seven dashboard cards plus pinned Quick Log, explicit fictional preview mode, shared preview-completion actions, and an in-app voice entry point. Assistant setup no longer blocks the dashboard. Preview state is temporary and never submitted or presented as saved data.

The existing system overlay now uses the app theme. Its recording cleanup also observes lifecycle stop, background requests no longer mutate UI state from the IO dispatcher, and replies stay visible until dismissed. This is an incremental correction, not completion of M3: real STT, persistent history, robust recorder outcomes and cancellation of the underlying HTTP call remain open.

Aligned the Compose compiler with AGP 9's built-in Kotlin version; added the explicit lifecycle Compose/ViewModel dependencies used by the app. Fixed the obsolete instrumentation package assertion and backend mock patch target. Added preview-isolation unit tests, navigation/preview device tests, and Android CI.

### Evidence and remaining gates

- Remote source revision: `46f0c8510f0bdf2d1c55a779b23a62bb9cd9d857`; source files restored through the GitHub connector because shell GitHub DNS failed.
- Local Gradle command attempted: `bash ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`.
- Initial result: Java was missing. The owner subsequently installed Android Studio and updated local Gradle to 9.6.0 and AGP to 9.4.1; those changes are preserved.
- Local validation on 20 October: debug APK built, lint passed with zero errors and 29 warnings, all four unit tests passed (three preview-isolation tests and the existing example). Bundled JBR 25 and SDK Platform 36 were used. The offline attempt first reported an uncached Kotlin dependency; allowing normal dependency download resolved it.
- CI run `39322919423` passed all three API 35 instrumentation tests. That run's lint failure identified an API 27-only system-bar XML setting, which was removed; local lint now passes. Latest CI results are still pending.
- Local API 37 emulator validation on 20 October: all three instrumentation tests passed through `connectedDebugAndroidTest` (5 seconds), and direct instrumentation also reported `OK (3 tests)`. The old Espresso dependency initially failed on removed `InputManager.getInstance`; stable Espresso 3.7.0 and AndroidX JUnit 1.3.0 resolved that compatibility issue. A subsequent interrupted launch was diagnosed from an ADB force-stop in the log; the standard Gradle rerun passed. The debug APK was installed and the launcher activity opened successfully.
- The Android Studio unsupported-module notice came from an IDE-created plain Java module; its Remove unsupported modules and re-sync action was advised. The JDK warning was historical: `.gradle/config.properties` now contains the bundled JDK path. No local IDE or SDK paths are included in the PR.
- M1 remains open until build/lint/unit/device tests pass and the Poco acceptance checks in `chips-mobile/README.md` are recorded.
- M2–M5 remain open. No activity persistence, synchronization, real transcription, or live dashboard integrations are claimed.

Use `ANDROID_ROADMAP.md` for the full acceptance criteria and `chips-mobile/README.md` for build and manual test instructions.

## Visual refinement — 20 October 2026

Reworked the first dashboard after visual review: deliberate typography and a dated header, one prominent voice action, a sleep summary with score ring, a compact activity grid, paired Beat Saber/Japanese cards, smaller streak rings, grouped meal/drawing summaries, and slimmer Quick Log/navigation areas. Replaced text-symbol icons with a consistent set of native line icons. Settings and History use the same visual language.

The dark palette uses warm neutral text with restrained violet, sage, amber and rose accents. Empty states stay honest; sample values still require explicit preview mode and are labeled as fictional. Preview actions and persistence behavior are unchanged. Accessibility labels describe missing scores without announcing zero-percent data; paired layouts stack when text is enlarged.

Validated the normal and 1.5× text layouts visually on the local API 37 emulator; restored its original 1.0 font setting afterward. The redesigned UI passed build, lint, four unit tests and three instrumentation tests. The instrumentation assertion was adapted to the new Quick Log status label. After the final accessibility-label refinement, build/lint/unit checks passed again, but the emulator disconnected during test installation; that Gradle invocation reported success without producing device results and is not counted as a device-test pass. A temporary headless emulator also crashed at startup, so latest-source UI verification is left to CI or the next available device. Physical-device acceptance remains pending.

## Assistant settings button — 20 October 2026

Replaced the assistant-role request with the public voice/assistant settings action and a Default apps fallback. The old path only checked whether the assistant role existed, then requested it as if it were user-requestable; Android can reject that request immediately without displaying any UI. The button now opens system settings directly, retains lifecycle-based status refresh on return, and provides manual instructions if neither settings screen can be launched. Added next-screen guidance in the app.

The connected Poco resolves the dedicated action to `com.android.settings/.Settings$ManageAssistActivity` and the fallback to its system DefaultAppListActivity. Its assistant was Google during diagnostics; no assistant selection was changed by the agent. The fix built successfully with lint/unit checks passing and both APKs assembled. Added an Espresso Intents regression test for the actual button, with the outgoing settings intent stubbed so tests cannot change device defaults. Main app update installed on the Poco; separate test APK installation was canceled, so no new automated phone test result is claimed. The phone blocks ADB input injection, so the owner tapped the button manually and confirmed that assistant settings now open. The new regression test is compiled and awaits CI execution.

Platform evidence: the AOSP assistant role is marked `requestable="false"` in [roles.xml](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml); the supported settings actions are documented in [Settings](https://developer.android.com/reference/android/provider/Settings).

## Poco acceptance — 20 October 2026

The owner confirmed on the Poco X6 Pro that Dashboard, History and Settings work; denying microphone permission leaves the dashboard usable; returning from assistant settings refreshes setup status; and the repaired assistant button opens system settings. The owner explicitly waived large-text testing on the Poco as unnecessary. This is a waiver of that physical-device check, not a claim that it was run; the earlier 1.5× emulator visual inspection remains evidence.

All requested physical-device acceptance checks are now satisfied or waived. The latest code revision, `ee4307c9924e428802c92432751d7f565521332f`, has build and UI CI jobs running in workflow run `39482910525`. M1 remains open only until those checks pass; M2 has not started.


## M1 closure — 20 October 2026

Verified PR #1 remains open and draft, with latest head `7f1f34fb0dae85ae78d08bfd70517ad80852c635`. Both jobs (`build` and `ui-tests`) in [run 39483379703](https://github.com/Ido-Riko/Chips/actions/runs/39483379703) completed successfully, including build/lint/unit checks and API 35 instrumentation. The previous assistant-fix run `39482910525` also passed both jobs. Together with the owner’s Poco acceptance and explicit large-text waiver above, this closes M1. PR #1 is preserved without merging or changing its contents.

## M2 implementation — 20 October 2026

Implemented durable Workout logging first in the shared local storage path and extended it to Beat Saber, Drawing and Japanese. Today and the pinned Quick Log save sessions before displaying success. The Undo snackbar deletes only the session just created. History displays actual stored sessions and sync status, with activity/date/note editing and confirmed deletion. Fictional preview actions remain separate and never create stored records; toggling preview retains personal records.

The native SQLite schema stores UUIDs, capture timestamps, captured timezone/calendar date, notes, revisions, tombstones and pending/synced/failed states. Native SQLite was selected instead of the proposed Room dependency to keep the existing AGP/Kotlin setup intact. A voice-history table is reserved for M3; this does not claim voice persistence. Today and real streaks derive from these rows. Multiple sessions count once per activity/day; yesterday keeps a streak alive until today ends. Captured dates survive timezone changes, with explicit date corrections available in History. Day-boundary/DST tests use calendar dates.

Added a cancellable HTTPS API client and network-constrained WorkManager synchronization, including durable retries, manual retry and periodic refresh. Higher local revisions survive older acknowledgements. Permanent server tombstones prevent late retries from restoring deleted records. One enrolled originating device writes each record for the initial personal deployment; other devices for that owner can read, with general multi-device editing deferred. The first verified service/owner/device identity binds the database before uploading, preventing accidental cross-account submission.

Added backend owned activity create/read/update/delete routes, HTTPS/device-token checks, additive PostgreSQL migration and operator-only enrollment/rotation/revocation commands. Android encrypts the revocable token in Keystore-backed AES-GCM storage and excludes credential preferences from backup/transfer. Release builds disable cleartext traffic. The existing voice route now checks the same device credential and records ownership; its Android caller uses the enrolled endpoint/token. STT and persistent voice replies remain M3 work. Added backend CI with a disposable PostgreSQL service. Setup, conflict behavior, scope and recovery are documented in `docs/ACTIVITY_SYNC.md`.

### Executed local evidence

- Final Android command: `:app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest` with existing Gradle 9.6.0 / AGP 9.4.1 / JBR 25. Both APK variants built. Lint: zero errors, 34 warnings. Release manifest verified `usesCleartextTraffic="false"`; the release APK is unsigned, not a distribution release.
- Seven unit tests passed: original preview isolation plus daily completion, missing days, repeated sessions, deleted sessions, local midnight and DST boundaries.
- Nine API 37 emulator instrumentation tests passed with zero failures/errors/skips. XML report timestamp: `2026-10-20T19:44:32` UTC. Coverage includes existing navigation/assistant intent checks, SQLite reopen/edit/deletion, all four activity types, undo without losing an earlier session, logging through the UI and activity recreation, lost-response retries, an edit during sync, deletion retries, and Keystore encryption/reopen/HTTPS validation.
- Offline process restart: installed the debug APK on the temporary API 37 emulator, disabled Wi-Fi and mobile data, tapped Workout, inspected its saved row, force-stopped the app, relaunched it, and verified the identical UUID/revision/date in SQLite and a pending Workout in History. See [captured evidence](evidence/m2-offline-workout.json). This is an actual process-stop/relaunch check, separate from activity recreation.
- The emulator’s default GLES/SwiftShader and host renderers crashed during earlier attempts. ANGLE (`-gpu swangle -feature -Vulkan`) booted and completed the final tests and offline restart check. Earlier interrupted attempts are not counted as passes. The Poco was not modified during M2 validation.
- Twelve backend tests passed using Python 3.12 and the pinned application dependencies, including real PostgreSQL 16 migration/concurrency coverage. Checks cover repeated create/edit/delete, stale and conflicting revisions, deletion before initial upload, note removal on deletion, validation, missing/invalid/revoked credentials, cross-owner reads/deletes, original-device write restrictions and cleartext rejection. Concurrent revisions produced one row at the newest revision; later updates could not resurrect a tombstone. Reapplying the migration preserved existing voice data.
- The documented migration CLI and enrollment/authentication/revocation commands were also smoke-tested against the disposable local PostgreSQL database without printing credentials into the chat.

### Remaining M2 acceptance gates

M2 is implemented locally but remains open pending owner-device and deployed-service acceptance: run the Android README’s M2 Poco checks, enroll against the migrated trusted HTTPS backend, reconnect the actual device and verify exactly-once server state after repeated retries/restarts, edit/delete recovery, token revocation and an observed day boundary. Local tests cover these rules and failure orderings but do not claim a deployed HTTPS end-to-end run or a new Poco result. No deployment or remote database migration was performed. New M2 CI has not been pushed/run remotely.

PR #1 was left untouched; the owner is merging it separately. Local Git still has no baseline commit, so no reset, checkout, commit, branch or merge operation was used. The M2 changes remain in the workspace. M3–M5 remain open.

## Your rhythm uncheck interaction — 20 October 2026

Your rhythm now toggles today's activity completion directly. Tapping an unchecked tile saves a session; tapping a checked tile atomically tombstones every active session for that activity on the current local date, so repeated Quick Log entries cannot leave the tile checked. Other activities and earlier dates remain intact. Quick Log continues adding individual sessions, and its snackbar Undo still removes only the new session. Tile semantics expose the checked state to accessibility services, and the card explains the uncheck behavior. Preview toggles remain temporary.

Validated the follow-up with debug build, lint (zero errors), all seven unit tests and all eleven API 37 instrumentation tests passing. Two new regression tests cover all four tile interactions, repeated sessions, unchanged other activities/earlier dates, restart persistence, pending tombstones and creating a fresh UUID after rechecking. The final instrumentation XML reports eleven tests, zero failures/errors/skips, timestamp `2026-10-20T20:09:54` UTC. Earlier M2 backend/release evidence remains recorded above; no backend behavior changed in this follow-up. Updated draft PR #3 remains unmerged; CI must recheck the new head and Poco/deployed-service gates remain open.

## Compact-screen rhythm tap fix — 20 October 2026

CI run `39489799862` passed build/lint/unit checks but failed one of eleven API 35 UI tests: the second rhythm tap never removed the saved session. Reproduced the unchanged test locally at CI's exact 320×640 / 160-dpi viewport. Diagnostic semantics showed the Workout tile at y=428–471 while the newly displayed saved snackbar occupied the same region, intercepting the next touch. This was a real compact-layout interaction defect; increasing the timeout or retrying CI would not fix it.

Rhythm toggles now use their visible checkmark as success feedback and dismiss any previous snackbar, without displaying a new success snackbar over the tile. Quick Log retains its saved/Undo snackbar, and storage failures still show an error. Preview rhythm toggles follow the same direct interaction. Storage, deletion and synchronization behavior did not change.

After removing the temporary diagnostics, the original regression test passed unchanged. The full eleven-test UI suite passed locally at both 320×640 / 160 dpi (XML timestamp `2026-10-20T20:34:16` UTC) and the original 1080×2400 / 420-dpi viewport (`2026-10-20T20:34:51` UTC). Debug build, lint and all seven unit tests passed. The fix is submitted to draft PR #3 for a fresh API 35 CI run; no merge or new Poco/deployed-service acceptance is claimed by this check.

## Deployment preparation without the server — 21 October 2026

Repaired the existing transfer path: `deploy.ps1` now builds an offline source archive by default, supports explicit `-Upload -ServerIP HOST`, validates its remote arguments and checks native command exit codes. It no longer references the absent `alembic` directory, copies `.env`, changes remote ownership or reports a failed transfer as a completed deployment. The archive contains the actual SQL migrations, app sources, pinned requirements, OpenRC service, configuration examples and deployment instructions; secrets/caches/virtual environments are excluded. `dist/` is ignored by Git. Packaging works independently of the caller's working directory and does not need app settings or network access.

Updated OpenRC to a loopback-only Uvicorn upstream with forwarded headers trusted only from the same-host proxy. Service account/directory overrides live in the supplied conf.d example; startup guards reject missing executable/migration files. Added an optional Caddy HTTPS template, server-side `.env.example`, and `chips-backend/DEPLOYMENT.md` covering inspection/backup, extraction, dependency install, explicit migration, existing-secret preservation, proxy validation, enrollment and recovery. The service does not run migrations on startup. Actual host-specific configuration and certificate issuance remain unverified.

Validation completed locally:

- All 21 backend/deployment tests passed with PostgreSQL 16, Python 3.12 and PowerShell 7.4.6. New tests cover archive contents/secret exclusion, missing-migration failure without replacing a prior archive, symlink rejection, loopback/proxy arguments, startup guards, offline default behavior, paths containing spaces, packaging/transfer failure handling and unsafe remote arguments. Transfer tests use a fake SCP command and never connect to a server.
- PowerShell parser checks and POSIX service syntax passed. The service description loaded with real OpenRC in a disposable Alpine container; startup guards were exercised separately with isolated test directories rather than claiming a full booted OpenRC host test.
- The Caddy configuration validated with networking disabled, without attempting certificate issuance.
- Extracted the generated archive into `/tmp`, applied its migration to disposable local PostgreSQL and ran its app on loopback behind a real local Caddy HTTPS proxy. A temporary localhost certificate was explicitly trusted by the client, with verification enabled. HTTPS health/authentication, plaintext rejection, repeated create requests without duplicates, latest revisions, terminal deletion, revoked read/write rejection, and rotation of the same device credential with pending-record recovery passed. See [local HTTPS evidence](evidence/m2-local-https-deployment.json).
- Executed `deploy.ps1` in its default offline mode and produced `chips-backend/dist/chips-backend.tar.gz`. No real server upload, database change, proxy/service installation, restart or phone operation was performed.

Backend CI now checks PowerShell/POSIX tooling and validates the proxy template offline before the full test suite. These changes extend draft PR #3; actual deployment and Poco HTTPS reconnect/revocation checks remain open. Local HTTPS validation is separate evidence and does not mark the deployed-service acceptance check complete.

## Deployed M2 backend — 21 October 2026

The actual postmarketOS/OpenRC server was backed up and upgraded to M2. The additive migration completed at version 1, preserving both existing voice logs. An initial external-helper import-path failure occurred before migration; rollback restored the prior API, the helper was corrected, and activation then succeeded.

Uvicorn now listens only on loopback port 8000, behind private Tailscale Serve HTTPS with normal certificate verification. The host's separate drop-policy nftables chain required an explicit TCP 443 allowance on tailscale0; the obsolete general TCP 8000 allowance was removed. Rules were backed up and persisted without flushing the full firewall or changing SSH/Tailscale-managed rules.

Live isolated-owner checks passed authentication, owner isolation, idempotent create, revision conflicts/stale retries, permanent deletion, revoked reads/writes and same-device token rotation/pending delivery. Synthetic devices were revoked and test records retained as isolated terminal tombstones. A separate phone credential was enrolled and verified; credentials stay only in the restricted server enrollment file. See [server HTTPS evidence](evidence/m2-server-https-deployment.json), with the private hostname omitted.

Actual Poco reconnect/retry/restart/edit/delete, revocation/rotation and real day-boundary gates remain open. The owner reporting “Sync configured” does not by itself prove delivery.

## Saved synchronization token display — 21 October 2026

The owner reported that the token field became blank after saving despite the configured status. Settings now restores the saved endpoint/token from the existing encrypted credential store, displays token characters as asterisks, and retains the masked token after saving. Disconnect clears the displayed token. Credential storage remains encrypted; the UI state is not saved into an Android instance-state bundle.

Debug APK assembly, lint and existing unit tests passed with the cached Gradle dependencies (`:app:assembleDebug :app:lintDebug :app:testDebugUnitTest --offline`). No device is currently attached to ADB, so the updated APK has not been installed or visually checked on the Poco.

## Assistant presentation and agent brief — 21 October 2026

Replaced the plain assistant card with a shared bottom-sheet presentation for the in-app dialog and Android assistant session. It uses the established violet/mint theme, branded header, accessible close action, decorative waveform emblem, distinct ready/listening/processing/reply/error layouts, a reply card and a fixed hold-to-speak control. Content scrolls independently of the controls. System sessions reserve fingerprint-sensor space on tall screens. The UI labels voice as a preview and explicitly states that transcription uses demo text and replies are not saved; this does not complete M3. Unexpected send failures now return to a visible error state; existing recording and backend behavior otherwise remain in place.

Updated `CHIPS_phase1_agent_brief.md` with draft PR #3, real M2 deployment/migration/firewall/HTTPS evidence, published-token CI success, remaining phone/day-boundary gates and the assistant source map. It no longer treats M2 as unpublished local work or asks a future agent to repeat an already completed deployment.

Debug assembly, lint, all seven unit tests and all 13 API 37 emulator instrumentation tests passed. Two new tests verify compact-window recording/processing/close controls and independently scrollable long replies at 1.5× font size. Inspected the actual in-app ready screen on the emulator; captured preview retained locally. Automated tests were restricted to the emulator, not the owner's phone. Physical-device/system-invocation acceptance remains separate.

The updated debug APK was subsequently installed in place on the connected Poco using `adb install -r`, without clearing application data. No automated instrumentation or input was run on the phone.

## Disconnect preserves enrollment — 21 October 2026

Disconnect now persists a paused connection flag and cancels sync jobs while retaining the encrypted service URL/token, masked token field and activity records. A Reconnect action resumes using the saved enrollment; saving sync settings also reconnects. Both WorkManager scheduling and worker execution check the connected flag, so app relaunches and new local logs cannot silently restart paused synchronization. Existing enrollments without a flag remain connected after upgrading. Explicit voice requests continue to use the saved enrollment; this control pauses activity synchronization, not server-side token validity.

Updated the sync contract, Android README and agent brief to match. The regression test verifies retained ciphertext/configuration, paused state across credential-store reopening, Reconnect, Save reconnecting, and compatibility with previously enrolled installations. Debug build, lint, all seven unit tests and all 14 API 37 emulator instrumentation tests passed. The Poco is no longer attached to ADB at this checkpoint, so this newer APK has not been installed on it. Credentials removed by an older app version cannot be restored by this change and need to be entered once again.

## Poco live acceptance started — 21 October 2026

The Poco reconnected to USB and the owner requested M2 acceptance. Installed the latest disconnect-fix debug APK in place successfully. Both Android and backend CI for `9f5ce142ffa0c0290d7e01fad584caf57a20d5b9` completed successfully. Actual phone time is 21 October in IDT and its Tailscale peer is reachable. Trusted HTTPS health and enrolled activity reads returned 200.

Read-only comparison of the phone SQLite snapshot and the actual server's owned activity snapshot matched all 25 records exactly across ID/type/timestamps/timezone/date/revision/deletion/note hashes. All 25 are tombstones, with zero active records and zero pending/failed phone records. Saved enrollment and identity binding are present. This establishes a baseline, not acceptance of a new record or unobserved failure scenarios. Requested the owner's on-device Disconnect/reopen/masked-token check; paused logging/reconnect/retry, edit/delete/restart, revocation/rotation and real day-boundary checks remain pending. See [ongoing Poco evidence](evidence/m2-poco-live-acceptance.json).

## Poco logging and credential recovery accepted — 21 October 2026

The owner completed the interactive phone scenarios and each stage was independently compared against the actual service. Disconnect retained the encrypted enrollment and masked token after reopening, with the persisted connection flag false. A new Workout survived reopening as PENDING on the phone while absent from the server. Reconnect/retry/reopen delivered that exact UUID once at revision 1. An edited test note survived reopening locally at revision 2 while the service retained revision 1, then synced to the same server row at revision 2. Deletion survived reopening and synchronized to a terminal tombstone at revision 4 with the note cleared. Counts and all compared fields agreed after each completed sync; no duplicate UUID or restored deletion was found.

Revoked the actual phone device token: identity, reads and writes returned 401. The owner added two local entries and retried; the phone retained both with no server upload and reported “Device access rejected. Check enrollment in Settings.” Operator-only database reads confirmed the server snapshot stayed unchanged while enrolled API access remained rejected. Rotated the token for the same owner/device UUIDs, stored it only in the restricted enrollment file, and verified old token 401 / replacement token 200. After the owner entered the replacement and retried/reopened, both pending UUIDs arrived exactly once. The original phone database identity binding was unchanged and the earlier deleted Workout remained deleted.

Final comparison: 28 records on phone and server, two active entries, zero pending/failed phone records, zero missing records and zero differing fields (including note hashes). Android/backend CI for the installed source `9f5ce142ffa0c0290d7e01fad584caf57a20d5b9` passed. Detailed non-secret [Poco evidence](evidence/m2-poco-live-acceptance.json) records each stage. M2 remains open only for real local-midnight and physical-device timezone acceptance, plus review/merge after any required latest-head checks. No midnight/timezone result is inferred from automated calendar tests or the same-day sync checks.
