package org.Craeckie.lecturerecorder

import android.util.Log
import android.webkit.JavascriptInterface

// Which processing constraints the injected getUserMedia patch should request.
//
// On Android, Chromium picks the AudioSource from those constraints, so "which microphone"
// and "which DSP" are the same knob:
//
//   ec:true                     -> VOICE_COMMUNICATION, which setCommunicationDevice
//                                  governs, so MicRouter's USB selection applies
//   ec:false ns:false agc:false -> an unprocessed source; measured on a Pixel 9a this is
//                                  CAMCORDER, pinned to a built-in mic array, which
//                                  ignores setCommunicationDevice entirely
//
// The full evidence and the mode table live in
// docs/superpowers/specs/2026-08-29-usb-mic-routing-design.md.
//
// Pure and free of Android runtime behaviour, so the decision is unit-testable on a bare
// JVM; MicBridge only supplies the two inputs.
object CaptureModes {
    const val RAW = "raw"
    const val VOICE = "voice"
    const val HYBRID = "hybrid"

    val ALL: Set<String> = setOf(RAW, VOICE, HYBRID)

    // The one place an outside string becomes a mode. Anything unrecognised is ignored
    // rather than trusted: a typo on the adb command line, or a stale value left in the
    // preferences by an older build, should fall back to the automatic rule rather than
    // put capture into an undefined state.
    fun sanitize(value: String?): String? = if (value != null && value in ALL) value else null

    // `forced` is the manual override — the in-app selector, or the debug-only `micmode`
    // intent extra that writes through it. Null means "let the routing decide".
    fun resolve(forced: String?, usbSelected: Boolean): String {
        sanitize(forced)?.let { return it }
        return if (usbSelected) VOICE else RAW
    }
}

// The only app state the wrapped page is allowed to read.
//
// addJavascriptInterface injects into EVERY frame of the loaded document, so this exposes
// exactly one method, which takes no arguments and returns one of three fixed strings.
// There is nothing here for a hostile frame to steal or drive.
//
// Deliberately a pull, not a push: the page calls this from inside its getUserMedia
// wrapper, so the answer reflects the routing at the moment recording actually starts
// rather than whatever was true when the page loaded. Called on the WebView's JS bridge
// thread, not the main thread -- which is fine, AudioManager.getCommunicationDevice() has
// no thread affinity.
//
// `forcedMode` is a supplier, not a value, for the same reason: the in-app selector can
// change the override between two recordings without the page being reloaded, and the
// next getUserMedia has to see the new choice.
class MicBridge(
    private val router: MicRouter,
    private val forcedMode: () -> String?,
) {
    @JavascriptInterface
    fun captureMode(): String {
        val usb = router.usbCommunicationDeviceSelected()
        val forced = forcedMode()
        val mode = CaptureModes.resolve(forced, usb)
        Log.i(
            LOG_TAG,
            "Page asked for capture mode -> $mode (usbSelected=$usb, forced=${forced ?: "none"})",
        )
        return mode
    }
}
