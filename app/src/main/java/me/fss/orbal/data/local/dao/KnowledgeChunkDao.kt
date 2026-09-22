package me.fss.orbal.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.fss.orbal.data.local.entities.KnowledgeChunk

@Dao
interface KnowledgeChunkDao {

    @Query("SELECT * FROM knowledge_chunks WHERE docId = :docId ORDER BY position ASC")
    suspend fun getChunks(docId: Long): List<KnowledgeChunk>

    @Query("SELECT * FROM knowledge_chunks WHERE docId IN (SELECT id FROM knowledge_documents WHERE projectId = :projectId AND enabled = 1) ORDER BY docId, position ASC")
    suspend fun getChunksForProject(projectId: String = "default"): List<KnowledgeChunk>

    @Query("SELECT * FROM knowledge_chunks WHERE docId IN (SELECT id FROM knowledge_documents WHERE projectId = :projectId) ORDER BY docId, position ASC LIMIT :limit")
    suspend fun getTopChunks(projectId: String = "default", limit: Int = 5): List<KnowledgeChunk>

    // Keyword fallback — LIKE search on enabled docs
    @Query("SELECT kc.* FROM knowledge_chunks kc JOIN knowledge_documents kd ON kc.docId = kd.id WHERE kd.projectId = :projectId AND kd.enabled = 1 AND kc.content LIKE '%' || :query || '%' ORDER BY kc.position ASC LIMIT :limit")
    suspend fun searchChunks(projectId: String = "default", query: String, limit: Int = 5): List<KnowledgeChunk>

    @Insert
    suspend fun insertAll(chunks: List<KnowledgeChunk>)

    @Query("DELETE FROM knowledge_chunks WHERE docId = :docId")
    suspend fun deleteByDoc(docId: Long)

    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE docId = :docId")
    suspend fun countByDoc(docId: Long): Int
}
