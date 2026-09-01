package org.Craeckie.lecturerecorder

import org.junit.Assert.assertEquals
import org.junit.Test

// The start/stop decision, kept apart from the Android glue for the same reason MicRouting
// is kept apart from MicRouter: it is the part with actual rules in it, and it is the part
// a bare JVM can test.
class CaptureServiceControlTest {
    @Test
    fun `capture starting while the service is down starts it`() {
        assertEquals(
            CaptureServiceControl.Action.START,
            CaptureServiceControl.next(captureActive = true, running = false),
        )
    }

    @Test
    fun `capture stopping while the service is up stops it`() {
        assertEquals(
            CaptureServiceControl.Action.STOP,
            CaptureServiceControl.next(captureActive = false, running = true),
        )
    }

    @Test
    fun `a repeated capture-active signal does not restart a running service`() {
        // MicDiagnostics only fires on transitions today, but a duplicate must be
        // harmless: startForegroundService twice would re-post the notification.
        assertEquals(
            CaptureServiceControl.Action.NONE,
            CaptureServiceControl.next(captureActive = true, running = true),
        )
    }

    @Test
    fun `a repeated capture-inactive signal does not stop a stopped service`() {
        assertEquals(
            CaptureServiceControl.Action.NONE,
            CaptureServiceControl.next(captureActive = false, running = false),
        )
    }
}
