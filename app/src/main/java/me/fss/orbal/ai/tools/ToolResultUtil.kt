package me.fss.orbal.ai.tools

const val MAX_TOOL_RESULT_CHARS = 24000 // ~6k tokens, matches OGAM bound

fun classifyToolError(err: Throwable): ToolErrorCategory {
    val msg = (err.message ?: err.toString()).lowercase()
    return when {
        msg.contains("abort") || msg.contains("timed out") || msg.contains("timeout") -> ToolErrorCategory.TIMEOUT
        msg.contains("network") || msg.contains("fetch") || msg.contains("enotfound") || msg.contains("econn") || msg.contains("unreachable") || msg.contains("not connected") || msg.contains("no server") || msg.contains("offline") || msg.contains("socket") -> ToolErrorCategory.NETWORK
        msg.contains("invalid") || msg.contains("required") || msg.contains("missing") || msg.contains("bad request") || msg.contains("validation") || msg.contains("schema") || msg.contains("malformed") -> ToolErrorCategory.VALIDATION
        msg.contains("not found") || msg.contains("no such") || msg.contains("unknown tool") || msg.contains("404") || msg.contains("does not exist") -> ToolErrorCategory.NOT_FOUND
        else -> ToolErrorCategory.INTERNAL
    }
}

fun toolErrorResult(call: ToolCall, err: Throwable, startMs: Long): ToolResult {
    return ToolResult(
        toolCallId = call.id,
        name = call.name,
        content = "",
        error = err.message ?: err.toString(),
        errorCategory = classifyToolError(err),
        status = ToolResultStatus.ERROR,
        durationMs = System.currentTimeMillis() - startMs,
    )
}

fun normalizeToolResult(call: ToolCall, raw: ToolResult): ToolResult {
    val id = raw.toolCallId ?: call.id
    if (raw.error != null) {
        return raw.copy(
            toolCallId = id,
            status = ToolResultStatus.ERROR,
            errorCategory = raw.errorCategory ?: classifyToolError(Exception(raw.error)),
        )
    }
    val hasContent = raw.content.trim().isNotEmpty()
    return raw.copy(toolCallId = id, status = if (hasContent) ToolResultStatus.OK else ToolResultStatus.EMPTY)
}

fun boundToolResult(name: String, content: String, maxChars: Int = MAX_TOOL_RESULT_CHARS): String {
    if (content.length <= maxChars) return content
    // Keep HEAD, OGAM keeps overview leading
    val truncated = content.take(maxChars)
    return truncated + "\n\n[Truncated — result was ${content.length} chars, showing first $maxChars]"
}

fun toolResultModelContent(result: ToolResult, maxChars: Int = MAX_TOOL_RESULT_CHARS): String {
    return when (result.status) {
        ToolResultStatus.ERROR -> {
            val cat = (result.errorCategory ?: ToolErrorCategory.INTERNAL).name.lowercase()
            boundToolResult(result.name, "Tool \"${result.name}\" failed ($cat): ${result.error ?: "unknown error"}. It returned no data — do not assume it succeeded.", maxChars)
        }
        ToolResultStatus.EMPTY -> "Tool \"${result.name}\" ran but returned no content."
        else -> boundToolResult(result.name, result.content, maxChars)
    }
}
