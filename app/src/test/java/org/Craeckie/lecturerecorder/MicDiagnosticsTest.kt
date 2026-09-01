package org.Craeckie.lecturerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicDiagnosticsTest {
    @Test
    fun `names the device types that matter for this app`() {
        assertEquals("USB_DEVICE", MicDiagnostics.describeDeviceType(11))
        assertEquals("USB_ACCESSORY", MicDiagnostics.describeDeviceType(12))
        assertEquals("USB_HEADSET", MicDiagnostics.describeDeviceType(22))
        assertEquals("BUILTIN_MIC", MicDiagnostics.describeDeviceType(15))
        assertEquals("WIRED_HEADSET", MicDiagnostics.describeDeviceType(3))
        assertEquals("BLUETOOTH_SCO", MicDiagnostics.describeDeviceType(7))
    }

    @Test
    fun `keeps the raw value for an unmapped device type`() {
        assertEquals("TYPE_99", MicDiagnostics.describeDeviceType(99))
    }

    @Test
    fun `names the audio sources that distinguish routed from unrouted capture`() {
        assertEquals("VOICE_COMMUNICATION", MicDiagnostics.describeAudioSource(7))
        assertEquals("MIC", MicDiagnostics.describeAudioSource(1))
        assertEquals("DEFAULT", MicDiagnostics.describeAudioSource(0))
        assertEquals("VOICE_RECOGNITION", MicDiagnostics.describeAudioSource(6))
    }

    @Test
    fun `keeps the raw value for an unmapped audio source`() {
        assertEquals("SOURCE_42", MicDiagnostics.describeAudioSource(42))
    }

    @Test
    fun `capture counts as silenced when any active config is silenced`() {
        // AudioRecordingConfiguration is a list: the page can hold more than one capture
        // at a time, and losing audio on any of them is the condition worth reporting.
        assertTrue(CaptureSilence.isSilenced(listOf(true)))
        assertTrue(CaptureSilence.isSilenced(listOf(false, true)))
    }

    @Test
    fun `capture is not silenced when every active config is live`() {
        assertFalse(CaptureSilence.isSilenced(listOf(false)))
        assertFalse(CaptureSilence.isSilenced(listOf(false, false)))
    }

    @Test
    fun `no active capture is not silenced capture`() {
        // The end of a recording must clear the banner, not raise it.
        assertFalse(CaptureSilence.isSilenced(emptyList()))
    }
}
