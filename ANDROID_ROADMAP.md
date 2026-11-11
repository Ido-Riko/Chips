# CHIPS Android: demo to daily-use app

Prepared 19 October 2026. Updated 12 November 2026. M1 is complete. M2 was merged on 21 October; server/Poco sync and recovery are accepted, with real-midnight/timezone acceptance still open. Android M3 recording/request reliability is in progress; server inference work is deferred by the owner.

## Outcome and scope

Build a personal Android assistant and daily dashboard that can be used every day: open CHIPS, see an honest summary of today, log an activity, ask a voice question, and find the result later. Saved data must survive restarts; failures must be visible and recoverable.

Planning assumption: the first release is for the owner on the Poco X6 Pro named in the existing brief. A public, multi-user release is a later decision. Android is the primary workstream; backend changes below are dependencies required to make Android features real.

Keep Kotlin, Jetpack Compose, and the existing Android assistant integration. Build incrementally in `chips-mobile/`. Preserve the eight-widget dashboard intent from the original brief; its silent-failure and mock-only instructions have been superseded in [CHIPS_phase1_agent_brief.md](CHIPS_phase1_agent_brief.md). Wake-word listening, autonomous phone actions, iOS, billing, and public account registration are outside the first release.

## Milestone names and current plan

Use **M1–M5** as the only current delivery numbering. "Phase 1" is a historical alias for M1; the filename `CHIPS_phase1_agent_brief.md` is retained for continuity. The old Phases 2–8 are retired, not extra milestones to execute after M5. The product domains remain in scope; their delivery order and grouping changed.

| Current milestone | Deliverable | Status on 12 November 2026 |
| --- | --- | --- |
| M1 — Useful Android home | Dashboard, navigation, preview, optional assistant setup, build/tests/CI | Complete; PR #1 merged, Poco checks passed, Poco large-text test explicitly waived |
| M2 — Trustworthy daily logging | Durable offline activity records, History, undo/edit/delete, real streaks and authenticated synchronization | Merged in PR #3; deployed HTTPS and Poco sync/edit/delete/revocation/rotation passed; real-midnight/timezone acceptance remains |
| M3 — Reliable real voice | Actual transcription and answers, safe recording/cancellation, recoverable errors and saved conversations | Android recording/request reliability underway; real transcription and saved conversations remain; server work deferred |
| M4 — Real dashboard integrations | Japanese, drawing, Beat Saber, sleep and meals/pantry connected to actual personal data | Not complete; deliver and accept one integration at a time |
| M5 — Dependable personal beta | Safe updates and recovery, export/deletion, diagnostics, device coverage and seven-day owner trial | Not complete |

| Retired phase | Where its work now belongs |
| --- | --- |
| Phase 1: dashboard shell | M1 — complete |
| Phase 2: Beat Saber | M4 — Beat Saber integration |
| Phase 3: sleep | M4 — sleep integration |
| Phase 4: pantry/meals | M4 — pantry and meal integration |
| Phase 5: real streaks | M2 — derived from durable activity records |
| Phase 6: drawing analysis/uploads | M4 — drawing integration |
| Phases 7/8: kanji reviews/SRS | M4 — Japanese integration |

M3 makes the existing voice prototype reliable and real; M5 adds a release/acceptance gate. Neither is a renaming of an old individual integration phase. The remaining M2 gate is physical midnight/timezone acceptance. Android-only M3 reliability work can proceed while the owner defers server work; M2 is not yet closed.

M1 and M2 code are on `master`; PR #3 merged on 21 October at `e3d4eea07708e20f138387b78dde4107546bee10`. Actual server and Poco synchronization/recovery evidence is recorded; remaining day-boundary/timezone gates still prevent M2 closure. Inspect the actual worktree and [docs/ANDROID_PROGRESS.md](docs/ANDROID_PROGRESS.md) before continuing.

## Initial baseline — historical, 19 October 2026

The following table describes the starting snapshot, **not the current implementation or remaining task list**. The original inspection used GitHub `Ido-Riko/Chips` at commit `46f0c8510f0bdf2d1c55a779b23a62bb9cd9d857` because the local Git repository had no source checkout. No build/device/deployment verification had been performed at that initial point. Use the current milestone status above and dated progress entries for subsequent results.

