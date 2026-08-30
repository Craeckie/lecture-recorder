package org.Craeckie.lecturerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureModesTest {
    @Test
    fun `no usb device means raw capture`() {
        assertEquals(CaptureModes.RAW, CaptureModes.resolve(forced = null, usbSelected = false))
    }

    @Test
    fun `a usb device means the routed mode`() {
        // hybrid, not voice: the 2026-08-30 device probe showed it reaches the USB mic
        // without the voice DSP. See the spec's "Measured ... KM_B2 attached" table.
        assertEquals(CaptureModes.HYBRID, CaptureModes.resolve(forced = null, usbSelected = true))
    }

    @Test
    fun `a forced mode overrides both directions`() {
        assertEquals(CaptureModes.RAW, CaptureModes.resolve(forced = "raw", usbSelected = true))
        assertEquals(CaptureModes.HYBRID, CaptureModes.resolve(forced = "hybrid", usbSelected = false))
        assertEquals(CaptureModes.VOICE, CaptureModes.resolve(forced = "voice", usbSelected = false))
    }

    @Test
    fun `an unrecognised forced mode falls back to the automatic rule`() {
        // A typo on the adb command line must not silently disable capture tuning.
        assertEquals(CaptureModes.RAW, CaptureModes.resolve(forced = "vioce", usbSelected = false))
        assertEquals(CaptureModes.HYBRID, CaptureModes.resolve(forced = "", usbSelected = true))
    }

    @Test
    fun `the mode strings are exactly what the injected JS accepts`() {
        // SITE_TWEAKS_JS's shellCaptureMode() hard-codes these three literals, because the
        // bridge value crosses a language boundary and cannot share a constant. If this
        // set changes, that function changes with it.
        assertEquals(setOf("raw", "voice", "hybrid"), CaptureModes.ALL)
    }

    @Test
    fun `sanitize keeps a known mode and rejects everything else`() {
        // The in-app selector and the intent extra both write through sanitize, so a
        // stale or hand-edited preference value can never reach resolve().
        assertEquals(CaptureModes.RAW, CaptureModes.sanitize("raw"))
        assertEquals(CaptureModes.VOICE, CaptureModes.sanitize("voice"))
        assertEquals(CaptureModes.HYBRID, CaptureModes.sanitize("hybrid"))
        assertNull(CaptureModes.sanitize(null))
        assertNull(CaptureModes.sanitize(""))
        assertNull(CaptureModes.sanitize("Raw"))
        assertNull(CaptureModes.sanitize("vioce"))
    }
}
