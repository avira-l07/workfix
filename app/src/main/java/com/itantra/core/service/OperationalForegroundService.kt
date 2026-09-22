package com.itantra.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.itantra.MainActivity

/**
 * Operational Foreground Service for:
 * 1. Continuous mode background listening with explicit microphone type.
 * 2. Active safety-critical emergency alert persistence when activity is in background or screen is off.
 *
 * Adheres to strict least-privilege: service is never run unless an active continuous mode
 * or an unresolved emergency alert is running.
 */
class OperationalForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "itantra_operational_channel"
        const val EMERGENCY_CHANNEL_ID = "itantra_emergency_channel"
        const val NOTIFICATION_ID = 1001
        const val EMERGENCY_NOTIFICATION_ID = 1002

        const val ACTION_START_CONTINUOUS = "com.itantra.action.START_CONTINUOUS"
        const val ACTION_STOP_CONTINUOUS = "com.itantra.action.STOP_CONTINUOUS"
        const val ACTION_TRIGGER_EMERGENCY = "com.itantra.action.TRIGGER_EMERGENCY"
        const val ACTION_RESOLVE_EMERGENCY = "com.itantra.action.RESOLVE_EMERGENCY"

        const val EXTRA_EMERGENCY_TEXT = "extra_emergency_text"

        fun startContinuous(context: Context) {
            val intent = Intent(context, OperationalForegroundService::class.java).apply {
                action = ACTION_START_CONTINUOUS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopContinuous(context: Context) {
            val intent = Intent(context, OperationalForegroundService::class.java).apply {
                action = ACTION_STOP_CONTINUOUS
            }
            context.startService(intent)
        }

        fun ensureNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                val continuousChannel = NotificationChannel(
                    CHANNEL_ID,
                    "iTantra Continuous Mode",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notification for active continuous speech transceiver"
                }

                val emergencyChannel = NotificationChannel(
                    EMERGENCY_CHANNEL_ID,
                    "iTantra Critical Emergency Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High priority notifications for unresolved SOS emergency messages"
                    setBypassDnd(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            manager.isNotificationPolicyAccessGranted
                        } else {
                            true
                        }
                    )
                }

                manager.createNotificationChannel(continuousChannel)
                manager.createNotificationChannel(emergencyChannel)
            }
        }

        fun triggerEmergency(context: Context, emergencyText: String) {
            val intent = Intent(context, OperationalForegroundService::class.java).apply {
                action = ACTION_TRIGGER_EMERGENCY
                putExtra(EXTRA_EMERGENCY_TEXT, emergencyText)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // If background start is restricted, notification can be shown directly
                try {
                    ensureNotificationChannels(context)
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    val publicNotification = NotificationCompat.Builder(context, EMERGENCY_CHANNEL_ID)
                        .setContentTitle("Critical iTantra alert")
                        .setContentText("Unlock to view details")
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .build()

                    val builder = NotificationCompat.Builder(context, EMERGENCY_CHANNEL_ID)
                        .setContentTitle("CRITICAL SOS ALERT")
                        .setContentText(emergencyText)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setOngoing(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setPublicVersion(publicNotification)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                    manager?.notify(EMERGENCY_NOTIFICATION_ID, builder.build())
                } catch (_: Exception) {}
            }
        }

        fun resolveEmergency(context: Context) {
            val intent = Intent(context, OperationalForegroundService::class.java).apply {
                action = ACTION_RESOLVE_EMERGENCY
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var isContinuousRunning = false
    private var hasActiveEmergency = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CONTINUOUS -> {
                isContinuousRunning = true
                acquireWakeLock()
                val notification = buildContinuousNotification()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            ACTION_STOP_CONTINUOUS -> {
                isContinuousRunning = false
                if (!hasActiveEmergency) {
                    releaseWakeLock()
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {}
                    stopSelf()
                }
            }
            ACTION_TRIGGER_EMERGENCY -> {
                hasActiveEmergency = true
                acquireWakeLock()
                val emergencyText = intent.getStringExtra(EXTRA_EMERGENCY_TEXT) ?: "CRITICAL SOS ALERT"
                val notification = buildEmergencyNotification(emergencyText)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(EMERGENCY_NOTIFICATION_ID, notification)
            }
            ACTION_RESOLVE_EMERGENCY -> {
                hasActiveEmergency = false
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(EMERGENCY_NOTIFICATION_ID)
                if (!isContinuousRunning) {
                    releaseWakeLock()
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {}
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            // No fixed timeout: wakelock is tied to continuous mode lifetime.
            // releaseWakeLock() is always called on ACTION_STOP_CONTINUOUS,
            // ACTION_RESOLVE_EMERGENCY, and onDestroy() to prevent leaks.
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iTantra:OperationalWakeLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wakeLock = null
    }

    private fun buildContinuousNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iTantra Continuous Mode Active")
            .setContentText("Microphone active for automated half-duplex voice transceiver")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildEmergencyNotification(alertText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val publicNotification = NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
            .setContentTitle("Critical iTantra alert")
            .setContentText("Unlock to view details")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()

        return NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
            .setContentTitle("CRITICAL SOS ALERT")
            .setContentText(alertText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
    }

    private fun createNotificationChannels() {
        ensureNotificationChannels(this)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
