package dev.kaspy.herdr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal object LiveExtractionClient {
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
            .put("max_tokens",480).put("response_format",JSONObject().put("type","json_object"))
            .put("messages",JSONArray()
                .put(JSONObject().put("role","system").put("content",instructions))
                .put(JSONObject().put("role","user").put("content",input.toString())))
    }

    fun result(raw:String):JSONObject? = runCatching {
        val choices=JSONObject(raw).getJSONArray("choices")
        if(choices.length()!=1) return null
        val choice=choices.getJSONObject(0)
        if(choice.opt("finish_reason")!="stop") return null
        val msg=choice.getJSONObject("message")
        if(msg.has("tool_calls") && !msg.isNull("tool_calls") ||
            msg.has("refusal") && !msg.isNull("refusal")) return null
        val content=msg.opt("content") as? String ?: return null
        if(content.length>4096) return null
        JSONObject(content)
    }.getOrNull()

    fun create(url:String,model:String,bearer:String?):LiveTurnExtraction {
        val target=requireNotNull(endpoint(url))
        require(validModel(model) && (bearer==null || validKey(bearer)))
        return LiveTurnExtraction { instructions,input ->
            withContext(Dispatchers.IO) {
                try {
                    val connection=target.toURL().openConnection() as HttpsURLConnection
                    try {
                        connection.instanceFollowRedirects=false
                        connection.connectTimeout=8_000
                        connection.readTimeout=20_000
                        connection.requestMethod="POST"
                        connection.doOutput=true
                        connection.useCaches=false
                        connection.setRequestProperty("Content-Type","application/json")
                        bearer?.let {connection.setRequestProperty("Authorization","Bearer $it")}
                        val payload=body(model,instructions,input).toString().toByteArray(Charsets.UTF_8)
                        connection.setFixedLengthStreamingMode(payload.size)
                        try {connection.outputStream.use {it.write(payload)}} finally {payload.fill(0)}
                        if(connection.responseCode!=200) return@withContext null
                        val response=connection.inputStream.use {it.readNBytes(32_769)}
                        if(response.size>32_768) null else result(response.toString(Charsets.UTF_8))
                    } finally {connection.disconnect()}
                } catch(_:java.io.IOException) {null}
            }
        }
    }
}
