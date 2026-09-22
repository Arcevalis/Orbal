package me.fss.orbal.ai.tools

/** Status after execution, mirrored from OGAM toolResult */
enum class ToolResultStatus { OK, EMPTY, ERROR }

enum class ToolErrorCategory { TIMEOUT, NETWORK, VALIDATION, NOT_FOUND, INTERNAL }

data class ToolParameter(
    val type: String,
    val description: String,
    val required: Boolean = false,
    val enum: List<String>? = null,
)

data class ToolDefinition(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val icon: String,
    val parameters: Map<String, ToolParameter>,
    val requiresNetwork: Boolean = false,
)

data class ToolCall(
    val id: String? = null,
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
    // Optional portable chat context for project-scoped tools
    val conversationId: String? = null,
)

data class ToolResult(
    val toolCallId: String? = null,
    val name: String,
    val content: String,
    val error: String? = null,
    val errorCategory: ToolErrorCategory? = null,
    val status: ToolResultStatus? = null,
    val durationMs: Long = 0,
)
