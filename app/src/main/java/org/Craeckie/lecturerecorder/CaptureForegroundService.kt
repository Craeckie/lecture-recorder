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
            .setSmallIcon(android.R.drawable.presence_audio_online)
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
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        Log.i(LOG_TAG, "Capture foreground service started (type=microphone)")
        // Not sticky: without the activity there is no WebView and no page, so a
        // resurrected service would hold the mic exemption open for nothing.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(LOG_TAG, "Capture foreground service stopped")
        super.onDestroy()
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CaptureForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureForegroundService::class.java))
        }
    }
}
