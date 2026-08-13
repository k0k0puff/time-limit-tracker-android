# Time Limit Tracker — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android app that monitors foreground app usage, enforces per-app time limits with a blocking overlay, and repeats reminder overlays every 5 minutes until the session ends.

**Architecture:** MVVM with a persistent foreground service (`UsageMonitorService`) that polls `UsageEvents` every 12 seconds, drives a pure-Kotlin session state machine, and triggers `WindowManager` overlays + `AlarmManager` exact alarms for reminders. Room persists all state; ViewModels expose Flows to Compose screens.

**Tech Stack:** Kotlin, Jetpack Compose, Room 2.6.1, DataStore Preferences, Navigation Compose 2.7.7, ViewModel Compose 2.7.0, AlarmManager, WindowManager, UsageStatsManager, KSP for Room codegen.

## Global Constraints

- Minimum SDK: API 26 (Android 8.0); Target SDK: API 34
- Package name: `com.timelimittracker`
- Language: Kotlin only — no Java files
- All Room queries run on `Dispatchers.IO`; all UI state exposed as `StateFlow` or `Flow`
- Session 5-minute pause-to-expiry window: `5 * 60 * 1000L` ms (constant `SESSION_EXPIRY_MS`)
- Service poll interval: `12_000L` ms (constant `POLL_INTERVAL_MS`)
- Reminder interval: `5 * 60 * 1000L` ms (constant `REMINDER_INTERVAL_MS`)
- Reminder cycle: index 0 = main message, 1–3 = reminders; after index 3, loops back to 1
- Snapshot rule: session created with copies of settings at start time; later edits don't affect running sessions
- Token substitution: `{appName}`, `{limit}`, `{elapsed}` — unknown tokens left as-is
- Default main message: `"You've been on {appName} for {limit} min."`
- Default reminder 1: `"Still on {appName}. Take a break."`
- Default reminder 2: `"Another 5 minutes on {appName}..."`
- Default reminder 3: `"You've now been on {appName} for {elapsed} min."`
- No network calls; all data stored locally on-device

---

## File Map

```
app/
  build.gradle.kts                                    # Task 1
  src/
    main/
      AndroidManifest.xml                             # Task 1
      java/com/timelimittracker/
        TimeLimitApp.kt                               # Task 8
        MainActivity.kt                               # Task 12
        data/
          db/
            AppDatabase.kt                            # Task 2
            entities/
              TrackedAppEntity.kt                     # Task 2
              GlobalTemplatesEntity.kt                # Task 2
              SessionEntity.kt                        # Task 2
            dao/
              TrackedAppDao.kt                        # Task 2
              GlobalTemplatesDao.kt                   # Task 2
              SessionDao.kt                           # Task 2
          datastore/
            AppSettingsDataStore.kt                   # Task 3
          repository/
            SettingsRepository.kt                     # Task 3
            SessionRepository.kt                      # Task 3
        service/
          SessionStateMachine.kt                      # Task 4
          TokenSubstitutor.kt                         # Task 4
          AlarmScheduler.kt                           # Task 5
          ReminderReceiver.kt                         # Task 5
          BootReceiver.kt                             # Task 5
          OverlayManager.kt                           # Task 6
          UsageMonitorService.kt                      # Task 7
        ui/
          theme/
            Color.kt                                  # Task 1
            Type.kt                                   # Task 1
            Theme.kt                                  # Task 1
          navigation/
            NavGraph.kt                               # Task 12
          onboarding/
            OnboardingScreen.kt                       # Task 8
            OnboardingViewModel.kt                    # Task 8
          home/
            HomeScreen.kt                             # Task 9
            HomeViewModel.kt                          # Task 9
          addapp/
            AddAppScreen.kt                           # Task 10
            AddAppViewModel.kt                        # Task 10
          templates/
            TemplatesScreen.kt                        # Task 11
            TemplatesViewModel.kt                     # Task 11
    test/java/com/timelimittracker/
      service/
        SessionStateMachineTest.kt                    # Task 4
        TokenSubstitutorTest.kt                       # Task 4
    androidTest/java/com/timelimittracker/
      data/db/dao/
        TrackedAppDaoTest.kt                          # Task 2
        SessionDaoTest.kt                             # Task 2
```

---

### Task 1: Project Scaffold — Gradle, Manifest, Theme

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/timelimittracker/ui/theme/Color.kt`
- Create: `app/src/main/java/com/timelimittracker/ui/theme/Type.kt`
- Create: `app/src/main/java/com/timelimittracker/ui/theme/Theme.kt`

**Interfaces:**
- Produces: All dependencies available on classpath; `TimeLimitTrackerTheme` composable callable from any screen.

- [ ] **Step 1: Create a new Android project in Android Studio**

  File → New → New Project → "Empty Activity" (Compose template).
  - Name: `Time Limit Tracker`
  - Package: `com.timelimittracker`
  - Save location: your project root
  - Language: Kotlin
  - Min SDK: API 26

  This generates the initial Gradle wrapper, `settings.gradle.kts`, and the base `app/` module.

- [ ] **Step 2: Replace `app/build.gradle.kts` with the full dependency set**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

android {
    namespace = "com.timelimittracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.timelimittracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.core:core-ktx:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

- [ ] **Step 3: Replace `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:name=".TimeLimitApp"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:theme="@style/Theme.TimeLimitTracker">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.UsageMonitorService"
            android:foregroundServiceType="specialUse"
            android:exported="false" />

        <receiver
            android:name=".service.ReminderReceiver"
            android:exported="false" />

        <receiver
            android:name=".service.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

- [ ] **Step 4: Write the theme files**

`Color.kt`:
```kotlin
package com.timelimittracker.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)
val OverlayScrim = Color(0xCC000000)
```

`Type.kt`:
```kotlin
package com.timelimittracker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)
```

`Theme.kt`:
```kotlin
package com.timelimittracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.os.Build

private val DarkColorScheme = darkColorScheme(
    primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80
)
private val LightColorScheme = lightColorScheme(
    primary = Purple40, secondary = PurpleGrey40, tertiary = Pink40
)

@Composable
fun TimeLimitTrackerTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColorScheme else LightColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
```

- [ ] **Step 5: Sync Gradle and verify the project builds**

  In Android Studio: File → Sync Project with Gradle Files, then Build → Make Project.
  Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml \
  app/src/main/java/com/timelimittracker/ui/theme/
