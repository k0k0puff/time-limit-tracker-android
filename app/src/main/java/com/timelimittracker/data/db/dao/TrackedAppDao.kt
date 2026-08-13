package com.timelimittracker.data.db.dao

import androidx.room.*
import com.timelimittracker.data.db.entities.TrackedAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedAppDao {
    @Upsert
    suspend fun upsert(app: TrackedAppEntity)

    @Query("SELECT * FROM tracked_apps WHERE isTracked = 1")
    fun getAllTracked(): Flow<List<TrackedAppEntity>>

    @Query("SELECT * FROM tracked_apps")
    fun getAll(): Flow<List<TrackedAppEntity>>

    @Query("SELECT * FROM tracked_apps WHERE packageName = :packageName")
    suspend fun getByPackageName(packageName: String): TrackedAppEntity?

    @Query("DELETE FROM tracked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
