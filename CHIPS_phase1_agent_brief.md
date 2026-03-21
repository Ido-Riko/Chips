# CHIPS — Phase 1 Agent Brief

> **Goal of Phase 1:** Build the main app dashboard. All widgets present, populated with mock data. Dark theme, data-dense, looks like the real app. No live backend data yet — that slots in as later phases ship.

---

## Context (What Already Exists)

### Android (complete from Phase 0)
- Kotlin + Jetpack Compose project at `/chips-android`
- `VoiceInteractionService` registered and working as default Digital Assistant
- `MainActivity.kt` currently just handles assistant setup prompt
- `ApiClient.kt` exists — OkHttp, talks to backend at configurable `BASE_URL`

### Backend (complete from Phase 0)
- FastAPI on Xiaomi Mi 9T running postmarketOS (Alpine Linux, OpenRC)
- PostgreSQL 18 + Redis 8 running, `chips` DB exists
- `POST /api/v1/voice-log` endpoint working
- Server IP: `192.168.31.86:8000`

---

## Environment

### Android
- Kotlin
- Jetpack Compose
- Min SDK: 26 / Target SDK: 34
- Add dependency: **Vico** (Compose-native charting)

```kotlin
// build.gradle.kts
implementation("com.patrykandpatrick.vico:compose:1.13.1")
implementation("com.patrykandpatrick.vico:compose-m3:1.13.1")
```

---

## Folder Structure (additions to existing project)

```
chips-android/
├── app/src/main/java/com/chips/
│   ├── MainActivity.kt                   # UPDATE: add navigation to Dashboard
│   ├── dashboard/
│   │   ├── DashboardScreen.kt            # Main scrollable dashboard
│   │   ├── DashboardViewModel.kt         # Holds mock state, will later call API
│   │   ├── MockData.kt                   # All mock data in one place
│   │   └── widgets/
│   │       ├── SleepCard.kt              # Sleep score + HR sparkline
│   │       ├── TodayPlanCard.kt          # Today's activities
│   │       ├── BeatSaberCard.kt          # Accuracy trend + target skill
│   │       ├── StreakRings.kt            # Per-activity streak rings
│   │       ├── KanjiCard.kt             # Kanji of the day
│   │       ├── MealCard.kt              # Suggested meal + pantry note
│   │       ├── DrawingCard.kt           # Last drawing thumbnail + last drill
│   │       └── QuickLogBar.kt           # Tap-to-log buttons
│   ├── ui/
│   │   └── theme/
│   │       ├── Theme.kt                 # Dark theme definition
│   │       ├── Color.kt                 # Color palette
│   │       └── Type.kt                 # Typography
│   └── assistant/                       # UNCHANGED from Phase 0
```

---

## What to Build

### 1. Theme

Dark theme. Not generic Material dark — something with personality.

**`Color.kt`:**
```kotlin
val Background = Color(0xFF0A0A0F)
val Surface = Color(0xFF13131A)
val SurfaceVariant = Color(0xFF1C1C27)
val Accent = Color(0xFF6C63FF)
val AccentSecondary = Color(0xFF00D4AA)
val TextPrimary = Color(0xFFEEEEF5)
val TextSecondary = Color(0xFF8888AA)
val Warning = Color(0xFFFFB347)
val Destructive = Color(0xFFFF4D6D)
```

Apply in `Theme.kt` as a dark MaterialTheme. No light theme needed.

---

### 2. MockData.kt

Single file. All hardcoded data lives here. Easy to swap for real API calls later.

```kotlin
object MockData {
    val sleepScore = 74
    val sleepDuration = "7h 12m"
    val restingHR = 58
    val hrSparkline = listOf(55f, 57f, 60f, 58f, 56f, 54f, 58f)

    val todayPlan = listOf(
        PlanItem("Calisthenics", scheduled = true, done = false),
        PlanItem("Beat Saber", scheduled = true, done = false),
        PlanItem("Drawing", scheduled = true, done = false),
        PlanItem("Japanese", scheduled = true, done = false),
    )

    val beatSaberAccuracy = listOf(87f, 89f, 85f, 91f, 88f, 93f)
    val beatSaberTargetSkill = "Left hand timing on burst patterns"
    val beatSaberLastScore = "93.4%"

    val streaks = mapOf(
        "Drawing" to 3,
        "Japanese" to 7,
        "Workout" to 1,
        "Beat Saber" to 5
    )

    val kanjiOfDay = "食"
    val kanjiReading = "しょく / た(べる)"
    val kanjiMeaning = "eat / food"

    val suggestedMeal = "Oat porridge with eggs"
    val pantryNote = "Low on milk"

    val lastDrawingFeedback = "Work on chest/pelvis box tilt in 3/4 poses"
    val lastDrawingDate = "2 days ago"

    data class PlanItem(val name: String, val scheduled: Boolean, val done: Boolean)
}
```

