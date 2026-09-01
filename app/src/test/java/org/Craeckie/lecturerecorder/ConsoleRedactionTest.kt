package org.Craeckie.lecturerecorder

import org.junit.Assert.assertEquals
import org.junit.Test

// The forwarded page console is the one log path that was NOT redacted: capture A
// (docs/superpowers/specs/2026-09-01-device-log-findings.md) carried the session id in the
// clear 52 times, once per console line, in msg.sourceId(). These cases are taken from
// that capture.
//
// redactSessionIds handles the free-form msg.message() half (id-segment redaction only,
// no query/fragment stripping -- see its comment for why). redactSourceUrl handles the
// msg.sourceId() half, which is a genuine URL, so it strips query/fragment first.
class ConsoleRedactionTest {
    @Test
    fun `redacts the session id out of a page source url`() {
        assertEquals(
            "https://lt2srv.iar.kit.edu/present/<id>",
            redactSessionIds(
                "https://lt2srv.iar.kit.edu/present/" +
                    "123456789012345678901234567890123456789",
            ),
        )
    }

    @Test
    fun `redacts an id that is an interior path segment`() {
        assertEquals(
            "https://lt2srv.iar.kit.edu/webapi/<id>/0/append",
            redactSessionIds(
                "https://lt2srv.iar.kit.edu/webapi/" +
                    "123456789012345678901234567890123456789/0/append",
            ),
        )
    }

    @Test
    fun `redacts a lowercase and an uppercase hex id`() {
        assertEquals("/s/<id>", redactSessionIds("/s/0123456789abcdef01234567"))
        assertEquals("/s/<id>", redactSessionIds("/s/0123456789ABCDEF01234567"))
    }

    @Test
    fun `leaves short and non-hex segments alone`() {
        // The QR short links are the ones that must survive: they are how a capture is
        // identified in a log at all, and they are not ids.
        assertEquals(
            "https://lt2srv.iar.kit.edu/webapi/shorten/ExampleTalk",
            redactSessionIds("https://lt2srv.iar.kit.edu/webapi/shorten/ExampleTalk"),
        )
        assertEquals("/present/12345", redactSessionIds("/present/12345"))
    }

    @Test
    fun `redacts an id inside a longer console message, not just a bare url`() {
        // The message body goes through the same call: the site console.logs URLs too.
        assertEquals(
            "GOT MESSAGE for /webapi/<id>/0/append",
            redactSessionIds(
                "GOT MESSAGE for /webapi/" +
                    "123456789012345678901234567890123456789/0/append",
            ),
        )
    }

    @Test
    fun `handles null and non-url sources without throwing`() {
        assertEquals("", redactSessionIds(null))
        assertEquals("about:blank", redactSessionIds("about:blank"))
        assertEquals("", redactSessionIds(""))
    }

    @Test
    fun `is a no-op on lines the JS side already redacted`() {
        // SITE_TWEAKS_JS / NET_TRACE_JS / PERF_TRACE_JS redact their own paths, so this
        // second pass must not corrupt them.
        val already = "[app-tweaks] sync-xhr HIT  POST /webapi/<id>/getgraph"
        assertEquals(already, redactSessionIds(already))
    }

    @Test
    fun `a mid-string question mark in a free-form message is NOT truncated`() {
        // Regression guard: redactSessionIds must not strip a query string, because
        // msg.message() is free-form site console.log text, not a URL. An earlier version
        // truncated everything from a mid-string '?' onward with no marker.
        assertEquals(
            "processed 5 items, query was ?x=1, continuing",
            redactSessionIds("processed 5 items, query was ?x=1, continuing"),
        )
    }

    @Test
    fun `a mid-string hash in a free-form message is NOT truncated`() {
        assertEquals(
            "color is #ffffff, still going",
            redactSessionIds("color is #ffffff, still going"),
        )
    }

    @Test
    fun `a free-form message with both a mid-string question mark and an embedded id redacts only the id`() {
        assertEquals(
            "loaded /webapi/<id>/getgraph?debug=1 successfully",
            redactSessionIds(
                "loaded /webapi/123456789012345678901234567890123456789/getgraph" +
                    "?debug=1 successfully",
            ),
        )
    }

    @Test
    fun `redactSourceUrl strips a query string and a fragment`() {
        assertEquals("https://x/y", redactSourceUrl("https://x/y?token=abc"))
        assertEquals("https://x/y", redactSourceUrl("https://x/y#frag"))
    }

    @Test
    fun `redactSourceUrl still redacts the session id`() {
        assertEquals(
            "https://lt2srv.iar.kit.edu/present/<id>",
            redactSourceUrl(
                "https://lt2srv.iar.kit.edu/present/" +
                    "123456789012345678901234567890123456789?x=1",
            ),
        )
    }

    @Test
    fun `redactSourceUrl handles null without throwing`() {
        assertEquals("", redactSourceUrl(null))
    }
}