git commit -m "feat: project scaffold with dependencies, manifest, and theme"
```

---

### Task 2: Room Data Layer — Entities, DAOs, Database

**Files:**
- Create: `app/src/main/java/com/timelimittracker/data/db/entities/TrackedAppEntity.kt`
- Create: `app/src/main/java/com/timelimittracker/data/db/entities/GlobalTemplatesEntity.kt`
- Create: `app/src/main/java/com/timelimittracker/data/db/entities/SessionEntity.kt`
- Create: `app/src/main/java/com/timelimittracker/data/db/dao/TrackedAppDao.kt`
- Create: `app/src/main/java/com/timelimittracker/data/db/dao/GlobalTemplatesDao.kt`
- Create: `app/src/main/java/com/timelimittracker/data/db/dao/SessionDao.kt`
- Create: `app/src/main/java/com/timelimittracker/data/db/AppDatabase.kt`
- Test: `app/src/androidTest/java/com/timelimittracker/data/db/dao/TrackedAppDaoTest.kt`
- Test: `app/src/androidTest/java/com/timelimittracker/data/db/dao/SessionDaoTest.kt`

**Interfaces:**
- Produces: `AppDatabase.getInstance(context)` singleton; `TrackedAppDao`, `GlobalTemplatesDao`, `SessionDao` injectable; `SessionStatus` enum usable by service and repositories.

- [ ] **Step 1: Write the failing DAO tests (instrumented)**

`TrackedAppDaoTest.kt`:
```kotlin
package com.timelimittracker.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.timelimittracker.data.db.AppDatabase
import com.timelimittracker.data.db.entities.TrackedAppEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackedAppDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: TrackedAppDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        dao = db.trackedAppDao()
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun insertAndQueryTrackedApp() = runTest {
        val app = TrackedAppEntity("com.instagram.android", "Instagram", null, 30, true)
        dao.upsert(app)
        val result = dao.getAllTracked().first()
        assertEquals(1, result.size)
        assertEquals("Instagram", result[0].appName)
    }

    @Test
    fun getByPackageName_returnsNullIfAbsent() = runTest {
        val result = dao.getByPackageName("com.missing.app")
        assertNull(result)
    }

    @Test
    fun delete_removesApp() = runTest {
        val app = TrackedAppEntity("com.facebook.katana", "Facebook", null, 45, true)
        dao.upsert(app)
        dao.delete("com.facebook.katana")
        val result = dao.getAllTracked().first()
        assertEquals(0, result.size)
    }
}
```

`SessionDaoTest.kt`:
```kotlin
package com.timelimittracker.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.timelimittracker.data.db.AppDatabase
import com.timelimittracker.data.db.entities.SessionEntity
import com.timelimittracker.data.db.entities.SessionStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        dao = db.sessionDao()
    }

    @After
    fun teardown() { db.close() }

    private fun makeSession(pkg: String, status: SessionStatus) = SessionEntity(
        sessionId = UUID.randomUUID().toString(),
        packageName = pkg,
        accumulatedActiveSeconds = 0L,
        lastForegroundTimestamp = System.currentTimeMillis(),
        pausedAt = null,
        status = status,
        snapshotLimitMinutes = 30,
        snapshotMainMessage = "Main",
        snapshotReminder1 = null,
        snapshotReminder2 = null,
        snapshotReminder3 = null,
        reminderCycleIndex = 0,
        lastPromptTime = null
    )

    @Test
    fun getActiveSession_returnsNonEndedSession() = runTest {
        dao.insert(makeSession("com.instagram.android", SessionStatus.ACTIVE))
        val result = dao.getActiveSession("com.instagram.android")
        assertEquals(SessionStatus.ACTIVE, result?.status)
    }

    @Test
    fun getActiveSession_returnsNullAfterEnded() = runTest {
        val s = makeSession("com.instagram.android", SessionStatus.ENDED)
        dao.insert(s)
        val result = dao.getActiveSession("com.instagram.android")
        assertNull(result)
    }

    @Test
    fun endAllActiveSessions_marksAllAsEnded() = runTest {
        dao.insert(makeSession("com.instagram.android", SessionStatus.ACTIVE))
        dao.insert(makeSession("com.facebook.katana", SessionStatus.PAUSED))
        dao.endAllActiveSessions()
        assertNull(dao.getActiveSession("com.instagram.android"))
        assertNull(dao.getActiveSession("com.facebook.katana"))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

  In Android Studio: right-click `androidTest` folder → Run Tests (or run on connected device/emulator).
  Expected: compile error — types not yet defined.

- [ ] **Step 3: Write the `SessionStatus` enum and entities**

`SessionEntity.kt`:
```kotlin
package com.timelimittracker.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SessionStatus { ACTIVE, PAUSED, LIMIT_REACHED, ENDED }

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val packageName: String,
    val accumulatedActiveSeconds: Long,
    val lastForegroundTimestamp: Long,
    val pausedAt: Long?,
    val status: SessionStatus,
    val snapshotLimitMinutes: Int,
    val snapshotMainMessage: String,
    val snapshotReminder1: String?,
    val snapshotReminder2: String?,
    val snapshotReminder3: String?,
    val reminderCycleIndex: Int,
    val lastPromptTime: Long?
)
```

`TrackedAppEntity.kt`:
```kotlin
package com.timelimittracker.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracked_apps")
data class TrackedAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val iconBytes: ByteArray?,
    val limitMinutes: Int,
    val isTracked: Boolean
)
```

`GlobalTemplatesEntity.kt`:
```kotlin
package com.timelimittracker.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "global_templates")
data class GlobalTemplatesEntity(
    @PrimaryKey val id: Int = 1,
    val mainMessage: String = "You've been on {appName} for {limit} min.",
    val reminder1: String? = null,
    val reminder2: String? = null,
    val reminder3: String? = null
)
```

- [ ] **Step 4: Write the DAOs**

`TrackedAppDao.kt`:
```kotlin
package com.timelimittracker.data.db.dao

import androidx.room.*
import com.timelimittracker.data.db.entities.TrackedAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedAppDao {
    @Upsert
    suspend fun upsert(app: TrackedAppEntity)

    @Query("SELECT * FROM tracked_apps WHERE isTracked = 1")
    fun getAllTracked(): Flow<List<TrackedAppEntity>>

    @Query("SELECT * FROM tracked_apps")
    fun getAll(): Flow<List<TrackedAppEntity>>

    @Query("SELECT * FROM tracked_apps WHERE packageName = :packageName")
    suspend fun getByPackageName(packageName: String): TrackedAppEntity?

    @Query("DELETE FROM tracked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
```

`GlobalTemplatesDao.kt`:
```kotlin
package com.timelimittracker.data.db.dao

import androidx.room.*
import com.timelimittracker.data.db.entities.GlobalTemplatesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GlobalTemplatesDao {
    @Upsert
    suspend fun upsert(templates: GlobalTemplatesEntity)

    @Query("SELECT * FROM global_templates WHERE id = 1")
    fun observe(): Flow<GlobalTemplatesEntity?>

    @Query("SELECT * FROM global_templates WHERE id = 1")
    suspend fun get(): GlobalTemplatesEntity?
}
```

`SessionDao.kt`:
```kotlin
package com.timelimittracker.data.db.dao

import androidx.room.*
import com.timelimittracker.data.db.entities.SessionEntity
import com.timelimittracker.data.db.entities.SessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("""
        SELECT * FROM sessions
        WHERE packageName = :packageName
        AND status != 'ENDED'
        LIMIT 1
    """)
    suspend fun getActiveSession(packageName: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status != 'ENDED'")
    suspend fun getAllActiveSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE status != 'ENDED'")
    fun observeActiveSessions(): Flow<List<SessionEntity>>

    @Query("""
        UPDATE sessions SET status = 'ENDED'
        WHERE status != 'ENDED'
    """)
    suspend fun endAllActiveSessions()
}
```

- [ ] **Step 5: Write `AppDatabase`**

```kotlin
package com.timelimittracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.timelimittracker.data.db.dao.GlobalTemplatesDao
import com.timelimittracker.data.db.dao.SessionDao
import com.timelimittracker.data.db.dao.TrackedAppDao
import com.timelimittracker.data.db.entities.GlobalTemplatesEntity
import com.timelimittracker.data.db.entities.SessionEntity
import com.timelimittracker.data.db.entities.TrackedAppEntity

@Database(
    entities = [TrackedAppEntity::class, GlobalTemplatesEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackedAppDao(): TrackedAppDao
    abstract fun globalTemplatesDao(): GlobalTemplatesDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "time_limit_tracker.db"
                ).build().also { INSTANCE = it }
            }
    }
}
```

- [ ] **Step 6: Run instrumented tests — confirm they pass**

  Expected: `TrackedAppDaoTest` — 3 passed; `SessionDaoTest` — 3 passed.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/timelimittracker/data/db/ \
  app/src/androidTest/java/com/timelimittracker/data/db/
git commit -m "feat: Room entities, DAOs, and database with DAO tests"
```

---

### Task 3: DataStore + Repositories

**Files:**
- Create: `app/src/main/java/com/timelimittracker/data/datastore/AppSettingsDataStore.kt`
- Create: `app/src/main/java/com/timelimittracker/data/repository/SettingsRepository.kt`
- Create: `app/src/main/java/com/timelimittracker/data/repository/SessionRepository.kt`

**Interfaces:**
- Consumes: `AppDatabase` (Task 2), `TrackedAppDao`, `GlobalTemplatesDao`, `SessionDao`, `SessionEntity`, `TrackedAppEntity`, `GlobalTemplatesEntity`
- Produces:
  - `AppSettingsDataStore.trackingEnabled: Flow<Boolean>`, `suspend setTrackingEnabled(Boolean)`
  - `SettingsRepository.trackedApps: Flow<List<TrackedAppEntity>>`, `suspend upsertTrackedApp(TrackedAppEntity)`, `suspend deleteTrackedApp(String)`, `suspend getTemplates(): GlobalTemplatesEntity`, `templates: Flow<GlobalTemplatesEntity?>`, `suspend upsertTemplates(GlobalTemplatesEntity)`
  - `SessionRepository.activeSessions: Flow<List<SessionEntity>>`, `suspend getActiveSession(String): SessionEntity?`, `suspend upsertSession(SessionEntity)`, `suspend endAllSessions()`

- [ ] **Step 1: Write `AppSettingsDataStore`**

```kotlin
package com.timelimittracker.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsDataStore(private val context: Context) {
    companion object {
        private val TRACKING_ENABLED = booleanPreferencesKey("tracking_enabled")
    }

    val trackingEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[TRACKING_ENABLED] ?: false }

    suspend fun setTrackingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[TRACKING_ENABLED] = enabled
        }
    }
}
```

- [ ] **Step 2: Write `SettingsRepository`**

```kotlin
package com.timelimittracker.data.repository

