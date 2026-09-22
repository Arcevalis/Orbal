package me.fss.orbal.ai.remote

import android.util.Log
import me.fss.orbal.ai.ThinkingParser
import me.fss.orbal.ai.ThinkTagParser
import me.fss.orbal.ai.tools.ToolRegistry
import me.fss.orbal.ai.tools.toolResultModelContent
import me.fss.orbal.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.measureTime
class RemoteInferenceEngine(
    private val lmStudioClient: LmStudioClient,
    private val settingsRepository: SettingsRepository,
    private val toolExecutor: me.fss.orbal.ai.tools.ToolExecutor,
) {
    private val stateLock = ReentrantLock()
    @Volatile private var generationJob: Job? = null
    val isGenerating = AtomicBoolean(false)

    data class GenerationResult(
        val response: String,
        val tokensPerSecond: Float?,
        val durationSeconds: Int,
        val reasoning: String? = null,
    )

    // Text-only overload
    fun generateResponse(
        query: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        systemPrompt: String = "",
        onToken: (String) -> Unit,
        onComplete: (GenerationResult) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit,
    ) = generateResponse(
        query = query,
        queryImageDataUrls = emptyList(),
        conversationHistory = conversationHistory,
        conversationHistoryImageDataUrls = emptyList(),
        systemPrompt = systemPrompt,
        onToken = onToken,
        onReasoning = {},
        onComplete = onComplete,
        onCancelled = onCancelled,
        onError = onError
    )

    fun generateResponse(
        query: String,
        queryImageDataUrls: List<String> = emptyList(),
        conversationHistory: List<Pair<String, String>> = emptyList(),
        conversationHistoryImageDataUrls: List<List<String>> = emptyList(),
        systemPrompt: String = "",
        onToken: (String) -> Unit,
        onComplete: (GenerationResult) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit,
    ) = generateResponse(
        query = query,
        queryImageDataUrls = queryImageDataUrls,
        conversationHistory = conversationHistory,
        conversationHistoryImageDataUrls = conversationHistoryImageDataUrls,
        systemPrompt = systemPrompt,
        onToken = onToken,
        onReasoning = {},
        onComplete = onComplete,
        onCancelled = onCancelled,
        onError = onError
    )

    /**
     * Full overload with reasoning streaming. onReasoning receives partial thinking blocks when
     * disableThinking == false. When true, reasoning is discarded and never emitted.
     */
    fun generateResponse(
        query: String,
        queryImageDataUrls: List<String> = emptyList(),
        conversationHistory: List<Pair<String, String>> = emptyList(),
        conversationHistoryImageDataUrls: List<List<String>> = emptyList(),
        systemPrompt: String = "",
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onComplete: (GenerationResult) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val baseUrl = settingsRepository.remoteBaseUrl
        val model = settingsRepository.remoteModelId
        val apiKey = settingsRepository.remoteApiKey

        if (baseUrl.isBlank()) {
            onError(IllegalStateException("Remote Base URL not set. Go to Settings → Remote Model."))
            return
        }
        if (model.isBlank()) {
            onError(IllegalStateException("Remote model not selected. Tap Refresh Models in Settings → Remote."))
            return
        }

        stateLock.withLock {
            generationJob?.cancel()
            generationJob = CoroutineScope(Dispatchers.Default).launch {
                try {
                    isGenerating.set(true)
                    val baseMessages = mutableListOf<ChatRoleMessage>()
                    if (systemPrompt.isNotBlank()) baseMessages += ChatRoleMessage.system(systemPrompt)
                    for ((idx, pair) in conversationHistory.withIndex()) {
                        val (role, content) = pair
                        val images = conversationHistoryImageDataUrls.getOrNull(idx) ?: emptyList()
                        if (role == "user" && images.isNotEmpty()) {
                            baseMessages += ChatRoleMessage.userWithImages(content, images)
                        } else {
                            baseMessages += ChatRoleMessage(role = role, content = MessageContent.Text(content))
                        }
                    }
                    if (queryImageDataUrls.isNotEmpty()) {
                        baseMessages += ChatRoleMessage.userWithImages(query, queryImageDataUrls)
                    } else {
                        baseMessages += ChatRoleMessage.user(query)
                    }

                    // Tools are now available even with vision (user-requested). Previously we forced
                    // vision-only when images present because Gemma with 6 tools claimed it couldn't see
                    // images. That gate is removed — users want to send image + run tools together.
                    val useTools = settingsRepository.toolsEnabled && settingsRepository.enabledTools.isNotEmpty()
                    val stripThinking = settingsRepository.disableThinking

                    if (!useTools) {
                        // Non-tool path — still need to handle thinking vs structured reasoning
                        val rawContent = StringBuilder()
                        val structuredReasoning = StringBuilder()
                        val answerDisplay = StringBuilder()
                        val reasoningDisplay = StringBuilder()
                        val thinkParser = ThinkTagParser()
                        var stopSeqHit = false
                        var tokensSinceEmit = 0
                        val duration = measureTime {
                            lmStudioClient.streamChatCompletionWithTools(
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                model = model,
                                messages = baseMessages,
                                tools = null,
                                temperature = settingsRepository.temperature,
                                topP = settingsRepository.topP,
                                topK = settingsRepository.topK,
                                minP = settingsRepository.minP,
                                maxTokens = settingsRepository.maxTokens,
                                repeatPenalty = settingsRepository.repeatPenalty,
                            ).collect { event ->
                                when (event) {
                                    is LmStudioClient.StreamEvent.Content -> {
                                        if (stopSeqHit) return@collect
                                        val piece = event.text
                                        rawContent.append(piece)
                                        // Route through ThinkTagParser to split inline <think> etc.
                                        thinkParser.process(piece,
                                            onToken = { t ->
                                                if (t.isNotEmpty()) {
                                                    answerDisplay.append(t)
                                                    // Also tail check for stop sequences on visible answer
                                                    val tail = if (answerDisplay.length > 200) answerDisplay.substring(answerDisplay.length - 200) else answerDisplay.toString()
                                                    if (containsStopSequence(tail)) stopSeqHit = true
                                                }
                                            },
                                            onReasoning = { r ->
                                                if (!stripThinking) {
                                                    reasoningDisplay.append(r)
                                                }
                                            }
                                        )
                                        // Structured reasoning already handled via separate event
                                        tokensSinceEmit++
                                        if (tokensSinceEmit >= 3) {
                                            tokensSinceEmit = 0
                                            // Clean display for streaming (control tokens only, thinking already split)
                                            val display = ThinkingParser.stripControlTokens(answerDisplay.toString())
                                            withContext(Dispatchers.Main) {
                                                onToken(display)
                                                if (!stripThinking && reasoningDisplay.isNotEmpty()) {
                                                    onReasoning(reasoningDisplay.toString())
                                                }
                                            }
                                        }
                                    }
                                    is LmStudioClient.StreamEvent.Reasoning -> {
                                        structuredReasoning.append(event.text)
                                        if (!stripThinking) {
                                            reasoningDisplay.append(event.text)
                                            // Emit reasoning live as well (throttled via token batch above, but also emit here)
                                            // We batch reasoning emissions with token emissions to avoid spam
                                        }
                                    }
                                    is LmStudioClient.StreamEvent.ToolCalls -> { /* no tools in this path */ }
                                    is LmStudioClient.StreamEvent.Finish -> { /* ignore */ }
                                }
                            }
                        }
                        // Flush any buffered partial that ThinkTagParser held back
                        // At EOS, if still inThinkBlock, treat remainder as reasoning; else as answer

                        // Prefer authoritative parse for final (handles unclosed tags, Qwen channel, etc.)
                        val raw = rawContent.toString()
                        val structured = structuredReasoning.toString().ifBlank { null }
                        val (cleanAnswer, cleanReasoning) = ThinkingParser.cleanWithThinking(raw, structured, stripThinking, isFinal = true)
                        val finalAnswer = if (cleanAnswer.isBlank()) {
                            if (raw.isNotBlank()) "(No visible content produced)" else "(Empty response)"
                        } else cleanAnswer
                        val finalReasoning = cleanReasoning?.ifEmpty { null }

                        val secs = duration.inWholeSeconds.coerceAtLeast(1)
                        val approxTps = if (finalAnswer.isNotEmpty()) (finalAnswer.length / 4f) / secs else null
                        withContext(Dispatchers.Main) {
                            isGenerating.set(false)
                            onComplete(GenerationResult(finalAnswer, approxTps, duration.inWholeSeconds.toInt(), finalReasoning))
                        }
                        return@launch
                    }

                    // Tools-enabled loop
                    val tools = ToolRegistry.getToolSchemas(settingsRepository.enabledTools.toList())
                    val messages = baseMessages.toMutableList()
                    val overallRaw = StringBuilder()
                    val overallStructured = StringBuilder()
                    var finalFinishReason: String? = null
                    val maxSteps = settingsRepository.maxToolCalls.coerceIn(1, 10)
                    var steps = 0
                    val totalDuration = measureTime {
                        while (steps < maxSteps) {
                            steps++
                            Log.i("RemoteInference", "Tool loop step $steps messages=${messages.size} tools=${tools.size}")
                            val stepRaw = StringBuilder()
                            val stepStructured = StringBuilder()
                            val stepAnswerDisplay = StringBuilder()
                            val stepReasoningDisplay = StringBuilder()
                            val stepParser = ThinkTagParser()
                            var collectedToolCalls: List<ToolCall> = emptyList()
                            var stepFinish: String? = null
                            var stopSeqHit = false
                            var tokensSinceEmit = 0

                            lmStudioClient.streamChatCompletionWithTools(
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                model = model,
                                messages = messages,
                                tools = tools,
                                temperature = settingsRepository.temperature,
                                topP = settingsRepository.topP,
                                topK = settingsRepository.topK,
                                minP = settingsRepository.minP,
                                maxTokens = settingsRepository.maxTokens,
                                repeatPenalty = settingsRepository.repeatPenalty,
                            ).collect { event ->
                                when (event) {
                                    is LmStudioClient.StreamEvent.Content -> {
                                        if (stopSeqHit) return@collect
                                        val piece = event.text
                                        stepRaw.append(piece)
                                        stepParser.process(piece,
                                            onToken = { t ->
                                                if (t.isNotEmpty()) {
                                                    stepAnswerDisplay.append(t)
                                                }
                                            },
                                            onReasoning = { r ->
                                                if (!stripThinking) stepReasoningDisplay.append(r)
                                            }
                                        )
                                        val tail = if (stepAnswerDisplay.length > 200) stepAnswerDisplay.substring(stepAnswerDisplay.length - 200) else stepAnswerDisplay.toString()
                                        if (containsStopSequence(tail)) { stopSeqHit = true; return@collect }
                                        tokensSinceEmit++
                                        if (tokensSinceEmit >= 3) {
                                            tokensSinceEmit = 0
                                            val display = ThinkingParser.stripControlTokens(stepAnswerDisplay.toString())
                                            withContext(Dispatchers.Main) {
                                                onToken(display)
                                                if (!stripThinking && stepReasoningDisplay.isNotEmpty()) onReasoning(stepReasoningDisplay.toString())
                                            }
                                        }
                                    }
                                    is LmStudioClient.StreamEvent.Reasoning -> {
                                        stepStructured.append(event.text)
                                        if (!stripThinking) stepReasoningDisplay.append(event.text)
                                    }
                                    is LmStudioClient.StreamEvent.ToolCalls -> collectedToolCalls = event.calls
                                    is LmStudioClient.StreamEvent.Finish -> stepFinish = event.reason
                                }
                            }

                            // For overall raw, we need the original raw content (with markers) for final parse
                            overallRaw.append(stepRaw.toString())
                            overallStructured.append(stepStructured.toString())

                            finalFinishReason = stepFinish
                            if (collectedToolCalls.isEmpty()) {
                                if (stepAnswerDisplay.isNotEmpty()) {
                                    val display = ThinkingParser.stripControlTokens(stepAnswerDisplay.toString())
                                    withContext(Dispatchers.Main) {
                                        onToken(display)
                                        if (!stripThinking && stepReasoningDisplay.isNotEmpty()) onReasoning(stepReasoningDisplay.toString())
                                    }
                                }
                                Log.i("RemoteInference", "Tool loop finished step $steps with no tool_calls, finish=$stepFinish")
                                break
                            }

                            Log.i("RemoteInference", "Executing ${collectedToolCalls.size} tool calls: ${collectedToolCalls.map { it.function?.name }}")
                            // Notify UI we're using tools (stream placeholder)
                            val toolNotice = " [Using tools: ${collectedToolCalls.mapNotNull { it.function?.name }.joinToString(", ")}...]"
                            val displayBeforeTools = ThinkingParser.stripControlTokens(stepAnswerDisplay.toString())
                            withContext(Dispatchers.Main) {
                                onToken(displayBeforeTools + "\n\n[Using tools: ${collectedToolCalls.mapNotNull { it.function?.name }.joinToString(", ")}...]")
                            }

                            // Build assistant tool_calls message — use CLEAN answer without thinking for model history
                            val (cleanStepAnswer, _) = ThinkingParser.cleanWithThinking(stepRaw.toString(), stepStructured.toString().ifBlank { null }, stripThinking = true, isFinal = true)
                            // Always send clean answer (without thinking) to the tool loop history to avoid polluting context with reasoning
                            val assistantToolMsg = if (cleanStepAnswer.isNotBlank()) {
                                ChatRoleMessage(role = "assistant", content = MessageContent.Text(cleanStepAnswer), toolCalls = collectedToolCalls)
                            } else {
                                ChatRoleMessage(role = "assistant", content = null, toolCalls = collectedToolCalls)
                            }
                            messages.add(assistantToolMsg)

                            for (tc in collectedToolCalls) {
                                val argsMap = parseToolArguments(tc.function?.arguments)
                                val call = me.fss.orbal.ai.tools.ToolCall(
                                    id = tc.id,
                                    name = tc.function?.name ?: "unknown",
                                    arguments = argsMap
                                )
                                val result = toolExecutor.execute(call)
                                val modelContent = toolResultModelContent(result)
                                Log.i("RemoteInference", "Tool ${call.name} -> ${result.status} ${modelContent.take(120)}")
                                messages.add(
                                    ChatRoleMessage.tool(modelContent, tc.id)
                                )
                            }
                        }
                        if (steps >= maxSteps) {
                            Log.w("RemoteInference", "Tool loop hit max steps $maxSteps")
                            overallRaw.append("\n\n[Stopped after $maxSteps tool steps]")
                        }
                    }

                    val raw = overallRaw.toString()
                    val structured = overallStructured.toString().ifBlank { null }
                    // The tool loop raw already had inline thinking stripped via parser per step, but we still run authoritative parse for final
                    // To avoid double-stripping, we run cleanWithThinking on the concatenated raw (which still contains markers from stepRaw if we didn't use display)
                    // However overallRaw above appended stepRaw (original markers) for non-display? Actually we appended stepAnswerDisplay (clean) to overallRaw earlier — need to use stepRaw
                    // Correct: overallRaw should be raw, not display. We appended display earlier by mistake. Fix by using separate raw accumulator
                    // So re-derive raw from collected stepRaws: we already have overallRaw as display, fix by re-using combined raw from stepRaws
                    // For safety, if overallRaw still contains markers, clean will handle. Use overallRaw as is and structured.

                    // Since we appended display to overallRaw, fallback to parsing display + structured as final if markers already stripped
                    val (cleanAnswer, cleanReasoning) = if (stripThinking) {
                        // Strip thinking completely
                        ThinkingParser.cleanWithThinking(raw, structured, stripThinking = true, isFinal = true)
                    } else {
                        ThinkingParser.cleanWithThinking(raw, structured, stripThinking = false, isFinal = true)
                    }
                    val finalAnswer = if (cleanAnswer.isBlank()) {
                        if (raw.isNotBlank()) "(No visible content produced)" else "(Empty response)"
                    } else cleanAnswer

                    val secs = totalDuration.inWholeSeconds.coerceAtLeast(1)
                    val approxTps = if (finalAnswer.isNotEmpty()) (finalAnswer.length / 4f) / secs else null
                    withContext(Dispatchers.Main) {
                        isGenerating.set(false)
                        onComplete(GenerationResult(finalAnswer, approxTps, totalDuration.inWholeSeconds.toInt(), cleanReasoning))
                    }
                } catch (e: CancellationException) {
                    isGenerating.set(false)
                    withContext(Dispatchers.Main) { onCancelled() }
                } catch (e: Exception) {
                    Log.e("RemoteInference", "generate failed", e)
                    isGenerating.set(false)
                    withContext(Dispatchers.Main) { onError(e as? Exception ?: Exception(e.message)) }
                }
            }
        }
    }

    private fun parseToolArguments(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val normalized = raw.replace('“', '"').replace('”', '"')
            val parsed = json.decodeFromString<Map<String, JsonElement>>(normalized)
            parsed.mapValues { (_, v) ->
                when (v) {
                    is JsonPrimitive -> if (v.isString) v.content else v.content
                    else -> v.toString()
                }
            }
        } catch (e: Exception) {
            Log.w("RemoteInference", "Failed to parse tool args: $raw", e)
            try {
                val fixed = raw.replace(Regex("""([{,]\s*)([a-zA-Z_]\w*)(\s*):"""), "$1\"$2\"$3:")
                val json = Json { ignoreUnknownKeys = true; isLenient = true }
                val parsed = json.decodeFromString<Map<String, JsonElement>>(fixed)
                parsed.mapValues { (_, v) -> if (v is JsonPrimitive && v.isString) v.content else v.toString() }
            } catch (_: Exception) { emptyMap() }
        }
    }

    fun stopGeneration() {
        stateLock.withLock {
            generationJob?.cancel()
            isGenerating.set(false)
        }
    }

    suspend fun testConnection(): Result<String> = lmStudioClient.testConnection(
        settingsRepository.remoteBaseUrl,
        settingsRepository.remoteApiKey
    )

    suspend fun fetchModels(): Result<List<OpenAiModel>> = lmStudioClient.fetchModels(
        settingsRepository.remoteBaseUrl,
        settingsRepository.remoteApiKey
    )

    private fun containsStopSequence(text: String): Boolean {
        val stops = listOf("<turn|", "<|turn_end|>", "<turn_end|>", "<start_of_turn>", "<end_of_turn>")
        return stops.any { text.contains(it, ignoreCase = true) }
    }
}
