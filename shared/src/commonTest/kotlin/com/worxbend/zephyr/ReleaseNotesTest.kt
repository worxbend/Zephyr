package com.worxbend.zephyr

import com.worxbend.zephyr.data.isValidHttpsUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseNotesTest {
    @Test
    fun knownUpdateTargetsResolveOnlyToHttpsUrls() {
        val urls =
            listOf(
                releaseNotesUrl("gradle", "8.14"),
                releaseNotesUrl("java", "21.0.7-tem"),
                releaseNotesUrl("kotlin", "2.2.0"),
            )
        assertTrue(urls.all { it != null && isValidHttpsUrl(it) })
        assertEquals("https://docs.gradle.org/8.14/release-notes.html", urls.first())
    }

    @Test
    fun validatorRejectsUnsafeOrAmbiguousUrls() {
        assertFalse(isValidHttpsUrl("http://example.com/releases"))
        assertFalse(isValidHttpsUrl("https://user@example.com/releases"))
        assertFalse(isValidHttpsUrl("https://example.com/\ncommand"))
        assertNull(releaseNotesUrl("gradle", "bad/version"))
        assertNull(releaseNotesUrl("unknown", "1.0"))
    }
}
