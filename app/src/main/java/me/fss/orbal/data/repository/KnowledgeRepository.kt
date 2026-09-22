package me.fss.orbal.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import me.fss.orbal.data.local.dao.KnowledgeChunkDao
import me.fss.orbal.data.local.dao.KnowledgeDocumentDao
import me.fss.orbal.data.local.entities.KnowledgeChunk
import me.fss.orbal.data.local.entities.KnowledgeDocument
import me.fss.orbal.data.rag.RetrievalService
import me.fss.orbal.data.rag.chunkDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class IndexProgress {
    data class Stage(val name: String, val message: String) : IndexProgress()
    data object Done : IndexProgress()
}
class KnowledgeRepository(
    private val context: Context,
    private val documentDao: KnowledgeDocumentDao,
    private val chunkDao: KnowledgeChunkDao,
    private val retrievalService: RetrievalService,
) {
    fun getDocuments(projectId: String = "default"): Flow<List<KnowledgeDocument>> =
        documentDao.getDocuments(projectId)

    suspend fun getDocumentsSync(projectId: String = "default") =
        documentDao.getDocumentsSync(projectId)

    suspend fun toggleEnabled(id: Long, enabled: Boolean) = documentDao.setEnabled(id, enabled)

    suspend fun deleteDocument(id: Long) {
        // Cascades to chunks via FK, but ensure
        chunkDao.deleteByDoc(id)
        documentDao.delete(id)
    }

    /**
     * Index a document from a content Uri (file picker) or pasted text.
     * Mirrors OGAM indexDocument: extract → chunk → insert doc+chunks.
     */
    suspend fun indexDocumentUri(uri: Uri, projectId: String = "default", onProgress: (IndexProgress) -> Unit = {}): Long = withContext(Dispatchers.IO) {
        onProgress(IndexProgress.Stage("extracting", "Extracting text..."))
        val (fileName, size, text) = extractTextFromUri(uri)
        if (text.isNullOrBlank()) {
            val isPdf = fileName.lowercase().endsWith(".pdf")
            throw IllegalArgumentException(
                if (isPdf) "This looks like a scanned PDF with no text layer, so there was no text to extract (OCR not available)."
                else "Could not extract text from document"
            )
        }
        // Dedupe by name
        val existing = documentDao.getByName(fileName, projectId)
        if (existing != null) throw IllegalArgumentException("Document \"$fileName\" is already in the knowledge base")

        onProgress(IndexProgress.Stage("chunking", "Splitting into chunks..."))
        val chunks = chunkDocument(text)
        if (chunks.isEmpty()) throw IllegalArgumentException("Document produced no indexable content")

        onProgress(IndexProgress.Stage("indexing", "Indexing ${chunks.size} chunks..."))
        val docId = documentDao.insert(
            KnowledgeDocument(projectId = projectId, name = fileName, path = uri.toString(), size = size, enabled = true)
        )
        val entities = chunks.map { KnowledgeChunk(docId = docId, content = it.content, position = it.position) }
        chunkDao.insertAll(entities)

        onProgress(IndexProgress.Done)
        docId
    }

    suspend fun indexPastedText(title: String, text: String, projectId: String = "default", onProgress: (IndexProgress) -> Unit = {}): Long = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("There is no text to save.")
        val safeTitle = title.trim().ifEmpty { "Pasted note ${System.currentTimeMillis()}" }
        val fileName = if (safeTitle.lowercase().endsWith(".txt")) safeTitle else "$safeTitle.txt"
        val existing = documentDao.getByName(fileName, projectId)
        if (existing != null) throw IllegalArgumentException("Document \"$fileName\" is already in the knowledge base")
        onProgress(IndexProgress.Stage("chunking", "Splitting into chunks..."))
        val chunks = chunkDocument(trimmed)
        if (chunks.isEmpty()) throw IllegalArgumentException("Document produced no indexable content")
        onProgress(IndexProgress.Stage("indexing", "Indexing..."))
        val docId = documentDao.insert(
            KnowledgeDocument(projectId = projectId, name = fileName, path = "pasted:${System.currentTimeMillis()}", size = trimmed.length.toLong(), enabled = true)
        )
        chunkDao.insertAll(chunks.map { KnowledgeChunk(docId = docId, content = it.content, position = it.position) })
        onProgress(IndexProgress.Done)
        docId
    }

    suspend fun search(projectId: String = "default", query: String, contextLength: Int? = null): String {
        val results = if (contextLength != null) {
            retrievalService.searchWithBudget(projectId, query, contextLength)
        } else {
            retrievalService.search(projectId, query)
        }
        return retrievalService.formatForPrompt(results)
    }

    suspend fun searchRaw(projectId: String = "default", query: String, topK: Int = 5) =
        retrievalService.search(projectId, query, topK)

    private fun extractTextFromUri(uri: Uri): Triple<String, Long, String?> {
        val cr = context.contentResolver
        var name = "document"
        var size: Long = 0
        cr.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
            }
        }
        // For Standard, we handle plain text + markdown + limited PDF text extraction via naive read
        // PDFs will be read as bytes and we try to extract text; if binary, it will be empty and error handled
        val text = try {
            cr.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (size == 0L) size = bytes.size.toLong()
                // Cap 500k chars like OGAM RAG_MAX_CHARS
                val raw = bytes.toString(Charsets.UTF_8)
                // Quick heuristic: if raw contains many nulls, it's binary PDF — try naive PDF text extract
                if (raw.count { it == '\u0000' } > 10 || name.lowercase().endsWith(".pdf")) {
                    // Naive PDF text: extract strings in parentheses and between BT/ET
                    // For proper PDFs without text layer this will be empty → error message
                    val extracted = extractPdfTextNaive(bytes)
                    if (extracted.isNotBlank()) extracted else raw.take(500_000)
                } else raw.take(500_000)
            }
        } catch (e: Exception) {
            null
        }
        return Triple(name, size, text)
    }

    private fun extractPdfTextNaive(bytes: ByteArray): String {
        // Very light PDF text extraction: look for (text) and <hex> strings
        val asString = bytes.toString(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        val parenRegex = Regex("""\(([^\\()]+(?:\\.[^\\()]*)*)\)""")
        for (m in parenRegex.findAll(asString)) {
            val txt = m.groupValues[1].replace("\\n", "\n").replace("\\r", "").replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")
            // Heuristic: keep only plausible text runs
            if (txt.length in 3..500 && txt.any { it.isLetter() }) sb.appendLine(txt)
            if (sb.length > 500_000) break
        }
        return sb.toString().trim().take(500_000)
    }
}
