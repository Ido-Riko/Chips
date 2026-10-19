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
