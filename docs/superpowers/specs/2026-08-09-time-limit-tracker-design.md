# Time Limit Tracker — Android App Design Spec

**Date:** 2026-08-09
**Status:** Approved
**Stack:** Kotlin, Jetpack Compose, Room, DataStore, MVVM, AlarmManager

---

## 1. Resolved Open Questions

| # | Question | Decision |
|---|----------|----------|
| OQ1 | Website tracking needed? | No — apps only |
| OQ2 | Global or per-app message templates? | Global (one set for all tracked apps) |
| OQ3 | Reminder repeats forever or stop after N? | Forever (until session ends) |
| OQ4 | Re-activation: immediately or next fresh open? | Immediately — next 12s tick starts session for foreground app |
| OQ5 | Reboot: resume or discard mid-session? | Discard — mark all non-ENDED sessions as ENDED on BOOT_COMPLETED |

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                     MainActivity                        │
│              (Jetpack Compose UI host)                  │
│   HomeScreen │ AddAppScreen │ MessageTemplatesScreen    │
└──────────────────────┬──────────────────────────────────┘
                       │ observes StateFlow
        ┌──────────────▼──────────────┐
        │        ViewModels           │
        │  HomeViewModel              │
        │  AddAppViewModel            │
        │  TemplatesViewModel         │
        └──────┬──────────────┬───────┘
               │              │
    ┌──────────▼──┐    ┌──────▼──────────┐
    │ Settings    │    │ Session         │
    │ Repository  │    │ Repository      │
    │ (Room/DS)   │    │ (Room)          │
    └─────────────┘    └────────┬────────┘
                                │ read/write
          ┌─────────────────────▼──────────────────────┐
          │         UsageMonitorService                 │
          │         (Foreground Service)                │
          │  - polls UsageEvents every 12s              │
          │  - drives session state machine             │
          │  - triggers OverlayManager                  │
          │  - schedules/cancels AlarmManager alarms    │
          └──────────┬──────────────────┬──────────────┘
                     │                  │
        ┌────────────▼───┐   ┌──────────▼──────────┐
        │ OverlayManager │   │  ReminderReceiver   │
        │ (WindowManager │   │  (BroadcastReceiver │
        │  overlay view) │   │   from AlarmManager)│
        └────────────────┘   └─────────────────────┘
```

**Layer responsibilities:**
- **UI layer** (Compose screens + ViewModels): display state, handle user actions, no business logic
- **Repository layer**: single source of truth for all persisted data; exposes Flows to ViewModels and the service
- **Service layer** (`UsageMonitorService`): the engine — runs always, owns session lifecycle, owns overlay triggering
- **Alarm layer** (`ReminderReceiver`): wakes up the service to fire a reminder even under Doze

---

## 3. Data Model & Persistence

### Room Entities

```kotlin
TrackedApp(
    packageName: String (PK),
    appName: String,
    iconBytes: ByteArray?,   // cached to avoid PackageManager calls at runtime
    limitMinutes: Int,
    isTracked: Boolean
)

GlobalTemplates(
    id: Int (PK, always row 1),
    mainMessage: String,     // default: "You've been on {appName} for {limit} min."
    reminder1: String?,      // default: "Still on {appName}. Take a break."
    reminder2: String?,      // default: "Another 5 minutes on {appName}..."
    reminder3: String?       // default: "You've now been on {appName} for {elapsed} min."
)

Session(
    sessionId: String (PK, UUID),
    packageName: String,
    accumulatedActiveSeconds: Long,
    lastForegroundTimestamp: Long,  // when app last came to foreground
    pausedAt: Long?,                // null = currently active
    status: Enum(ACTIVE, PAUSED, LIMIT_REACHED, ENDED),
    snapshotLimitMinutes: Int,
    snapshotMainMessage: String,
    snapshotReminder1: String?,
    snapshotReminder2: String?,
    snapshotReminder3: String?,
    reminderCycleIndex: Int,        // 0=main, 1=r1, 2=r2, 3=r3
    lastPromptTime: Long?
)
```

### DataStore
```kotlin
AppSettings(
    trackingEnabled: Boolean   // persists across reboots
)
```

**Notes:**
- Icon cached as bytes in `TrackedApp` to avoid slow `PackageManager` lookups in the service
- Only current/active session per app is kept; no historical session analytics
- `DataStore` for the single boolean toggle (simpler than a Room table)
- On reboot: `BOOT_COMPLETED` receiver → service marks all non-ENDED sessions as ENDED

---

## 4. Session State Machine

The `UsageMonitorService` polls `UsageEvents` every 12 seconds.

```
Every 12s tick:
  if trackingEnabled == false → do nothing

  currentForegroundApp = queryUsageEvents()

  for each trackedApp:
    session = getActiveSession(packageName)

    if currentForegroundApp == packageName:
      if session == null → START new session (snapshot settings)
      if session.status == PAUSED → RESUME (add gap to accumulated time)
      accumulate time since lastForegroundTimestamp
      if accumulatedSeconds >= snapshotLimitMinutes * 60 AND status != LIMIT_REACHED:
        status = LIMIT_REACHED → show overlay, schedule first alarm

    else: // app not in foreground
      if session.status == ACTIVE or LIMIT_REACHED:
        status = PAUSED, record pausedAt = now

      if session.status == PAUSED:
        if now - pausedAt > 5 minutes → END session, cancel alarms
