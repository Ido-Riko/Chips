# CHIPS — Full Development Plan

> Conversational Humanlike Intelligence for Programming Simplification
> One app. Lockscreen-first. Dashboard at the center. Knows your life.

---

## What It Is

An Android app with a persistent lockscreen assistant. Talk to it, log things, get proactive nudges, and have it run analysis in the background on your Beat Saber sessions, sleep, drawing, and language learning. Everything feeds into a shared memory + context layer.

---

## Stack Decision

| Layer | Choice | Why |
|---|---|---|
| Mobile | Android (Kotlin/Jetpack Compose) | You're on Android. Native gives full control over Digital Assistant API and BLE. |
| Backend | FastAPI (Python) | Easy async, great for ML tasks, you likely already know Python. |
| DB | PostgreSQL + JSONB | Structured state + flexible telemetry blobs. |
| Cache/Queue | Redis | Job queues for async analysis (Beat Saber, drawing). |
| Object Store | Local server filesystem (or S3-compatible like Wasabi) | Drawings, audio snippets, receipt photos. |
| Auth | JWT + OAuth2 | Simple. You're the only user so don't over-engineer this. |
| AI calls | Anthropic API (Claude) | Already integrated in your meal logger. |
| Band | GadgetBridge → export → ingest | You already have HR sharing experience. |
| Beat Saber | Beat Leader API | You have prior experience. |
| STT | Whisper (local or API) | Food logging, general voice input. |

Server: Xiaomi Mi 9T running postmarketOS (Alpine Linux). Native Linux — no compatibility layer, no Docker needed. systemd for process management. Tailscale or Cloudflare Tunnel for remote access.

---

## Modules

### 1. Lockscreen Assistant
The core. Everything else is accessed through this.

- Registered as the default Android Digital Assistant (`VoiceInteractionService` + `VoiceInteractionSession`)
- Triggered from lockscreen via home button long-press or swipe gesture — native OS behavior, no overlay hacks
- Context-aware: knows time of day, recent logs, pending reminders
- Can silently log (meal → just log it) or open full conversation
- Camera capture from lockscreen → route to drawing analysis or receipt OCR

**Inputs:** Voice, camera, touch
**Outputs:** Text response, push notification, background job trigger

---

### 2. Dashboard (Main App UI)
The home screen. Not a utility screen — a living display of your data.

- Sleep score + HR trend from last night
- Today's plan: workout, Beat Saber, drawing, Japanese, sleep target
- Beat Saber stats: recent accuracy trend, current target skill
- Streak rings per activity (drawing, Japanese, workout, Beat Saber)
- Pantry status + today's suggested meal
- Kanji of the day card
- Recent drawing thumbnail + last feedback
- Quick log buttons (tap to log workout done, drawing done, etc.)

**Library:** Vico (Compose-native charting). Clean, modern, no XML required.
**Design direction:** Dark theme, data-dense but readable. Think personal cockpit not productivity app.

---

### 3. Memory + Context Layer
The brain. Every module reads/writes here.

- Persistent user state: pantry, goals, streaks, preferences, recent interactions
- Daily metadata: cooking energy, mood tags (optional), planned activities
- Rolling context window passed to Claude on every call: "last 3 days summary + today's state"
- Memory summary regenerated nightly (cron job)

**DB tables:** `daily_logs`, `user_state`, `memory_snapshots`

---

### 4. Morning Digest
Runs at your wake time (configurable, read from daily schedule).

- Pull last night's sleep from GadgetBridge export (HR, stages, quality score)
- Pick Kanji of the day from your SRS schedule
- Generate today's plan: workouts, Beat Saber, drawing, study, sleep target
- Ask cooking energy (0–3) → stored in `daily_logs`
- Push as a card notification OR auto-open lockscreen assistant

**Cron:** 1 job at wake time
**Calls:** Claude for natural language digest generation

---

### 5. Pantry + Meal System
- Receipt photo → OCR (Google ML Kit on-device or Tesseract) → parse items → add to pantry
- Pantry tracks approximate quantities (bought N, used M estimated from logs)
- On request (or morning): suggest breakfast based on pantry × cooking energy
- Voice log from lockscreen → Whisper STT → Claude parses food → log calories/macros (USDA or custom DB)

**DB tables:** `pantry_items`, `food_logs`, `recipes`
**Edge case:** Pantry drift. Periodic "quick pantry check" prompt (weekly): "still have eggs?". Keep it short.

---

### 6. Beat Saber Pipeline
Most automatable module. Near-zero friction after setup.

- Beat Leader API: poll for new scores after sessions (webhook or scheduled poll)
- Parse: accuracy, miss patterns, hand balance, timing jitter, song density, BPM
- Generate 2–3 bullet analysis + 1 target skill
- Generate next session playlist: 60% warmup, 30% targeted drill maps, 10% challenge
- Store history → track improvement trends over weeks

