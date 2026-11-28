package dev.kaspy.herdr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal val lucidPaneId=Regex("[A-Za-z0-9]{1,40}:p[A-Za-z0-9]{1,40}")
internal data class LucidAdvertisement(val paneId:String,val label:String)
internal const val LUCID_DISCOVERY_MAX_BYTES=100_000

internal fun parseLucidAdvertisements(text:String):Map<String,LucidAdvertisement> = runCatching {
    if(text.length !in 1..LUCID_DISCOVERY_MAX_BYTES || text.trimEnd().contains('\n')) return emptyMap()
    val body=JSONObject(text)
    if(body.optInt("version")!=1) return emptyMap()
    val sessions=body.optJSONArray("sessions") ?: return emptyMap()
    if(sessions.length()>128) return emptyMap()
    val result=mutableMapOf<String,LucidAdvertisement>()
    val duplicates=mutableSetOf<String>()
    for(index in 0 until sessions.length()) {
        val row=sessions.optJSONObject(index) ?: continue
        val id=row.optString("paneId")
        if(!lucidPaneId.matches(id)) continue
        val ad=LucidAdvertisement(id,lucidSafeDisplay(row.optString("label"),100).ifBlank {"Lucid ACP"})
        if(result.put(id,ad)!=null) duplicates.add(id)
    }
    result - duplicates
}.getOrDefault(emptyMap())

internal fun lucidMatches(session:CoreSession,ads:Map<String,LucidAdvertisement>):Boolean =
    session.state=="live" && session.tuiLocation?.paneId?.let(ads::containsKey)==true

internal class LucidTunnel(val input:InputStream,val output:OutputStream,private val disconnect:()->Unit) : AutoCloseable {
    private val closed=java.util.concurrent.atomic.AtomicBoolean()
    override fun close() {if(closed.compareAndSet(false,true)) disconnect()}
}

private fun readLucidRecord(input:InputStream):JSONObject? {
    val bytes=ByteArrayOutputStream()
    while(true) {
        val next=input.read()
        if(next==-1) return if(bytes.size()==0) null else throw IOException("Incomplete Lucid event")
        if(next==10) return JSONObject(bytes.toString("UTF-8"))
        if(bytes.size()>=410000) throw IOException("Lucid event is too large")
        bytes.write(next)
    }
}

private fun JSONObject.events(name:String):List<JSONObject> {
    val rows=optJSONArray(name) ?: return emptyList()
    return (0 until rows.length().coerceAtMost(32)).mapNotNull {rows.optJSONObject(it)}
}

internal fun lucidSafeDisplay(text:String,max:Int=16000)=text.take(max).filterNot {
    (it.isISOControl() && it!='\n' && it!='\t') || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069'
}

