package me.fss.orbal.ai

/**
 * Reasoning delimiter grammar — single source for inline thinking extraction.
 * Mirrors OGAM's REASONING_DELIMITERS (shared grammar) so Orbal and OGAM agree:
 *  - <think>...</think>  DeepSeek / Qwen
 *  - <|channel>thought ... <channel|>  Gemma 4 native
 *  - <|channel|>analysis<|message|> ... <|channel|>final<|message|>  Qwen channel
 */
data class ReasoningDelimiter(val open: String, val close: String)

data class ParsedContent(
    val thinking: String?,
    val response: String,
    val isThinkingComplete: Boolean
)

data class ParsedModelOutput(
    val reasoning: String?,
    val answer: String,
    val isReasoningComplete: Boolean
)

object ThinkingParser {

    val REASONING_DELIMITERS: List<ReasoningDelimiter> = listOf(
        // Longest opens first so more specific wins when prefixes overlap (Qwen analysis before generic)
        ReasoningDelimiter("<|channel|>analysis<|message|>", "<|channel|>final<|message|>"),
        ReasoningDelimiter("<|channel|>analysis", "<|channel|>final"),
        ReasoningDelimiter("<|channel>thought", "<|channel|>"),
        ReasoningDelimiter("<|channel>thought", "<channel|>"),
        ReasoningDelimiter("<think>", "</think>"),
        // Fallback lower-case variants (Gemma may emit <Think>)
        // We handle case-insensitive search in parser via lowercasing
    )

    // For case-insensitive matching we lower-case both content and delimiter when searching, but keep original slicing
    // Simpler: search case-insensitively via indexOf with ignoreCase via manual lower.
    // We'll provide helpers.

    fun indexOfIgnoreCase(haystack: String, needle: String, startIndex: Int = 0): Int {
        if (needle.isEmpty()) return startIndex
        val hayLower = haystack.lowercase()
        val needleLower = needle.lowercase()
        return hayLower.indexOf(needleLower, startIndex)
    }

    /**
     * Complete-string parser: split raw content containing inline reasoning delimiters.
     * Handles one leading reasoning block (common). If multiple, first block is extracted, rest kept in response.
     */
    fun parseThinkingContent(content: String): ParsedContent {
        if (content.isEmpty()) return ParsedContent(null, "", true)

        var bestIdx = -1
        var bestDelimiter: ReasoningDelimiter? = null
        for (d in REASONING_DELIMITERS) {
            val idx = indexOfIgnoreCase(content, d.open)
            if (idx != -1 && (bestIdx == -1 || idx < bestIdx || (idx == bestIdx && d.open.length > (bestDelimiter?.open?.length ?: 0)))) {
                bestIdx = idx
                bestDelimiter = d
            }
        }
        if (bestIdx == -1 || bestDelimiter == null) {
            return ParsedContent(null, content, true)
        }
        val open = bestDelimiter.open
        val close = bestDelimiter.close
        val startThinking = bestIdx + open.length
        var reasoningStart = startThinking
        // Consume single optional newline after opener (OGAM semantics)
        if (reasoningStart < content.length && content[reasoningStart] == '\n') reasoningStart++
        // Also handle \r\n
        if (reasoningStart < content.length && content[reasoningStart - 1] == '\n' && reasoningStart < content.length && content[reasoningStart] == '\r') {
            // shouldn't happen, ignore
        }

        var closeIdx = indexOfIgnoreCase(content, close, reasoningStart)
        var actualClose = close
        // Gemma fallback: try alternative close form if first not found
        if (closeIdx == -1 && bestDelimiter.open == "<|channel>thought") {
            val altClose = if (close == "<channel|>") "<|channel|>" else "<channel|>"
            val altIdx = indexOfIgnoreCase(content, altClose, reasoningStart)
            if (altIdx != -1) {
                closeIdx = altIdx
                actualClose = altClose
            }
        }
        return if (closeIdx != -1) {
            val thinking = content.substring(reasoningStart, closeIdx).trim()
            val before = content.substring(0, bestIdx)
            val after = content.substring(closeIdx + actualClose.length)
            val response = (before + after).trim()
            ParsedContent(
                thinking = thinking.ifEmpty { null },
                response = response,
                isThinkingComplete = true
            )
        } else {
            // No close — incomplete thinking, everything after open is thinking
            val thinking = content.substring(reasoningStart).trim()
            val before = content.substring(0, bestIdx).trim()
            ParsedContent(
                thinking = thinking.ifEmpty { null },
                response = before,
                isThinkingComplete = false
            )
        }
    }

