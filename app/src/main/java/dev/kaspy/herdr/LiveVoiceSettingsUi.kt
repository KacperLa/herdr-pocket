package dev.kaspy.herdr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun LiveVoiceServiceSettings(voice:PocketLiveVoice) {
    var profile by remember(voice.profile) {mutableStateOf(voice.profile)}
    var endpoint by remember(voice.endpoint) {mutableStateOf(voice.endpoint.orEmpty())}
    var key by remember(voice.endpoint,voice.profile) {mutableStateOf("")}
    var extractor by remember(voice.extractorUrl) {mutableStateOf(voice.extractorUrl)}
    var model by remember(voice.extractorModel) {mutableStateOf(voice.extractorModel)}
    var extractorKey by remember(voice.extractorUrl) {mutableStateOf("")}
    var disableThinking by remember(voice.disableExtractionThinking) {mutableStateOf(voice.disableExtractionThinking)}
    Text("Choose a trusted Live service. For an OpenAI-backed service, keep the OpenAI project key on your proxy and use its client key here.")
    for (option in LiveVoiceProfile.entries) {
        TextButton(onClick={profile=option;key=""},enabled=profile!=option) {Text(when(option) {
            LiveVoiceProfile.GPT_LIVE -> "GPT Live compatible"
            LiveVoiceProfile.FOSS_LIVE -> "FOSS-Live"
            LiveVoiceProfile.RELAY_TICKET -> "One-time relay ticket"
        })}
    }
    OutlinedTextField(value=endpoint,onValueChange={endpoint=it.take(2048)},singleLine=true,
        label={Text("Live secure URL")},modifier=Modifier.fillMaxWidth(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
    if(profile!=LiveVoiceProfile.RELAY_TICKET) OutlinedTextField(value=key,onValueChange={key=it.take(2048)},
        label={Text("Live API key")},singleLine=true,visualTransformation=PasswordVisualTransformation(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
    Text("Codex uses stored replies over SSH, without an extraction model. For other agents, the optional service below receives visible terminal text. Only text you approve is shared with Live Voice.")
    OutlinedTextField(value=extractor,onValueChange={extractor=it.take(2048)},singleLine=true,
        label={Text("Extraction URL (full chat completions endpoint)")},modifier=Modifier.fillMaxWidth(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
    OutlinedTextField(value=model,onValueChange={model=it.take(160)},singleLine=true,
        label={Text("Extraction model")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(value=extractorKey,onValueChange={extractorKey=it.take(2048)},singleLine=true,
        label={Text("Extraction API key (if required)")},visualTransformation=PasswordVisualTransformation(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
        .toggleable(disableThinking,role=Role.Checkbox,onValueChange={disableThinking=it}),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(checked=disableThinking,onCheckedChange=null)
        Column {
            Text("Disable thinking for extraction")
            Text("For llama.cpp-compatible servers. Affects only Pocket’s extraction requests.")
        }
    }
    if(voice.settingsSaved) Text("Live Voice settings saved. Blank key fields keep the saved keys when their URLs are unchanged.")
    TextButton(onClick={
        voice.configure(profile,endpoint.trim(),key.trim(),extractor.trim(),model.trim(),extractorKey.trim(),disableThinking)
        key="";extractorKey=""
    },enabled=LiveVoiceProtocol.normalizeEndpoint(endpoint.trim())!=null &&
        PocketLiveVoice.extractionSettingsValid(extractor.trim(),model.trim()) &&
        (extractor.isNotBlank() || extractorKey.isBlank())) {Text("Save Live Voice settings")}
    val extractionMatchesSaved=extractor.trim()==voice.extractorUrl && model.trim()==voice.extractorModel &&
        extractorKey.isEmpty() && voice.extractorUrl.isNotEmpty() && disableThinking==voice.disableExtractionThinking
    TextButton(onClick=voice::testSavedExtraction,enabled=extractionMatchesSaved && !voice.extractionChecking) {
        Text(if(voice.extractionChecking) "Testing extraction…" else "Test saved extraction")
    }
    voice.extractionCheckResult?.let {
        Text(it,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
    }
    var diagnosticReport by remember {mutableStateOf<String?>(null)}
    TextButton(onClick={diagnosticReport=voice.diagnosticText()}) {Text("View diagnostics")}
    diagnosticReport?.let { report ->
        val context=LocalContext.current
        PocketDialog(onDismissRequest={diagnosticReport=null},title={Text("Live Voice diagnostics")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Recent events from this app session. No API keys, server URLs, terminal text, or transcripts.")
                    Text(report.ifEmpty {"No diagnostic events yet. Run the extraction test or try a call first."},
                        modifier=Modifier.heightIn(max=280.dp).verticalScroll(rememberScrollState()),fontFamily=FontFamily.Monospace)
                }
            },
            confirmButton={TextButton(enabled=report.isNotEmpty(),onClick={copyLiveDiagnostics(context,report)}) {
                Text("Copy diagnostics")
            }},
            dismissButton={TextButton(onClick={diagnosticReport=null}) {Text("Close")}})
    }
    if(voice.endpoint!=null) TextButton(onClick={voice.clearConfiguration()}) {Text("Remove Live Voice settings and keys")}
}

@Composable
internal fun LiveVoiceDialogs(voice:PocketLiveVoice) {
    voice.ticketAnchor?.let {anchor ->
        var ticket by remember(anchor) {mutableStateOf("")}
        var bottomLines by remember(anchor) {mutableStateOf(voice.savedIgnoreBottomLines(anchor).toString())}
        val bottom=bottomLines.toIntOrNull()?.takeIf {it in 0..LiveExtractionWindow.MAX_IGNORED_LINES}
        PocketDialog(onDismissRequest=voice::cancelTicket,title={Text("Start Live Voice?")},
            text={
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Microphone audio goes to your Live endpoint. Codex uses stored replies over SSH. Other agents use the optional extraction service, which receives visible terminal snapshots. Pocket asks before submitting a prompt or sharing a reply.")
                    Text("Live: "+(voice.endpoint.orEmpty()))
                    if(voice.extractorUrl.isNotEmpty()) Text("Optional extractor: "+voice.extractorUrl)
                    if(voice.extractorUrl.isNotEmpty()) OutlinedTextField(value=bottomLines,onValueChange={ value ->
                        if(value.length<=2 && value.all {it in '0'..'9'}) bottomLines=value
                    },label={Text("Ignore bottom lines")},singleLine=true,isError=bottomLines.isNotEmpty() && bottom==null,
                        supportingText={Text("0–${LiveExtractionWindow.MAX_IGNORED_LINES}. Only applies to the optional extractor for non-Codex panes.")},
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                    if(voice.profile==LiveVoiceProfile.RELAY_TICKET) OutlinedTextField(value=ticket,onValueChange={ticket=it.take(160)},
                        label={Text("One-time relay ticket")},singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                        visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                }
            },
            confirmButton={TextButton(onClick={val submitted=ticket.trim();ticket="";bottom?.let {voice.start(submitted,it)}},
                enabled=bottom!=null && (voice.profile!=LiveVoiceProfile.RELAY_TICKET || LiveVoiceProtocol.validTicket(ticket.trim()))) {Text("Start call")}},
            dismissButton={TextButton(onClick=voice::cancelTicket){Text("Cancel")}})
    }
    voice.review?.let {review ->
        val prompt=review.kind==PocketLiveReview.Kind.PROMPT
        val question=review.kind==PocketLiveReview.Kind.QUESTION
        var text by remember(review) {mutableStateOf(review.draft)}
        val content=text.trim()
        val valid=if(prompt) LiveAgentHerdr.validPrompt(content) else LiveAgentHerdr.validShare(content)
        val dismiss={voice.resolveReview(review,null)}
        PocketDialog(onDismissRequest=dismiss,title={Text(if(prompt) "Send to this agent?" else if(question) "Read the agent’s question aloud?" else "Share agent result?")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(if(prompt) "Review and edit the draft before Pocket submits it to the agent."
                        else if(question) "This only reads the question aloud. Answer or approve it in the terminal."
                        else if(review.draft.isBlank()) "Herdr reported completion, but Pocket could not isolate a new reply. You can type a short summary or skip sharing."
                        else "Review this draft and remove anything private before sharing it with Live Voice.")
                    review.evidence?.let { if(!question) Text("Seen in terminal: $it") }
                    OutlinedTextField(value=text,onValueChange={text=it.take(4000)},
                        label={Text(if(prompt) "Agent prompt" else "Text to share")},
                        minLines=2,maxLines=5,modifier=Modifier.fillMaxWidth())
                    if(!prompt && content.toByteArray(Charsets.UTF_8).size>350) Text("Shorten the excerpt before sharing.")
                    if(prompt && content.isNotEmpty() && !valid) Text("Use one line without terminal control keys.")
                }
            },
            confirmButton={TextButton(enabled=valid,onClick={voice.resolveReview(review,content)}) {
                Text(if(prompt) "Send to agent" else "Share with GPT-Live")
            }},
            dismissButton={TextButton(onClick=dismiss){Text("Skip")}})
    }
    voice.notice?.let { notice ->
        val context=LocalContext.current
        PocketDialog(onDismissRequest=voice::dismissNotice,title={Text("Live Voice")},
            text={Text(notice)},confirmButton={TextButton(onClick=voice::dismissNotice){Text("OK")}},
            dismissButton={
                if(voice.diagnosticText().isNotEmpty()) TextButton(onClick={copyLiveDiagnostics(context,voice.diagnosticText())}) {
                    Text("Copy diagnostics")
                }
            })
    }
}

private fun copyLiveDiagnostics(context:Context,report:String) {
    if(report.isEmpty()) return
    val copied=runCatching {
        val clipboard=checkNotNull(context.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("Pocket Live Voice diagnostics",report))
    }.isSuccess
    if(!copied) Toast.makeText(context,"Could not copy diagnostics.",Toast.LENGTH_SHORT).show()
}
