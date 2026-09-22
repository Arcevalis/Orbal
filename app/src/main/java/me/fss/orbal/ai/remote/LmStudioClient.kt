package me.fss.orbal.ai.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class LmStudioClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // infinite for streaming
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        // Remove trailing slashes
        url = url.trimEnd('/')
        // Ensure we keep /v1 suffix – if user entered just host:port, append /v1
        if (!url.endsWith("/v1")) {
            // If they typed .../v1/chat/completions we strip to /v1
            if (url.contains("/chat/completions")) {
                url = url.substringBefore("/chat/completions").trimEnd('/')
            }
            if (!url.endsWith("/v1")) {
                // Only append if it looks like host (contains :1234 or /)
                // But safe to append /v1 if missing
                url += "/v1"
            }
        }
        return url
    }

    fun getNormalizedBaseUrl(raw: String): String = normalizeBaseUrl(raw)

    suspend fun fetchModels(baseUrl: String, apiKey: String? = null): Result<List<OpenAiModel>> = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        if (normalized.isBlank()) return@withContext Result.failure(IllegalArgumentException("Base URL is empty"))
        val url = "$normalized/models"
        val builder = Request.Builder().url(url).get()
        if (!apiKey.isNullOrBlank()) builder.header("Authorization", "Bearer $apiKey")
        val request = builder.build()
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}: $body"))
                }
                val parsed = json.decodeFromString<OpenAiModelsResponse>(body)
                Result.success(parsed.data)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Returns the IDs of *loaded* models. Tries LM Studio v0 first (state == loaded),
     * then falls back to OpenAI /v1/models (all models treated as loaded for generic
     * OpenAI-compatible servers like Ollama, vLLM).
     */
    suspend fun fetchLoadedModelIds(baseUrl: String, apiKey: String? = null): Result<List<String>> = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        if (normalized.isBlank()) return@withContext Result.failure(IllegalArgumentException("Base URL is empty"))
        val baseNoV1 = normalized.removeSuffix("/v1").trimEnd('/')

        // Try LM Studio v0 — richest, has state == loaded
        try {
            val v0Url = "$baseNoV1/api/v0/models"
            val b = Request.Builder().url(v0Url).get()
            if (!apiKey.isNullOrBlank()) b.header("Authorization", "Bearer $apiKey")
            client.newCall(b.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        try {
                            val root = json.parseToJsonElement(body).jsonObject
                            val data = root["data"]?.jsonArray
                            if (data != null) {
                                val objs = data.mapNotNull { it as? JsonObject }
                                // Prefer those explicitly marked loaded
                                val loaded = objs.filter { obj ->
                                    val state = obj["state"]?.jsonPrimitive?.content?.lowercase()
                                    val status = obj["status"]?.jsonPrimitive?.content?.lowercase()
                                    val loadedField = obj["loaded"]?.jsonPrimitive
                                    val loadedBool = when {
                                        loadedField == null -> null
                                        loadedField.isString -> loadedField.content.toBooleanStrictOrNull()
                                        else -> loadedField.intOrNull == 1
                                    }
                                    state == "loaded" || status == "loaded" || loadedBool == true
                                }.mapNotNull { it["id"]?.jsonPrimitive?.content }

                                if (loaded.isNotEmpty()) {
                                    Log.i("LmStudioClient", "Loaded models from $v0Url: $loaded")
                                    return@withContext Result.success(loaded)
                                }
                                // v0 succeeded but no explicit loaded flag — fall back to all ids from v0
                                val allIds = objs.mapNotNull { it["id"]?.jsonPrimitive?.content }
                                if (allIds.isNotEmpty()) {
                                    Log.i("LmStudioClient", "v0 models (no loaded flag, using all): $allIds")
                                    return@withContext Result.success(allIds)
                                }
                            } else if (root.containsKey("id")) {
                                val id = root["id"]?.jsonPrimitive?.content
                                if (id != null) return@withContext Result.success(listOf(id))
                            }
                        } catch (e: Exception) {
                            Log.d("LmStudioClient", "Failed to parse $v0Url for loaded ids", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("LmStudioClient", "v0 loaded probe failed, falling back to /v1/models", e)
        }

        // Fallback to generic /v1/models — every listed model is considered loaded
        val fallback = fetchModels(baseUrl, apiKey)
        fallback.map { list -> list.map { it.id } }
    }

    suspend fun testConnection(baseUrl: String, apiKey: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val r = fetchModels(baseUrl, apiKey)
        r.map { list ->
            if (list.isEmpty()) "Connected — no models listed (load a model on the server)"
            else "Connected — ${list.size} model(s): ${list.joinToString { it.id }}"
        }
    }

    sealed class StreamEvent {
        data class Content(val text: String) : StreamEvent()
        data class Reasoning(val text: String) : StreamEvent()
        data class ToolCalls(val calls: List<ToolCall>) : StreamEvent()
        data class Finish(val reason: String?) : StreamEvent()
    }

    /**
     * Streaming chat completion. Mirrors InferenceEngine.generateResponse callback shape but via remote.
     * Emits partial content strings. Throws on HTTP error.
     * Caller should collect on Dispatchers.IO / Default and switch to Main for UI.
     */
    fun streamChatCompletion(
        baseUrl: String,
        apiKey: String? = null,
        model: String,
        messages: List<ChatRoleMessage>,
        temperature: Float? = null,
        topP: Float? = null,
        topK: Int? = null,
        minP: Float? = null,
        maxTokens: Int? = null,
        repeatPenalty: Float? = null,
    ): Flow<String> {
        return streamChatCompletionWithTools(baseUrl, apiKey, model, messages, tools = null, temperature = temperature, topP = topP, topK = topK, minP = minP, maxTokens = maxTokens, repeatPenalty = repeatPenalty)
            .mapNotNull { ev -> (ev as? StreamEvent.Content)?.text }
    }

    fun streamChatCompletionWithTools(
        baseUrl: String,
        apiKey: String? = null,
        model: String,
        messages: List<ChatRoleMessage>,
        tools: List<ToolSchema>? = null,
        temperature: Float? = null,
        topP: Float? = null,
        topK: Int? = null,
        minP: Float? = null,
        maxTokens: Int? = null,
        repeatPenalty: Float? = null,
    ): Flow<StreamEvent> = flow {
        val normalized = normalizeBaseUrl(baseUrl)
        require(normalized.isNotBlank()) { "Base URL is empty" }
        require(model.isNotBlank()) { "Model is empty" }
        val url = "$normalized/chat/completions"

        val reqBody = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            topP = topP,
            topK = topK,
            minP = minP,
            maxTokens = maxTokens,
            stream = true,
            repeatPenalty = repeatPenalty,
            tools = tools,
            toolChoice = if (!tools.isNullOrEmpty()) "auto" else null,
        )
        val jsonBody = json.encodeToString(reqBody)
        val mediaType = "application/json".toMediaType()
        val body = jsonBody.toRequestBody(mediaType)

        val reqBuilder = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        if (!apiKey.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")
        val request = reqBuilder.build()

        var call: Call? = null
        try {
            call = client.newCall(request)
            // Ensure cancellation propagates to OkHttp Call
            coroutineContext.ensureActive()
            val response = withContext(Dispatchers.IO) { call.execute() }

            if (!response.isSuccessful) {
                val err = withContext(Dispatchers.IO) { response.body?.string() ?: "" }
                response.close()
                throw IOException("HTTP ${response.code}: $err")
            }
            val contentType = response.header("Content-Type") ?: ""
            // Fallback: if server returned non-stream JSON (e.g. stream:false or omitted), handle it
            if (contentType.contains("application/json") && !contentType.contains("text/event-stream")) {
                val bodyStr = withContext(Dispatchers.IO) { response.body?.string() ?: "" }
                response.close()
                try {
                    val parsed = json.decodeFromString<ChatCompletionResponse>(bodyStr)
                    val msg = parsed.choices.firstOrNull()?.message
                    val text = msg?.content?.textValue()
                    val toolCalls = msg?.toolCalls
                    if (!text.isNullOrEmpty()) emit(StreamEvent.Content(text))
                    if (!toolCalls.isNullOrEmpty()) emit(StreamEvent.ToolCalls(toolCalls))
                    if (text.isNullOrEmpty() && toolCalls.isNullOrEmpty()) Log.w("LmStudioClient", "Non-stream response had no content: $bodyStr")
                    val finish = parsed.choices.firstOrNull()?.finishReason
                    if (finish != null) emit(StreamEvent.Finish(finish))
                } catch (e: Exception) {
                    Log.w("LmStudioClient", "Failed to parse non-stream response: $bodyStr", e)
                    throw IOException("Failed to parse response: ${e.message}")
                }
                return@flow
            }
            val source = response.body?.source()
                ?: throw IOException("Empty response body")

            // SSE parsing — each line "data: {...}" or "data: [DONE]"
            var emittedAny = false
            // Accumulate incremental tool_calls by index
            data class Acc(var id: String? = null, var name: String? = null, val args: StringBuilder = StringBuilder())
            val toolAcc = mutableMapOf<Int, Acc>()
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    // OkHttp source blocks; we are on flowOn(IO) so okay
                    val line = withContext(Dispatchers.IO) {
                        try { source.readUtf8Line() } catch (e: Exception) { null }
                    } ?: break

                    if (line.isEmpty()) continue
                    if (line.startsWith(":")) continue // keep-alive comment
                    if (!line.startsWith("data:")) {
                        Log.d("LmStudioClient", "Skipping non-data line: $line")
                        continue
                    }
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue

                    try {
                        val chunk = json.decodeFromString<ChatCompletionChunk>(data)
                        val delta = chunk.choices.firstOrNull()?.delta
                        val finish = chunk.choices.firstOrNull()?.finishReason
                        // Content + structured reasoning (OGAM-style: reasoning_content / reasoning / thinking / reasoning_details)
                        val content = delta?.content
                        if (!content.isNullOrEmpty()) {
                            emittedAny = true
                            emit(StreamEvent.Content(content))
                        }
                        // Structured reasoning — always emitted as Reasoning event (not gated; gate applied at display layer like OGAM)
                        // Handles LM Studio (reasoning_content), Ollama (reasoning/thinking), and reasoning_details array
                        val structuredReasoning = delta?.reasoningContent
                            ?: delta?.reasoning
                            ?: delta?.thinking
                            ?: delta?.reasoningDetails
                        if (!structuredReasoning.isNullOrEmpty()) {
                            emittedAny = true
                            emit(StreamEvent.Reasoning(structuredReasoning))
                        }
                        // Tool calls deltas
                        delta?.toolCalls?.forEach { tcDelta ->
                            val idx = tcDelta.index ?: 0
                            val acc = toolAcc.getOrPut(idx) { Acc() }
                            if (!tcDelta.id.isNullOrBlank()) acc.id = tcDelta.id
                            val fn = tcDelta.function
                            if (!fn?.name.isNullOrBlank()) acc.name = fn?.name
                            if (!fn?.arguments.isNullOrEmpty()) acc.args.append(fn?.arguments)
                            Log.v("LmStudioClient", "Tool delta idx=$idx id=${acc.id} name=${acc.name} args+=${fn?.arguments?.take(60)}")
                        }
                        if (finish != null) {
                            emit(StreamEvent.Finish(finish))
                            if (finish == "tool_calls" || toolAcc.isNotEmpty()) {
                                // Don't break immediately — wait for [DONE] to get all tool args
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("LmStudioClient", "Failed to parse chunk: $data", e)
                    }
                }
                // After stream, emit aggregated tool calls if any
                if (toolAcc.isNotEmpty()) {
                    val calls = toolAcc.entries.sortedBy { it.key }.map { (idx, acc) ->
                        ToolCall(
                            id = acc.id ?: "call_$idx",
                            type = "function",
                            function = FunctionCall(name = acc.name, arguments = acc.args.toString())
                        )
                    }.filter { it.function?.name != null }
                    if (calls.isNotEmpty()) {
                        Log.i("LmStudioClient", "Aggregated ${calls.size} tool calls: ${calls.map { it.function?.name }}")
                        emit(StreamEvent.ToolCalls(calls))
                    }
                }
                if (!emittedAny && toolAcc.isEmpty()) {
                    Log.w("LmStudioClient", "Stream completed without emitting any content or tool chunks")
                }
            } finally {
                try { source.close() } catch (_: Exception) {}
                response.close()
            }
        } catch (e: Exception) {
            // Cancel call on failure / cancellation
            try { call?.cancel() } catch (_: Exception) {}
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Try to discover the model's context window directly from the server.
     * Probes several endpoints (OpenAI /models extra fields, llama.cpp /props, Ollama /api/show)
     * and returns the first plausible Int found (>512). Empty if undeterminable.
     */
    suspend fun fetchModelContextLength(
        baseUrl: String,
        modelId: String? = null,
        apiKey: String? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        if (normalized.isBlank()) return@withContext Result.failure(IllegalArgumentException("Base URL is empty"))
        val baseNoV1 = normalized.removeSuffix("/v1").trimEnd('/')

        val candidates = listOf(
            "loaded_context_length", "max_context_length", "context_length", "context_window",
            "max_context", "n_ctx", "n_ctx_train", "max_model_len",
            "context_size", "max_tokens", "max_position_embeddings", "ctx_len",
            "num_ctx", "ctx"
        )

        fun extractInt(obj: JsonObject): Int? {
            for (k in candidates) {
                val v = obj[k]?.jsonPrimitive?.intOrNull
                if (v != null && v > 512) return v
                // nested options: e.g. {"meta": {"n_ctx": 32768}}
                // also check case-insensitive keys
            }
            // case-insensitive fallback
            for ((key, value) in obj) {
                if (candidates.any { it.equals(key, ignoreCase = true) }) {
                    val v = value.jsonPrimitive.intOrNull
                    if (v != null && v > 512) return v
                }
                // nested object one level deep
                if (value is JsonObject) {
                    val nested = extractInt(value)
                    if (nested != null) return nested
                }
            }
            return null
        }

        // 0) LM Studio /api/v0/models — richest (loaded_context_length + max_context_length)
        for (v0Url in if (!modelId.isNullOrBlank()) listOf("$baseNoV1/api/v0/models/$modelId", "$baseNoV1/api/v0/models") else listOf("$baseNoV1/api/v0/models")) {
            try {
                val b = Request.Builder().url(v0Url).get()
                if (!apiKey.isNullOrBlank()) b.header("Authorization", "Bearer $apiKey")
                client.newCall(b.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        if (body.isNotBlank()) {
                            try {
                                val root = json.parseToJsonElement(body).jsonObject
                                // Single model object vs {data:[...]}
                                val obj: JsonObject? = when {
                                    root.containsKey("data") -> {
                                        val arr = root["data"]?.jsonArray
                                        if (!modelId.isNullOrBlank()) arr?.mapNotNull { it as? JsonObject }?.firstOrNull { it["id"]?.jsonPrimitive?.content == modelId }
                                        else arr?.mapNotNull { it as? JsonObject }?.firstOrNull { it["state"]?.jsonPrimitive?.content == "loaded" }
                                        ?: arr?.firstOrNull() as? JsonObject
                                    }
                                    root.containsKey("id") -> root
                                    else -> root
                                }
                                if (obj != null) {
                                    // Prefer loaded_context_length over max_context_length
                                    val loaded = obj["loaded_context_length"]?.jsonPrimitive?.intOrNull
                                    if (loaded != null && loaded > 512) {
                                        Log.i("LmStudioClient", "Context from $v0Url (loaded): $loaded (model=$modelId)")
                                        return@withContext Result.success(loaded)
                                    }
                                    val ctx = extractInt(obj)
                                    if (ctx != null) {
                                        Log.i("LmStudioClient", "Context from $v0Url: $ctx (model=$modelId)")
                                        return@withContext Result.success(ctx)
                                    }
                                }
                            } catch (e: Exception) { Log.d("LmStudioClient", "Failed to parse $v0Url", e) }
                        }
                    }
                }
            } catch (e: Exception) { Log.d("LmStudioClient", "v0 models probe $v0Url failed", e) }
        }

        // 1) OpenAI /models — check for extra fields on the matching model
        try {
            val url = "$normalized/models"
            val b = Request.Builder().url(url).get()
            if (!apiKey.isNullOrBlank()) b.header("Authorization", "Bearer $apiKey")
            client.newCall(b.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        try {
                            val root = json.parseToJsonElement(body).jsonObject
                            val data = root["data"]?.jsonArray
                            if (data != null) {
                                val target: JsonObject? = if (!modelId.isNullOrBlank()) {
                                    data.mapNotNull { it as? JsonObject }.firstOrNull { it["id"]?.jsonPrimitive?.content == modelId }
                                } else null
                                val objs = if (target != null) listOf(target) else data.mapNotNull { it as? JsonObject }
                                for (obj in objs) {
                                    val ctx = extractInt(obj)
                                    if (ctx != null) {
                                        Log.i("LmStudioClient", "Context from /models: $ctx (model=$modelId)")
                                        return@withContext Result.success(ctx)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("LmStudioClient", "Failed to parse /models for ctx", e)
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.d("LmStudioClient", "models ctx probe failed", e) }

        // 2) llama.cpp /props (and /v1/props fallback)
        for (propsUrl in listOf("$baseNoV1/props", "$normalized/props")) {
            try {
                val b = Request.Builder().url(propsUrl).get()
                if (!apiKey.isNullOrBlank()) b.header("Authorization", "Bearer $apiKey")
                client.newCall(b.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        if (body.isNotBlank()) {
                            try {
                                val root = json.parseToJsonElement(body).jsonObject
                                val ctx = extractInt(root)
                                    ?: root["default_generation_settings"]?.let { (it as? JsonObject)?.let { o -> extractInt(o) } }
                                if (ctx != null) {
                                    Log.i("LmStudioClient", "Context from $propsUrl: $ctx")
                                    return@withContext Result.success(ctx)
                                }
                            } catch (e: Exception) { Log.d("LmStudioClient", "Failed to parse $propsUrl", e) }
                        }
                    }
                }
            } catch (e: Exception) { Log.d("LmStudioClient", "props probe $propsUrl failed", e) }
        }

        // 3) Ollama /api/show — POST {"name": modelId}
        if (!modelId.isNullOrBlank()) {
            for (showUrl in listOf("$baseNoV1/api/show", "$baseNoV1/v1/api/show")) {
                try {
                    val media = "application/json".toMediaType()
                    val reqBody = "{\"name\":\"$modelId\"}".toRequestBody(media)
                    val b = Request.Builder().url(showUrl).post(reqBody).header("Content-Type", "application/json")
                    if (!apiKey.isNullOrBlank()) b.header("Authorization", "Bearer $apiKey")
                    client.newCall(b.build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string() ?: ""
                            if (body.isNotBlank()) {
                                try {
                                    val root = json.parseToJsonElement(body).jsonObject
                                    // common Ollama paths: details.num_ctx, parameters.num_ctx, model_info.*
                                    val ctx = extractInt(root)
                                        ?: root["details"]?.let { (it as? JsonObject)?.let { o -> extractInt(o) } }
                                        ?: root["parameters"]?.let { (it as? JsonObject)?.let { o -> extractInt(o) } }
                                        ?: root["model_info"]?.let { (it as? JsonObject)?.let { o -> extractInt(o) } }
                                    if (ctx != null) {
                                        Log.i("LmStudioClient", "Context from $showUrl: $ctx")
                                        return@withContext Result.success(ctx)
                                    }
                                } catch (e: Exception) { Log.d("LmStudioClient", "Failed to parse $showUrl", e) }
                            }
                        }
                    }
                } catch (e: Exception) { Log.d("LmStudioClient", "show probe $showUrl failed", e) }
            }
        }

        // 4) Generic GET /v1/model (some servers)
        try {
            val url = "$normalized/model"
            val b = Request.Builder().url(url).get()
            if (!apiKey.isNullOrBlank()) b.header("Authorization", "Bearer $apiKey")
            client.newCall(b.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        try {
                            val root = json.parseToJsonElement(body).jsonObject
                            val ctx = extractInt(root)
                            if (ctx != null) {
                                Log.i("LmStudioClient", "Context from /model: $ctx")
                                return@withContext Result.success(ctx)
                            }
                        } catch (e: Exception) { Log.d("LmStudioClient", "Failed to parse /model", e) }
                    }
                }
            }
        } catch (e: Exception) { Log.d("LmStudioClient", "model probe failed", e) }

        Result.failure(IOException("Could not determine context length from server (tried /models, /props, /api/show)"))
    }

    /**
     * Non-streaming fallback — useful for quick test.
     */
    suspend fun chatCompletionBlocking(
        baseUrl: String,
        apiKey: String? = null,
        model: String,
        messages: List<ChatRoleMessage>,
        temperature: Float? = null,
        topP: Float? = null,
        topK: Int? = null,
        minP: Float? = null,
        maxTokens: Int? = null,
        repeatPenalty: Float? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        val url = "$normalized/chat/completions"
        val reqBody = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            topP = topP,
            topK = topK,
            minP = minP,
            maxTokens = maxTokens,
            stream = false,
            repeatPenalty = repeatPenalty,
        )
        val jsonBody = json.encodeToString(reqBody)
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val b = Request.Builder().url(url).post(body).header("Content-Type", "application/json")
        if (!apiKey.isNullOrBlank()) b.header("Authorization", "Bearer $apiKey")
        try {
            client.newCall(b.build()).execute().use { resp ->
                val s = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result.failure(IOException("HTTP ${resp.code}: $s"))
                val parsed = json.decodeFromString<ChatCompletionResponse>(s)
                val text = parsed.choices.firstOrNull()?.message?.content?.textValue() ?: ""
                Result.success(text)
            }
        } catch (e: Exception) { Result.failure(e) }
    }
}