```

### Overlay & Alarm Flow

1. Status → `LIMIT_REACHED` → `OverlayManager.show(mainMessage)`
2. `AlarmManager` schedules exact alarm 5 min from now → `ReminderReceiver`
3. `ReminderReceiver` fires → checks session still `LIMIT_REACHED` and app still foreground → if yes, advance `reminderCycleIndex`, show next overlay, reschedule alarm
4. User taps Dismiss → overlay removed; session stays `LIMIT_REACHED`; alarm still pending
5. Session ends → `OverlayManager.hide()`, cancel all pending alarms

### Reminder Cycle

| Prompt | reminderCycleIndex |
|--------|--------------------|
| Main message | 0 |
| Reminder 1 | 1 |
| Reminder 2 | 2 |
| Reminder 3 | 3 |
| Reminder 1 (loops) | back to 1 |

### Re-activation
When toggle flips ON, the next 12s tick immediately evaluates the foreground app and starts a session if it's tracked.

### Reboot
`BOOT_COMPLETED` broadcast → service restarts → marks all non-ENDED sessions as ENDED.

---

## 5. UI Screens

### Home Screen
- Global on/off toggle (DataStore-backed)
- List of tracked apps: icon, name, limit, status badge (Idle / In Session / Limit Reached)
- FAB "+ Add App"
- "Message Templates" in top app bar
- Status badges update in real-time via Flow from SessionRepository

### Add/Edit App Screen
- Searchable scrollable list of installed apps (icon + name)
- Already-tracked apps shown with checkmark
- Tapping untracked app → enter limit (minutes, default 30) → save
- Tapping tracked app → edit limit or remove from tracking
- Snapshot rule: changes have no effect on in-progress sessions

### Message Templates Screen
- 4 text fields: Main Message, Reminder 1, Reminder 2, Reminder 3
- Helper text: `Available tokens: {appName} {limit} {elapsed}`
- Empty reminder fields show greyed placeholder; default used at display time (not stored)
- "Save" button disabled until a change is made

### Blocking Overlay
- Full-screen `TYPE_APPLICATION_OVERLAY` window, dark scrim background
- Centred card styled like a system "App not responding" dialog
- Token-substituted message text
- Single "Dismiss" button — only interactive element
- Back button and outside taps do nothing (overlay consumes all input)

### Permissions Onboarding (first launch)
1. **Usage Access** — opens `Settings.ACTION_USAGE_ACCESS_SETTINGS` (required, cannot skip)
2. **Display Over Other Apps** — opens `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (required, cannot skip)
3. **Notifications** (Android 13+) — standard `requestPermissions` (recommended, skippable)
4. **Battery Optimization Exemption** — opens `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (recommended, skippable)

---

## 6. Edge Cases

| Scenario | Handling |
|----------|----------|
| Tracked app uninstalled mid-session | `PackageManager` resolution fails → END session, remove from `TrackedApp` |
| Multiple tracked apps in quick succession | Independent session rows; only foreground app accumulates; others pause |
| Overlay permission revoked at runtime | `BadTokenException` caught → log, no crash; tracking continues |
| Service killed by OS | `START_STICKY` → OS restarts; service reads Room state and resumes |
| User force-stops tracker | Tracking stops; banner shown on next launch: "Tracking was interrupted" |
| Exact alarm permission denied (API 31+) | Fall back to `setWindow()` alarms; one-time warning shown |
| Unknown tokens in message | Left as-is — no crash |

---

## 7. Permissions Required

| Permission | Purpose |
|------------|---------|
| `PACKAGE_USAGE_STATS` | Detect foreground app via `UsageStatsManager` |
| `SYSTEM_ALERT_WINDOW` | Show blocking overlay via `WindowManager` |
| `FOREGROUND_SERVICE` | Keep `UsageMonitorService` alive |
| `RECEIVE_BOOT_COMPLETED` | Restart service and clean up sessions on reboot |
| `POST_NOTIFICATIONS` (API 33+) | Required foreground service notification |
| `SCHEDULE_EXACT_ALARM` (API 31+) | Precise 5-minute reminder alarms |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent Doze from killing service |

---

## 8. Project Structure (suggested)

```
app/
  src/main/
    data/
      db/           # Room database, DAOs, entities
      datastore/    # AppSettings DataStore
      repository/   # SessionRepository, SettingsRepository
    service/
      UsageMonitorService.kt
      OverlayManager.kt
      ReminderReceiver.kt
      BootReceiver.kt
    ui/
      home/         # HomeScreen, HomeViewModel
      addapp/       # AddAppScreen, AddAppViewModel
      templates/    # TemplatesScreen, TemplatesViewModel
      onboarding/   # OnboardingScreen, OnboardingViewModel
      overlay/      # Overlay composable / View
      theme/        # Colors, Typography, Theme
    MainActivity.kt
    TimeLimitApp.kt  # Application class
```
