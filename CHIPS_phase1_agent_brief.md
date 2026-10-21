# CHIPS — M1 (formerly Phase 1) Results and Android Agent Brief

Updated 21 October 2026. **M1 is complete. M2 is implemented and its server is deployed; physical-device synchronization and day-boundary acceptance remain open.** This document replaces the original mock-dashboard build instructions with the accepted implementation, current evidence, and delivery order.

Use **M1–M5** in new chats, issues and implementation work. This historical filename is retained for continuity. The canonical numbering, status table and retired-phase mapping are in [ANDROID_ROADMAP.md](ANDROID_ROADMAP.md#milestone-names-and-current-plan); do not maintain a parallel Phase 2–8 schedule.

## Read this before continuing

- Android is the current product focus. The first release is a personal app for the owner on a Poco X6 Pro.
- The Android project is `chips-mobile/`, not `chips-android/`. Preserve the existing Kotlin/Compose implementation and the accepted visual design.
- PRs [#1](https://github.com/Ido-Riko/Chips/pull/1) and [#2](https://github.com/Ido-Riko/Chips/pull/2) are merged. M2 is in [draft PR #3](https://github.com/Ido-Riko/Chips/pull/3), targeting `master` at base `3cd553972c3701d59e812e8d07046b058123380a` in the last inspected snapshot. Do not infer the current master SHA from older audit notes.
- The shared local workspace contains M2 and subsequent assistant UI work. M2 is **published as a draft, not merged or fully accepted**. Preserve local changes; do not rebuild logging from scratch or overwrite this workspace while obtaining a checkout.
- [ANDROID_ROADMAP.md](ANDROID_ROADMAP.md) defines milestones M1–M5. [docs/ANDROID_PROGRESS.md](docs/ANDROID_PROGRESS.md) records execution evidence in the workspace. Its later M1-closure/M2 entries extend the earlier history; earlier pending-status notes are historical.
- The original Phase 2–8 labels are superseded by M2–M5 below. In particular, current **M2 means durable logging**, not the original brief's Beat Saber integration phase.

## What this chat delivered: completed M1

### App home and design

CHIPS opens directly to Today. Dashboard, History and Settings are available without microphone permission or device-assistant enrollment. History in the merged M1 build is an honest empty state, not saved history.

The initial scaffold was redesigned following owner feedback. The accepted dark interface has deliberate typography, a dated header, one prominent Ask CHIPS action, a sleep-score ring, a compact activity grid, paired Beat Saber/Japanese cards, smaller streak rings, grouped meal/drawing summaries, and slimmer pinned Quick Log/navigation areas. Native line icons replace text-symbol icons. Layouts stack for larger text where appropriate.

The palette uses warm neutral text with restrained violet, sage, amber and rose accents. The original exact colors, card order, fixed spacing and per-widget file layout are no longer implementation requirements. Continue the established theme in `ChipsTheme.kt` rather than creating a second theme system.

### Honest preview behavior

Sample values live in `DemoData.kt` and require explicit opt-in through Settings → Show preview data. The dashboard labels them as fictional and says nothing is saved. Normal mode does not invent personal scores or history.

Today and Quick Log share one action path. In merged M1, preview actions toggle temporary completions; leaving preview clears them. Normal-mode taps explain that saving is not connected. **M1 did not implement the original Quick Log POST or persist activities.** Real logging is M2 work, already implemented separately in the local workspace as described below.

### Assistant setup and incremental voice fixes

- Microphone permission and device-assistant selection are available from Settings; neither blocks the dashboard.
- Ask CHIPS opens voice in the app when microphone permission is granted, or directs the owner to Settings when it is missing.
- Choose device assistant opens `Settings.ACTION_VOICE_INPUT_SETTINGS`, then tries `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` if the dedicated route is unavailable/restricted. The previous assistant-role request could return canceled without showing a picker and was removed.
- Returning from Android settings refreshes microphone/assistant status.
- The system overlay uses the app theme. Recording cleanup observes lifecycle stop; background requests no longer update Compose state from the IO dispatcher; replies remain visible until dismissed.

These are foundation fixes. Full recorder failure handling, underlying request cancellation, real transcription, saved voice replies, system invocation and lock-screen behavior are not certified by M1. The backend STT implementation still returns a fixed test transcription; real voice is M3.

### Eight-widget coverage

All eight original dashboard areas are represented, but a visible widget is not a completed live integration.

| Original widget | Accepted M1 result | Remaining real-data work |
| --- | --- | --- |
| Sleep | Score ring, duration/HR and native sparkline in preview; explicit missing-source state normally | Connect the owner's sleep source; attribute/define the score before presenting it as measured data |
| Today plan | Four activity controls under Your rhythm: Workout, Beat Saber, Drawing, Japanese | M2 durable activity completion; later scheduling if required |
| Streak rings | Per-activity preview rings and missing-history states | M2 counts from saved calendar-day records |
| Beat Saber | Preview accuracy, trend and focus text; missing-source state | M4 score import/deduplication and real trends; distinguish manual from generated focus |
| Kanji | Preview character, reading and meaning; empty practice state | M4 sourced content and persistent reviews/SRS |
| Suggested meal | Meal/pantry preview within the grouped summary | M4 actual pantry, preferences, stock thresholds and suggestions |
| Drawing | Preview feedback or no-drawings state within the grouped summary | M4 image picker/upload, retained thumbnail, real/manual feedback and media deletion |
| Quick Log | Pinned compact controls; shared preview path and honest unsaved feedback | M2 local save, undo/history, visible synchronization state and retries |

No drawing thumbnail, image picker, real food/sleep/score integration or kanji review system was completed in M1. Sleep-score thresholds from the old mock spec are not a validated scoring model. Sparklines use Compose Canvas; Vico was not added and is not a prerequisite for this foundation.

## Build and source map

Pinned project configuration at this audit:

| Item | Current value |
| --- | --- |
| Android directory / application ID | `chips-mobile/` / `com.chips` |
| Minimum / target / compile SDK | 26 / 36 / 36 |
| Android Gradle Plugin / Gradle wrapper | 9.4.1 / 9.6.0 |
| Kotlin / Compose compiler plugin | 2.2.10 / 2.2.10 |
| Compose BOM | 2024.09.00 |
| AndroidX JUnit / Espresso | 1.3.0 / 3.7.0 |
| Local validated JDK / CI JDK | Android Studio bundled JBR 25 / JDK 17 |

Use [chips-mobile/README.md](chips-mobile/README.md) for build commands, endpoint configuration and device checks. Keep local SDK paths, IDE configuration, tokens and provider keys out of Git. The server was inspected and deployed on 21 October; use the current server evidence below rather than the original brief's infrastructure assumptions. A migration has already been applied; do not repeat deployment setup without inspecting the current installation.

The M1 source is organized as follows; the proposed separate `widgets/` files were not needed:

```text
chips-mobile/app/src/main/java/com/chips/
├── MainActivity.kt                    # Permissions, assistant settings and status refresh
├── dashboard/
│   ├── DashboardScreen.kt             # Dashboard sections, rings, sparklines, Quick Log
│   ├── DashboardViewModel.kt          # Immutable state exposed through StateFlow
│   └── DemoData.kt                    # Explicit fictional preview values
├── ui/
│   ├── ChipsApp.kt                    # Today / History / Settings and in-app voice entry
│   ├── ChipsIcon.kt                   # Native line icons
│   ├── AssistantOverlayContent.kt
│   ├── AssistantPanel.kt              # Shared assistant presentation and state layouts
│   └── theme/ChipsTheme.kt
├── assistant/                        # System assistant registration/session
├── audio/AudioRecorder.kt
└── network/ApiClient.kt
```

M2 adds `data/`, `ActivityHistoryScreen.kt`, `SyncSettingsCard.kt` and backend activity/auth/migration code. Settings restores the saved service URL and shows the saved token as asterisks, including after reopening; Disconnect clears the token field. Inspect the actual files before planning more logging work. Do not replace the M2 database with a new Room implementation merely because Room was suggested in the roadmap; native SQLite is the current implementation.

## M1 acceptance and evidence

- [x] App opens to the dashboard with Today, History and Settings navigation.
- [x] All eight original dashboard areas are represented, with a consistent dark theme and pinned Quick Log.
- [x] Preview is explicitly enabled/labeled and cannot create saved history.
- [x] Normal-mode empty states do not claim personal scores or successful saves.
- [x] Build, lint and unit checks passed. M1 retains lint warnings; this is not a zero-warning claim.
- [x] Automated navigation/preview tests and the assistant-settings intent regression passed in CI.
- [x] Owner confirmed Dashboard, History and Settings work on the Poco.
- [x] Owner confirmed microphone denial leaves the dashboard usable.
- [x] Owner confirmed Choose device assistant opens system settings and returning refreshes setup status.
- [x] Larger text was visually inspected at 1.5× on the emulator. **The owner explicitly waived large-text testing on the Poco.** Do not report that physical-device check as executed or require it again to close M1.
- [x] PR #1 merged; foundation work is available on master.

The assistant-button code revision `ee4307c9924e428802c92432751d7f565521332f` passed both jobs in [CI run 39482910525](https://github.com/Ido-Riko/Chips/actions/runs/39482910525). The final PR head `7f1f34fb0dae85ae78d08bfd70517ad80852c635` also passed both jobs in [CI run 39483379703](https://github.com/Ido-Riko/Chips/actions/runs/39483379703). CI built/linted/tested the app and ran API 35 instrumentation. M1 has four unit tests and four instrumentation tests, including two basic template checks; this is limited foundation coverage, not proof of recording/network/lock-screen reliability.

The owner accepted the revised visual direction as usable. Device acceptance covers the stated navigation/permission/setup scenarios; it is not a general no-crashes guarantee for every voice or background scenario. The CI results above describe M1 history, not validation of later source changes; use the progress ledger for current checks.

## Revisited plan: keep the product goal, change the delivery order

The old plan put multiple integrations ahead of durable logging and allowed the only write action to fail silently. That would make a convincing preview but leave the owner unable to trust it. The revised plan makes saved personal records and recovery the foundation for later integrations, and closes each milestone against its actual acceptance evidence.

| Milestone | Outcome | Current status and gate |
| --- | --- | --- |
| M1 — Useful Android home | Dashboard/navigation/theme, explicit preview, optional setup, tests/CI | Complete; owner device checks passed or were explicitly waived; PR #1 merged |
| M2 — Trustworthy daily logging | Offline records, shared Today/Quick Log/History, undo/edit/delete, real streaks, authenticated sync | Draft PR #3; actual HTTPS backend deployed and operator sync checks passed; phone reconnect/revocation/rotation and real day-boundary checks remain open |
| M3 — Real voice assistant | Actual transcription/answers, safe recording lifecycle, cancellation, structured errors and persisted conversations | Not complete; fixed STT test string remains a blocker |
| M4 — Personal dashboard integrations | Real Japanese, drawing, Beat Saber, sleep and pantry/meal data, one source at a time | Not complete; choose/validate actual sources and demonstrate each integration |
| M5 — Dependable personal beta | Upgrade/recovery/export/deletion, diagnostics, device coverage and a seven-day owner trial | Not complete; a signed/distributed release and trial are not implied by a debug build |

### Immediate next work: finish accepting M2, not another dashboard scaffold

Current source contains SQLite activity storage, real History/edit/delete/undo, daily streak rules, WorkManager sync, Keystore-protected enrollment credentials, and owned backend activity routes/migration/enrollment tooling. Normal-mode activity actions save locally; preview remains isolated. The activity sync contract uses stable UUIDs and `/api/v1/activities` operations, not the original brief's proposed `POST /api/v1/activity-log`.

Recorded M2 evidence includes seven unit tests, eleven API 37 instrumentation tests after the rhythm-toggle fixes, 21 backend/deployment tests with PostgreSQL coverage, and offline force-stop/relaunch persistence. Android and backend CI passed at PR head `06fb9f18259b4c083068b7650f90e9c8379a9f14`. The saved-token fix at `091e156111e251f03a6646c59a63bf04fd007774` also passed [Android CI](https://github.com/Ido-Riko/Chips/actions/runs/39627667148) and [backend CI](https://github.com/Ido-Riko/Chips/actions/runs/39627667118); both results were checked on 21 October. These results do not automatically validate subsequent assistant UI changes.

### Actual server state verified on 21 October

- The server runs postmarketOS/aarch64, Python 3.12, OpenRC, PostgreSQL and Redis. M2 is installed in a separate release directory; the previous application and restricted database/config backups were retained.
- The additive migration completed at schema version 1 and preserved both existing voice logs. The first activation failed before migration due to a helper import path; this was fixed and the successful rerun independently verified.
- Uvicorn listens only on loopback port 8000. Tailscale Serve provides private HTTPS with normal certificate verification; no public registration or public internet exposure was added.
- The host firewall now allows TCP 443 through `tailscale0`; the obsolete general TCP 8000 allowance was removed and the change persisted. Do not reload the full nftables ruleset casually: its configuration flushes rules, including Tailscale-managed state.
- Live isolated-owner HTTPS checks passed: device authentication, owner isolation, repeated create without duplicates, stale/equal-revision handling, terminal deletion, revoked reads/writes, and same-device token rotation/pending delivery. Test devices were revoked; test tombstones remain separate from the phone owner's records.
- A separate phone device credential was enrolled and verified. Its token exists only in a restricted server-side enrollment file; never include it in this brief, Git, screenshots or chat. The owner reported “Sync configured” in the app, but that alone is not proof of activity delivery.

See [server HTTPS evidence](docs/evidence/m2-server-https-deployment.json) and the dated entries in [the progress ledger](docs/ANDROID_PROGRESS.md). Server tests do not substitute for phone acceptance.

Before closing M2:

1. Preserve local changes and inspect draft PR #3 before publishing further work. Local Git still has no baseline commit; do not reset or overwrite this workspace to match master.
2. Preserve the Poco offline checks already marked accepted in PR #3; inspect the latest evidence before repeating them. Install/check the latest UI build separately as needed.
3. Connect the Poco to Tailscale and confirm its configured CHIPS service and enrolled device. Verify actual offline records reach the deployed server exactly once after retries/restarts, with correct edit/delete recovery and no restored deletions.
4. Test revocation on the actual phone: remote requests fail while local logging works; rotate that same device identity and recover pending records. Do not replace the owner/device UUID or service URL casually; Android binds its activity database to them.
5. Observe a real local midnight and confirm streak/date behavior and retained captured dates across timezone changes.
6. Check CI for the latest published head, review and close remaining gates before merging M2. Passing M1 CI or operator-only server tests do not close the physical-device gates.

### Assistant presentation update — 21 October

The shared in-app/system assistant now has a dedicated bottom-sheet layout in the established CHIPS theme: branded header and close action, a decorative voice emblem, distinct ready/listening/processing/reply/error states, a readable reply card, and a fixed hold-to-speak control. Long content scrolls independently of the controls; system invocation retains fingerprint-sensor clearance on tall screens. Voice preview labeling explicitly says transcription uses demo text and replies are not saved. This visual work does not close M3 or prove lock-screen/recording reliability. Consult the progress ledger for build and emulator validation of the latest UI source.

### After M2

Complete M3 before treating voice as a working personal assistant. Choose the owner's spoken languages and transcription/answer provider using representative recordings. Keep provider keys on the backend. Prove the full speak → actual transcript → answer → retained history flow and its failure/cancellation cases. Verify system invocation and locked-device behavior separately on the Poco.

For M4, reuse M2's saved activities for Today, Quick Log and streaks. The roadmap's proposed next integration order is Japanese → drawing → Beat Saber → sleep → meals/pantry; confirm source availability and owner priorities as each is started. Retain the original domain ambitions, including drawing uploads/feedback and kanji reviews. Every enabled card needs an actual source, cache/freshness indication, useful actions, deletion behavior and visible recovery. No source means an honest unavailable/manual-input state, not invented scores.

M5 then validates normal updates, populated-database migration, backup/restore/export/deletion, minimal diagnostics without personal transcripts/tokens, accessibility, minimum/target Android support, and the seven-day Poco trial. The M1 large-text waiver is recorded; do not silently convert it into a claim that broader release accessibility has been certified.

## Superseded instructions: do not reintroduce these

| Original instruction | Revised decision |
| --- | --- |
| Open setup before the dashboard when CHIPS is not the default assistant | Dashboard always usable; setup is optional in Settings |
| Populate all widgets with mock values by default | Fictional preview is explicit opt-in; normal mode reflects actual availability |
| Add Vico and prescribed widget/theme files | Continue the existing Canvas charts and source structure; add dependencies only for a demonstrated need |
| Quick Log POSTs to an assumed route and silently fails | Save durably before success; visible pending/failed sync and safe retries; use the implemented M2 contract |
| One mutable StateFlow per mock field | Keep the current immutable UI state and storage/repository boundaries; preview never owns production data |
| Leave assistant implementation unchanged from Phase 0 | Retain the settings-route, lifecycle and theme fixes already made; complete voice reliability in M3 |
| Beat Saber → sleep → pantry → real streaks → drawing → SRS as separate Phases 2–8 | M2 durable logging/streaks → M3 reliable voice → M4 validated domain integrations → M5 beta |
| Treat a rendered mock card or a sample score as a finished integration | Track the card shell and its real data/action acceptance separately |
| Assume the named LAN backend, database and Redis are running | Revalidate the actual deployment, configuration and authorization before using or changing it |

Do not add new platform targets, public registration, billing, wake-word listening or autonomous phone actions to this delivery without a separate scope decision. The immediate objective remains a trustworthy personal Android app.
