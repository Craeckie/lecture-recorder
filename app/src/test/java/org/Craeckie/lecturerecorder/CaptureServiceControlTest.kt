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

    @Test
    fun `after a start that failed to actually start, the next inactive signal is a no-op, not a stop`() {
        // CaptureForegroundService.start() can fail (ForegroundServiceStartNotAllowedException
        // on API 31+, or IllegalStateException) and reports that through its Boolean return
        // rather than throwing, so a transient platform refusal degrades capture instead of
        // crashing the activity mid-lecture. MainActivity.onCaptureActiveChanged is required
        // to feed that real outcome -- not an assumed `true` -- into `running` on the next
        // call. Simulating that here: a START was requested, but it did not actually start
        // (running stays false), so the following capture=false signal must be NONE, not
        // STOP -- there is nothing running to stop.
        val requestedStart = CaptureServiceControl.next(captureActive = true, running = false)
        assertEquals(CaptureServiceControl.Action.START, requestedStart)

        val serviceActuallyRunning = false // what CaptureForegroundService.start() would
        // return on failure, and what onCaptureActiveChanged would then store.

        assertEquals(
            CaptureServiceControl.Action.NONE,
            CaptureServiceControl.next(captureActive = false, running = serviceActuallyRunning),
        )
    }
}