@Composable
internal fun LucidChatScreen(open:()->LucidTunnel,title:String,overlay:Boolean,voice:PocketLiveVoice,voiceLink:LucidVoiceLink,
    dictationAutoSend:Boolean,onBack:()->Unit,onDetails:()->Unit,
) {
    val scope=rememberCoroutineScope()
    val history=remember { mutableStateListOf<JSONObject>() }
    val permissions=remember { mutableStateMapOf<String,JSONObject>() }
    var body by remember {mutableStateOf("")}
    var instance by remember {mutableStateOf("")}
    var after by remember {mutableLongStateOf(0)}
    var busy by remember {mutableStateOf(false)}
    var pendingPrompt by remember {mutableStateOf<String?>(null)}
    var pendingDecision by remember {mutableStateOf<Pair<String,String>?>(null)}
    var ready by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf<String?>(null)}
    var notice by remember {mutableStateOf<String?>(null)}
    var ask by remember {mutableStateOf<JSONObject?>(null)}
    var connection by remember {mutableStateOf<Channel<JSONObject>?>(null)}
    var closeTunnel by remember {mutableStateOf<(() -> Unit)?>(null)}
    var job by remember {mutableStateOf<kotlinx.coroutines.Job?>(null)}
    val list=rememberLazyListState()
    fun followLatest() {if(history.isNotEmpty()) list.requestScrollToItem(history.size)}


    fun stop() {
        voiceLink.unbind()
        closeTunnel?.invoke()
        closeTunnel=null
        job?.cancel()
        job=null
        connection?.close()
        connection=null
        ready=false
    }
    DisposableEffect(Unit) {onDispose {stop()}}
    BackHandler(enabled=!overlay,onBack=onBack)
    fun connect() {
        if(job!=null) return
        val queue=Channel<JSONObject>(8)
        connection=queue
        status=null
        notice=null
        job=scope.launch {
            var tunnel:LucidTunnel?=null
            try {
                // Acquire ownership within this coroutine even if the view is abandoned during SSH setup.
                withContext(Dispatchers.IO) { tunnel=open() }
                val live=tunnel ?: throw IOException()
                closeTunnel={live.close()}
                coroutineScope {
                    val hello=JSONObject().put("op","hello").put("version",1)
                        .put("instance",instance).put("after",after)
                    val writer=launch(Dispatchers.IO) {
                        fun write(row:JSONObject){live.output.write((row.toString()+"\n").toByteArray(Charsets.UTF_8));live.output.flush()}
                        write(hello)
                        while(true) write(queue.receiveCatching().getOrNull() ?: break)
                    }
                    var receivedHello=false
                    val handshakeTimer=launch {
                        delay(8000)
                        if(!receivedHello) live.close()
                    }
                    try {
                        val stream=BufferedInputStream(live.input)
                        while(true) {
                            val row=withContext(Dispatchers.IO) {readLucidRecord(stream)} ?: break
                            when(row.optString("type")) {
                                "hello" -> {
                                    check(row.optInt("version")==1)
                                    receivedHello=true
                                    handshakeTimer.cancel()
                                    if(row.optBoolean("reset")) history.clear()
                                    instance=row.optString("instance")
                                    after=row.optLong("after")
                                    busy=row.optBoolean("busy")
                                    permissions.clear()
                                    row.events("pending").forEach {permissions[it.optString("permissionId")]=it}
                                    if(row.optBoolean("truncated")) notice="Earlier messages expired."
                                }
                                "synced" -> {
                                    check(row.optLong("seq")==after)
                                    ready=true
                                    pendingPrompt=null
                                    voiceLink.bind(instance) {id,text ->
                                        if(!ready || busy || permissions.isNotEmpty() || pendingPrompt!=null || pendingDecision!=null)
                                            false
                                        else queue.trySend(JSONObject().put("op","prompt").put("id",id)
                                            .put("instance",instance).put("text",text)).isSuccess.also {if(it) pendingPrompt=id}
                                    }
                                }
                                "ack" -> {
                                    voiceLink.event(row)
                                    val id=row.optString("id")
                                    if(id==pendingPrompt) pendingPrompt=null
                                    if(id==pendingDecision?.first) pendingDecision=null
                                    if(!row.optBoolean("ok")) notice="Lucid didn’t accept the request. Check the conversation before trying again."
                                }
                                "error" -> {status="The host’s Lucid bridge rejected the connection."; break}
                                else -> {
                                    val seq=row.optLong("seq")
                                    if(seq!=after+1) throw IOException("Lucid event sequence changed")
                                    after=seq
                                    if(ready) voiceLink.event(row)
                                    when(row.optString("type")) {
                                        "permission" -> if(ready) permissions[row.optString("permissionId")]=row
                                        "permissionResolved" -> if(ready) permissions.remove(row.optString("permissionId"))
                                        "user" -> if(ready) busy=true
                                        "done" -> if(ready) busy=false
                                    }
                                    val follow=ready && !list.isScrollInProgress && !list.canScrollForward
                                    val previous=history.lastOrNull()
                                    if(row.optString("type")=="text" && previous?.optString("type")=="text" &&
                                        previous.optString("text").length + row.optString("text").length <= 48000) {
                                        history[history.lastIndex]=JSONObject().put("type","text").put("seq",previous.optLong("seq"))
                                            .put("text",previous.optString("text")+row.optString("text"))
                                    } else history.add(row)
                                    if(history.size>250) history.removeAt(0)
                                    while(history.count {it.optString("type")=="image"}>8) history.removeAt(history.indexOfFirst {it.optString("type")=="image"})
                                    if(follow) followLatest()
                                }
                            }
                        }
                        if(status==null) {
                            status=if(pendingPrompt!=null || pendingDecision!=null)
                                "Disconnected before confirmation. Reconnect and check the conversation before resending."
                            else "Disconnected. Reconnect to recover the conversation."
                        }
                    } finally {
                        ready=false
                        ask=null
                        voiceLink.unbind()
                        handshakeTimer.cancel()
                        live.close()
                        writer.cancelAndJoin()
                    }
                }
            } catch(error:Exception) {
                if(error is kotlinx.coroutines.CancellationException) throw error
                status=if(pendingPrompt!=null || pendingDecision!=null)
                    "Disconnected before confirmation. Reconnect and check the conversation before resending."
                else "Couldn’t reach Lucid over SSH. Check that Lucid is running on the host."
            } finally {
                voiceLink.unbind()
                tunnel?.close()
                closeTunnel=null
                queue.close()
                ready=false
                pendingDecision=null
                pendingPrompt=null
                job=null
                connection=null
            }
        }
    }

    fun send(op:String,id:String,payload:JSONObject):Boolean =
        ready && connection?.trySend(payload.put("op",op).put("id",id).put("instance",instance))?.isSuccess==true

    fun submitMessage(text:String):Boolean {
        if(busy || text.isBlank() || pendingPrompt!=null || pendingDecision!=null || permissions.isNotEmpty()) return false
        val request=UUID.randomUUID().toString()
        if(!send("prompt",request,JSONObject().put("text",text))) return false
        pendingPrompt=request
        body=""
        return true
    }

    LaunchedEffect(Unit) {connect()}
    LaunchedEffect(ready) {if(ready) followLatest()}
    val headerHeight=agentSessionControlSize()+12.dp
    val fadeHeight=headerHeight+16.dp
    Box(Modifier.fillMaxSize().then(if(overlay) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier)
        .safeDrawingPadding().imePadding()) {
        Column(Modifier.fillMaxSize().padding(start=12.dp,end=12.dp,bottom=12.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(!ready) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(top=headerHeight),contentAlignment=Alignment.Center) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(job!=null || status==null) {
                            CircularProgressIndicator()
                            Text("Connecting to Lucid…",Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                        } else {
                            Text(status.orEmpty(),Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                            OutlinedButton(onClick=::connect) {Text("Retry connection")}
                        }
                    }
                }
            } else {
            LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth(),
                contentPadding=PaddingValues(top=fadeHeight+4.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(history,key={it.optLong("seq")}) { row ->
                    when(row.optString("type")) {
                        "user","text" -> Surface(shape=PocketPanelShape,color=if(row.optString("type")=="user") PocketInk.copy(alpha=.12f) else PocketBlack) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(if(row.optString("type")=="user") "You" else "Agent",style=MaterialTheme.typography.labelLarge)
                                SelectionContainer {Text(lucidSafeDisplay(row.optString("text"),48000))}
                            }
                        }
                        "progress" -> Text(lucidSafeDisplay(listOf(row.optString("title"),row.optString("status")).filter(String::isNotBlank).joinToString(" · ")),
                            style=MaterialTheme.typography.bodyMedium)
                        "plan" -> row.events("entries").forEach {Text(lucidSafeDisplay(it.optString("text")+" · "+it.optString("status")))}
                        "permission" -> if(row.optString("permissionId") !in permissions) {
                            val id=row.optString("permissionId")
                            val result=history.lastOrNull {it.optString("type")=="permissionResolved" && it.optString("permissionId")==id}
                            val chosen=row.events("options").firstOrNull {it.optString("id")==result?.optString("optionId")}
                            val outcome=when {
                                result?.optBoolean("cancelled")==true || chosen?.optString("kind")=="reject_once" -> "Declined"
                                chosen?.optString("kind")=="allow_once" -> "Allowed once"
                                else -> "Approval closed"
                            }
                            Text("$outcome · "+lucidSafeDisplay(row.optString("title"),500),style=MaterialTheme.typography.bodyMedium)
                        }
                        "image" -> LucidInlineImage(row)
                        "done" -> if(row.optString("reason")=="agent_error") Text("The ACP agent couldn’t complete the turn.")
                    }
                }
                item(key="conversation-end") {Spacer(Modifier.height(1.dp))}
            }
            notice?.let {Text(it,Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
            // Pending decisions remain reachable even if the user scrolls or the transcript is replayed.
            val permission=permissions.values.firstOrNull()
            if(ready && permission!=null) {
                val id=permission.optString("permissionId")
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text("Approval needed · "+lucidSafeDisplay(permission.optString("title"),500),
                        Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled=pendingDecision==null,onClick={ask=permission}){Text("Review")}
                        TextButton(enabled=pendingDecision==null,onClick={
                            val request=UUID.randomUUID().toString()
                            if(send("permission",request,JSONObject().put("permissionId",id).put("cancel",true))) pendingDecision=request to id
                        }) {Text("Decline")}
                    }
                }
            }
            val inCall=voice.phase.showsSpeakerSlot
            val showCall=body.isBlank() && (voice.canStartFromDock || inCall)
            if(inCall && !showCall && !overlay) LiveVoiceDockControl(voice,inputEnabled=true)
            val trailing:@Composable ()->Unit = {
                if(showCall && !overlay) LiveVoiceDockControl(voice,inputEnabled=true)
                else LucidSendButton(enabled=body.isNotBlank() && !busy && pendingPrompt==null &&
                    pendingDecision==null && permissions.isEmpty() && !overlay,onSend={submitMessage(body)})
            }
            if(!inCall && !overlay) TerminalDictationControls(
                enabled=true,terminalIdentity={connection.takeIf {ready && !voice.phase.showsSpeakerSlot}},
                send={id, transcript, autoSend, allowed, completed ->
                    val draft=lucidDictationDraft(body,transcript)
                    if(!allowed() || !ready || id!==connection || draft==null) false
                    else {
                        if(!autoSend || !submitMessage(draft)) body=draft
                        completed(true)
                        true
                    }
                },
                pressEnter=dictationAutoSend,onExclusiveDock={},chat=true,
            ) {mic -> LucidMessageCapsule(body,{body=it.take(12000)},busy,mic,trailing)}
            else LucidMessageCapsule(body,{body=it.take(12000)},busy,
                {if(inCall && !overlay) LiveVoiceSpeakerButton(voice,inputEnabled=true)},trailing)
            }
        }
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(fadeHeight)
            .background(Brush.verticalGradient(0f to PocketBlack.copy(alpha=.97f),.45f to PocketBlack.copy(alpha=.76f),
                .78f to PocketBlack.copy(alpha=.25f),1f to PocketBlack.copy(alpha=0f))))
        AgentSessionHeader(title,"Back to agents",onBack,onDetails,
            modifier=Modifier.align(Alignment.TopStart).padding(horizontal=12.dp,vertical=6.dp))
    }
    if(ready && !overlay) ask?.let { permission ->
        val id=permission.optString("permissionId")
        AlertDialog(onDismissRequest={ask=null},title={Text("Review tool approval")},
            text={Column(Modifier.heightIn(max=280.dp).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(lucidSafeDisplay(permission.optString("title"),500))
                permission.events("options").take(8).forEach { option ->
                    val permit=option.optString("kind")=="allow_once"
                    TextButton(enabled=ready && id in permissions && pendingDecision==null,onClick={
                        val request=UUID.randomUUID().toString()
                        if(send("permission",request,JSONObject().put("permissionId",id).put("optionId",option.optString("id")))) pendingDecision=request to id
                        ask=null
                    }) {Text((if(permit) "Allow once: " else "Reject: ")+lucidSafeDisplay(option.optString("name"),160))}
                }
            }},
            confirmButton={TextButton(onClick={ask=null}){Text("Close")}})
    }
}