| Area | Observed source | Implication |
| --- | --- | --- |
| Launcher | `MainActivity.kt` renders assistant setup only | No everyday app home or dashboard yet |
| Dashboard | Phase 1 brief exists; no dashboard files in the repository tree | All eight widgets remain planned work |
| Voice overlay | Records while pressed, posts audio, displays text, auto-dismisses after five seconds | Basic interaction exists; no retained history or explicit request state model |
| Lifecycle | Cleanup is tied to composition disposal; session hide changes lifecycle state | Explicitly verify and handle hide/dismiss during recording and requests |
| Recording | Exceptions are printed; `stopRecording()` can return a file despite failure | UI cannot reliably distinguish valid audio from a failed capture |
| Audio contract | MPEG-4 container/AAC encoder, `.aac` filename and `audio/aac` upload | Align container, extension, MIME type, and backend decoder |
| Network | Plain text responses; exceptions become strings; blocking OkHttp call inside IO coroutine | Introduce typed errors, request cancellation, and structured results |
| Transcription | `stt.py` always returns `test transcription` | Uploaded speech does not yet drive the answer |
| Data | Backend voice records exist; no Android database or activity-log route in tree | Logging, history, streaks, and offline state need a real data model |
| Access | Voice route has no authentication dependency or per-user ownership | Protect the service before remote personal use |
| Release config | Cleartext enabled globally; emulator URL fallback | Separate development and release configuration |
| Tests | Android examples only; instrumentation expects old app ID; backend test patches `claude_service` while route imports `llm_service` | Repair baseline checks before relying on them |
| Documentation | README references SDK 34, Docker setup and absent setup files; Gradle targets 36 and actual directory is `chips-mobile` | Make setup instructions match the checked-in project |

