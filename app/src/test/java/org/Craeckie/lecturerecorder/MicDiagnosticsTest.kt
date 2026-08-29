package org.Craeckie.lecturerecorder

import org.junit.Assert.assertEquals
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
}
