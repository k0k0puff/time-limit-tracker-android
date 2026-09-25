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
        .map { prefs -> prefs[TRACKING_ENABLED] ?: true }

    suspend fun setTrackingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[TRACKING_ENABLED] = enabled
        }
    }
}
