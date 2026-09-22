package me.fss.orbal.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_documents",
    indices = [Index(value = ["projectId"]), Index(value = ["name"])]
)
data class KnowledgeDocument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String = "default", // single global KB for Standard; per-conversation later
    val name: String,
    val path: String, // file path or synthetic "pasted:..." 
    val size: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
)