import com.timelimittracker.data.db.dao.GlobalTemplatesDao
import com.timelimittracker.data.db.dao.TrackedAppDao
import com.timelimittracker.data.db.entities.GlobalTemplatesEntity
import com.timelimittracker.data.db.entities.TrackedAppEntity
import kotlinx.coroutines.flow.Flow

class SettingsRepository(
    private val trackedAppDao: TrackedAppDao,
    private val globalTemplatesDao: GlobalTemplatesDao
) {
    val trackedApps: Flow<List<TrackedAppEntity>> = trackedAppDao.getAllTracked()
    val allApps: Flow<List<TrackedAppEntity>> = trackedAppDao.getAll()
    val templates: Flow<GlobalTemplatesEntity?> = globalTemplatesDao.observe()

    suspend fun upsertTrackedApp(app: TrackedAppEntity) = trackedAppDao.upsert(app)
    suspend fun deleteTrackedApp(packageName: String) = trackedAppDao.delete(packageName)
    suspend fun getByPackageName(packageName: String) = trackedAppDao.getByPackageName(packageName)
    suspend fun getTemplates(): GlobalTemplatesEntity =
        globalTemplatesDao.get() ?: GlobalTemplatesEntity()
    suspend fun upsertTemplates(templates: GlobalTemplatesEntity) =
        globalTemplatesDao.upsert(templates)
}
```

- [ ] **Step 3: Write `SessionRepository`**

```kotlin
package com.timelimittracker.data.repository

import com.timelimittracker.data.db.dao.SessionDao
import com.timelimittracker.data.db.entities.SessionEntity
import kotlinx.coroutines.flow.Flow

class SessionRepository(private val sessionDao: SessionDao) {
    val activeSessions: Flow<List<SessionEntity>> = sessionDao.observeActiveSessions()

    suspend fun getActiveSession(packageName: String): SessionEntity? =
        sessionDao.getActiveSession(packageName)

    suspend fun getAllActiveSessions(): List<SessionEntity> =
        sessionDao.getAllActiveSessions()

    suspend fun upsertSession(session: SessionEntity) = sessionDao.insert(session)
    suspend fun updateSession(session: SessionEntity) = sessionDao.update(session)
    suspend fun endAllSessions() = sessionDao.endAllActiveSessions()
}
```

- [ ] **Step 4: Verify project compiles**

  Build → Make Project. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/timelimittracker/data/
git commit -m "feat: DataStore, SettingsRepository, and SessionRepository"
```

---

### Task 4: SessionStateMachine + TokenSubstitutor (Pure Kotlin, Unit Tested)

**Files:**
- Create: `app/src/main/java/com/timelimittracker/service/TokenSubstitutor.kt`
- Create: `app/src/main/java/com/timelimittracker/service/SessionStateMachine.kt`
- Test: `app/src/test/java/com/timelimittracker/service/TokenSubstitutorTest.kt`
- Test: `app/src/test/java/com/timelimittracker/service/SessionStateMachineTest.kt`

**Interfaces:**
- Produces:
  - `TokenSubstitutor.substitute(template: String, appName: String, limitMinutes: Int, elapsedSeconds: Long): String`
  - `SessionStateMachine.startSession(packageName, snapshot, nowMs): SessionEntity`
  - `SessionStateMachine.onForeground(session, nowMs): Pair<SessionEntity, SessionEvent>`
  - `SessionStateMachine.onBackground(session, nowMs): SessionEntity`
  - `SessionStateMachine.checkExpiry(session, nowMs): SessionEntity`
  - `SessionStateMachine.advanceReminderCycle(session): SessionEntity`
  - `sealed class SessionEvent { object None, LimitReached, AlreadyAtLimit }`

- [ ] **Step 1: Write failing unit tests for `TokenSubstitutor`**

`TokenSubstitutorTest.kt`:
```kotlin
package com.timelimittracker.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenSubstitutorTest {
    @Test
    fun substitutesAppName() {
        val result = TokenSubstitutor.substitute("{appName}", "Instagram", 30, 0)
        assertEquals("Instagram", result)
    }

    @Test
    fun substitutesLimit() {
        val result = TokenSubstitutor.substitute("{limit}", "Instagram", 30, 0)
        assertEquals("30", result)
    }

    @Test
    fun substitutesElapsed() {
        val result = TokenSubstitutor.substitute("{elapsed}", "Instagram", 30, 1860)
        assertEquals("31", result) // 1860 seconds = 31 minutes
    }

    @Test
    fun substitutesAllTokens() {
        val result = TokenSubstitutor.substitute(
            "You've been on {appName} for {elapsed} min (limit: {limit})",
            "TikTok", 45, 2700
        )
        assertEquals("You've been on TikTok for 45 min (limit: 45)", result)
    }

    @Test
    fun unknownTokenLeftAsIs() {
        val result = TokenSubstitutor.substitute("{unknown}", "App", 30, 0)
        assertEquals("{unknown}", result)
    }

    @Test
    fun emptyTemplateReturnsEmpty() {
        val result = TokenSubstitutor.substitute("", "App", 30, 0)
        assertEquals("", result)
    }
}
```

- [ ] **Step 2: Write failing unit tests for `SessionStateMachine`**

`SessionStateMachineTest.kt`:
```kotlin
package com.timelimittracker.service

import com.timelimittracker.data.db.entities.SessionStatus
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SessionStateMachineTest {
    private val snapshot = SessionSnapshot(
        limitMinutes = 1,
        mainMessage = "Main",
        reminder1 = "R1",
        reminder2 = "R2",
        reminder3 = "R3"
    )
    private val t0 = 1_000_000L

    @Test
    fun startSession_createsActiveSession() {
        val session = SessionStateMachine.startSession("com.example.app", snapshot, t0)
        assertEquals(SessionStatus.ACTIVE, session.status)
        assertEquals(0L, session.accumulatedActiveSeconds)
        assertEquals(t0, session.lastForegroundTimestamp)
    }

    @Test
    fun onForeground_accumulatesTime() {
        val session = SessionStateMachine.startSession("com.example.app", snapshot, t0)
        val t1 = t0 + 30_000L // 30 seconds later
        val (updated, event) = SessionStateMachine.onForeground(session, t1)
        assertEquals(30L, updated.accumulatedActiveSeconds)
        assertEquals(SessionEvent.None, event)
    }

    @Test
    fun onForeground_firesLimitReachedWhenLimitExceeded() {
        val session = SessionStateMachine.startSession("com.example.app", snapshot, t0)
        val t1 = t0 + 61_000L // limit is 1 minute = 60 seconds
        val (updated, event) = SessionStateMachine.onForeground(session, t1)
        assertEquals(SessionStatus.LIMIT_REACHED, updated.status)
        assertEquals(SessionEvent.LimitReached, event)
    }

    @Test
    fun onBackground_pausesActiveSession() {
        val session = SessionStateMachine.startSession("com.example.app", snapshot, t0)
        val t1 = t0 + 10_000L
        val paused = SessionStateMachine.onBackground(session, t1)
        assertEquals(SessionStatus.PAUSED, paused.status)
        assertEquals(t1, paused.pausedAt)
    }

    @Test
    fun checkExpiry_endsSessionAfter5Minutes() {
        val session = SessionStateMachine.startSession("com.example.app", snapshot, t0)
        val paused = SessionStateMachine.onBackground(session, t0 + 10_000L)
        val t2 = t0 + 10_000L + (5 * 60 * 1000L) + 1L
        val ended = SessionStateMachine.checkExpiry(paused, t2)
        assertEquals(SessionStatus.ENDED, ended.status)
    }

    @Test
    fun checkExpiry_doesNotEndSessionBeforeWindow() {
        val session = SessionStateMachine.startSession("com.example.app", snapshot, t0)
        val paused = SessionStateMachine.onBackground(session, t0 + 10_000L)
        val t2 = t0 + 10_000L + (4 * 60 * 1000L)
        val stillPaused = SessionStateMachine.checkExpiry(paused, t2)
        assertEquals(SessionStatus.PAUSED, stillPaused.status)
    }

    @Test
    fun advanceReminderCycle_advancesFrom0To1() {
        val session = SessionStateMachine.startSession("com.example.app", snapshot, t0)
        val advanced = SessionStateMachine.advanceReminderCycle(session)
        assertEquals(1, advanced.reminderCycleIndex)
    }

    @Test
    fun advanceReminderCycle_loopsFrom3BackTo1() {
        val session = SessionStateMachine.startSession("com.example.app", snapshot, t0)
            .copy(reminderCycleIndex = 3)
        val looped = SessionStateMachine.advanceReminderCycle(session)
        assertEquals(1, looped.reminderCycleIndex)
    }

    @Test
    fun resumeFromPause_accumulatesOnlyActiveTime() {
        val session = SessionStateMachine.startSession("com.example.app", snapshot, t0)
        // Foreground for 20s
        val (after20s, _) = SessionStateMachine.onForeground(session, t0 + 20_000L)
        // Background (pause) - accumulated should be 20s
        val paused = SessionStateMachine.onBackground(after20s, t0 + 20_000L)
        assertEquals(SessionStatus.PAUSED, paused.status)
        // Return to foreground 30s later (within 5-min window)
        val (resumed, _) = SessionStateMachine.onForeground(paused, t0 + 50_000L)
        // Only additional foreground time accumulates
        // The last foreground timestamp is reset to t0+50s, accumulated stays at 20s until next tick
        assertEquals(SessionStatus.ACTIVE, resumed.status)
        assertEquals(20L, resumed.accumulatedActiveSeconds)
    }
}
```

