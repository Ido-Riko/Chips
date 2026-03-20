# CHIPS — Phase 0 Agent Brief

> Conversational Humanlike Intelligence for Programming Simplification  
> **Goal of Phase 0:** Register CHIPS as the Android Digital Assistant. User triggers it from the lockscreen via home button long-press. Records audio, sends to FastAPI backend, calls Claude, displays text response. Nothing else.

---

## Environment

### Android
- Language: Kotlin
- UI: Jetpack Compose
- Min SDK: 26 (Android 8.0)
- Target SDK: 34 (Android 14)
- Build: Gradle (Kotlin DSL)

### Backend (Server = Xiaomi Mi 9T running postmarketOS)
- OS: postmarketOS 25.06 (Alpine Linux, native — no compatibility layer)
- Python 3.11
- FastAPI 0.111
- PostgreSQL 15
- Redis 7
- Uvicorn (ASGI server)
- systemd (process manager — native on postmarketOS 25.06)
- No Docker needed.

### Dependencies (Android)
```kotlin
// build.gradle.kts (app)
implementation("androidx.compose.ui:ui:1.6.0")
implementation("androidx.compose.material3:material3:1.2.0")
implementation("androidx.activity:activity-compose:1.9.0")
implementation("androidx.lifecycle:lifecycle-service:2.7.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
// VoiceInteraction API is part of the Android SDK — no extra dependency needed
```

### Dependencies (Python)
```
fastapi==0.111.0
uvicorn==0.30.0
sqlalchemy==2.0.30
asyncpg==0.29.0
redis==5.0.4
anthropic==0.28.0
python-multipart==0.0.9
python-jose==3.3.0
passlib==1.7.4
pydantic==2.7.0
pydantic-settings==2.2.1
```

---

## Folder Structure

### Android (`/chips-android`)
```
chips-android/
├── app/
│   ├── src/main/
│   │   ├── java/com/chips/
│   │   │   ├── MainActivity.kt               # Setup screen: prompt user to set CHIPS as default assist app
│   │   │   ├── assistant/
│   │   │   │   ├── ChipsVoiceInteractionService.kt
│   │   │   │   ├── ChipsVoiceInteractionSessionService.kt
│   │   │   │   └── ChipsVoiceInteractionSession.kt
│   │   │   ├── audio/
│   │   │   │   └── AudioRecorder.kt
│   │   │   ├── network/
│   │   │   │   └── ApiClient.kt
│   │   │   └── ui/
│   │   │       └── AssistantOverlayContent.kt  # Compose UI shown during session
│   │   ├── res/
│   │   │   └── xml/
│   │   │       └── interaction_service.xml     # VoiceInteractionService metadata
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
└── build.gradle.kts
```

### Backend (lives on Mi 9T at `~/chips-backend`)
```
chips-backend/
├── app/
│   ├── main.py
│   ├── config.py
│   ├── database.py
│   ├── models/
│   │   ├── user.py
│   │   └── daily_log.py
│   ├── routers/
│   │   ├── voice.py                      # POST /api/v1/voice-log
│   │   └── health.py
│   ├── services/
│   │   ├── stt.py                        # Stub for Phase 0
│   │   └── claude.py
│   └── schemas/
│       └── voice.py
├── alembic/
├── chips-api.openrc                      # OpenRC init script template
├── .env
└── requirements.txt
```

---

## What to Build

### 1. Android: Digital Assistant Registration

CHIPS registers as an Android Voice Interaction Service. Once set as the default assist app, home button long-press triggers it from anywhere including the lockscreen.

**`res/xml/interaction_service.xml`:**
```xml
<voice-interaction-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:sessionService="com.chips.assistant.ChipsVoiceInteractionSessionService"
    android:supportsLocalVoiceTrigger="false"/>
```

**`AndroidManifest.xml` — key entries:**
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>

<service
    android:name=".assistant.ChipsVoiceInteractionService"
    android:label="CHIPS"
    android:permission="android.permission.BIND_VOICE_INTERACTION"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.voice.VoiceInteractionService"/>
    </intent-filter>
    <meta-data
        android:name="android.voice_interaction"
        android:resource="@xml/interaction_service"/>
</service>

<service
    android:name=".assistant.ChipsVoiceInteractionSessionService"
    android:permission="android.permission.BIND_VOICE_INTERACTION"
    android:exported="false"/>
