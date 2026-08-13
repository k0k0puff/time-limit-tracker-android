# Android App: App Usage Time Limit Tracker — Requirements Document

## 1. Overview
An Android application that lets users select specific apps (e.g. Instagram, Facebook) to track usage time on, set a per-app time limit, and — once that limit is exceeded within a single usage session — shows a full-screen blocking message that prevents interaction with the tracked app until dismissed, repeating every 5 minutes with cycling reminder text until the session ends.

> **Note on requirement 7:** The original requirement list mentions "configure the website and time limit of each site," which appears to be carried over from a browser-extension version of this spec. This document treats requirement 7 as referring to the same **tracked apps** defined in FR1/FR2 (not websites), since this is a native Android app tracking installed apps, not a browser. Flag if a website-tracking component is actually intended — see OQ1.

## 2. Goals
- Help users self-limit time spent on specific apps (e.g. social media).
- Enforce a "cannot interact until dismissed" block message once the per-app limit is hit, mimicking the app having stopped.
- Track usage per **session**, where a session persists through brief app-switching but ends after 5 minutes of continuous non-use.

## 3. Core Concepts

### 3.1 Session Definition
- A **session** starts when the user opens a tracked app.
- The session's elapsed time only accumulates while the tracked app is in the **foreground/active** state.
- If the user switches away to a different app (or to the home screen), the session **pauses** (does not accumulate time, does not end).
- If the user returns to the same tracked app within 5 minutes, the session **resumes** and continues accumulating from where it paused.
- If the tracked app remains unused (backgrounded) for **more than 5 minutes**, the session **ends**. The next time the user opens that app, a brand-new session starts, with elapsed time back to 0.
- Removing the app from Android's "Recent Apps" (task switcher) after a session has started does **not** end the session — the session keeps running/paused according to the same foreground/5-minute rules until it naturally ends (see FR1).

### 3.2 Snapshot Rule for Config Changes (applies to FR2 and FR3)
- If a user changes an app's **time limit** or the **message templates** while a session for that app is already in progress, the **in-progress session continues using the settings that were active when it started** (a "snapshot" taken at session start).
- Only **future sessions** (started after the change is saved) use the new time limit / message templates.

## 4. Functional Requirements

### FR1 — Tracked App Configuration
- User can browse installed apps (via `PackageManager`) and select which apps to track (e.g., Instagram, Facebook).
- User can remove an app from tracking at any time.
- If a currently-tracked app is removed from the tracking list **while a session is in progress** for it, that session continues running to completion under its original snapshot settings (FR3.2); only future opens of that app are no longer tracked.
- Tracked apps are listed in the app's main/home screen with their configured limit and current status (not in session / session active / limit reached).

### FR2 — Time Limit Setting
- For each tracked app, user sets a time limit in minutes (integer, e.g. 30).
- Default suggested value: 30 minutes (editable).
- Per the Snapshot Rule (3.2): changing the limit does not affect a session already in progress for that app; it only applies to sessions that start after the change.

### FR3 — Message Template Configuration
- User can configure text for:
  - The **main message** — shown the first time the limit is reached in a session.
  - Up to **3 reminder messages** ("Reminder 1", "Reminder 2", "Reminder 3") — shown on subsequent 5-minute repeats.
- Each message field supports placeholder tokens (e.g., `{appName}`, `{limit}`, `{elapsed}`) substituted at display time.
- Blank reminder slots fall back to a sensible default string.
- Per the Snapshot Rule (3.2): changing templates does not affect a session already in progress; it only applies to sessions that start after the change.
- Templates are global (one set applied to all tracked apps) unless per-app templates are explicitly desired — see OQ2.

### FR4 — Limit-Reached Blocking Prompt
- When a session's accumulated active time reaches that session's snapshotted time limit:
  - Display a full-screen system overlay (drawn on top of the tracked app, using `SYSTEM_ALERT_WINDOW` / `TYPE_APPLICATION_OVERLAY`) that visually mimics the app having stopped/become unresponsive.
  - Overlay captures all touch/back/input, blocking interaction with the underlying app until dismissed.
  - Overlay displays the session's snapshotted **main message** with a single "Dismiss" button.
  - Tapping "Dismiss" removes the overlay and returns control of the app to the user (the session keeps running/accumulating per FR5).
