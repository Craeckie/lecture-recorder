package org.Craeckie.lecturerecorder

import org.junit.Assert.assertEquals
import org.junit.Test

// The rule that keeps a mid-lecture USB hot-plug from costing 6 seconds of audio.
// See docs/superpowers/specs/2026-09-02-usb-hotplug-and-network-switch-findings.md.
class MicRoutingGateTest {
    @Test
    fun `selects a usb device when nothing is being recorded`() {
        assertEquals(
            MicRoutingGate.Action.SELECT,
            MicRoutingGate.next(usbAvailable = true, captureActive = false),
        )
    }

    @Test
    fun `defers the selection while capture is live`() {
        assertEquals(
            MicRoutingGate.Action.DEFER,
            MicRoutingGate.next(usbAvailable = true, captureActive = true),
        )
    }

    @Test
    fun `clears when no usb device is available, whether or not capture is live`() {
        // Not deferred: the device is physically gone and the platform has already fallen
        // back. Both unplugs measured on 2026-09-02 cost nothing, unlike the plug-in.
        assertEquals(
            MicRoutingGate.Action.CLEAR,
            MicRoutingGate.next(usbAvailable = false, captureActive = false),
        )
        assertEquals(
            MicRoutingGate.Action.CLEAR,
            MicRoutingGate.next(usbAvailable = false, captureActive = true),
        )
    }

    @Test
    fun `capture being live never turns a clear into a select`() {
        // The whole point of the gate: while capture is live, no call that can move the
        // OUTPUT route to a new device may happen. Only CLEAR and DEFER are reachable.
        for (usbAvailable in listOf(true, false)) {
            val action = MicRoutingGate.next(usbAvailable = usbAvailable, captureActive = true)
            assertEquals(false, action == MicRoutingGate.Action.SELECT)
        }
    }
}
