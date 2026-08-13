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
