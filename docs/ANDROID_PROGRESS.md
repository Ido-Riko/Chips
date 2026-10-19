# Android implementation progress

## First batch — 19 October 2026

Implemented the M1 app shell: dark theme, Today/History/Settings navigation, seven dashboard cards plus pinned Quick Log, explicit fictional preview mode, shared preview-completion actions, and an in-app voice entry point. Assistant setup no longer blocks the dashboard. Preview state is temporary and never submitted or presented as saved data.

The existing system overlay now uses the app theme. Its recording cleanup also observes lifecycle stop, background requests no longer mutate UI state from the IO dispatcher, and replies stay visible until dismissed. This is an incremental correction, not completion of M3: real STT, persistent history, robust recorder outcomes and cancellation of the underlying HTTP call remain open.

Aligned the Compose compiler with AGP 9's built-in Kotlin version; added the explicit lifecycle Compose/ViewModel dependencies used by the app. Fixed the obsolete instrumentation package assertion and backend mock patch target. Added preview-isolation unit tests, navigation/preview device tests, and Android CI.

### Evidence and remaining gates

- Remote source revision: `46f0c8510f0bdf2d1c55a779b23a62bb9cd9d857`; source files restored through the GitHub connector because shell GitHub DNS failed.
- Local Gradle command attempted: `bash ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`.
- Result: Java missing, so no compilation or Android tests executed. SDK/device also unavailable here.
- M1 remains open until build/lint/unit/device tests pass and the Poco acceptance checks in `chips-mobile/README.md` are recorded.
- M2–M5 remain open. No activity persistence, synchronization, real transcription, or live dashboard integrations are claimed.

Use `ANDROID_ROADMAP.md` for the full acceptance criteria and `chips-mobile/README.md` for build and manual test instructions.
