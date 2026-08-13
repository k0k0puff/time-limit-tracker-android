package com.timelimittracker.service

object TokenSubstitutor {
    fun substitute(template: String, appName: String, limitMinutes: Int, elapsedSeconds: Long): String {
        val elapsedMinutes = (elapsedSeconds / 60).toString()
        return template
            .replace("{appName}", appName)
            .replace("{limit}", limitMinutes.toString())
            .replace("{elapsed}", elapsedMinutes)
    }
}
