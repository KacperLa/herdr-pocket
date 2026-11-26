package dev.kaspy.herdr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager

/** Never starts a call. Only protects a user-started microphone lease while the screen is off. */
internal class LiveVoiceForegroundService : Service() {
    private var token: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra(EXTRA_TOKEN, 0) ?: 0
        if (intent?.action == ACTION_STOP) {
            if (token == requested && gate.isPromoted(requested)) {
                // Only the exact call may be ended by this PendingIntent.
                stopCapture(requested, LiveVoiceForegroundLeaseGate.End.USER)
                stopSelf(startId)
            } else if (token?.let(gate::isPromoted) != true) stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!gate.owns(requested)) {
            // A canceled startForegroundService call must still be acknowledged.
            // Never replace a successor's notification.
            if (token?.let(gate::isPromoted) != true) {
                runCatching { promote(requested) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (token == requested && gate.isPromoted(requested)) return START_NOT_STICKY

        token = requested
        if (runCatching { promote(requested); keepCpuAwake() }.isSuccess) {
            gate.promote(requested)
        } else {
            stopCapture(requested, LiveVoiceForegroundLeaseGate.End.LOST)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun promote(token: Long) {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.live_voice_notification_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) })
        val reopen = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, LiveVoiceForegroundService::class.java)
            .setAction(ACTION_STOP).setData(Uri.parse("herdr-pocket-live://end/$token"))
            .putExtra(EXTRA_TOKEN, token), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_terminal_live_voice)
            .setContentTitle(getString(R.string.live_voice_notification_title))
            .setContentText(getString(R.string.live_voice_notification_body))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(reopen)
            .addAction(Notification.Action.Builder(null as Icon?, getString(R.string.live_voice_notification_stop), stop).build())
            .build()
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    // A bounded-by-call lease, not a generic background wakelock: rendering,
    // microphone and network use stop on hangup, route loss, or service teardown.
    @SuppressLint("WakelockTimeout")
    private fun keepCpuAwake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = checkNotNull(getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:live_voice")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun stopCapture(id: Long, reason: LiveVoiceForegroundLeaseGate.End) {
        gate.clear(id, reason)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        token?.let { stopCapture(it, LiveVoiceForegroundLeaseGate.End.LOST) }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION = 7095
        private const val CHANNEL = "live_voice_call"
        private const val ACTION_STOP = "dev.kaspy.herdr.STOP_LIVE_VOICE"
        private const val EXTRA_TOKEN = "dev.kaspy.herdr.LIVE_VOICE_TOKEN"
        private val gate = LiveVoiceForegroundLeaseGate()

        /** Called from a visible Activity after the microphone permission check. */
        fun begin(activity: Activity, onEnd: (LiveVoiceForegroundLeaseGate.End) -> Unit):
            LiveVoiceForegroundLeaseGate.Lease? {
            if (activity.isFinishing || activity.isDestroyed ||
                activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
            val lease = gate.claim(onEnd) ?: return null
            val intent = Intent(activity, LiveVoiceForegroundService::class.java).putExtra(EXTRA_TOKEN, lease.token)
            if (runCatching { activity.startForegroundService(intent) != null }.getOrDefault(false)) return lease
            gate.clear(lease.token)
            return null
        }

        fun isActive(lease: LiveVoiceForegroundLeaseGate.Lease): Boolean = gate.isPromoted(lease.token)

        fun release(context: Context, lease: LiveVoiceForegroundLeaseGate.Lease) {
            val active = gate.isPromoted(lease.token)
            if (gate.clear(lease.token) && active) {
                runCatching { context.stopService(Intent(context, LiveVoiceForegroundService::class.java)) }
            }
            // Pending starts still receive onStartCommand; they acknowledge and exit.
        }
    }
}
