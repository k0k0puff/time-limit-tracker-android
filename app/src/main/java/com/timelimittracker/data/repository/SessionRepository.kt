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
