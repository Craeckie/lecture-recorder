package org.Craeckie.lecturerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionShortLinksTest {
    @Test
    fun `accepts a full shorten URL`() {
        assertEquals(
            "abc123",
            SessionShortLinks.nameFrom("https://lt2srv.iar.kit.edu/webapi/shorten/abc123"),
        )
    }

    @Test
    fun `accepts a bare name`() {
        assertEquals("abc123", SessionShortLinks.nameFrom("abc123"))
    }

    @Test
    fun `accepts a trailing slash and a query string`() {
        assertEquals(
            "abc123",
            SessionShortLinks.nameFrom("https://lt2srv.iar.kit.edu/webapi/shorten/abc123/"),
        )
        assertEquals(
            "abc123",
            SessionShortLinks.nameFrom("https://lt2srv.iar.kit.edu/webapi/shorten/abc123?x=1"),
        )
    }

    @Test
    fun `rejects a presenter page`() {
        // /present/<id> is deliberately not collected -- see SessionShortLinks' comment.
        assertNull(SessionShortLinks.nameFrom("https://lt2srv.iar.kit.edu/present/abc123"))
    }

    @Test
    fun `rejects other hosts`() {
        assertNull(SessionShortLinks.nameFrom("https://example.com/webapi/shorten/abc123"))
    }

    @Test
    fun `rejects empty input`() {
        assertNull(SessionShortLinks.nameFrom(""))
        assertNull(SessionShortLinks.nameFrom("   "))
    }

    @Test
    fun `rejects a bare name containing a slash`() {
        assertNull(SessionShortLinks.nameFrom("foo/bar"))
    }

    @Test
    fun `remember inserts a new name at the front`() {
        val result = SessionShortLinks.remember(emptyList(), "a", now = 1L)
        assertEquals(listOf(SessionShortLink("a", 1L)), result)
    }

    @Test
    fun `remember moves an existing name to the front and dedups`() {
        val entries = listOf(SessionShortLink("a", 1L), SessionShortLink("b", 2L))
        val result = SessionShortLinks.remember(entries, "a", now = 3L)
        assertEquals(listOf(SessionShortLink("a", 3L), SessionShortLink("b", 2L)), result)
    }

    @Test
    fun `urlFor round-trips through nameFrom`() {
        val url = "https://lt2srv.iar.kit.edu/webapi/shorten/xyz"
        assertEquals(url, SessionShortLinks.urlFor(SessionShortLinks.nameFrom(url)!!))
    }
}