- [ ] **Step 3: Run JVM tests to confirm they fail**

```bash
./gradlew test
```
Expected: Compile error — `TokenSubstitutor`, `SessionStateMachine`, `SessionSnapshot`, `SessionEvent` not yet defined.

- [ ] **Step 4: Write `TokenSubstitutor`**

```kotlin
package com.timelimittracker.service

object TokenSubstitutor {
    fun substitute(template: String, appName: String, limitMinutes: Int, elapsedSeconds: Long): String {
        val elapsedMinutes = (elapsedSeconds / 60).toString()
        return template
            .replace("{appName}", appName)
            .replace("{limit}", limitMinutes.toString())
            .replace("{elapsed}", elapsedMinutes)
    }
}
```

- [ ] **Step 5: Write `SessionSnapshot` data class and `SessionEvent` sealed class**

Add to `SessionStateMachine.kt`:
```kotlin
package com.timelimittracker.service

import com.timelimittracker.data.db.entities.SessionEntity
import com.timelimittracker.data.db.entities.SessionStatus
import java.util.UUID

data class SessionSnapshot(
    val limitMinutes: Int,
    val mainMessage: String,
    val reminder1: String?,
    val reminder2: String?,
    val reminder3: String?
)

sealed class SessionEvent {
    object None : SessionEvent()
    object LimitReached : SessionEvent()
    object AlreadyAtLimit : SessionEvent()
}
```

- [ ] **Step 6: Write `SessionStateMachine`**

```kotlin
object SessionStateMachine {
    private const val SESSION_EXPIRY_MS = 5 * 60 * 1000L

    fun startSession(packageName: String, snapshot: SessionSnapshot, nowMs: Long): SessionEntity =
        SessionEntity(
            sessionId = UUID.randomUUID().toString(),
            packageName = packageName,
            accumulatedActiveSeconds = 0L,
            lastForegroundTimestamp = nowMs,
            pausedAt = null,
            status = SessionStatus.ACTIVE,
            snapshotLimitMinutes = snapshot.limitMinutes,
            snapshotMainMessage = snapshot.mainMessage,
            snapshotReminder1 = snapshot.reminder1,
            snapshotReminder2 = snapshot.reminder2,
            snapshotReminder3 = snapshot.reminder3,
            reminderCycleIndex = 0,
            lastPromptTime = null
        )

    fun onForeground(session: SessionEntity, nowMs: Long): Pair<SessionEntity, SessionEvent> {
        val baseSession = if (session.status == SessionStatus.PAUSED) {
            // Resume: don't count paused gap, reset lastForegroundTimestamp
            session.copy(status = SessionStatus.ACTIVE, pausedAt = null, lastForegroundTimestamp = nowMs)
        } else {
            session
        }

        val additionalSeconds = (nowMs - baseSession.lastForegroundTimestamp) / 1000L
        val newAccumulated = baseSession.accumulatedActiveSeconds + additionalSeconds
        val limitSeconds = baseSession.snapshotLimitMinutes * 60L

        return if (newAccumulated >= limitSeconds && baseSession.status != SessionStatus.LIMIT_REACHED) {
            val updated = baseSession.copy(
                accumulatedActiveSeconds = newAccumulated,
                lastForegroundTimestamp = nowMs,
                status = SessionStatus.LIMIT_REACHED
            )
            Pair(updated, SessionEvent.LimitReached)
        } else {
            val updated = baseSession.copy(
                accumulatedActiveSeconds = newAccumulated,
                lastForegroundTimestamp = nowMs
            )
            val event = if (updated.status == SessionStatus.LIMIT_REACHED)
                SessionEvent.AlreadyAtLimit else SessionEvent.None
            Pair(updated, event)
        }
    }

    fun onBackground(session: SessionEntity, nowMs: Long): SessionEntity {
        if (session.status == SessionStatus.PAUSED || session.status == SessionStatus.ENDED) return session
        return session.copy(status = SessionStatus.PAUSED, pausedAt = nowMs)
    }

    fun checkExpiry(session: SessionEntity, nowMs: Long): SessionEntity {
        if (session.status != SessionStatus.PAUSED) return session
        val pausedAt = session.pausedAt ?: return session
        return if (nowMs - pausedAt > SESSION_EXPIRY_MS) {
            session.copy(status = SessionStatus.ENDED)
        } else {
            session
        }
    }

    fun advanceReminderCycle(session: SessionEntity): SessionEntity {
        val nextIndex = if (session.reminderCycleIndex >= 3) 1 else session.reminderCycleIndex + 1
        return session.copy(reminderCycleIndex = nextIndex, lastPromptTime = System.currentTimeMillis())
    }

    fun getMessageForCycle(session: SessionEntity): String {
        val defaultR1 = "Still on {appName}. Take a break."
        val defaultR2 = "Another 5 minutes on {appName}..."
        val defaultR3 = "You've now been on {appName} for {elapsed} min."
        return when (session.reminderCycleIndex) {
            0 -> session.snapshotMainMessage
            1 -> session.snapshotReminder1 ?: defaultR1
            2 -> session.snapshotReminder2 ?: defaultR2
            3 -> session.snapshotReminder3 ?: defaultR3
            else -> session.snapshotReminder1 ?: defaultR1
        }
    }
}
```

- [ ] **Step 7: Run JVM tests — confirm they pass**

```bash
./gradlew test
```
Expected: All 9 tests PASSED.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/timelimittracker/service/SessionStateMachine.kt \
  app/src/main/java/com/timelimittracker/service/TokenSubstitutor.kt \
  app/src/test/java/com/timelimittracker/service/
git commit -m "feat: SessionStateMachine and TokenSubstitutor with unit tests"
```

---

### Task 5: AlarmScheduler + ReminderReceiver + BootReceiver

**Files:**
- Create: `app/src/main/java/com/timelimittracker/service/AlarmScheduler.kt`
- Create: `app/src/main/java/com/timelimittracker/service/ReminderReceiver.kt`
- Create: `app/src/main/java/com/timelimittracker/service/BootReceiver.kt`

**Interfaces:**
- Consumes: `SessionRepository` (Task 3), `UsageMonitorService` (Task 7 — forward reference; receivers just start the service)
- Produces:
  - `AlarmScheduler.scheduleReminder(context, packageName)` — schedules exact alarm 5 min from now
  - `AlarmScheduler.cancelReminder(context, packageName)` — cancels pending alarm
  - `ReminderReceiver` — receives alarm, starts `UsageMonitorService` with `ACTION_REMINDER` intent
  - `BootReceiver` — receives `BOOT_COMPLETED`, starts `UsageMonitorService` with `ACTION_BOOT` intent

- [ ] **Step 1: Write `AlarmScheduler`**

```kotlin
package com.timelimittracker.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object AlarmScheduler {
    private const val REMINDER_INTERVAL_MS = 5 * 60 * 1000L
    private const val ACTION_REMINDER = "com.timelimittracker.ACTION_REMINDER"
    private const val EXTRA_PACKAGE_NAME = "package_name"

    fun scheduleReminder(context: Context, packageName: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        val requestCode = packageName.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + REMINDER_INTERVAL_MS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            // Fallback: window alarm (up to 5-minute tolerance)
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                5 * 60 * 1000L,
                pendingIntent
            )
        }
    }

    fun cancelReminder(context: Context, packageName: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
        }
        val requestCode = packageName.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }
}
```

- [ ] **Step 2: Write `ReminderReceiver`**

```kotlin
package com.timelimittracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val ACTION_REMINDER = "com.timelimittracker.ACTION_REMINDER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val serviceIntent = Intent(context, UsageMonitorService::class.java).apply {
            action = UsageMonitorService.ACTION_REMINDER
            putExtra(UsageMonitorService.EXTRA_PACKAGE_NAME, packageName)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
