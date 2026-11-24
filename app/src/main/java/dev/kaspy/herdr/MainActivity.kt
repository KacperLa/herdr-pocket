package dev.kaspy.herdr

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.indexOfLastPressed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.connectbot.terminal.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil

class PocketModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("workspace", 0)
    private val startupGate=StartupReconnect()
    private val resolver:ContentResolver=app.contentResolver
    private val vault=PassphraseVault(app)
    private fun hasReadGrant(uri:Uri):Boolean = resolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission }
    private val initialKey=prefs.getString("key_document_uri",null)?.let(Uri::parse)?.takeIf { it.scheme=="content" }
    private val restoredKey=initialKey?.takeIf { runCatching {hasReadGrant(it)}.getOrDefault(false) }
    private val keyGrantRevoked=initialKey!=null && restoredKey==null
    private fun releaseKey(uri:Uri?) {
        if(uri!=null) runCatching {resolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
    }
    init {
        if(keyGrantRevoked) {
            prefs.edit().remove("key_document_uri").apply()
            vault.clear()
        }
    }
    var agentName by mutableStateOf(prefs.getString("agent", "My agent")!!)
    private var hostState by mutableStateOf(prefs.getString("host", "")!!)
    private var portState by mutableStateOf(prefs.getString("port", "22")!!)
    var host: String
        get() = hostState
        set(value) { if (value != hostState) { hostState=value; noteManualConnectionAction(); invalidateOffer(); saveTypedPassphrase=false; passphrase=""; if(listedConfig!=null) cancelPicker() } }
    var port: String
        get() = portState
        set(value) { if (value != portState) { portState=value; noteManualConnectionAction(); invalidateOffer(); saveTypedPassphrase=false; passphrase=""; if(listedConfig!=null) cancelPicker() } }
    private val hostTrust = HostTrust(
        read={ endpoint -> prefs.getString(endpoint.preferenceKey,null) },
        write={ endpoint,fingerprint -> prefs.edit().putString(endpoint.preferenceKey,fingerprint).commit() },
    )
    private val selectedEndpoint get()=HostEndpoint.parse(host,port)
    private var pinRevision by mutableIntStateOf(0)
    val storedPin: String?
        get() = pinRevision.let { selectedEndpoint?.let(hostTrust::pin) }
    init {
        val legacyPin=prefs.getString("pin",null)
        if(legacyPin!=null) {
            val endpoint=selectedEndpoint
            // One atomic edit; never leave an unscoped pin available for a future endpoint edit.
            val edit=prefs.edit().remove("pin")
            if(endpoint!=null) hostTrust.legacyPinToMigrate(endpoint,legacyPin)?.let {
                edit.putString(endpoint.preferenceKey,it)
            }
            edit.commit()
        }
    }
    private var userState by mutableStateOf(prefs.getString("user", "")!!)
    var user: String
        get() = userState
        set(value) { if(value!=userState) {userState=value;noteManualConnectionAction();invalidateOffer();saveTypedPassphrase=false;passphrase="";if(listedConfig!=null) cancelPicker()} }
    var command by mutableStateOf(prefs.getString("command", "herdr")!!)
    var keyUri by mutableStateOf<Uri?>(restoredKey)
        private set
    var keyRemembered by mutableStateOf(restoredKey!=null)
        private set
    var passphrase by mutableStateOf("")
    var saveTypedPassphrase by mutableStateOf(false)
        private set
    var hasSavedPassphrase by mutableStateOf(vault.hasSaved())
        private set
    var credentialNotice by mutableStateOf(if(keyGrantRevoked) "Key access expired. Select the file again." else "")
        private set
    private data class PendingSave(val scope:PassphraseScope,val bytes:ByteArray)
    private var pendingSave:PendingSave?=null
    private val vaultMutation=Any()
    @Volatile private var credentialEpoch=0L
    private fun clearPendingSave() {pendingSave?.bytes?.fill(0);pendingSave=null}
    private fun onAuthenticated() {
        val saving=pendingSave ?: return
        pendingSave=null
        val epoch=credentialEpoch
        saveTypedPassphrase=false
        viewModelScope.launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                val saved=withContext(Dispatchers.IO) {
                    synchronized(vaultMutation) {
                        epoch==credentialEpoch && vault.save(saving.scope,saving.bytes)
                    }
                }
                if(epoch==credentialEpoch) {
                    hasSavedPassphrase=vault.hasSaved()
                    credentialNotice=if(saved) "Passphrase saved on this device." else "The passphrase was not saved. You can still use this connection."
                }
            } finally {saving.bytes.fill(0)}
        }
    }
    fun toggleRememberPassphrase(enabled:Boolean) {saveTypedPassphrase=enabled && keyRemembered}
    fun forgetSavedPassphrase():Boolean {
        clearPendingSave()
        saveTypedPassphrase=false
        credentialEpoch++
        val removed=synchronized(vaultMutation) {vault.clear()}
        hasSavedPassphrase=vault.hasSaved()
        credentialNotice=if(removed) "Saved passphrase forgotten." else "Could not fully clear the saved passphrase. Try again."
        return removed
    }
    private var tuiSessionState by mutableStateOf(prefs.getString("tui_session","").orEmpty())
    var tuiSession:String
        get()=tuiSessionState
        set(value) {
            if(value==tuiSessionState || busy) return
            tuiSessionState=value
            noteManualConnectionAction()
            invalidateOffer()
            if(listedConfig!=null) cancelPicker()
        }
    var sessions by mutableStateOf<List<CoreSession>>(emptyList())
    var showSessionPicker by mutableStateOf(false)
        private set
    private var startupInFlight=false
    private var startupWatchdog:Job?=null
    private var reconnectTarget:ReconnectTarget?=null
    private var reconnectOffered by mutableStateOf(false)

    private fun reconnectProfile():ReconnectProfile? {
        val endpoint=selectedEndpoint ?: return null
        val pin=storedPin?.takeIf(::isSshSha256Pin) ?: return null
        val login=user.trim().takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return null
        val key=keyUri ?: return null
        return ReconnectProfile(endpoint,login,pin,key.toString())
    }

    val canReconnect get() = reconnectOffered && reconnectTarget?.isAvailable(
        reconnectProfile(),busy || showSessionPicker || demo,command,tuiSession.trim().ifBlank {null},
    )==true

    private var retainedTerminal by mutableStateOf(false)
    private val retainedOutput=ArrayDeque<ByteArray>()
    private var retainedOutputBytes=0

    internal val terminalRecovery get()=terminalRecoveryUi(retainedTerminal,terminalVisible,busy,canReconnect)

    private fun suspendCurrentTerminal() {
        FilesUploadInterruption.onConnectionLoss(activeUpload!=null,activeUpload?.request!=null)
            ?.let {filesUploadNotice=it}
        dismissAgentMenu()
        if(terminalVisible && !demo && displayedPty!=null) retainedTerminal=true
        clearRetainedOutput()
    }

    private fun clearRetainedOutput() {
        retainedOutput.clear()
        retainedOutputBytes=0
    }

    private fun renderConnectionOutput(bytes:ByteArray) {
        if(!retainedTerminal) {
            renderRemote(bytes)
        } else if(bytes.size <= 1024*1024-retainedOutputBytes) {
            // SSH can emit some output before its exec acknowledgement. Never
            // paint it onto the old pane while the replacement is still staged.
            retainedOutput.addLast(bytes)
            retainedOutputBytes+=bytes.size
        }
    }

    private fun restoreTerminalAfterReconnect() {
        retainedTerminal=false
        replaceEmulator(displayedPty)
        retainedOutput.forEach(::renderRemote)
        clearRetainedOutput()
    }

    internal fun openRecoverySettings() {if(terminalRecovery!=null) terminalVisible=false}

    fun reconnect() {
        if(!canReconnect) return
        when(val mode=reconnectTarget?.mode ?: return) {
            is ReconnectMode.AgentSession -> connect(list=true,autoSelect=mode.autoSelect,pinnedOnly=true,
                preferredAgent=mode.preferredAgent,backend=mode.backend,tuiSessionName=mode.tuiSession,
                keepRetainedTerminal=retainedTerminal)
            is ReconnectMode.Custom -> connect(pinnedOnly=true,keepRetainedTerminal=retainedTerminal)
        }
    }

    fun noteManualConnectionAction() { startupGate.cancelAutomatic() }

    private fun finishStartup():Boolean {
        val wasStarting=startupInFlight
        startupInFlight=false
        startupWatchdog?.cancel()
        startupWatchdog=null
        return wasStarting
    }

    fun tryStartupReconnect() {
        val profile=StartupReconnectProfile(
            prefs.getString("startup_host",null) ?: host,
            prefs.getInt("startup_port",port.trim().toIntOrNull() ?: 0),
            prefs.getString("startup_user",null) ?: user,
        )
        val endpoint=HostEndpoint.parse(profile.host,profile.port.toString())
        val pin=endpoint?.let(hostTrust::pin)
        val unverifiedReplacement=prefs.getBoolean("startup_key_needs_auth",false)
        val granted=keyRemembered && keyUri?.let {runCatching {hasReadGrant(it)}.getOrDefault(false)}==true
        val allowed=startupGate.claim(profile,granted && !unverifiedReplacement,pin)
        if(busy || terminalVisible) return
        if(allowed==null) {
            if(status=="Disconnected" && endpoint!=null && profile.user.isNotBlank()) {
                if(!granted) status="Select your SSH key to reconnect."
                else if(unverifiedReplacement) status="Connect once with the newly selected SSH key."
                else if(pin==null || !isSshSha256Pin(pin)) status="Connect manually to verify this SSH host."
            }
            return
        }
        hostState=allowed.host
        portState=allowed.port.toString()
        userState=allowed.user
        startupInFlight=true
        startupWatchdog=viewModelScope.launch {
            delay(30_000)
            if(startupInFlight) {
                val retry=reconnectTarget
                disconnect()
                reconnectTarget=retry
                reconnectOffered=retry!=null
                terminalVisible=false
                status="Automatic reconnect timed out."
            }
        }
        val backend=AgentBackend.entries.firstOrNull {
            it.name==prefs.getString("startup_backend",null)
        } ?: AgentBackend.CORE
        if(backend==AgentBackend.TUI) tuiSessionState=prefs.getString("startup_tui_session","").orEmpty()
        connect(list=true,autoSelect=true,automatic=true,backend=backend)
    }

    private fun saveRecentSession(target:ResumeTarget, addressKey:String, address:String) {
        prefs.edit().putString(addressKey,address).apply {
            if(keyRemembered) {
                putString("startup_host",target.config.host)
                putInt("startup_port",target.config.port)
                putString("startup_user",target.config.user)
                putString("startup_backend",target.backend.name)
                putString("startup_tui_session",target.tuiSession)
                remove("startup_key_needs_auth")
            }
        }.apply()
    }
    private var autoPick=false
    private var requestedAgent:String?=null
    private var attachStarted=false
    private var attachOutputSeen=false
    private var rememberCandidate: Pair<String,String>?=null
    private fun scheduleRemember(token:Long) {
        val candidate=rememberCandidate ?: return
        if(!attachStarted || !attachOutputSeen) return
        rememberCandidate=null
        val target=resume ?: return
        val startedGeneration=generation
        viewModelScope.launch {
            // Channel-open alone is not success: allow immediate command failures to clear this target.
            delay(1200)
            if(busy && generation==startedGeneration && resume===target && sshCurrent(token) && status=="Connected · SSH PTY") {
                saveRecentSession(target,candidate.first,candidate.second)
                if(finishStartup()) terminalVisible=true
            }
        }
    }
    fun cancelPicker() { pendingColdWatchTerminal=null;showSessionPicker=false;sessions=emptyList();listedConfig=null;listedBackend=AgentBackend.CORE;listedTuiSession=null;passphrase="" }
    private fun publishSessionList() {
        val backend=listedBackend
        sessions=backend.parse(listBytes.toString("UTF-8"))
        val liveCount=sessions.count { it.state=="live" }
        if(liveCount==0) {
            reconnectOffered=true
            finishStartup()
            cancelPicker()
            status="No live ${backend.label} terminals found."
            return
        }
        val cfg=listedConfig
        val endpoint=selectedEndpoint
        val remembered=if(cfg!=null && endpoint!=null) prefs.getString(backend.rememberedKey(endpoint,cfg.user,listedTuiSession),null) else null
        val automatic=startupInFlight
        val preferred=requestedAgent
        val watchTarget=pendingColdWatchTerminal
        pendingColdWatchTerminal=null
        val selection=if(watchTarget!=null) sessions.singleOrNull {it.isAgent && it.state=="live" && it.address==watchTarget}
            else if(automatic || preferred!=null) startupSession(sessions,preferred ?: remembered)
            else if(autoPick) CoreSessions.autoAttach(sessions,remembered) else null
        if(selection!=null) connect(attach=selection,automatic=automatic,backend=backend,tuiSessionName=listedTuiSession,
            keepRetainedTerminal=retainedTerminal)
        else {
            retainedTerminal=false
            clearRetainedOutput()
            finishStartup()
            terminalVisible=false
            showSessionPicker=true
            status=if(preferred!=null || (automatic && remembered!=null)) "The previous agent is not available. Choose a live ${backend.label} session."
                else "Choose a live ${backend.label} session."
        }
    }
    private var listedConfig:SshConfig?=null
    private var listedTuiSession:String?=null
    internal var listedBackend by mutableStateOf(AgentBackend.CORE)
        private set
    private var listing=false
    private val listBytes=ByteArrayOutputStream()
    private val listErrors=ByteArrayOutputStream()
    var status by mutableStateOf("Disconnected")
    var busy by mutableStateOf(false)
    var terminalVisible by mutableStateOf(false)
    var demo by mutableStateOf(false)
    var observedPin by mutableStateOf<String?>(null)
        private set
    internal var offeredTrust by mutableStateOf<FirstUseOffer?>(null)
        private set
    private data class Request(val list:Boolean,val autoPick:Boolean,val backend:AgentBackend,val tuiSession:String?)
    private data class Probe(val endpoint:HostEndpoint,val generation:Long,val resumeRequest:Request?=null)
    private var probeContext:Probe?=null
    private var offeredRequest:Request?=null
    private fun invalidateOffer() {
        offeredTrust=null;offeredRequest=null;observedPin=null
        if(probeContext!=null) {
            generation++;ssh.disconnect();busy=false;listing=false;probeContext=null
            status="Host check canceled."
        }
    }
    fun rejectHost() {invalidateOffer();passphrase="";status="Host not trusted. No authentication was sent."}
    fun trustOfferedHost() {
        val offer=offeredTrust ?: return
        val request=offeredRequest
        if(!hostTrust.accept(offer,selectedEndpoint,generation)) {
            invalidateOffer();status="Host not saved. Check its fingerprint again.";return
        }
        pinRevision++
        invalidateOffer()
        status="Host fingerprint saved on first use."
        if(request!=null) connect(list=request.list, autoSelect=request.autoPick,backend=request.backend,tuiSessionName=request.tuiSession)
    }
    var terminalFontSp by mutableFloatStateOf(prefs.getFloat("terminal_font_sp",18f).let { TerminalFont.resize(it,1f) })
        private set
    fun resizeTerminalFont(factor:Float) {
        val next=TerminalFont.resize(terminalFontSp,factor)
        if(next!=terminalFontSp) {
            terminalFontSp=next
            prefs.edit().putFloat("terminal_font_sp",next).apply()
        }
    }
    private var cols=80
    private var rows=24
    private val terminalMouse=TerminalMouse()
    private var displayedPty by mutableStateOf<Any?>(null)
    val dictationEnabled get() = speechTerminalIdentity()!=null
    internal fun sendTerminalControl(byte:Byte) {
        speechTerminalIdentity()?.let {ssh.sendToTerminal(it,byteArrayOf(byte))}
    }
    fun speechTerminalIdentity():Any? =
        displayedPty?.takeIf {!retainedTerminal && !demo && busy && terminalVisible && !showAgentSwitcher &&
            status=="Connected · SSH PTY" && ssh.terminalIdentity()===it}
    fun sendDictation(identity:Any,text:String,stillAllowed:()->Boolean,completed:(Boolean)->Unit):Boolean {
        val renderer=emulator
        if(!stillAllowed() || speechTerminalIdentity()!==identity) return false
        return renderer.postEncodedUserLine(text) { bytes ->
            completed(stillAllowed() && emulator===renderer && speechTerminalIdentity()===identity &&
                ssh.sendToTerminal(identity,bytes))
        }
    }
    internal fun sendDockKeyboardInput(identity:Any,bytes:ByteArray):Boolean =
        speechTerminalIdentity()===identity && ssh.sendToTerminal(identity,bytes)

    internal fun pasteTerminalClipboard(identity:Any,renderer:TerminalEmulator,text:String) {
        // Output from this renderer stays bound to this PTY even if delivery runs after a switch.
        if(renderer===emulator && speechTerminalIdentity()===identity && emulatorOutput.owner===identity)
            renderer.pasteText(text)
    }

    internal data class PhysicalWheelRoute(val identity:Any,val modeRevision:Long,val remote:Boolean)
    internal fun physicalWheelRoute():PhysicalWheelRoute? {
        if(demo && terminalVisible && !retainedTerminal) return PhysicalWheelRoute(emulator,0,false)
        return speechTerminalIdentity()?.let {PhysicalWheelRoute(it,terminalMouse.touchModeRevision,terminalMouse.wheelEnabled)}
    }

    fun remoteWheelIdentity():Any? = speechTerminalIdentity()?.takeIf {terminalMouse.wheelEnabled}
    fun sendRemoteWheel(identity:Any,up:Boolean,x:Float,y:Float,width:Int,height:Int):Boolean {
        if(remoteWheelIdentity()!==identity || width<=0 || height<=0 || !x.isFinite() || !y.isFinite()) return false
        val col=(x.coerceIn(0f,width.toFloat())/width*cols).toInt().coerceIn(0,cols-1)+1
        val row=(y.coerceIn(0f,height.toFloat())/height*rows).toInt().coerceIn(0,rows-1)+1
        val packet=terminalMouse.wheel(if(up) TerminalMouse.Direction.UP else TerminalMouse.Direction.DOWN,col,row) ?: return false
        return ssh.sendToTerminal(identity,packet)
    }
    internal fun remoteClickTarget():RemoteClickTarget? =
        speechTerminalIdentity()?.takeIf {terminalMouse.clickEnabled}
            ?.let { RemoteClickTarget(it,terminalMouse.touchModeRevision) }

    internal fun terminalLinkRoute(renderer:TerminalEmulator):RemoteClickTarget? =
        speechTerminalIdentity()?.takeIf { renderer===emulator && offeredTrust==null }
            ?.let { RemoteClickTarget(it,terminalMouse.touchModeRevision) }

    internal fun sendRemoteClick(target:RemoteClickTarget,cell:TerminalCell):Boolean {
        if(!target.matches(remoteClickTarget())) return false
        val packet=terminalMouse.click(cell.column,cell.row) ?: return false
        return ssh.sendToTerminal(target.pty,packet)
    }

    private fun renderRemote(bytes:ByteArray) {
        terminalMouse.observe(bytes)
        emulator.writeInput(bytes)
    }
    private var generation=0L
    private data class ResumeTarget(val config:SshConfig,val key:ByteArray,val passphrase:ByteArray?,var command:String,var address:String,val backend:AgentBackend,val tuiSession:String?,var retries:Int=0) {
        fun clear() {key.fill(0);passphrase?.fill(0)}
    }
    internal val rosterAvatarScope:AgentAvatarScope?
        get()=resume?.let {target ->
            AgentAvatarScope(target.backend.name,target.config.host,target.config.port,target.config.user,target.tuiSession)
        }

    internal var watchNotice by mutableStateOf<String?>(null)
        private set
    private var pendingWatchTerminal:AgentWatchAlertTarget?=null
    private var pendingColdWatchTerminal:String?=null
    private var watchPermissionScope:AgentWatchScope?=null
    internal val canStartAgentWatch get()=canSwitchAgents && activeWatchScope()!=null
    private fun activeWatchScope():AgentWatchScope? = resume?.takeIf {
        it.backend==AgentBackend.TUI && isSshSha256Pin(it.config.expectedPin.orEmpty())
    }?.let {AgentWatchScope(it.config,normalizedWatchSession(it.tuiSession))}

    internal fun clearWatchNotice() {watchNotice=null}

    internal fun prepareAgentWatchPermission():Boolean {
        watchPermissionScope=activeWatchScope().takeIf {canStartAgentWatch}
        return watchPermissionScope!=null
    }

    internal fun finishAgentWatchPermission(activity:MainActivity,granted:Boolean) {
        val expected=watchPermissionScope
        watchPermissionScope=null
        if(!granted) notificationPermissionDeclined()
        else if(expected!=null && expected==activeWatchScope()) startAgentWatch(activity)
        else watchNotice="The Herdr connection changed. Turn on agent alerts again from the new connection."
    }

    internal fun startAgentWatch(activity:MainActivity) {
        val target=resume
        if(target==null || !canStartAgentWatch) {
            watchNotice="Connect to a Herdr TUI terminal before turning on agent alerts."
            return
        }
        watchNotice=if(AgentWatchService.startFromVisible(activity,target.config,target.tuiSession,target.key,target.passphrase)) null
            else "Could not start agent alerts. Check that Pocket notifications are allowed."
    }

    internal fun notificationPermissionDeclined() {
        watchNotice="Allow Pocket notifications to turn on agent alerts."
    }

    internal fun expiredWatchAlert() {
        noteManualConnectionAction()
        watchNotice="That agent alert is no longer available. Open the agent from your Herdr session."
    }

    internal fun openWatchedAgent(target:AgentWatchAlertTarget) {
        noteManualConnectionAction()
        pendingWatchTerminal=null
        val liveScope=activeWatchScope()
        if(canSwitchAgents && liveScope==target.scope && !switchBusy && renamingAgent==null) {
            if(filesUi?.let {it.downloading || it.uploading || it.deleting || it.creatingFolder}==true) {
                watchNotice="Finish or cancel the file transfer before switching agents."
                return
            }
            dismissAgentMenu()
            terminalVisible=true
            if(!showAgentSwitcher) {
                openAgentSwitcher()
                if(!rosterRefresh.active) {
                    watchNotice="Could not refresh this agent’s session. Your terminal is unchanged."
                    return
                }
            } else if(!refreshAgents()) {
                watchNotice="Could not refresh this agent’s session. Your terminal is unchanged."
                return
            }
            pendingWatchTerminal=target
            return
        }
        val endpoint=selectedEndpoint
        val profile=target.scope.config
        // Never silently switch hosts, usernames, pins, or named Herdr sessions.
        if(!busy && endpoint?.host==profile.host && endpoint.port==profile.port &&
            user.trim()==profile.user && hostTrust.pin(endpoint)==profile.expectedPin &&
            normalizedWatchSession(tuiSession)==target.scope.namedSession && keyRemembered) {
            connect(list=true,preferredAgent=target.terminalId,pinnedOnly=true,backend=AgentBackend.TUI,
                tuiSessionName=tuiSession.trim().ifBlank {null})
            if(listing && listedConfig==profile) pendingColdWatchTerminal=target.terminalId
        } else {
            watchNotice="This alert is for a different or unavailable Herdr connection. Connect to that session to open the agent."
        }
    }

    private fun consumeWatchedAgent() {
        val target=pendingWatchTerminal ?: return
        pendingWatchTerminal=null
        val exact=switchSessions.singleOrNull {it.isAgent && it.address==target.terminalId}
        if(activeWatchScope()==target.scope && exact!=null && showAgentSwitcher) chooseAgent(exact)
        else switchMessage="That agent is no longer available in this Herdr session."
    }

    var activeCoreAddress by mutableStateOf<String?>(null)
        private set
    var showAgentSwitcher by mutableStateOf(false)
        private set
    var switchSessions by mutableStateOf<List<CoreSession>>(emptyList())
        private set
    private data class CoreShellCreate(val name:String,val readingRoster:Boolean=false)
    private var coreShellCreate:CoreShellCreate?=null
    private var switchingNewTerminal=false
    internal var renamingAgent by mutableStateOf<CoreSession?>(null)
        private set
    private var tuiCanCreate=false
    private var tuiRosterFresh=false
    private var nextRosterNotice:String?=null
    private var interruptedRosterNotice:String?=null
    private sealed interface TuiRosterWork {
        data class VerifyRename(val location:TuiLocation,val label:String):TuiRosterWork
        data class Rename(val location:TuiLocation,val label:String):TuiRosterWork
        data class VerifyCreate(val anchor:TuiLocation):TuiRosterWork
        data class CreateTab(val workspace:String):TuiRosterWork
        data class VerifyNewPane(val location:TuiLocation):TuiRosterWork
    }
    private var tuiWork:TuiRosterWork?=null
    var switchMessage by mutableStateOf("")
        private set
    private var switchOperation by mutableStateOf<Long?>(null)
    val switchBusy get() = switchOperation!=null
    private var rosterRefresh by mutableStateOf(AgentRosterRefresh())
    val isRefreshingAgents get()=rosterRefresh.active
    val canRefreshAgents get()=canRefreshAgentRoster(
        visible=showAgentSwitcher,connected=canSwitchAgents,hasAgentTarget=resume!=null,
        operationPending=switchBusy,dialogOpen=renamingAgent!=null,
    )
    private var switchCandidate:CoreSession?=null
    val canSwitchAgents get() = activeCoreAddress!=null && busy && status=="Connected · SSH PTY"

    val canCreateAgent get() = resume!=null
    val switchingTuiAgents get() = resume?.backend==AgentBackend.TUI

    internal var agentMenuOpen by mutableStateOf(false)
        private set
    internal var filesUi by mutableStateOf<AgentFilesUiState?>(null)
        private set
    private class FilesRoute(val pty: Any, val address: String, val session: String?)
    private data class FilesRead(val parts: List<String>, val preview: Boolean = false)
    private var filesRoute: FilesRoute? = null
    private var filesLookup: Long? = null
    private var filesRequest: SshSession.FileRequest? = null
    private var filesRoot: AgentFilesRoot? = null
    private var filesFolder = emptyList<String>()
    private var filesLastRead = FilesRead(emptyList())
    private var downloadableFile: FilesRead? = null
    private class FileDownload(
        val route:FilesRoute,
        val root:String,
        val file:FilesRead,
    ) {
        val gate=FileSaveGate()
        var request:SshSession.FileRequest?=null
        var stage:File?=null
        var target:Uri?=null
        var copying=false
        var cleanupStarted=false
    }
    private var downloadPicker:FileDownload?=null
    private var activeDownload:FileDownload?=null
    private class FileUpload(val route:FilesRoute,val anchor:FilesUploadAnchor) {
        var request:SshSession.FileRequest?=null
        var foregroundLease:Long?=null
        var pickerLaunched=false
    }
    // Keep the picker slot until Android answers, even if Files was closed meanwhile.
    private var uploadPicker:FileUpload?=null
    private var activeUpload:FileUpload?=null
    internal var filesUploadNotice by mutableStateOf<FilesUploadInterruption?>(null)
        private set

    internal fun clearFilesUploadNotice() {filesUploadNotice=null}

    private class FilesDelete(val route:FilesRoute,val anchor:FilesUploadAnchor,
        val entry:AgentFileEntry,val prompt:AgentFileDeletePrompt)
    private var pendingFilesDelete:FilesDelete?=null
    private var filesDeleteRequest:SshSession.FileRequest?=null

    private class FilesMkdir(val route:FilesRoute,val anchor:FilesUploadAnchor,val prompt:AgentNewFolderPrompt)
    private var pendingFilesMkdir:FilesMkdir?=null
    private var filesMkdirRequest:SshSession.FileRequest?=null

    internal fun openAgentMenu() {
        if (canSwitchAgents && terminalVisible && !showAgentSwitcher) agentMenuOpen = true
    }

    internal fun dismissAgentMenu() {
        closeFiles()
        agentMenuOpen = false
    }

    internal fun openAgentFiles() {
        if (!agentMenuOpen || filesUi != null) return
        val target = resume
        val address = activeCoreAddress
        val pty = speechTerminalIdentity()
        if (target?.backend != AgentBackend.TUI || address == null || pty == null) {
            filesUi = AgentFilesUiState(error = "Files needs a connected Herdr TUI terminal.", canRetry = false)
            return
        }
        filesRoute = FilesRoute(pty, address, target.tuiSession)
        filesUi = AgentFilesUiState(loading = true)
        resolveFilesRoot()
    }

    private fun currentFilesRoute(route: FilesRoute): Boolean =
        filesRoute === route && activeCoreAddress == route.address && displayedPty === route.pty &&
            ssh.terminalIdentity() === route.pty && resume?.backend == AgentBackend.TUI &&
            resume?.tuiSession == route.session

    private fun resolveFilesRoot() {
        val route = filesRoute ?: return
        if (!currentFilesRoute(route)) return
        filesLookup = ssh.listOnTransport(TuiAgents.listCommand(route.session))
        filesUi = filesUi?.copy(loading = filesLookup != null, error =
            if (filesLookup == null) "The connection is busy. Try again." else null)
    }

    private fun acceptFilesRoot(event: SshSession.Event.OperationList) {
        filesLookup = null
        val route = filesRoute ?: return
        if (!currentFilesRoute(route)) return
        val root = if (event.exitStatus == 0) runCatching {
            AgentFiles.root(event.stdout.toString(Charsets.UTF_8), route.address)
        }.getOrNull() else null
        if (root == null) {
            filesUi = filesUi?.copy(loading = false, error = "Herdr did not report a working folder for this terminal.")
            return
        }
        filesRoot = root
        fetchFiles(FilesRead(emptyList()), canonical = false)
    }

    private fun fetchFiles(target: FilesRead, canonical: Boolean = filesUi?.folder != null) {
        val route = filesRoute ?: return
        val root = filesRoot ?: return
        if (!currentFilesRoute(route) || filesRequest != null) return
        filesLastRead = target
        downloadableFile = null
        filesUi = filesUi?.copy(loading = true, error = null, preview = null, image = null,
            unrenderableName = null, downloadMessage = null)
        filesRequest = ssh.readFiles(route.pty, root.path, target.parts, target.preview, canonical) { request, result ->
            val image=(result as? SftpRead.Image)?.let {decodeAgentFileImage(it.bytes)}
            main.post {
                if (!currentFilesRoute(route) || filesRequest !== request) return@post
                filesRequest = null
                val ui = filesUi ?: return@post
                filesUi = when (result) {
                    is SftpRead.Failure -> ui.copy(loading = false, error = result.message)
                    is SftpRead.Folder -> {
                        filesRoot = root.copy(path = result.root)
                        filesFolder = target.parts
                        ui.copy(folder = result.path, entries = result.entries.map {
                            AgentFileEntry(it.name, it.directory, it.directory || it.regular, it.identity)
                        }, loading = false, canGoBack = target.parts.isNotEmpty(),
                            workspaceFallback = root.workspaceFallback)
                    }
                    is SftpRead.Preview -> {
                        downloadableFile = target
                        ui.copy(loading = false,
                            preview = AgentFilePreview(target.parts.last(), result.text, result.truncated))
                    }
                    is SftpRead.Image -> {
                        downloadableFile=target
                        val name=target.parts.last()
                        if(image==null) ui.copy(loading=false,unrenderableName=name)
                        else ui.copy(loading=false,image=AgentFileImagePreview(name,image))
                    }
                    SftpRead.Unrenderable -> {
                        downloadableFile = target
                        ui.copy(loading = false, unrenderableName = target.parts.last())
                    }
                }
            }
        }
        if (filesRequest == null) filesUi = filesUi?.copy(loading = false, error = "The connection is busy. Try again.")
    }

    internal fun openFilesEntry(entry: AgentFileEntry) {
        val ui = filesUi ?: return
        if (ui.loading || ui.uploading || ui.deleting || pendingFilesDelete!=null || pendingFilesMkdir!=null ||
            downloadableFile != null || entry !in ui.entries || !entry.canOpen) return
        filesUi=ui.copy(uploadMessage=null,deleteMessage=null,folderMessage=null)
        fetchFiles(FilesRead(filesFolder + entry.name, preview = !entry.isDirectory))
    }

    private fun currentDownload(download:FileDownload):Boolean =
        activeDownload===download && download.gate.canWrite && currentFilesRoute(download.route) &&
            filesRoot?.path==download.root && downloadableFile===download.file

    internal fun prepareFileSave():Intent? {
        val route=filesRoute ?: return null
        val root=filesRoot ?: return null
        val selection=downloadableFile ?: return null
        val name=selection.parts.lastOrNull() ?: return null
        val ui=filesUi ?: return null
        if(downloadPicker!=null || activeDownload!=null || !currentFilesRoute(route) ||
            filesLastRead!==selection || !selection.preview ||
            name!=(ui.preview?.name ?: ui.image?.name ?: ui.unrenderableName)) return null
        val download=FileDownload(route,root.path,selection)
        activeDownload=download
        downloadPicker=download
        filesUi=filesUi?.copy(downloading=true,downloadMessage=null)
        val extension=name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        return Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime).putExtra(Intent.EXTRA_TITLE,name)
    }

    internal fun fileSavePickerFailed() {
        val download=downloadPicker ?: return
        downloadPicker=null
        endDownload(download,"Could not open Android’s save picker.",removeTarget=false)
    }

    internal fun onFileSavePicked(uri:Uri?) {
        val download=downloadPicker ?: return
        downloadPicker=null
        download.target=uri?.takeIf {it.scheme=="content"}
        if(!currentDownload(download)) {
            endDownload(download,null)
            return
        }
        if(download.target==null) {
            endDownload(download,if(uri==null) null else "Android did not return a writable document.",removeTarget=false)
            return
        }
        val stage=runCatching {File.createTempFile("herdr-export-", ".tmp", getApplication<Application>().cacheDir)}.getOrNull()
        if(stage==null) {
            endDownload(download,"Could not prepare the download.")
            return
        }
        download.stage=stage
        download.request=ssh.downloadFile(download.route.pty,download.root,download.file.parts,stage) { request,result ->
            main.post {
                if(!currentDownload(download) || download.request!==request) {
                    stage.delete()
                    endDownload(download,null)
                    return@post
                }
                download.request=null
                when(result) {
                    is SftpDownload.Failure -> endDownload(download,result.message)
                    is SftpDownload.Success -> saveStagedDownload(download,stage,result.bytes)
                }
            }
        }
        if(download.request==null) endDownload(download,"The connection is busy. Try again.")
    }

    private fun saveStagedDownload(download:FileDownload,stage:File,bytes:Long) {
        val target=download.target ?: return endDownload(download,"Android did not return a writable document.")
        download.copying=true
        Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext,Runnable {
            fun checkRoute() {
                if(!download.gate.canWrite || ssh.terminalIdentity()!==download.route.pty) throw IOException("Download canceled")
            }
            var failure:String?=null
            try {
                checkRoute()
                if(stage.length()!=bytes) throw IOException("Incomplete staged file")
                stage.inputStream().use { input ->
                    val output=resolver.openOutputStream(target,"wt") ?: throw IOException("Document cannot be opened")
                    output.use {
                        val buffer=ByteArray(64*1024)
                        var copied=0L
                        while(true) {
                            checkRoute()
                            val count=input.read(buffer)
                            if(count<0) break
                            output.write(buffer,0,count)
                            copied+=count
                        }
                        if(copied!=bytes) throw IOException("Incomplete save")
                        output.flush()
                    }
                }
                checkRoute()
                if(!download.gate.commit()) throw IOException("Download canceled")
            } catch (_:Exception) {
                failure="Could not save the download."
            } finally {
                stage.delete()
                // Do not tie this worker to ViewModel coroutine cancellation: it
                // must close and clean up a provider already opened by the save.
                main.post {
                    download.copying=false
                    endDownload(download,failure ?: "File saved.",removeTarget=!download.gate.committed)
                }
            }
        })
    }

    private fun endDownload(download:FileDownload,message:String?,removeTarget:Boolean=true) {
        val wasCurrent=activeDownload===download
        if(wasCurrent) {
            activeDownload=null
            filesUi=filesUi?.copy(downloading=false,downloadMessage=message)
        }
        if(!download.gate.committed) download.gate.cancel()
        download.request?.let(ssh::cancelFiles)
        download.request=null
        if(download.copying) return // The writer closes the document before cleanup.
        download.stage?.delete()
        download.stage=null
        val target=download.target?.takeIf {removeTarget && !download.gate.committed}
        if(target!=null && !download.cleanupStarted) {
            download.cleanupStarted=true
            // ACTION_CREATE_DOCUMENT returns a new document, never an existing file to overwrite.
            // A provider may refuse deletion; make that limitation visible in the current preview.
            Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext,Runnable {
                val removed=runCatching {
                    DocumentsContract.isDocumentUri(getApplication<Application>(),target) &&
                        DocumentsContract.deleteDocument(resolver,target)
                }.getOrDefault(false)
                if(!removed && wasCurrent && message!=null) main.post {
                    val ui=filesUi
                    if(currentFilesRoute(download.route) && downloadableFile===download.file && ui?.downloadMessage==message)
                        filesUi=ui.copy(downloadMessage="$message The destination may contain an incomplete file.")
                }
            })
        }
    }

    private fun currentUpload(upload:FileUpload):Boolean =
        activeUpload===upload && currentFilesRoute(upload.route) &&
            upload.anchor.matches(filesRoot?.path,filesFolder,filesUi?.folder)

    private fun prepareFileUpload():Boolean {
        val route=filesRoute ?: return false
        val root=filesRoot ?: return false
        val ui=filesUi ?: return false
        val folder=ui.folder ?: return false
        if(uploadPicker!=null || activeUpload!=null || activeDownload!=null || !currentFilesRoute(route) ||
            filesRequest!=null || ui.loading || ui.deleting || pendingFilesDelete!=null || pendingFilesMkdir!=null || ui.error!=null || downloadableFile!=null) return false
        val upload=FileUpload(route,FilesUploadAnchor(root.path,filesFolder.toList(),folder))
        activeUpload=upload
        uploadPicker=upload
        filesUi=ui.copy(uploading=true,uploadMessage=null,deleteMessage=null,folderMessage=null)
        return true
    }

    internal fun startProtectedFileUpload(activity:ComponentActivity,launchPicker:()->Unit) {
        if(!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            !prepareFileUpload()) return
        val upload=checkNotNull(activeUpload)
        upload.foregroundLease=UploadForegroundService.start(activity,onReady={
            if(!currentUpload(upload) || activity.isFinishing || activity.isDestroyed ||
                !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                endUpload(upload,null)
            } else {
                upload.pickerLaunched=true
                runCatching {launchPicker()}.onFailure {
                    upload.pickerLaunched=false
                    endUpload(upload,"Could not open Android’s file picker.")
                }
            }
        },onFailure={
            if(currentUpload(upload)) endUpload(upload,when {
                upload.request!=null -> "Upload protection stopped. Reopen this folder to check whether the file was saved."
                upload.pickerLaunched -> "Upload protection stopped before the file was sent. Try again."
                else -> "Android could not keep Pocket connected for the file picker. Try again."
            }) else endUpload(upload,null)
        })
        if(upload.foregroundLease==null)
            endUpload(upload,"Android could not keep Pocket connected for the file picker. Try again.")
    }

    internal fun onFileUploadPicked(uri:Uri?) {
        val upload=uploadPicker ?: run {
            // Android can restore the picker result after killing Pocket's process.
            // The old route/authorization isn't restorable: never send to a fresh connection.
            FilesUploadInterruption.onPickerWithoutRoute(uri!=null)?.let {filesUploadNotice=it}
            return
        }
        uploadPicker=null
        if(!currentUpload(upload) || uri==null) {
            endUpload(upload,null)
            return
        }
        if(upload.foregroundLease?.let(UploadForegroundService::isValid)!=true) {
            endUpload(upload,"Upload protection expired before the file was sent. Try again.")
            return
        }
        if(uri.scheme!="content") {
            endUpload(upload,"Android did not return a readable document.")
            return
        }
        viewModelScope.launch {
            val name=withContext(Dispatchers.IO) {
                runCatching {
                    val display=android.provider.OpenableColumns.DISPLAY_NAME
                    resolver.query(uri,arrayOf(display),null,null,null)?.use { cursor ->
                        val index=cursor.getColumnIndex(display)
                        if(index>=0 && cursor.moveToFirst()) cursor.getString(index) else null
                    }
                }.getOrNull()
            }
            if(!currentUpload(upload)) {
                endUpload(upload,null)
                return@launch
            }
            if(upload.foregroundLease?.let(UploadForegroundService::isValid)!=true) {
                endUpload(upload,"Upload protection expired before the file was sent. Try again.")
                return@launch
            }
            if(name==null || !AgentFiles.entryName(name)) {
                endUpload(upload,"This document does not have a usable file name.")
                return@launch
            }
            upload.request=ssh.uploadFile(upload.route.pty,upload.anchor.root,upload.anchor.parts,name,
                source={resolver.openInputStream(uri) ?: throw FileNotFoundException("Unreadable document")}) { request,result ->
                main.post {
                    if(!currentUpload(upload) || upload.request!==request) return@post
                    upload.request=null
                    when(result) {
                        is SftpUpload.Failure -> endUpload(upload,result.message)
                        is SftpUpload.Success -> {
                            endUpload(upload,"File uploaded.")
                            fetchFiles(FilesRead(upload.anchor.parts))
                        }
                    }
                }
            }
            if(upload.request==null) endUpload(upload,"The connection is busy. Try again.")
        }
    }

    private fun endUpload(upload:FileUpload,message:String?) {
        if(uploadPicker===upload && !upload.pickerLaunched) uploadPicker=null
        upload.foregroundLease?.let {UploadForegroundService.release(getApplication<Application>(),it)}
        upload.foregroundLease=null
        if(activeUpload===upload) {
            activeUpload=null
            filesUi=filesUi?.copy(uploading=false,uploadMessage=message)
        }
        upload.request?.let(ssh::cancelFiles)
        upload.request=null
    }

    internal fun askDeleteFilesEntry(entry:AgentFileEntry) {
        val ui=filesUi ?: return
        val route=filesRoute ?: return
        val root=filesRoot ?: return
        val folder=ui.folder ?: return
        if(!currentFilesRoute(route) || ui.loading || ui.error!=null || ui.uploading || ui.downloading ||
            ui.deleting || pendingFilesDelete!=null || pendingFilesMkdir!=null || downloadableFile!=null ||
            entry !in ui.entries || entry.isDirectory || !entry.canOpen) return
        val prompt=AgentFileDeletePrompt(entry.name,folder)
        pendingFilesDelete=FilesDelete(route,FilesUploadAnchor(root.path,filesFolder.toList(),folder),entry,prompt)
        filesUi=ui.copy(deletePrompt=prompt,deleteMessage=null,folderMessage=null,uploadMessage=null)
    }

    internal fun cancelFilesDelete(prompt:AgentFileDeletePrompt) {
        if(pendingFilesDelete?.prompt!==prompt || filesDeleteRequest!=null) return
        pendingFilesDelete=null
        filesUi=filesUi?.copy(deletePrompt=null)
    }

    internal fun confirmFilesDelete(prompt:AgentFileDeletePrompt) {
        val target=pendingFilesDelete ?: return
        val ui=filesUi ?: return
        if(target.prompt!==prompt || filesDeleteRequest!=null) return
        if(!currentFilesRoute(target.route) || !target.anchor.matches(filesRoot?.path,filesFolder,ui.folder) ||
            ui.loading || ui.error!=null || downloadableFile!=null || target.entry !in ui.entries) {
            cancelFilesDelete(prompt)
            return
        }
        filesUi=ui.copy(deletePrompt=null,deleting=true,deleteMessage=null)
        filesDeleteRequest=ssh.deleteFile(target.route.pty,target.anchor.root,target.anchor.parts,target.entry.name,
            expected=target.entry.identity) { request,result ->
            main.post {
                if(filesDeleteRequest!==request || pendingFilesDelete!==target ||
                    !currentFilesRoute(target.route) ||
                    !target.anchor.matches(filesRoot?.path,filesFolder,filesUi?.folder)) return@post
                filesDeleteRequest=null
                pendingFilesDelete=null
                when(result) {
                    SftpDelete.Success -> {
                        filesUi=filesUi?.copy(deleting=false,deleteMessage="File deleted.")
                        fetchFiles(FilesRead(target.anchor.parts))
                    }
                    is SftpDelete.Failure -> filesUi=filesUi?.copy(deleting=false,deleteMessage=result.message)
                }
            }
        }
        if(filesDeleteRequest==null) {
            pendingFilesDelete=null
            filesUi=filesUi?.copy(deleting=false,deleteMessage="Could not start the deletion. Try again.")
        }
    }

    internal fun askNewFilesFolder() {
        val ui=filesUi ?: return
        val route=filesRoute ?: return
        val root=filesRoot ?: return
        val folder=ui.folder ?: return
        if(!currentFilesRoute(route) || ui.loading || ui.error!=null || ui.uploading || ui.downloading ||
            ui.deleting || pendingFilesDelete!=null || pendingFilesMkdir!=null || downloadableFile!=null) return
        val prompt=AgentNewFolderPrompt(folder)
        pendingFilesMkdir=FilesMkdir(route,FilesUploadAnchor(root.path,filesFolder.toList(),folder),prompt)
        filesUi=ui.copy(newFolderPrompt=prompt,folderMessage=null,deleteMessage=null,uploadMessage=null)
    }

    internal fun cancelNewFilesFolder(prompt:AgentNewFolderPrompt) {
        if(pendingFilesMkdir?.prompt!==prompt || filesMkdirRequest!=null) return
        pendingFilesMkdir=null
        filesUi=filesUi?.copy(newFolderPrompt=null)
    }

    internal fun confirmNewFilesFolder(prompt:AgentNewFolderPrompt,name:String) {
        val target=pendingFilesMkdir ?: return
        val ui=filesUi ?: return
        if(target.prompt!==prompt || filesMkdirRequest!=null || !AgentFiles.newFolderName(name)) return
        if(!currentFilesRoute(target.route) || !target.anchor.matches(filesRoot?.path,filesFolder,ui.folder) ||
            ui.loading || ui.error!=null || downloadableFile!=null) {
            cancelNewFilesFolder(prompt)
            return
        }
        filesUi=ui.copy(newFolderPrompt=null,creatingFolder=true,folderMessage=null)
        filesMkdirRequest=ssh.createFolder(target.route.pty,target.anchor.root,target.anchor.parts,name) { request,result ->
            main.post {
                if(filesMkdirRequest!==request || pendingFilesMkdir!==target ||
                    !currentFilesRoute(target.route) ||
                    !target.anchor.matches(filesRoot?.path,filesFolder,filesUi?.folder)) return@post
                filesMkdirRequest=null
                pendingFilesMkdir=null
                when(result) {
                    SftpMkdir.Success -> {
                        filesUi=filesUi?.copy(creatingFolder=false,folderMessage="Folder created.")
                        fetchFiles(FilesRead(target.anchor.parts))
                    }
                    is SftpMkdir.Failure -> filesUi=filesUi?.copy(creatingFolder=false,folderMessage=result.message)
                }
            }
        }
        if(filesMkdirRequest==null) {
            pendingFilesMkdir=null
            filesUi=filesUi?.copy(creatingFolder=false,folderMessage="Could not create the folder. Try again.")
        }
    }

    internal fun retryFiles() {
        if (filesUi?.loading != false || filesRoute == null) return
        if (filesRoot == null) resolveFilesRoot() else fetchFiles(filesLastRead)
    }

    internal fun backInFiles() {
        if(filesDeleteRequest!=null || filesMkdirRequest!=null) return
        pendingFilesMkdir?.let {cancelNewFilesFolder(it.prompt);return}
        pendingFilesDelete?.let {cancelFilesDelete(it.prompt);return}
        activeUpload?.let {
            val transferring=it.request!=null
            endUpload(it,if(transferring) "Upload interrupted. Reopen this folder to check whether it finished." else null)
            return
        }
        activeDownload?.let {endDownload(it,null)}
        val ui = filesUi ?: return
        when (filesBackAction(ui.folder != null, filesFolder.isNotEmpty(), ui.loading, downloadableFile != null || ui.error != null)) {
            FilesBackAction.MENU -> closeFiles()
            FilesBackAction.CANCEL_READ -> {
                filesRequest?.let(ssh::cancelFiles)
                filesRequest = null
                filesUi = ui.copy(loading = false)
            }
            FilesBackAction.FOLDER_CONTENTS -> {
                downloadableFile = null
                filesUi = ui.copy(preview = null, image = null, unrenderableName = null, error = null, downloadMessage = null)
            }
            FilesBackAction.PARENT -> {
                filesUi=ui.copy(uploadMessage=null,deleteMessage=null,folderMessage=null)
                fetchFiles(FilesRead(filesFolder.dropLast(1)))
            }
        }
    }

    private fun closeFiles() {
        filesMkdirRequest?.let(ssh::cancelFiles)
        filesMkdirRequest=null
        pendingFilesMkdir=null
        filesDeleteRequest?.let(ssh::cancelFiles)
        filesDeleteRequest=null
        pendingFilesDelete=null
        activeUpload?.let {endUpload(it,null)}
        activeDownload?.let {endDownload(it,null)}
        filesLookup?.let(ssh::cancelOperation)
        filesRequest?.let(ssh::cancelFiles)
        filesRequest = null
        filesLookup = null
        filesUi = null
        filesRoute = null
        filesRoot = null
        filesFolder = emptyList()
        filesLastRead = FilesRead(emptyList())
        downloadableFile = null
    }

    fun openAgentSwitcher() {
        if(!canSwitchAgents || showAgentSwitcher) return
        showAgentSwitcher=true
        refreshAgents()
    }

    fun refreshAgents():Boolean {
        if(!canRefreshAgents) return false
        val target=resume ?: return false
        switchCandidate=null
        if(target.backend==AgentBackend.TUI) {
            tuiRosterFresh=false
            tuiCanCreate=false
        }
        val id=ssh.listOnTransport(target.backend.listCommand(target.tuiSession))
        if(id==null) {
            switchMessage="The connection is not ready. Try again."
            return false
        }
        switchMessage=""
        switchOperation=id
        rosterRefresh=AgentRosterRefresh(id)
        return true
    }

    fun chooseAgent(session:CoreSession) {
        if(!showAgentSwitcher || switchBusy) return
        when(agentSelection(session,switchSessions,activeCoreAddress.takeIf {canSwitchAgents},
            displayedPty,ssh.terminalIdentity())) {
            AgentSelection.RESUME_CURRENT -> {
                // Already attached: just dismiss the picker. Keep the PTY and emulator.
                clearAgentSwitcher()
                return
            }
            AgentSelection.UNAVAILABLE -> {
                switchMessage="That session is not available. Refresh the list and try again."
                return
            }
            AgentSelection.SWITCH -> Unit
        }
        startTerminalSwitch(session)
    }

    private fun startTerminalSwitch(session:CoreSession,newlyCreated:Boolean=false) {
        val target=resume ?: return
        switchCandidate=session
        switchingNewTerminal=newlyCreated
        switchMessage="Opening ${session.name}…"
        switchOperation=ssh.switchPty(target.backend.switchCommand(session.address,target.tuiSession),
            cols,rows,tui=target.backend==AgentBackend.TUI)
        if(switchOperation==null) {
            switchCandidate=null
            switchingNewTerminal=false
            switchMessage=if(newlyCreated) "The shell was created but did not open. Refresh to find it. Your current terminal is unchanged."
                else "Could not start the switch. Your current terminal is unchanged."
        }
    }

    fun createPlainTerminal() {
        if(!showAgentSwitcher || !canSwitchAgents || switchBusy || renamingAgent!=null) return
        val target=resume ?: return
        if(target.backend==AgentBackend.TUI) {
            val loc=switchSessions.firstOrNull {it.address==activeCoreAddress}?.tuiLocation
            if(!tuiCanCreate || !tuiRosterFresh || loc==null) {
                switchMessage="Creating a shell from Pocket needs Herdr 0.7.5–0.9.x and a current workspace."
                return
            }
            beginTuiWork(TuiRosterWork.VerifyCreate(loc),TuiMutations.inspectPane(loc,target.tuiSession),
                "Checking the current workspace…","Could not confirm the workspace. Nothing was created.")
        } else {
            val name="shell-"+java.util.UUID.randomUUID().toString().replace("-","").take(20)
            coreShellCreate=CoreShellCreate(name)
            switchMessage="Creating a plain shell on the SSH host…"
            switchOperation=ssh.listOnTransport(CoreSessions.createShellCommand(name))
            if(switchOperation==null) {
                coreShellCreate=null
                switchMessage="Could not send the request. Nothing was created."
            }
        }
    }

    private fun didCoreShellWork(event:SshSession.Event.OperationList,work:CoreShellCreate) {
        coreShellCreate=null
        if(resume?.backend!=AgentBackend.CORE) return
        if(!work.readingRoster && event.exitStatus==0) {
            coreShellCreate=work.copy(readingRoster=true)
            switchOperation=ssh.listOnTransport(CoreSessions.listCommand())
            if(switchOperation!=null) return
            coreShellCreate=null
        } else if(work.readingRoster && event.exitStatus==0) {
            val roster=runCatching { CoreSessions.parse(event.stdout.toString(Charsets.UTF_8)) }.getOrNull()
            val shell=roster?.let { CoreSessions.createdShell(it,work.name) }
            if(shell!=null && shell.address!=activeCoreAddress && switchSessions.none {it.address==shell.address}) {
                switchSessions=roster.filter {it.state=="live"}.distinctBy {it.address}
                startTerminalSwitch(shell,newlyCreated=true)
                return
            }
        }
        switchMessage="Could not confirm the new shell. Refresh before trying again. Your current terminal is unchanged."
    }

    internal fun askToRename(session:CoreSession) {
        if(showAgentSwitcher && switchingTuiAgents && canSwitchAgents && !switchBusy && tuiRosterFresh &&
            session in switchSessions && session.tuiLocation!=null) renamingAgent=session
    }

    internal fun cancelRename() {renamingAgent=null}

    internal fun renameAgent(label:String) {
        val target=resume?.takeIf {it.backend==AgentBackend.TUI} ?: return
        val selected=renamingAgent ?: return
        val location=selected.tuiLocation ?: return
        if(!showAgentSwitcher || !canSwitchAgents || switchBusy || selected !in switchSessions ||
            location.terminalId!=selected.address || !TuiMutations.canRename(label)) return
        renamingAgent=null
        beginTuiWork(TuiRosterWork.VerifyRename(location,label),TuiMutations.inspectPane(location,target.tuiSession),
            "Checking the selected pane…","Could not check the pane. Nothing was changed.")
    }

    private fun beginTuiWork(work:TuiRosterWork,command:String,statusText:String,notSent:String) {
        tuiWork=work
        switchMessage=statusText
        switchOperation=ssh.listOnTransport(command)
        if(switchOperation==null) {
            tuiWork=null
            switchMessage=notSent
        }
    }

    private fun didTuiWork(event:SshSession.Event.OperationList,work:TuiRosterWork) {
        tuiWork=null
        val target=resume?.takeIf {it.backend==AgentBackend.TUI} ?: return
        val text=event.stdout.toString(Charsets.UTF_8)
        val ok=event.exitStatus==0
        when(work) {
            is TuiRosterWork.VerifyRename -> if(ok && TuiMutations.confirmsPane(text,work.location)) {
                beginTuiWork(TuiRosterWork.Rename(work.location,work.label),
                    TuiMutations.renamePane(work.location,work.label,target.tuiSession),"Renaming only this pane…",
                    "Could not send the rename. Nothing was changed.")
            } else switchMessage="That agent moved or its pane is no longer available. Refresh before renaming."
            is TuiRosterWork.Rename -> if(ok && TuiMutations.confirmsPane(text,work.location,work.label)) {
                nextRosterNotice="Pane renamed."
                refreshAgents()
            } else switchMessage="Could not confirm the pane rename. Refresh before trying again."
            is TuiRosterWork.VerifyCreate -> if(ok && TuiMutations.confirmsPane(text,work.anchor)) {
                beginTuiWork(TuiRosterWork.CreateTab(work.anchor.workspaceId),
                    TuiMutations.createShellTab(work.anchor.workspaceId,target.tuiSession),"Creating an unfocused shell tab…",
                    "Could not send the request. Nothing was created.")
            } else switchMessage="The current workspace changed. Refresh before creating a shell."
            is TuiRosterWork.CreateTab -> {
                val fresh=if(ok) TuiMutations.newPane(text,work.workspace) else null
                if(fresh!=null && switchSessions.none {it.address==fresh.terminalId || it.tuiLocation?.paneId==fresh.paneId}) {
                    beginTuiWork(TuiRosterWork.VerifyNewPane(fresh),
                        TuiMutations.inspectPane(fresh,target.tuiSession),"Checking the new shell…",
                        "The shell was created but did not open. Refresh to find it.")
                } else switchMessage=if(event.exitStatus==78) "This Herdr CLI does not support creating a shell from Pocket."
                    else "Could not confirm creation. Check Herdr for the new tab before trying again."
            }
            is TuiRosterWork.VerifyNewPane -> if(ok && TuiMutations.confirmsPane(text,work.location)) {
                val shell=TuiAgents.plainTerminal(work.location)
                switchSessions=switchSessions+shell
                startTerminalSwitch(shell,newlyCreated=true)
            } else switchMessage="The new shell pane changed before it could be opened. Refresh to find it."
        }
    }

    fun dismissAgentSwitcher() {
        if(coreShellCreate!=null || tuiWork!=null) return // A sent mutation cannot reliably be undone.
        val operation=switchOperation
        if(operation!=null && !ssh.cancelOperation(operation)) return // a result is already being delivered
        clearAgentSwitcher()
    }

    fun connectionFromAgents() {
        if(!showAgentSwitcher || switchBusy) return
        clearAgentSwitcher()
        terminalVisible=false
    }

    internal val connectionBackDestination get() =
        if(retainedTerminal && !terminalVisible && !showSessionPicker) ConnectionBackDestination.TERMINAL
        else connectionBackDestination(
        connected = busy && status=="Connected · SSH PTY",
        terminalVisible = terminalVisible,
        agentsVisible = showAgentSwitcher,
        displayedPty = displayedPty,
        livePty = ssh.terminalIdentity(),
        activeCoreAddress = activeCoreAddress,
    )

    internal fun returnFromConnection() {
        val destination=connectionBackDestination ?: return
        terminalVisible=true
        if(destination==ConnectionBackDestination.AGENTS) openAgentSwitcher()
    }

    private fun clearAgentSwitcher() {
        interruptedRosterNotice=when(tuiWork) {
            is TuiRosterWork.Rename -> "Connection interrupted during rename. Check the current pane label."
            is TuiRosterWork.CreateTab -> "Tab creation was interrupted. Check Herdr before trying again."
            is TuiRosterWork.VerifyNewPane -> "A shell was created. Refresh to find it."
            else -> if(coreShellCreate!=null || switchingNewTerminal)
                "The shell request was interrupted. Refresh to check for it before trying again."
            else interruptedRosterNotice
        }
        showAgentSwitcher=false
        pendingWatchTerminal=null
        switchOperation=null
        switchCandidate=null
        switchSessions=emptyList()
        rosterRefresh=AgentRosterRefresh()
        coreShellCreate=null
        switchingNewTerminal=false
        renamingAgent=null
        tuiCanCreate=false
        tuiRosterFresh=false
        tuiWork=null
        nextRosterNotice=null
        switchMessage=""
    }

    private fun acceptSwitch(id:Long) {
        if(switchOperation!=id) return
        val selected=switchCandidate ?: return
        val target=resume ?: return
        target.command=target.backend.attachCommand(selected.address,target.tuiSession)
        target.address=selected.address
        target.retries=0
        listErrors.reset()
        activeCoreAddress=selected.address
        reconnectTarget=reconnectTarget?.copy(mode=ReconnectMode.AgentSession(true,selected.address,target.backend,target.tuiSession))
        displayedPty=ssh.terminalIdentity()
        rememberCandidate=null
        agentName=selected.name
        val endpoint=HostEndpoint.parse(target.config.host,target.config.port.toString())
        prefs.edit().putString("agent",selected.name).apply()
        if(endpoint!=null) saveRecentSession(target,target.backend.rememberedKey(endpoint,target.config.user,target.tuiSession),selected.address)
        terminalMouse.reset()
        replaceEmulator(displayedPty)
        switchingNewTerminal=false
        clearAgentSwitcher()
    }

    private var resume:ResumeTarget?=null
    private fun clearResume() {dismissAgentMenu();resume?.clear();resume=null;activeCoreAddress=null;displayedPty=null;terminalMouse.reset();clearAgentSwitcher()}
    private fun reattachAfterLoss() {
        suspendCurrentTerminal()
        if(finishStartup()) {
            busy=false;terminalVisible=false;clearResume()
            reconnectOffered=true
            status="Automatic reconnect lost its connection."
            return
        }
        terminalMouse.reset()
        displayedPty=null
        activeCoreAddress=null
        clearAgentSwitcher()
        val target=resume
        val uri=keyUri
        if(keyRemembered && (uri==null || !runCatching {hasReadGrant(uri)}.getOrDefault(false))) {
            busy=false;clearResume();forgetKey()
            credentialNotice="Key access expired. Select the file again."
            status="Connection lost. Key access expired."
            return
        }
        if(target==null || target.retries>=3) {busy=false;clearResume();reconnectOffered=true;status="Connection lost.";return}
        target.retries++
        val request=generation
        busy=true
        status="Connection lost · reattaching ${target.retries}/3…"
        viewModelScope.launch {
            delay(1000L shl (target.retries-1))
            if(request!=generation || resume!==target) return@launch
            try {listErrors.reset();checkNotNull(ssh.connect(target.config,target.key,target.passphrase,target.command,cols,rows))}
            catch(e:Exception) {busy=false;clearResume();reconnectOffered=true;status="Reattach stopped. Check connection details."}
        }
    }
    private val main=Handler(Looper.getMainLooper())
    private val pendingOutput=java.util.concurrent.Semaphore(32)
    val ssh:SshSession = SshSession { token, event ->
        val dataEvent=event is SshSession.Event.Output || event is SshSession.Event.ErrorOutput
        if(dataEvent) {
            while(!pendingOutput.tryAcquire(1,java.util.concurrent.TimeUnit.SECONDS)) {
                if(!sshCurrent(token)) return@SshSession
            }
        }
        main.post {
          try {
            if (!sshCurrent(token)) return@post
            when(event) {
                is SshSession.Event.Connected -> {
                    onAuthenticated()
                    busy=true
                    status=if(listing) {
                        if(startupInFlight) "Reconnecting · looking for your agent…" else "Loading ${listedBackend.label} sessions…"
                    } else "Connected · SSH PTY"
                    if(!listing) {
                        displayedPty=ssh.terminalIdentity()
                        if(retainedTerminal) {
                            // Respect a trip to settings while retry was running. The
                            // normal settings Back affordance now returns to the live PTY.
                            if(displayedPty!=null) restoreTerminalAfterReconnect()
                        } else {
                            displayedPty?.let { if(!emulatorOutput.bind(it)) replaceEmulator(it) }
                            terminalVisible=!startupInFlight
                        }
                        activeCoreAddress=resume?.address
                        attachStarted=true
                        scheduleRemember(token)
                    }
                }
                is SshSession.Event.Output -> if(listing) {
                    if(listBytes.size()+event.bytes.size>1024*1024) {
                        val retry=reconnectTarget
                        disconnect()
                        reconnectTarget=retry
                        reconnectOffered=retry!=null
                        status="Session list exceeds 1 MiB."
                    }
                    else listBytes.write(event.bytes)
                } else {renderConnectionOutput(event.bytes);attachOutputSeen=true;scheduleRemember(token)}
                is SshSession.Event.ErrorOutput -> {
                    if((listing || resume?.backend==AgentBackend.TUI) && listErrors.size()<8192)
                        listErrors.write(event.bytes,0,minOf(event.bytes.size,8192-listErrors.size()))
                    if(!listing) renderConnectionOutput(event.bytes)
                }
                is SshSession.Event.OperationList -> if(filesLookup==event.id) {
                    acceptFilesRoot(event)
                } else if(switchOperation==event.id) {
                    val wasRoster=rosterRefresh.operationId==event.id
                    rosterRefresh=rosterRefresh.complete(event.id)
                    switchOperation=null
                    val work=tuiWork
                    if(work!=null) {
                        didTuiWork(event,work)
                    } else if(coreShellCreate!=null) {
                        coreShellCreate?.let {didCoreShellWork(event,it)}
                    } else if(wasRoster && event.exitStatus==0) try {
                        val backend=resume?.backend ?: AgentBackend.CORE
                        switchSessions=backend.parse(event.stdout.toString(Charsets.UTF_8))
                            .filter { it.state=="live" }.distinctBy { it.address }
                        if(backend==AgentBackend.TUI) {
                            tuiRosterFresh=true
                            tuiCanCreate=TuiMutations.canCreateFromRoster(event.stdout.toString(Charsets.UTF_8))
                        }
                        // Reclassifying a pane after it starts an agent does not change its PTY.
                        TuiAgents.nameForTerminal(switchSessions,activeCoreAddress)?.let {agentName=it}
                        switchMessage=agentRosterNotice(switchSessions.size,backend.label,nextRosterNotice,interruptedRosterNotice)
                        nextRosterNotice=null
                        interruptedRosterNotice=null
                        consumeWatchedAgent()
                    } catch(_:Exception) {
                        pendingWatchTerminal=null
                        switchMessage="Could not read the session list. Your terminal is unchanged."
                    }
                    else if(wasRoster) {
                        pendingWatchTerminal=null
                        switchMessage=(resume?.backend ?: AgentBackend.CORE).listFailure(event.exitStatus,event.stderr)+" Your terminal is unchanged."
                    }
                }
                is SshSession.Event.OperationSwitched -> acceptSwitch(event.id)
                is SshSession.Event.OperationFailed -> if(filesLookup==event.id) {
                    filesLookup = null
                    filesUi = filesUi?.copy(loading = false, error = "Could not look up this agent’s folder. Try again.")
                } else if(switchOperation==event.id) {
                    rosterRefresh=rosterRefresh.complete(event.id)
                    switchOperation=null;switchCandidate=null;pendingWatchTerminal=null
                    switchMessage=when(tuiWork) {
                        is TuiRosterWork.Rename -> "Could not confirm the pane rename. Refresh before trying again."
                        is TuiRosterWork.VerifyNewPane -> "The shell was created but did not open. Refresh to find it."
                        is TuiRosterWork.CreateTab -> "Could not confirm tab creation. Check Herdr before trying again."
                        is TuiRosterWork.VerifyRename -> "Could not check the pane. No rename was sent."
                        is TuiRosterWork.VerifyCreate -> "Could not check the workspace. Nothing was created."
                        null -> when {
                            coreShellCreate!=null -> "Could not confirm the new shell. Refresh before trying again."
                            switchingNewTerminal -> "The shell was created but could not open. Your current terminal is unchanged."
                            else -> event.message
                        }
                    }
                    tuiWork=null
                    coreShellCreate=null
                    switchingNewTerminal=false
                }
                is SshSession.Event.TransportLost -> reattachAfterLoss()
                is SshSession.Event.RejectedHostKey -> {
                    suspendCurrentTerminal()
                    reconnectOffered=false
                    reconnectTarget=null
                    val probe=probeContext
                    finishStartup()
                    clearPendingSave();probeContext=null
                    clearResume();rememberCandidate=null;listing=false;observedPin=event.actual;busy=false
                    if(probe==null) passphrase=""
                    if(probe!=null && probe.generation==generation && probe.endpoint==selectedEndpoint) {
                        val expected=hostTrust.pin(probe.endpoint)
                        offeredTrust=hostTrust.offer(probe.endpoint,event.actual,generation)
                        offeredRequest=probe.resumeRequest
                        status=when {
                            expected==null -> "New SSH host. Confirm its fingerprint to continue."
                            expected==event.actual -> "Offered fingerprint matches the saved pin. No authentication sent."
                            else -> "HOST KEY MISMATCH. Connection rejected."
                        }
                    } else status="HOST KEY MISMATCH. Connection rejected."
                }
                is SshSession.Event.Closed -> {
                    if(!listing) suspendCurrentTerminal()
                    val attachError=if(!listing && resume?.backend==AgentBackend.TUI)
                        TuiAgents.attachFailure(listErrors.toByteArray()) else null
                    val closingBackend=listedBackend
                    reconnectOffered=event.exitStatus!=0
                    clearPendingSave();clearResume();rememberCandidate=null;busy=false
                    if(listing) {
                        listing=false
                        if(event.exitStatus==0) try {
                            publishSessionList()
                        } catch(e:Exception) {finishStartup();cancelPicker();reconnectOffered=true;status="Could not read ${closingBackend.label} agent data. Check the installed version."}
                        else {finishStartup();cancelPicker();status=closingBackend.listFailure(event.exitStatus,listErrors.toByteArray())}
                    } else if(finishStartup()) {
                        terminalVisible=false
                        reconnectOffered=true
                        status=attachError ?: "Could not reopen the previous agent."
                    } else status=attachError ?: if(event.exitStatus==0) "Disconnected · remote session ended."
                        else "Disconnected · remote exit ${event.exitStatus ?: "unknown"}"
                }
                is SshSession.Event.Failed -> {
                    suspendCurrentTerminal()
                    reconnectOffered=probeContext==null
                    val automatic=finishStartup()
                    clearPendingSave();probeContext=null;clearResume();rememberCandidate=null;passphrase="";listing=false;busy=false
                    status=if(automatic) "Automatic reconnect failed. ${event.message}" else event.message
                }
            }
          } finally {if(dataEvent) pendingOutput.release()}
        }
    }
    private fun sshCurrent(token:Long):Boolean=ssh.isCurrent(token)
    private val retiredEmulators=mutableSetOf<TerminalEmulator>()
    private val attachedEmulators=mutableSetOf<TerminalEmulator>()
    private var emulatorEpoch=0L
    private var emulatorOutput=TerminalOutputBinding()
    var emulator by mutableStateOf(newEmulator())
        private set

    private fun replaceEmulator(pty:Any?=null) {
        val old=emulator
        if(old in attachedEmulators) retiredEmulators.add(old) else old.close()
        emulator=newEmulator(pty)
    }

    private fun newEmulator(pty:Any?=null):TerminalEmulator {
        val epoch=++emulatorEpoch
        val binding=TerminalOutputBinding(pty)
        emulatorOutput=binding
        return TerminalEmulatorFactory.create(
            initialCols=cols, initialRows=rows,
            inlineImages=InlineImages.On(),
            onKeyboardInput={ bytes ->
                if(epoch==emulatorEpoch && !demo && !retainedTerminal) {
                    // Termlib posts its output, including protocol replies, to this
                    // Handler. A queued old-renderer callback must never follow a
                    // newly swapped or reconnected channel.
                    val exact=binding.owner ?: ssh.terminalIdentity()?.takeIf(binding::bind)
                    if(exact!=null) ssh.sendToTerminal(exact,bytes)
                }
            },
            onResize={ d -> if(epoch==emulatorEpoch) {
                cols=d.columns; rows=d.rows
                ssh.resize(d.columns,d.rows,d.widthPixels,d.heightPixels)
            } },
        )
    }
    fun attachTerminal(terminal:TerminalEmulator) {attachedEmulators.add(terminal)}
    fun retireTerminal(old:TerminalEmulator) {
        attachedEmulators.remove(old)
        if(retiredEmulators.remove(old)) old.close()
    }
    internal fun connect(probe:Boolean=false, list:Boolean=false, attach:CoreSession?=null, autoSelect:Boolean=false,
        automatic:Boolean=false, pinnedOnly:Boolean=false, preferredAgent:String?=null, backend:AgentBackend=AgentBackend.CORE,
        tuiSessionName:String?=tuiSession.trim().ifBlank {null},
        keepRetainedTerminal:Boolean=false,
    ) {
        if(!automatic) noteManualConnectionAction()
        if(busy) return
        val namedSession=tuiSessionName.takeIf {backend==AgentBackend.TUI}
        if(namedSession!=null && TuiAgents.parseSession(namedSession)!=namedSession) {
            finishStartup()
            status="Enter a valid Herdr TUI session name or leave it blank for the default."
            return
        }
        val endpoint=selectedEndpoint
        if(endpoint==null) {status="Enter a valid host and port (1–65535).";return}
        if(!probe && (user.isBlank() || (!list && attach==null && command.isBlank()))) {status="Enter a username and, for custom attach, a command.";return}
        val expected=hostTrust.pin(endpoint)
        if(automatic && (expected==null || !isSshSha256Pin(expected) || !keyRemembered ||
            keyUri?.let {runCatching {hasReadGrant(it)}.getOrDefault(false)}!=true)) {
            finishStartup()
            status="Automatic reconnect needs a remembered key and a saved host fingerprint. Connect manually."
            return
        }
        if(pinnedOnly && (expected==null || !isSshSha256Pin(expected))) {
            reconnectOffered=false
            status="Verify the SSH host before reconnecting."
            return
        }
        val checking=probe || expected==null
        if(!probe && keyUri==null) {status="Select a private key file.";return}
        val cfg=SshConfig(endpoint.host,endpoint.port,user.trim().ifBlank{"probe"},if(checking)null else expected)
        if(attach!=null && (listedConfig!=cfg || listedBackend!=backend || listedTuiSession!=namedSession || attach !in sessions || attach.state!="live")) {cancelPicker();status="Connection details changed. Choose sessions again.";return}
        reconnectOffered=false
        reconnectTarget=if(checking) null else reconnectProfile()?.let { profile ->
            val mode=when {
                attach!=null -> ReconnectMode.AgentSession(true,attach.address,backend,namedSession)
                list -> ReconnectMode.AgentSession(autoSelect,preferredAgent ?: if(autoSelect)
                    prefs.getString(backend.rememberedKey(endpoint,cfg.user,namedSession),null) else null,backend,namedSession)
                else -> ReconnectMode.Custom(command)
            }
            ReconnectTarget(profile,mode)
        }
        invalidateOffer()
        if(!keepRetainedTerminal) retainedTerminal=false
        clearRetainedOutput()
        clearResume()
        showSessionPicker=false;attachStarted=false;attachOutputSeen=false
        rememberCandidate=if(attach!=null) backend.rememberedKey(endpoint,cfg.user,namedSession) to attach.address else null
        demo=false; busy=true; status=when {
            checking -> "Checking host key (no authentication)…"
            automatic -> "Reconnecting to your agent…"
            else -> "Connecting…"
        }
        val request=++generation
        if(checking) probeContext=Probe(endpoint,request,if(probe || attach!=null)null else Request(list,autoSelect,backend,namedSession))
        val uri=keyUri
        val secret=if(checking) byteArrayOf() else passphrase.toByteArray(Charsets.UTF_8)
        val wantsSave=!checking && keyRemembered && saveTypedPassphrase && secret.isNotEmpty()
        val mayReadStored=!checking && keyRemembered && secret.isEmpty()
        clearPendingSave()
        if(!checking && !list) passphrase=""
        listing=!checking && list
        if(listing) {pendingColdWatchTerminal=null;autoPick=autoSelect;requestedAgent=preferredAgent;listedBackend=backend;listedTuiSession=namedSession;listedConfig=cfg;sessions=emptyList()}
        if(!list && !checking && !retainedTerminal) replaceEmulator()
        listBytes.reset();listErrors.reset()
        if(attach!=null) agentName=attach.name
        val cmd=if(checking) "true" else if(list) backend.listCommand(namedSession) else if(attach!=null) backend.attachCommand(attach.address,namedSession) else command
        viewModelScope.launch {
            var key:ByteArray?=null
            var unlocked:ByteArray?=null
            try {
                key=if(checking) byteArrayOf() else withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri!!)?.use { input ->
                        val data=input.readNBytes(128*1024+1)
                        if(data.size>128*1024) {
                            data.fill(0)
                            throw IllegalArgumentException("Key file is too large (maximum 128 KiB).")
                        }
                        data
                    } ?: throw FileNotFoundException()
                }
                if(request!=generation) return@launch
                val scope=if(!checking && (wantsSave || mayReadStored) && expected!=null) PassphraseScope(
                    endpoint=endpoint, username=cfg.user, keyUri=uri.toString(),
                    keyContentSha256=keyFileSha256(key),
                    expectedPin=expected,
                ) else null
                if(mayReadStored && scope!=null && vault.has(scope)) {
                    // A missing/changed local pin cannot reach this code. SSH still verifies the remote key before auth.
                    unlocked=withContext(Dispatchers.IO) {vault.read(scope)}
                    if(request!=generation) return@launch
                    hasSavedPassphrase=vault.hasSaved()
                    if(unlocked==null) {
                        finishStartup()
                        busy=false;listing=false
                        reconnectOffered=true
                        terminalVisible=false
                        clearRetainedOutput()
                        credentialNotice="Saved passphrase could not be unlocked. Enter it again."
                        status="Enter the key passphrase and try again."
                        return@launch
                    }
                }
                val credential=unlocked ?: secret.takeIf { it.isNotEmpty() }
                prefs.edit().putString("agent",agentName).putString("host",host).putString("port",port)
                    .putString("user",user).putString("command",command).apply {
                        if(backend==AgentBackend.TUI && !checking) putString("tui_session",namedSession)
                    }.apply()
                if(wantsSave && scope!=null) pendingSave=PendingSave(scope,secret.copyOf())
                if(attach!=null) resume=ResumeTarget(cfg,key.copyOf(),credential?.copyOf(),cmd,attach.address,backend,namedSession)
                checkNotNull(ssh.connect(cfg,key,credential,cmd,cols,rows,pty=!list))
            } catch(e:Exception) {
                if(request==generation) {
                    finishStartup()
                    clearRetainedOutput()
                    clearPendingSave();probeContext=null;clearResume();busy=false;listing=false;passphrase=""
                    if(!checking && (e is SecurityException || e is FileNotFoundException)) {
                        forgetKey()
                        credentialNotice="Key access was lost. Select the file again."
                        status="Could not access the SSH key."
                    } else {
                        reconnectOffered=true
                        status=if(e is IllegalArgumentException) e.message ?: "Invalid input" else "Could not load key. Select the file again."
                    }
                }
            } finally {key?.fill(0);unlocked?.fill(0);secret.fill(0)}
        }
    }

    fun selectKey(uri:Uri) {
        noteManualConnectionAction()
        if(busy || uri.scheme!="content") return
        if(AgentWatchService.state.value is AgentWatchServiceState.Active) AgentWatchService.stop(getApplication<Application>())
        invalidateOffer()
        val previous=keyUri
        reconnectOffered=false
        cancelPicker()
        val cleared=forgetSavedPassphrase()
        keyUri=uri
        keyRemembered=runCatching {
            resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            hasReadGrant(uri)
        }.getOrDefault(false)
        val persisted=prefs.edit().putBoolean("startup_key_needs_auth",true).apply {
            if(keyRemembered) putString("key_document_uri",uri.toString()) else remove("key_document_uri")
        }.commit()
        if(!persisted) {
            keyRemembered=false
            releaseKey(uri)
        }
        if(previous!=uri) releaseKey(previous)
        credentialNotice=if(keyRemembered) "Key file remembered on this device." else "Android did not let the app remember this key. It is available for this session only."
        if(!cleared) credentialNotice+=" Old saved passphrase could not be fully cleared."
        status="Key selected."
    }

    fun forgetKey() {
        noteManualConnectionAction()
        if(busy) return
        invalidateOffer()
        if(AgentWatchService.state.value is AgentWatchServiceState.Active) AgentWatchService.stop(getApplication<Application>())
        val previous=keyUri
        reconnectOffered=false
        reconnectTarget=null
        keyUri=null;keyRemembered=false
        val removed=prefs.edit().remove("key_document_uri").commit()
        releaseKey(previous)
        cancelPicker()
        val cleared=forgetSavedPassphrase()
        credentialNotice=if(removed && cleared) "Key access and saved passphrase forgotten." else "Could not confirm credentials were fully forgotten. Try again."
    }

    fun disconnect() {
        if(AgentWatchService.state.value is AgentWatchServiceState.Active) AgentWatchService.stop(getApplication<Application>())
        if(retainedTerminal) terminalVisible=false
        retainedTerminal=false
        clearRetainedOutput()
        noteManualConnectionAction();finishStartup();reconnectOffered=false;reconnectTarget=null;invalidateOffer()
        generation++;clearPendingSave();clearResume();rememberCandidate=null;ssh.disconnect()
        busy=false;listing=false;cancelPicker();status="Disconnected"
    }
    fun showDemo() {
        disconnect();demo=true;terminalVisible=true;status="LOCAL DEMO · no SSH connection"
        emulator.writeInput("\u001b[2J\u001b[HHerdr Pocket · local renderer demo\r\n\r\n".toByteArray())
        val bitmap=Bitmap.createBitmap(240,100,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(android.graphics.Color.rgb(30,55,65))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.rgb(166,227,161);textSize=26f }
        canvas.drawCircle(35f,50f,21f,paint);canvas.drawText("Kitty PNG",70f,59f,paint)
        val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle()
        val payload=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
        emulator.writeInput(("\u001b_Ga=T,f=100,t=d,i=42,c=24,r=5;"+payload+"\u001b\\\r\n\r\nLocal PNG sent through Kitty escape bytes.\r\nPinch text to zoom; rotate or unfold to resize.\r\nThis is not a remote Herdr session.\r\n").toByteArray())
    }
    override fun onCleared() {clearRetainedOutput();finishStartup();clearPendingSave();clearResume();ssh.close();emulator.close();retiredEmulators.forEach { it.close() };retiredEmulators.clear();passphrase=""}
}

