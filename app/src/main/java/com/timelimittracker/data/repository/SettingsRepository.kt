package com.timelimittracker.data.repository

import com.timelimittracker.data.db.dao.GlobalTemplatesDao
import com.timelimittracker.data.db.dao.TrackedAppDao
import com.timelimittracker.data.db.entities.GlobalTemplatesEntity
import com.timelimittracker.data.db.entities.TrackedAppEntity
import kotlinx.coroutines.flow.Flow

class SettingsRepository(
    private val trackedAppDao: TrackedAppDao,
    private val globalTemplatesDao: GlobalTemplatesDao
) {
    val trackedApps: Flow<List<TrackedAppEntity>> = trackedAppDao.getAllTracked()
    val allApps: Flow<List<TrackedAppEntity>> = trackedAppDao.getAll()
    val templates: Flow<GlobalTemplatesEntity?> = globalTemplatesDao.observe()

    suspend fun upsertTrackedApp(app: TrackedAppEntity) = trackedAppDao.upsert(app)
    suspend fun deleteTrackedApp(packageName: String) = trackedAppDao.delete(packageName)
    suspend fun getByPackageName(packageName: String) = trackedAppDao.getByPackageName(packageName)
    suspend fun getTemplates(): GlobalTemplatesEntity =
        globalTemplatesDao.get() ?: GlobalTemplatesEntity()
    suspend fun upsertTemplates(templates: GlobalTemplatesEntity) =
        globalTemplatesDao.upsert(templates)
}
