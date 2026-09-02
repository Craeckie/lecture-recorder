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
        deviceTypes.indexOfFirst { isUsbCommunicationType(it) }.takeIf { it >= 0 }
}

// Whether a routing change may be applied right now, given whether a USB communication
// device is available and whether the page is currently recording. Split out of MicRouter
// for the same reason MicRouting is: this is the part with a rule in it, and the only part
// a bare JVM can test.
//
// Selecting a communication device moves the OUTPUT route, and Chromium's WebAudio output
// stream cannot follow that mid-recording. Measured on device 2026-09-02: plugging the
// KM_B2 in during a live lecture timed the AAudio stream out after 1 s, errored the page's
// AudioContext into `suspended`, and cost 6.0 s of audio -- one whole `[perf]` rollup at
// `cb=0`, recovered only because patchAudioContext() resumes on `statechange`.
//
// And it cannot help even when it works: the capture mode and the microphone are both
// fixed at getUserMedia time, so a device that arrives after the record button was pressed
// is unreachable for that session. Across three USB attach/detach events in that same log
// the platform never once reported a recording-configuration change -- capture stayed on
// the built-in mic throughout. So there is nothing to trade off: a deferred SELECT loses
// nothing and is replayed when capture stops, in time for the next getUserMedia.
//
// A CLEAR is NOT deferred. There the device is physically gone, the platform has already
// fallen back on its own, and both unplugs in that log cost nothing measurable.
object MicRoutingGate {
    enum class Action { SELECT, CLEAR, DEFER }

    fun next(usbAvailable: Boolean, captureActive: Boolean): Action = when {
        !usbAvailable -> Action.CLEAR
        captureActive -> Action.DEFER
        else -> Action.SELECT
    }
}

// Routes microphone capture to an attached USB input, so the wrapped page's getUserMedia
// call records from it instead of the built-in mic. See CLAUDE.md and
// docs/superpowers/specs/2026-08-29-usb-mic-routing-design.md for why this has to happen
// at the app level and cannot be done from the page.
class MicRouter(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var deviceCallback: AudioDeviceCallback? = null

    // Mirrored from MicDiagnostics through MainActivity.onCaptureActiveChanged -- the
    // shell's only knowledge that the page is recording. See MicRoutingGate for why the
    // router needs it.
    private var captureActive = false

    // A USB device showed up mid-recording and its selection was skipped. Replayed the
    // moment capture stops, so the next getUserMedia sees the device.
    private var pendingRouting = false

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
        // MainActivity detaches the router before the diagnostics, and MicDiagnostics.detach()
        // reports capture as ended -- which lands back here in setCaptureActive(false). Drop
        // the queue so that call cannot re-select a device on the way out, right after the
        // clearCommunicationDevice() below.
        pendingRouting = false
        audioManager.unregisterAudioDeviceCallback(callback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    // Told to the router by MainActivity, off MicDiagnostics' capture signal -- the same
    // one that drives FLAG_KEEP_SCREEN_ON, the back confirmation and the foreground
    // service, so none of the four can disagree about whether capture is live.
    //
    // Only transitions do anything, and only the falling edge does work: a routing change
    // skipped during the recording is applied here instead, which is early enough for the
    // next getUserMedia and late enough to leave the live AudioContext alone.
    fun setCaptureActive(active: Boolean) {
        if (active == captureActive) return
        captureActive = active
        if (active || !pendingRouting) return
        // Null before attach(), after detach(), and on API < 31 where attach() returns
        // early and there is no routing to apply at all. The explicit SDK_INT check is
        // still there for lint, which cannot see that implication.
        if (deviceCallback == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        pendingRouting = false
        Log.i(LOG_TAG, "Capture stopped; applying the USB routing deferred during the recording")
        applyRouting()
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
        val device = MicRouting.pickPreferredDeviceIndex(devices.map { it.type })?.let { devices[it] }
        when (MicRoutingGate.next(usbAvailable = device != null, captureActive = captureActive)) {
            MicRoutingGate.Action.CLEAR -> {
                // The device is gone, so a queued selection for it is gone with it.
                pendingRouting = false
                if (audioManager.communicationDevice != null) {
                    audioManager.clearCommunicationDevice()
                }
                Log.i(
                    LOG_TAG,
                    "No USB communication device among ${devices.size} communication devices " +
                        "(${devices.joinToString { MicDiagnostics.describeDeviceType(it.type) }}); using system default",
                )
            }
            MicRoutingGate.Action.DEFER -> {
                pendingRouting = true
                Log.i(
                    LOG_TAG,
                    "USB device attached during a live recording; routing deferred until it ends. " +
                        "This recording keeps the microphone it started with — attach the USB " +
                        "device BEFORE pressing record for it to be used",
                )
            }
            MicRoutingGate.Action.SELECT -> {
                pendingRouting = false
                // The gate only returns SELECT when usbAvailable was true, so this is a
                // null check for the compiler's benefit rather than a reachable branch.
                if (device != null) select(device)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun select(device: AudioDeviceInfo) {
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
