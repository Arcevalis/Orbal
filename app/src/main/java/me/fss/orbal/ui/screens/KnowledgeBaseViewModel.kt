package me.fss.orbal.ui.screens

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import me.fss.orbal.data.local.entities.KnowledgeDocument
import me.fss.orbal.data.rag.RagSearchResult
import me.fss.orbal.data.repository.KnowledgeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KnowledgeUiState(
    val documents: List<KnowledgeDocument> = emptyList(),
    val isIndexing: Boolean = false,
    val progressMessage: String? = null,
    val searchQuery: String = "",
    val searchResults: List<RagSearchResult> = emptyList(),
    val isSearching: Boolean = false,
)

@HiltViewModel
class KnowledgeBaseViewModel @Inject constructor(
    private val app: Application,
    private val repo: KnowledgeRepository,
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(KnowledgeUiState())
    val uiState: StateFlow<KnowledgeUiState> = _uiState

    init {
        viewModelScope.launch {
            repo.getDocuments().collect { docs ->
                _uiState.update { it.copy(documents = docs) }
            }
        }
    }

    fun indexUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isIndexing = true, progressMessage = "Extracting...") }
            try {
                repo.indexDocumentUri(uri) { prog ->
                    val msg = when (prog) {
                        is me.fss.orbal.data.repository.IndexProgress.Stage -> prog.message
                        is me.fss.orbal.data.repository.IndexProgress.Done -> "Done"
                        else -> prog.toString()
                    }
                    _uiState.update { it.copy(progressMessage = msg) }
                }
                Toast.makeText(app, "Document indexed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(app, "Index failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                _uiState.update { it.copy(isIndexing = false, progressMessage = null) }
            }
        }
    }

    fun indexPastedText(title: String, text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isIndexing = true, progressMessage = "Indexing note...") }
            try {
                repo.indexPastedText(title, text)
                Toast.makeText(app, "Note indexed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(app, "Index failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                _uiState.update { it.copy(isIndexing = false, progressMessage = null) }
            }
        }
    }

    fun toggleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repo.toggleEnabled(id, enabled) }
    }

    fun deleteDocument(id: Long) {
        viewModelScope.launch {
            repo.deleteDocument(id)
            Toast.makeText(app, "Deleted", Toast.LENGTH_SHORT).show()
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            try {
                val results = repo.searchRaw(query = query, topK = 5)
                _uiState.update { it.copy(searchResults = results) }
            } finally {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    }
}
