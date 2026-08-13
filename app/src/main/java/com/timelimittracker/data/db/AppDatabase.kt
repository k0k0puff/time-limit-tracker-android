package com.timelimittracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.timelimittracker.data.db.dao.GlobalTemplatesDao
import com.timelimittracker.data.db.dao.SessionDao
import com.timelimittracker.data.db.dao.TrackedAppDao
import com.timelimittracker.data.db.entities.GlobalTemplatesEntity
import com.timelimittracker.data.db.entities.SessionEntity
import com.timelimittracker.data.db.entities.SessionStatus
import com.timelimittracker.data.db.entities.TrackedAppEntity

class Converters {
    @TypeConverter
    fun fromSessionStatus(status: SessionStatus): String = status.name

    @TypeConverter
    fun toSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)
}

@Database(
    entities = [TrackedAppEntity::class, GlobalTemplatesEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackedAppDao(): TrackedAppDao
    abstract fun globalTemplatesDao(): GlobalTemplatesDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "time_limit_tracker.db"
                ).build().also { INSTANCE = it }
            }
    }
}
