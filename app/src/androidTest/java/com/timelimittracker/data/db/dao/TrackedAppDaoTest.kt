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
