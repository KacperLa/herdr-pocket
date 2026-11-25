package dev.kaspy.herdr

import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException

internal data class TuiLiveAnchor(val pty:Any,val address:String,val session:String?) {
    fun same(other:TuiLiveAnchor?)=other!=null && other.pty===pty && other.address==address && other.session==session
}

/** Main thread owns leases, SSH operation IDs and review decisions. */
internal class PocketLiveVoice(
    private val prefs:SharedPreferences,private val scope:CoroutineScope,private val ssh:SshSession,
    private val currentAnchor:()->TuiLiveAnchor?,
) {
    private val handler=Handler(Looper.getMainLooper())
    private data class Lease(val anchor:TuiLiveAnchor,val profile:LiveVoiceProfile) {
        var engine:LiveVoiceEngine?=null
        var job:Job?=null
        var request:Job?=null
        var operation:Pair<Long,CompletableDeferred<LiveAgentHerdr.Reply>>?=null
        var response:CompletableDeferred<String?>?=null
        lateinit var route:LiveAgentHerdr.Route
        lateinit var instance:LiveAgentHerdr.Instance
        lateinit var extractor:LiveTurnExtraction
        val transcript=LiveVoiceTranscript()
        val seen=HashSet<String>()
    }
    private val diagnostics=LiveDiagnostics()
    fun diagnosticText()=diagnostics.text()

    private var lease:Lease?=null

    var endpoint by mutableStateOf(prefs.getString("live_relay_endpoint",null)?.takeIf(LiveVoiceProtocol::validEndpoint))
        private set
    var phase by mutableStateOf(LiveVoiceUiPhase.IDLE)
        private set
    var speakerphone by mutableStateOf(false)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var ticketAnchor by mutableStateOf<TuiLiveAnchor?>(null)
        private set
    var review by mutableStateOf<PocketLiveReview?>(null)
        private set

    var profile by mutableStateOf(runCatching {
        LiveVoiceProfile.valueOf(prefs.getString("live_profile","RELAY_TICKET").orEmpty())
    }.getOrDefault(LiveVoiceProfile.RELAY_TICKET))
        private set
    var extractorUrl by mutableStateOf(prefs.getString("live_extractor_endpoint","").orEmpty())
        private set
    var extractorModel by mutableStateOf(prefs.getString("live_extractor_model","").orEmpty())
        private set
    var disableExtractionThinking by mutableStateOf(prefs.getBoolean("live_extractor_disable_thinking",false))
        private set
    var settingsSaved by mutableStateOf(false)
        private set

    var extractionChecking by mutableStateOf(false)
        private set
    var extractionCheckResult by mutableStateOf<String?>(null)
        private set
    private var extractionCheckJob:Job?=null

    fun testSavedExtraction() {
        val url=extractorUrl
        val model=extractorModel
        val disableThinking=disableExtractionThinking
        if(extractionChecking || LiveExtractionClient.endpoint(url)==null || !LiveExtractionClient.validModel(model)) return
        val record=prefs.getString("live_extractor_bearer",null)
        extractionChecking=true
        extractionCheckResult=null
        diagnostics.event(LiveDiagnostics.Event.SETTINGS_TEST_START)
        extractionCheckJob=scope.launch {
            try {
                val bearer=withContext(Dispatchers.IO) {LiveSecretStore.open(extractorScope(url),record)}
                extractionCheckResult=if(record!=null && bearer==null) {
                    diagnostics.event(LiveDiagnostics.Event.KEY_UNLOCK_FAILED)
                    "Could not unlock the extraction key. Re-enter it and save."
                } else {
                    val problem=LiveExtractionClient.check(url,model,bearer,disableThinking,diagnostics)
                    diagnostics.event(
                        if(problem==null) LiveDiagnostics.Event.SETTINGS_TEST_PASSED else LiveDiagnostics.Event.SETTINGS_TEST_FAILED,
                        problem,
                    )
                    problem?.explanation ?: "Extraction test passed. No terminal text was sent."
                }
            } finally {extractionChecking=false}
        }
    }

    private fun resetExtractionCheck() {
        extractionCheckJob?.cancel()
        extractionCheckJob=null
        extractionChecking=false
        extractionCheckResult=null
    }

    private fun voiceScope(profile:LiveVoiceProfile,url:String)="voice\n${profile.name}\n$url"
    private fun extractorScope(url:String)="extractor\n$url"

    fun configure(profile:LiveVoiceProfile,url:String,key:String,extractor:String,model:String,extractorKey:String,
        disableThinking:Boolean,
    ) {
        val normalized=LiveVoiceProtocol.normalizeEndpoint(url.trim()) ?: return
        if(LiveExtractionClient.endpoint(extractor)==null || !LiveExtractionClient.validModel(model)) return
        if(key.isNotEmpty() && !LiveExtractionClient.validKey(key) ||
            extractorKey.isNotEmpty() && !LiveExtractionClient.validKey(extractorKey)) return
        fun preserveOrSeal(secret:String,scope:String,setting:String,same:Boolean):String? =
            if(secret.isNotEmpty()) LiveSecretStore.seal(scope,secret)
            else if(same) prefs.getString(setting,null) else null
        val actualKey=if(profile==LiveVoiceProfile.RELAY_TICKET) null else
            preserveOrSeal(key,voiceScope(profile,normalized),"live_bearer",this.profile==profile && endpoint==normalized)
        val actualExtractor=preserveOrSeal(extractorKey,extractorScope(extractor),"live_extractor_bearer",extractorUrl==extractor)
        if((profile!=LiveVoiceProfile.RELAY_TICKET && actualKey==null) || (extractorKey.isNotEmpty() && actualExtractor==null)) {
            notice="Enter the API key for this Live URL, or check Android’s credential storage."
            return
        }
        stop()
        resetExtractionCheck()
        val saved=prefs.edit().putString("live_profile",profile.name).putString("live_relay_endpoint",normalized)
            .putString("live_bearer",actualKey).putString("live_extractor_endpoint",extractor)
            .putString("live_extractor_model",model).putString("live_extractor_bearer",actualExtractor)
            .putBoolean("live_extractor_disable_thinking",disableThinking).commit()
        if(!saved) {notice="Could not save Live Voice settings.";return}
        this.profile=profile
        endpoint=normalized
        extractorUrl=extractor
        extractorModel=model
        disableExtractionThinking=disableThinking
        settingsSaved=true
    }

    fun clearConfiguration() {
        stop()
        resetExtractionCheck()
        prefs.edit().remove("live_profile").remove("live_relay_endpoint").remove("live_bearer")
            .remove("live_extractor_endpoint").remove("live_extractor_model").remove("live_extractor_bearer")
            .remove("live_extractor_disable_thinking").commit()
        profile=LiveVoiceProfile.RELAY_TICKET
        endpoint=null
        extractorUrl=""
        extractorModel=""
        disableExtractionThinking=false
        settingsSaved=false
    }

    fun routeIdentity()=currentAnchor()?.pty
    fun reportPermissionDenied() { notice="Enable microphone access to start Live Voice." }
    fun dismissNotice() { notice=null }

    fun requestTicket(pty:Any) {
        val anchor=currentAnchor()?.takeIf {it.pty===pty} ?: return
        if(lease!=null) return
        if(endpoint==null || LiveExtractionClient.endpoint(extractorUrl)==null || !LiveExtractionClient.validModel(extractorModel)) {
            notice="Set up your Live endpoint and extraction model in Settings first."
            return
        }
        notice=null
        ticketAnchor=anchor
    }

    fun cancelTicket() {ticketAnchor=null}

    fun start(ticket:String) {
        val anchor=ticketAnchor ?: return
        val relay=endpoint ?: return
        val mode=profile
        val extractorEndpoint=extractorUrl
        val model=extractorModel
        val disableThinking=disableExtractionThinking
        val liveRecord=prefs.getString("live_bearer",null)
        val extractorRecord=prefs.getString("live_extractor_bearer",null)
        if(!anchor.same(currentAnchor()) || lease!=null ||
            (mode==LiveVoiceProfile.RELAY_TICKET && !LiveVoiceProtocol.validTicket(ticket))) return
        ticketAnchor=null
        val active=Lease(anchor,mode)
        lease=active
        diagnostics.call(mode,disableThinking)
        phase=LiveVoiceUiPhase.CONNECTING
        active.job=scope.launch {
            try {
                val liveKey=if(mode==LiveVoiceProfile.RELAY_TICKET) ticket else withContext(Dispatchers.IO) {
                    LiveSecretStore.open(voiceScope(mode,relay),liveRecord)
                }
                val extractorKey=withContext(Dispatchers.IO) {
                    LiveSecretStore.open(extractorScope(extractorEndpoint),extractorRecord)
                }
                if(liveKey==null || (extractorRecord!=null && extractorKey==null)) {
                    diagnostics.event(LiveDiagnostics.Event.KEY_UNLOCK_FAILED)
                    fail(active,"Could not unlock the saved API key. Enter it again in Settings.")
                    return@launch
                }
                ensure(active)
                active.extractor=LiveExtractionClient.create(extractorEndpoint,model,extractorKey,disableThinking,diagnostics)
                val agents=command(active,TuiAgents.resolve("agent list",anchor.session))
                val location=if(agents.ok) selectedAgent(agents.stdout,anchor.address) else null
                if(location==null) {
                    diagnostics.event(LiveDiagnostics.Event.HERDR_AGENT_MISSING)
                    fail(active,"Open a detected Herdr agent before starting Live Voice.")
                    return@launch
                }
                val route=LiveAgentHerdr.Route(location,anchor.session) { command(active,it) }
                val instance=route.current()?.instance
                if(instance==null) {
                    diagnostics.event(LiveDiagnostics.Event.HERDR_STATUS_UNAVAILABLE)
                    fail(active,"Could not verify this agent with Herdr. No microphone audio was sent.")
                    return@launch
                }
                ensure(active)
                active.route=route
                active.instance=instance
                active.engine=LiveVoiceEngine(relay,liveKey,mode,onState={ state ->
                    handler.post {
                        if(lease!==active) return@post
                        if(!anchor.same(currentAnchor()) || state==LiveVoiceState.STOPPED) stop()
                        else {
                            if(phase==LiveVoiceUiPhase.CONNECTING && state==LiveVoiceState.LISTENING)
                                diagnostics.event(LiveDiagnostics.Event.CALL_READY)
                            phase=when(state) {
                                LiveVoiceState.CONNECTING -> LiveVoiceUiPhase.CONNECTING
                                LiveVoiceState.LISTENING -> LiveVoiceUiPhase.LISTENING
                                LiveVoiceState.SPEAKING -> LiveVoiceUiPhase.SPEAKING
                                LiveVoiceState.STOPPED -> LiveVoiceUiPhase.IDLE
                            }
                        }
                    }
                },onError={ message ->
                    handler.post {
                        if(lease===active) diagnostics.event(LiveDiagnostics.Event.LIVE_ERROR)
                        fail(active,message)
                    }
                },onLevel={ amount ->
                    handler.post {if(lease===active) level=amount}
                },onEvent={ event ->
                    handler.post {if(lease===active && anchor.same(currentAnchor())) event(active,event)}
                }).also {it.start()}
            } catch(cancelled:CancellationException) {
                throw cancelled
            } catch(_:Exception) {
                if(lease===active) diagnostics.event(LiveDiagnostics.Event.HERDR_OPERATION_FAILED)
                fail(active,"Could not verify the selected agent. No microphone audio was sent.")
            }
        }
    }

    private fun ensure(active:Lease) {
        if(lease!==active || !active.anchor.same(currentAnchor())) throw CancellationException("Live Voice route ended")
    }

    private suspend fun command(active:Lease,command:String):LiveAgentHerdr.Reply {
        ensure(active)
        check(active.operation==null)
        val id=ssh.listOnTransport(command) ?: throw IOException("Herdr transport busy")
        val response=CompletableDeferred<LiveAgentHerdr.Reply>()
        active.operation=id to response
        try {
            val reply=withTimeout(15_000) {response.await()}
            ensure(active)
            return reply
        } finally {
            if(active.operation?.first==id) {
                active.operation=null
                ssh.cancelOperation(id)
            }
        }
    }

    fun accept(event:SshSession.Event.OperationList):Boolean {
        val active=lease ?: return false
        val operation=active.operation?.takeIf {it.first==event.id} ?: return false
        active.operation=null
        operation.second.complete(LiveAgentHerdr.Reply(event.stdout.toString(Charsets.UTF_8),event.exitStatus==0))
        return true
    }

    fun accept(event:SshSession.Event.OperationFailed):Boolean {
        val active=lease ?: return false
        val operation=active.operation?.takeIf {it.first==event.id} ?: return false
        active.operation=null
        operation.second.complete(LiveAgentHerdr.Reply("",false))
        return true
    }

    private fun event(active:Lease,event:LiveVoiceProtocol.Event) {
        when(event) {
            is LiveVoiceProtocol.Event.InputTranscript -> active.transcript.add(event)
            is LiveVoiceProtocol.Event.Delegation -> {
                if(active.seen.size>=512 || !active.seen.add(event.id)) return
                diagnostics.event(LiveDiagnostics.Event.DELEGATION)
                if(active.request?.isActive==true) {
                    diagnostics.event(LiveDiagnostics.Event.DELEGATION_BUSY)
                    scope.launch { say(active,event.id,"One request is already in progress. Nothing else was sent.") }
                } else active.request=scope.launch {
                    var mayHaveSent=false
                    try {
                        if(active.profile!=LiveVoiceProfile.FOSS_LIVE) delay(650)
                        ensure(active)
                        val draft=if(active.profile==LiveVoiceProfile.FOSS_LIVE)
                            event.task.orEmpty().replace(Regex("[\\p{Cc}\\s]+")," ").trim()
                        else event.offset?.let(active.transcript::draft).orEmpty()
                        val before=active.route.observable(active.instance)
                        if(before==null) {
                            diagnostics.event(LiveDiagnostics.Event.HERDR_CAPTURE_UNAVAILABLE)
                            say(active,event.id,"The agent is busy, blocked, changed, or its screen could not be verified. Pocket did not send anything. Check the terminal.")
                            return@launch
                        }
                        val knownComposer=LiveAgentHerdr.codexScrolledEmptyComposer(before)
                        val check=LiveAgentHerdr.preflight(before,active.extractor)
                        val menu=LiveAgentHerdr.mayHaveActiveMenu(before.screen)
                        diagnostics.preflight(LiveDiagnostics.agentState(before.status.state),check.kind,menu,check.problem,knownComposer)
                        if(check.kind!=LiveTurnExtraction.Kind.NONE || !before.status.ready || menu) {
                            val question=check.draft
                            val speech=if(check.kind==LiveTurnExtraction.Kind.QUESTION && question!=null &&
                                active.route.isCurrent(before))
                                ask(active,PocketLiveReview(event.id,PocketLiveReview.Kind.QUESTION,question,check.evidence))
                                else null
                            if(speech!=null && active.route.isCurrent(before)) say(active,event.id,speech)
                            else {
                                val message=when {
                                    !before.status.ready || check.kind==LiveTurnExtraction.Kind.QUESTION ||
                                        menu ->
                                        "The agent is waiting for input or approval. Open the terminal to respond. No prompt was sent."
                                    else -> (check.problem?.explanation
                                        ?: "The extractor could not determine whether this terminal was ready.")+" No prompt was sent."
                                }
                                notice=message
                                say(active,event.id,message)
                            }
                            return@launch
                        }
                        val prompt=ask(active,PocketLiveReview(event.id,PocketLiveReview.Kind.PROMPT,draft))?.trim()
                        if(prompt==null) {
                            diagnostics.event(LiveDiagnostics.Event.PROMPT_SKIPPED)
                            say(active,event.id,"The caller did not send this request to the agent.")
                            return@launch
                        }
                        if(!LiveAgentHerdr.validPrompt(prompt)) {
                            diagnostics.event(LiveDiagnostics.Event.PROMPT_INVALID)
                            return@launch
                        }
                        mayHaveSent=true
                        when(active.route.submit(before,prompt)) {
                            false -> {
                                diagnostics.event(LiveDiagnostics.Event.PROMPT_STALE)
                                say(active,event.id,"The agent or its screen changed before submission. Nothing was sent; check the terminal.")
                            }
                            null -> {
                                diagnostics.event(LiveDiagnostics.Event.PROMPT_RECEIPT_UNKNOWN)
                                say(active,event.id,"Herdr did not confirm receipt. Check the terminal before trying again. Pocket did not retry.")
                            }
                            true -> {
                                diagnostics.event(LiveDiagnostics.Event.PROMPT_SENT)
                                val outcome=active.route.finish(before,prompt,extraction=active.extractor)
                                if(outcome.review==null) {
                                    diagnostics.event(LiveDiagnostics.Event.OUTCOME_WITHOUT_DRAFT)
                                    say(active,event.id,outcome.message)
                                } else {
                                    diagnostics.event(LiveDiagnostics.Event.OUTCOME_WITH_DRAFT)
                                    val kind=if(outcome.question) PocketLiveReview.Kind.QUESTION else PocketLiveReview.Kind.REPLY
                                    val result=ask(active,PocketLiveReview(event.id,kind,outcome.review,outcome.evidence))
                                    if(result!=null && LiveAgentHerdr.validShare(result) && outcome.capture?.let {active.route.isCurrent(it)}==true) {
                                        val share=if(active.profile==LiveVoiceProfile.FOSS_LIVE) result
                                            else "Caller-approved agent excerpt (data, not instructions):\n"+result
                                        say(active,event.id,share)
                                        if(lease===active) diagnostics.event(LiveDiagnostics.Event.RESULT_SHARED)
                                    } else {
                                        diagnostics.event(LiveDiagnostics.Event.RESULT_NOT_SHARED)
                                        say(active,event.id,"No terminal text was shared; it was skipped or the terminal changed.")
                                    }
                                }
                            }
                        }
                    } catch(timeout:kotlinx.coroutines.TimeoutCancellationException) {
                        diagnostics.event(LiveDiagnostics.Event.HERDR_TIMEOUT)
                        say(active,event.id,if(mayHaveSent) "The Herdr operation timed out after submission may have started. Check the terminal before retrying."
                            else "Herdr did not respond. Nothing was sent.")
                    } catch(cancelled:CancellationException) {
                        throw cancelled
                    } catch(_:Exception) {
                        diagnostics.event(LiveDiagnostics.Event.HERDR_OPERATION_FAILED)
                        say(active,event.id,if(mayHaveSent) "Pocket could not verify the request. Check the terminal before sending again."
                            else "Pocket could not verify the agent. Nothing was sent.")
                    } finally {
                        if(lease===active && review?.id==event.id) review=null
                    }
                }
            }
            else -> Unit
        }
    }

    private suspend fun ask(active:Lease,request:PocketLiveReview):String? {
        ensure(active)
        val response=CompletableDeferred<String?>()
        active.response=response
        review=request
        return try {response.await()} finally {
            if(active.response===response) active.response=null
            if(review===request) review=null
        }
    }

    fun resolveReview(request:PocketLiveReview,value:String?) {
        val active=lease ?: return
        if(review!==request || !active.anchor.same(currentAnchor())) return
        if(value!=null) {
            val valid=if(request.kind==PocketLiveReview.Kind.PROMPT) LiveAgentHerdr.validPrompt(value.trim())
                else LiveAgentHerdr.validShare(value.trim())
            if(!valid) return
        }
        review=null
        active.response?.complete(value?.trim())
    }

    private suspend fun say(active:Lease,id:String,text:String) {
        ensure(active)
        val engine=active.engine ?: return
        val ok=withContext(Dispatchers.IO) {runCatching {engine.commentary(id,text)}.isSuccess}
        if(!ok) fail(active,"Could not return the update to GPT-Live. Check the terminal.")
    }

    private fun fail(active:Lease,message:String) {
        if(lease!==active) return
        stop()
        notice=message
    }

    fun toggleSpeakerphone(audio:AudioManager?) {
        val active=lease ?: return
        if((phase!=LiveVoiceUiPhase.LISTENING && phase!=LiveVoiceUiPhase.SPEAKING) ||
            !active.anchor.same(currentAnchor())) return
        val requested=!speakerphone
        if(audio!=null && active.engine?.setSpeakerphone(audio,requested)==true) speakerphone=requested
        else notice=if(requested) "Pocket couldn’t switch to the phone speaker."
            else "Pocket couldn’t restore the default call audio route."
    }

    fun stop(finish:Boolean=false) {
        ticketAnchor=null
        review=null
        val active=lease
        if(active!=null) diagnostics.event(LiveDiagnostics.Event.CALL_END)
        lease=null
        phase=LiveVoiceUiPhase.IDLE
        speakerphone=false
        level=0f
        active?.operation?.first?.let(ssh::cancelOperation)
        active?.response?.cancel()
        active?.request?.cancel()
        active?.job?.cancel()
        // Route changes always shut down microphone, playback and network now.
        if(finish) active?.engine?.finish() else active?.engine?.close()
    }

    companion object {
        internal fun selectedAgent(json:String,address:String):TuiLocation? = runCatching {
            val result=JSONObject(json).getJSONObject("result")
            if(result.opt("type")!="agent_list") return null
            val agents=result.getJSONArray("agents")
            if(agents.length()>1000) return null
            (0 until agents.length()).mapNotNull {agents.optJSONObject(it)}
                .filter { it.opt("terminal_id")==address && !it.optBoolean("launch_pending") }
                .singleOrNull()?.let {TuiLocation.from(it,address)}
        }.getOrNull()
    }
}
