# CHIPS (Conversational Humanlike Intelligence for Programming Simplification)

**CHIPS** is a personal Android dashboard and Digital Assistant in development. The app opens to Today, with History and Settings navigation. Assistant enrollment is optional for browsing; voice can also be opened in the app. Lock-screen behavior still needs device verification.

Start with the [Android build and test guide](chips-mobile/README.md), [implementation roadmap](ANDROID_ROADMAP.md), and [current progress](docs/ANDROID_PROGRESS.md). Dashboard previews are fictional and opt-in. Activity persistence, real transcription and live data integrations remain planned work.

## Repository Structure

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
  - Captures `AAC` audio using the device microphone.
  - Sends multipart POST to the backend and renders the returned response asynchronously.

### Backend Sever (`chips-backend`)
- **Language**: Python 3.11
- **Framework**: FastAPI (Served via Uvicorn)
- **Database**: PostgreSQL via SQLAlchemy
- **LLM Integration**: Anthropic, OpenAI, or Gemini, selected from backend configuration
- **Deployment**: OpenRC service definition; no Docker Compose setup is checked in

## Getting Started

1. **Mobile App**: Follow [chips-mobile/README.md](chips-mobile/README.md). The dashboard works without a backend or device-assistant enrollment.
2. **Voice demo backend**: Install `chips-backend/requirements.txt` in a virtual environment, configure the settings in `chips-backend/app/config.py`, prepare the database tables, and run `uvicorn app.main:app` from `chips-backend/`. Database migrations and a reproducible full backend setup remain roadmap work.

---

*(Note: Phase 0 does not feature real STT, Wake-Word detection, or User Authentication yet. These are scheduled components for future releases.)*
