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
