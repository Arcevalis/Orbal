package me.fss.orbal.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.fss.orbal.data.local.entities.KnowledgeDocument
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDocumentDao {

    @Query("SELECT * FROM knowledge_documents WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun getDocuments(projectId: String = "default"): Flow<List<KnowledgeDocument>>

    @Query("SELECT * FROM knowledge_documents WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun getDocumentsSync(projectId: String = "default"): List<KnowledgeDocument>

    @Query("SELECT * FROM knowledge_documents WHERE id = :id")
    suspend fun getDocument(id: Long): KnowledgeDocument?

    @Query("SELECT * FROM knowledge_documents WHERE name = :name AND projectId = :projectId LIMIT 1")
    suspend fun getByName(name: String, projectId: String = "default"): KnowledgeDocument?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(document: KnowledgeDocument): Long

    @Query("UPDATE knowledge_documents SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM knowledge_documents WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM knowledge_documents WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    @Query("SELECT COUNT(*) FROM knowledge_documents WHERE projectId = :projectId")
    suspend fun count(projectId: String = "default"): Int
}
