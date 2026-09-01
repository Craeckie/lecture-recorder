package org.Craeckie.lecturerecorder

import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

// The foreground service is what stops a backgrounded app from recording 86.5 seconds of
// digital silence (docs/superpowers/specs/2026-09-01-device-log-findings.md, Part 1). Every
// element below is load-bearing: without the type attribute, Android 14+ refuses to start
// it; without FOREGROUND_SERVICE_MICROPHONE, the mic exemption does not apply. None of that
// is visible in the app's UI when it breaks — the symptom is silence.
class ForegroundServiceManifestTest {
    private val manifest: String by lazy {
        // The JVM test's working directory is the app module.
        File("src/main/AndroidManifest.xml").readText()
    }

    @Test
    fun `declares the foreground service permissions`() {
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE\""))
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS\""))
    }

    @Test
    fun `declares the service with the microphone foreground type`() {
        assertTrue(manifest.contains(".CaptureForegroundService"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"microphone\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
    }

    @Test
    fun `still keeps uiMode in configChanges`() {
        // Unrelated to this service, but this is the cheapest place to pin it: dropping
        // uiMode restarts the activity on a theme switch, which reloads the page, which
        // POSTs /delete_session/<id> and destroys the recording (pitfall #9).
        assertTrue(manifest.contains("android:configChanges=\"uiMode|orientation|screenSize"))
    }
}
