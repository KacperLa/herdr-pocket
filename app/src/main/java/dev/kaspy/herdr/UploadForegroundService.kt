package dev.kaspy.herdr

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/** Process-local protection for a single user-started file upload. No document data enters the service. */
internal class UploadForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var token: Long? = null
    private var currentStartId = 0
    private var timeout: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra(EXTRA_TOKEN, 0) ?: 0
        if (!gate.owns(requested)) {
            // An already requested foreground start still needs its promotion even if
            // the UI canceled before delivery. A stale start cannot open the picker.
            promoteForeground()
            if (token?.let(gate::owns) != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (token != requested) timeout?.let(handler::removeCallbacks)
        token = requested
        currentStartId = startId
        if (!promoteForeground()) {
            finishFromService()
        } else if (gate.promote(requested)) {
            val expiry = Runnable { if (token == requested && gate.owns(requested)) finishFromService() }
            timeout = expiry
            expiresAt = SystemClock.elapsedRealtime() + MAX_LEASE_MS
            handler.postDelayed(expiry, MAX_LEASE_MS)
            pendingReady?.let { ready -> pendingReady = null; ready() }
        }
        return START_NOT_STICKY
    }

    private fun promoteForeground(): Boolean = runCatching {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.agent_files_upload_title), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) })
        val notice = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_file_upload_notice)
            .setContentTitle(getString(R.string.agent_files_upload_notice))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }.isSuccess

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (currentStartId == startId) finishFromService()
    }

    private fun finishFromService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        token?.let(::lost)
    }

    override fun onDestroy() {
        timeout?.let(handler::removeCallbacks)
        stopForeground(STOP_FOREGROUND_REMOVE)
        token?.let(::lost)
        super.onDestroy()
    }

    companion object {
        private const val MAX_LEASE_MS = 5 * 60_000L
        private const val NOTIFICATION = 7094
        private const val CHANNEL = "file_upload"
        private const val EXTRA_TOKEN = "dev.kaspy.herdr.UPLOAD_FOREGROUND"
        private val gate = UploadForegroundGate()
        private var pendingReady: (() -> Unit)? = null
        private var onLost: (() -> Unit)? = null
        private var expiresAt = 0L

        /** Called only on the main thread from a visible, resumed Activity. */
        fun start(activity: Activity, onReady: () -> Unit, onFailure: () -> Unit): Long? {
            val token = gate.claim() ?: return null
            pendingReady = onReady
            onLost = onFailure
            val intent = Intent(activity, UploadForegroundService::class.java).putExtra(EXTRA_TOKEN, token)
            val started = runCatching { activity.startForegroundService(intent) != null }.getOrDefault(false)
            if (started) return token
            clear(token)
            return null
        }

        fun isValid(token: Long): Boolean =
            gate.isPromoted(token) && SystemClock.elapsedRealtime() < expiresAt

        /** Stop only the exact terminating upload. */
        fun release(context: Context, token: Long) {
            val promoted = gate.isPromoted(token)
            if (clear(token) && promoted) {
                runCatching { context.stopService(Intent(context, UploadForegroundService::class.java)) }
            }
            // If promotion is still pending, onStartCommand fulfils that platform
            // obligation and immediately stops without opening the picker.
        }

        private fun lost(token: Long) {
            val callback = onLost
            if (clear(token)) callback?.invoke()
        }

        private fun clear(token: Long): Boolean {
            if (!gate.release(token)) return false
            pendingReady = null
            onLost = null
            expiresAt = 0L
            return true
        }
    }
}
