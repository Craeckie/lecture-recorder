package org.Craeckie.lecturerecorder

import org.junit.Assert.assertEquals
import org.junit.Test

// The forwarded page console is the one log path that was NOT redacted: capture A
// (docs/superpowers/specs/2026-09-01-device-log-findings.md) carried the session id in the
// clear 52 times, once per console line, in msg.sourceId(). These cases are taken from
// that capture.
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
    fun `strips a query string and a fragment`() {
        assertEquals("https://x/y", redactSessionIds("https://x/y?token=abc"))
        assertEquals("https://x/y", redactSessionIds("https://x/y#frag"))
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
}
