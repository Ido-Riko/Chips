# CHIPS (Conversational Humanlike Intelligence for Programming Simplification)

**CHIPS** is an Android Digital Assistant application designed to act as a system-level assist app triggerable directly from the home button (even on the lockscreen). Phase 0 focuses on capturing audio, routing it to a backend for transcription, querying Claude (Anthropic), and presenting a short text-based response.

## Repository Structure

This is a mono-repo containing both the mobile client and the Python backend services.

- **`chips-mobile/`**: The Android project built with Kotlin and Jetpack Compose. Contains the `VoiceInteractionService` to register as the default device assistant.
- **`chips-backend/`**: A Python FastAPI service that processes audio input, interacts with an STT service (stubbed in Phase 0), forwards queries to Claude 3.5 Sonnet, and responds with plain text. Handled locally via Docker.

## Setup & Architecture

### Mobile App (`chips-mobile`)
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Minimum SDK**: Android 8.0 (API 26)
- **Target SDK**: Android 14 (API 34)
- **Key Features**: 
  - Overlays a recording button via `VoiceInteractionSession`. 
  - Captures `AAC` audio using the device microphone.
  - Sends multipart POST to the backend and renders the returned response asynchronously.

### Backend Sever (`chips-backend`)
- **Language**: Python 3.11
- **Framework**: FastAPI (Served via Uvicorn)
- **Database**: PostgreSQL 15 & Redis 7
- **LLM Integration**: Anthropic SDK (Claude 3.5 Sonnet)
- **Deployment**: Docker Compose for easy local testing.

## Getting Started

1. **Backend**: Navigate to `chips-backend/`, copy `.env.example` to `.env`, input your Anthropic API Key, and run:
   ```bash
   docker compose up --build
   ```
2. **Mobile App**: Open `chips-mobile/` in Android Studio and build the project. Ensure your emulator or physical device has the assistant app set to "CHIPS" in Settings > Default Apps > Digital Assistant.

---

*(Note: Phase 0 does not feature real STT, Wake-Word detection, or User Authentication yet. These are scheduled components for future releases.)*
