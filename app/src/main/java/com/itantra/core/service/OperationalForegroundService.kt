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
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.app.NotificationCompat
import com.example.itantra.MainActivity

internal fun microphoneForegroundServiceType(sdk: Int): Int =
    if (sdk >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

internal fun operationalForegroundServiceType(sdk: Int, microphone: Boolean, peer: Boolean): Int =
    (if (microphone) microphoneForegroundServiceType(sdk) else 0) or
        (if (peer && sdk >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)

/**
 * Operational Foreground Service for:
 * 1. Continuous mode background listening with explicit microphone type.
 * 2. Active safety-critical emergency alert persistence when activity is in background or screen is off.
 *
 * Runs only for an active voice operation, emergency, or verified peer connection.
 */
class OperationalForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "itantra_operational_channel"
        const val EMERGENCY_CHANNEL_ID = "itantra_emergency_channel"
        const val NOTIFICATION_ID = 1001
        const val EMERGENCY_NOTIFICATION_ID = 1002

        const val ACTION_START_CONTINUOUS = "com.itantra.action.START_CONTINUOUS"
        const val ACTION_START_PEER = "com.itantra.action.START_PEER"
        const val ACTION_STOP_PEER = "com.itantra.action.STOP_PEER"
        const val ACTION_STOP_CONTINUOUS = "com.itantra.action.STOP_CONTINUOUS"
        const val ACTION_TRIGGER_EMERGENCY = "com.itantra.action.TRIGGER_EMERGENCY"
        const val ACTION_RESOLVE_EMERGENCY = "com.itantra.action.RESOLVE_EMERGENCY"

        const val EXTRA_EMERGENCY_TEXT = "extra_emergency_text"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60_000L
        private val _lastStartupError = MutableStateFlow<String?>(null)
        val lastStartupError = _lastStartupError.asStateFlow()
        private val _continuousReady = MutableStateFlow(false)
        val continuousReady = _continuousReady.asStateFlow()

        internal fun reportContinuousFailure(message: String) {
            _continuousReady.value = false
            _lastStartupError.value = message
        }

        fun startContinuous(context: Context): Boolean {
            _lastStartupError.value = null
            _continuousReady.value = false
            val intent = Intent(context, OperationalForegroundService::class.java).apply {
                action = ACTION_START_CONTINUOUS
            }
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: RuntimeException) {
                _lastStartupError.value = "Continuous listening could not start. Keep iTantra open and check microphone and notification permissions."
                false
            }
        }

        fun startPeerConnection(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, OperationalForegroundService::class.java).apply {
                action = ACTION_START_PEER
            })
            true
        } catch (e: RuntimeException) {
            android.util.Log.w("iTantraConnection", "Background connection service unavailable: ${e.message}")
            false
        }

        fun stopPeerConnection(context: Context) {
            try { context.startService(Intent(context, OperationalForegroundService::class.java).apply {
                action = ACTION_STOP_PEER
            }) } catch (_: RuntimeException) {}
        }

        fun stopContinuous(context: Context) {
            val intent = Intent(context, OperationalForegroundService::class.java).apply {
                action = ACTION_STOP_CONTINUOUS
            }
            try { context.startService(intent) } catch (_: RuntimeException) {}
        }

        fun ensureNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                val continuousChannel = NotificationChannel(
                    CHANNEL_ID,
                    "iTantra connection and voice",
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
    private var hasPeerConnection = false
    private var hasActiveEmergency = false
    private val wakeLockHandler = Handler(Looper.getMainLooper())
    private val renewWakeLock = object : Runnable {
        override fun run() {
            if (isContinuousRunning || hasActiveEmergency || hasPeerConnection) {
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
                wakeLockHandler.postDelayed(this, WAKE_LOCK_TIMEOUT_MS / 2)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PEER -> {
                hasPeerConnection = true
                try { refreshForeground(); acquireWakeLock() }
                catch (e: RuntimeException) {
                    hasPeerConnection = false
                    android.util.Log.w("iTantraConnection", "Foreground connection service failed", e)
                    if (!isContinuousRunning && !hasActiveEmergency) { releaseWakeLock(); stopSelf(startId) }
                }
            }
            ACTION_STOP_PEER -> {
                hasPeerConnection = false
                if (!isContinuousRunning && !hasActiveEmergency) {
                    releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                } else if (isContinuousRunning) refreshForeground()
            }
            ACTION_START_CONTINUOUS -> {
                isContinuousRunning = true
                try {
                    refreshForeground()
                    acquireWakeLock()
                    _continuousReady.value = true
                } catch (e: Exception) {
                    isContinuousRunning = false
                    _continuousReady.value = false
                    _lastStartupError.value = "Continuous listening stopped: microphone service is unavailable. Check permissions and retry while iTantra is open."
                    if (!hasActiveEmergency && !hasPeerConnection) {
                        releaseWakeLock()
                        stopSelf(startId)
                    }
                }
            }
            ACTION_STOP_CONTINUOUS -> {
                isContinuousRunning = false
                _continuousReady.value = false
                if (!hasActiveEmergency && !hasPeerConnection) {
                    releaseWakeLock()
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {}
                    stopSelf()
                } else if (hasPeerConnection) refreshForeground()
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
                if (!isContinuousRunning && !hasPeerConnection) {
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
            // Bounded acquisition renewed only while an operation is active.
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iTantra:OperationalWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
        wakeLockHandler.removeCallbacks(renewWakeLock)
        wakeLockHandler.postDelayed(renewWakeLock, WAKE_LOCK_TIMEOUT_MS / 2)
    }

    private fun releaseWakeLock() {
        wakeLockHandler.removeCallbacks(renewWakeLock)
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
            .setContentTitle(if (isContinuousRunning) "iTantra Continuous Mode Active" else "iTantra peer connected")
            .setContentText(if (isContinuousRunning) "Microphone active for automated half-duplex voice transceiver"
                else "Secure nearby messages are active. Open iTantra to disconnect.")
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

    private fun refreshForeground() {
        val notification = buildContinuousNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification,
            operationalForegroundServiceType(Build.VERSION.SDK_INT, isContinuousRunning, hasPeerConnection))
        else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        if (isContinuousRunning) reportContinuousFailure("Continuous listening stopped because the microphone service ended. Open iTantra and retry.")
        _continuousReady.value = false
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
