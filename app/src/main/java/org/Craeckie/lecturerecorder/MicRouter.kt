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

// The pure selection rule. Free of any Android runtime behavior — it only references
// compile-time int constants from AudioDeviceInfo, which is why it can be unit-tested on a
// bare JVM.
//
// These type constants describe devices from AudioManager.availableCommunicationDevices,
// which is a SINK-role (playback) device list, not literally a list of microphones — the
// platform maps a selected sink to its matching source automatically. That means a USB
// headset or audio interface (which has a playback path) can appear here and route
// correctly, but a plain input-only USB microphone with no playback endpoint (a lav mic, a
// podcast mic — exactly the hardware this app is named for) can NEVER appear in this list.
// There is no workaround for that case: it is the same category of limitation as
// AudioRecord.setPreferredDevice being unreachable from this shell — see the spec's "Known
// limitation" section.
object MicRouting {
    val USB_COMMUNICATION_TYPES: Set<Int> = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    fun isUsbCommunicationType(type: Int): Boolean = type in USB_COMMUNICATION_TYPES

    // Index of the first USB communication device in the given device-type list, or null if
    // there is none. First-wins rather than a priority order: the platform lists devices in
    // connection order, and with two USB mics attached there is no principled way to prefer
    // one.
    fun pickPreferredDeviceIndex(deviceTypes: List<Int>): Int? =
        deviceTypes.indexOfFirst { it in USB_COMMUNICATION_TYPES }.takeIf { it >= 0 }
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
        val callback = newDeviceCallback()
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

    // Whether capture routed to the communication path would currently land on a USB
    // device. Reads the platform's CURRENT communication device rather than what
    // applyRouting() last decided, so it stays truthful if the system, a phone call or
    // another app changed the route behind us. Safe to call from any thread.
    //
    // This is the single bit MicBridge hands to the page, and it is why the page's
    // capture-mode decision is made at getUserMedia time rather than at page load.
    fun usbCommunicationDeviceSelected(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.communicationDevice ?: return false
        return MicRouting.isUsbCommunicationType(device.type)
    }

    // Only ever constructed from inside the SDK_INT >= S branch of attach(), but its
    // overrides call applyRouting() (itself @RequiresApi(S)), which static analysis can't
    // see across that branch — so the helper that builds it is annotated directly.
    @RequiresApi(Build.VERSION_CODES.S)
    private fun newDeviceCallback(): AudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            applyRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            applyRouting()
        }
    }

    // Selects the USB input among the devices the platform reports as usable for
    // communication capture. availableCommunicationDevices — not getDevices() — because
    // setCommunicationDevice only accepts devices from that list.
    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyRouting() {
        val devices = audioManager.availableCommunicationDevices
        val index = MicRouting.pickPreferredDeviceIndex(devices.map { it.type })
        if (index == null) {
            if (audioManager.communicationDevice != null) {
                audioManager.clearCommunicationDevice()
            }
            Log.i(
                LOG_TAG,
                "No USB communication device among ${devices.size} communication devices " +
                    "(${devices.joinToString { MicDiagnostics.describeDeviceType(it.type) }}); using system default",
            )
            return
        }
        val device = devices[index]
        val applied = try {
            audioManager.setCommunicationDevice(device)
        } catch (e: IllegalArgumentException) {
            // The device was listed a moment ago but is gone now — unplugged mid-call.
            // The AudioDeviceCallback will fire again; nothing to do but say so.
            Log.w(LOG_TAG, "setCommunicationDevice rejected ${device.productName}", e)
            false
        }
        if (applied) {
            Log.i(LOG_TAG, "USB communication device selected: ${MicDiagnostics.describe(device)}")
        } else {
            Log.w(LOG_TAG, "FAILED to select USB communication device ${device.productName} — capture will use the system default")
        }
    }
}
