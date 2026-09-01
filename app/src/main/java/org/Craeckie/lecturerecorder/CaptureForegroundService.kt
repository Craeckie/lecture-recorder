package org.Craeckie.lecturerecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

// Whether the service should be running, given the capture signal and what is running now.
// Split out of the service for the same reason MicRouting is split out of MicRouter: this
// is the part with rules in it, and the only part a bare JVM can test.
object CaptureServiceControl {
    enum class Action { START, STOP, NONE }

    fun next(captureActive: Boolean, running: Boolean): Action = when {
        captureActive && !running -> Action.START
        !captureActive && running -> Action.STOP
        else -> Action.NONE
    }
}

// Since Android 9 (API 28) a backgrounded app with no microphone-type foreground service
// gets digital silence from the mic, not an error. On 2026-09-01 that cost a real lecture
// 86.5 seconds: the Opus encoder kept producing 243-256 packets per 5 s while the bytes
// collapsed from ~20 KB to ~1 KB (about 4 bytes per packet). Nothing in the page or the
// shell reported anything, because nothing failed — the audio was zeros.
//
// This service exists solely to hold the microphone exemption open. It does no capture
// itself: the WebView's Chromium browser process, which is this app's process, owns the
// AudioRecord. It is started when MicDiagnostics reports capture going live and stopped
// when capture ends or the activity is destroyed.
class CaptureForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        // The type argument is required from API 34 and ignored below it; ServiceCompat
        // handles that split so this call site does not have to.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        // On API 34+ this can throw SecurityException if RECORD_AUDIO is not held at the
        // instant of the call (e.g. revoked between the activity's check and this running).
        // A service that never reaches startForeground gets killed by the system anyway --
        // stopSelf() here is an orderly version of that same outcome, not a new failure mode.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            Log.i(LOG_TAG, "Capture foreground service started (type=microphone)")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "startForeground failed, stopping service: $e")
            stopSelf()
        }
        // Not sticky: without the activity there is no WebView and no page, so a
        // resurrected service would hold the mic exemption open for nothing.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(LOG_TAG, "Capture foreground service stopped")
        super.onDestroy()
    }

    // Swiping the task away does not guarantee onDestroy runs (or runs promptly), and
    // START_NOT_STICKY only covers the service getting killed outright -- not this case,
    // where the process (and this service) can keep running with the activity gone. Without
    // this override the service would go on holding the microphone exemption open and
    // showing an ongoing "recording in progress" notification for a page that no longer
    // exists, with no WebView left to ever end it. There is nothing left to record once the
    // task is gone, so stop.
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(LOG_TAG, "Task removed; stopping the capture foreground service")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // IMPORTANCE_LOW: ongoing and silent. The notification is a status indicator, not
        // an alert - it must not buzz mid-lecture.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1

        // Returns whether the service was actually asked to start. Context.
        // startForegroundService() can throw ForegroundServiceStartNotAllowedException
        // (API 31+) or IllegalStateException if the process isn't in an exempted state at
        // this exact instant -- both undocumented-until-you-hit-them edge cases, not just a
        // theoretical possibility. Uncaught, that exception would propagate out of
        // onCaptureActiveChanged and crash the activity, which kills the WebView and ends
        // the recording outright (pitfall #9) -- strictly worse than the digital-silence bug
        // this service exists to fix. So: catch broadly (the throwable set varies by API
        // level and OEM), log, and let capture continue without the background exemption
        // rather than take the whole app down.
        fun start(context: Context): Boolean {
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CaptureForegroundService::class.java),
                )
                true
            } catch (e: Exception) {
                Log.w(
                    LOG_TAG,
                    "Could not start the capture foreground service; capture will " +
                        "continue but is NOT protected from background silencing: $e",
                )
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureForegroundService::class.java))
        }
    }
}
