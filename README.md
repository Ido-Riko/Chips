# CHIPS (Conversational Humanlike Intelligence for Programming Simplification)

**CHIPS** is a personal Android dashboard and Digital Assistant in development. The app opens to Today, with History and Settings navigation. Assistant enrollment is optional for browsing; voice can also be opened in the app. Voice and private replies require an unlocked device; emulator system/secure-lock checks passed, while physical Poco acceptance remains open.

Start with the [Android build and test guide](chips-mobile/README.md), [implementation roadmap](ANDROID_ROADMAP.md), and [current progress](docs/ANDROID_PROGRESS.md). Dashboard previews are fictional and opt-in. Activity logging, History and streaks now use local SQLite, with an authenticated background sync path. The M3 Android voice client is implemented and locally verified, including saved conversations. Full M3 remains open for a compatible service, real inference and physical Poco acceptance; Japanese study and the drawing sketchbook are implemented locally; drawing server deployment/real AI analysis and the remaining M4 integrations are still open.

## Repository Structure

The [M4 integration plan](docs/M4_PLAN.md) records the agreed Beat Saber, Smart Band sleep/live-HR, barcode/receipt pantry, Japanese and drawing integrations, including references to reusable projects. Keep project plans, agent notes and READMEs version-controlled; credentials and generated caches remain local.

The first [Beat Saber integration](docs/BEAT_SABER.md) reads public BeatLeader/BeatSaver data directly on Android, with score exploration, exact-version map enrichment and offline caching/removal. No CHIPS server, enrollment or Tailscale is required. M4 follows the owner's local-first rule: retain useful features without a server; reserve server/PC use for optional sync or processing that needs it.

[Japanese and drawing](docs/JAPANESE_DRAWING.md) add offline kanji reviews and a local sketchbook with queued server analysis. Drawing images are retained in PostgreSQL; real feedback requires the updated server and an explicitly configured vision model.

This is a mono-repo containing both the mobile client and the Python backend services.

- **`chips-mobile/`**: The Android project built with Kotlin and Jetpack Compose. Contains the `VoiceInteractionService` to register as the default device assistant.
- **`chips-backend/`**: A Python FastAPI service with PostgreSQL storage, stubbed transcription, configurable answer providers, and a plain-text voice response. The repository includes an OpenRC service definition and a deployment script.

## Setup & Architecture

### Mobile App (`chips-mobile`)
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Minimum SDK**: Android 8.0 (API 26)
- **Target SDK**: API 36
- **Key Features**: 
  - Overlays a recording button via `VoiceInteractionSession`. 
  - Captures bounded MPEG-4/AAC (`.m4a`, `audio/mp4`) recordings with cancellation and recoverable errors.
  - Checks authenticated version-1 service capabilities before uploading audio, then validates structured transcript/answer responses.
  - Saves successful conversations locally before confirming success; History supports reading and deleting the local copy.

### Backend Server (`chips-backend`)
- **Language**: Python 3.12 (validated)
- **Framework**: FastAPI (Served via Uvicorn)
- **Database**: PostgreSQL via SQLAlchemy
- **LLM Integration**: Anthropic, OpenAI, or Gemini, selected from backend configuration
- **Deployment**: OpenRC service definition; no Docker Compose setup is checked in

## Getting Started

1. **Mobile App**: Follow [chips-mobile/README.md](chips-mobile/README.md). The dashboard works without a backend or device-assistant enrollment.
2. **Existing backend**: Install `chips-backend/requirements.txt` in a virtual environment, follow [deployment preparation](chips-backend/DEPLOYMENT.md) and [service setup](docs/ACTIVITY_SYNC.md) to apply the additive migration, enroll a revocable device, and run the service behind HTTPS. Activity synchronization is supported. Its current demo/plain-text voice implementation is incompatible with the M3 Android client; the deferred service update must implement the [voice contract](docs/VOICE_CONTRACT.md).

---

M1 is complete. M2 code is merged; real-midnight/timezone acceptance remains open. M3 Android implementation and local acceptance are complete; the compatible server, real STT/LLM quality and physical Poco acceptance remain open. The M3 plan requires independently configurable PC-hosted STT and LLM inference with explicitly selected API alternatives; those service adapters are deferred, not implemented by the client. See the [canonical milestone plan](ANDROID_ROADMAP.md) and [Android acceptance evidence](docs/evidence/m3-android-client-acceptance.json).

Wake-word detection remains outside the first release. Backend personal-data routes require an enrolled device credential.
