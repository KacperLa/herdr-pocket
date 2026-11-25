package dev.kaspy.herdr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal object LiveExtractionClient {
    enum class Problem(val explanation:String) {
        AUTH("The extraction service rejected its API key."),
        ROUTE("The extraction URL or model was not found. Check the full Chat Completions URL and model name."),
        CONTRACT("The extraction service rejected the request. Check the model name and Chat Completions JSON mode support."),
        UNAVAILABLE("The extraction service is unavailable or rate limited. Try again when it is ready."),
        INVALID_REPLY("The extraction model did not return the expected JSON response."),
        OUTPUT_LIMIT("The extraction model hit its output limit before finishing. In Pocket, try Disable thinking for extraction. The gateway and model must honor the request."),
        REASONING_ONLY("The extraction model returned reasoning but no final JSON. In Pocket, try Disable thinking for extraction. The gateway and model must honor the request."),
        TIMEOUT("The extraction service timed out."),
        CONNECTION("Pocket could not reach the extraction service over HTTPS."),
    }

    class Failure(val problem:Problem):Exception()

    fun problem(status:Int):Problem? = when(status) {
        200 -> null
        401,403 -> Problem.AUTH
        404,405 -> Problem.ROUTE
        429,in 500..599 -> Problem.UNAVAILABLE
        in 300..399 -> Problem.ROUTE
        else -> Problem.CONTRACT
    }

    suspend fun check(
        url:String,model:String,bearer:String?,disableThinking:Boolean=false,diagnostics:LiveDiagnostics?=null,
    ):Problem? {
        val extractor=LiveTurnExtraction(transport(url,model,bearer,disableThinking,diagnostics,LiveDiagnostics.Origin.SETTINGS_TEST))
        val idle="Codex · idle\n\n› Ask Codex to make a change"
        val observation=extractor.inspect("",idle,"",false)
        return if(observation.kind==LiveTurnExtraction.Kind.NONE) null
            else observation.problem ?: Problem.INVALID_REPLY
    }

    fun endpoint(value:String):URI? = runCatching {
        require(value.length in 1..2048 && value.all {it.code in 33..126})
        URI(value).also {
            require(it.scheme=="https" && it.host!=null && it.rawUserInfo==null &&
                it.rawQuery==null && it.rawFragment==null && it.rawPath.startsWith("/") &&
                (it.port==-1 || it.port in 1..65535))
        }
    }.getOrNull()

    fun validModel(value:String)=value.length in 1..160 && value.all {it.code in 33..126}
    fun validKey(value:String)=value.length in 1..2048 && value.all {it.code in 33..126}

    fun body(model:String,instructions:String,input:JSONObject,disableThinking:Boolean=false):JSONObject {
        require(validModel(model))
        return JSONObject().put("model",model).put("stream",false).put("temperature",0)
            .put("max_tokens",1536).put("response_format",JSONObject().put("type","json_object"))
            .apply {
                if(disableThinking) {
                    // llama.cpp supports a per-request off setting. The template flag
                    // also covers compatible versions that use this legacy option.
                    put("reasoning_effort","none")
                    put("chat_template_kwargs",JSONObject().put("enable_thinking",false))
                }
            }
            .put("messages",JSONArray()
                .put(JSONObject().put("role","system").put("content",instructions))
                .put(JSONObject().put("role","user").put("content",input.toString())))
    }

    fun result(raw:String):JSONObject? = runCatching { decode(raw) }.getOrNull()

    internal fun decode(raw:String):JSONObject {
        try {
            val choices=JSONObject(raw).getJSONArray("choices")
            if(choices.length()!=1) throw Failure(Problem.INVALID_REPLY)
            val choice=choices.getJSONObject(0)
            if(choice.opt("finish_reason")=="length") throw Failure(Problem.OUTPUT_LIMIT)
            if(choice.opt("finish_reason")!="stop") throw Failure(Problem.INVALID_REPLY)
            val msg=choice.getJSONObject("message")
            if(msg.has("tool_calls") && !msg.isNull("tool_calls") ||
                msg.has("refusal") && !msg.isNull("refusal")) throw Failure(Problem.INVALID_REPLY)
            val content=(msg.opt("content") as? String).orEmpty().trim()
            if(content.isEmpty() && listOf("reasoning_content","reasoning").any {
                    (msg.opt(it) as? String)?.isNotBlank()==true
                }) throw Failure(Problem.REASONING_ONLY)
            if(content.length !in 1..4096) throw Failure(Problem.INVALID_REPLY)
            // Accept one complete JSON object, optionally fenced. Never search arbitrary text
            // or reasoning for a readiness decision.
            val clean=FENCED.matchEntire(content)?.groupValues?.get(1)?.trim() ?: content
            val tokens=JSONTokener(clean)
            val obj=tokens.nextValue()
            if(obj !is JSONObject || tokens.nextClean()!='\u0000') throw Failure(Problem.INVALID_REPLY)
            return obj
        } catch(failure:Failure) {throw failure}
        catch(_:org.json.JSONException) {throw Failure(Problem.INVALID_REPLY)}
    }

    private val FENCED=Regex("\\x60{3}(?:json)?[ \t]*\r?\n(.*?)\r?\n\\x60{3}",
        setOf(RegexOption.DOT_MATCHES_ALL,RegexOption.IGNORE_CASE))

    fun create(
        url:String,model:String,bearer:String?,disableThinking:Boolean=false,diagnostics:LiveDiagnostics?=null,
    )=LiveTurnExtraction(transport(url,model,bearer,disableThinking,diagnostics,LiveDiagnostics.Origin.LIVE_CALL))

    private fun transport(
        url:String,model:String,bearer:String?,disableThinking:Boolean,diagnostics:LiveDiagnostics?,
        origin:LiveDiagnostics.Origin,
    ):suspend (String,JSONObject)->JSONObject {
        val target=requireNotNull(endpoint(url))
        require(validModel(model) && (bearer==null || validKey(bearer)))
        return { instructions,input ->
            withContext(Dispatchers.IO) {
                val start=System.nanoTime()
                var status:Int?=null
                var size:Int?=null
                var reply:LiveDiagnostics.ReplyStats?=null
                var failure:Problem?=null
                try {
                    val connection=target.toURL().openConnection() as HttpsURLConnection
                    try {
                        connection.instanceFollowRedirects=false
                        connection.connectTimeout=8_000
                        connection.readTimeout=30_000
                        connection.requestMethod="POST"
                        connection.doOutput=true
                        connection.useCaches=false
                        connection.setRequestProperty("Content-Type","application/json")
                        bearer?.let {connection.setRequestProperty("Authorization","Bearer $it")}
                        val payload=body(model,instructions,input,disableThinking).toString().toByteArray(Charsets.UTF_8)
                        connection.setFixedLengthStreamingMode(payload.size)
                        try {connection.outputStream.use {it.write(payload)}} finally {payload.fill(0)}
                        val httpCode=connection.responseCode
                        status=httpCode
                        problem(httpCode)?.let {throw Failure(it)}
                        val response=connection.inputStream.use {it.readNBytes(32_769)}
                        try {
                            size=response.size
                            if(response.size>32_768) throw Failure(Problem.INVALID_REPLY)
                            val raw=response.toString(Charsets.UTF_8)
                            if(diagnostics!=null) reply=LiveDiagnostics.summarize(raw)
                            decode(raw)
                        } finally {response.fill(0)}
                    } finally {connection.disconnect()}
                } catch(e:Failure) {
                    failure=e.problem
                    throw e
                } catch(_:java.net.SocketTimeoutException) {
                    failure=Problem.TIMEOUT
                    throw Failure(Problem.TIMEOUT)
                } catch(_:java.io.IOException) {
                    failure=Problem.CONNECTION
                    throw Failure(Problem.CONNECTION)
                } finally {
                    diagnostics?.extraction(origin,LiveTurnExtraction.diagnosticStage(instructions),
                        disableThinking,status,size,(System.nanoTime()-start)/1_000_000,reply,failure)
                }
            }
        }
    }
}
