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