```

- [ ] **Step 3: Write `BootReceiver`**

```kotlin
package com.timelimittracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val serviceIntent = Intent(context, UsageMonitorService::class.java).apply {
            action = UsageMonitorService.ACTION_BOOT
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
```

- [ ] **Step 4: Build to confirm no errors**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL (note: `UsageMonitorService` is referenced but not yet created — add a stub if needed).

If build fails due to missing `UsageMonitorService`, create a temporary stub:
```kotlin
// Temporary stub — will be replaced in Task 7
package com.timelimittracker.service
import android.app.Service
import android.content.Intent
import android.os.IBinder
class UsageMonitorService : Service() {
    companion object {
        const val ACTION_REMINDER = "com.timelimittracker.ACTION_REMINDER"
        const val ACTION_BOOT = "com.timelimittracker.ACTION_BOOT"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/timelimittracker/service/AlarmScheduler.kt \
  app/src/main/java/com/timelimittracker/service/ReminderReceiver.kt \
  app/src/main/java/com/timelimittracker/service/BootReceiver.kt
git commit -m "feat: AlarmScheduler, ReminderReceiver, and BootReceiver"
```

---

### Task 6: OverlayManager

**Files:**
- Create: `app/src/main/java/com/timelimittracker/service/OverlayManager.kt`

**Interfaces:**
- Produces:
  - `OverlayManager(context: Context)`
  - `fun show(message: String, onDismiss: () -> Unit)`
  - `fun hide()`
  - `val isShowing: Boolean`

- [ ] **Step 1: Write `OverlayManager`**

The overlay is a programmatic view (not Compose) since it runs from a service context with no lifecycle owner.

```kotlin
package com.timelimittracker.service

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var currentOverlay: FrameLayout? = null

    val isShowing: Boolean get() = currentOverlay != null

    fun show(message: String, onDismiss: () -> Unit) {
        if (isShowing) hide()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            // Allow button to receive touch but block everything else
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        }

        val overlay = buildOverlayView(message) {
            hide()
            onDismiss()
        }

        try {
            windowManager.addView(overlay, params)
            currentOverlay = overlay
        } catch (e: WindowManager.BadTokenException) {
            Log.e("OverlayManager", "Cannot show overlay — SYSTEM_ALERT_WINDOW revoked", e)
        }
    }

    fun hide() {
        val overlay = currentOverlay ?: return
        try {
            windowManager.removeView(overlay)
        } catch (e: Exception) {
            Log.e("OverlayManager", "Error removing overlay", e)
        }
        currentOverlay = null
    }

    private fun buildOverlayView(message: String, onDismiss: () -> Unit): FrameLayout {
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(204, 0, 0, 0)) // 80% black
            isClickable = true
            isFocusable = true
        }

        val cardPaddingPx = dpToPx(24)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPaddingPx, cardPaddingPx, cardPaddingPx, cardPaddingPx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dpToPx(12).toFloat()
            }
        }

        val messageView = TextView(context).apply {
            text = message
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(24))
        }

        val dismissButton = Button(context).apply {
            text = "Dismiss"
            textSize = 16f
            setOnClickListener { onDismiss() }
        }

        card.addView(messageView)
        card.addView(dismissButton)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            val margin = dpToPx(32)
            setMargins(margin, margin, margin, margin)
        }

        scrim.addView(card, cardParams)
        return scrim
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
```

- [ ] **Step 2: Manual verification plan**

  After Task 7 (service) is complete, manually verify the overlay by:
  1. Granting SYSTEM_ALERT_WINDOW permission.
  2. Opening a tracked app past its time limit.
  3. Confirm: full-screen dark overlay appears with message and Dismiss button.
  4. Confirm: tapping outside the button does nothing; tapping Dismiss removes the overlay.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/timelimittracker/service/OverlayManager.kt
git commit -m "feat: WindowManager overlay manager for blocking overlay"
```

---

### Task 7: UsageMonitorService

**Files:**
- Modify/Replace: `app/src/main/java/com/timelimittracker/service/UsageMonitorService.kt`

**Interfaces:**
- Consumes: `SessionStateMachine` (Task 4), `AlarmScheduler` (Task 5), `OverlayManager` (Task 6), `SettingsRepository` (Task 3), `SessionRepository` (Task 3), `AppSettingsDataStore` (Task 3), `AppDatabase` (Task 2)
- Produces: Running foreground service that drives the session lifecycle and overlay display.

- [ ] **Step 1: Write `UsageMonitorService`**

```kotlin
package com.timelimittracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.timelimittracker.data.db.AppDatabase
import com.timelimittracker.data.db.entities.SessionStatus
import com.timelimittracker.data.datastore.AppSettingsDataStore
import com.timelimittracker.data.repository.SessionRepository
import com.timelimittracker.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class UsageMonitorService : Service() {

    companion object {
        const val ACTION_REMINDER = "com.timelimittracker.ACTION_REMINDER"
        const val ACTION_BOOT = "com.timelimittracker.ACTION_BOOT"
        const val EXTRA_PACKAGE_NAME = "package_name"
        private const val NOTIF_CHANNEL_ID = "usage_monitor"
        private const val NOTIF_ID = 1
        private const val POLL_INTERVAL_MS = 12_000L
        private const val TAG = "UsageMonitorService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var dataStore: AppSettingsDataStore
    private lateinit var overlayManager: OverlayManager
    private lateinit var usageStatsManager: UsageStatsManager
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        settingsRepo = SettingsRepository(db.trackedAppDao(), db.globalTemplatesDao())
        sessionRepo = SessionRepository(db.sessionDao())
        dataStore = AppSettingsDataStore(this)
        overlayManager = OverlayManager(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BOOT -> scope.launch { handleBoot() }
            ACTION_REMINDER -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return START_STICKY
                scope.launch { handleReminder(pkg) }
            }
        }
        startPollingIfNeeded()
        return START_STICKY
    }

    private fun startPollingIfNeeded() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try {
                    poll()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun poll() {
        val trackingEnabled = dataStore.trackingEnabled.first()
        if (!trackingEnabled) return

        val foregroundPkg = getForegroundPackage()
        val trackedApps = settingsRepo.trackedApps.first()
        val nowMs = System.currentTimeMillis()

        // Check for uninstalled apps
        trackedApps.forEach { app ->
            try {
                packageManager.getPackageInfo(app.packageName, 0)
            } catch (e: Exception) {
                Log.i(TAG, "App uninstalled: ${app.packageName} — ending session")
                val session = sessionRepo.getActiveSession(app.packageName)
                if (session != null) {
                    sessionRepo.updateSession(session.copy(status = SessionStatus.ENDED))
                    AlarmScheduler.cancelReminder(this, app.packageName)
                }
                settingsRepo.deleteTrackedApp(app.packageName)
                return@forEach
            }
        }

        trackedApps.forEach { app ->
            val session = sessionRepo.getActiveSession(app.packageName)

            if (foregroundPkg == app.packageName) {
                // App is in foreground
                if (session == null) {
                    // Start new session
                    val templates = settingsRepo.getTemplates()
                    val snapshot = SessionSnapshot(
                        limitMinutes = app.limitMinutes,
                        mainMessage = templates.mainMessage,
                        reminder1 = templates.reminder1,
                        reminder2 = templates.reminder2,
                        reminder3 = templates.reminder3
                    )
                    val newSession = SessionStateMachine.startSession(app.packageName, snapshot, nowMs)
                    sessionRepo.upsertSession(newSession)
                } else {
                    val (updated, event) = SessionStateMachine.onForeground(session, nowMs)
                    sessionRepo.updateSession(updated)
                    if (event == SessionEvent.LimitReached) {
                        val message = SessionStateMachine.getMessageForCycle(updated)
                        val substituted = TokenSubstitutor.substitute(
                            message, app.appName, updated.snapshotLimitMinutes,
                            updated.accumulatedActiveSeconds
                        )
                        withContext(Dispatchers.Main) { overlayManager.show(substituted) {} }
                        AlarmScheduler.scheduleReminder(this, app.packageName)
                    }
                }
            } else {
                // App not in foreground
                if (session != null) {
                    when (session.status) {
                        SessionStatus.ACTIVE, SessionStatus.LIMIT_REACHED -> {
                            val paused = SessionStateMachine.onBackground(session, nowMs)
                            sessionRepo.updateSession(paused)
                        }
                        SessionStatus.PAUSED -> {
                            val maybeEnded = SessionStateMachine.checkExpiry(session, nowMs)
                            if (maybeEnded.status == SessionStatus.ENDED) {
                                sessionRepo.updateSession(maybeEnded)
                                AlarmScheduler.cancelReminder(this, app.packageName)
                                withContext(Dispatchers.Main) { overlayManager.hide() }
                            }
                        }
                        SessionStatus.ENDED -> { /* nothing */ }
                    }
                }
            }
        }
    }

    private suspend fun handleReminder(packageName: String) {
        val session = sessionRepo.getActiveSession(packageName) ?: return
        if (session.status != SessionStatus.LIMIT_REACHED) return

        val foregroundPkg = getForegroundPackage()
        if (foregroundPkg != packageName) return  // App no longer in foreground — skip

        val advanced = SessionStateMachine.advanceReminderCycle(session)
        sessionRepo.updateSession(advanced)

        val trackedApp = settingsRepo.getByPackageName(packageName) ?: return
        val message = SessionStateMachine.getMessageForCycle(advanced)
        val substituted = TokenSubstitutor.substitute(
            message, trackedApp.appName, advanced.snapshotLimitMinutes,
            advanced.accumulatedActiveSeconds
        )
        withContext(Dispatchers.Main) { overlayManager.show(substituted) {} }
        AlarmScheduler.scheduleReminder(this, packageName)
    }

    private suspend fun handleBoot() {
        sessionRepo.endAllSessions()
    }

    private fun getForegroundPackage(): String? {
        val nowMs = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(nowMs - 10_000L, nowMs)
        val event = UsageEvents.Event()
        var lastForegroundPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForegroundPkg = event.packageName
            }
        }
        return lastForegroundPkg
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Usage Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Tracks app usage in the background" }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Time Limit Tracker")
            .setContentText("Monitoring app usage...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        overlayManager.hide()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

- [ ] **Step 2: Build the project**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke test on device/emulator**

  1. Install debug APK.
  2. Grant Usage Access and Display Over Other Apps permissions manually in Settings.
  3. Add a tracked app (you'll wire the UI in later tasks; for now, insert a row directly via DB browser or wait for Task 9/10).
  4. Set `limitMinutes = 1` for quick testing.
  5. Open the tracked app for 60+ seconds.
  6. Confirm overlay appears.
  7. Dismiss it; wait 5 minutes; confirm reminder overlay appears.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/timelimittracker/service/UsageMonitorService.kt
git commit -m "feat: UsageMonitorService — polling, session lifecycle, overlay, alarms"
```