internal fun lucidDictationDraft(current:String,transcript:String):String? {
    val speech=transcript.trim().takeIf(String::isNotBlank) ?: return null
    if(current.isBlank()) return speech.take(12000)
    val suffix=if(current.last().isWhitespace()) speech else " "+speech
    return (current+suffix).take(12000).takeIf {it.length>current.length}
}

@Composable private fun LucidMessageCapsule(value:String,onChange:(String)->Unit,busy:Boolean,
    mic:@Composable ()->Unit,trailing:@Composable ()->Unit,
) {
    var focused by remember {mutableStateOf(false)}
    val ink=MaterialTheme.typography.bodyLarge.copy(color=PocketInk)
    val label="Message or answer"
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(28.dp),color=PocketBlack,contentColor=PocketInk,
        border=BorderStroke(if(focused) 2.dp else 1.dp,PocketOutline)) {
        Row(Modifier.heightIn(min=56.dp).padding(start=16.dp,end=4.dp,top=4.dp,bottom=4.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            BasicTextField(value,onChange,maxLines=4,textStyle=ink,cursorBrush=SolidColor(PocketInk),
                modifier=Modifier.weight(1f).padding(vertical=4.dp).onFocusChanged {focused=it.isFocused}
                    .semantics {contentDescription=label},
                decorationBox={ input ->
                    Box {
                        if(value.isEmpty()) Text(if(busy) "Agent working…" else label,style=ink.copy(color=PocketInk.copy(alpha=.7f)))
                        input()
                    }
                })
            mic()
            trailing()
        }
    }
}