**DB tables:** `beat_sessions`, `beat_scores`, `playlists`
**Trigger:** Beat Leader score appears → queue analysis job → notify when done

---

### 7. Language Learning (SRS App)
Built-in, no external dependency.

- Kanji + vocab deck with spaced repetition (SM-2 algorithm or simpler)
- Daily session: lockscreen card or assistant prompt
- Track: reviews done, accuracy per card, weak readings
- Adapt: if accuracy on a reading drops below threshold → increase frequency
- Morning Kanji picked from due cards prioritized by weakness

**DB tables:** `kanji_cards`, `srs_reviews`, `language_progress`
**Scope risk:** Start with Kanji only. Don't build a full grammar engine first.

---

### 8. Drawing Feedback
- Upload from lockscreen camera or gallery
- Send to Claude with prompt: "Analyze this anime-style figure drawing. Identify top 2–3 weaknesses. Return concrete drills."
- Optional: CLIP embedding comparison to reference drawings (later feature)
- Store uploads + feedback history → track what drills were assigned

**DB tables:** `drawings`, `drawing_feedback`
**Trigger:** Manual (user initiates) or reminder-prompted

---

### 9. Accountability + Reminders
- Track last activity timestamp per module (drawing, Japanese, workout, Beat Saber)
- Configurable target frequency per activity
- Cron checks gap → fires reminder if overdue
- Reads band data: if sleep < 6h or resting HR elevated → skip workout reminder
- Weekly summary: blunt numbers. No cheerleading.
- Escalation: missed 2+ days → more direct notification copy

**DB tables:** `activity_logs`, `reminder_config`
**Rule:** Reminders fire inside your configured daily windows, not randomly.

---

### 10. Band Integration (GadgetBridge)
- GadgetBridge exports HR + sleep data
- Options: (a) export to file → your app ingests on sync, (b) GadgetBridge HTTP webhook if supported by your firmware
- Parse sleep stages, resting HR, HRV if available
- Feed into: morning digest, workout reminder gating, fatigue context

**DB tables:** `sleep_records`, `hr_samples`

---

## Data Model (Core Tables)

```sql
users (id, name, timezone, wake_time, sleep_target)
daily_logs (id, user_id, date, cooking_energy, notes, created_at)
activity_logs (id, user_id, activity_type, completed_at, duration_minutes, metadata JSONB)
pantry_items (id, user_id, name, quantity_estimate, unit, last_updated)
food_logs (id, user_id, logged_at, description, calories_est, macros_est JSONB, confidence)
beat_sessions (id, user_id, session_at, raw_json JSONB, analysis JSONB, playlist JSONB)
sleep_records (id, user_id, date, duration_minutes, quality_score, stages JSONB, resting_hr)
kanji_cards (id, user_id, character, readings JSONB, meanings JSONB, interval_days, due_at, ease_factor)
srs_reviews (id, card_id, reviewed_at, grade, new_interval)
drawings (id, user_id, uploaded_at, file_path, feedback JSONB)
memory_snapshots (id, user_id, generated_at, summary_text)
```

---

## API Endpoints (Sketch)

```
POST /api/v1/voice-log          { audio_b64 } → parsed food log
POST /api/v1/receipt-ocr        { image_b64 } → pantry items
GET  /api/v1/morning-digest     → sleep + kanji + plan
POST /api/v1/drawing-feedback   { image_b64 } → drills
GET  /api/v1/beat-analysis      { session_id } → analysis + playlist
POST /api/v1/activity-log       { type, completed_at } → log + streak update
GET  /api/v1/reminders/pending  → due reminders
GET  /api/v1/context            → current memory snapshot for Claude calls
```

---

## Build Phases

