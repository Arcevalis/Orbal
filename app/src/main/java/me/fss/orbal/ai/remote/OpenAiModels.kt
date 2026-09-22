package me.fss.orbal.ai.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class OpenAiModelsResponse(
    @SerialName("object") val objectType: String? = null,
    val data: List<OpenAiModel> = emptyList(),
)

@Serializable
data class OpenAiModel(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
)

// Vision: content can be String or array of parts
@Serializable(with = MessageContentSerializer::class)
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class Parts(val parts: List<ContentPart>) : MessageContent()

    fun asTextOrNull(): String? = (this as? Text)?.text
    fun asPartsOrNull(): List<ContentPart>? = (this as? Parts)?.parts
    fun textValue(): String = when (this) {
        is Text -> text
        is Parts -> parts.filter { it.type == "text" }.joinToString("\n") { it.text ?: "" }
    }
}

@Serializable
data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null
)

@Serializable
data class ImageUrl(
    val url: String
)

object MessageContentSerializer : KSerializer<MessageContent> {
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor("MessageContent", SerialKind.CONTEXTUAL)

    override fun serialize(encoder: Encoder, value: MessageContent) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("MessageContent can only be serialized with JSON")
        val element: JsonElement = when (value) {
            is MessageContent.Text -> JsonPrimitive(value.text)
            is MessageContent.Parts -> jsonEncoder.json.encodeToJsonElement(value.parts)
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): MessageContent {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("MessageContent can only be deserialized with JSON")
        val element = jsonDecoder.decodeJsonElement()
        return when {
            element is JsonPrimitive && element.isString -> MessageContent.Text(element.content)
            element is JsonArray -> {
                val parts = jsonDecoder.json.decodeFromJsonElement<List<ContentPart>>(element)
                MessageContent.Parts(parts)
            }
            element is JsonNull -> throw SerializationException("Unexpected null for MessageContent")
            else -> throw SerializationException("Unexpected JSON for MessageContent: $element")
        }
    }
}

@Serializable
data class ChatRoleMessage(
    val role: String,
    val content: MessageContent? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
) {
    // Convenience for decoding — get text string regardless of parts
    val contentText: String? get() = content?.textValue()

    companion object {
        fun system(text: String) = ChatRoleMessage(role = "system", content = MessageContent.Text(text))
        fun user(text: String) = ChatRoleMessage(role = "user", content = MessageContent.Text(text))
        fun assistant(text: String?) = ChatRoleMessage(role = "assistant", content = text?.let { MessageContent.Text(it) })
        fun tool(text: String, toolCallId: String?) = ChatRoleMessage(role = "tool", content = MessageContent.Text(text), toolCallId = toolCallId)
        fun userWithImages(text: String, imageDataUrls: List<String>): ChatRoleMessage {
            if (imageDataUrls.isEmpty()) return user(text)
            val parts = mutableListOf<ContentPart>()
            if (text.isNotBlank()) {
                parts.add(ContentPart(type = "text", text = text))
            } else {
                parts.add(ContentPart(type = "text", text = "Describe the images"))
            }
            imageDataUrls.forEach { url ->
                parts.add(ContentPart(type = "image_url", imageUrl = ImageUrl(url)))
            }
            return ChatRoleMessage(role = "user", content = MessageContent.Parts(parts))
        }
    }
}

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatRoleMessage>,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("min_p") val minP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = true,
    @SerialName("repeat_penalty") val repeatPenalty: Float? = null, // LM Studio may ignore
    val tools: List<ToolSchema>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null, // "auto"
)

// OpenAI tool schemas — mirrors ToolRegistry but kept here for serialization decoupling
@Serializable
data class ToolSchema(
    val type: String = "function",
    val function: FunctionDef,
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: FunctionParameters,
)

@Serializable
data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, FunctionParam> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serializable
data class FunctionParam(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
)

@Serializable
data class ToolCall(
    val id: String? = null,
    val type: String? = "function",
    val function: FunctionCall? = null,
)

@Serializable
data class FunctionCall(
    val name: String? = null,
    val arguments: String? = null, // JSON string
)

// Streaming deltas
@Serializable
data class ToolCallDelta(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallDelta? = null,
)

@Serializable
data class FunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

@Serializable
data class ChatChoice(
    val index: Int? = null,
    val message: ChatRoleMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

// Streaming chunk
@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
)

@Serializable
data class ChunkChoice(
    val index: Int? = null,
    val delta: ChunkDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    val reasoning: String? = null, // some providers (Ollama /v1)
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("reasoning_details") val reasoningDetails: String? = null,
    @SerialName("thinking") val thinking: String? = null, // fallback (Anthropic-style)
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)
