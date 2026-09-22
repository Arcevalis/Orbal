package me.fss.orbal.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.fss.orbal.data.local.entities.MessageAttachment
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageAttachmentDao {

    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY createdAt ASC")
    fun getForMessage(messageId: String): Flow<List<MessageAttachment>>

    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY createdAt ASC")
    suspend fun getForMessageSync(messageId: String): List<MessageAttachment>

    @Query("SELECT * FROM message_attachments WHERE messageId IN (:messageIds) ORDER BY createdAt ASC")
    suspend fun getForMessagesSync(messageIds: List<String>): List<MessageAttachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: MessageAttachment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<MessageAttachment>)

    @Query("DELETE FROM message_attachments WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    @Query("DELETE FROM message_attachments WHERE id = :id")
    suspend fun deleteById(id: String)
}