class MainActivity : ComponentActivity() {
    private lateinit var model:PocketModel
    private val alertPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.finishAgentWatchPermission(this,granted)
    }
    private fun enableAgentWatch() {
        if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if(model.prepareAgentWatchPermission()) alertPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else if(!AgentWatchService.canNotify(this)) {
            runCatching {startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))}
            model.notificationPermissionDeclined()
        } else model.startAgentWatch(this)
    }
    private fun handleAgentAlert(intent:Intent?) {
        if(!AgentWatchService.isAlertIntent(intent)) return
        val target=AgentWatchService.takeAlert(intent)
        // Don't fall through to a different saved agent if the process that
        // issued this one-use notification no longer exists.
        if(target!=null) model.openWatchedAgent(target) else model.expiredWatchAlert()
        setIntent(Intent(this,MainActivity::class.java))
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        handleAgentAlert(intent)
    }
    private val filePicker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.onFileSavePicked(result.data?.data?.takeIf {result.resultCode==android.app.Activity.RESULT_OK})
    }
    private val uploadPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        model.onFileUploadPicked(uri)
    }
    private val keyPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) model.selectKey(uri)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model=ViewModelProvider(this)[PocketModel::class.java]
        handleAgentAlert(intent)
        setContent {
            LaunchedEffect(model) { model.tryStartupReconnect() }
            MaterialTheme(colorScheme=darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    PocketScreen(model,
                        enableAgentWatch=::enableAgentWatch,
                        stopAgentWatch={AgentWatchService.stop(this)},
                        chooseKey={model.noteManualConnectionAction();keyPicker.launch(arrayOf("*/*"))},
                        uploadFile={
                            model.startProtectedFileUpload(this) {uploadPicker.launch(arrayOf("*/*"))}
                        },
                        saveFile={
                            model.prepareFileSave()?.let { intent ->
                                runCatching {filePicker.launch(intent)}.onFailure {model.fileSavePickerFailed()}
                            }
                        },
                    )
                }
            }
        }
    }
}
@Composable private fun PocketScreen(m:PocketModel,enableAgentWatch:()->Unit,stopAgentWatch:()->Unit,
    chooseKey:()->Unit,saveFile:()->Unit,uploadFile:()->Unit) {
 var rosterView by rememberSaveable { mutableStateOf(AgentRosterView.AGENTS) }
 val agentsScroll=rememberLazyListState()
 val spacesScroll=rememberLazyListState()

 m.offeredTrust?.let { offer ->
    AlertDialog(
      onDismissRequest={m.rejectHost()},
      title={Text("Trust this SSH host?")},
      text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
          Text(offer.endpoint.display,fontSize=20.sp)
          Text(offer.fingerprint,fontSize=16.sp)
          Text("This identity has not been independently verified. A fake server could be impersonating your host. Compare the fingerprint through a trusted route before accepting if you can.",fontSize=16.sp)
        }
      },
      confirmButton={TextButton(onClick={m.trustOfferedHost()}){Text("Trust on first use")}},
      dismissButton={TextButton(onClick={m.rejectHost()}){Text("Cancel")}},
    )
 }

 if(m.filesUploadNotice==null) m.watchNotice?.let { notice ->
    AlertDialog(onDismissRequest=m::clearWatchNotice,title={Text("Agent alerts")},
        text={Text(notice)},confirmButton={TextButton(onClick=m::clearWatchNotice){Text("OK")}})
 }

 m.filesUploadNotice?.let { notice ->
    AlertDialog(onDismissRequest=m::clearFilesUploadNotice,title={Text(stringResource(R.string.agent_files_upload_title))},
        text={Text(stringResource(when(notice) {
            FilesUploadInterruption.CONNECTION_LOST_BEFORE_TRANSFER -> R.string.agent_files_upload_connection_lost_before
            FilesUploadInterruption.CONNECTION_LOST_DURING_TRANSFER -> R.string.agent_files_upload_connection_lost_during
            FilesUploadInterruption.PICKER_ROUTE_EXPIRED -> R.string.agent_files_upload_route_expired
        }))},confirmButton={TextButton(onClick=m::clearFilesUploadNotice){Text("OK")}})
 }

 m.terminalRecovery?.let { recovery ->
    FrozenTerminalScreen(m,recovery)
    return
 }
 if(m.showAgentSwitcher) {
    AgentSwitcher(m,rosterView,{rosterView=it},if(rosterView==AgentRosterView.AGENTS) agentsScroll else spacesScroll)
    return
 }
 if(m.terminalVisible && m.canSwitchAgents) {
    CoreTerminal(m,saveFile,uploadFile)
    return
 }

 Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
  Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween) {
   val destination=m.connectionBackDestination
   if(destination!=null) {
    val label=stringResource(when(destination) {
        ConnectionBackDestination.AGENTS -> R.string.back_to_agents
        ConnectionBackDestination.TERMINAL -> R.string.back_to_terminal
    })
    RoundBackArrow(label,m::returnFromConnection,48.dp)
    Spacer(Modifier.width(8.dp))
   }
   Text(m.agentName.ifBlank{"Agent workspace"},fontSize=22.sp,modifier=Modifier.weight(1f))
   if(m.canReconnect) TextButton(onClick=m::reconnect) {Text(stringResource(R.string.reconnect))}
   if(m.terminalVisible) TextButton(onClick={m.terminalVisible=false}) {Text("Connection")}
  }
  Text(m.status,fontSize=16.sp,modifier=Modifier.padding(horizontal=12.dp,vertical=4.dp))
  if(m.terminalVisible) {
   TerminalPanel(m,Modifier.weight(1f).fillMaxWidth())
  } else {
   Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    var advanced by remember { mutableStateOf(false) }
    Text("Connect to Herdr",fontSize=22.sp)
    AgentWatchSettings(m.canStartAgentWatch,enableAgentWatch,stopAgentWatch)
    Field("Host or IP address",m.host,{m.host=it},!m.busy)
    Field("SSH username",m.user,{m.user=it},!m.busy)
    OutlinedButton(onClick=chooseKey,enabled=!m.busy){Text(if(m.keyUri==null) "Select private key file" else "Change private key file")}
    if(m.keyUri!=null) {
       OutlinedTextField(value=m.passphrase,onValueChange={m.passphrase=it},label={Text("Key passphrase (optional)")},
         visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
         singleLine=true,enabled=!m.busy,modifier=Modifier.fillMaxWidth())
       Row(
         Modifier.fillMaxWidth().toggleable(
            value=m.saveTypedPassphrase,enabled=!m.busy && m.keyRemembered,
            role=Role.Checkbox,onValueChange=m::toggleRememberPassphrase),
         verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,
       ) {
          Checkbox(checked=m.saveTypedPassphrase,onCheckedChange=null,
            enabled=!m.busy && m.keyRemembered,modifier=Modifier.padding(end=12.dp))
          Text("Remember the passphrase I enter",fontSize=16.sp)
       }
       if(m.hasSavedPassphrase) {
          Text("A saved passphrase is used only with its original host, login, and key.",fontSize=16.sp)
          TextButton(onClick={m.forgetSavedPassphrase()},enabled=!m.busy){Text("Forget saved passphrase")}
       }
       TextButton(onClick=m::forgetKey,enabled=!m.busy){Text("Forget key")}
    }
    if(m.credentialNotice.isNotBlank()) Text(m.credentialNotice,fontSize=16.sp,
       modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
    Button(onClick={m.connect(list=true,backend=AgentBackend.TUI)},enabled=!m.busy,modifier=Modifier.fillMaxWidth()) {
        Text("Connect to Herdr TUI",fontSize=20.sp)
    }
    OutlinedButton(onClick={m.connect(list=true,autoSelect=true)},enabled=!m.busy,modifier=Modifier.fillMaxWidth()) {
        Text("Connect to Herdr Core")
    }
    if(m.busy) OutlinedButton(onClick={m.disconnect()}){Text(if(m.status=="Connected · SSH PTY") "Disconnect" else "Cancel connection")}
    OutlinedButton(onClick={m.connect(list=true)},enabled=!m.busy,modifier=Modifier.fillMaxWidth()){Text("Choose another Core session")}
    if(m.showSessionPicker) {
       Text("Live ${m.listedBackend.label.removePrefix("Herdr ")} sessions",fontSize=20.sp)
       val (agentRows,terminalRows)=groupedLiveRoster(m.sessions)
       for((group,label) in listOf(agentRows to R.string.agent_roster_agents,terminalRows to R.string.agent_roster_terminals)) {
          if(group.isEmpty()) continue
          Text(stringResource(label),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {heading()})
          for(session in group) {
              if(m.listedBackend==AgentBackend.TUI) {
                  AgentPickerRow(session,current=false,tui=true,enabled=!m.busy,
                      onClick={m.connect(attach=session,backend=AgentBackend.TUI)})
              } else OutlinedButton(onClick={m.connect(attach=session,backend=m.listedBackend)},enabled=!m.busy,modifier=Modifier.fillMaxWidth()) {
                 Column(Modifier.fillMaxWidth()) {
                     Text(session.name,fontSize=22.sp)
                     Text("${session.address} · ${session.agent.ifBlank{"terminal"}} · ${session.status ?: session.state}",fontSize=16.sp)
                 }
              }
          }
       }
       TextButton(onClick={m.cancelPicker()}) {Text("Cancel session selection")}
    }
    CoreInstallHelp()
    if(advanced || m.status.startsWith("HOST KEY MISMATCH")) m.observedPin?.let {
      Text("Offered by server: $it\nCompare against the server fingerprint through an independent trusted route.",fontSize=16.sp)
    }
    TextButton(onClick={advanced=!advanced}) {Text(if(advanced) "Hide advanced options" else "Advanced options")}
    if(advanced) {
      OutlinedTextField(value=m.port,onValueChange={m.port=it},label={Text("SSH port")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,enabled=!m.busy,modifier=Modifier.fillMaxWidth())
      Field("Herdr TUI session (blank = default)",m.tuiSession,{m.tuiSession=it},!m.busy)
      Field("Agent / session name",m.agentName,{m.agentName=it},!m.busy)
      Field("Custom attach command",m.command,{m.command=it},!m.busy)
      OutlinedButton(onClick={m.connect()},enabled=!m.busy,modifier=Modifier.fillMaxWidth()){Text("Run custom attach command")}
      OutlinedButton(onClick={m.connect(probe=true)},enabled=!m.busy){Text("Check host fingerprint")}
      m.storedPin?.let { Text("Saved host key: $it",fontSize=16.sp) }
      OutlinedButton(onClick={m.showDemo()},enabled=!m.busy){Text("Local Kitty image demo")}
      Text("Choose an attach-only custom command. It runs on the SSH host. Key files are not copied. Remembered passphrases are encrypted on this device.",fontSize=16.sp)
      Text("ConnectBot Termlib (Apache 2.0), libvterm (MIT), mwiede JSch (BSD). Licenses bundled.",fontSize=14.sp)
    }
   }
  }
 }
}
@Composable private fun TerminalPanel(
    m:PocketModel, modifier:Modifier, suspended:Boolean=false, overlay:@Composable BoxScope.()->Unit = {},
) {
    // Termlib displays the IME when showSoftKeyboard becomes true. Scope this to the
    // displayed terminal, never saved across navigation, replacement, or Activity recreation.
    val terminal=m.emulator
    val context=LocalContext.current
    val pty=if(suspended) null else m.speechTerminalIdentity()
    val modifiers=remember(terminal) {
        dockModifierManager(terminal,m::speechTerminalIdentity) { pty,bytes ->
            terminal===m.emulator && m.sendDockKeyboardInput(pty,bytes)
        }
    }
    SideEffect {modifiers.bind(pty)}
    DisposableEffect(modifiers) {onDispose {modifiers.bind(null)}}
    var keyboard by remember(terminal,suspended) { mutableStateOf(TerminalKeyboard()) }
    val terminalModifier=if(suspended) modifier.clearAndSetSemantics {hideFromAccessibility()} else modifier
    Column(terminalModifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TerminalBody(
                m,Modifier.fillMaxSize(),!suspended && keyboard.requested,interactive=!suspended,
                modifiers=modifiers,onRequestKeyboard={if(!suspended) keyboard=keyboard.show()},
                onKeyboardVisibility={keyboard=keyboard.reportVisibility(it)},
            )
            overlay()
        }
        TerminalDock(
            m,keyboardShownOrRequested=keyboard.shownOrRequested,inputEnabled=!suspended,
            onToggleKeyboard={if(!suspended) keyboard=keyboard.toggle()},
            ctrlSelected=modifiers.ctrlSelected(pty),altSelected=modifiers.altSelected(pty),
            onToggleCtrl={modifiers.toggleCtrl(pty)},onToggleAlt={modifiers.toggleAlt(pty)},
            onKey={ key ->
                if(pty!=null && terminal===m.emulator && m.speechTerminalIdentity()===pty) {
                    termlibDockKey(key)?.let { native ->
                        modifiers.sendDockInput(terminal,pty) { target,mask -> target.dispatchKey(mask,native) }
                    }
                }
            },
            onPaste={
                if(!suspended && pty!=null && terminal===m.emulator && m.speechTerminalIdentity()===pty) {
                    // Read at the user's tap, not in composition or from a clipboard listener.
                    val text=runCatching {
                        context.getSystemService(ClipboardManager::class.java)?.primaryClip
                            ?.takeIf {it.itemCount>0}?.getItemAt(0)?.text.let(::terminalClipboardText)
                    }.getOrNull()
                    if(text!=null) m.pasteTerminalClipboard(pty,terminal,text)
                    else android.widget.Toast.makeText(context,R.string.terminal_paste_unavailable,
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onSlash={
                if(pty!=null && terminal===m.emulator && m.speechTerminalIdentity()===pty) {
                    modifiers.sendDockInput(terminal,pty) { target,mask -> target.dispatchCharacter(mask,'/'.code) }
                }
            },
        )
    }
}

private data class PendingTerminalLink(val target:TerminalWebLink,val route:RemoteClickTarget)

@Composable private fun TerminalBody(
    m:PocketModel, modifier:Modifier, keyboardRequested:Boolean,interactive:Boolean,
    modifiers:DockModifierManager,onRequestKeyboard:()->Unit, onKeyboardVisibility:(Boolean)->Unit,
) {
    val terminal=m.emulator
    var selection by remember(terminal) {mutableStateOf<SelectionController?>(null)}
    var localScroll by remember(terminal) {mutableStateOf<((Int)->Boolean)?>(null)}
    var linkHit by remember(terminal) {mutableStateOf<((Float,Float)->RenderedTerminalLink?)?>(null)}
    var pendingLink by remember(terminal,interactive) {mutableStateOf<PendingTerminalLink?>(null)}
    val context=LocalContext.current
    val noBrowser=stringResource(R.string.terminal_link_no_browser)
    fun showInBrowser(link:TerminalWebLink) {
        if(!openTerminalBrowser(context,link)) {
            android.widget.Toast.makeText(context,noBrowser,android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val browserAction by rememberUpdatedState<(TerminalWebLink)->Unit>(::showInBrowser)
    val fontSp=m.terminalFontSp.sp
    val fontPx=with(LocalDensity.current) {fontSp.toPx()}
    // Mirror pinned Termlib 0.3.15's measured monospace M advance and snapped row height.
    // PTY widthPixels/heightPixels describe the full viewport, including partial-cell gutters.
    val cellSize=remember(fontPx) {
        val paint=TextPaint().apply {
            typeface=Typeface.MONOSPACE
            textSize=fontPx
            isAntiAlias=true
        }
        val height=paint.fontMetrics.let {ceil(it.descent-it.ascent).takeIf {h->h>0f} ?: 20f}
        paint.measureText("M") to height
    }
    DisposableEffect(terminal) { m.attachTerminal(terminal);onDispose {m.retireTerminal(terminal)} }
    Box(modifier.pointerInput(m,terminal,cellSize,interactive) {
        if(!interactive) return@pointerInput
        val mouseWheel=PhysicalMouseWheel()
        var revision=Long.MIN_VALUE
        awaitEachGesture {
            var pinching=false
            var scale=1f
            var claimedWheel=false
            var started=false
            var wheelIdentity:Any?=null
            var clickTarget:RemoteClickTarget?=null
            var linkRoute:RemoteClickTarget?=null
            var linkStart:RenderedTerminalLink?=null
            var safeLink:TerminalWebLink?=null
            var tap:TerminalTouchTap?=null
            var tapPointer:androidx.compose.ui.input.pointer.PointerId?=null
            var accumulatedX=0f
            var accumulatedY=0f
            val notchPx=maxOf(viewConfiguration.touchSlop*2f,24.dp.toPx())
            while(true) {
                val event=awaitPointerEvent(PointerEventPass.Initial)
                if(event.type==PointerEventType.Scroll) {
                    tap?.cancel()
                    val route=m.physicalWheelRoute()
                    val scroll=localScroll
                    val wheel=event.changes.firstOrNull {it.type==PointerType.Mouse && !it.isConsumed &&
                        it.scrollDelta.y.isFinite() && it.scrollDelta.y!=0f}
                    if(route==null || route.modeRevision!=revision) {
                        mouseWheel.reset()
                        revision=route?.modeRevision ?: Long.MIN_VALUE
                    }
                    if(route!=null && wheel!=null && (route.remote || scroll!=null)) {
                        val steps=mouseWheel.update(route.identity,wheel.scrollDelta.y)
                        var delivered=steps==0
                        if(route.remote) {
                            repeat(abs(steps)) {
                                if(m.sendRemoteWheel(route.identity,steps<0,wheel.position.x,wheel.position.y,size.width,size.height))
                                    delivered=true
                            }
                        } else if(scroll!=null && steps!=0) {
                            delivered=scroll(-steps*3)
                        }
                        if(delivered) wheel.consume()
                    }
                    if(event.changes.none {it.pressed}) break
                    continue
                }
                val fingers=event.changes.filter {it.pressed}
                val keys=event.keyboardModifiers
                val modified=keys.isShiftPressed || keys.isCtrlPressed || keys.isAltPressed || keys.isMetaPressed
                val primary=isExclusiveTerminalPrimaryButton(event.buttons.isPrimaryPressed,
                    event.buttons.indexOfLastPressed(),modified)
                if(!started && fingers.isNotEmpty()) {
                    started=true
                    val down=fingers.singleOrNull()
                    if(down!=null) {
                        val touch=down.type==PointerType.Touch
                        val mouse=down.type==PointerType.Mouse && event.type==PointerEventType.Press &&
                            down.changedToDown() && primary
                        if(touch) wheelIdentity=m.remoteWheelIdentity()
                        if((touch || mouse) && !down.isConsumed && selection?.isSelectionActive==false) {
                            clickTarget=m.remoteClickTarget()
                            if(!modified && pendingLink==null) {
                                val route=m.terminalLinkRoute(terminal)
                                val hit=if(route!=null) linkHit?.invoke(down.position.x,down.position.y) else null
                                val approved=hit?.let { TerminalWebLink.parse(it.url) }
                                if(approved!=null) {
                                    linkRoute=route
                                    linkStart=hit
                                    safeLink=approved
                                }
                            }
                            if(clickTarget!=null || linkStart!=null) {
                                // A mouse moves far less than a finger before it is a drag.
                                val slop=if(mouse) maxOf(1.5.dp.toPx(),viewConfiguration.touchSlop/8f)
                                    else viewConfiguration.touchSlop
                                tap=TerminalTouchTap(down.position.x,down.position.y,down.uptimeMillis,
                                    slop,viewConfiguration.longPressTimeoutMillis,physicalMouse=mouse)
                                tapPointer=down.id
                            }
                        }
                    }
                }
                val original=event.changes.firstOrNull {it.id==tapPointer}
                val currentTap=tap
                if(currentTap!=null) {
                    if(original==null || event.changes.any {it.id!=tapPointer && (it.pressed || it.previousPressed)} ||
                        original.isConsumed || selection?.isSelectionActive!=false || (linkStart!=null && modified)
                    ) currentTap.cancel()
                    if(original!=null) {
                        currentTap.observeMouseButtons(original.pressed,primary,event.buttons.areAnyPressed,modified)
                        currentTap.move(original.position.x,original.position.y)
                    }
                }
                val identity=wheelIdentity
                if(fingers.size>=2) {
                    pinching=true
                    val step=event.calculateZoom()
                    if(step.isFinite() && step>0f) scale=(scale*step).coerceIn(0.25f,4f)
                } else if(!pinching && identity!=null && fingers.size==1 && m.remoteWheelIdentity()===identity) {
                    val change=fingers[0]
                    if(change.previousPressed) {
                        val delta=change.position-change.previousPosition
                        accumulatedX+=delta.x
                        accumulatedY+=delta.y
                        if(!claimedWheel && abs(accumulatedX)>viewConfiguration.touchSlop &&
                            abs(accumulatedX)>abs(accumulatedY)*1.3f) wheelIdentity=null
                        if(wheelIdentity===identity && abs(accumulatedY)>=notchPx && (claimedWheel || abs(accumulatedY)>abs(accumulatedX))) {
                            // Finger dragging down reveals earlier content (wheel up). Never emit arrow keys.
                            claimedWheel=true
                            val up=accumulatedY>0f
                            val notches=(abs(accumulatedY)/notchPx).toInt().coerceIn(1,4)
                            repeat(notches) {m.sendRemoteWheel(identity,up,change.position.x,change.position.y,size.width,size.height)}
                            accumulatedY%=notchPx
                        }
                    }
                }
                // Touch and exclusive primary mouse clicks share the exact same
                // one-shot SGR delivery and measured-cell/PTY checks. Leave
                // unclaimed ups to Termlib (selection and keyboard).
                if(!pinching && !claimedWheel && original!=null && original.changedToUp() &&
                    (original.type!=PointerType.Mouse || event.type==PointerEventType.Release) &&
                    currentTap?.releaseOnce(original.uptimeMillis)==true
                ) {
                    val target=clickTarget
                    val dimensions=terminal.dimensions
                    val start=linkStart
                    val link=safeLink
                    val route=linkRoute
                    if(start!=null && link!=null && route!=null) {
                        val end=linkHit?.invoke(original.position.x,original.position.y)
                        // A stale link gesture is never silently converted into a
                        // remote click or retargeted to newly painted output.
                        original.consume()
                        if(pendingLink==null && route.matches(m.terminalLinkRoute(terminal)) && start.matches(end)) {
                            if(start.osc8) pendingLink=PendingTerminalLink(link,route)
                            else browserAction(link)
                        }
                    } else if(target!=null && dimensions.widthPixels==size.width && dimensions.heightPixels==size.height &&
                        terminalGridMatches(size.width,size.height,cellSize.first,cellSize.second,dimensions.columns,dimensions.rows)
                    ) {
                        val cell=terminalCellAt(original.position.x,original.position.y,cellSize.first,cellSize.second,
                            dimensions.columns,dimensions.rows)
                        if(cell!=null && m.sendRemoteClick(target,cell)) original.consume()
                    }
                }
                // Leave ordinary one-finger local scrollback and unhandled taps to Termlib.
                if(pinching || claimedWheel) event.changes.forEach {it.consume()}
                if(fingers.isEmpty()) break
            }
            if(pinching) m.resizeTerminalFont(scale)
        }
    }) {
        TermlibWheelViewport(terminal,Modifier.fillMaxSize(),fontSp,
            keyboardEnabled=interactive && !m.demo,
            showKeyboard=interactive && !m.demo && keyboardRequested,modifiers=modifiers,
            onTap={if(interactive && !m.demo) onRequestKeyboard()},
            onImeChanged=onKeyboardVisibility,
            onSelectionAvailable={selection=it},
            onLinkTestsAvailable={linkHit=it},
            onLocalScrollAvailable={localScroll=it})
    }
    pendingLink?.let { pending ->
        AlertDialog(
            onDismissRequest={pendingLink=null},
            title={Text(stringResource(R.string.terminal_link_confirm))},
            text={
                Text(pending.target.url,Modifier.heightIn(max=240.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton={
                TextButton(onClick={
                    pendingLink=null
                    if(pending.route.matches(m.terminalLinkRoute(terminal))) browserAction(pending.target)
                }) {Text(stringResource(R.string.terminal_link_open))}
            },
            dismissButton={TextButton(onClick={pendingLink=null}) {Text(stringResource(android.R.string.cancel))}},
        )
    }
}

@Composable private fun FrozenTerminalScreen(m:PocketModel,recovery:TerminalRecoveryUi) {
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.clearFocus(force=true)
        keyboard?.hide()
    }
    BackHandler(onBack=m::openRecoverySettings)
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        TerminalPanel(m,Modifier.fillMaxSize(),suspended=true)
        // One physical scrim catches gestures on the frozen terminal; the
        // centered actions sit above it and are the only available controls.
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha=0.70f)).pointerInput(Unit) {
            awaitPointerEventScope {
                while(true) awaitPointerEvent().changes.forEach {it.consume()}
            }
        })
        Column(
            Modifier.align(androidx.compose.ui.Alignment.Center).widthIn(max=420.dp).padding(24.dp),
            horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(10.dp),
        ) {
            if(recovery==TerminalRecoveryUi.RECONNECTING) {
                Text(stringResource(R.string.terminal_reconnecting),fontSize=22.sp,
                    modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            } else {
                if(recovery==TerminalRecoveryUi.RECONNECT) {
                    Button(onClick=m::reconnect) {
                        Text(stringResource(R.string.reconnect),fontSize=20.sp)
                    }
                }
                Text(m.status,fontSize=17.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                    modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            }
            OutlinedButton(onClick=m::openRecoverySettings) {
                Text(stringResource(R.string.connection_settings),fontSize=17.sp)
            }
        }
    }
}

@Composable private fun CoreTerminal(m:PocketModel,saveFile:()->Unit,uploadFile:()->Unit) {
    val headerSize=maxOf(48.dp, with(LocalDensity.current) {24.sp.toDp()} + 16.dp)
    val files = m.filesUi
    val menuOpen = m.agentMenuOpen
    BackHandler(enabled = !menuOpen && files == null, onBack = m::openAgentSwitcher)
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        TerminalPanel(m,Modifier.fillMaxSize().imePadding(),suspended=menuOpen || files!=null) {
            if (!menuOpen && files == null) Row(
                Modifier.align(androidx.compose.ui.Alignment.TopStart).fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),
                verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(8.dp),
            ) {
                RoundBackArrow("Open agents",m::openAgentSwitcher,headerSize)
                Box(Modifier.weight(1f)) {
                    Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surfaceVariant) {
                        Box(Modifier.height(headerSize),contentAlignment=androidx.compose.ui.Alignment.Center) {
                            Text(m.agentName.ifBlank{"Agent"},fontSize=20.sp,lineHeight=24.sp,
                                modifier=Modifier.padding(horizontal=15.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                AgentActionsMenu(headerSize,m::openAgentMenu)
            }
        }
        when {
            files != null -> AgentFilesExplorer(files,m::openFilesEntry,m::backInFiles,m::retryFiles,onDownload=saveFile,onUpload=uploadFile,
                onDeleteEntry=m::askDeleteFilesEntry,onConfirmDelete=m::confirmFilesDelete,onCancelDelete=m::cancelFilesDelete,
                onNewFolder=m::askNewFilesFolder,onConfirmNewFolder=m::confirmNewFilesFolder,onCancelNewFolder=m::cancelNewFilesFolder)
            menuOpen -> AgentMenuScreen(m.agentName,m::openAgentFiles,m::dismissAgentMenu)
        }
    }
}

private val HeaderControlSize=48.dp
private val HeaderControlFill=Color(0xFF515151)
private val HeaderControlInk=Color.White

@Composable private fun RoundHeaderButton(
    label:String,onClick:()->Unit,diameter:Dp=HeaderControlSize,enabled:Boolean=true,modifier:Modifier=Modifier,
    emphasized:Boolean=false,
    content:@Composable (Color)->Unit,
) {
    val color=if(enabled) HeaderControlInk else HeaderControlInk.copy(alpha=0.38f)
    Surface(
        onClick=onClick,enabled=enabled,shape=CircleShape,color=HeaderControlFill,
        contentColor=color,border=BorderStroke(if(emphasized) 2.dp else 1.dp,color),
        modifier=modifier.size(diameter).semantics {contentDescription=label;role=Role.Button},
    ) {
        Box(contentAlignment=androidx.compose.ui.Alignment.Center) { content(color) }
    }
}

@Composable internal fun RoundBackArrow(label:String,onClick:()->Unit,diameter:Dp,modifier:Modifier=Modifier) {
    RoundHeaderButton(label,onClick,diameter,modifier=modifier) { color ->
        Canvas(Modifier.size(24.dp)) {
            val center=size.height/2
            val left=Offset(size.width*0.22f,center)
            val width=2.2.dp.toPx()
            drawLine(color,left,Offset(size.width*0.78f,center),width,StrokeCap.Round)
            drawLine(color,left,Offset(size.width*0.48f,size.height*0.24f),width,StrokeCap.Round)
            drawLine(color,left,Offset(size.width*0.48f,size.height*0.76f),width,StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AgentSwitcher(
    m:PocketModel,
    rosterView:AgentRosterView,
    onRosterViewChange:(AgentRosterView)->Unit,
    listState:LazyListState,
) {
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.clearFocus(force=true)
        keyboard?.hide()
    }
    BackHandler(onBack=when {
        m.renamingAgent!=null -> m::cancelRename
        else -> m::dismissAgentSwitcher
    })
    m.renamingAgent?.let {RenameAgentDialog(m,it)}
    val isAgents=rosterView==AgentRosterView.AGENTS
    val title=stringResource(rosterView.label)
    val listLabel=stringResource(if(isAgents) R.string.agent_roster_agent_list else R.string.agent_roster_workspace_list)
    val sessions=m.switchSessions
    val searchScope=rememberCoroutineScope()
    var search by rememberSaveable {mutableStateOf("")}
    var statusFilter by rememberSaveable {mutableStateOf(SpaceStatusFilter.ALL)}
    val isFiltered=search.isNotBlank() || statusFilter!=SpaceStatusFilter.ALL
    val agentRows=remember(sessions,isAgents,search,statusFilter) {
        if(isAgents) filteredAgentRoster(sessions,search,statusFilter) else emptyList()
    }
    val workspaces=remember(sessions,isAgents,search,statusFilter) {
        if(isAgents) emptyList() else filteredSpacesRoster(sessions,search,statusFilter)
    }
    fun resetResultsPosition() {
        searchScope.launch {listState.scrollToItem(0)}
    }
    val refreshLabel=stringResource(R.string.agent_roster_refresh)
    val refreshState=rememberPullToRefreshState()
    val theme=MaterialTheme.colorScheme
    var toolbarPx by remember {mutableIntStateOf(0)}
    val toolbarHeight=maxOf(64.dp,with(LocalDensity.current) {toolbarPx.toDp()})
    val fadeHeight=toolbarHeight+18.dp
    val notice=m.switchMessage.takeIf {it.isNotBlank()}
    var noticePx by remember {mutableIntStateOf(0)}
    val noticeHeight=if(notice==null) 0.dp else with(LocalDensity.current) {noticePx.toDp()}
    val controlsBottom=toolbarHeight+noticeHeight
    val listTop=maxOf(fadeHeight+4.dp,controlsBottom+6.dp)
    var searchFooterPx by remember {mutableIntStateOf(0)}
    val searchFooterHeight=maxOf(64.dp,with(LocalDensity.current) {searchFooterPx.toDp()})

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().semantics {paneTitle=title}) {
        // Controls float above the roster viewport, so rows scroll behind
        // them and the measured content padding lets the last row clear.
        Box(
            Modifier.matchParentSize().pullToRefresh(
                isRefreshing=m.isRefreshingAgents,state=refreshState,
                enabled=m.canRefreshAgents || m.isRefreshingAgents,
                onRefresh={m.refreshAgents()},
            ),
        ) {
            key(rosterView) {
                LazyColumn(
                    modifier=Modifier.fillMaxSize().semantics {
                        contentDescription=listLabel
                        if(m.canRefreshAgents) customActions=listOf(CustomAccessibilityAction(refreshLabel,m::refreshAgents))
                    },
                    state=listState,
                    contentPadding=PaddingValues(start=16.dp,end=16.dp,top=listTop,bottom=searchFooterHeight+12.dp),
                    verticalArrangement=Arrangement.spacedBy(8.dp),
                ) {
                    if(isAgents) {
                        if(agentRows.isEmpty() && !m.isRefreshingAgents) item(key="empty-agents") {
                            RosterEmptyMessage(if(isFiltered) R.string.agent_roster_no_matches else R.string.agent_roster_empty_agents)
                        }
                        rosterRows(m,agentRows)
                    } else {
                        if(workspaces.isEmpty() && !m.isRefreshingAgents) item(key="empty-spaces") {
                            RosterEmptyMessage(if(isFiltered) R.string.agent_roster_no_matches else R.string.agent_roster_empty_spaces)
                        }
                        for(workspace in workspaces) {
                            item(key="workspace:${workspace.id}") {
                                Column(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=4.dp)) {
                                    Text(workspace.label,style=MaterialTheme.typography.titleLarge,
                                        modifier=Modifier.semantics {heading()})
                                    workspace.rows.firstNotNullOfOrNull {it.rosterPlacement?.workspaceBranch}?.let { branch ->
                                        Text(branch,style=MaterialTheme.typography.bodyMedium,color=theme.onSurfaceVariant)
                                    }
                                }
                            }
                            rosterRows(m,workspace.rows,spacesMode=true)
                        }
                    }
                }
            }
        }

        // Draw only: this layer has no pointer input or accessibility semantics.
        Box(
            Modifier.align(androidx.compose.ui.Alignment.TopCenter).fillMaxWidth().height(fadeHeight)
                .background(Brush.verticalGradient(
                    0f to theme.surface.copy(alpha=0.72f),
                    0.45f to theme.surface.copy(alpha=0.38f),
                    0.76f to theme.surface.copy(alpha=0.13f),
                    1f to theme.surface.copy(alpha=0f),
                )),
        )
        PullToRefreshDefaults.Indicator(
            state=refreshState,isRefreshing=m.isRefreshingAgents,
            modifier=Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top=controlsBottom),
        )

        Column(Modifier.align(androidx.compose.ui.Alignment.TopCenter).fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min=64.dp).onSizeChanged {toolbarPx=it.height}
                    .padding(horizontal=12.dp,vertical=4.dp),
                verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(6.dp),
            ) {
                RoundHeaderButton(
                    label=stringResource(R.string.connection_settings),
                    onClick=m::connectionFromAgents,
                    enabled=!m.switchBusy,
                ) { color ->
                    Icon(
                        painter=painterResource(R.drawable.ic_connection_gear),
                        contentDescription=null, tint=color, modifier=Modifier.size(24.dp),
                    )
                }
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                    AgentRosterViewSwitch(rosterView,onRosterViewChange)
                }
                if(m.canCreateAgent) RoundHeaderButton(
                    label=stringResource(R.string.agent_roster_new_terminal),
                    onClick=m::createPlainTerminal,enabled=!m.switchBusy,
                ) { ink ->
                    Canvas(Modifier.size(24.dp)) {
                        val center=size.width/2
                        val edge=size.width*.22f
                        val stroke=2.2.dp.toPx()
                        drawLine(ink,Offset(edge,center),Offset(size.width-edge,center),stroke,StrokeCap.Round)
                        drawLine(ink,Offset(center,edge),Offset(center,size.height-edge),stroke,StrokeCap.Round)
                    }
                } else Spacer(Modifier.size(48.dp))
            }
            if(notice!=null) {
                Surface(
                    modifier=Modifier.fillMaxWidth().onSizeChanged {noticePx=it.height},
                    color=theme.surface.copy(alpha=0.96f),
                ) {
                    Text(notice,fontSize=16.sp,modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp)
                        .semantics {liveRegion=LiveRegionMode.Polite})
                }
            }
        }

        RosterSearchBar(
            query=search,
            onQueryChange={
                search=it.take(256)
                resetResultsPosition()
            },
            status=statusFilter,
            agentsOnly=isAgents,
            onStatusChange={
                statusFilter=it
                resetResultsPosition()
            },
            onSearch={
                focus.clearFocus(force=true)
                keyboard?.hide()
            },
            modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged {searchFooterPx=it.height}
                .padding(horizontal=12.dp,vertical=8.dp),
        )
    }
}

private fun LazyListScope.rosterRows(m:PocketModel,rows:List<CoreSession>,spacesMode:Boolean=false) {
    items(rows,key={ "terminal:${it.address}" }) { session ->
        AgentPickerRow(
            session=session,
            current=session.address==m.activeCoreAddress,
            tui=m.switchingTuiAgents,
            enabled=!m.switchBusy,
            onClick={m.chooseAgent(session)},
            onLongClick=if(m.switchingTuiAgents && session.tuiLocation!=null) {{m.askToRename(session)}} else null,
            spacesMode=spacesMode,
            compactAgent=!spacesMode,
            avatarScope=m.rosterAvatarScope,
        )
    }
}

@Composable private fun RosterSearchBar(
    query:String,
    onQueryChange:(String)->Unit,
    status:SpaceStatusFilter,
    agentsOnly:Boolean,
    onStatusChange:(SpaceStatusFilter)->Unit,
    onSearch:()->Unit,
    modifier:Modifier=Modifier,
) {
    var searchFocused by remember {mutableStateOf(false)}
    val searchLabel=stringResource(R.string.agent_roster_search)
    val searchText=MaterialTheme.typography.bodyLarge.copy(color=HeaderControlInk)
    Row(
        modifier,
        horizontalArrangement=Arrangement.spacedBy(4.dp),
        verticalAlignment=Alignment.CenterVertically,
    ) {
        Surface(
            modifier=Modifier.weight(1f),shape=CircleShape,color=HeaderControlFill,
            contentColor=HeaderControlInk,border=BorderStroke(if(searchFocused) 2.dp else 1.dp,HeaderControlInk),
        ) {
            Row(
                modifier=Modifier.heightIn(min=HeaderControlSize),
                verticalAlignment=Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value=query,onValueChange=onQueryChange,singleLine=true,
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                    keyboardActions=KeyboardActions(onSearch={onSearch()}),
                    textStyle=searchText,cursorBrush=SolidColor(HeaderControlInk),
                    modifier=Modifier.weight(1f)
                        .onFocusChanged {searchFocused=it.isFocused}
                        .semantics {contentDescription=searchLabel},
                    decorationBox={ input ->
                        Box(
                            Modifier.fillMaxWidth().heightIn(min=HeaderControlSize).padding(
                                start=14.dp,end=if(query.isEmpty()) 14.dp else 2.dp,
                                top=5.dp,bottom=5.dp,
                            ),
                            contentAlignment=Alignment.CenterStart,
                        ) {
                            if(query.isEmpty()) Text(
                                stringResource(R.string.agent_roster_search_hint),
                                style=searchText,color=HeaderControlInk.copy(alpha=.82f),
                                maxLines=1,overflow=TextOverflow.Ellipsis,
                            )
                            input()
                        }
                    },
                )
                if(query.isNotEmpty()) {
                    val label=stringResource(R.string.agent_roster_clear_search)
                    IconButton(onClick={onQueryChange("")},
                        modifier=Modifier.size(HeaderControlSize).semantics {contentDescription=label}) {
                        Canvas(Modifier.size(22.dp)) {
                            val stroke=2.dp.toPx()
                            val first=size.width*.28f
                            val last=size.width*.72f
                            drawLine(HeaderControlInk,Offset(first,first),Offset(last,last),stroke,StrokeCap.Round)
                            drawLine(HeaderControlInk,Offset(first,last),Offset(last,first),stroke,StrokeCap.Round)
                        }
                    }
                }
            }
        }
        Box {
            var showFilters by remember {mutableStateOf(false)}
            val filterAction=stringResource(R.string.agent_roster_status_filter)
            val activeFilter=stringResource(status.labelResource(agentsOnly))
            RoundHeaderButton(
                label=filterAction,onClick={showFilters=true},emphasized=status!=SpaceStatusFilter.ALL,
                modifier=Modifier.semantics {stateDescription=activeFilter},
            ) { ink ->
                Canvas(Modifier.size(24.dp)) {
                    val stroke=2.dp.toPx()
                    for((i,length) in listOf(0.76f,0.52f,0.27f).withIndex()) {
                        val y=size.height*(0.26f+0.24f*i)
                        val edge=(1f-length)*size.width/2f
                        drawLine(ink,Offset(edge,y),Offset(size.width-edge,y),stroke,StrokeCap.Round)
                    }
                }
            }
            DropdownMenu(expanded=showFilters,onDismissRequest={showFilters=false}) {
                for(option in SpaceStatusFilter.entries) {
                    DropdownMenuItem(
                        text={Text(stringResource(option.labelResource(agentsOnly)),fontSize=18.sp)},
                        leadingIcon={RadioButton(selected=status==option,onClick=null)},
                        onClick={onStatusChange(option);showFilters=false},
                        modifier=Modifier.heightIn(min=48.dp),
                    )
                }
            }
        }
    }
}

private fun SpaceStatusFilter.labelResource(agentsOnly:Boolean):Int = when(this) {
    SpaceStatusFilter.ALL -> if(agentsOnly) R.string.agent_roster_all_agents else R.string.agent_roster_all_terminals
    SpaceStatusFilter.BLOCKED -> R.string.agent_status_blocked
    SpaceStatusFilter.WORKING -> R.string.agent_status_working
    SpaceStatusFilter.IDLE -> R.string.agent_status_idle
    SpaceStatusFilter.DONE -> R.string.agent_status_done
}

@Composable private fun RosterEmptyMessage(text:Int) {
    Text(stringResource(text),style=MaterialTheme.typography.bodyLarge,
        color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=4.dp,vertical=8.dp))
}

@Composable private fun RenameAgentDialog(m:PocketModel,session:CoreSession) {
    var label by remember(session.address) {mutableStateOf(session.name)}
    AlertDialog(
        onDismissRequest=m::cancelRename,
        title={Text("Rename pane")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("This changes only this pane’s label in Herdr. Its tab, workspace and agent ID stay the same.")
                Field("Pane label",label,{label=it},true)
            }
        },
        confirmButton={
            TextButton(onClick={m.renameAgent(label.trim())},
                enabled=TuiMutations.canRename(label.trim()) && label.trim()!=session.name) {Text("Rename pane")}
        },
        dismissButton={TextButton(onClick=m::cancelRename){Text("Cancel")}},
    )
}

@Composable private fun Field(label:String,value:String,change:(String)->Unit,enabled:Boolean) {
 OutlinedTextField(value=value,onValueChange=change,label={Text(label)},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth())
}

@Composable private fun CoreInstallHelp() {
    val context = LocalContext.current
    val label = stringResource(R.string.core_install_clipboard_label)
    val command = stringResource(R.string.core_install_shell_command)
    var copied by remember { mutableStateOf<Boolean?>(null) }

    Text(stringResource(R.string.core_install_help), fontSize = 16.sp)
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            copied = runCatching {
                val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                clipboard.setPrimaryClip(ClipData.newPlainText(label, command))
            }.isSuccess
        },
    ) { Text(stringResource(R.string.core_install_copy)) }
    copied?.let { ok ->
        Text(
            stringResource(if (ok) R.string.core_install_copied else R.string.core_install_copy_failed),
            fontSize = 16.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
