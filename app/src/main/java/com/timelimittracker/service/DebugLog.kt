package com.timelimittracker.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private const val MAX_ENTRIES = 500
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "$timestamp [$tag] $message"
        synchronized(this) {
            val current = _entries.value.toMutableList()
            current.add(0, entry) // newest first
            if (current.size > MAX_ENTRIES) {
                _entries.value = current.subList(0, MAX_ENTRIES)
            } else {
                _entries.value = current
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
