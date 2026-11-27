package dev.kaspy.herdr

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.ContentResolver
import android.content.SharedPreferences
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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

internal data class TerminalOpeningUi(val title:String,val agentChrome:Boolean)

class PocketModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("workspace", 0)
    internal var dictationPressEnter by mutableStateOf(prefs.getBoolean("dictation_press_enter",true))
        private set
    internal fun setDictationEnter(enabled:Boolean) {
        dictationPressEnter=enabled
        prefs.edit().putBoolean("dictation_press_enter",enabled).apply()
    }
    private val startupGate=StartupReconnect()
    private val resolver:ContentResolver=app.contentResolver
    private var hostRegistry by mutableStateOf(SshHostProfiles.decode(prefs.getString("ssh_host_profiles_v1",null)))
    internal val selectedHostId get()=hostRegistry.selected
    private fun prefsFor(id:String?) =
        getApplication<Application>().getSharedPreferences(SshHostProfiles.preferenceBucket(id),0)
    private val hostPrefs get()=prefsFor(selectedHostId)
    private fun vaultFor(id:String?)=PassphraseVault(getApplication<Application>(),id)
    private val vault get()=vaultFor(selectedHostId)
    private sealed interface KeyPickerOwner {
        data class Active(val id:String,val generation:Long):KeyPickerOwner
        data class Editor(val route:SshNavigation.Edit):KeyPickerOwner
    }
    private var keyPickerOwner:KeyPickerOwner?=null
    internal var editorKey by mutableStateOf<Uri?>(null)
        private set
    internal var hostManageNotice by mutableStateOf("")
        private set
    private var profileRevision by mutableIntStateOf(0)
    private var labelState by mutableStateOf(hostPrefs.getString("connection_label","").orEmpty())
    internal var connectionLabel:String
        get()=labelState
        set(value) {if(!busy) labelState=value.filterNot(Char::isISOControl).take(64)}

    internal val savedHosts:List<SavedSshHost> get() {
        // SharedPreferences is not observable, so bump the revision on writes.
        profileRevision
        return hostRegistry.ids.map { id ->
            val source=prefsFor(id)
            val name=source.getString("connection_label","").orEmpty()
            val endpoint=HostEndpoint.parse(source.getString("host","").orEmpty(),source.getString("port","22").orEmpty())
            val login=source.getString("user","").orEmpty().trim()
            SavedSshHost(id,name.ifBlank {endpoint?.display ?: "New connection"},
                if(endpoint==null) "Not configured" else if(login.isBlank()) endpoint.display else "$login@${endpoint.display}",
                endpoint!=null && login.isNotBlank())
        }
    }
    private fun storeHostRegistry(next:SshHostProfiles,selectInMemory:Boolean=true):Boolean {
        if(!prefs.edit().putString("ssh_host_profiles_v1",next.encode()).commit()) {
            hostManageNotice="Could not save SSH connections. Please try again."
            return false
        }
        if(selectInMemory) hostRegistry=next
        return true
    }

    internal val canManageHosts get()=!busy || status=="Connected · SSH PTY"
    internal val connectionIsOpen get()=displayedPty!=null || hasRosterConnection
    private fun resetForHostSwitch() {
        disconnect()
        // Invalidate queued credential IO before changing the selected profile.
        synchronized(vaultMutation) {credentialEpoch++}
        terminalVisible=false
        demo=false
        clearRetainedOutput()
        watchNotice=null
        filesUploadNotice=null
        nextRosterNotice=null
        interruptedRosterNotice=null
        replaceEmulator()
    }

    private fun loadHostForm() {
        val source=hostPrefs
        labelState=source.getString("connection_label","").orEmpty()
        hostState=source.getString("host","").orEmpty()
        portState=source.getString("port","22").orEmpty()
        userState=source.getString("user","").orEmpty()
        command=source.getString("command","herdr").orEmpty()
        agentName=source.getString("agent","My agent").orEmpty()
        tuiSessionState=source.getString("tui_session","").orEmpty()
        val stored=source.getString("key_document_uri",null)?.let(Uri::parse)?.takeIf {it.scheme=="content"}
        keyUri=stored?.takeIf {runCatching {hasReadGrant(it)}.getOrDefault(false)}
        keyRemembered=keyUri!=null
        if(stored!=null && keyUri==null) {
            source.edit().remove("key_document_uri").commit()
            vault.clear()
        }
        hasSavedPassphrase=selectedHostId!=null && vault.hasSaved()
        credentialNotice=if(stored!=null && keyUri==null) "Key access expired. Select the file again." else ""
        passphrase=""
        saveTypedPassphrase=false
        pinRevision++
    }

    internal fun clearHostManageNotice() {hostManageNotice=""}

    internal var sshNavigation by mutableStateOf<SshNavigation>(SshNavigation.Session)
        private set
    internal fun openSshSettings() {
        liveVoice.stop()
        sshNavigation=SshNavigation.Settings
    }
    internal fun openAppSettings() {
        sshNavigation=SshNavigation.AppSettings
    }
    internal fun backToSshConnections() {
        clearHostManageNotice()
        editorKey=null
        sshNavigation=sshNavigation.back()
    }
    internal val canResumeConnection get()=terminalVisible || showAgentSwitcher
    internal fun resumeConnection() {sshNavigation=SshNavigation.Session}
    private fun showConnectedAgents() {
        if(!showAgentSwitcher && canSwitchAgents) openAgentSwitcher()
        if(showAgentSwitcher) resumeConnection()
    }

    internal fun editHost(id:String?) {
        if(id!=null && id !in hostRegistry.ids) return
        if(id==selectedHostId && id!=null) {
            openSshSettings()
            return
        }
        clearHostManageNotice()
        if(id==null && hostRegistry.ids.size>=SshHostProfiles.MAX_HOSTS) {
            hostManageNotice="Remove an unused connection before adding another."
            return
        }
        editorKey=null
        sshNavigation=SshNavigation.Edit(id)
    }

    internal fun hostEditorFields(id:String?):SshHostEditorFields {
        if(id==null) return SshHostEditorFields()
        val source=prefsFor(id)
        return SshHostEditorFields(
            source.getString("connection_label","").orEmpty(),
            source.getString("host","").orEmpty(),
            source.getString("port","22").orEmpty(),
            source.getString("user","").orEmpty(),
        )
    }

    internal fun saveEditedHost(id:String?,fields:SshHostEditorFields):Boolean {
        val endpoint=HostEndpoint.parse(fields.host,fields.port)
        if(endpoint==null || fields.user.isBlank() || fields.user.any(Char::isISOControl)) {
            hostManageNotice="Enter a valid SSH host, port, and username."
            return false
        }
        val target=id ?: java.util.UUID.randomUUID().toString()
        if(id!=null && (id==selectedHostId || id !in hostRegistry.ids)) return false
        val next=if(id==null) hostRegistry.add(target) else hostRegistry
        if(next==null) {
            hostManageNotice="Keep up to ${SshHostProfiles.MAX_HOSTS} saved connections. Remove an unused connection to add another."
            return false
        }
        val source=prefsFor(target)
        val previousKey=source.getString("key_document_uri",null)?.let(Uri::parse)
        val chosenKey=editorKey
        val keyChanged=chosenKey!=null && chosenKey!=previousKey
        val destinationChanged=source.getString("host",null)!=endpoint.host ||
            source.getString("port","22")!=endpoint.port.toString() ||
            source.getString("user",null)!=fields.user.trim()
        if(chosenKey!=null && !runCatching {
            if(!hasReadGrant(chosenKey)) resolver.takePersistableUriPermission(chosenKey,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            hasReadGrant(chosenKey)
        }.getOrDefault(false)) {
            hostManageNotice="Android could not remember this key. Choose the file again."
            return false
        }
        val ok=source.edit().putString("host",endpoint.host).putString("port",endpoint.port.toString())
            .putString("user",fields.user.trim())
            .putString("connection_label",fields.name.filterNot(Char::isISOControl).take(64)).apply {
                if(keyChanged) putString("key_document_uri",chosenKey.toString()).putBoolean("startup_key_needs_auth",true)
                if(destinationChanged) putBoolean("startup_manually_disconnected",true)
            }.commit()
        if(!ok || (id==null && !storeHostRegistry(next))) {
            if(id==null) source.edit().clear().commit()
            releaseKeyIfUnused(chosenKey)
            hostManageNotice="Could not save this connection."
            return false
        }
        if(keyChanged) {
            synchronized(vaultMutation) {vaultFor(target).clear()}
            releaseKeyIfUnused(previousKey)
        }
        profileRevision++
        backToSshConnections()
        hostManageNotice="SSH connection saved."
        return true
    }

    internal fun savedHostAction(id:String)=savedHostPrimaryAction(
        isSelected=id==selectedHostId,
        connectionOpen=connectionIsOpen,
        hasAgentsView=showAgentSwitcher || hasRosterConnection || canSwitchAgents,
    )

    internal fun openConnectedHost(id:String):Boolean {
        // Recheck on tap. This path never starts SSH or changes the startup host.
        when(savedHostAction(id)) {
            SavedHostPrimaryAction.OPEN_AGENTS -> showConnectedAgents()
            SavedHostPrimaryAction.OPEN_TERMINAL -> if(canResumeConnection) resumeConnection() else return false
            SavedHostPrimaryAction.CONNECT -> return false
        }
        return true
    }

    internal fun connectSavedHost(id:String):Boolean {
        if(!canManageHosts || savedHostAction(id)!=SavedHostPrimaryAction.CONNECT) return false
        val next=hostRegistry.select(id) ?: return false
        if(id!=selectedHostId) {
            // Disconnect while all credential and trust state still resolves to the old host.
            if(!storeHostRegistry(next,selectInMemory=false)) return false
            resetForHostSwitch()
            hostRegistry=next
            loadHostForm()
        }
        connect(list=true,backend=savedRosterBackend())
        return true
    }

    private fun savedRosterBackend()=if(hostPrefs.getString("startup_backend",null)==AgentBackend.CORE.name)
        AgentBackend.CORE else AgentBackend.TUI

    internal fun removeHost(id:String):Boolean {
        if(!canManageHosts) return false
        val next=hostRegistry.remove(id) ?: return false
        val removingSelected=id==selectedHostId
        val source=prefsFor(id)
        val uri=source.getString("key_document_uri",null)?.let(Uri::parse)
        if(!storeHostRegistry(next,selectInMemory=!removingSelected)) return false
        if(removingSelected) {
            resetForHostSwitch()
            hostRegistry=next
            loadHostForm()
        }
        val deleted=if(id==SshHostProfiles.LEGACY) {
            source.edit().apply {source.all.keys.filter(SshHostProfiles::isLegacyHostKey).forEach {remove(it)}}.commit()
        } else source.edit().clear().commit()
        val secretRemoved=synchronized(vaultMutation) {vaultFor(id).clear()}
        releaseKeyIfUnused(uri)
        profileRevision++
        hostManageNotice=if(deleted && secretRemoved) "Saved connection removed."
            else "Connection removed from the list, but Pocket could not confirm all local data was cleared."
        return true
    }

    internal fun saveHostDetails():Boolean {
        if(selectedHostId==null || busy || connectionIsOpen) return false
        val endpoint=selectedEndpoint
        if(endpoint==null || user.isBlank() || user.any(Char::isISOControl)) {
            status="Enter a valid SSH host, port, and username."
            return false
        }
        val namedSession=tuiSession.trim().ifBlank {null}
        if(namedSession!=null && TuiAgents.parseSession(namedSession)!=namedSession) {
            status="Enter a valid Herdr TUI session name or leave it blank."
            return false
        }
        val sameStartup=hostPrefs.getString("startup_host",null)==endpoint.host &&
            hostPrefs.getInt("startup_port",0)==endpoint.port &&
            hostPrefs.getString("startup_user",null)==user.trim() &&
            hostPrefs.getString("startup_tui_session",null)==namedSession
        val ok=hostPrefs.edit().putString("host",endpoint.host).putString("port",endpoint.port.toString())
            .putString("user",user.trim()).putString("connection_label",connectionLabel)
            .putString("command",command).putString("tui_session",namedSession).apply {
                if(!sameStartup) putBoolean("startup_manually_disconnected",true)
            }.commit()
        if(ok) profileRevision++
        status=if(ok) "SSH connection saved." else "Could not save this connection."
        return ok
    }

    internal fun beginKeyPicker():Boolean {
        if(keyPickerOwner!=null) return false
        val route=sshNavigation
        if(route is SshNavigation.Edit) {
            keyPickerOwner=KeyPickerOwner.Editor(route)
        } else {
            if(busy || connectionIsOpen) return false
            keyPickerOwner=KeyPickerOwner.Active(selectedHostId ?: return false,generation)
            noteManualConnectionAction()
        }
        return true
    }

    internal fun keyPicked(uri:Uri?) {
        val owner=keyPickerOwner
        keyPickerOwner=null
        if(uri?.scheme!="content") return
        when(owner) {
            is KeyPickerOwner.Active -> if(owner.id==selectedHostId && owner.generation==generation) selectKey(uri)
            is KeyPickerOwner.Editor -> if(sshNavigation===owner.route) editorKey=uri
            null -> Unit
        }
    }

    internal fun editorHasKey(id:String?) = editorKey!=null || id?.let {hostId ->
        prefsFor(hostId).getString("key_document_uri",null)?.let(Uri::parse)
            ?.let {runCatching {hasReadGrant(it)}.getOrDefault(false)}
    }==true
    private fun hasReadGrant(uri:Uri):Boolean = resolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission }
    private val initialKey=hostPrefs.getString("key_document_uri",null)?.let(Uri::parse)?.takeIf { it.scheme=="content" }
    private val restoredKey=initialKey?.takeIf { runCatching {hasReadGrant(it)}.getOrDefault(false) }
    private val keyGrantRevoked=initialKey!=null && restoredKey==null
    private fun releaseKeyIfUnused(uri:Uri?) {
        if(uri!=null && hostRegistry.ids.none {prefsFor(it).getString("key_document_uri",null)==uri.toString()}) runCatching {resolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
    }
    init {
        if(keyGrantRevoked) {
            hostPrefs.edit().remove("key_document_uri").apply()
            vault.clear()
        }
    }
    var agentName by mutableStateOf(hostPrefs.getString("agent", "My agent")!!)
    private var hostState by mutableStateOf(hostPrefs.getString("host", "")!!)
    private var portState by mutableStateOf(hostPrefs.getString("port", "22")!!)
    var host: String
        get() = hostState
        set(value) { if (value != hostState) { hostState=value; noteManualConnectionAction(); invalidateOffer(); saveTypedPassphrase=false; passphrase=""; if(listedConfig!=null) cancelPicker() } }
    var port: String
        get() = portState
        set(value) { if (value != portState) { portState=value; noteManualConnectionAction(); invalidateOffer(); saveTypedPassphrase=false; passphrase=""; if(listedConfig!=null) cancelPicker() } }
    private val hostTrust = HostTrust(
        read={ endpoint -> hostPrefs.getString(endpoint.preferenceKey,null) },
        write={ endpoint,fingerprint -> hostPrefs.edit().putString(endpoint.preferenceKey,fingerprint).commit() },
    )
    private val selectedEndpoint get()=HostEndpoint.parse(host,port)
    private var pinRevision by mutableIntStateOf(0)
    val storedPin: String?
        get() = pinRevision.let { selectedEndpoint?.let(hostTrust::pin) }
    init {
        val legacyPin=hostPrefs.getString("pin",null)
        if(legacyPin!=null) {
            val endpoint=selectedEndpoint
            // One atomic edit; never leave an unscoped pin available for a future endpoint edit.
            val edit=hostPrefs.edit().remove("pin")
            if(endpoint!=null) hostTrust.legacyPinToMigrate(endpoint,legacyPin)?.let {
                edit.putString(endpoint.preferenceKey,it)
            }
            edit.commit()
        }
    }
    private var userState by mutableStateOf(hostPrefs.getString("user", "")!!)
    var user: String
        get() = userState
        set(value) { if(value!=userState) {userState=value;noteManualConnectionAction();invalidateOffer();saveTypedPassphrase=false;passphrase="";if(listedConfig!=null) cancelPicker()} }
    var command by mutableStateOf(hostPrefs.getString("command", "herdr")!!)
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
        val owner=selectedHostId
        val targetVault=vault
        saveTypedPassphrase=false
        viewModelScope.launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                val saved=withContext(Dispatchers.IO) {
                    synchronized(vaultMutation) {
                        epoch==credentialEpoch && targetVault.save(saving.scope,saving.bytes)
                    }
                }
                if(epoch==credentialEpoch && owner==selectedHostId) {
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
    private var tuiSessionState by mutableStateOf(hostPrefs.getString("tui_session","").orEmpty())
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
        liveVoice.stop()
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

    internal fun leaveTerminalRecovery() {if(terminalRecovery!=null) backToSshConnections()}

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
        if(selectedHostId==null || hostPrefs.getBoolean("startup_manually_disconnected",false)) return
        val profile=StartupReconnectProfile(
            hostPrefs.getString("startup_host",null) ?: host,
            hostPrefs.getInt("startup_port",port.trim().toIntOrNull() ?: 0),
            hostPrefs.getString("startup_user",null) ?: user,
        )
        val endpoint=HostEndpoint.parse(profile.host,profile.port.toString())
        val pin=endpoint?.let(hostTrust::pin)
        val unverifiedReplacement=hostPrefs.getBoolean("startup_key_needs_auth",false)
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
            it.name==hostPrefs.getString("startup_backend",null)
        } ?: AgentBackend.TUI
        if(backend==AgentBackend.TUI) tuiSessionState=hostPrefs.getString("startup_tui_session",tuiSession).orEmpty()
        connect(list=true,automatic=true,backend=backend)
    }

    private fun saveStartupProfile(config:SshConfig,backend:AgentBackend,tuiSession:String?) {
        if(!keyRemembered) return
        hostPrefs.edit().putString("startup_host",config.host)
            .putInt("startup_port",config.port)
            .putString("startup_user",config.user)
            .putString("startup_backend",backend.name)
            .putString("startup_tui_session",tuiSession)
            .remove("startup_key_needs_auth")
            .remove("startup_manually_disconnected")
            .apply()
    }

    private fun saveRecentSession(target:ResumeTarget, addressKey:String, address:String) {
        hostPrefs.edit().putString(addressKey,address).apply()
        saveStartupProfile(target.config,target.backend,target.tuiSession)
    }
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
                if(finishStartup()) {
                    initialTerminalOpening=null
                    terminalVisible=true
                }
            }
        }
    }
    fun cancelPicker() {
        if(ssh.hasIdleTransport()) ssh.disconnect()
        coldRosterOperation=null
        coldAttach=false
        clearRosterWatchLease()
        if(showSessionPicker) clearAgentSwitcher()
        pendingColdWatchTerminal=null
        showSessionPicker=false
        sessions=emptyList()
        listedConfig=null
        listedBackend=AgentBackend.CORE
        listedTuiSession=null
        passphrase=""
    }

    private fun publishSessionList() {
        val backend=listedBackend
        sessions=backend.parse(listBytes.toString("UTF-8"))
        listedConfig?.let {saveStartupProfile(it,backend,listedTuiSession)}
        val automatic=startupInFlight
        val preferred=requestedAgent
        val watchTarget=pendingColdWatchTerminal
        pendingColdWatchTerminal=null
        val selection=explicitRosterSelection(sessions,watchTarget,preferred)
        if(selection!=null) {
            clearRosterWatchLease()
            connect(attach=selection,automatic=automatic,backend=backend,tuiSessionName=listedTuiSession,
                keepRetainedTerminal=retainedTerminal)
        } else {
            retainedTerminal=false
            clearRetainedOutput()
            finishStartup()
            terminalVisible=false
            showSessionPicker=true
            switchSessions=sessions.filter {it.state=="live"}.distinctBy {it.address}
            showAgentSwitcher=true
            switchMessage=when {
                watchTarget!=null -> "That agent is no longer available in this Herdr session."
                preferred!=null -> "The previous agent is not available. Choose a live ${backend.label} session."
                else -> ""
            }
            status=if(ssh.hasIdleTransport()) "Connected · SSH" else "Choose a live ${backend.label} session."
            val lease=pendingRosterWatchLease
            pendingRosterWatchLease=null
            if(lease!=null) {
                if(lease.config==listedConfig && lease.session==listedTuiSession) {
                    rosterWatchLease?.wipe()
                    rosterWatchLease=lease
                } else lease.wipe()
            }
        }
    }
    private var listedConfig:SshConfig?=null
    private var listedTuiSession:String?=null
    internal var listedBackend by mutableStateOf(AgentBackend.CORE)
        private set
    private var listing=false
    private var coldRosterOperation:Long?=null
    private var coldAttach=false
    internal val hasRosterConnection get()=showSessionPicker && status=="Connected · SSH"
    private val listBytes=ByteArrayOutputStream()
    private val listErrors=ByteArrayOutputStream()
    var status by mutableStateOf("Disconnected")
    var busy by mutableStateOf(false)
    var terminalVisible by mutableStateOf(false)
    var demo by mutableStateOf(false)
    var observedPin by mutableStateOf<String?>(null)
        private set
    internal var offeredTrust by mutableStateOf<HostTrustOffer?>(null)
        private set
    private data class Request(val list:Boolean,val autoPick:Boolean,val backend:AgentBackend,val tuiSession:String?)
    private data class Probe(val endpoint:HostEndpoint,val generation:Long,val reviewChange:Boolean,val resumeRequest:Request?=null)
    private var probeContext:Probe?=null
    private var offeredRequest:Request?=null
    private var hostKeyMismatch by mutableStateOf(false)
    val canReviewHostKey get()=hostKeyMismatch && !busy
    private fun invalidateOffer() {
        offeredTrust=null;offeredRequest=null;observedPin=null;hostKeyMismatch=false
        if(probeContext!=null) {
            generation++;ssh.disconnect();busy=false;listing=false;probeContext=null
            status="Host check canceled."
        }
    }
    internal fun rejectHost(offer:HostTrustOffer) {
        if(offeredTrust!==offer) return
        val replacing=offer.previousPin!=null
        invalidateOffer();passphrase="";saveTypedPassphrase=false
        hostKeyMismatch=replacing
        status=if(replacing) "Saved host fingerprint unchanged. No authentication was sent."
            else "Host not trusted. No authentication was sent."
    }
    internal fun trustOfferedHost(offer:HostTrustOffer) {
        if(offeredTrust!==offer) return
        val request=offeredRequest
        if(!hostTrust.accept(offer,selectedEndpoint,generation)) {
            invalidateOffer();status="Host not saved. Check its fingerprint again.";return
        }
        pinRevision++
        invalidateOffer()
        if(offer.previousPin!=null) {
            passphrase="";saveTypedPassphrase=false
            status="SSH host fingerprint replaced. Connect again when ready."
            return
        }
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
        displayedPty?.takeIf {sshNavigation==SshNavigation.Session && !retainedTerminal && !demo && busy && terminalVisible && !showAgentSwitcher &&
            status=="Connected · SSH PTY" && ssh.terminalIdentity()===it}
    internal val liveVoice by lazy {
        PocketLiveVoice(getApplication<Application>(),prefs,viewModelScope,ssh) {
            val target=resume?.takeIf {it.backend==AgentBackend.TUI}
            val address=activeCoreAddress
            val pty=speechTerminalIdentity()
            val hostScope=target?.let {AgentPinScope.from(it.config,it.backend,it.tuiSession)}
            if(target!=null && address!=null && target.address==address && pty!=null && hostScope!=null &&
                !agentMenuOpen && filesUi==null)
                TuiLiveAnchor(pty,address,target.tuiSession,LiveExtractionWindow.preferenceKey(hostScope,address))
            else null
        }
    }

    fun sendDictation(identity:Any,text:String,pressEnter:Boolean,stillAllowed:()->Boolean,completed:(Boolean)->Unit):Boolean {
        val renderer=emulator
        if(!stillAllowed() || speechTerminalIdentity()!==identity) return false
        return renderer.postEncodedUserLine(text,pressEnter=pressEnter) { bytes ->
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
        if(sshNavigation==SshNavigation.Session && demo && terminalVisible && !retainedTerminal) return PhysicalWheelRoute(emulator,0,false)
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
    private class ResumeTarget(val config:SshConfig,val key:ByteArray,val passphrase:ByteArray?,var command:String,var address:String,val backend:AgentBackend,val tuiSession:String?,var retries:Int=0) {
        fun clear() {key.fill(0);passphrase?.fill(0)}
    }
    private val agentPins=AgentPins(
        read={ key -> hostPrefs.getStringSet(key,emptySet()).orEmpty() },
        write={ key, pins -> hostPrefs.edit().putStringSet(key,pins).apply() },
    )
    private var agentPinRevision by mutableIntStateOf(0)
    private fun currentPinScope():AgentPinScope? {
        val target=resume
        return if(target!=null) AgentPinScope.from(target.config,target.backend,target.tuiSession)
            else listedConfig?.takeIf {showSessionPicker}?.let {AgentPinScope.from(it,listedBackend,listedTuiSession)}
    }
    internal val rosterPins:Set<String> get() =
        agentPinRevision.let { currentPinScope()?.let(agentPins::values).orEmpty() }

    internal fun isAgentPinned(session:CoreSession):Boolean = session.isAgent && session.address in rosterPins

    internal fun canPinAgent(session:CoreSession):Boolean {
        if(!session.isAgent || session.state!="live" || !AgentPins.validAddress(session.address) ||
            currentPinScope()==null) return false
        val exactRoster=showAgentSwitcher && !switchBusy && switchSessions.any {it===session} &&
            (showSessionPicker || canSwitchAgents && displayedPty!=null && ssh.terminalIdentity()===displayedPty)
        val exactProfile=agentSettingsRoute?.let {currentAgentSettings(it) &&
            activeAgentDetails===session && it.address==session.address}==true
        if(!exactRoster && !exactProfile) return false
        val pins=rosterPins
        return session.address in pins || pins.size<AgentPins.MAX_PINS
    }

    internal fun toggleAgentPin(session:CoreSession) {
        if(!canPinAgent(session)) return
        val scope=currentPinScope() ?: return
        if(agentPins.toggle(scope,session.address)) agentPinRevision++
    }

    internal val rosterAvatarScope:AgentAvatarScope?
        get()=resume?.let {target ->
            AgentAvatarScope(target.backend.name,target.config.host,target.config.port,target.config.user,target.tuiSession)
        } ?: listedConfig?.takeIf {showSessionPicker}?.let { config ->
            AgentAvatarScope(listedBackend.name,config.host,config.port,config.user,listedTuiSession)
        }

    internal var watchNotice by mutableStateOf<String?>(null)
        private set
    private var pendingWatchTerminal:AgentWatchAlertTarget?=null
    private var pendingColdWatchTerminal:String?=null
    private class RosterWatchLease(
        val config:SshConfig,val session:String?,val key:ByteArray,val passphrase:ByteArray?,
    ) {
        fun wipe() {key.fill(0);passphrase?.fill(0)}
    }
    private var pendingRosterWatchLease:RosterWatchLease?=null
    private var rosterWatchLease by mutableStateOf<RosterWatchLease?>(null)
    private var authenticatedWatchTerminal by mutableStateOf<ResumeTarget?>(null)
    private val watchStartGate=AgentWatchStartGate()
    internal var notificationsEnabled by mutableStateOf(prefs.getBoolean(AGENT_NOTIFICATIONS_ENABLED,false))
        private set
    private val notificationPrefListener=SharedPreferences.OnSharedPreferenceChangeListener { preferences,key ->
        if(key==AGENT_NOTIFICATIONS_ENABLED) {
            notificationsEnabled=preferences.getBoolean(AGENT_NOTIFICATIONS_ENABLED,false)
            if(!notificationsEnabled) watchStartGate.reset()
        }
    }
    init {prefs.registerOnSharedPreferenceChangeListener(notificationPrefListener)}

    private fun clearRosterWatchLease() {
        pendingRosterWatchLease?.wipe();pendingRosterWatchLease=null
        rosterWatchLease?.wipe();rosterWatchLease=null
    }

    private fun watchProfileMatches(config:SshConfig,session:String?):Boolean {
        val endpoint=selectedEndpoint ?: return false
        // Never run from a lease for stale settings, a different account, or an unpinned route.
        return config.host==endpoint.host && config.port==endpoint.port && config.user==user.trim() &&
            config.expectedPin?.let(::isSshSha256Pin)==true && config.expectedPin==storedPin &&
            normalizedWatchSession(session)==normalizedWatchSession(tuiSession)
    }

    internal val watchLeaseToken:Any?
        get() = authenticatedWatchTerminal?.takeIf {
            canSwitchAgents && displayedPty!=null && ssh.terminalIdentity()===displayedPty &&
                it.backend==AgentBackend.TUI && watchProfileMatches(it.config,it.tuiSession)
        } ?: rosterWatchLease?.takeIf {showSessionPicker && listedBackend==AgentBackend.TUI && watchProfileMatches(it.config,it.session)}

    private fun activeWatchScope():AgentWatchScope? = resume?.takeIf {
        it.backend==AgentBackend.TUI && isSshSha256Pin(it.config.expectedPin.orEmpty())
    }?.let {AgentWatchScope(it.config,normalizedWatchSession(it.tuiSession))}

    internal fun clearWatchNotice() {watchNotice=null}
    internal fun resetWatchStartGate() = watchStartGate.reset()

    internal fun setNotificationsEnabled(enabled:Boolean) {
        notificationsEnabled=enabled
        prefs.edit().putBoolean(AGENT_NOTIFICATIONS_ENABLED,enabled).apply()
        if(!enabled) {
            watchStartGate.reset()
            AgentWatchService.stop(getApplication<Application>())
        }
    }

    internal fun maybeStartAgentWatch(activity:MainActivity) {
        if(!notificationsEnabled || !AgentWatchService.canNotify(activity)) return
        val lease=watchLeaseToken ?: return
        val (config,session,key,passphrase)=when(lease) {
            is ResumeTarget -> WatchCredentials(lease.config,lease.tuiSession,lease.key,lease.passphrase)
            is RosterWatchLease -> WatchCredentials(lease.config,lease.session,lease.key,lease.passphrase)
            else -> return
        }
        val active=AgentWatchService.state.value as? AgentWatchServiceState.Active
        val scope=AgentWatchScope(config,normalizedWatchSession(session))
        if(!watchStartGate.claim(lease)) return
        if(active?.scope==scope) return
        if(active!=null) AgentWatchService.stop(activity)
        AgentWatchService.startFromVisible(activity,config,session,key,passphrase)
    }

    private data class WatchCredentials(
        val config:SshConfig,val session:String?,val key:ByteArray,val passphrase:ByteArray?,
    )

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
            resumeConnection()
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
    internal var createWorkspaceChoices by mutableStateOf<List<TuiWorkspaceChoice>>(emptyList())
        private set
    private var nextRosterNotice:String?=null
    private var interruptedRosterNotice:String?=null
    private sealed interface TuiRosterWork {
        data class VerifyRename(val location:TuiLocation,val label:String,val kind:TuiMutations.RenameTarget):TuiRosterWork
        data class Rename(val location:TuiLocation,val label:String,val kind:TuiMutations.RenameTarget):TuiRosterWork
        data class CreateShell(val workspace:TuiWorkspaceChoice?):TuiRosterWork
        data class VerifyNewPane(val location:TuiLocation):TuiRosterWork
    }
    private var tuiWork:TuiRosterWork?=null
    var switchMessage by mutableStateOf("")
        private set
    private var switchOperation by mutableStateOf<Long?>(null)
    val switchBusy get() = switchOperation!=null || showSessionPicker && busy
    private var rosterRefresh by mutableStateOf(AgentRosterRefresh())
    val isRefreshingAgents get()=rosterRefresh.active || showSessionPicker && busy && listing
    val canRefreshAgents get()=canRefreshAgentRoster(
        visible=showAgentSwitcher,connected=canSwitchAgents || showSessionPicker,
        hasAgentTarget=resume!=null || showSessionPicker && listedConfig!=null,
        operationPending=switchBusy,dialogOpen=renamingAgent!=null,
    )
    private var switchCandidate:CoreSession?=null
    private var initialTerminalOpening by mutableStateOf<TerminalOpeningUi?>(null)

    internal val terminalOpening:TerminalOpeningUi? get() {
        if(switchOperation!=null) switchCandidate?.let {return TerminalOpeningUi(it.name,agentChrome=true)}
        return initialTerminalOpening.takeIf {busy && !terminalVisible}
    }

    internal fun cancelTerminalOpening() {
        if(terminalOpening==null) return
        if(switchOperation!=null && switchCandidate!=null) dismissAgentSwitcher()
        else disconnect()
    }
    val canSwitchAgents get() = activeCoreAddress!=null && busy && status=="Connected · SSH PTY"

    val canCreateAgent get() = resume!=null
    val switchingTuiAgents get() = (resume?.backend ?: listedBackend.takeIf {showSessionPicker})==AgentBackend.TUI

    internal var agentMenuOpen by mutableStateOf(false)
        private set
    internal var filesUi by mutableStateOf<AgentFilesUiState?>(null)
        private set
    internal var agentSettingsOpen by mutableStateOf(false)
        private set
    internal var activeAgentDetails by mutableStateOf<CoreSession?>(null)
        private set
    internal var agentSettingsNotice by mutableStateOf<String?>(null)
        private set
    internal var agentSettingsLoading by mutableStateOf(false)
        private set
    private class AgentSettingsRoute(val pty:Any,val address:String,val target:ResumeTarget)
    private sealed interface AgentSettingsWork {
        data class Read(val renamed:TuiMutations.RenameTarget?=null) : AgentSettingsWork
        data class Verify(val location:TuiLocation,val label:String,val kind:TuiMutations.RenameTarget) : AgentSettingsWork
        data class Rename(val location:TuiLocation,val label:String,val kind:TuiMutations.RenameTarget) : AgentSettingsWork
    }
    private var agentSettingsRoute:AgentSettingsRoute?=null
    private var agentSettingsRequest:Long?=null
    private var agentSettingsWork:AgentSettingsWork?=null

    private fun currentAgentSettings(route:AgentSettingsRoute):Boolean =
        agentSettingsRoute===route && agentSettingsOpen && agentMenuOpen && filesUi==null &&
            displayedPty===route.pty && speechTerminalIdentity()===route.pty &&
            activeCoreAddress==route.address && resume===route.target && route.target.address==route.address

    private var codexProgressOperation:Pair<Long,CompletableDeferred<SshSession.Event.OperationList>>?=null
    internal val codexProgressAnchor:Any? get() {
        val route=agentSettingsRoute ?: return null
        val agent=activeAgentDetails ?: return null
        return route.takeIf {currentAgentSettings(it) && it.target.backend==AgentBackend.TUI &&
            agent.address==it.address && agent.agent.equals("codex",true) &&
            agent.tuiLocation?.terminalId==it.address}
    }

    internal suspend fun readCodexProgress(anchor:Any):CodexProgress.Snapshot {
        val unavailable=CodexProgress.Snapshot(CodexProgress.Availability.UNAVAILABLE)
        val route=anchor as? AgentSettingsRoute ?: return unavailable
        if(codexProgressAnchor!==route) return unavailable
        val location=activeAgentDetails?.tuiLocation ?: return unavailable
        suspend fun query(command:String,input:SshSession.StdioExchange?=null):LiveAgentHerdr.Reply? {
            if(codexProgressAnchor!==route || codexProgressOperation!=null) return null
            val id=(if(input==null) ssh.listOnTransport(command) else ssh.exchangeOnTransport(command,input)) ?: return null
            val result=CompletableDeferred<SshSession.Event.OperationList>()
            codexProgressOperation=id to result
            try {
                val event=withTimeoutOrNull(8_000) {result.await()} ?: return null
                if(codexProgressAnchor!==route || activeAgentDetails?.tuiLocation!=location) return null
                return LiveAgentHerdr.Reply(event.stdout.toString(Charsets.UTF_8),event.exitStatus==0)
            } finally {
                if(codexProgressOperation?.first==id) codexProgressOperation=null
                ssh.cancelOperation(id)
            }
        }
        val verify=LiveAgentHerdr.get(location,route.target.tuiSession)
        fun instance(reply:LiveAgentHerdr.Reply?)=reply?.let {LiveAgentHerdr.status(it,location)}?.instance
        val before=instance(query(verify)) ?: return unavailable
        val thread=LiveCodexHistory.threadId(before)
            ?: return CodexProgress.Snapshot(CodexProgress.Availability.NO_SESSION)
        val exchange=SshSession.StdioExchange(LiveCodexHistory.request(thread),LiveCodexHistory::responseSeen)
        val response=query(LiveCodexHistory.command,exchange)?.takeIf {it.ok} ?: return unavailable
        if(instance(query(verify))!=before) return unavailable
        return LiveCodexHistory.parse(response.stdout)?.let(CodexProgress::from) ?: unavailable
    }

    private fun acceptCodexProgress(event:SshSession.Event.OperationList):Boolean {
        val (id,result)=codexProgressOperation ?: return false
        if(event.id!=id) return false
        result.complete(event)
        return true
    }

    internal val canRenameActiveAgent:Boolean get() {
        val route=agentSettingsRoute ?: return false
        val details=activeAgentDetails ?: return false
        return currentAgentSettings(route) && route.target.backend==AgentBackend.TUI && !agentSettingsLoading &&
            details.address==route.address && details.tuiLocation?.terminalId==route.address
    }

    private fun openAgentSettings() {
        if(!agentMenuOpen || agentSettingsOpen || filesUi!=null) return
        val target=resume ?: return
        val pty=speechTerminalIdentity() ?: return
        val address=activeCoreAddress?.takeIf {it==target.address} ?: return
        val route=AgentSettingsRoute(pty,address,target)
        agentSettingsRoute=route
        activeAgentDetails=null
        agentSettingsOpen=true
        agentSettingsNotice=null
        refreshAgentSettings()
    }

    internal fun refreshAgentSettings() {
        val route=agentSettingsRoute ?: return
        if(!currentAgentSettings(route) || agentSettingsLoading) return
        beginAgentSettings(route,AgentSettingsWork.Read(),
            route.target.backend.listCommand(route.target.tuiSession))
    }

    internal fun dismissAgentSettings() {
        // Once a verified rename is sent, keep the profile visible for its
        // acknowledgment; abandoning the route on connection loss still works.
        if(agentSettingsWork is AgentSettingsWork.Rename) return
        dismissAgentMenu()
    }

    private fun clearAgentSettings() {
        agentSettingsRequest?.let(ssh::cancelOperation)
        if(agentSettingsOpen) renamingAgent=null
        agentSettingsRoute=null
        agentSettingsRequest=null
        agentSettingsWork=null
        activeAgentDetails=null
        agentSettingsLoading=false
        agentSettingsNotice=null
        agentSettingsOpen=false
    }

    internal fun askToRenameActiveAgent() {
        if(canRenameActiveAgent) renamingAgent=activeAgentDetails
    }

    private fun beginAgentSettings(route:AgentSettingsRoute,work:AgentSettingsWork,command:String) {
        if(!currentAgentSettings(route) || agentSettingsRequest!=null) return
        agentSettingsNotice=null
        val id=ssh.listOnTransport(command)
        if(id==null) {
            agentSettingsNotice="The connection is busy. Try again."
            return
        }
        agentSettingsWork=work
        agentSettingsRequest=id
        agentSettingsLoading=true
    }

    private fun acceptAgentSettings(event:SshSession.Event.OperationList) {
        val route=agentSettingsRoute
        val work=agentSettingsWork
        agentSettingsRequest=null
        agentSettingsWork=null
        agentSettingsLoading=false
        if(route==null || !currentAgentSettings(route)) return
        val response=event.stdout.toString(Charsets.UTF_8)
        val ok=event.exitStatus==0
        when(work) {
            is AgentSettingsWork.Read -> {
                val details=if(ok) runCatching {activeProfileRow(route.target.backend.parse(response),route.address)}.getOrNull() else null
                activeAgentDetails=details
                if(details!=null) {
                    agentName=details.name
                    if(work.renamed!=null) {
                        hostPrefs.edit().putString("agent",details.name).apply()
                        agentSettingsNotice="${work.renamed.displayName} renamed."
                    }
                } else agentSettingsNotice=if(work.renamed!=null)
                    "${work.renamed.displayName} renamed, but the profile didn’t refresh."
                else "Could not read this terminal’s profile. Refresh to try again."
            }
            is AgentSettingsWork.Verify -> {
                if(!ok || !TuiMutations.confirmsTarget(response,work.location,work.kind)) {
                    activeAgentDetails=null
                    agentSettingsNotice=TuiMutations.renameRejection(response,event.stderr.toString(Charsets.UTF_8))
                        ?: "The rename target changed. Refresh before renaming."
                    return
                }
                beginAgentSettings(route,AgentSettingsWork.Rename(work.location,work.label,work.kind),
                    TuiMutations.renameTarget(work.location,work.kind,work.label,route.target.tuiSession))
            }
            is AgentSettingsWork.Rename -> {
                if(ok && TuiMutations.confirmsTarget(response,work.location,work.kind,work.label)) {
                    beginAgentSettings(route,AgentSettingsWork.Read(work.kind),
                        route.target.backend.listCommand(route.target.tuiSession))
                } else {
                    activeAgentDetails=null
                    agentSettingsNotice=TuiMutations.renameRejection(response,event.stderr.toString(Charsets.UTF_8))
                        ?: "Could not confirm the rename. Refresh before trying again."
                }
            }
            null -> Unit
        }
    }

    private fun failedAgentSettings() {
        val route=agentSettingsRoute
        val work=agentSettingsWork
        agentSettingsRequest=null
        agentSettingsWork=null
        agentSettingsLoading=false
        if(route==null || !currentAgentSettings(route)) return
        activeAgentDetails=null
        agentSettingsNotice=when(work) {
            is AgentSettingsWork.Rename -> "Could not confirm the rename. Refresh before trying again."
            is AgentSettingsWork.Verify -> "Could not verify the target. No rename was sent."
            else -> "Could not read this terminal’s profile. Refresh to try again."
        }
    }

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
        if (canSwitchAgents && terminalVisible && !showAgentSwitcher) {
            liveVoice.stop()
            agentMenuOpen = true
            openAgentSettings()
        }
    }

    internal fun dismissAgentMenu() {
        clearAgentSettings()
        closeFiles()
        agentMenuOpen = false
    }

    internal fun openAgentFiles() {
        if (!agentMenuOpen || agentSettingsLoading || filesUi != null) return
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
            entry !in ui.entries || !entry.canOpen) return
        val prompt=AgentFileDeletePrompt(entry.name,folder,entry.isDirectory)
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
            expected=target.entry.identity,directory=target.entry.isDirectory) { request,result ->
            main.post {
                if(filesDeleteRequest!==request || pendingFilesDelete!==target ||
                    !currentFilesRoute(target.route) ||
                    !target.anchor.matches(filesRoot?.path,filesFolder,filesUi?.folder)) return@post
                filesDeleteRequest=null
                pendingFilesDelete=null
                when(result) {
                    SftpDelete.Success -> {
                        filesUi=filesUi?.copy(deleting=false,deleteMessage=if(target.entry.isDirectory) "Folder deleted." else "File deleted.")
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
        liveVoice.stop()
        showAgentSwitcher=true
        refreshAgents()
    }

    fun refreshAgents():Boolean {
        if(!canRefreshAgents) return false
        if(showSessionPicker) {
            val previous=listedConfig ?: return false
            val endpoint=selectedEndpoint ?: return false
            if(previous!=SshConfig(endpoint.host,endpoint.port,user.trim(),hostTrust.pin(endpoint)) ||
                listedBackend==AgentBackend.TUI && listedTuiSession!=tuiSession.trim().ifBlank {null}) {
                switchMessage="Connection details changed. Connect again from Settings."
                return false
            }
            if(ssh.hasIdleTransport()) return listColdRoster()
            connect(list=true,pinnedOnly=true,backend=listedBackend,tuiSessionName=listedTuiSession,keepColdRoster=true)
            return busy && listing
        }
        val target=resume ?: return false
        switchCandidate=null
        if(target.backend==AgentBackend.TUI) {
            tuiRosterFresh=false
            tuiCanCreate=false
            createWorkspaceChoices=emptyList()
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
        if(showSessionPicker) {
            if(session in switchSessions && session.state=="live") {
                if(ssh.hasIdleTransport()) attachFromColdRoster(session)
                else connect(attach=session,pinnedOnly=true,backend=listedBackend,tuiSessionName=listedTuiSession)
            }
            return
        }
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

    private fun listColdRoster():Boolean {
        val id=ssh.listOnTransport(listedBackend.listCommand(listedTuiSession)) ?: return false
        busy=true
        listing=true
        coldRosterOperation=id
        switchMessage=""
        return true
    }

    private fun finishColdRoster(event:SshSession.Event.OperationList) {
        coldRosterOperation=null
        busy=false
        listing=false
        if(event.exitStatus==0) try {
            listBytes.reset()
            listBytes.write(event.stdout)
            publishSessionList()
            return
        } catch(_:Exception) {
            failColdRoster("Could not read ${listedBackend.label} agent data. Check the installed version.")
            return
        }
        failColdRoster(listedBackend.listFailure(event.exitStatus,event.stderr))
    }

    private fun failColdRoster(message:String) {
        coldRosterOperation=null
        busy=false
        listing=false
        if(!startupInFlight && showSessionPicker && ssh.hasIdleTransport()) {
            switchMessage=message
            return
        }
        if(finishStartup() || !showSessionPicker || switchSessions.isEmpty()) {
            reconnectOffered=reconnectTarget!=null
            cancelPicker()
            status=message
        } else {
            switchMessage=message
        }
    }

    private fun attachFromColdRoster(session:CoreSession) {
        val lease=rosterWatchLease?.takeIf {it.config==listedConfig && it.session==listedTuiSession} ?: return
        val endpoint=selectedEndpoint ?: return
        if(lease.config!=SshConfig(endpoint.host,endpoint.port,user.trim(),storedPin)) return
        val command=listedBackend.attachCommand(session.address,listedTuiSession)
        val operation=ssh.switchPty(listedBackend.switchCommand(session.address,listedTuiSession),
            cols,rows,tui=listedBackend==AgentBackend.TUI)
        if(operation==null) {
            switchMessage="The SSH connection is not ready. Refresh and try again."
            return
        }
        resume=ResumeTarget(lease.config,lease.key.copyOf(),lease.passphrase?.copyOf(),command,session.address,
            listedBackend,listedTuiSession)
        coldAttach=true
        switchCandidate=session
        switchOperation=operation
        switchMessage="Opening ${session.name}…"
    }

    private fun clearColdAttach() {
        if(!coldAttach) return
        coldAttach=false
        resume?.clear()
        resume=null
        switchCandidate=null
        switchOperation=null
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

    internal fun createPlainTerminal(workspace:TuiWorkspaceChoice?=null) {
        if(!showAgentSwitcher || !canSwitchAgents || switchBusy || renamingAgent!=null) return
        val target=resume ?: return
        if(target.backend==AgentBackend.TUI) {
            if(!tuiCanCreate || !tuiRosterFresh) {
                switchMessage="Creating a shell from Pocket needs Herdr 0.7.5–0.9.x and a refreshed session list."
                return
            }
            if(workspace!=null && workspace !in createWorkspaceChoices) {
                switchMessage="That workspace is no longer available. Refresh the list and choose again."
                return
            }
            val command=if(workspace==null) TuiMutations.createShellWorkspace(target.tuiSession)
                else TuiMutations.createShellTab(workspace.id,target.tuiSession)
            beginTuiWork(TuiRosterWork.CreateShell(workspace),command,
                if(workspace==null) "Creating a new workspace…" else "Creating a shell in ${workspace.label}…",
                "Could not send the request. Nothing was created.")
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

    internal fun renameAgent(label:String,kind:TuiMutations.RenameTarget) {
        val target=resume?.takeIf {it.backend==AgentBackend.TUI} ?: return
        val selected=renamingAgent ?: return
        val location=selected.tuiLocation ?: return
        if(kind!=TuiMutations.RenameTarget.TAB &&
            (kind==TuiMutations.RenameTarget.AGENT)!=selected.isAgent) return
        if(!TuiMutations.canRenameTarget(label,kind)) return
        if(agentSettingsOpen) {
            val route=agentSettingsRoute ?: return
            if(!canRenameActiveAgent || selected!==activeAgentDetails || selected.address!=route.address ||
                location.terminalId!=route.address) return
            renamingAgent=null
            beginAgentSettings(route,AgentSettingsWork.Verify(location,label,kind),
                TuiMutations.inspectTarget(location,kind,target.tuiSession))
            return
        }
        if(!showAgentSwitcher || !canSwitchAgents || switchBusy || selected !in switchSessions ||
            location.terminalId!=selected.address) return
        renamingAgent=null
        beginTuiWork(TuiRosterWork.VerifyRename(location,label,kind),TuiMutations.inspectTarget(location,kind,target.tuiSession),
            "Checking the rename target…","Could not check the target. Nothing was changed.")
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
            is TuiRosterWork.VerifyRename -> if(ok && TuiMutations.confirmsTarget(text,work.location,work.kind)) {
                beginTuiWork(TuiRosterWork.Rename(work.location,work.label,work.kind),
                    TuiMutations.renameTarget(work.location,work.kind,work.label,target.tuiSession),
                    "Renaming the ${work.kind.displayName.lowercase()} in Herdr…",
                    "Could not send the rename. Nothing was changed.")
            } else switchMessage=TuiMutations.renameRejection(text,event.stderr.toString(Charsets.UTF_8))
                ?: "The rename target moved or is no longer available. Refresh before renaming."
            is TuiRosterWork.Rename -> if(ok && TuiMutations.confirmsTarget(text,work.location,work.kind,work.label)) {
                nextRosterNotice="${work.kind.displayName} renamed."
                refreshAgents()
            } else switchMessage=TuiMutations.renameRejection(text,event.stderr.toString(Charsets.UTF_8))
                ?: "Could not confirm the rename. Refresh before trying again."
            is TuiRosterWork.CreateShell -> {
                val fresh=if(ok) TuiMutations.newShellPane(text,work.workspace?.id) else null
                if(fresh!=null && fresh.terminalId!=activeCoreAddress &&
                    (work.workspace!=null || createWorkspaceChoices.none {it.id==fresh.workspaceId}) && switchSessions.none {
                    it.address==fresh.terminalId || it.tuiLocation?.paneId==fresh.paneId ||
                        (work.workspace==null && it.tuiLocation?.workspaceId==fresh.workspaceId) ||
                        it.tuiLocation?.tabId==fresh.tabId
                }) {
                    beginTuiWork(TuiRosterWork.VerifyNewPane(fresh),
                        TuiMutations.inspectPane(fresh,target.tuiSession),"Checking the new shell…",
                        "The shell was created but did not open. Refresh to find it.")
                } else switchMessage=if(event.exitStatus==78) "This Herdr CLI does not support creating a shell from Pocket."
                    else "Could not confirm the new shell. Check Herdr before trying again."
            }
            is TuiRosterWork.VerifyNewPane -> if(ok && TuiMutations.confirmsPane(text,work.location)) {
                val shell=TuiAgents.plainTerminal(work.location)
                switchSessions=switchSessions+shell
                startTerminalSwitch(shell,newlyCreated=true)
            } else switchMessage="The new shell pane changed before it could be opened. Refresh to find it."
        }
    }

    fun dismissAgentSwitcher() {
        if(showSessionPicker) {
            if(coldAttach) {
                val operation=switchOperation ?: return
                if(!ssh.cancelOperation(operation)) return
                clearColdAttach()
                switchMessage=""
                return
            }
            if(busy && listing) {
                // Cancel only this no-PTY roster refresh; the independent alert
                // watcher may still be intentionally active for this account.
                generation++
                finishStartup()
                clearPendingSave()
                ssh.disconnect()
                busy=false
                listing=false
                reconnectTarget=null
                reconnectOffered=false
                status="Disconnected"
            }
            cancelPicker()
            return
        }
        if(coreShellCreate!=null || tuiWork!=null) return // A sent mutation cannot reliably be undone.
        val operation=switchOperation
        if(operation!=null && !ssh.cancelOperation(operation)) return // a result is already being delivered
        clearAgentSwitcher()
    }

    internal fun backFromTerminal():Boolean {
        return when(terminalBackDestination(canSwitchAgents)) {
            TerminalBackDestination.AGENTS -> {showConnectedAgents(); true}
            TerminalBackDestination.CONNECTIONS -> {backToSshConnections(); false}
        }
    }

    private fun clearAgentSwitcher() {
        interruptedRosterNotice=when(tuiWork) {
            is TuiRosterWork.Rename -> "Connection interrupted during rename. Check the name in Herdr."
            is TuiRosterWork.CreateShell -> "Shell creation was interrupted. Check Herdr before trying again."
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
        createWorkspaceChoices=emptyList()
        tuiWork=null
        nextRosterNotice=null
        switchMessage=""
    }

    private fun acceptSwitch(id:Long) {
        if(switchOperation!=id) return
        val selected=switchCandidate ?: return
        val target=resume ?: return
        if(coldAttach) {
            coldAttach=false
            showSessionPicker=false
            sessions=emptyList()
            listedConfig=null
            clearRosterWatchLease()
            busy=true
            terminalVisible=true
            status="Connected · SSH PTY"
        }
        target.command=target.backend.attachCommand(selected.address,target.tuiSession)
        target.address=selected.address
        target.retries=0
        listErrors.reset()
        activeCoreAddress=selected.address
        reconnectTarget=reconnectTarget?.copy(mode=ReconnectMode.AgentSession(true,selected.address,target.backend,target.tuiSession))
        displayedPty=ssh.terminalIdentity()
        authenticatedWatchTerminal=target.takeIf {displayedPty!=null && it.backend==AgentBackend.TUI}
        rememberCandidate=null
        agentName=selected.name
        val endpoint=HostEndpoint.parse(target.config.host,target.config.port.toString())
        hostPrefs.edit().putString("agent",selected.name).apply()
        if(endpoint!=null) saveRecentSession(target,target.backend.rememberedKey(endpoint,target.config.user,target.tuiSession),selected.address)
        terminalMouse.reset()
        replaceEmulator(displayedPty)
        switchingNewTerminal=false
        clearAgentSwitcher()
    }

    private var resume:ResumeTarget?=null
    private fun clearResume() {initialTerminalOpening=null;coldAttach=false;dismissAgentMenu();authenticatedWatchTerminal=null;watchStartGate.reset();resume?.clear();resume=null;activeCoreAddress=null;displayedPty=null;terminalMouse.reset();clearAgentSwitcher()}
    private fun returnToRosterAfterTerminalEnded(event:SshSession.Event.TerminalEnded) {
        val target=resume ?: return
        if((displayedPty!=null && displayedPty!==event.channel) || !ssh.hasIdleTransport()) return
        // Keep credentials scoped to the existing verified host and named Herdr session.
        val lease=RosterWatchLease(target.config,target.tuiSession,target.key.copyOf(),target.passphrase?.copyOf())
        val backend=target.backend
        clearRosterWatchLease()
        finishStartup()
        clearPendingSave()
        clearResume()
        replaceEmulator()
        retainedTerminal=false
        clearRetainedOutput()
        terminalVisible=false
        rememberCandidate=null
        pendingColdWatchTerminal=null
        requestedAgent=null
        listedConfig=lease.config
        listedTuiSession=lease.session
        listedBackend=backend
        rosterWatchLease=lease
        sessions=emptyList()
        showSessionPicker=true
        showAgentSwitcher=true
        reconnectTarget=reconnectTarget?.copy(mode=ReconnectMode.AgentSession(false,null,backend,lease.session))
        reconnectOffered=false
        busy=false
        listing=false
        status="Connected · SSH"
        if(!listColdRoster()) failColdRoster("The terminal closed. Could not refresh the session list.")
    }

    private fun reattachAfterLoss() {
        authenticatedWatchTerminal=null
        watchStartGate.reset()
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
            try {listErrors.reset();checkNotNull(ssh.connect(target.config,target.key,target.passphrase,target.command,cols,rows,keepTransportOnPtyClose=true))}
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
                is SshSession.Event.TransportReady -> {
                    onAuthenticated()
                    if(!listing || !listColdRoster()) failColdRoster("Could not read sessions on the SSH connection.")
                }
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
                            if(!startupInFlight) initialTerminalOpening=null
                            terminalVisible=!startupInFlight
                        }
                        activeCoreAddress=resume?.address
                        authenticatedWatchTerminal=resume?.takeIf {displayedPty!=null && it.backend==AgentBackend.TUI}
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
                is SshSession.Event.OperationList -> if(liveVoice.accept(event) || acceptCodexProgress(event)) Unit else if(coldRosterOperation==event.id) {
                    finishColdRoster(event)
                } else if(agentSettingsRequest==event.id) {
                    acceptAgentSettings(event)
                } else if(filesLookup==event.id) {
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
                            val snapshot=event.stdout.toString(Charsets.UTF_8)
                            tuiRosterFresh=true
                            tuiCanCreate=TuiMutations.canCreateFromRoster(snapshot)
                            createWorkspaceChoices=if(tuiCanCreate) TuiMutations.workspaceChoices(snapshot) else emptyList()
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
                is SshSession.Event.OperationFailed -> if(liveVoice.accept(event)) Unit else if(coldRosterOperation==event.id) {
                    failColdRoster(event.message)
                } else if(agentSettingsRequest==event.id) {
                    failedAgentSettings()
                } else if(filesLookup==event.id) {
                    filesLookup = null
                    filesUi = filesUi?.copy(loading = false, error = "Could not look up this agent’s folder. Try again.")
                } else if(switchOperation==event.id) {
                    clearColdAttach()
                    rosterRefresh=rosterRefresh.complete(event.id)
                    switchOperation=null;switchCandidate=null;pendingWatchTerminal=null
                    switchMessage=when(tuiWork) {
                        is TuiRosterWork.Rename -> "Could not confirm the rename. Refresh before trying again."
                        is TuiRosterWork.VerifyNewPane -> "The shell was created but did not open. Refresh to find it."
                        is TuiRosterWork.CreateShell -> "Could not confirm the new shell. Check Herdr before trying again."
                        is TuiRosterWork.VerifyRename -> "Could not check the target. No rename was sent."
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
                is SshSession.Event.TerminalEnded -> returnToRosterAfterTerminalEnded(event)
                is SshSession.Event.TransportLost -> reattachAfterLoss()
                is SshSession.Event.RejectedHostKey -> {
                    suspendCurrentTerminal()
                    // Show verification instead of trapping a rejected reconnect on the stale terminal.
                    retainedTerminal=false
                    terminalVisible=false
                    reconnectOffered=false
                    reconnectTarget=null
                    val probe=probeContext
                    finishStartup()
                    clearPendingSave();probeContext=null
                    clearRosterWatchLease();clearResume();rememberCandidate=null;listing=false;observedPin=event.actual;busy=false
                    if(probe==null) passphrase=""
                    if(probe!=null && probe.generation==generation && probe.endpoint==selectedEndpoint) {
                        val expected=hostTrust.pin(probe.endpoint)
                        hostKeyMismatch=expected!=null && expected!=event.actual
                        offeredTrust=if(hostKeyMismatch && probe.reviewChange)
                            hostTrust.replacementOffer(probe.endpoint,event.actual,generation)
                        else hostTrust.offer(probe.endpoint,event.actual,generation)
                        offeredRequest=probe.resumeRequest
                        status=when {
                            expected==null -> "New SSH host. Confirm its fingerprint to continue."
                            expected==event.actual -> "Offered fingerprint matches the saved pin. No authentication sent."
                            else -> "HOST KEY MISMATCH. Connection rejected."
                        }
                    } else {
                        hostKeyMismatch=event.expected!=null && event.expected==storedPin && event.actual!=event.expected
                        status="HOST KEY MISMATCH. Connection rejected."
                    }
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
                    clearPendingSave();probeContext=null;clearResume();cancelPicker();rememberCandidate=null;passphrase="";listing=false;busy=false
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
        keepRetainedTerminal:Boolean=false,keepColdRoster:Boolean=false,
    ) {
        if(!automatic) noteManualConnectionAction()
        if(busy || selectedHostId==null) return
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
        if(!probe) resumeConnection()
        reconnectOffered=false
        reconnectTarget=if(checking) null else reconnectProfile()?.let { profile ->
            val mode=when {
                attach!=null -> ReconnectMode.AgentSession(true,attach.address,backend,namedSession)
                list -> ReconnectMode.AgentSession(autoSelect,preferredAgent ?: if(autoSelect)
                    hostPrefs.getString(backend.rememberedKey(endpoint,cfg.user,namedSession),null) else null,backend,namedSession)
                else -> ReconnectMode.Custom(command)
            }
            ReconnectTarget(profile,mode)
        }
        invalidateOffer()
        if(!keepRetainedTerminal) retainedTerminal=false
        clearRetainedOutput()
        val coldRows=switchSessions.takeIf {keepColdRoster && showSessionPicker && list && !checking}
        val holdRosterTransport=list && !checking && preferredAgent==null && !autoSelect && !keepRetainedTerminal
        if(ssh.hasIdleTransport()) ssh.disconnect()
        coldRosterOperation=null
        clearRosterWatchLease()
        clearResume()
        showSessionPicker=coldRows!=null || holdRosterTransport
        if(holdRosterTransport) {
            terminalVisible=false
            showAgentSwitcher=true
        }
        if(coldRows!=null) {
            switchSessions=coldRows
            showAgentSwitcher=true
        }
        attachStarted=false;attachOutputSeen=false
        rememberCandidate=if(attach!=null) backend.rememberedKey(endpoint,cfg.user,namedSession) to attach.address else null
        demo=false; busy=true; status=when {
            checking -> "Checking host key (no authentication)…"
            automatic -> "Connecting to your SSH host…"
            else -> "Connecting…"
        }
        val request=++generation
        val targetVault=vault
        if(checking) probeContext=Probe(endpoint,request,probe,if(probe || attach!=null)null else Request(list,autoSelect,backend,namedSession))
        val uri=keyUri
        val secret=if(checking) byteArrayOf() else passphrase.toByteArray(Charsets.UTF_8)
        val wantsSave=!checking && keyRemembered && saveTypedPassphrase && secret.isNotEmpty()
        val mayReadStored=!checking && keyRemembered && secret.isEmpty()
        clearPendingSave()
        if(!checking && !list) passphrase=""
        listing=!checking && list
        if(listing) {pendingColdWatchTerminal=null;requestedAgent=preferredAgent;listedBackend=backend;listedTuiSession=namedSession;listedConfig=cfg;sessions=emptyList()}
        if(!list && !checking && !retainedTerminal) replaceEmulator()
        listBytes.reset();listErrors.reset()
        if(attach!=null) agentName=attach.name
        if(!list && !checking && !retainedTerminal) {
            terminalVisible=false
            initialTerminalOpening=TerminalOpeningUi(agentName,agentChrome=attach!=null)
        }
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
                    unlocked=withContext(Dispatchers.IO) {targetVault.read(scope)}
                    if(request!=generation) return@launch
                    hasSavedPassphrase=vault.hasSaved()
                    if(unlocked==null) {
                        finishStartup()
                        busy=false;listing=false
                        cancelPicker()
                        reconnectOffered=true
                        terminalVisible=false
                        clearRetainedOutput()
                        credentialNotice="Saved passphrase could not be unlocked. Enter it again."
                        status="Enter the key passphrase and try again."
                        return@launch
                    }
                }
                val credential=unlocked ?: secret.takeIf { it.isNotEmpty() }
                hostPrefs.edit().putString("agent",agentName).putString("host",host).putString("port",port)
                    .putString("user",user).putString("command",command).putString("connection_label",connectionLabel).apply {
                        if(backend==AgentBackend.TUI && !checking) putString("tui_session",namedSession)
                    }.apply()
                profileRevision++
                if(wantsSave && scope!=null) pendingSave=PendingSave(scope,secret.copyOf())
                if(attach!=null) resume=ResumeTarget(cfg,key.copyOf(),credential?.copyOf(),cmd,attach.address,backend,namedSession)
                checkNotNull(ssh.connect(cfg,key,credential,cmd,cols,rows,pty=!list,transportOnly=holdRosterTransport,keepTransportOnPtyClose=attach!=null && !checking))
                // The callback is posted to main. Promote this pending lease only after a successful roster response.
                if(listing && (holdRosterTransport || backend==AgentBackend.TUI) && expected?.let(::isSshSha256Pin)==true)
                    pendingRosterWatchLease=RosterWatchLease(cfg,namedSession,key.copyOf(),credential?.copyOf())
            } catch(e:Exception) {
                if(request==generation) {
                    finishStartup()
                    clearRetainedOutput()
                    clearPendingSave();probeContext=null;clearResume();cancelPicker();busy=false;listing=false;passphrase=""
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
        val persisted=hostPrefs.edit().putBoolean("startup_key_needs_auth",true).apply {
            if(keyRemembered) putString("key_document_uri",uri.toString()) else remove("key_document_uri")
        }.commit()
        if(!persisted) {
            keyRemembered=false
            releaseKeyIfUnused(uri)
        }
        if(previous!=uri) releaseKeyIfUnused(previous)
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
        val removed=hostPrefs.edit().remove("key_document_uri").commit()
        releaseKeyIfUnused(previous)
        cancelPicker()
        val cleared=forgetSavedPassphrase()
        credentialNotice=if(removed && cleared) "Key access and saved passphrase forgotten." else "Could not confirm credentials were fully forgotten. Try again."
    }

    fun disconnect(manual:Boolean=false) {
        if(manual) hostPrefs.edit().putBoolean("startup_manually_disconnected",true).apply()
        liveVoice.stop()
        if(AgentWatchService.state.value is AgentWatchServiceState.Active) AgentWatchService.stop(getApplication<Application>())
        if(retainedTerminal || manual) terminalVisible=false
        retainedTerminal=false
        clearRetainedOutput()
        noteManualConnectionAction();finishStartup();reconnectOffered=false;reconnectTarget=null;invalidateOffer()
        generation++;clearPendingSave();clearResume();rememberCandidate=null;ssh.disconnect()
        busy=false;listing=false;cancelPicker();status="Disconnected"
    }
    fun showDemo() {
        disconnect();resumeConnection();demo=true;terminalVisible=true;status="LOCAL DEMO · no SSH connection"
        emulator.writeInput("\u001b[2J\u001b[HHerdr Pocket · local renderer demo\r\n\r\n".toByteArray())
        val bitmap=Bitmap.createBitmap(240,100,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(android.graphics.Color.rgb(30,55,65))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.rgb(166,227,161);textSize=26f }
        canvas.drawCircle(35f,50f,21f,paint);canvas.drawText("Kitty PNG",70f,59f,paint)
        val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle()
        val payload=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
        emulator.writeInput(("\u001b_Ga=T,f=100,t=d,i=42,c=24,r=5;"+payload+"\u001b\\\r\n\r\nLocal PNG sent through Kitty escape bytes.\r\nPinch text to zoom; rotate or unfold to resize.\r\nThis is not a remote Herdr session.\r\n").toByteArray())
    }
    override fun onCleared() {liveVoice.stop();prefs.unregisterOnSharedPreferenceChangeListener(notificationPrefListener);clearRosterWatchLease();clearRetainedOutput();finishStartup();clearPendingSave();clearResume();ssh.close();emulator.close();retiredEmulators.forEach { it.close() };retiredEmulators.clear();passphrase=""}
}

class MainActivity : ComponentActivity() {
    private lateinit var model:PocketModel
    private var watchUiVisible by mutableStateOf(false)
    private var watchOsAllowed by mutableStateOf(false)
    private var previousWatchPermission:Boolean?=null
    private val alertPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshWatchPermission()
    }

    private fun refreshWatchPermission() {
        val allowed=AgentWatchService.canNotify(this)
        if(previousWatchPermission?.let {it!=allowed}==true) model.resetWatchStartGate()
        previousWatchPermission=allowed
        watchOsAllowed=allowed
    }

    private fun setAgentNotifications(enabled:Boolean) {
        model.setNotificationsEnabled(enabled)
        if(!enabled) return
        refreshWatchPermission()
        if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            alertPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openAndroidNotificationSettings() {
        runCatching {startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))}
    }

    override fun onResume() {
        super.onResume()
        refreshWatchPermission()
        watchUiVisible=true
    }

    override fun onPause() {
        watchUiVisible=false
        super.onPause()
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
        model.keyPicked(uri)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        model=ViewModelProvider(this)[PocketModel::class.java]
        handleAgentAlert(intent)
        model.tryStartupReconnect()
        setContent {
            val lease=model.watchLeaseToken
            LaunchedEffect(lease,model.notificationsEnabled,watchUiVisible,watchOsAllowed) {
                if(lease!=null && watchUiVisible && watchOsAllowed) model.maybeStartAgentWatch(this@MainActivity)
            }
            MaterialTheme(colorScheme=PocketColorScheme) {
                Surface(Modifier.fillMaxSize()) {
                    PocketScreen(model,
                        notificationsAllowed=watchOsAllowed,
                        setAgentNotifications=::setAgentNotifications,
                        openAndroidNotificationSettings=::openAndroidNotificationSettings,
                        chooseKey={if(model.beginKeyPicker()) runCatching {keyPicker.launch(arrayOf("*/*"))}.onFailure {model.keyPicked(null)}},
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
@Composable private fun PocketScreen(m:PocketModel,notificationsAllowed:Boolean,
    setAgentNotifications:(Boolean)->Unit,openAndroidNotificationSettings:()->Unit,chooseKey:()->Unit,saveFile:()->Unit,uploadFile:()->Unit) {
 var rosterView by rememberSaveable { mutableStateOf(AgentRosterView.AGENTS) }
 val agentsScroll=rememberLazyListState()
 val spacesScroll=rememberLazyListState()

 LiveVoiceDialogs(m.liveVoice)

 m.offeredTrust?.let { offer ->
    val replacing=offer.previousPin!=null
    PocketDialog(
      onDismissRequest={m.rejectHost(offer)},
      title={Text(if(replacing) "SSH host identity changed" else "Trust this SSH host?")},
      text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
          Text(offer.endpoint.display,fontSize=20.sp)
          if(replacing) {
            Text(stringResource(R.string.connection_saved_host_pin,offer.previousPin.orEmpty()),fontSize=16.sp)
            Text(stringResource(R.string.connection_offered_host_pin,offer.fingerprint),fontSize=16.sp)
            Text("A server reinstall or key rotation can cause this, but so can an impersonation attempt. Verify the new fingerprint through a trusted route before replacing the saved one.",fontSize=16.sp)
          } else {
            Text(offer.fingerprint,fontSize=16.sp)
            Text("This identity has not been independently verified. A fake server could be impersonating your host. Compare the fingerprint through a trusted route before accepting if you can.",fontSize=16.sp)
          }
        }
      },
      confirmButton={TextButton(onClick={m.trustOfferedHost(offer)}){Text(if(replacing) "Replace saved fingerprint" else "Trust on first use")}},
      dismissButton={TextButton(onClick={m.rejectHost(offer)}){Text("Cancel")}},
    )
 }

 if(m.filesUploadNotice==null) m.watchNotice?.let { notice ->
    PocketDialog(onDismissRequest=m::clearWatchNotice,title={Text("Agent alerts")},
        text={Text(notice)},confirmButton={TextButton(onClick=m::clearWatchNotice){Text("OK")}})
 }

 m.filesUploadNotice?.let { notice ->
    PocketDialog(onDismissRequest=m::clearFilesUploadNotice,title={Text(stringResource(R.string.agent_files_upload_title))},
        text={Text(stringResource(when(notice) {
            FilesUploadInterruption.CONNECTION_LOST_BEFORE_TRANSFER -> R.string.agent_files_upload_connection_lost_before
            FilesUploadInterruption.CONNECTION_LOST_DURING_TRANSFER -> R.string.agent_files_upload_connection_lost_during
            FilesUploadInterruption.PICKER_ROUTE_EXPIRED -> R.string.agent_files_upload_route_expired
        }))},confirmButton={TextButton(onClick=m::clearFilesUploadNotice){Text("OK")}})
 }

 when(val route=m.sshNavigation) {
    SshNavigation.Connections -> { SavedConnectionsScreen(m,onOpenAgents={rosterView=AgentRosterView.AGENTS}); return }
    SshNavigation.Settings -> {
        ConnectionSettingsScreen(m,chooseKey)
        return
    }
    SshNavigation.AppSettings -> {
        PocketAppSettingsScreen(m,notificationsAllowed,setAgentNotifications,openAndroidNotificationSettings)
        return
    }
    is SshNavigation.Edit -> { key(route) { SshHostEditorScreen(m,route.id,chooseKey) }; return }
    SshNavigation.Session -> Unit
 }
 m.terminalRecovery?.let { recovery ->
    FrozenTerminalScreen(m,recovery)
    return
 }
 val opening=m.terminalOpening
 if(opening?.agentChrome==true || opening==null && !m.showAgentSwitcher && m.terminalVisible && m.canSwitchAgents) {
    CoreTerminal(m,saveFile,uploadFile,opening,onBack={
        if(m.backFromTerminal()) rosterView=AgentRosterView.AGENTS
    })
    return
 }
 if(opening==null && m.showAgentSwitcher) {
    AgentSwitcher(m,rosterView,{rosterView=it},if(rosterView==AgentRosterView.AGENTS) agentsScroll else spacesScroll)
    return
 }

 if(m.terminalVisible || opening!=null) {
    val back=if(opening!=null) m::cancelTerminalOpening else ({ m.backFromTerminal(); Unit })
    BackHandler(onBack=back)
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),
            verticalAlignment=Alignment.CenterVertically,
        ) {
            Text((opening?.title ?: m.agentName).ifBlank{"Terminal"},fontSize=22.sp,modifier=Modifier.weight(1f))
            RoundBackArrow(if(opening!=null) stringResource(android.R.string.cancel) else "Back to SSH connections",back,48.dp)
        }
        TerminalPanel(m,Modifier.weight(1f).fillMaxWidth(),opening=opening!=null)
    }
    return
 }
 SavedConnectionsScreen(m,onOpenAgents={rosterView=AgentRosterView.AGENTS})
}

@Composable
private fun ConnectionSettingsScreen(
    m:PocketModel,
    chooseKey:()->Unit,
) {
    val theme=MaterialTheme.colorScheme
    var advanced by rememberSaveable(m.selectedHostId) {mutableStateOf(false)}
    val canEditHost=!m.busy && !m.connectionIsOpen
    BackHandler(onBack=m::backToSshConnections)
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Box(
            Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp,vertical=4.dp),
            contentAlignment=Alignment.Center,
        ) {
            RoundBackArrow("Back to SSH connections",m::backToSshConnections,48.dp,Modifier.align(Alignment.CenterStart))
            Text(stringResource(R.string.connection_settings),
                style=MaterialTheme.typography.titleLarge,textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                modifier=Modifier.padding(horizontal=52.dp).semantics {heading()})
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),
        ) {
            val current=m.savedHosts.firstOrNull {it.id==m.selectedHostId}
            if(current!=null) {
                Text(current.name,style=MaterialTheme.typography.titleMedium)
                Text(current.address,style=MaterialTheme.typography.bodyMedium)
            }
            ConnectionSection(R.string.connection_status_section) {
                Text(m.status,style=MaterialTheme.typography.bodyLarge,
                    modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                if(m.canReviewHostKey) OutlinedButton(onClick={m.connect(probe=true)}) {Text("Review changed host key")}
                if(m.canReconnect) OutlinedButton(onClick={m.reconnect(); if(m.busy) m.resumeConnection()}) {Text(stringResource(R.string.reconnect))}
                if(m.busy || m.hasRosterConnection) OutlinedButton(onClick={m.disconnect(manual=true)}) {
                    Text(if(m.status=="Connected · SSH PTY" || m.hasRosterConnection) "Disconnect" else "Cancel connection")
                }
            }
            if(current!=null) {
                ConnectionSection(R.string.connection_host_section) {
                    Field("Connection name (optional)",m.connectionLabel,{m.connectionLabel=it},canEditHost)
                    Field("Host or IP address",m.host,{m.host=it},canEditHost)
                    Field("SSH username",m.user,{m.user=it},canEditHost)
                    OutlinedTextField(value=m.port,onValueChange={m.port=it},label={Text("SSH port")},
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,
                        enabled=canEditHost,modifier=Modifier.fillMaxWidth())
                    if(m.connectionIsOpen) Text("Disconnect to edit this connection.")
                    else TextButton(onClick={m.saveHostDetails()},enabled=canEditHost) {Text("Save connection")}
                }
                ConnectionSection(R.string.connection_keys_section) {
                    OutlinedButton(onClick=chooseKey,enabled=canEditHost){
                        Text(if(m.keyUri==null) "Select private key file" else "Change private key file")
                    }
                    if(m.keyUri!=null) {
                        OutlinedTextField(value=m.passphrase,onValueChange={m.passphrase=it},
                            label={Text("Key passphrase (optional)")},
                            visualTransformation=PasswordVisualTransformation(),
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                            singleLine=true,enabled=canEditHost,modifier=Modifier.fillMaxWidth())
                        Row(
                            Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(
                                value=m.saveTypedPassphrase,enabled=canEditHost && m.keyRemembered,
                                role=Role.Checkbox,onValueChange=m::toggleRememberPassphrase),
                            verticalAlignment=Alignment.CenterVertically,
                        ) {
                            Checkbox(checked=m.saveTypedPassphrase,onCheckedChange=null,
                                enabled=canEditHost && m.keyRemembered,modifier=Modifier.padding(end=12.dp))
                            Text("Remember the passphrase I enter",style=MaterialTheme.typography.bodyLarge)
                        }
                        if(m.hasSavedPassphrase) {
                            Text("A saved passphrase is used only with its original host, login, and key.",
                                style=MaterialTheme.typography.bodyMedium,color=theme.onSurfaceVariant)
                            TextButton(onClick={m.forgetSavedPassphrase()},enabled=canEditHost) {Text("Forget saved passphrase")}
                        }
                        TextButton(onClick=m::forgetKey,enabled=canEditHost) {Text("Forget key")}
                    }
                    if(m.credentialNotice.isNotBlank()) Text(m.credentialNotice,style=MaterialTheme.typography.bodyLarge,
                        modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                }
                if(!m.connectionIsOpen) {
                    OutlinedButton(onClick={m.connect(list=true,backend=AgentBackend.TUI)},enabled=canEditHost,
                        modifier=Modifier.fillMaxWidth(),border=BorderStroke(2.dp,if(canEditHost) PocketInk else PocketDisabledInk)) {Text("Connect to Herdr TUI",fontSize=18.sp)}
                    OutlinedButton(onClick={m.connect(list=true,backend=AgentBackend.CORE)},enabled=canEditHost,
                        modifier=Modifier.fillMaxWidth()) {Text("Connect to Herdr Core")}
                }
                ConnectionSection(R.string.connection_verify_section) {
                    m.storedPin?.let { pin ->
                        Text(stringResource(R.string.connection_saved_host_pin,pin),style=MaterialTheme.typography.bodyLarge)
                    } ?: Text(stringResource(R.string.connection_no_saved_host_pin),
                        style=MaterialTheme.typography.bodyMedium,color=theme.onSurfaceVariant)
                    m.observedPin?.let { pin ->
                        Text(stringResource(R.string.connection_offered_host_pin,pin),style=MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.connection_compare_host_pin),style=MaterialTheme.typography.bodyMedium,
                            color=theme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick={m.connect(probe=true)},enabled=canEditHost) {Text("Check host fingerprint")}
                }
            }
            ConnectionSection(R.string.connection_advanced_section) {
                TextButton(onClick={advanced=!advanced}) {
                    Text(stringResource(if(advanced) R.string.connection_hide_advanced else R.string.connection_show_advanced))
                }
                if(advanced) {
                    if(current!=null) {
                        Field("Herdr TUI session (blank = default)",m.tuiSession,{m.tuiSession=it},canEditHost)
                        Field("Agent / session name",m.agentName,{m.agentName=it},canEditHost)
                        Field("Custom attach command",m.command,{m.command=it},canEditHost)
                        OutlinedButton(onClick={m.connect()},enabled=canEditHost,modifier=Modifier.fillMaxWidth()) {
                            Text("Run custom attach command")
                        }
                        Text("Choose an attach-only custom command. It runs on the SSH host.",
                            style=MaterialTheme.typography.bodyMedium,color=theme.onSurfaceVariant)
                    }
                    CoreInstallHelp()

                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PocketAppSettingsScreen(
    m:PocketModel,
    notificationsAllowed:Boolean,
    setAgentNotifications:(Boolean)->Unit,
    openAndroidNotificationSettings:()->Unit,
) {
    val theme=MaterialTheme.colorScheme
    BackHandler(onBack=m::backToSshConnections)
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp,vertical=4.dp),
            contentAlignment=Alignment.Center) {
            RoundBackArrow("Back to SSH connections",m::backToSshConnections,48.dp,Modifier.align(Alignment.CenterStart))
            Text("App settings",style=MaterialTheme.typography.titleLarge,
                modifier=Modifier.padding(horizontal=52.dp).semantics {heading()})
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("These settings apply to all SSH connections.",style=MaterialTheme.typography.bodyMedium)
            ConnectionSection(R.string.connection_alerts_section) {
                AgentWatchSettings(m.notificationsEnabled,notificationsAllowed,m.watchLeaseToken!=null,
                    setAgentNotifications,openAndroidNotificationSettings)
            }
            ConnectionSection(R.string.dictation_settings) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(
                        value=m.dictationPressEnter,role=Role.Switch,onValueChange=m::setDictationEnter),
                    horizontalArrangement=Arrangement.spacedBy(12.dp),
                    verticalAlignment=Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.dictation_press_enter),style=MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.dictation_press_enter_detail),
                            style=MaterialTheme.typography.bodyMedium,color=theme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked=m.dictationPressEnter,onCheckedChange=null,colors=pocketSwitchColors())
                }
            }
            ConnectionSection(R.string.live_voice_settings) { LiveVoiceServiceSettings(m.liveVoice) }
            ConnectionSection(R.string.connection_advanced_section) {
                OutlinedButton(onClick=m::showDemo,enabled=!m.busy && !m.connectionIsOpen) {Text("Local Kitty image demo")}
                Text("ConnectBot Termlib (Apache 2.0), libvterm (MIT), mwiede JSch (BSD). Licenses bundled.",
                    style=MaterialTheme.typography.bodySmall,color=theme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SshHostEditorScreen(m:PocketModel,id:String?,chooseKey:()->Unit) {
    val original=remember(id) {m.hostEditorFields(id)}
    var name by rememberSaveable(id) {mutableStateOf(original.name)}
    var host by rememberSaveable(id) {mutableStateOf(original.host)}
    var port by rememberSaveable(id) {mutableStateOf(original.port)}
    var user by rememberSaveable(id) {mutableStateOf(original.user)}
    BackHandler(onBack=m::backToSshConnections)
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp,vertical=4.dp),
            contentAlignment=Alignment.Center) {
            RoundBackArrow("Back to SSH connections",m::backToSshConnections,48.dp,Modifier.align(Alignment.CenterStart))
            Text(if(id==null) "Add SSH connection" else "Edit SSH connection",style=MaterialTheme.typography.titleLarge,
                modifier=Modifier.padding(horizontal=52.dp).semantics {heading()})
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Field("Connection name (optional)",name,{name=it.take(64)},true)
            Field("Host or IP address",host,{host=it},true)
            Field("SSH username",user,{user=it},true)
            OutlinedTextField(value=port,onValueChange={port=it},label={Text("SSH port")},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,
                modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick=chooseKey) {
                Text(if(m.editorHasKey(id)) "Change private key file" else "Select private key file")
            }
            if(m.editorKey!=null) Text("Key selected.")
            if(id==null) Text("Saving won’t switch hosts. If you skip the key, you can select one when you connect.")
            if(m.hostManageNotice.isNotBlank()) Text(m.hostManageNotice,
                modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            OutlinedButton(onClick={m.saveEditedHost(id,SshHostEditorFields(name,host,port,user))},
                modifier=Modifier.fillMaxWidth()) {Text("Save connection")}
        }
    }
}

@Composable
private fun SavedConnectionsScreen(m:PocketModel,onOpenAgents:()->Unit) {
    var removeId by rememberSaveable {mutableStateOf<String?>(null)}
    val hosts=m.savedHosts
    val selected=m.selectedHostId
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp,vertical=4.dp),
            contentAlignment=Alignment.Center) {
            Text("SSH connections",style=MaterialTheme.typography.titleLarge,
                modifier=Modifier.padding(horizontal=52.dp).semantics {heading()})
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedButton(onClick={m.editHost(null)},modifier=Modifier.fillMaxWidth()) {Text("Add connection")}
                OutlinedButton(onClick=m::openAppSettings,modifier=Modifier.fillMaxWidth()) {Text("App settings")}
                if(hosts.isEmpty()) Text("No saved connections yet.")
                if(selected!=null && !m.connectionIsOpen && m.status!="Disconnected") {
                    Text(m.status,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                }
                if(m.connectionIsOpen && hosts.size>1) Text("Connecting to another host switches Pocket to that host.")
                if(m.hostManageNotice.isNotBlank()) Text(m.hostManageNotice,
                    modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            }
            items(hosts,key={it.id}) {host ->
                val action=m.savedHostAction(host.id)
                val connected=action!=SavedHostPrimaryAction.CONNECT
                val openLabel=if(action==SavedHostPrimaryAction.OPEN_AGENTS) "Open agents" else "Open terminal"
                val open=Modifier.clickable(role=Role.Button,onClickLabel=openLabel) {
                    if(m.openConnectedHost(host.id) && action==SavedHostPrimaryAction.OPEN_AGENTS) onOpenAgents()
                }
                Surface(Modifier.fillMaxWidth(),shape=PocketPanelShape,color=PocketBlack,
                    border=BorderStroke(if(connected) 2.dp else 1.dp,if(connected) PocketInk else PocketOutline)) {
                    Column {
                        Column(Modifier.fillMaxWidth()
                            .then(if(connected) Modifier.background(PocketInk.copy(alpha=.12f)).then(open) else Modifier)
                            .heightIn(min=56.dp).padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
                                Text(host.name,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                                if(connected) {
                                    Canvas(Modifier.padding(start=8.dp).size(20.dp)) {
                                        val left=size.width*.32f
                                        val right=size.width*.61f
                                        val center=size.height*.5f
                                        val offset=size.height*.23f
                                        drawLine(PocketInk,Offset(left,center-offset),Offset(right,center),PocketGlyphStroke.toPx(),StrokeCap.Round)
                                        drawLine(PocketInk,Offset(right,center),Offset(left,center+offset),PocketGlyphStroke.toPx(),StrokeCap.Round)
                                    }
                                }
                            }
                            Text(host.address,style=MaterialTheme.typography.bodyMedium)
                            if(connected) Text("Connected",style=MaterialTheme.typography.labelLarge,color=PocketInk)
                            else if(host.id==selected) Text(if(m.busy) "Connecting" else "Selected · Disconnected",
                                style=MaterialTheme.typography.bodySmall)
                        }
                        if(!connected) OutlinedButton(onClick={m.connectSavedHost(host.id)},
                            enabled=m.canManageHosts && host.configured,
                            modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) {Text("Connect")}
                        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                            TextButton(onClick={m.editHost(host.id)}) {
                                Text(if(host.id==selected) "Settings" else "Edit")
                            }
                            TextButton(onClick={removeId=host.id},enabled=m.canManageHosts) {Text("Remove")}
                        }
                    }
                }
            }
        }
    }
    hosts.firstOrNull {it.id==removeId}?.let {host ->
        PocketDialog(onDismissRequest={removeId=null},title={Text("Remove this connection?")},
            text={Text("${host.name}\n${host.address}\n\nThis removes its saved connection details, fingerprint, and remembered passphrase from Pocket. It does not change the remote host or delete your SSH key file."+
                if(host.id==selected && m.connectionIsOpen) " Pocket will disconnect from this host." else "")},
            confirmButton={TextButton(onClick={if(m.removeHost(host.id)) removeId=null}) {Text("Remove connection")}},
            dismissButton={TextButton(onClick={removeId=null}) {Text("Cancel")}})
    }
}

@Composable
private fun ConnectionSection(title:Int,content:@Composable androidx.compose.foundation.layout.ColumnScope.()->Unit) {
    Surface(
        modifier=Modifier.fillMaxWidth(),shape=PocketPanelShape,
        color=PocketBlack,border=BorderStroke(1.dp,PocketOutline),
    ) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title),style=MaterialTheme.typography.titleMedium,
                modifier=Modifier.semantics {heading()})
            content()
        }
    }
}

@Composable private fun TerminalPanel(
    m:PocketModel, modifier:Modifier, suspended:Boolean=false, opening:Boolean=false, overlay:@Composable BoxScope.()->Unit = {},
) {
    // Termlib displays the IME when showSoftKeyboard becomes true. Scope this to the
    // displayed terminal, never saved across navigation, replacement, or Activity recreation.
    val terminal=m.emulator
    val context=LocalContext.current
    val interactive=!suspended && !opening
    val pty=if(interactive) m.speechTerminalIdentity() else null
    val modifiers=remember(terminal) {
        dockModifierManager(terminal,m::speechTerminalIdentity) { pty,bytes ->
            terminal===m.emulator && m.sendDockKeyboardInput(pty,bytes)
        }
    }
    SideEffect {modifiers.bind(pty)}
    DisposableEffect(modifiers) {onDispose {modifiers.bind(null)}}
    var keyboard by remember(terminal,interactive) { mutableStateOf(TerminalKeyboard()) }
    val focus=LocalFocusManager.current
    val softKeyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(opening) {
        if(opening) {
            focus.clearFocus(force=true)
            softKeyboard?.hide()
        }
    }
    val terminalModifier=if(suspended) modifier.clearAndSetSemantics {hideFromAccessibility()} else modifier
    Column(terminalModifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // A switch retains the previous renderer so it can recover on failure.
            // Do not mount its view underneath the new terminal's chrome.
            if(opening) {
                val label=stringResource(R.string.terminal_opening)
                Box(Modifier.matchParentSize().background(PocketBlack),contentAlignment=Alignment.Center) {
                    CircularProgressIndicator(
                        modifier=Modifier.size(36.dp).semantics {
                            contentDescription=label
                            liveRegion=LiveRegionMode.Polite
                        },
                        color=PocketInk,
                        strokeWidth=3.dp,
                    )
                }
            } else TerminalBody(
                m,Modifier.fillMaxSize(),interactive && keyboard.requested,interactive=interactive,
                modifiers=modifiers,onRequestKeyboard={if(interactive) keyboard=keyboard.show()},
                onKeyboardVisibility={keyboard=keyboard.reportVisibility(it)},
            )
            overlay()
        }
        TerminalDock(
            m,keyboardShownOrRequested=keyboard.shownOrRequested,inputEnabled=interactive,opening=opening,
            onToggleKeyboard={if(interactive) keyboard=keyboard.toggle()},
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
                if(interactive && pty!=null && terminal===m.emulator && m.speechTerminalIdentity()===pty) {
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
        PocketDialog(
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
    BackHandler(onBack=m::leaveTerminalRecovery)
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
            OutlinedButton(onClick=m::leaveTerminalRecovery) {
                Text("SSH connections",fontSize=17.sp)
            }
        }
    }
}

@Composable private fun CoreTerminal(m:PocketModel,saveFile:()->Unit,uploadFile:()->Unit,opening:TerminalOpeningUi?,onBack:()->Unit) {
    val headerSize=maxOf(48.dp, with(LocalDensity.current) {24.sp.toDp()} + 16.dp)
    val files = m.filesUi
    val menuOpen = m.agentMenuOpen
    val back=if(opening!=null) m::cancelTerminalOpening else onBack
    // This is only a view preference for the current terminal, not a navigation destination.
    var headerHidden by remember(m.emulator,opening) {mutableStateOf(false)}
    val hideHeader=stringResource(R.string.terminal_header_hide)
    val showHeader=stringResource(R.string.terminal_header_show)
    val interactiveHeader=opening==null && !menuOpen && files==null
    BackHandler(enabled = !menuOpen && files == null) {
        if(headerHidden) headerHidden=false else back()
    }
    val restoreAction=if(headerHidden && interactiveHeader) Modifier.semantics {
        customActions=listOf(CustomAccessibilityAction(showHeader) {headerHidden=false;true})
    } else Modifier
    val swipeToHide=if(interactiveHeader) Modifier.pointerInput(Unit) {
        val threshold=24.dp.toPx()
        var distance=0f
        detectVerticalDragGestures(
            onDragStart={distance=0f},
            onDragEnd={if(distance<=-threshold) headerHidden=true},
        ) { _,delta -> distance+=delta }
    } else Modifier
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        TerminalPanel(m,Modifier.fillMaxSize().imePadding().then(restoreAction),
            suspended=menuOpen || files!=null,opening=opening!=null) {
            if (!headerHidden && !menuOpen && files == null) Row(
                Modifier.align(androidx.compose.ui.Alignment.TopStart).fillMaxWidth()
                    .then(swipeToHide).padding(horizontal=12.dp,vertical=6.dp),
                verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(8.dp),
            ) {
                RoundBackArrow(if(opening!=null) stringResource(android.R.string.cancel) else "Back to agents",back,headerSize)
                Box(Modifier.weight(1f)) {
                    Surface(
                        shape=CircleShape,color=PocketBlack,contentColor=PocketInk,
                        border=BorderStroke(1.dp,PocketOutline),
                    ) {
                        Box(Modifier.height(headerSize),contentAlignment=androidx.compose.ui.Alignment.Center) {
                            val hideAction=if(interactiveHeader) Modifier.semantics {
                                customActions=listOf(CustomAccessibilityAction(hideHeader) {headerHidden=true;true})
                            } else Modifier
                            Text((opening?.title ?: m.agentName).ifBlank{"Agent"},fontSize=20.sp,lineHeight=24.sp,
                                modifier=Modifier.padding(horizontal=15.dp).then(hideAction),
                                maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                AgentActionsMenu(headerSize,m::openAgentMenu,enabled=opening==null)
            }
        }
        when {
            files != null -> AgentFilesExplorer(files,m::openFilesEntry,m::backInFiles,m::retryFiles,onDownload=saveFile,onUpload=uploadFile,
                onDeleteEntry=m::askDeleteFilesEntry,onConfirmDelete=m::confirmFilesDelete,onCancelDelete=m::cancelFilesDelete,
                onNewFolder=m::askNewFilesFolder,onConfirmNewFolder=m::confirmNewFilesFolder,onCancelNewFolder=m::cancelNewFilesFolder)
            menuOpen -> {
                AgentMenuScreen(m.activeAgentDetails?.name ?: m.agentName,m.activeAgentDetails,m.rosterAvatarScope,
                    m.canRenameActiveAgent,m.agentSettingsLoading,m.agentSettingsNotice,
                    m::askToRenameActiveAgent,m::refreshAgentSettings,m::openAgentFiles,m::dismissAgentSettings,
                    progressAnchor=m.codexProgressAnchor,readProgress=m::readCodexProgress)
                m.renamingAgent?.let {RenameAgentDialog(m,it,agentSettings=true)}
            }
        }
    }
}

@Composable internal fun RoundBackArrow(label:String,onClick:()->Unit,diameter:Dp,modifier:Modifier=Modifier) {
    PocketIconControl(label,onClick,diameter,modifier=modifier) { color ->
        Canvas(Modifier.size(24.dp)) {
            val center=size.height/2
            val left=Offset(size.width*0.22f,center)
            val width=PocketGlyphStroke.toPx()
            drawLine(color,left,Offset(size.width*0.78f,center),width,StrokeCap.Round)
            drawLine(color,left,Offset(size.width*0.48f,size.height*0.24f),width,StrokeCap.Round)
            drawLine(color,left,Offset(size.width*0.48f,size.height*0.76f),width,StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
// LazyRow applies alignment only to content with spare width; overflow stays scrollable.
internal val pinnedStripArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)

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
    var showCreateOptions by remember {mutableStateOf(false)}
    var chooseWorkspace by remember {mutableStateOf(false)}
    BackHandler(onBack=when {
        chooseWorkspace -> { {chooseWorkspace=false} }
        m.renamingAgent!=null -> m::cancelRename
        else -> m::backToSshConnections
    })
    m.renamingAgent?.let {RenameAgentDialog(m,it)}
    if(chooseWorkspace) WorkspaceTerminalPicker(m.createWorkspaceChoices,
        onCancel={chooseWorkspace=false},onChoose={
            chooseWorkspace=false
            m.createPlainTerminal(it)
        })
    val isAgents=rosterView==AgentRosterView.AGENTS
    val title=stringResource(rosterView.label)
    val listLabel=stringResource(if(isAgents) R.string.agent_roster_agent_list else R.string.agent_roster_workspace_list)
    val sessions=m.switchSessions
    val searchScope=rememberCoroutineScope()
    var search by rememberSaveable {mutableStateOf("")}
    var statusFilter by rememberSaveable {mutableStateOf(SpaceStatusFilter.ALL)}
    val isFiltered=search.isNotBlank() || statusFilter!=SpaceStatusFilter.ALL
    val pins=m.rosterPins
    val agentRows=remember(sessions,isAgents,search,statusFilter,pins) {
        if(isAgents) filteredAgentRoster(sessions,search,statusFilter,pins) else emptyList()
    }
    val (pinnedAgents,ordinaryAgents)=remember(agentRows,pins) {agentRows.partition {it.address in pins}}
    val pinnedStripLabel=stringResource(R.string.roster_agent_pinned_strip)
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
                        if(pinnedAgents.isNotEmpty()) item(key="pinned-agents") {
                            LazyRow(
                                Modifier.fillMaxWidth().semantics {contentDescription=pinnedStripLabel},
                                horizontalArrangement=pinnedStripArrangement,
                            ) {
                                items(pinnedAgents,key={it.address}) { session ->
                                    AgentRosterRow(m,session,spacesMode=false,pinnedTile=true)
                                }
                            }
                        }
                        rosterRows(m,ordinaryAgents)
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
                RoundBackArrow("Back to SSH connections",m::backToSshConnections,48.dp)
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                    AgentRosterViewSwitch(rosterView,onRosterViewChange)
                }
                if(m.canCreateAgent) Box {
                    PocketIconControl(
                        label=stringResource(R.string.agent_roster_new_terminal),
                        onClick={if(m.switchingTuiAgents) showCreateOptions=true else m.createPlainTerminal()},
                        enabled=!m.switchBusy,
                    ) { ink ->
                        Canvas(Modifier.size(24.dp)) {
                            val center=size.width/2
                            val edge=size.width*.22f
                            val stroke=PocketGlyphStroke.toPx()
                            drawLine(ink,Offset(edge,center),Offset(size.width-edge,center),stroke,StrokeCap.Round)
                            drawLine(ink,Offset(center,edge),Offset(center,size.height-edge),stroke,StrokeCap.Round)
                        }
                    }
                    PocketMenu(expanded=showCreateOptions,onDismissRequest={showCreateOptions=false}) {
                        DropdownMenuItem(
                            text={Text(stringResource(R.string.agent_roster_new_workspace),fontSize=18.sp)},
                            onClick={showCreateOptions=false;m.createPlainTerminal()},
                            modifier=Modifier.heightIn(min=48.dp),
                        )
                        DropdownMenuItem(
                            text={Text(stringResource(R.string.agent_roster_terminal_in_workspace),fontSize=18.sp)},
                            onClick={showCreateOptions=false;chooseWorkspace=true},
                            modifier=Modifier.heightIn(min=48.dp),
                        )
                    }
                } else Spacer(Modifier.size(48.dp))
            }
            if(notice!=null) {
                Surface(
                    modifier=Modifier.fillMaxWidth().onSizeChanged {noticePx=it.height},
                    color=theme.surface,
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
        AgentRosterRow(m,session,spacesMode)
    }
}

@Composable private fun AgentRosterRow(m:PocketModel,session:CoreSession,spacesMode:Boolean,pinnedTile:Boolean=false) {
    AgentPickerRow(
        session=session,
        current=session.address==m.activeCoreAddress,
        tui=m.switchingTuiAgents,
        enabled=!m.switchBusy,
        onClick={m.chooseAgent(session)},
        onRename=if(m.canSwitchAgents && m.switchingTuiAgents && session.tuiLocation!=null) {{m.askToRename(session)}} else null,
        onTogglePin=if(m.canPinAgent(session)) {{m.toggleAgentPin(session)}} else null,
        pinned=m.isAgentPinned(session),
        pinnedTile=pinnedTile,
        spacesMode=spacesMode,
        compactAgent=!spacesMode,
        avatarScope=m.rosterAvatarScope,
    )
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
    val searchText=MaterialTheme.typography.bodyLarge.copy(color=PocketInk)
    Row(
        modifier,
        horizontalArrangement=Arrangement.spacedBy(4.dp),
        verticalAlignment=Alignment.CenterVertically,
    ) {
        Surface(
            modifier=Modifier.weight(1f),shape=CircleShape,color=PocketBlack,
            contentColor=PocketInk,border=BorderStroke(if(searchFocused) 2.dp else 1.dp,PocketOutline),
        ) {
            Row(
                modifier=Modifier.heightIn(min=PocketControlSize),
                verticalAlignment=Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value=query,onValueChange=onQueryChange,singleLine=true,
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                    keyboardActions=KeyboardActions(onSearch={onSearch()}),
                    textStyle=searchText,cursorBrush=SolidColor(PocketInk),
                    modifier=Modifier.weight(1f)
                        .onFocusChanged {searchFocused=it.isFocused}
                        .semantics {contentDescription=searchLabel},
                    decorationBox={ input ->
                        Box(
                            Modifier.fillMaxWidth().heightIn(min=PocketControlSize).padding(
                                start=14.dp,end=if(query.isEmpty()) 14.dp else 2.dp,
                                top=5.dp,bottom=5.dp,
                            ),
                            contentAlignment=Alignment.CenterStart,
                        ) {
                            if(query.isEmpty()) Text(
                                stringResource(R.string.agent_roster_search_hint),
                                style=searchText,color=PocketInk.copy(alpha=.82f),
                                maxLines=1,overflow=TextOverflow.Ellipsis,
                            )
                            input()
                        }
                    },
                )
                if(query.isNotEmpty()) {
                    val label=stringResource(R.string.agent_roster_clear_search)
                    PocketIconControl(label=label,onClick={onQueryChange("")}) { ink ->
                        Canvas(Modifier.size(PocketGlyphSize)) {
                            val stroke=PocketGlyphStroke.toPx()
                            val first=size.width*.28f
                            val last=size.width*.72f
                            drawLine(ink,Offset(first,first),Offset(last,last),stroke,StrokeCap.Round)
                            drawLine(ink,Offset(first,last),Offset(last,first),stroke,StrokeCap.Round)
                        }
                    }
                }
            }
        }
        Box {
            var showFilters by remember {mutableStateOf(false)}
            val filterAction=stringResource(R.string.agent_roster_status_filter)
            val activeFilter=stringResource(status.labelResource(agentsOnly))
            PocketIconControl(
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
            PocketMenu(expanded=showFilters,onDismissRequest={showFilters=false}) {
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

@Composable private fun WorkspaceTerminalPicker(
    workspaces:List<TuiWorkspaceChoice>,onCancel:()->Unit,onChoose:(TuiWorkspaceChoice)->Unit,
) {
    val duplicates=remember(workspaces) {workspaces.groupingBy {it.label}.eachCount()}
    PocketDialog(
        onDismissRequest=onCancel,
        title={Text(stringResource(R.string.agent_roster_choose_workspace))},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(if(workspaces.isEmpty()) R.string.agent_roster_no_workspace_destinations
                    else R.string.agent_roster_workspace_opens_shell))
                if(workspaces.isNotEmpty()) LazyColumn(
                    modifier=Modifier.fillMaxWidth().heightIn(max=320.dp),
                    verticalArrangement=Arrangement.spacedBy(6.dp),
                ) {
                    items(workspaces,key={it.id}) { workspace ->
                        OutlinedButton(
                            onClick={onChoose(workspace)},
                            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),
                        ) {
                            Text(if((duplicates[workspace.label] ?: 0)>1) "${workspace.label} (${workspace.id})" else workspace.label,
                                fontSize=17.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton={TextButton(onClick=onCancel){Text(stringResource(android.R.string.cancel))}},
    )
}

@Composable private fun RenameAgentDialog(m:PocketModel,session:CoreSession,agentSettings:Boolean=false) {
    val item=if(session.isAgent) TuiMutations.RenameTarget.AGENT else TuiMutations.RenameTarget.PANE
    var kind by remember(session.address) {mutableStateOf(TuiMutations.RenameTarget.TAB)}
    val existing=when(kind) {
        TuiMutations.RenameTarget.TAB -> session.rosterPlacement?.tabLabel.orEmpty()
        TuiMutations.RenameTarget.AGENT -> session.tuiAgentName.orEmpty()
        TuiMutations.RenameTarget.PANE -> session.name
    }
    var label by remember(session.address,kind,existing) {mutableStateOf(existing)}
    PocketDialog(
        onDismissRequest=m::cancelRename,
        title={Text("Rename in Herdr")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(TuiMutations.RenameTarget.TAB,item).forEach { choice ->
                    androidx.compose.material3.OutlinedButton(onClick={kind=choice},
                        modifier=Modifier.fillMaxWidth().semantics {selected=kind==choice}) {
                        RadioButton(selected=kind==choice,onClick=null,modifier=Modifier.padding(end=8.dp))
                        Text("Rename ${choice.displayName.lowercase()}")
                    }
                }
                Text(when(kind) {
                    TuiMutations.RenameTarget.TAB -> "Changes the tab title on the host for every pane in this tab."
                    TuiMutations.RenameTarget.AGENT -> "Changes the agent’s name, not the tab. Use 1–32 lowercase letters, digits, hyphens or underscores, starting with a letter."
                    TuiMutations.RenameTarget.PANE -> "Changes only this pane’s label, not the tab."
                })
                Field("${kind.displayName} name",label,{label=it},true)
            }
        },
        confirmButton={
            TextButton(onClick={m.renameAgent(label.trim(),kind)},
                enabled=TuiMutations.canRenameTarget(label.trim(),kind) && label.trim()!=existing) {
                Text(if(agentSettings) stringResource(R.string.agent_settings_save_name) else "Save")
            }
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