    /**
     * Unified display parse: combines separate reasoning channel + inline delimiters.
     * Mirrors OGAM's parseModelOutput(content, reasoningContent) — structured reasoning takes precedence,
     * inline is stripped from answer in both cases.
     */
    fun parseModelOutput(content: String, reasoningContent: String?): ParsedModelOutput {
        val inline = parseThinkingContent(content)
        val answer = inline.response
        val structured = reasoningContent?.trim()?.takeIf { it.isNotEmpty() }
        val inlineThinking = inline.thinking?.trim()?.takeIf { it.isNotEmpty() }

        val reasoning = when {
            !structured.isNullOrEmpty() && !inlineThinking.isNullOrEmpty() -> "$structured\n$inlineThinking"
            !structured.isNullOrEmpty() -> structured
            !inlineThinking.isNullOrEmpty() -> inlineThinking
            else -> null
        }
        // isReasoningComplete: if structured present => true (server already final), else inline completeness
        val isComplete = if (structured != null) true else inline.isThinkingComplete
        return ParsedModelOutput(
            reasoning = reasoning,
            answer = answer,
            isReasoningComplete = isComplete
        )
    }

    /**
     * Strip all inline thinking blocks (used when disableThinking == true).
     * Also handles unclosed tag at EOS (e.g. "<think>..." without close).
     */
    fun stripThinking(content: String): String {
        // Repeatedly strip until no more delimiters (in case multiple blocks)
        var cur = content
        var iteration = 0
        while (iteration < 5) {
            val parsed = parseThinkingContent(cur)
            if (parsed.thinking == null) break
            cur = parsed.response
            iteration++
        }
        return cur
    }