---

### Task 8: Application Class + Permissions Onboarding

**Files:**
- Create: `app/src/main/java/com/timelimittracker/TimeLimitApp.kt`
- Create: `app/src/main/java/com/timelimittracker/ui/onboarding/OnboardingViewModel.kt`
- Create: `app/src/main/java/com/timelimittracker/ui/onboarding/OnboardingScreen.kt`

**Interfaces:**
- Produces:
  - `TimeLimitApp` — Application class; exposes `database`, `settingsRepo`, `sessionRepo`, `dataStore` as properties
  - `OnboardingViewModel.permissionsGranted: StateFlow<PermissionState>`, `fun refresh()`
  - `OnboardingScreen(onAllRequiredGranted: () -> Unit)`
  - `data class PermissionState(usageAccess: Boolean, overlayPermission: Boolean, notifications: Boolean, batteryOptExempt: Boolean)`

- [ ] **Step 1: Write `TimeLimitApp`**

```kotlin
package com.timelimittracker

import android.app.Application
import com.timelimittracker.data.datastore.AppSettingsDataStore
import com.timelimittracker.data.db.AppDatabase
import com.timelimittracker.data.repository.SessionRepository
import com.timelimittracker.data.repository.SettingsRepository

class TimeLimitApp : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
    val settingsRepo by lazy {
        SettingsRepository(database.trackedAppDao(), database.globalTemplatesDao())
    }
    val sessionRepo by lazy { SessionRepository(database.sessionDao()) }
    val dataStore by lazy { AppSettingsDataStore(this) }
}
```

- [ ] **Step 2: Write `OnboardingViewModel`**

```kotlin
package com.timelimittracker.ui.onboarding

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PermissionState(
    val usageAccess: Boolean,
    val overlayPermission: Boolean,
    val notifications: Boolean,
    val batteryOptExempt: Boolean
) {
    val allRequiredGranted: Boolean get() = usageAccess && overlayPermission
}

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val _permissions = MutableStateFlow(checkPermissions())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    fun refresh() {
        _permissions.value = checkPermissions()
    }

    private fun checkPermissions(): PermissionState {
        val ctx = getApplication<Application>()
        return PermissionState(
            usageAccess = hasUsageAccess(ctx),
            overlayPermission = Settings.canDrawOverlays(ctx),
            notifications = hasNotificationPermission(),
            batteryOptExempt = isBatteryOptExempt(ctx)
        )
    }

    private fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val ctx = getApplication<Application>()
        return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryOptExempt(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}
```

- [ ] **Step 3: Write `OnboardingScreen`**

```kotlin
package com.timelimittracker.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun OnboardingScreen(
    onAllRequiredGranted: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val context = LocalContext.current
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()

    LaunchedEffect(permissions.allRequiredGranted) {
        if (permissions.allRequiredGranted) onAllRequiredGranted()
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Setup Required", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Time Limit Tracker needs a few permissions to work.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(32.dp))

        PermissionRow(
            label = "Usage Access",
            description = "Required to detect which app is in the foreground.",
            granted = permissions.usageAccess,
            required = true,
            onGrant = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        )
        Spacer(Modifier.height(16.dp))

        PermissionRow(
            label = "Display Over Other Apps",
            description = "Required to show blocking overlays.",
            granted = permissions.overlayPermission,
            required = true,
            onGrant = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )
        Spacer(Modifier.height(16.dp))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(
                label = "Notifications",
                description = "Recommended for the background service notification.",
                granted = permissions.notifications,
                required = false,
                onGrant = {
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            )
            Spacer(Modifier.height(16.dp))
        }

        PermissionRow(
            label = "Battery Optimization Exemption",
            description = "Recommended to prevent the OS from stopping tracking.",
            granted = permissions.batteryOptExempt,
            required = false,
            onGrant = {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )

        Spacer(Modifier.height(32.dp))
        Button(onClick = { viewModel.refresh() }) {
            Text("I've granted permissions — Continue")
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    description: String,
    granted: Boolean,
    required: Boolean,
    onGrant: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label + if (required) " *" else " (recommended)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            if (granted) {
                Text("✓", color = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onGrant) { Text("Grant") }
            }
        }
    }
}
```

- [ ] **Step 4: Build to confirm no errors**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/timelimittracker/TimeLimitApp.kt \
  app/src/main/java/com/timelimittracker/ui/onboarding/
git commit -m "feat: TimeLimitApp, OnboardingScreen, and OnboardingViewModel"
```

---

### Task 9: Home Screen

**Files:**
- Create: `app/src/main/java/com/timelimittracker/ui/home/HomeViewModel.kt`
- Create: `app/src/main/java/com/timelimittracker/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `SettingsRepository.trackedApps`, `AppSettingsDataStore.trackingEnabled`, `SessionRepository.activeSessions`, `TimeLimitApp` (for repos/datastore)
- Produces:
  - `HomeViewModel.trackingEnabled: StateFlow<Boolean>`, `fun setTrackingEnabled(Boolean)`
  - `HomeViewModel.trackedApps: StateFlow<List<TrackedAppUiState>>`, `HomeViewModel.wasInterrupted: StateFlow<Boolean>`
  - `data class TrackedAppUiState(packageName, appName, iconBytes, limitMinutes, sessionStatus)`
  - `HomeScreen(onAddApp: () -> Unit, onEditApp: (String) -> Unit, onOpenTemplates: () -> Unit)`

- [ ] **Step 1: Write `HomeViewModel`**

