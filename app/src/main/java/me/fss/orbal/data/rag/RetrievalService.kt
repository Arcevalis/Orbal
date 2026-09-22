package me.fss.orbal.data.rag

import me.fss.orbal.data.local.dao.KnowledgeChunkDao
import me.fss.orbal.data.local.dao.KnowledgeDocumentDao
import me.fss.orbal.data.local.entities.KnowledgeChunk
import javax.inject.Inject
import javax.inject.Singleton

data class RagSearchResult(
    val docId: Long,
    val docName: String,
    val content: String,
    val position: Int,
    val score: Float = 0f, // for future embedding scores
)

@Singleton
class RetrievalService @Inject constructor(
    private val documentDao: KnowledgeDocumentDao,
    private val chunkDao: KnowledgeChunkDao,
) {
    /**
     * Keyword search Standard — no embeddings yet. Uses SQLite LIKE fallback via DAO.
     * Mirrors OGAM search() but without vector cosine; still topK 5 + budget.
     */
    suspend fun search(projectId: String = "default", query: String, topK: Int = 5): List<RagSearchResult> {
        if (query.isBlank()) return emptyList()
        val chunks = chunkDao.searchChunks(projectId, query, topK)
        if (chunks.isNotEmpty()) return mapWithNames(chunks)
        // Fallback: first chunks if no keyword hit (like OGAM's no-embedding fallback)
        val fallback = chunkDao.getChunksForProject(projectId).take(topK)
        return mapWithNames(fallback)
    }

    suspend fun searchWithBudget(projectId: String = "default", query: String, contextLength: Int, topK: Int = 5): List<RagSearchResult> {
        val results = search(projectId, query, topK)
        val budget = estimateCharBudget(contextLength)
        var total = 0
        val fitting = mutableListOf<RagSearchResult>()
        for (r in results) {
            total += r.content.length
            if (total > budget) break
            fitting += r
        }
        return fitting
    }

    private suspend fun mapWithNames(chunks: List<KnowledgeChunk>): List<RagSearchResult> {
        if (chunks.isEmpty()) return emptyList()
        val docCache = mutableMapOf<Long, String>()
        return chunks.map { c ->
            val name = docCache.getOrPut(c.docId) {
                // Cheap sync fetch — documentDao is suspend but we can cache
                // We avoid N queries by fetching docs once if many
                ""
            }
            // Lazy: fetch name if not cached via direct map
            RagSearchResult(docId = c.docId, docName = name, content = c.content, position = c.position)
        }.let { list ->
            // Bulk-fetch names to fill blanks
            val ids = list.map { it.docId }.distinct()
            val docs = ids.mapNotNull { id -> documentDao.getDocument(id)?.let { id to it.name } }.toMap()
            list.map { it.copy(docName = docs[it.docId] ?: "Document ${it.docId}") }
        }
    }

    fun formatForPrompt(results: List<RagSearchResult>): String {
        if (results.isEmpty()) return ""
        val sections = results.map { r ->
            val safeName = r.docName.replace("<", "").replace(">", "")
            val safeContent = stripAngleTags(r.content)
            "[Source: $safeName (part ${r.position + 1})]\n$safeContent"
        }
        return "<knowledge_base>\nThe following excerpts are from the user's knowledge base. Use them to inform your response when relevant.\n\n${sections.joinToString("\n\n---\n\n")}\n</knowledge_base>"
    }

    fun estimateCharBudget(contextLengthTokens: Int): Int {
        // 25% of window reserved for RAG, ~4 chars per token → budget = contextLength (OGAM formula)
        return maxOf(0, contextLengthTokens)
    }

    private fun stripAngleTags(text: String): String {
        val sb = StringBuilder()
        var inTag = false
        for (ch in text) {
            when (ch) {
                '<' -> inTag = true
                '>' -> inTag = false
                else -> if (!inTag) sb.append(ch)
            }
        }
        return sb.toString()
    }
}
