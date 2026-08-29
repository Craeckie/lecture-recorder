package org.Craeckie.lecturerecorder

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

private const val LOG_TAG = "LectureRecorder"

// The pure selection rule, kept free of Android types so it can be unit-tested on the JVM.
object MicRouting {
    val USB_INPUT_TYPES: Set<Int> = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    // Index of the first USB input in the given device-type list, or null if there is none.
    // First-wins rather than a priority order: the platform lists devices in connection
    // order, and with two USB mics attached there is no principled way to prefer one.
    fun pickPreferredInputIndex(deviceTypes: List<Int>): Int? =
        deviceTypes.indexOfFirst { it in USB_INPUT_TYPES }.takeIf { it >= 0 }
}

// Routes microphone capture to an attached USB input, so the wrapped page's getUserMedia
// call records from it instead of the built-in mic. See CLAUDE.md and
// docs/superpowers/specs/2026-08-29-usb-mic-routing-design.md for why this has to happen
// at the app level and cannot be done from the page.
class MicRouter(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var deviceCallback: AudioDeviceCallback? = null

    // Call before the WebView is created, so the device is selected before the page can
    // call getUserMedia. Registering the callback immediately reports the currently
    // connected devices, which is what performs the initial routing.
    fun attach() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // setCommunicationDevice is API 31+. There is no fallback: the alternative,
            // AudioRecord.setPreferredDevice, needs the recorder instance, and Chromium
            // owns that one — the shell never sees it.
            Log.i(LOG_TAG, "USB mic routing unavailable on API ${Build.VERSION.SDK_INT} (needs 31+)")
            return
        }
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                applyRouting()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                applyRouting()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        deviceCallback = callback
        applyRouting()
    }

    fun detach() {
        val callback = deviceCallback ?: return
        deviceCallback = null
        audioManager.unregisterAudioDeviceCallback(callback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    // Selects the USB input among the devices the platform reports as usable for
    // communication capture. availableCommunicationDevices — not getDevices() — because
    // setCommunicationDevice only accepts devices from that list.
    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyRouting() {
        val devices = audioManager.availableCommunicationDevices
        val index = MicRouting.pickPreferredInputIndex(devices.map { it.type })
        if (index == null) {
            if (audioManager.communicationDevice != null) {
                audioManager.clearCommunicationDevice()
            }
            Log.i(LOG_TAG, "No USB input among ${devices.size} communication devices; using system default")
            return
        }
        val device = devices[index]
        val applied = audioManager.setCommunicationDevice(device)
        Log.i(
            LOG_TAG,
            "USB input selected: ${device.productName} (type=${device.type}), setCommunicationDevice=$applied",
        )
    }
}
