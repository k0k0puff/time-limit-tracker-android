package com.timelimittracker.data.db.dao

import androidx.room.*
import com.timelimittracker.data.db.entities.GlobalTemplatesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GlobalTemplatesDao {
    @Upsert
    suspend fun upsert(templates: GlobalTemplatesEntity)

    @Query("SELECT * FROM global_templates WHERE id = 1")
    fun observe(): Flow<GlobalTemplatesEntity?>

    @Query("SELECT * FROM global_templates WHERE id = 1")
    suspend fun get(): GlobalTemplatesEntity?
}
