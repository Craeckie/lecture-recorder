package org.Craeckie.lecturerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// AudioDeviceInfo type constants, spelled out so this test runs on a bare JVM.
private const val TYPE_BUILTIN_MIC = 15
private const val TYPE_USB_DEVICE = 11
private const val TYPE_USB_ACCESSORY = 12
private const val TYPE_USB_HEADSET = 22
private const val TYPE_BLUETOOTH_SCO = 7

class MicRoutingTest {
    @Test
    fun `returns null when no usb communication device is present`() {
        assertNull(MicRouting.pickPreferredDeviceIndex(listOf(TYPE_BUILTIN_MIC, TYPE_BLUETOOTH_SCO)))
    }

    @Test
    fun `returns null for an empty device list`() {
        assertNull(MicRouting.pickPreferredDeviceIndex(emptyList()))
    }

    @Test
    fun `picks a usb device over the builtin mic`() {
        assertEquals(1, MicRouting.pickPreferredDeviceIndex(listOf(TYPE_BUILTIN_MIC, TYPE_USB_DEVICE)))
    }

    @Test
    fun `recognises usb headset and usb accessory as usb communication devices`() {
        assertEquals(1, MicRouting.pickPreferredDeviceIndex(listOf(TYPE_BUILTIN_MIC, TYPE_USB_HEADSET)))
        assertEquals(1, MicRouting.pickPreferredDeviceIndex(listOf(TYPE_BUILTIN_MIC, TYPE_USB_ACCESSORY)))
    }

    @Test
    fun `picks the first usb communication device when several are attached`() {
        assertEquals(
            1,
            MicRouting.pickPreferredDeviceIndex(
                listOf(TYPE_BUILTIN_MIC, TYPE_USB_DEVICE, TYPE_USB_HEADSET),
            ),
        )
    }

    @Test
    fun `usb communication types are exactly the three usb constants`() {
        assertEquals(setOf(TYPE_USB_DEVICE, TYPE_USB_ACCESSORY, TYPE_USB_HEADSET), MicRouting.USB_COMMUNICATION_TYPES)
    }
}
