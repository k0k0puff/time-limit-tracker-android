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
