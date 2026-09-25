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
        val defaultR1 = "You're done. Stop"
        val defaultR2 = "That's enough"
        val defaultR3 = "Stop Now"
        return when (session.reminderCycleIndex) {
            0 -> session.snapshotMainMessage
            1 -> session.snapshotReminder1 ?: defaultR1
            2 -> session.snapshotReminder2 ?: defaultR2
            3 -> session.snapshotReminder3 ?: defaultR3
            else -> session.snapshotReminder1 ?: defaultR1
        }
    }
}
