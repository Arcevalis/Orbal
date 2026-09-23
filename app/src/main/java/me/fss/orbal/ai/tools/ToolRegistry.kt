package me.fss.orbal.ai.tools

import me.fss.orbal.ai.remote.FunctionDef
import me.fss.orbal.ai.remote.FunctionParam
import me.fss.orbal.ai.remote.FunctionParameters
import me.fss.orbal.ai.remote.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ToolRegistry {

    val AVAILABLE_TOOLS: List<ToolDefinition> = listOf(
        ToolDefinition(
            id = "web_search",
            name = "web_search",
            displayName = "Web Search",
            description = "Search the live web and return real-time result titles, snippets, and URLs. Use for current events, prices, weather, news, or anything needing up-to-date info. Call read_url on a result URL to get full page content.",
            icon = "globe",
            requiresNetwork = true,
            parameters = mapOf(
                "query" to ToolParameter(type = "string", description = "Search query", required = true)
            )
        ),
        ToolDefinition(
            id = "calculator",
            name = "calculator",
            displayName = "Calculator",
            description = "Evaluate math expressions",
            icon = "hash",
            parameters = mapOf(
                "expression" to ToolParameter(type = "string", description = "Math expression, e.g. 2*(3+4)", required = true)
            )
        ),
        ToolDefinition(
            id = "get_current_datetime",
            name = "get_current_datetime",
            displayName = "Date & Time",
            description = "Get current date and time",
            icon = "clock",
            parameters = mapOf(
                "timezone" to ToolParameter(type = "string", description = "IANA timezone, e.g. America/New_York")
            )
        ),
        ToolDefinition(
            id = "get_device_info",
            name = "get_device_info",
            displayName = "Device Info",
            description = "Get device hardware info",
            icon = "smartphone",
            parameters = mapOf(
                "info_type" to ToolParameter(type = "string", description = "Info type", enum = listOf("battery", "storage", "memory", "all"))
            )
        ),
        ToolDefinition(
            id = "search_knowledge_base",
            name = "search_knowledge_base",
            displayName = "Knowledge Base",
            description = "Search uploaded project documents and pasted notes",
            icon = "book-open",
            parameters = mapOf(
                "query" to ToolParameter(type = "string", description = "Search query", required = true)
            )
        ),
        ToolDefinition(
            id = "read_url",
            name = "read_url",
            displayName = "URL Reader",
            description = "Fetch the full live content of any URL. Use after web_search to read a result page, or when user shares a link.",
            icon = "link",
            requiresNetwork = true,
            parameters = mapOf(
                "url" to ToolParameter(type = "string", description = "Full URL to fetch", required = true)
            )
        ),
    )

    /**
     * Build OpenAI `tools` array for ChatCompletionRequest.
     */
    fun getToolsAsOpenAISchema(enabledToolIds: List<String>): List<Map<String, Any>> {
        return AVAILABLE_TOOLS.filter { it.id in enabledToolIds }.map { tool ->
            val props = buildJsonObject {
                tool.parameters.forEach { (k, p) ->
                    put(k, buildJsonObject {
                        put("type", p.type)
                        put("description", p.description)
                        if (p.enum != null) put("enum", kotlinx.serialization.json.JsonArray(p.enum.map { JsonPrimitive(it) }))
                    })
                }
            }
            val required = tool.parameters.filter { it.value.required }.keys.toList()
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to props,
                        "required" to required
                    )
                )
            )
        }
    }

    /** Minimal JSON-schema objects for serialization via kotlinx.serialization (used in LmStudioClient) */
    fun getToolSchemas(enabledToolIds: List<String>): List<ToolSchema> {
        return AVAILABLE_TOOLS.filter { it.id in enabledToolIds }.map { tool ->
            ToolSchema(
                type = "function",
                function = FunctionDef(
                    name = tool.name,
                    description = tool.description,
                    parameters = FunctionParameters(
                        type = "object",
                        properties = tool.parameters.mapValues { (_, p) ->
                            FunctionParam(type = p.type, description = p.description, enum = p.enum)
                        },
                        required = tool.parameters.filter { it.value.required }.keys.toList()
                    )
                )
            )
        }
    }

    fun buildToolSystemPromptHint(enabledToolIds: List<String>): String {
        val enabled = AVAILABLE_TOOLS.filter { it.id in enabledToolIds }
        if (enabled.isEmpty()) return ""
        val list = enabled.joinToString("\n") { "- ${it.name}: ${it.description}" }
        return "\n\nTools available:\n$list\nUse these tools proactively and precisely — call the right tool at the right moment rather than guessing."
    }
}
