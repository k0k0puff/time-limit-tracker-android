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
