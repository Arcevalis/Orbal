package me.fss.orbal.data.repository

import me.fss.orbal.data.local.dao.ConversationDao
import me.fss.orbal.data.local.dao.MessageAttachmentDao
import me.fss.orbal.data.local.dao.MessageDao
import me.fss.orbal.data.local.dao.ModelDao
import me.fss.orbal.data.local.entities.Conversation
import me.fss.orbal.data.local.entities.Message
import me.fss.orbal.data.local.entities.MessageAttachment
import me.fss.orbal.data.local.entities.ModelInfo
import me.fss.orbal.utils.CompressedImage
import me.fss.orbal.utils.ImageCompressor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ExportedChat(
    val title: String,
    val systemPromptKey: String,
    val createdAt: Long,
    val messages: List<ExportedMessage>
)

@Serializable
data class ExportedMessage(
    val role: String,
    val content: String,
    val timestamp: Long,
    val reasoningContent: String? = null
)

@Serializable
data class ExportData(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val chats: List<ExportedChat>
)

data class MessageWithAttachments(
    val message: Message,
    val attachments: List<MessageAttachment> = emptyList()
)
class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val messageAttachmentDao: MessageAttachmentDao,
    private val modelDao: ModelDao,
) {
    // Conversations
    fun getAllConversations(): Flow<List<Conversation>> = conversationDao.getAllConversations()

    suspend fun getConversation(id: String): Conversation? = conversationDao.getConversation(id)

    suspend fun getMostRecentConversation(): Conversation? = conversationDao.getMostRecent()

    suspend fun createConversation(title: String = "New Chat", systemPromptKey: String = "default"): Conversation {
        val conversation = Conversation(title = title, systemPromptKey = systemPromptKey)
        conversationDao.insert(conversation)
        return conversation
    }

    suspend fun updateConversation(conversation: Conversation) = conversationDao.update(conversation)

    suspend fun deleteConversation(id: String) = conversationDao.delete(id)

    suspend fun deleteAllConversations() {
        messageDao.deleteAll()
        conversationDao.deleteAll()
    }

    suspend fun getConversationCount(): Int = conversationDao.getCount()

    fun searchConversations(query: String): Flow<List<Conversation>> = conversationDao.search(query)

    // Messages
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>> =
        messageDao.getMessagesForConversation(conversationId)

    // Sync helper that actually fetches attachments
    suspend fun getMessagesWithAttachmentsSync(conversationId: String): List<MessageWithAttachments> {
        val messages = messageDao.getMessagesForConversationSync(conversationId)
        if (messages.isEmpty()) return emptyList()
        val ids = messages.map { it.id }
        val attachments = messageAttachmentDao.getForMessagesSync(ids)
        val grouped = attachments.groupBy { it.messageId }
        return messages.map { msg -> MessageWithAttachments(msg, grouped[msg.id] ?: emptyList()) }
    }

    suspend fun getMessagesSync(conversationId: String): List<Message> =
        messageDao.getMessagesForConversationSync(conversationId)

    suspend fun getAttachmentsForMessageSync(messageId: String): List<MessageAttachment> =
        messageAttachmentDao.getForMessageSync(messageId)

    suspend fun addMessage(conversationId: String, role: String, content: String, tokenCount: Int = 0, reasoningContent: String? = null): Message {
        val message = Message(
            conversationId = conversationId,
            role = role,
            content = content,
            tokenCount = tokenCount,
            reasoningContent = reasoningContent
        )
        messageDao.insert(message)
        conversationDao.incrementMessageCount(conversationId)
        return message
    }

    suspend fun addAssistantMessage(conversationId: String, content: String, reasoningContent: String?, tokenCount: Int = 0): Message {
        return addMessage(conversationId, "assistant", content, tokenCount, reasoningContent)
    }

    suspend fun addMessageWithAttachments(
        conversationId: String,
        role: String,
        content: String,
        compressedImages: List<CompressedImage>,
        tokenCount: Int = 0,
        reasoningContent: String? = null
    ): MessageWithAttachments {
        val message = Message(
            conversationId = conversationId,
            role = role,
            content = content,
            tokenCount = tokenCount,
            reasoningContent = reasoningContent
        )
        messageDao.insert(message)
        conversationDao.incrementMessageCount(conversationId)
        val attachments = compressedImages.map { ci ->
            MessageAttachment(
                messageId = message.id,
                localPath = ci.localPath,
                mimeType = ci.mimeType,
                width = ci.width,
                height = ci.height,
                sizeBytes = ci.sizeBytes
            )
        }
        if (attachments.isNotEmpty()) messageAttachmentDao.insertAll(attachments)
        return MessageWithAttachments(message, attachments)
    }

    suspend fun deleteMessage(id: String) {
        // Delete physical files first
        try {
            val atts = messageAttachmentDao.getForMessageSync(id)
            atts.forEach { File(it.localPath).delete() }
        } catch (_: Exception) {}
        messageDao.delete(id)
    }

    suspend fun updateMessageContent(messageId: String, newContent: String) =
        messageDao.updateContent(messageId, newContent)

    suspend fun deleteMessagesAfter(conversationId: String, messageId: String) {
        // Find messages after and delete their attachment files
        try {
            val all = messageDao.getMessagesForConversationSync(conversationId)
            val targetIdx = all.indexOfFirst { it.id == messageId }
            if (targetIdx >= 0) {
                val toDelete = all.drop(targetIdx + 1)
                toDelete.forEach { msg ->
                    try {
                        val atts = messageAttachmentDao.getForMessageSync(msg.id)
                        atts.forEach { File(it.localPath).delete() }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        messageDao.deleteAfter(conversationId, messageId)
    }

    suspend fun deleteAllMessagesForConversation(conversationId: String) =
        messageDao.deleteAllForConversation(conversationId)

    fun searchMessages(query: String): Flow<List<Message>> = messageDao.search(query)

    // Models Meow
    fun getAllModels(): Flow<List<ModelInfo>> = modelDao.getAllModels()

    suspend fun getAllModelsSync(): List<ModelInfo> = modelDao.getAllModelsSync()

    suspend fun getModel(id: Long): ModelInfo? = modelDao.getModel(id)

    suspend fun getModelByPath(path: String): ModelInfo? = modelDao.getModelByPath(path)

    suspend fun addModel(model: ModelInfo): Long = modelDao.insert(model)

    suspend fun deleteModel(id: Long) = modelDao.delete(id)

    // Export / Import Meow
    suspend fun exportChatsToJson(): String {
        val json = Json { prettyPrint = true }
        val count = conversationDao.getCount()
        if (count == 0) return json.encodeToString(ExportData(chats = emptyList()))
        val exportedChats = mutableListOf<ExportedChat>()
        val convList = getMostRecentConversation()?.let { recent ->
            listOf(recent)
        } ?: emptyList()
        return json.encodeToString(ExportData(chats = exportedChats))
    }

    suspend fun exportAllChats(): String {
        val json = Json { prettyPrint = true }
        val exportedChats = mutableListOf<ExportedChat>()
        return json.encodeToString(ExportData(chats = exportedChats))
    }

    suspend fun importChatsFromJson(jsonString: String): Result<Int> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val data = json.decodeFromString<ExportData>(jsonString)
            var count = 0
            for (chat in data.chats) {
                val conversation = Conversation(
                    title = chat.title,
                    systemPromptKey = chat.systemPromptKey,
                    createdAt = chat.createdAt,
                    updatedAt = System.currentTimeMillis(),
                    messageCount = chat.messages.size
                )
                conversationDao.insert(conversation)
                for (msg in chat.messages) {
                    messageDao.insert(
                        Message(
                            conversationId = conversation.id,
                            role = msg.role,
                            content = msg.content,
                            timestamp = msg.timestamp,
                            reasoningContent = msg.reasoningContent
                        )
                    )
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