    fun stripControlTokens(content: String): String {
        var cleaned = content
        // Mirrors OGAM's CONTROL_TOKEN_PATTERNS minus tool blocks (we keep answer clean)
        cleaned = cleaned.replace(Regex("<\\|im_start\\|>\\s*(?:system|assistant|user|tool)?\\s*\\n?", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("<\\|im_end\\|>\\s*\\n?", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("<\\|end\\|>", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("<\\|eot_id\\|>", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("</s>", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("<\\|\">"), "")
        // Gemma turn markers (Orbal existing)
        cleaned = cleaned.replace(Regex("<turn\\|.*?\\|>", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("<\\|turn_end\\|>", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("<turn_end\\|>", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("<start_of_turn>.*?\\n", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("<end_of_turn>", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace("System instruction:", "")
        return cleaned
    }

    /**
     * Full clean respecting the strip toggle.
     * If stripThinking == true, thinking is discarded. If false, thinking is returned separately.
     */
    fun cleanWithThinking(raw: String, structuredReasoning: String?, stripThinking: Boolean, isFinal: Boolean): Pair<String, String?> {
        // Parse combined
        val parsed = parseModelOutput(raw, structuredReasoning)
        var answer = stripControlTokens(parsed.answer)
        var reasoning = parsed.reasoning?.let { stripControlTokens(it) }

        // Also strip any remaining tool-call markup that may leak? For Orbal we keep simple and don't strip tool calls here (they are structured)
        // Handle dangling "<" at streaming intermediate vs final
        if (!isFinal) {
            val potentialTagStart = listOf("<turn", "<|turn", "<|", "<start", "<")
            for (p in potentialTagStart) {
                if (answer.endsWith(p, ignoreCase = true)) {
                    answer = answer.substring(0, answer.length - p.length).trim()
                    break
                }
            }
            // Same for reasoning? Hold back partial?
        } else {
            // Final: remove trailing incomplete tag
            answer = answer.replace(Regex("<[^>]*$"), "").trim()
            reasoning = reasoning?.replace(Regex("<[^>]*$"), "")?.trim()
        }

        if (stripThinking) {
            // Discard reasoning entirely
            return answer to null
        } else {
            // Keep reasoning if present
            return answer to reasoning?.ifEmpty { null }
        }
    }
}

/**
 * Streaming parser that routes inline thinking delimiters to onReasoning vs onToken.
 * Mirrors OGAM's ThinkTagParser but Kotlin-ized.
 * Buffers partial tags across chunks so "<thi" + "nk>" is still recognized.
 */
class ThinkTagParser {
    private var inThinkBlock = false
    private var buffer = StringBuilder()
    private var activeClose = ""
    private var stripLeadingNewlineOnEntry = false

    fun process(content: String, onToken: (String) -> Unit, onReasoning: (String) -> Unit) {
        buffer.append(content)
        flush(onToken, onReasoning)
    }

    fun remaining(flushAsReasoningIfIncomplete: Boolean = true): Pair<String, String?> {
        // At stream end, what remains in buffer?
        // If inThinkBlock, treat remaining as reasoning (incomplete close); else as token.
        val remaining = buffer.toString()
        buffer = StringBuilder()
        return if (inThinkBlock) {
            // Incomplete reasoning block — remaining is reasoning
            "" to if (remaining.isNotEmpty()) remaining else null
        } else {
            remaining to null
        }
    }

    fun reset() {
        inThinkBlock = false
        buffer = StringBuilder()
        activeClose = ""
        stripLeadingNewlineOnEntry = false
    }

    private fun flush(onToken: (String) -> Unit, onReasoning: (String) -> Unit) {
        while (buffer.length > 0) {
            val shouldBreak = if (inThinkBlock) handleInsideThink(onReasoning) else handleOutsideThink(onToken)
            if (shouldBreak) break
        }
    }

    private fun partialTagSuffix(text: String, tag: String): Int {
        val max = minOf(text.length, tag.length - 1)
        for (i in max downTo 1) {
            if (text.endsWith(tag.substring(0, i), ignoreCase = true)) return i
        }
        return 0
    }

    private fun maxPartialTagSuffix(text: String, tags: List<String>): Int {
        var max = 0
        for (t in tags) max = maxOf(max, partialTagSuffix(text, t))
        return max
    }

    private fun earliestOpen(): Pair<Int, ReasoningDelimiter?> {
        var bestIdx = -1
        var bestDelimiter: ReasoningDelimiter? = null
        val bufStr = buffer.toString()
        for (d in ThinkingParser.REASONING_DELIMITERS) {
            val idx = ThinkingParser.indexOfIgnoreCase(bufStr, d.open)
            if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) {
                bestIdx = idx
                bestDelimiter = d
            }
        }
        return bestIdx to bestDelimiter
    }

    private fun handleOutsideThink(onToken: (String) -> Unit): Boolean {
        val (bestIdx, bestDelimiter) = earliestOpen()
        if (bestIdx == -1) {
            val partial = maxPartialTagSuffix(buffer.toString(), ThinkingParser.REASONING_DELIMITERS.map { it.open })
            if (partial > 0) {
                val emitUpTo = buffer.length - partial
                if (emitUpTo > 0) {
                    onToken(buffer.substring(0, emitUpTo))
                    buffer = StringBuilder(buffer.substring(emitUpTo))
                }
                return true
            }
            onToken(buffer.toString())
            buffer = StringBuilder()
            return true
        }
        if (bestIdx > 0) {
            onToken(buffer.substring(0, bestIdx))
        }
        // Remove up to end of open
        val openLen = bestDelimiter!!.open.length
        buffer = StringBuilder(buffer.substring(bestIdx + openLen))
        inThinkBlock = true
        activeClose = bestDelimiter.close
        stripLeadingNewlineOnEntry = true
        return false
    }

    private fun handleInsideThink(onReasoning: (String) -> Unit): Boolean {
        if (stripLeadingNewlineOnEntry) {
            if (buffer.length == 0) return true
            if (buffer[0] == '\n') {
                buffer = StringBuilder(buffer.substring(1))
            } else if (buffer.startsWith("\r\n")) {
                buffer = StringBuilder(buffer.substring(2))
            }
            stripLeadingNewlineOnEntry = false
        }
        val bufStr = buffer.toString()
        // Gemma's close may be either <channel|> or <|channel|> — search for earliest of both
        val candidateCloses = if (activeClose == "<channel|>" || activeClose == "<|channel|>") {
            listOf("<channel|>", "<|channel|>")
        } else listOf(activeClose)

        var bestIdx = -1
        var bestClose = ""
        for (c in candidateCloses) {
            val idx = ThinkingParser.indexOfIgnoreCase(bufStr, c)
            if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) {
                bestIdx = idx
                bestClose = c
            }
        }
        if (bestIdx == -1) {
            val partial = candidateCloses.maxOf { partialTagSuffix(bufStr, it) }
            if (partial > 0) {
                val emitUpTo = buffer.length - partial
                if (emitUpTo > 0) {
                    onReasoning(buffer.substring(0, emitUpTo))
                    buffer = StringBuilder(buffer.substring(emitUpTo))
                }
                return true
            }
            onReasoning(bufStr)
            buffer = StringBuilder()
            return true
        }
        if (bestIdx > 0) {
            onReasoning(bufStr.substring(0, bestIdx))
        }
        buffer = StringBuilder(bufStr.substring(bestIdx + bestClose.length))
        inThinkBlock = false
        activeClose = ""
        return false
    }
}
