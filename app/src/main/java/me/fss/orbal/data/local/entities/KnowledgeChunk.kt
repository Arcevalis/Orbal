package me.fss.orbal.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_chunks",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeDocument::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["docId"]), Index(value = ["position"])]
)
data class KnowledgeChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val content: String,
    val position: Int,
)
