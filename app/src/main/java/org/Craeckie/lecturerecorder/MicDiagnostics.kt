package org.Craeckie.lecturerecorder

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

// Whether the platform is currently feeding this app silence instead of microphone audio.
// Pure, so the rule ("any silenced config counts, an empty list does not") is testable on a
// bare JVM; AudioRecordingConfiguration itself is not constructible in a unit test.
object CaptureSilence {
    fun isSilenced(silencedFlags: List<Boolean>): Boolean = silencedFlags.any { it }
}

// Everything this app logs about the microphone. Kept apart from MicRouter so the routing
// decision stays readable: MicRouter decides, MicDiagnostics reports.
class MicDiagnostics(
    context: Context,
    // Called on the main thread whenever capture starts or stops, and only on an actual
    // transition. The recording callback is the one signal the shell has for "the page is
    // capturing right now" — the page itself never tells us — so this is also what drives
    // FLAG_KEEP_SCREEN_ON in MainActivity.
    private val onCaptureActiveChanged: (Boolean) -> Unit = {},
    // Called on the main thread whenever the platform starts or stops feeding this app
    // silence instead of real microphone audio, and only on an actual transition. Android
    // reports this (isClientSilenced, API 29+) but the page cannot see it and does not
    // notice: the track stays live and the encoder keeps producing packets, they just carry
    // zeros. On 2026-09-01 that was 86.5 seconds of a real lecture, invisible until the log
    // was read afterwards.
    private val onCaptureSilencedChanged: (Boolean) -> Unit = {},
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainExecutor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var communicationDeviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var captureActive = false
    private var captureSilenced = false

    companion object {
        // AudioDeviceInfo.TYPE_* names, for the types this app can plausibly see. Unmapped
        // values keep their raw number rather than being reported as "unknown".
        private val DEVICE_TYPE_NAMES = mapOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET to "WIRED_HEADSET",
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to "BLUETOOTH_SCO",
            AudioDeviceInfo.TYPE_USB_DEVICE to "USB_DEVICE",
            AudioDeviceInfo.TYPE_USB_ACCESSORY to "USB_ACCESSORY",
            AudioDeviceInfo.TYPE_USB_HEADSET to "USB_HEADSET",
            AudioDeviceInfo.TYPE_BUILTIN_MIC to "BUILTIN_MIC",
            AudioDeviceInfo.TYPE_FM_TUNER to "FM_TUNER",
            AudioDeviceInfo.TYPE_TELEPHONY to "TELEPHONY",
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX to "REMOTE_SUBMIX",
        )

        // MediaRecorder.AudioSource.* names. VOICE_COMMUNICATION vs MIC is the one that
        // matters: setCommunicationDevice governs the former and not the latter.
        private val AUDIO_SOURCE_NAMES = mapOf(
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT",
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
        )

        fun describeDeviceType(type: Int): String = DEVICE_TYPE_NAMES[type] ?: "TYPE_$type"

        fun describeAudioSource(source: Int): String = AUDIO_SOURCE_NAMES[source] ?: "SOURCE_$source"

        // Shared device-description format, so MicRouter's routing-decision logs and
        // MicDiagnostics's own logs describe the same device in the same shape.
        internal fun describe(device: AudioDeviceInfo): String =
            "${device.productName} [${describeDeviceType(device.type)} id=${device.id}]"
    }

    fun attach() {
        logInputInventory()
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                logRecordingConfigs(configs.orEmpty())
            }
        }
        audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        recordingCallback = callback

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                // Fires both as an echo of this app's own setCommunicationDevice() calls and
                // for external changes (a call, another app, a Bluetooth headset connecting).
                // On the normal happy path you'll see "USB communication device selected: ..."
                // immediately followed by this line for the same device — that's the echo,
                // not a takeover; don't mistake it for interference.
                Log.i(LOG_TAG, "Communication device now: ${device?.let(::describe) ?: "none"}")
            }
            audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, listener)
            communicationDeviceListener = listener
        }
    }

    fun detach() {
        recordingCallback?.let { audioManager.unregisterAudioRecordingCallback(it) }
        recordingCallback = null
        // No more callbacks are coming, so report the capture as over rather than leaving
        // the listener believing it is still running.
        if (captureActive) {
            captureActive = false
            onCaptureActiveChanged(false)
        }
        if (captureSilenced) {
            captureSilenced = false
            onCaptureSilencedChanged(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            communicationDeviceListener?.let { audioManager.removeOnCommunicationDeviceChangedListener(it) }
        }
        communicationDeviceListener = null
    }

    // Every input the platform can see, so "the USB mic never showed up" and "it showed up
    // but wasn't selected" are distinguishable from the log alone.
    fun logInputInventory() {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (inputs.isEmpty()) {
            Log.w(LOG_TAG, "No audio inputs reported by the platform")
            return
        }
        Log.i(LOG_TAG, "Audio inputs (${inputs.size}):")
        for (device in inputs) {
            Log.i(LOG_TAG, "  - ${describe(device)}")
        }
    }

    private fun logRecordingConfigs(configs: List<AudioRecordingConfiguration>) {
        val active = configs.isNotEmpty()
        if (active != captureActive) {
            captureActive = active
            onCaptureActiveChanged(active)
        }
        val silencedFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            configs.map { it.isClientSilenced }
        } else {
            emptyList()
        }
        val silenced = CaptureSilence.isSilenced(silencedFlags)
        if (silenced != captureSilenced) {
            captureSilenced = silenced
            onCaptureSilencedChanged(silenced)
        }
        if (configs.isEmpty()) {
            // Also the normal end of a recording — read it together with the page console.
            Log.i(LOG_TAG, "Recording stopped (no active capture)")
            return
        }
        for (config in configs) {
            val source = describeAudioSource(config.clientAudioSource)
            // AudioRecordingConfiguration.getAudioDevice() is API 29+; minSdk is 26, and this
            // callback fires on any device the first time capture starts, so the call must be
            // guarded the same way isClientSilenced already is above.
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                config.audioDevice?.let(::describe) ?: "device unreported"
            } else {
                "device unknown (API < 29)"
            }
            val format = config.format
            Log.i(
                LOG_TAG,
                "Recording active: source=$source via $device " +
                    "${format.sampleRate}Hz/${format.channelCount}ch",
            )
            if (source != "VOICE_COMMUNICATION") {
                // See the known limitation in the spec: setCommunicationDevice does not
                // govern the plain MIC source, so USB routing will be ignored.
                Log.w(LOG_TAG, "Capture uses $source, not VOICE_COMMUNICATION — USB routing does not apply")
            }
        }
        if (silenced) {
            // The track stays live and the page notices nothing — this log line and the
            // in-app banner are the only signs that the audio reaching the server is silence.
            Log.w(LOG_TAG, "Capture is SILENCED by the system (call, privacy toggle, or another app)")
        }
    }
}