### Phase 0 — Foundation (1–2 weeks)
- [ ] Android project setup (Kotlin + Jetpack Compose)
- [ ] FastAPI backend skeleton + Postgres schema
- [ ] JWT auth (single user, don't overengineer)
- [ ] Lockscreen overlay service — basic mic tap → text response
- [ ] Claude API integration plumbing

**Goal:** Talk to it from lockscreen. It responds. Nothing else.

---

### Phase 1 — Dashboard (1–2 weeks)
- [ ] Main Activity with Jetpack Compose scaffold
- [ ] Add Vico dependency for charts
- [ ] Static/mock data for all dashboard widgets (no real data yet)
- [ ] Sleep score card + HR sparkline
- [ ] Today's plan section
- [ ] Streak rings per activity
- [ ] Beat Saber accuracy trend chart (mock data)
- [ ] Kanji of the day card
- [ ] Quick log buttons (tap → POST to backend)
- [ ] Dark theme + layout polish

**Goal:** The app looks like CHIPS. All widgets present, populated with fake data. Real data slots in as each backend module ships.

---

### Phase 2 — Beat Saber Loop (1–2 weeks)
- [ ] Beat Leader API polling (or webhook)
- [ ] Session parser → analysis job (Redis queue)
- [ ] Analysis output: 3 bullets + target skill
- [ ] Basic playlist heuristic (BPM + pattern tags)
- [ ] Notification when analysis is ready
- [ ] Dashboard Beat Saber widget now shows real data

**Goal:** Finish a Beat Saber session → get analysis on your phone without touching anything.

---

### Phase 3 — Morning Digest (1 week)
- [ ] GadgetBridge export ingest → sleep record
- [ ] Sleep quality scorer (simple formula: duration + stages)
- [ ] Kanji SRS — static deck, SM-2 scheduler
- [ ] Morning digest generation via Claude
- [ ] Cron job + push notification
- [ ] Dashboard sleep + kanji widgets now show real data

**Goal:** Wake up, get a useful card. Dashboard sleep score is live.

---

### Phase 4 — Food + Pantry (1–2 weeks)
- [ ] Voice log endpoint (Whisper STT → Claude parse → food log)
- [ ] Lockscreen mic → voice log flow
- [ ] Receipt OCR → pantry items
- [ ] Simple recipe/meal suggestion engine
- [ ] Morning cooking-energy prompt

**Goal:** Log a meal by talking. Update pantry by photographing a receipt.

---

### Phase 5 — Accountability Engine (1 week)
- [ ] Activity log table + API
- [ ] Reminder scheduler cron
- [ ] Per-activity frequency config
- [ ] Band-gated workout reminders
- [ ] Weekly summary generation
- [ ] Dashboard streak rings now show real data

**Goal:** It nags you. Appropriately.

---

### Phase 6 — Drawing Feedback (3–5 days)
- [ ] Drawing upload from lockscreen camera
- [ ] Claude vision prompt for drill analysis
- [ ] Feedback history storage
- [ ] Trend: "you keep getting assigned gesture drills"

**Goal:** Upload a drawing, get 3 drills.

---

### Phase 7 — Memory Layer (1 week)
- [ ] Context aggregator: pulls last N days of logs into summary
- [ ] Nightly memory snapshot cron
- [ ] Pass snapshot to every Claude call as system context
- [ ] Rolling context aware of streaks, energy patterns, recent performance

**Goal:** It remembers what happened yesterday without you repeating yourself.

---

### Phase 8 — Language App (2–3 weeks, separate sub-project)
- [ ] Full Kanji deck (JLPT N5→N3 priority)
- [ ] SRS review UI (dedicated screen)
- [ ] Weak reading detection + frequency boost
- [ ] Vocabulary expansion (readings → vocab cards)
- [ ] Optional: sentence mining from anime you watch

**Note:** Scope this separately. Don't let it block Phases 1–7.

---

## Security (Non-optional)

- All data encrypted at rest (Postgres encryption or field-level for sensitive telemetry)
- HTTPS everywhere
- API keys rotated, never hardcoded, pulled from env
- GadgetBridge data stays on personal server — only anonymized summaries go to cloud
- Claude API calls: don't send raw biometrics, send derived summaries

---

## Personal Server Setup

- **Hardware:** Xiaomi Mi 9T flashed with postmarketOS (keep plugged in always, ventilated, battery charge capped ~80% if supported)
- **OS:** postmarketOS 25.06 — Alpine Linux directly on the hardware. No Android, no compatibility layer, no proot.
- **Package manager:** `apk add` — install Python, Postgres, Redis like any normal Linux server
- **Process management:** OpenRC — native to postmarketOS/Alpine, rc-service + rc-update for service management
- **Remote access:** SSH natively. Tailscale (easiest) or Cloudflare Tunnel (free) for outside home network
- **Note:** This phone is now a headless server. It's no longer usable as a daily Android device.
- **Battery:** No kernel-level charge limit support on this build — use a smart plug or timer outlet to cut charging at 80%.

---

## What to Build First

1. Lockscreen assistant → mic → Claude response (Phase 0)
2. Dashboard skeleton with mock data (Phase 1)
3. Beat Leader ingest → analysis notification (Phase 2)

Everything else is additive. Phase 0 proves the assistant works. Phase 1 gives you something that looks like the real app. Everything after fills it with real data.

---

## Known Risks

| Risk | Mitigation |
|---|---|
| Language app scope creep | Timebox it. Kanji-only for MVP. |
| Pantry drift (quantity tracking) | Weekly quick-check prompt. Accept imprecision. |
| Low energy → stop maintaining it | Phases 1–3 require minimal daily input. Design for that. |
| GadgetBridge export format changes | Abstract the parser. Swap formats without touching business logic. |

---

## What This Is Not

- Not a social app
- Not a productivity suite
- Not a generic AI wrapper

It's a personal instrument. It works because it knows specifically you, not because it's impressive in a demo.