- Blocking only occurs while the app-level tracking feature is activated (FR7).

### FR5 — Repeating Reminder with Cycling Messages
- After the first block prompt is dismissed, the same style of overlay reappears every 5 minutes for as long as the session remains active (i.e., has not ended per §3.1).
- Reminder content cycles through the 3 snapshotted reminder messages in sequence, then loops:
  - 15 min elapsed → Main message
  - +5 min → Reminder 1
  - +5 min → Reminder 2
  - +5 min → Reminder 3
  - +5 min → Reminder 1 (cycle repeats)
- Note: the 5-minute reminder cadence continues to advance only while the app is actively in the foreground; if the session is paused (user switched away, within the 5-minute grace window), the reminder countdown pauses too and resumes on return — consistent with §3.1.
- Repetition continues until the session ends (§3.1), with no other cutoff, unless configured otherwise — see OQ3.

### FR6 — Session Reset
- A new session (per the §3.1 definition — app reopened after the prior session ended due to 5+ minutes of disuse) starts elapsed time at 0 and resets the reminder cycle back to the main message.
- Time limit and message templates used are re-snapshotted from current settings at the moment the new session starts (§3.2).

### FR7 — Global Activate/Deactivate Toggle
- The app's home screen includes an on/off toggle that activates or deactivates the entire time-limit tracking feature.
- When deactivated: no usage tracking, no session accumulation, no blocking overlays occur for any tracked app.
- When reactivated: tracking resumes; any app opened afterward starts a fresh session (see OQ4 for whether an app already open at time of reactivation should immediately start tracking).
- Toggle state persists across app restarts and device reboots.

## 5. Non-Functional Requirements
- Target modern Android (API 26+), built with Kotlin.
- Requires **Usage Access** permission (`PACKAGE_USAGE_STATS`, via `UsageStatsManager`) to detect which app is currently in the foreground.
- Requires **"Display over other apps"** permission (`SYSTEM_ALERT_WINDOW`) to show the blocking overlay on top of other apps.
- Foreground detection and session timing should run via a persistent **foreground service** (with a permanent low-priority notification, as required by Android for foreground services) to survive backgrounding and avoid being killed by the OS.
- Session state (elapsed time, pause timestamps, snapshot settings, reminder cycle position) must be persisted (e.g., Room database or DataStore) so an OS-killed-and-restarted service can resume correctly rather than silently losing session state.
- Battery/performance: avoid tight polling; use `UsageEvents` callbacks or periodic checks (e.g., every 10–15 seconds) rather than per-second polling.
- All data stored locally on-device; no network calls required.

## 6. Data Model (suggested)

```
// Room entities (simplified)

TrackedApp {
  packageName: String (PK),
  appName: String,
  limitMinutes: Int,
  isTracked: Boolean
}

MessageTemplates {
  id: Int (PK, singleton row, or per-app if OQ2 resolves that way),
  mainMessage: String,
  reminder1: String?,
  reminder2: String?,
  reminder3: String?
}

Session {
  sessionId: String (PK, UUID),
  packageName: String,
  startTime: Long (epoch ms),
  accumulatedActiveSeconds: Long,
  lastActiveTimestamp: Long,      // last moment app was foreground
  pausedAt: Long?,                // null if not currently paused
  status: Enum(ACTIVE, PAUSED, LIMIT_REACHED, ENDED),
  snapshotLimitMinutes: Int,      // limit at session start (§3.2)
  snapshotMainMessage: String,    // templates at session start (§3.2)
  snapshotReminder1: String?,
  snapshotReminder2: String?,
  snapshotReminder3: String?,
  reminderCycleIndex: Int,        // 0 = main shown; 1-3 = last reminder shown
  lastPromptTime: Long?
}

AppSettings {
  trackingEnabled: Boolean
}
```

## 7. UI Requirements

### 7.1 Home Screen
- Global on/off toggle for the tracking feature (FR7).
- List of tracked apps showing: app icon/name, configured limit, current status (idle / in session / limit reached).
- "+ Add App" button → opens app picker (FR1).
- Tapping a tracked app row opens its detail/edit screen (limit + optionally per-app override).

### 7.2 Add/Edit Tracked App Screen
- Searchable list of installed apps (icon + name) to add to tracking.
- Numeric input for time limit (minutes) when adding or editing an app.

