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
        OUTPUT_LIMIT("The extraction model hit its output limit before finishing. Try a non-thinking extraction model or disable thinking for this model on your server."),
        REASONING_ONLY("The extraction model returned reasoning but no final JSON. Try a non-thinking extraction model or disable thinking on your server."),
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

    suspend fun check(url:String,model:String,bearer:String?):Problem? {
        val extractor=create(url,model,bearer)
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

    fun body(model:String,instructions:String,input:JSONObject):JSONObject {
        require(validModel(model))
        return JSONObject().put("model",model).put("stream",false).put("temperature",0)
            .put("max_tokens",1536).put("response_format",JSONObject().put("type","json_object"))
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

    fun create(url:String,model:String,bearer:String?)=LiveTurnExtraction(transport(url,model,bearer))

    private fun transport(url:String,model:String,bearer:String?):suspend (String,JSONObject)->JSONObject {
        val target=requireNotNull(endpoint(url))
        require(validModel(model) && (bearer==null || validKey(bearer)))
        return { instructions,input ->
            withContext(Dispatchers.IO) {
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
                        val payload=body(model,instructions,input).toString().toByteArray(Charsets.UTF_8)
                        connection.setFixedLengthStreamingMode(payload.size)
                        try {connection.outputStream.use {it.write(payload)}} finally {payload.fill(0)}
                        problem(connection.responseCode)?.let {throw Failure(it)}
                        val response=connection.inputStream.use {it.readNBytes(32_769)}
                        try {
                            if(response.size>32_768) throw Failure(Problem.INVALID_REPLY)
                            decode(response.toString(Charsets.UTF_8))
                        } finally {response.fill(0)}
                    } finally {connection.disconnect()}
                } catch(_:java.net.SocketTimeoutException) {throw Failure(Problem.TIMEOUT)}
                catch(_:java.io.IOException) {throw Failure(Problem.CONNECTION)}
            }
        }
    }
}
