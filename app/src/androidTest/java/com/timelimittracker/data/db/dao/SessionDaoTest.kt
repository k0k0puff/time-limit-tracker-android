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