### 7.3 Message Templates Screen
- Text field for main message.
- Three text fields for Reminder 1/2/3 (optional, showing default placeholder text when blank).
- Helper text listing available placeholder tokens (`{appName}`, `{limit}`, `{elapsed}`).
- "Save" button; save applies only to future sessions per the Snapshot Rule (§3.2).

### 7.4 Blocking Overlay
- Full-screen overlay drawn above the tracked app via `TYPE_APPLICATION_OVERLAY`, styled to resemble an app-crashed/stopped dialog.
- Displays the current cycle's message text and a single "Dismiss" button.
- Back button and touches outside the "Dismiss" button do nothing (overlay is modal).

## 8. Technical Architecture
- **UsageMonitorService (foreground service)** — polls `UsageStatsManager`/`UsageEvents` to determine the current foreground app; drives session state transitions (start/pause/resume/end) per §3.1; schedules the 5-minute reminder cadence via `AlarmManager` or a coroutine-based timer tied to session active-time.
- **OverlayManager** — responsible for inflating/removing the `WindowManager`-based blocking overlay view when a session's `status` becomes `LIMIT_REACHED` or a reminder fires.
- **SessionRepository (Room)** — persists `Session` rows so state survives process death; the foreground service reconciles state on restart.
- **SettingsRepository (Room/DataStore)** — persists `TrackedApp`, `MessageTemplates`, and `AppSettings`.
- **MainActivity / Compose UI** — home screen, add/edit app screen, message templates screen (§7).
- **Permissions flow** — on first launch, guide user through granting Usage Access and "Display over other apps" permissions, since both are required for core functionality.

## 9. Edge Cases to Handle
- Device reboot mid-session — foreground service restarts; session should either resume from last persisted state or be safely ended, depending on how much time has passed (see OQ5).
- User force-stops the tracker app itself from Android settings — tracking obviously stops; no special handling possible, but consider surfacing a notification warning if this is detected on next launch.
- User uninstalls a tracked app while it's mid-session — session should end gracefully (app package no longer resolvable).
- Multiple tracked apps used in quick succession (e.g., switching between Instagram and Facebook, both tracked) — each app's session is independent; switching away from App A to App B pauses App A's session and, if App B is tracked, starts/resumes App B's session simultaneously.
- Notification permission (Android 13+) needed for the mandatory foreground-service notification — request as part of onboarding.
- Battery optimization / Doze mode potentially delaying timer accuracy — recommend user exempt the app from battery optimization during onboarding.

## 10. Open Questions (please confirm before development)
1. **OQ1:** Requirement 7 mentions configuring "website" tracking — is a website/browser-tracking component actually needed in this Android app, or was that carried over by mistake from a related browser-extension spec (assumed the latter in this doc)?
2. **OQ2:** Are message templates global across all tracked apps, or should each tracked app have its own independent set of templates?
3. **OQ3:** Should the 5-minute repeat reminders continue forever within a session, or stop after N repeats?
4. **OQ4:** If the user re-activates tracking (FR7) while a previously-untracked-state app is already open in the foreground, should a session start immediately, or only on the next fresh app open?
5. **OQ5:** After a device reboot, should any session that was mid-progress before the reboot resume with its previously accumulated time, or be discarded/ended?

## 11. Acceptance Criteria
- [ ] User can browse and select installed apps to track, and remove apps from tracking.
- [ ] User can set a time limit (minutes) per tracked app.
- [ ] User can configure main + up to 3 reminder message templates, with sensible defaults when blank.
- [ ] A session accumulates time only while the tracked app is in the foreground; pauses when the user switches away; resumes if the user returns within 5 minutes; ends if unused for more than 5 minutes.
- [ ] Changing a time limit or message templates does not affect any session already in progress — only sessions started afterward.
- [ ] Reaching the time limit shows a full-screen blocking overlay with the main message that cannot be dismissed except via the "Dismiss" button.
- [ ] After dismissal, the overlay reappears every 5 minutes, cycling Reminder 1 → 2 → 3 → 1 → ... in sequence, until the session ends.
- [ ] A new session (after the prior one ends per the 5-minute-disuse rule) starts elapsed time at 0 with the reminder cycle reset.
- [ ] A global toggle can activate/deactivate all tracking and blocking behavior, and its state persists across app restarts and device reboots.
