package me.fss.orbal.ai.compaction

import android.util.Log
import me.fss.orbal.ai.InferenceEngine
import me.fss.orbal.ai.remote.ChatRoleMessage
import me.fss.orbal.ai.remote.LmStudioClient
import me.fss.orbal.data.repository.MessageWithAttachments
import me.fss.orbal.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CompactResult(
    val summary: String,
    val summarizedCount: Int,
    val upToMessageId: String?
)

@Singleton
class CompactionManager @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val lmStudioClient: LmStudioClient,
    private val inferenceEngine: InferenceEngine
) {

    fun shouldCompact(estimatedInputTokens: Int, effectiveContextMax: Int): Boolean {
        if (!settingsRepository.compactionEnabled) return false
        if (effectiveContextMax <= 0) return false
        val threshold = settingsRepository.compactionThreshold.coerceIn(0.5f, 0.95f)
        // Headroom for output — never cap output itself, but ensure input leaves room.
        // User wants: if a reply wants 50% of ctx, so be it — so we derive inputLimit from maxTokens.
        val headroom = (settingsRepository.maxTokens + 512).coerceAtLeast(512)
        val inputLimit = (effectiveContextMax - headroom).coerceAtLeast(effectiveContextMax / 2)
        val thresholdBased = (effectiveContextMax * threshold).toInt()
        val inputThreshold = minOf(thresholdBased, inputLimit)
        val result = estimatedInputTokens > inputThreshold
        Log.i("CompactionManager", "shouldCompact? est=$estimatedInputTokens effective=$effectiveContextMax threshold=${threshold} headroom=$headroom inputLimit=$inputLimit inputThreshold=$inputThreshold -> $result")
        return result
    }

    suspend fun compact(
        history: List<MessageWithAttachments>,
        keepRecent: Int = settingsRepository.compactionKeepRecent.coerceIn(4, 20),
        systemHint: String? = null
    ): Result<CompactResult> = withContext(Dispatchers.IO) {
        if (history.size <= keepRecent) {
            return@withContext Result.failure(IllegalStateException("Not enough history to compact (size=${history.size}, keep=$keepRecent)"))
        }
        val toSummarize = history.dropLast(keepRecent)
        val upToId = toSummarize.lastOrNull()?.message?.id
        val summarizedCount = toSummarize.size

        // Build transcript — limit per-message to keep prompt bounded
        val transcript = buildString {
            // Optional system hint not needed for summarizer, but include if provided
            for (mwa in toSummarize) {
                val role = mwa.message.role
                val content = mwa.message.content.take(800) // cap per msg
                val imgNote = if (mwa.attachments.isNotEmpty()) " [Attached ${mwa.attachments.size} image(s)]" else ""
                appendLine("${role.uppercase()}: $content$imgNote")
                // hard cap total transcript to ~12k chars to avoid exceeding model input
                if (length > 12000) {
                    appendLine("... [truncated ${toSummarize.size} msgs, showing head]")
                    break
                }
            }
        }

        // Prefer remote LLM summarization when remote is enabled — most accurate, fast on server GPU
        if (settingsRepository.remoteEnabled && settingsRepository.remoteModelId.isNotBlank()) {
            try {
                val baseUrl = settingsRepository.remoteBaseUrl
                val model = settingsRepository.remoteModelId
                val apiKey = settingsRepository.remoteApiKey

                val sys = "You are a conversation summarizer. Summarize the following chat history concisely for continuity. Preserve: user goals, key decisions, preferences, names/dates, tool results (web_search, read_url, knowledge base), and any image descriptions. Keep under ~400 tokens. Output plain markdown, no preamble, no hedging."
                val messages = listOf(
                    ChatRoleMessage.system(sys),
                    ChatRoleMessage.user(transcript)
                )
                Log.i("CompactionManager", "Compacting ${toSummarize.size} msgs via remote $model (transcript ${transcript.length} chars)")
                val blocking = lmStudioClient.chatCompletionBlocking(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    messages = messages,
                    temperature = 0.2f,
                    maxTokens = 600
                )
                return@withContext blocking.map { summaryRaw ->
                    val summary = summaryRaw.trim().ifBlank { "Summary of $summarizedCount earlier messages." }
                    Log.i("CompactionManager", "Remote compaction success: ${summary.take(120)}")
                    CompactResult(summary = summary, summarizedCount = summarizedCount, upToMessageId = upToId)
                }.recoverCatching { e ->
                    Log.w("CompactionManager", "Remote compaction failed, falling back to truncation", e)
                    throw e
                }
            } catch (e: Exception) {
                Log.w("CompactionManager", "Remote compaction exception, fallback", e)
                // fall through to local/heuristic
            }
        }

        // Local LLM path — try if model is loaded, else heuristic
        if (inferenceEngine.isModelLoaded.get()) {
            try {
                val prompt = "Summarize this conversation history in under 400 tokens, preserving key facts:\n\n$transcript"
                Log.i("CompactionManager", "Compacting via local LLM (${toSummarize.size} msgs)")
                val summary = suspendCancellableCoroutine<String> { cont ->
                    inferenceEngine.generateResponse(
                        query = prompt,
                        onToken = {},
                        onComplete = { result -> cont.resume(result.response) },
                        onCancelled = { cont.resume("") },
                        onError = { e -> cont.resumeWithException(e) }
                    )
                }
                val cleaned = summary.trim().ifBlank { heuristicSummary(toSummarize) }
                return@withContext Result.success(CompactResult(cleaned, summarizedCount, upToId))
            } catch (e: Exception) {
                Log.w("CompactionManager", "Local compaction failed, using heuristic", e)
            }
        }

        // Heuristic fallback — never block sending; just produce a lightweight summary
        val heuristic = heuristicSummary(toSummarize)
        Log.i("CompactionManager", "Heuristic compaction: $heuristic")
        Result.success(CompactResult(heuristic, summarizedCount, upToId))
    }

    private fun heuristicSummary(toSummarize: List<MessageWithAttachments>): String {
        val firstUser = toSummarize.firstOrNull { it.message.role == "user" }?.message?.content?.take(200) ?: ""
        val lastAssistant = toSummarize.lastOrNull { it.message.role == "assistant" }?.message?.content?.take(200) ?: ""
        val imgCount = toSummarize.sumOf { it.attachments.size }
        return buildString {
            append("Earlier conversation summary (${toSummarize.size} messages")
            if (imgCount > 0) append(", $imgCount images")
            append("):\n")
            if (firstUser.isNotBlank()) append("- Started with: $firstUser\n")
            if (lastAssistant.isNotBlank()) append("- Last assistant: $lastAssistant\n")
            append("- (Auto-compacted to keep context fast; recent ${settingsRepository.compactionKeepRecent} messages kept verbatim.)")
        }
    }

    /** For callers that want to apply truncation instead of summarization on hard failure */
    fun truncationFallback(history: List<MessageWithAttachments>, keepRecent: Int): List<MessageWithAttachments> {
        return if (history.size <= keepRecent) history else history.takeLast(keepRecent)
    }
}
