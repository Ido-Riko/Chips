# CHIPS (Conversational Humanlike Intelligence for Programming Simplification)

**15 November server update:** the owner's real drawing reached the PC with completed AI feedback, and real-phone voice requests returned answers. An intermittent answer-service error remains undiagnosed after a subsequent successful request. See [startup instructions and current server status](docs/PC_SERVER.md#everyday-startup).

**CHIPS** is a personal Android dashboard and Digital Assistant in development. The app opens to Today, with History and Settings navigation. Assistant enrollment is optional for browsing; voice can also be opened in the app. Assistant voice and feature access are enabled on the lockscreen by default, with an owner-controlled Settings switch to require unlocking. Physical Poco lockscreen acceptance remains open.

Start with the [Android build and test guide](chips-mobile/README.md), [implementation roadmap](ANDROID_ROADMAP.md), and [current progress](docs/ANDROID_PROGRESS.md). Dashboard previews are fictional and opt-in. Activity logging, History and streaks now use local SQLite, with an authenticated background sync path. The M3 Android voice client is implemented and locally verified, including saved conversations. The PC server now implements compatible voice and real local inference; full M3 remains open for owner/Poco acceptance; Japanese study and the drawing sketchbook are implemented locally; drawing/receipt inference is deployed on the PC; personal-data and hardware acceptance remain open. See [PC server setup and readiness](docs/PC_SERVER.md).

## Repository Structure

The [M4 integration plan](docs/M4_PLAN.md) records the agreed Beat Saber, Smart Band sleep/live-HR, barcode/receipt pantry, Japanese and drawing integrations, including references to reusable projects. Keep project plans, agent notes and READMEs version-controlled; credentials and generated caches remain local.

The first [Beat Saber integration](docs/BEAT_SABER.md) reads public BeatLeader/BeatSaver data directly on Android, with score exploration, exact-version map enrichment and offline caching/removal. No CHIPS server, enrollment or Tailscale is required. M4 follows the owner's local-first rule: retain useful features without a server; reserve server/PC use for optional sync or processing that needs it.

[Japanese and drawing](docs/JAPANESE_DRAWING.md) add offline kanji reviews and a local sketchbook with queued server analysis. Drawing images are retained in PostgreSQL; real feedback requires the updated server and an explicitly configured vision model.

[Pantry](docs/PANTRY.md) adds offline groceries, barcode lookup, editable receipt drafts, stock adjustments and server-backed inventory. Meal recommendations belong to the main AI; the backend supplies owner-scoped inventory context. PC deployment and synthetic real receipt inference are verified; owner receipt quality and phone acceptance remain pending.

The single Activities card, streak rings and digital assistant open the implemented activity features. The duplicate pinned Quick Log bar has been removed. Japanese reviews/drawing imports update real completions; Workout and Beat Saber retain explicit manual logging.

[Live heart rate](docs/HEART_RATE.md) brings MiBand-Converter's Share HR capture, optional BLE relay and OSC output into Android. Capture stores timestamped sessions locally in lossless equal-value runs and runs with an ongoing notification containing Reconnect and Stop. Outputs can change during capture; ordinary process death restores active capture with an explicit gap. Stop offers an optional linked Beat Saber save; records commit locally first and sync HR before its linked activity. Server storage requires backend migration 005. Real Smart Band/Poco/Quest acceptance remains open; [sleep history import and per-play Beat Saber HR reports](docs/SLEEP_AND_BEAT_HR.md) are implemented locally. Real band/provider end-to-end acceptance remains open.

This is a mono-repo containing both the mobile client and the Python backend services.

- **`chips-mobile/`**: The Android project built with Kotlin and Jetpack Compose. Contains the `VoiceInteractionService` to register as the default device assistant.
- **`chips-backend/`**: A Python FastAPI service with PostgreSQL storage, local Whisper or an explicitly selected transcription API, independently configured answer providers, and versioned JSON voice responses with durable deduplication. The repository includes PC systemd/Podman provisioning and legacy OpenRC deployment support.

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
- **LLM Integration**: Explicit local or API chat-completions endpoint/model; PC deployment uses Ollama qwen3.5:9b
- **Deployment**: Bazzite PC user services, rootless PostgreSQL and private Tailscale HTTPS; legacy OpenRC templates retained

## Getting Started

1. **Mobile App**: Follow [chips-mobile/README.md](chips-mobile/README.md). The dashboard works without a backend or device-assistant enrollment.
2. **Existing backend**: Install `chips-backend/requirements.txt` in a virtual environment, follow [deployment preparation](chips-backend/DEPLOYMENT.md) and [service setup](docs/ACTIVITY_SYNC.md) to apply the additive migration, enroll a revocable device, and run the service behind HTTPS. Activity synchronization is supported. The PC deployment implements the [voice contract](docs/VOICE_CONTRACT.md); see [connection, operations and readiness](docs/PC_SERVER.md).

---

M1 is complete. M2 code is merged; real-midnight/timezone acceptance remains open. M3 Android implementation and local acceptance are complete; the compatible PC server and synthetic real-inference checks are complete; owner STT/LLM quality and physical Poco acceptance remain open. The M3 plan requires independently configurable PC-hosted STT and LLM inference with explicitly selected API alternatives; those adapters are implemented on the server, with no implicit cloud fallback. See the [canonical milestone plan](ANDROID_ROADMAP.md) and [Android acceptance evidence](docs/evidence/m3-android-client-acceptance.json).

Wake-word detection remains outside the first release. Backend personal-data routes require an enrolled device credential.

## Start the PC server

Run `./chips-backend/deploy/start-pc.sh` from this repository. It starts/connects Tailscale, starts Ollama and the Chips database/API, and checks private HTTPS. Run as your desktop user, without sudo; the script requests administrative access only if Tailscale needs it. See [the server guide](docs/PC_SERVER.md#everyday-startup).