---

### 3. DashboardScreen.kt

Vertically scrollable `LazyColumn`. Cards stacked in this order:

1. `SleepCard`
2. `TodayPlanCard`
3. `StreakRings`
4. `BeatSaberCard`
5. `KanjiCard`
6. `MealCard`
7. `DrawingCard`
8. `QuickLogBar` (pinned at bottom, always visible — use `Scaffold` bottomBar)

Padding: 16dp horizontal, 12dp between cards. No headers or section labels — cards are self-labeled.

---

### 4. Widget Specs

#### SleepCard
- Large sleep score number (74) with label "Sleep Score"
- Subtitle: duration + resting HR ("7h 12m · 58 bpm")
- Vico sparkline of HR across the night (small, 60dp tall)
- Color score: ≥80 green, 60–79 yellow, <60 red

#### TodayPlanCard
- Title: "Today"
- Row of activity chips: each shows icon + name + checkbox
- Activities: Calisthenics, Beat Saber, Drawing, Japanese
- Tapping a chip calls `QuickLogBar` action (same as quick log buttons)

#### StreakRings
- 4 circular progress rings in a row
- Each ring: activity name below, streak number in center
- Color per activity: Drawing = Accent, Japanese = AccentSecondary, Workout = Warning, Beat Saber = TextPrimary

#### BeatSaberCard
- Title: "Beat Saber"
- Last score badge (93.4%)
- Vico line chart of last 6 session accuracies
- Target skill label: small text, muted color, "Focus: Left hand timing on burst patterns"

#### KanjiCard
- Large kanji character centered (食)
- Reading below in smaller text
- Meaning below that, muted
- Simple card, no chart

#### MealCard
- Title: "Suggested Meal"
- Meal name large
- Pantry note in Warning color if present ("Low on milk")

#### DrawingCard
- Title: "Drawing"
- Last feedback as body text
- "Last uploaded: 2 days ago" muted subtitle
- Upload button (icon only) — tapping opens image picker (wire to existing camera/gallery logic from Phase 0 if present, otherwise TODO)

#### QuickLogBar
- Pinned bottom bar
- 4 icon buttons: Workout ✓, Beat Saber ✓, Drawing ✓, Japanese ✓
- Tapping: POST to `api/v1/activity-log` with `{ type, completed_at }` — use existing `ApiClient`
- Visual feedback on tap: button briefly highlights AccentSecondary
- If POST fails: silent fail, TODO for proper error handling later

---

### 5. DashboardViewModel.kt

Simple. Holds `MockData` as `StateFlow`s. No API calls in Phase 1 except `QuickLogBar`.

```kotlin
class DashboardViewModel : ViewModel() {
    val sleepScore = MutableStateFlow(MockData.sleepScore)
    val todayPlan = MutableStateFlow(MockData.todayPlan)
    val streaks = MutableStateFlow(MockData.streaks)
    // etc — one StateFlow per widget data
}
```

---

### 6. MainActivity.kt Update

Add navigation so the app opens to the Dashboard by default, with the assistant setup check secondary.

```kotlin
// Simple: if already set as default assistant, go straight to Dashboard
// If not, show setup prompt first, then Dashboard
```

No navigation library needed for Phase 1 — just conditional composable rendering.

---

## What Phase 1 Does NOT Include

- Real API data for any widget except QuickLogBar
- Beat Saber live data (Phase 2)
- Sleep live data (Phase 3)
- Pantry real state (Phase 4)
- Streak real counts (Phase 5)
- Drawing upload analysis (Phase 6)
- Kanji SRS (Phase 7/8)
- Error handling beyond QuickLogBar silent fail
- Animations (nice to have, not required)

---

## Definition of Done

- [ ] App opens to Dashboard by default
- [ ] All 8 widgets render with mock data
- [ ] Dark theme applied consistently
- [ ] Vico charts render in SleepCard and BeatSaberCard
- [ ] StreakRings render with correct colors per activity
- [ ] QuickLogBar pinned at bottom, visible at all times
- [ ] Tapping QuickLogBar button POSTs to backend, silent fail if unreachable
- [ ] Scrolling through all cards works smoothly
- [ ] No crashes on the Poco X6 Pro (test device)
