package dev.kaspy.herdr

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class AgentWatchScope(val config:SshConfig,val namedSession:String?)
internal const val AGENT_NOTIFICATIONS_ENABLED = "agent_notifications_enabled"

internal data class AgentWatchAlertTarget(val scope:AgentWatchScope,val terminalId:String)
internal enum class AgentWatchServiceFailure { NOTIFICATIONS_DISABLED, PROFILE_UNAVAILABLE, START_DENIED, START_TIMEOUT, CONNECTION_ENDED }
internal sealed interface AgentWatchServiceState {
    data object Stopped:AgentWatchServiceState
    data class Active(val scope:AgentWatchScope,val state:AgentWatchState):AgentWatchServiceState
    data class Ended(val scope:AgentWatchScope,val state:AgentWatchState):AgentWatchServiceState
    data class Failed(val reason:AgentWatchServiceFailure):AgentWatchServiceState
}

/** Only private process memory carries SSH credentials or the routing behind alert notifications. */
internal class AgentWatchService : Service() {
    private var token:String?=null
    private var request:StartRequest?=null
    private var connection:AgentWatchConnection?=null
    private var stateInService=AgentWatchState.CONNECTING
    private var receiverRegistered=false
    private val byTerminal=LinkedHashMap<String,PostedAlert>()
    private val checkNotificationSettings=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(token!=null && !canNotify(context))
                finish(AgentWatchServiceState.Failed(AgentWatchServiceFailure.NOTIFICATIONS_DISABLED))
        }
    }

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val incoming=intent?.data?.lastPathSegment
        if(intent?.action==STOP_ACTION) {
            if(incoming!=null && incoming==token && incoming==ownedToken) {
                getSharedPreferences("workspace", MODE_PRIVATE).edit()
                    .putBoolean(AGENT_NOTIFICATIONS_ENABLED, false).apply()
                finish(AgentWatchServiceState.Stopped)
            } else if(token==null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val start=pending?.takeIf {intent?.action==START_ACTION && it.token==incoming && it.token==ownedToken}
        if(start==null) {
            if(token==null) {
                // A user may stop just before Android delivers startForegroundService.
                // Fulfil that foreground launch with a transient notice instead of ever using stale credentials.
                if(intent?.action==START_ACTION && incoming!=null) {
                    runCatching {startForeground(ONGOING_ID,ongoing(incoming,AgentWatchState.CONNECTING),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)}
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        pending=null
        // The platform may reuse this service instance when a new verified profile
        // replaces an existing watcher. Always retire the old transport first.
        closeTransport()
        request=start
        token=start.token
        stateInService=AgentWatchState.CONNECTING
        try {
            startForeground(ONGOING_ID,ongoing(start.token,stateInService),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            if(!canNotify(this)) {
                finish(AgentWatchServiceState.Failed(AgentWatchServiceFailure.NOTIFICATIONS_DISABLED))
                return START_NOT_STICKY
            }
            registerReceiver(
                checkNotificationSettings,
                IntentFilter().apply {
                    addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
                    addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered=true
            val next=AgentWatchConnection(
                start.scope.config,start.transportSession,start.key,start.passphrase,
                {alert -> main.post {if(isCurrent(start.token)) postAlert(start.scope,alert)}},
                {newState -> main.post {if(isCurrent(start.token)) acceptState(start,newState)}},
            )
            connection=next
            Thread({
                try {
                    next.run()
                } catch(_:Exception) {
                    // Do not let a transport error crash the Activity process.
                } finally {
                    runCatching {next.close()}
                    start.wipe()
                    main.post {if(isCurrent(start.token)) transportEnded(start.scope)}
                }
            },"herdr-agent-watch").start()
        } catch(_:RuntimeException) {
            finish(AgentWatchServiceState.Failed(AgentWatchServiceFailure.START_DENIED))
        }
        return START_NOT_STICKY
    }

    private fun isCurrent(id:String):Boolean=token==id && ownedToken==id

    private fun acceptState(start:StartRequest,newState:AgentWatchState) {
        stateInService=newState
        if(!canNotify(this)) {
            finish(AgentWatchServiceState.Failed(AgentWatchServiceFailure.NOTIFICATIONS_DISABLED))
            return
        }
        if(newState in ACTIVE_TRANSPORT_STATES) {
            mutableState.value=AgentWatchServiceState.Active(start.scope,newState)
            runCatching {notifications().notify(ONGOING_ID,ongoing(start.token,newState))}
        } else {
            finish(AgentWatchServiceState.Ended(start.scope,newState))
        }
    }

    private fun transportEnded(scope:AgentWatchScope) {
        val final=if(stateInService in ACTIVE_TRANSPORT_STATES)
            AgentWatchServiceState.Failed(AgentWatchServiceFailure.CONNECTION_ENDED)
        else AgentWatchServiceState.Ended(scope,stateInService)
        finish(final)
    }

    private fun postAlert(scope:AgentWatchScope,alert:AgentWatchAlert) {
        if(!canNotify(this)) {
            finish(AgentWatchServiceState.Failed(AgentWatchServiceFailure.NOTIFICATIONS_DISABLED))
            return
        }
        if(!TERMINAL_ID.matches(alert.terminalId)) return
        val manager=notifications()
        val previous=byTerminal.remove(alert.terminalId)
        val id=previous?.id ?: nextAlertId++
        previous?.let {routes.remove(it.token)}
        if(byTerminal.size>=MAX_POSTED_ALERTS) {
            val first=byTerminal.entries.iterator()
            val record=first.next().value
            first.remove()
            routes.remove(record.token)
            manager.cancel(record.id)
        }
        val route=UUID.randomUUID().toString()
        routes.put(route,AgentWatchAlertTarget(scope,alert.terminalId),SystemClock.elapsedRealtime())
        val open=Intent(this,MainActivity::class.java).setAction(OPEN_ALERT_ACTION)
            .setData(tokenUri("open",route))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val title=when(alert.status) {
            AgentWatchStatus.BLOCKED -> R.string.agent_watch_blocked_alert
            AgentWatchStatus.DONE -> R.string.agent_watch_done_alert
        }
        try {
            manager.notify(id,Notification.Builder(this,ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_agent_watch)
                .setContentTitle(getString(title))
                .setContentText(alert.name.trim().ifEmpty {getString(R.string.agent_watch_open_app)})
                .setContentIntent(tap).setAutoCancel(true).setVisibility(Notification.VISIBILITY_SECRET)
                .setCategory(Notification.CATEGORY_STATUS).build())
            byTerminal[alert.terminalId]=PostedAlert(id,route)
        } catch(_:SecurityException) {
            routes.remove(route)
            finish(AgentWatchServiceState.Failed(AgentWatchServiceFailure.NOTIFICATIONS_DISABLED))
        }
    }

    private fun ongoing(id:String,state:AgentWatchState):Notification {
        val stop=Intent(this,AgentWatchService::class.java).setAction(STOP_ACTION).setData(tokenUri("stop",id))
        val stopTap=PendingIntent.getService(this,0,stop,PendingIntent.FLAG_IMMUTABLE)
        val open=Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openTap=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE)
        val body=when(state) {
            AgentWatchState.WATCHING -> R.string.agent_watch_watching_body
            AgentWatchState.RETRYING -> R.string.agent_watch_retrying_body
            else -> R.string.agent_watch_connecting_body
        }
        return Notification.Builder(this,WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_agent_watch)
            .setContentTitle(getString(R.string.agent_watch_ongoing)).setContentText(getString(body))
            .setContentIntent(openTap).addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_agent_watch),
                getString(R.string.agent_watch_stop),stopTap,
            ).build())
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun finish(value:AgentWatchServiceState) {
        val own=token
        token=null
        closeTransport()
        if(own!=null && ownedToken==own) {
            ownedToken=null
            mutableState.value=value
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTransport() {
        val transport=connection
        connection=null
        val start=request
        request=null
        if(transport!=null) Thread({runCatching {transport.close()}},"herdr-stop-watch").apply {
            isDaemon=true
            this.start()
        } else start?.wipe()
        if(receiverRegistered) {
            receiverRegistered=false
            runCatching {unregisterReceiver(checkNotificationSettings)}
        }
    }

    override fun onDestroy() {
        val own=token
        token=null
        closeTransport()
        if(own!=null && ownedToken==own) {
            ownedToken=null
            mutableState.value=AgentWatchServiceState.Stopped
        }
        super.onDestroy()
    }

    private data class PostedAlert(val id:Int,val token:String)
    private data class StartRequest(
        val token:String,val scope:AgentWatchScope,val transportSession:String?,
        val key:ByteArray,val passphrase:ByteArray?,
    ) {
        fun wipe() {key.fill(0);passphrase?.fill(0)}
    }

    companion object {
        private const val START_ACTION="dev.kaspy.herdr.watch.START"
        private const val STOP_ACTION="dev.kaspy.herdr.watch.STOP"
        private const val OPEN_ALERT_ACTION="dev.kaspy.herdr.watch.OPEN_ALERT"
        private const val WATCH_CHANNEL="agent_watch_service"
        private const val ALERT_CHANNEL="agent_watch_alerts"
        private const val ONGOING_ID=14500
        private const val MAX_POSTED_ALERTS=24
        private val ACTIVE_TRANSPORT_STATES=setOf(AgentWatchState.CONNECTING,AgentWatchState.WATCHING,AgentWatchState.RETRYING)
        private val TERMINAL_ID=Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
        private val main=Handler(Looper.getMainLooper())
        private val routes=AgentWatchTapTokens<AgentWatchAlertTarget>()
        private var nextAlertId=14501
        private var ownedToken:String?=null
        private var pending:StartRequest?=null
        private val mutableState=MutableStateFlow<AgentWatchServiceState>(AgentWatchServiceState.Stopped)
        val state:StateFlow<AgentWatchServiceState> = mutableState.asStateFlow()

        private fun tokenUri(purpose:String,token:String):Uri=Uri.Builder()
            .scheme("herdr-pocket").authority("agent-watch").appendPath(purpose).appendPath(token).build()

        /** For a visible UI only, after requesting POST_NOTIFICATIONS. */
        fun startFromVisible(
            activity:Activity,config:SshConfig,namedSession:String?,privateKey:ByteArray,passphrase:ByteArray?,
        ):Boolean {
            if(ownedToken!=null) return false
            if(activity.isFinishing || activity.isDestroyed ||
                activity is LifecycleOwner && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                mutableState.value=AgentWatchServiceState.Failed(AgentWatchServiceFailure.START_DENIED)
                return false
            }
            if(!canNotify(activity)) {
                mutableState.value=AgentWatchServiceState.Failed(AgentWatchServiceFailure.NOTIFICATIONS_DISABLED)
                return false
            }
            if(config.expectedPin?.let(::isSshSha256Pin)!=true || privateKey.isEmpty() ||
                namedSession!=null && TuiAgents.parseSession(namedSession)!=namedSession) {
                mutableState.value=AgentWatchServiceState.Failed(AgentWatchServiceFailure.PROFILE_UNAVAILABLE)
                return false
            }
            val current=StartRequest(UUID.randomUUID().toString(),
                AgentWatchScope(config,normalizedWatchSession(namedSession)),namedSession,
                privateKey.copyOf(),passphrase?.copyOf())
            pending=current
            ownedToken=current.token
            mutableState.value=AgentWatchServiceState.Active(current.scope,AgentWatchState.CONNECTING)
            val app=activity.applicationContext
            try {
                app.startForegroundService(Intent(app,AgentWatchService::class.java)
                    .setAction(START_ACTION).setData(tokenUri("start",current.token)))
            } catch(_:RuntimeException) {
                pending=null
                ownedToken=null
                current.wipe()
                mutableState.value=AgentWatchServiceState.Failed(AgentWatchServiceFailure.START_DENIED)
                return false
            }
            main.postDelayed({
                if(pending===current) {
                    pending=null
                    ownedToken=null
                    current.wipe()
                    mutableState.value=AgentWatchServiceState.Failed(AgentWatchServiceFailure.START_TIMEOUT)
                }
            },12_000)
            return true
        }

        fun stop(context:Context) {
            val starting=pending
            ownedToken=null
            starting?.wipe()
            pending=null
            mutableState.value=AgentWatchServiceState.Stopped
            // A queued foreground start must still be promoted and immediately stopped.
            if(starting==null) context.stopService(Intent(context,AgentWatchService::class.java))
        }

        fun canNotify(context:Context):Boolean {
            if(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false
            val manager=context.notifications()
            if(!manager.areNotificationsEnabled()) return false
            manager.createNotificationChannel(NotificationChannel(WATCH_CHANNEL,
                context.getString(R.string.agent_watch_service_channel),NotificationManager.IMPORTANCE_LOW).apply {setShowBadge(false)})
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL,
                context.getString(R.string.agent_watch_alert_channel),NotificationManager.IMPORTANCE_DEFAULT))
            return listOf(WATCH_CHANNEL,ALERT_CHANNEL).all {
                manager.getNotificationChannel(it)?.importance?.let {importance -> importance!=NotificationManager.IMPORTANCE_NONE}==true
            }
        }

        /** Only for suppressing cold auto-connect when navigating from an expired notification. */
        fun isAlertIntent(intent:Intent?):Boolean=intent?.action==OPEN_ALERT_ACTION

        /** Exported activity treats unknown, forged and post-process-death intents as no-op taps. */
        fun takeAlert(intent:Intent?):AgentWatchAlertTarget? {
            if(intent?.action!=OPEN_ALERT_ACTION) return null
            val data=intent.data ?: return null
            if(data.scheme!="herdr-pocket" || data.authority!="agent-watch" || data.pathSegments.size!=2 ||
                data.pathSegments.first()!="open" || data.query!=null || data.fragment!=null) return null
            return routes.take(data.pathSegments.last(),SystemClock.elapsedRealtime())
        }
    }
}

private fun Context.notifications():NotificationManager=requireNotNull(getSystemService(NotificationManager::class.java))
