package me.fss.orbal.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import me.fss.orbal.data.local.dao.ConversationDao
import me.fss.orbal.data.local.dao.KnowledgeChunkDao
import me.fss.orbal.data.local.dao.KnowledgeDocumentDao
import me.fss.orbal.data.local.dao.MessageAttachmentDao
import me.fss.orbal.data.local.dao.MessageDao
import me.fss.orbal.data.local.dao.ModelDao
import me.fss.orbal.data.local.entities.Conversation
import me.fss.orbal.data.local.entities.KnowledgeChunk
import me.fss.orbal.data.local.entities.KnowledgeDocument
import me.fss.orbal.data.local.entities.Message
import me.fss.orbal.data.local.entities.MessageAttachment
import me.fss.orbal.data.local.entities.ModelInfo

@Database(
    entities = [Conversation::class, Message::class, ModelInfo::class, KnowledgeDocument::class, KnowledgeChunk::class, MessageAttachment::class],
    version = 5,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun modelDao(): ModelDao
    abstract fun knowledgeDocumentDao(): KnowledgeDocumentDao
    abstract fun knowledgeChunkDao(): KnowledgeChunkDao
    abstract fun messageAttachmentDao(): MessageAttachmentDao
}