Source anchors: [Android launcher](https://github.com/Ido-Riko/Chips/blob/46f0c8510f0bdf2d1c55a779b23a62bb9cd9d857/chips-mobile/app/src/main/java/com/chips/MainActivity.kt), [voice overlay](https://github.com/Ido-Riko/Chips/blob/46f0c8510f0bdf2d1c55a779b23a62bb9cd9d857/chips-mobile/app/src/main/java/com/chips/ui/AssistantOverlayContent.kt), [existing dashboard brief](https://github.com/Ido-Riko/Chips/blob/46f0c8510f0bdf2d1c55a779b23a62bb9cd9d857/CHIPS_phase1_agent_brief.md), [STT stub](https://github.com/Ido-Riko/Chips/blob/46f0c8510f0bdf2d1c55a779b23a62bb9cd9d857/chips-backend/app/services/stt.py).

## Intermediate goals

Complete the milestones in order. Each produces a reviewable Android build; a milestone closes only when its acceptance evidence is recorded.

### M1 — An installable app with a useful home

**User result:** CHIPS opens to a dashboard; assistant setup and microphone permission are available when needed without blocking ordinary app use.

- Obtain a source checkout, record the baseline revision, and establish reproducible Android Studio/command-line setup.
- Build the app before refactoring. Fix obsolete package assertions and broken baseline tests. Add CI for debug build, lint, and meaningful unit tests.
- Introduce a small app structure: dashboard, history, settings/setup; ViewModels expose immutable UI state through StateFlow; repositories separate UI from storage/network.
- Implement the dark theme and all eight planned widgets, with a pinned Quick Log bar. Use clearly labeled preview data in an explicit demo mode; normal mode shows empty/unavailable states rather than invented scores.
- Make Today and Quick Log share one action path, ready for M2 persistence. Do not show a successful saved state before storage exists.
- Handle permission refusal and returning from assistant settings. Add an in-app voice entry point so use does not require changing the default assistant.

**Done when:** a clean setup builds and installs; the owner can navigate the dashboard, history and settings on the Poco; all widgets are reachable without overlap at large font sizes; permission denial leaves the dashboard usable; CI checks pass. Demo data is visibly distinguished from personal data.

Acceptance update, 20 October 2026: the owner confirmed Dashboard/History/Settings navigation, usable dashboard after microphone denial, and refreshed status after returning from assistant settings on the Poco. The owner waived large-text testing on the Poco as unnecessary; the earlier 1.5× emulator visual check remains recorded. The latest PR #1 head, `7f1f34fb0dae85ae78d08bfd70517ad80852c635`, passed build/lint/unit and API 35 UI jobs in [CI run 39483379703](https://github.com/Ido-Riko/Chips/actions/runs/39483379703). M1 is complete; the Poco large-text check is explicitly waived.

### M2 — Daily logging that can be trusted

Implementation update, 20 October 2026: durable local logging, undo/edit/delete, real streaks, background sync, device enrollment and owned backend contract are implemented. Debug/release builds, seven unit tests, nine emulator tests, twelve backend tests (including PostgreSQL) and an offline force-stop/relaunch check passed locally. M2 remains open for Poco and deployed HTTPS reconnect/retry/revocation acceptance; see `docs/ANDROID_PROGRESS.md` and `docs/ACTIVITY_SYNC.md`.

Acceptance update, 21 October 2026: the actual HTTPS backend and Poco passed paused logging/reopen, reconnect/retries without duplicates, edit/delete recovery, revoked access with local logging, and same-device token rotation/pending recovery. Phone/server snapshots match on 28 records with zero pending/failed records. Installed source `9f5ce142ffa0c0290d7e01fad584caf57a20d5b9` passed Android/backend CI. Only real local-midnight and physical-device timezone acceptance remain before review/closure; see [Poco evidence](docs/evidence/m2-poco-live-acceptance.json).

**User result:** log Workout, Beat Saber, Drawing, or Japanese, see Today and streaks update, and retain those records after closing the app—even offline.

- Keep the implemented native SQLite database for activity records and pending synchronization. Room was an initial proposal, not a migration requirement. The reserved voice-history schema does not imply persisted conversations; that behavior is M3. Use stable record IDs, timestamps, activity types and explicit sync status.
- Define completion and streak rules: one daily completion per activity for streaks, multiple sessions allowed in history; use an explicit local-day/timezone policy and test midnight boundaries.
- Implement log, undo/edit, history and deletion. Drive Today, streaks and Quick Log from the same stored records.
- Save locally before reporting success. Show pending/synced/failed states and retry controls. Retry automatically with persistent background work when connectivity returns; make repeated sends idempotent.
- Add the minimal backend activity create/read/update/delete contract, ownership, migrations and synchronization behavior. Account for deletion and conflicting updates so retries cannot resurrect deleted data.
- For the first personal deployment, use revocable device enrollment/access credentials stored securely on Android. Keep AI-provider credentials on the backend. Require authenticated encrypted transport for remote use.

**Done when:** an offline log survives force-stop/relaunch, appears exactly once after reconnecting and repeated retries, and can be edited/deleted without reappearing. Today, history and streaks agree across the day boundary. An unauthorized request cannot read or alter records.

### M3 — A real, reliable voice assistant

**User result:** hold to speak, receive an answer based on what was actually said, and find the transcript and answer in history.

- Share one voice controller between the in-app surface and system assistant overlay. Model idle, recording, uploading, processing, success, error and cancellation explicitly; prevent overlapping submissions.
- Make recorder start/stop report valid outcomes. Bound recording duration; reject empty/too-short recordings; handle microphone contention, revocation and rapid presses.
- Explicitly stop/release recording on session hide and destruction, cancel in-flight work, and prevent late responses from updating a later session. Clean temporary audio after success, cancellation or terminal failure.
- Align the audio format with the backend; replace stub transcription with real STT. Support speech recognition running on the owner's PC and an optional hosted API provider. Choose the local model/runtime and language support using representative owner recordings; verify current provider documentation during implementation.
- Support LLM inference running on the owner's PC, with optional hosted APIs for closed-source models. Configure STT and LLM providers independently so local and API inference can be combined. Keep endpoint/model selection explicit and API credentials on the backend. An unavailable PC service must produce a recoverable error; switching to an external API requires an explicitly configured choice.
- Return a structured voice response containing request ID, actual transcript and answer, plus structured errors. Propagate cancellation to the HTTP call and use bounded timeouts. Make processing retries safe against duplicate history or provider work.
- Persist transcript and answer. Keep results visible until dismissed or deliberately configured otherwise; expose retry/discard on failure instead of treating an error string as an answer.
- Verify default-assistant enrollment and invocation on the actual device. Treat locked-device support as a separate capability to prove; protect private history behind unlock and define what an overlay may reveal while locked.

**Backend dependency:** independently configurable PC-hosted/API STT and LLM adapters, authenticated voice route, response schema, bounded uploads, request deduplication and retention/deletion behavior. The backend routes requests to the selected inference services; PC hosting is a required supported path for both STT and the LLM. Do not add broader assistant tools yet.

**Done when:** ten representative utterances produce transcripts matching the spoken intent and corresponding answers, with results retained after restart. Demonstrate the full flow with both STT and LLM running on the owner's PC, verify the optional API adapters and independent provider selection, and verify recovery when a PC service is unavailable. Dismiss while recording/processing, deny/revoke permission, send near-empty audio, disconnect the network, time out the server, and invoke repeatedly: each must leave the microphone released, no duplicate request/history, and an understandable recoverable state. Record unlocked and locked invocation results separately.

### M4 — Dashboard cards backed by personal data

**User result:** every enabled card has a real source, useful actions and visible freshness; unsupported sources show an honest unavailable state.

Implement and validate one integration at a time, in the proposed order below. Each needs a source contract, persistence/cache, refresh behavior, error state and an on-device demonstration before moving on.

| Order | Widget/domain | Real behavior and evidence |
| --- | --- | --- |
| 1 | Today, streaks, Quick Log | Reuse M2 records; restart/offline/day-boundary checks remain green |
| 2 | Japanese | Persist daily kanji and review progress; define a licensed source and a small review schedule; completion logs Japanese activity |
| 3 | Drawing | Pick an image, show pending/upload/error, retain thumbnail and feedback; use actual analysis output or explicitly label manual feedback; deletion removes owned media |
| 4 | Beat Saber | Select the owner's actual score source; import session identity, accuracy and timestamps, deduplicate, and derive trends from history; label manually entered focus versus generated advice |
| 5 | Sleep | Select and validate the owner's device/data source and permissions; display measured duration/HR when present; introduce a sleep score only with a defined calculation or attributed provider score |
| 6 | Meals and pantry | Persist pantry edits and meal preferences; base suggestions on that data; low-stock notes derive from actual thresholds |

Use manual entry/import where a source integration is unavailable, clearly marked as such. Display last updated time and missing/partial data. Do not turn missing data into zero scores or broken streaks. Offer a way to disable cards the owner does not use.

**Done when:** all eight widgets from the original brief are implemented, each enabled integration has demonstrated real personal data end to end, and disconnected sources have useful empty/error states. Verify edits, refreshes, offline cache and deletion against the underlying records. Any feature still using demo data remains unfinished and explicitly tracked.

### M5 — A dependable personal beta

**User result:** install and update CHIPS normally and use it for a week without lost data or intervention to recover from ordinary failures.

- Finalize release application identity, versioning, signing and a private distribution path. Disable development cleartext access in release builds and validate required endpoint configuration.
- Add database migration coverage and an upgrade test using populated data. Provide export, deletion and a tested backup/restore policy; exclude credentials and transient audio from inappropriate backups.
- Collect minimal diagnostic events: request IDs, failure categories, timings, crash reports without transcripts, audio or credentials. Make pending requests and service failures diagnosable.
- Check TalkBack, large fonts, dark-theme contrast, Japanese text, edge-to-edge layout, navigation modes, repeated invocation, app/process restarts and permission changes.
- Run a seven-day owner trial on Poco X6 Pro; also smoke-test the minimum supported Android API and the current target API. Measure response latency and battery behavior before choosing optimization targets.
- Document install/update, service configuration, retention, recovery and known device limitations. If public distribution is chosen, separately verify current store requirements, privacy disclosures and multi-user account requirements.

**Done when:** the owner completes seven days of logging and voice use with no lost records, unresolved crashes, duplicate synced entries or microphone continuing after dismissal. An update preserves populated data; export/deletion/restore checks pass; every open defect has a severity and disposition, with no unresolved blocker to normal daily use.

## Implementation boundaries and dependencies

Android remains the product focus. Keep the existing monorepo and add only the backend functionality needed by the current Android milestone. Avoid a platform rewrite or speculative services.

Android boundaries: Compose screens → ViewModels/controllers → storage/repository interfaces → native SQLite and API client. The local database drives visible saved data. Background synchronization updates it; network callbacks do not own screen state. Settings hold configuration separately from activity/history records. Inject recorder/network/storage interfaces so failure paths are testable.

Dependency order: M1 → M2 → M3 → M4 → M5. CI, authentication, error handling, privacy and device checks enter with their associated features; M5 is the final release gate, not the first time those concerns are addressed.

Product choices to settle during the relevant milestone, without blocking M1: spoken languages; PC-hosted STT/LLM models and runtimes and optional API providers (PC hosting and API alternatives for both are required); actual sleep hardware and Beat Saber account/source; whether spoken replies are desired; whether this stays personal or eventually supports other users. This plan assumes text replies for the first release, matching the existing app.

## Completed first batch — M1 history

1. Restored the inspected source and established Android Studio/build setup with documented commands and results.
2. Repaired stale test references and added Android CI and meaningful foundation tests.
3. Added the accepted theme and dashboard/history/settings navigation, with optional assistant setup in Settings.
4. Represented all eight dashboard areas with explicit empty/preview states and no false saved-state feedback.
5. Recorded Poco navigation, microphone-denial and setup-status acceptance, the large-text test waiver, green CI and PR #1 merge.

These steps are history, not a fresh implementation queue. M2 was subsequently implemented locally; continue from its remaining acceptance gates above.

No calendar estimates are committed before a source build and first device run establish the baseline. Track progress by the acceptance evidence above, with small implementation PRs within each milestone.

## Progress ledger

- [x] Inspect current remote source and existing product brief; identify gaps and write this plan.
- [x] M1: installable app and useful home; build/CI/Poco evidence recorded in `docs/ANDROID_PROGRESS.md`, with owner waiver of Poco large-text testing.
- [ ] M2: trustworthy daily logging; attach persistence/sync/boundary evidence.
- [ ] M3: real voice assistant; attach transcription and interruption evidence.
- [ ] M4: personal dashboard integrations; attach evidence per source/widget.
- [ ] M5: personal beta; attach upgrade/recovery and seven-day trial results.

Each milestone closes against its own recorded acceptance evidence. M1 is closed; M2–M5 remain open. A documented plan or successful local build alone does not establish deployment acceptance or completion of the full app.
