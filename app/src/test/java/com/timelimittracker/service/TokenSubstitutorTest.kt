package com.timelimittracker.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenSubstitutorTest {
    @Test
    fun substitutesAppName() {
        val result = TokenSubstitutor.substitute("{appName}", "Instagram", 30, 0)
        assertEquals("Instagram", result)
    }

    @Test
    fun substitutesLimit() {
        val result = TokenSubstitutor.substitute("{limit}", "Instagram", 30, 0)
        assertEquals("30", result)
    }

    @Test
    fun substitutesElapsed() {
        val result = TokenSubstitutor.substitute("{elapsed}", "Instagram", 30, 1860)
        assertEquals("31", result) // 1860 seconds = 31 minutes
    }

    @Test
    fun substitutesAllTokens() {
        val result = TokenSubstitutor.substitute(
            "You've been on {appName} for {elapsed} min (limit: {limit})",
            "TikTok", 45, 2700
        )
        assertEquals("You've been on TikTok for 45 min (limit: 45)", result)
    }

    @Test
    fun unknownTokenLeftAsIs() {
        val result = TokenSubstitutor.substitute("{unknown}", "App", 30, 0)
        assertEquals("{unknown}", result)
    }

    @Test
    fun emptyTemplateReturnsEmpty() {
        val result = TokenSubstitutor.substitute("", "App", 30, 0)
        assertEquals("", result)
    }
}