```

---

### 2. Android: ChipsVoiceInteractionService

Minimal. Just needs to exist and point to the session service via XML metadata.

```kotlin
class ChipsVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }
}
```

---

### 3. Android: ChipsVoiceInteractionSessionService

Creates a new session when triggered:

```kotlin
class ChipsVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return ChipsVoiceInteractionSession(this)
    }
}
```

---

### 4. Android: ChipsVoiceInteractionSession

The actual session. UI renders here, audio is captured here.

**Behavior:**
1. `onShow()` called when assistant is triggered
2. Call `showUi(Bundle())` to render `AssistantOverlayContent` in the session window
3. Mic button press → `AudioRecorder.startRecording()`
4. Mic button release → `AudioRecorder.stopRecording()` → send to backend via `ApiClient`
5. Display text response in session UI
6. Dismiss after user taps dismiss or 5 seconds after response shown → call `hide()`

```kotlin
class ChipsVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val audioRecorder = AudioRecorder(context)
    private val apiClient = ApiClient()

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        showUi(Bundle())
    }

    fun onMicPressed() {
        audioRecorder.startRecording()
    }

    fun onMicReleased() {
        val audioFile = audioRecorder.stopRecording()
        CoroutineScope(Dispatchers.IO).launch {
            val response = apiClient.sendAudio(audioFile)
            withContext(Dispatchers.Main) {
                // update UI state with response text
            }
        }
    }
}
```

The session window is a system overlay anchored to the bottom of the screen — provided by the OS, not manually managed.

---

### 5. Android: AssistantOverlayContent

`AssistantOverlayContent.kt` — Compose UI inside the session window:
- Mic button (hold to record, release to send)
- Text area showing response when it arrives
- Dismiss button
- One screen, no navigation

---

### 6. Android: AudioRecorder

`AudioRecorder.kt`:
- `startRecording()` — `MediaRecorder`, `VOICE_RECOGNITION` source, AAC output to temp file
- `stopRecording(): File` — stops, returns file

---

### 7. Android: ApiClient

`ApiClient.kt`:
- OkHttp
- `suspend fun sendAudio(file: File): String`
- Multipart POST to `BASE_URL/api/v1/voice-log`
- `BASE_URL` from `local.properties`

---

### 8. Android: MainActivity

Single purpose: detect if CHIPS is the default assist app. If not, show a button that opens device Default Apps settings.

```kotlin
val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
startActivity(intent)
```

---

### 9. Backend: POST /api/v1/voice-log

**Input:** multipart/form-data, field `audio` (file)

**Pipeline:**
1. Save audio to temp
2. STT via `stt.py` (Phase 0: stub → return `"test transcription"`)
3. Pass to `claude.py` with system prompt:
```
You are CHIPS, a personal AI assistant. The user is speaking to you from their phone lockscreen.
Be concise. Max 2 sentences unless detail is genuinely needed.
```
4. Return Claude response as plain text

**Response:** `200 OK`, `text/plain`

---

### 10. Backend: Claude Wrapper

`services/claude.py`:
```python
import anthropic

client = anthropic.Anthropic()  # reads ANTHROPIC_API_KEY from env

async def get_response(user_message: str, system_prompt: str) -> str:
    message = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=256,
        system=system_prompt,
        messages=[{"role": "user", "content": user_message}]
    )
    return message.content[0].text
```

---

### 11. Backend: Server Setup (postmarketOS)

**One-time setup — SSH into the Mi 9T and run:**
```bash
# Install dependencies
apk add python3 py3-pip postgresql15 postgresql15-contrib redis

# Enable and start Postgres + Redis
rc-service postgresql start
rc-update add postgresql default
rc-service redis start
rc-update add redis default

# Create DB and user
sudo -u postgres psql -c "CREATE USER chips WITH PASSWORD 'chips';"
sudo -u postgres psql -c "CREATE DATABASE chips OWNER chips;"
```

**Create the voice_logs table:**
```sql
CREATE TABLE voice_logs (
    id SERIAL PRIMARY KEY,
    received_at TIMESTAMPTZ DEFAULT NOW(),
    transcription TEXT,
    response TEXT,
    confidence FLOAT
);
```

**Install Python dependencies:**
```bash
pip install -r requirements.txt
```

**`/etc/init.d/chips-api`** — OpenRC init script, keeps FastAPI alive, auto-starts on boot:
```bash
#!/sbin/openrc-run

name="chips-api"
description="CHIPS FastAPI Backend"
command="uvicorn"
command_args="app.main:app --host 0.0.0.0 --port 8000"
command_user="chips"
directory="/home/chips/chips-backend"
pidfile="/run/chips-api.pid"
command_background=true

depend() {
    need net postgresql redis
}
```

**Make executable and enable:**
```bash
chmod +x /etc/init.d/chips-api
rc-update add chips-api default
rc-service chips-api start
```

**`.env`:**
```
ANTHROPIC_API_KEY=your_key_here
DATABASE_URL=postgresql+asyncpg://chips:chips@localhost:5432/chips
REDIS_URL=redis://localhost:6379
```

**Network access from your main phone:**
- Same WiFi → use Mi 9T's local IP (e.g. `192.168.x.x:8000`)
- Outside home network → Tailscale (easiest) or Cloudflare Tunnel (free)

---

## What Phase 0 Does NOT Include

- Auth / JWT
- Real Whisper STT (stubbed)
- Kanji, Beat Saber, pantry, reminders — none of it
- Error handling beyond basic try/catch (mark TODOs)
- Wake word / always-on listening

---

## Definition of Done

- [x] CHIPS appears as an option in Default Apps → Digital Assistant
- [x] Once set as default, home long-press triggers CHIPS session from lockscreen
- [x] Session UI shows mic button
- [x] Hold mic → records audio → release → sends to backend
- [x] Backend stubs transcription, calls Claude, returns response
- [x] Response displayed in session UI
- [x] Voice log saved to Postgres
- [x] Backend runs as an OpenRC service on the Mi 9T, accessible from main phone over local network