```kotlin
package com.timelimittracker.ui.home

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timelimittracker.TimeLimitApp
import com.timelimittracker.data.db.entities.SessionStatus
import com.timelimittracker.service.UsageMonitorService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TrackedAppUiState(
    val packageName: String,
    val appName: String,
    val iconBytes: ByteArray?,
    val limitMinutes: Int,
    val sessionStatus: SessionStatus?  // null = no active session
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val appInstance = app as TimeLimitApp

    val trackingEnabled: StateFlow<Boolean> = appInstance.dataStore.trackingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val trackedApps: StateFlow<List<TrackedAppUiState>> =
        combine(
            appInstance.settingsRepo.trackedApps,
            appInstance.sessionRepo.activeSessions
        ) { apps, sessions ->
            val sessionMap = sessions.associateBy { it.packageName }
            apps.map { app ->
                TrackedAppUiState(
                    packageName = app.packageName,
                    appName = app.appName,
                    iconBytes = app.iconBytes,
                    limitMinutes = app.limitMinutes,
                    sessionStatus = sessionMap[app.packageName]?.status
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Shown when service was force-stopped: any active session exists but service isn't running
    // Simplified: detect if sessions are ACTIVE but service last known dead — just show a static flag
    // for now based on a flag written by the service on clean stop.
    val wasInterrupted: StateFlow<Boolean> = MutableStateFlow(false)

    fun setTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appInstance.dataStore.setTrackingEnabled(enabled)
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, UsageMonitorService::class.java)
            if (enabled) {
                androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
            } else {
                ctx.stopService(intent)
            }
        }
    }
}
```

- [ ] **Step 2: Write `HomeScreen`**

```kotlin
package com.timelimittracker.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timelimittracker.data.db.entities.SessionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddApp: () -> Unit,
    onEditApp: (String) -> Unit,
    onOpenTemplates: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val trackingEnabled by viewModel.trackingEnabled.collectAsStateWithLifecycle()
    val trackedApps by viewModel.trackedApps.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Time Limit Tracker") },
                actions = {
                    TextButton(onClick = onOpenTemplates) { Text("Templates") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddApp) {
                Icon(Icons.Default.Add, contentDescription = "Add App")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Global toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Tracking", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (trackingEnabled) "Active" else "Disabled",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = trackingEnabled,
                    onCheckedChange = { viewModel.setTrackingEnabled(it) }
                )
            }
            HorizontalDivider()

            if (trackedApps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No apps tracked yet. Tap + to add one.")
                }
            } else {
                LazyColumn {
                    items(trackedApps, key = { it.packageName }) { app ->
                        TrackedAppRow(app = app, onClick = { onEditApp(app.packageName) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackedAppRow(app: TrackedAppUiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon
        val bitmap = remember(app.iconBytes) {
            app.iconBytes?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = app.appName,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Box(
                Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) { Text(app.appName.first().toString()) }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(app.appName, style = MaterialTheme.typography.bodyLarge)
            Text("Limit: ${app.limitMinutes} min", style = MaterialTheme.typography.bodySmall)
        }

        // Status badge
        val (badgeText, badgeColor) = when (app.sessionStatus) {
            SessionStatus.ACTIVE -> "In Session" to MaterialTheme.colorScheme.primary
            SessionStatus.LIMIT_REACHED -> "Limit Reached" to MaterialTheme.colorScheme.error
            SessionStatus.PAUSED -> "Paused" to MaterialTheme.colorScheme.secondary
            null, SessionStatus.ENDED -> "Idle" to MaterialTheme.colorScheme.outline
        }
        Text(badgeText, color = badgeColor, style = MaterialTheme.typography.labelMedium)
    }
}
```

- [ ] **Step 3: Build**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/timelimittracker/ui/home/
git commit -m "feat: HomeScreen and HomeViewModel with live session status"
```

---

### Task 10: Add/Edit App Screen

**Files:**
- Create: `app/src/main/java/com/timelimittracker/ui/addapp/AddAppViewModel.kt`
- Create: `app/src/main/java/com/timelimittracker/ui/addapp/AddAppScreen.kt`

**Interfaces:**
- Produces:
  - `AddAppViewModel.installedApps: StateFlow<List<InstalledAppInfo>>`, `fun setSearch(String)`, `fun saveApp(packageName, limitMinutes)`, `fun removeApp(packageName)`
  - `data class InstalledAppInfo(packageName, appName, iconBytes, isTracked, currentLimitMinutes?)`
  - `AddAppScreen(onBack: () -> Unit)`

- [ ] **Step 1: Write `AddAppViewModel`**

```kotlin
package com.timelimittracker.ui.addapp

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timelimittracker.TimeLimitApp
import com.timelimittracker.data.db.entities.TrackedAppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val iconBytes: ByteArray?,
    val isTracked: Boolean,
    val currentLimitMinutes: Int?
)

class AddAppViewModel(app: Application) : AndroidViewModel(app) {
    private val appInstance = app as TimeLimitApp
    private val _search = MutableStateFlow("")
    private val _allInstalled = MutableStateFlow<List<InstalledAppInfo>>(emptyList())

    val filteredApps: StateFlow<List<InstalledAppInfo>> =
        combine(_search, _allInstalled, appInstance.settingsRepo.trackedApps) { query, installed, tracked ->
            val trackedMap = tracked.associateBy { it.packageName }
            val withTrackedStatus = installed.map { info ->
                val trackedEntry = trackedMap[info.packageName]
                info.copy(
                    isTracked = trackedEntry != null,
                    currentLimitMinutes = trackedEntry?.limitMinutes
                )
            }
            if (query.isBlank()) withTrackedStatus
            else withTrackedStatus.filter {
                it.appName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { loadInstalledApps() }
    }

    fun setSearch(query: String) { _search.value = query }

    fun saveApp(packageName: String, limitMinutes: Int) {
        viewModelScope.launch {
            val info = _allInstalled.value.find { it.packageName == packageName } ?: return@launch
            val entity = TrackedAppEntity(
                packageName = packageName,
                appName = info.appName,
                iconBytes = info.iconBytes,
                limitMinutes = limitMinutes,
                isTracked = true
            )
            appInstance.settingsRepo.upsertTrackedApp(entity)
        }
    }

    fun removeApp(packageName: String) {
        viewModelScope.launch {
            appInstance.settingsRepo.deleteTrackedApp(packageName)
        }
    }

    private suspend fun loadInstalledApps() = withContext(Dispatchers.IO) {
        val pm = getApplication<Application>().packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                InstalledAppInfo(
                    packageName = info.packageName,
                    appName = pm.getApplicationLabel(info).toString(),
                    iconBytes = try { iconToBytes(pm, info.packageName) } catch (e: Exception) { null },
                    isTracked = false,
                    currentLimitMinutes = null
                )
            }
            .sortedBy { it.appName }
        _allInstalled.value = apps
    }

