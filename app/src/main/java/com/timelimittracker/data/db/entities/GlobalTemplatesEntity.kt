package com.timelimittracker.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "global_templates")
data class GlobalTemplatesEntity(
    @PrimaryKey val id: Int = 1,
    val mainMessage: String = "You've been on {appName} for {limit} min.",
    val reminder1: String? = "You're done. Stop",
    val reminder2: String? = "That's enough",
    val reminder3: String? = "Stop Now"
)