@Composable private fun LucidSendButton(enabled:Boolean,onSend:()->Unit) {
    PocketIconControl("Send message",onSend,48.dp,enabled=enabled) { color ->
        Canvas(Modifier.size(24.dp)) {
            val stroke=PocketGlyphStroke.toPx()
            val top=Offset(size.width*.5f,size.height*.22f)
            drawLine(color,Offset(size.width*.5f,size.height*.78f),top,stroke,StrokeCap.Round)
            drawLine(color,top,Offset(size.width*.23f,size.height*.49f),stroke,StrokeCap.Round)
            drawLine(color,top,Offset(size.width*.77f,size.height*.49f),stroke,StrokeCap.Round)
        }
    }
}

@Composable
private fun LucidInlineImage(row:JSONObject) {
    val payload=row.optString("data")
    val bitmap by produceState<android.graphics.Bitmap?>(null,payload) {
        value=withContext(Dispatchers.Default) {
            if(payload.length !in 1..384000 || row.optString("mimeType") !in setOf("image/png","image/jpeg","image/webp")) return@withContext null
            runCatching {
                val bytes=Base64.getDecoder().decode(payload)
                val opts=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
                if(opts.outWidth !in 1..2048 || opts.outHeight !in 1..2048 ||
                    opts.outMimeType !in setOf("image/png","image/jpeg","image/webp")) return@runCatching null
                BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            }.getOrNull()
        }
    }
    bitmap?.let {Image(it.asImageBitmap(),"Image from the agent",Modifier.fillMaxWidth().heightIn(max=260.dp))}
        ?: Text("Agent image",style=MaterialTheme.typography.bodyMedium)
}