    private fun iconToBytes(pm: PackageManager, packageName: String): ByteArray {
        val drawable = pm.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 80, it) }.toByteArray()
    }
}
```

- [ ] **Step 2: Write `AddAppScreen`**

```kotlin
package com.timelimittracker.ui.addapp

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAppScreen(
    onBack: () -> Unit,
    viewModel: AddAppViewModel = viewModel()
) {
    val apps by viewModel.filteredApps.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var limitInput by remember { mutableStateOf("30") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add App") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.setSearch(it)
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search apps...") },
                singleLine = true
            )

            LazyColumn {
                items(apps, key = { it.packageName }) { app ->
                    AppListRow(
                        app = app,
                        onClick = {
                            selectedApp = app
                            limitInput = app.currentLimitMinutes?.toString() ?: "30"
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Edit/add dialog
    selectedApp?.let { app ->
        AlertDialog(
            onDismissRequest = { selectedApp = null },
            title = { Text(app.appName) },
            text = {
                Column {
                    Text(if (app.isTracked) "Edit time limit:" else "Set time limit (minutes):")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it.filter { c -> c.isDigit() } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        suffix = { Text("min") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val limit = limitInput.toIntOrNull()?.coerceAtLeast(1) ?: 30
                    viewModel.saveApp(app.packageName, limit)
                    selectedApp = null
                }) { Text("Save") }
            },
            dismissButton = {
                if (app.isTracked) {
                    TextButton(onClick = {
                        viewModel.removeApp(app.packageName)
                        selectedApp = null
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { selectedApp = null }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun AppListRow(app: InstalledAppInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = remember(app.iconBytes) {
            app.iconBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = app.appName, modifier = Modifier.size(40.dp))
        } else {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text(app.appName.first().toString())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.appName, style = MaterialTheme.typography.bodyLarge)
            if (app.isTracked) {
                Text("Tracked — ${app.currentLimitMinutes} min limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        if (app.isTracked) {
            Icon(Icons.Default.Check, contentDescription = "Tracked",
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}
```

- [ ] **Step 3: Build**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/timelimittracker/ui/addapp/
git commit -m "feat: AddAppScreen and AddAppViewModel with app picker and limit editor"
```

---

### Task 11: Message Templates Screen

**Files:**
- Create: `app/src/main/java/com/timelimittracker/ui/templates/TemplatesViewModel.kt`
- Create: `app/src/main/java/com/timelimittracker/ui/templates/TemplatesScreen.kt`

**Interfaces:**
- Produces:
  - `TemplatesViewModel.templates: StateFlow<GlobalTemplatesEntity>`, `fun updateMain(String)`, `fun updateReminder(index: Int, String)`, `fun save()`, `val isDirty: StateFlow<Boolean>`
  - `TemplatesScreen(onBack: () -> Unit)`

- [ ] **Step 1: Write `TemplatesViewModel`**

```kotlin
package com.timelimittracker.ui.templates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timelimittracker.TimeLimitApp
import com.timelimittracker.data.db.entities.GlobalTemplatesEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TemplatesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as TimeLimitApp).settingsRepo
    private val _drafts = MutableStateFlow(GlobalTemplatesEntity())
    private val _saved = MutableStateFlow(GlobalTemplatesEntity())

    val drafts: StateFlow<GlobalTemplatesEntity> = _drafts.asStateFlow()
    val isDirty: StateFlow<Boolean> = combine(_drafts, _saved) { d, s -> d != s }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            val existing = repo.getTemplates()
            _drafts.value = existing
            _saved.value = existing
        }
    }

    fun updateMain(text: String) { _drafts.value = _drafts.value.copy(mainMessage = text) }
    fun updateReminder1(text: String) { _drafts.value = _drafts.value.copy(reminder1 = text.ifBlank { null }) }
    fun updateReminder2(text: String) { _drafts.value = _drafts.value.copy(reminder2 = text.ifBlank { null }) }
    fun updateReminder3(text: String) { _drafts.value = _drafts.value.copy(reminder3 = text.ifBlank { null }) }

    fun save() {
        viewModelScope.launch {
            repo.upsertTemplates(_drafts.value)
            _saved.value = _drafts.value
        }
    }
}
```

- [ ] **Step 2: Write `TemplatesScreen`**

```kotlin
package com.timelimittracker.ui.templates

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    viewModel: TemplatesViewModel = viewModel()
) {
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message Templates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = isDirty
                    ) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Available tokens: {appName}  {limit}  {elapsed}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )

            TemplateField(
                label = "Main Message",
                value = drafts.mainMessage,
                placeholder = "You've been on {appName} for {limit} min.",
                onValueChange = { viewModel.updateMain(it) }
            )

            TemplateField(
                label = "Reminder 1",
                value = drafts.reminder1 ?: "",
                placeholder = "Still on {appName}. Take a break.",
                onValueChange = { viewModel.updateReminder1(it) }
            )

            TemplateField(
                label = "Reminder 2",
                value = drafts.reminder2 ?: "",
                placeholder = "Another 5 minutes on {appName}...",
                onValueChange = { viewModel.updateReminder2(it) }
            )

            TemplateField(
                label = "Reminder 3",
                value = drafts.reminder3 ?: "",
                placeholder = "You've now been on {appName} for {elapsed} min.",
                onValueChange = { viewModel.updateReminder3(it) }
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Changes only apply to sessions that start after saving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun TemplateField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.outline) },
        minLines = 2
    )
}
```

- [ ] **Step 3: Build**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/timelimittracker/ui/templates/
git commit -m "feat: TemplatesScreen and TemplatesViewModel for global message templates"
```

---

### Task 12: Navigation + MainActivity — Wire Everything Together

**Files:**
- Create: `app/src/main/java/com/timelimittracker/ui/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/timelimittracker/MainActivity.kt`

**Interfaces:**
- Consumes: all screens from Tasks 8–11, `OnboardingViewModel`, `TimeLimitTrackerTheme`
- Produces: runnable app with full navigation; onboarding gate; service start on launch.

- [ ] **Step 1: Write `NavGraph`**

```kotlin
package com.timelimittracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.timelimittracker.ui.addapp.AddAppScreen
import com.timelimittracker.ui.home.HomeScreen
import com.timelimittracker.ui.onboarding.OnboardingScreen
import com.timelimittracker.ui.templates.TemplatesScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ADD_APP = "add_app"
    const val TEMPLATES = "templates"
}

@Composable
fun AppNavGraph(startDestination: String) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onAllRequiredGranted = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onAddApp = { navController.navigate(Routes.ADD_APP) },
                onEditApp = { navController.navigate(Routes.ADD_APP) },
                onOpenTemplates = { navController.navigate(Routes.TEMPLATES) }
            )
        }
        composable(Routes.ADD_APP) {
            AddAppScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TEMPLATES) {
            TemplatesScreen(onBack = { navController.popBackStack() })
        }
    }
}
```

- [ ] **Step 2: Write `MainActivity`**

```kotlin
package com.timelimittracker

import android.app.AppOpsManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.timelimittracker.service.UsageMonitorService
import com.timelimittracker.ui.navigation.AppNavGraph
import com.timelimittracker.ui.navigation.Routes
import com.timelimittracker.ui.theme.TimeLimitTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startDest = if (permissionsGranted()) {
            startMonitorService()
            Routes.HOME
        } else {
            Routes.ONBOARDING
        }

        setContent {
            TimeLimitTrackerTheme {
                AppNavGraph(startDestination = startDest)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionsGranted()) startMonitorService()
    }

    private fun permissionsGranted(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val usageGranted = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
        return usageGranted && Settings.canDrawOverlays(this)
    }

    private fun startMonitorService() {
        val intent = Intent(this, UsageMonitorService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }
}
```

- [ ] **Step 3: Build and run on device/emulator**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: App launches, shows onboarding if permissions missing, shows home screen otherwise.

- [ ] **Step 4: End-to-end acceptance test (manual)**

  Work through the spec's acceptance criteria:
  - [ ] Add a tracked app with a 1-minute limit
  - [ ] Open that app — confirm session badge changes to "In Session"
  - [ ] Wait 60 seconds — confirm blocking overlay appears
  - [ ] Dismiss — confirm overlay closes; app is usable
  - [ ] Remain in app 5 min — confirm reminder overlay appears
  - [ ] Switch away for 5+ minutes — confirm badge returns to "Idle"
  - [ ] Re-open app — new session starts from 0
  - [ ] Change limit mid-session — confirm it doesn't affect running session
  - [ ] Toggle tracking OFF — confirm no overlay when limit is hit
  - [ ] Toggle tracking ON — confirm tracking resumes immediately for foreground app

- [ ] **Step 5: Final commit**

```bash
git add app/src/main/java/com/timelimittracker/ui/navigation/ \
  app/src/main/java/com/timelimittracker/MainActivity.kt
git commit -m "feat: NavGraph and MainActivity — full app wired up"
```

---

## Self-Review Against Spec

| Spec Requirement | Covered By |
|-----------------|-----------|
| FR1 — Browse/select/remove tracked apps | Tasks 10, 9 |
| FR2 — Per-app time limit, default 30 min | Task 10 |
| FR3 — Global templates with token substitution | Tasks 4, 11 |
| FR4 — Full-screen blocking overlay on limit reached | Tasks 6, 7 |
| FR5 — 5-min cycling reminder, forever | Tasks 4, 5, 7 |
| FR6 — Session reset after 5-min disuse | Task 4 (checkExpiry) |
| FR7 — Global toggle, persists across reboots | Tasks 3, 9 |
| Snapshot rule | Task 4 (startSession snapshots), Task 7 |
| Permissions onboarding | Task 8 |
| Reboot — discard sessions | Tasks 5 (BootReceiver), 7 (handleBoot) |
| Re-activation immediately | Task 7 (next poll tick) |
| Uninstalled app edge case | Task 7 (PackageManager check in poll) |
| Overlay permission revoked | Task 6 (BadTokenException caught) |
| Force-stop banner | HomeViewModel.wasInterrupted (stub — extend post-MVP) |
| Unknown tokens left as-is | Task 4 (TokenSubstitutor) |
| Service START_STICKY | Task 7 |
| Room state survives process death | Task 7 (service reads Room on onCreate) |
