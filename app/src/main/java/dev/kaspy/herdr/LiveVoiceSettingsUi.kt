package dev.kaspy.herdr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun LiveVoiceRelaySettings(voice:PocketLiveVoice) {
    var endpoint by remember(voice.endpoint) {mutableStateOf(voice.endpoint.orEmpty())}
    Text("Optional GPT-Live preview for one Herdr agent. Use a trusted secure relay URL. The relay keeps the OpenAI key; Pocket never stores it.")
    OutlinedTextField(value=endpoint,onValueChange={endpoint=it.take(2048)},singleLine=true,
        label={Text("Relay WebSocket URL")},modifier=Modifier.fillMaxWidth(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
    TextButton(onClick={voice.configureEndpoint(endpoint.trim())},
        enabled=LiveVoiceProtocol.validEndpoint(endpoint.trim()) && endpoint.trim()!=voice.endpoint) {Text("Save relay")}
    if(voice.endpoint!=null) TextButton(onClick={voice.configureEndpoint(null)}) {Text("Remove relay")}
}

@Composable
internal fun LiveVoiceDialogs(voice:PocketLiveVoice) {
    voice.ticketAnchor?.let {anchor ->
        var ticket by remember(anchor) {mutableStateOf("")}
        PocketDialog(onDismissRequest=voice::cancelTicket,title={Text("Start Live Voice?")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Microphone audio goes through your relay to GPT-Live. Pocket asks before sending a prompt or sharing terminal text.")
                    Text("Relay: "+(voice.endpoint.orEmpty()))
                    OutlinedTextField(value=ticket,onValueChange={ticket=it.take(160)},
                        label={Text("One-time relay ticket")},singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                        visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                }
            },
            confirmButton={TextButton(onClick={val submitted=ticket.trim();ticket="";voice.start(submitted)},
                enabled=LiveVoiceProtocol.validTicket(ticket.trim())) {Text("Start call")}},
            dismissButton={TextButton(onClick=voice::cancelTicket){Text("Cancel")}})
    }
    voice.review?.let {review ->
        val prompt=review.kind==PocketLiveReview.Kind.PROMPT
        var text by remember(review) {mutableStateOf(review.draft)}
        val content=text.trim()
        val valid=if(prompt) LiveAgentHerdr.validPrompt(content) else LiveAgentHerdr.validShare(content)
        val dismiss={voice.resolveReview(review,null)}
        PocketDialog(onDismissRequest=dismiss,title={Text(if(prompt) "Send to this agent?" else "Share agent result?")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(if(prompt) "Review and edit the voice transcript before Pocket submits it to the agent."
                        else if(review.draft.isBlank()) "Herdr reported completion, but Pocket could not isolate a new reply. You can type a short summary or skip sharing."
                        else "Review this excerpt. Remove anything private before sending it to GPT-Live to speak.")
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
        PocketDialog(onDismissRequest=voice::dismissNotice,title={Text("Live Voice")},
            text={Text(notice)},confirmButton={TextButton(onClick=voice::dismissNotice){Text("OK")}})
    }
}
