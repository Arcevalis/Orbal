package me.fss.orbal.ui.screens

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import me.fss.orbal.ai.InferenceEngine
import me.fss.orbal.ai.ModelManager
import me.fss.orbal.ai.SystemPrompts
import me.fss.orbal.ai.remote.RemoteInferenceEngine
import me.fss.orbal.data.local.entities.Conversation
import me.fss.orbal.data.local.entities.Message
import me.fss.orbal.data.repository.ChatRepository
import me.fss.orbal.data.repository.ExportData
import me.fss.orbal.data.repository.ExportedChat
import me.fss.orbal.data.repository.ExportedMessage
import me.fss.orbal.data.repository.MessageWithAttachments
import me.fss.orbal.data.repository.SettingsRepository
import me.fss.orbal.utils.CompressedImage
import me.fss.orbal.utils.ImageCompressor
import me.fss.orbal.utils.MemoryMonitor
import me.fss.orbal.utils.TtsHelper
import me.fss.orbal.utils.SecurityUtils
import me.fss.orbal.ai.compaction.CompactionManager
import me.fss.orbal.ai.compaction.TokenEstimator
import me.fss.orbal.ai.remote.LmStudioClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val currentConversation: Conversation? = null,
    val messages: List<MessageWithAttachments> = emptyList(),
    val partialResponse: String = "",
    val partialReasoning: String = "",
    val isGenerating: Boolean = false,
    val modelState: ModelManager.ModelState = ModelManager.ModelState.NotLoaded,
    val tokensPerSecond: Float? = null,
    val memoryStatus: me.fss.orbal.utils.MemoryStatus? = null,
    val showConversationDrawer: Boolean = false,
    val errorMessage: String? = null,
    val speakingMessageId: String? = null,
    val sensitiveDataAccessibilityEnabled: Boolean = false,
    val contextUsed: Int = 0,
    val contextMax: Int = 4096,
    val isRemoteEnabled: Boolean = false,
    val remoteModelId: String = "",
    val remoteBaseUrl: String = "",
    val toolsEnabled: Boolean = false,
    val compactionSummary: String = "",
    val compactionMessageCount: Int = 0,
    val compactionUpToMessageId: String? = null,
    val isCompacting: Boolean = false,
    val serverCtx: Int = 0,
    val queuedMessageCount: Int = 0,
)
class ChatViewModel(
    private val application: Application,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val modelManager: ModelManager,
    private val inferenceEngine: InferenceEngine,
    private val remoteInferenceEngine: RemoteInferenceEngine,
    private val knowledgeRepository: me.fss.orbal.data.repository.KnowledgeRepository,
    private val compactionManager: CompactionManager,
    private val lmStudioClient: LmStudioClient,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    private val exportJson = Json { prettyPrint = true }

    private val memoryMonitor = MemoryMonitor(application)
    private val ttsHelper = TtsHelper(application)
    private var navigationJob: kotlinx.coroutines.Job? = null

    // Queue for messages sent while generating (input allowed, send queues). CompactING blocks queue.
    private val generationQueue = ArrayDeque<String>()
    private fun updateQueuedCount() {
        _uiState.update { it.copy(queuedMessageCount = generationQueue.size) }
    }

    init {
        setupCollectors()
        memoryMonitor.startMonitoring(viewModelScope)
        viewModelScope.launch {
            memoryMonitor.memoryStatus.collect { status ->
                _uiState.update { it.copy(memoryStatus = status) }
            }
        }

        viewModelScope.launch {
            while (true) {
                val conv = _uiState.value.currentConversation
                val dbConv = conv?.let { try { chatRepository.getConversation(it.id) } catch (_: Exception) { null } }
                _uiState.update {
                    it.copy(
                        sensitiveDataAccessibilityEnabled = settingsRepository.sensitiveDataAccessibilityEnabled,
                        contextMax = settingsRepository.effectiveContextSize,
                        isRemoteEnabled = settingsRepository.remoteEnabled,
                        remoteModelId = settingsRepository.remoteModelId,
                        remoteBaseUrl = settingsRepository.remoteBaseUrl,
                        toolsEnabled = settingsRepository.toolsEnabled,
                        compactionSummary = dbConv?.compactionSummary ?: it.compactionSummary,
                        compactionMessageCount = dbConv?.compactionMessageCount ?: it.compactionMessageCount,
                        compactionUpToMessageId = dbConv?.compactionUpToMessageId,
                        serverCtx = settingsRepository.serverReportedCtx,
                    )
                }
                delay(500)
            }
        }
        // Periodically refresh server-reported context length when remote is enabled
        viewModelScope.launch {
            while (true) {
                delay(10000)
                if (settingsRepository.remoteEnabled && settingsRepository.remoteModelId.isNotBlank()) {
                    try { refreshServerContext() } catch (_: Exception) {}
                }
            }
        }

        viewModelScope.launch {
            var lastContextSize = settingsRepository.contextSize
            while (true) {
                delay(1000)
                val current = settingsRepository.contextSize
                if (current != lastContextSize) {
                    lastContextSize = current
                    val conv = _uiState.value.currentConversation
                    val ready = _uiState.value.modelState is ModelManager.ModelState.Ready
                    if (conv != null && ready && !_uiState.value.isGenerating) {
                        modelManager.unloadModel()
            _uiState.update { it.copy(contextUsed = 0) }
                        loadModelForConversation(conv)
                    }
                }
            }
        }
    }

    private fun setupCollectors() {
        viewModelScope.launch {
            chatRepository.getAllConversations().collect { conversations ->
                _uiState.update { it.copy(conversations = conversations) }
            }
        }

        viewModelScope.launch {
            modelManager.modelState.collect { state ->
                _uiState.update { it.copy(modelState = state) }
            }
        }

        viewModelScope.launch {
            _uiState.map { it.currentConversation?.id }
                .distinctUntilChanged()
                .collectLatest { conversationId ->
                    if (conversationId != null) {
                        chatRepository.getMessagesForConversation(conversationId)
                            .collect {
                                val enriched = chatRepository.getMessagesWithAttachmentsSync(conversationId)
                                val conv = try { chatRepository.getConversation(conversationId) } catch (_: Exception) { null }
                                _uiState.update { s -> s.copy(
                                    messages = enriched,
                                    compactionSummary = conv?.compactionSummary ?: "",
                                    compactionMessageCount = conv?.compactionMessageCount ?: 0,
                                    compactionUpToMessageId = conv?.compactionUpToMessageId,
                                ) }
                            }
                    } else {
                        _uiState.update { it.copy(messages = emptyList(), compactionSummary = "", compactionMessageCount = 0, compactionUpToMessageId = null) }
                    }
                }
        }
    }

    suspend fun refreshServerContext() {
        val base = settingsRepository.remoteBaseUrl
        val model = settingsRepository.remoteModelId
        if (base.isBlank() || model.isBlank()) return
        val result = lmStudioClient.fetchModelContextLength(base, model, settingsRepository.remoteApiKey)
        result.onSuccess { ctx ->
            if (ctx > 512) {
                Log.i("ChatViewModel", "Server reports ctx=$ctx for $model")
                settingsRepository.serverReportedCtx = ctx
                settingsRepository.serverReportedCtxModel = model
                _uiState.update { it.copy(serverCtx = ctx, contextMax = ctx) }
            }
        }.onFailure { e ->
            Log.w("ChatViewModel", "Failed to fetch server ctx: ${e.message}")
        }
    }

    private suspend fun maybeCompactBeforeGeneration(
        conversationId: String,
        systemPrompt: String
    ) {
        if (!settingsRepository.compactionEnabled) return
        val effectiveMax = settingsRepository.effectiveContextSize
        if (effectiveMax <= 0) return
        val enriched = chatRepository.getMessagesWithAttachmentsSync(conversationId)
        if (enriched.size <= settingsRepository.compactionKeepRecent) return
        val estimated = TokenEstimator.estimateHistoryWithAttachments(systemPrompt, enriched)
        // update bar immediately
        _uiState.update { it.copy(contextUsed = estimated, contextMax = effectiveMax) }
        if (!compactionManager.shouldCompact(estimated, effectiveMax)) return
        _uiState.update { it.copy(isCompacting = true) }
        try {
            val res = compactionManager.compact(enriched, keepRecent = settingsRepository.compactionKeepRecent)
            res.onSuccess { cr ->
                val conv = chatRepository.getConversation(conversationId) ?: return@onSuccess
                val updated = conv.copy(
                    compactionSummary = cr.summary,
                    compactionUpToMessageId = cr.upToMessageId,
                    compactionMessageCount = cr.summarizedCount,
                    compactionUpdatedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                chatRepository.updateConversation(updated)
                _uiState.update {
                    it.copy(
                        currentConversation = if (it.currentConversation?.id == conversationId) updated else it.currentConversation,
                        compactionSummary = cr.summary,
                        compactionMessageCount = cr.summarizedCount,
                        compactionUpToMessageId = cr.upToMessageId
                    )
                }
                Log.i("ChatViewModel", "Compaction applied: ${cr.summarizedCount} msgs -> ${cr.summary.take(80)}")
            }.onFailure { e ->
                Log.w("ChatViewModel", "Compaction failed: ${e.message}")
            }
        } finally {
            _uiState.update { it.copy(isCompacting = false) }
        }
    }

    private suspend fun getCompactionAugmentedPrompt(basePrompt: String, conversationId: String): String {
        val conv = try { chatRepository.getConversation(conversationId) } catch (_: Exception) { null }
        val summary = conv?.compactionSummary
        if (summary.isNullOrBlank()) return basePrompt
        val count = conv.compactionMessageCount
        val keep = settingsRepository.compactionKeepRecent
        return "$basePrompt\n\n[Earlier conversation summary ($count older messages compacted; recent $keep kept verbatim):\n$summary\n]"
    }

    private suspend fun buildHistoryForRemoteWithCompaction(conversationId: String, excludeLast: Boolean): Pair<List<Pair<String,String>>, List<List<String>>> {
        val conv = try { chatRepository.getConversation(conversationId) } catch (_: Exception) { null }
        val hasCompaction = !conv?.compactionSummary.isNullOrBlank()
        val keep = settingsRepository.compactionKeepRecent.coerceIn(4, 20)
        // Use base builder then truncate if needed
        val (rawHistory, rawImages) = buildHistoryWithImages(conversationId, excludeLast)
        if (!hasCompaction || !settingsRepository.compactionEnabled) return rawHistory to rawImages
        if (rawHistory.size <= keep) return rawHistory to rawImages
        // Keep only last `keep` entries when compacted
        return rawHistory.takeLast(keep) to rawImages.takeLast(keep)
    }

    fun clearCompaction(conversationId: String) {
        viewModelScope.launch {
            try {
                val conv = chatRepository.getConversation(conversationId) ?: return@launch
                val cleared = conv.copy(compactionSummary = "", compactionUpToMessageId = null, compactionMessageCount = 0, compactionUpdatedAt = 0L)
                chatRepository.updateConversation(cleared)
                if (_uiState.value.currentConversation?.id == conversationId) {
                    _uiState.update { it.copy(currentConversation = cleared, compactionSummary = "", compactionMessageCount = 0, compactionUpToMessageId = null) }
                }
            } catch (_: Exception) {}
        }
    }

    fun initialize() {
        viewModelScope.launch {
            val existing = chatRepository.getMostRecentConversation()
            val conversation = existing ?: chatRepository.createConversation()
            _uiState.update {
                it.copy(
                    currentConversation = conversation,
                    isRemoteEnabled = settingsRepository.remoteEnabled,
                    remoteModelId = settingsRepository.remoteModelId,
                    remoteBaseUrl = settingsRepository.remoteBaseUrl,
                    toolsEnabled = settingsRepository.toolsEnabled,
                    contextMax = settingsRepository.effectiveContextSize,
                    serverCtx = settingsRepository.serverReportedCtx,
                    compactionSummary = conversation.compactionSummary,
                    compactionMessageCount = conversation.compactionMessageCount,
                    compactionUpToMessageId = conversation.compactionUpToMessageId,
                )
            }

            if (settingsRepository.remoteEnabled) {
                // Refresh server-reported ctx so compaction & bar use real window
                try { refreshServerContext() } catch (_: Exception) {}
                return@launch
            }

            val modelId = settingsRepository.activeModelId
            if (modelId != -1L) {
                loadModelForConversation(conversation)
            }
        }
    }

    private suspend fun loadModelForConversation(conversation: Conversation) {
        val modelId = settingsRepository.activeModelId
        if (modelId == -1L) return

        val systemPrompt = SystemPrompts.getPrompt(
            settingsRepository.systemPromptKey,
            settingsRepository.customSystemPrompt,
            settingsRepository.translatorFrom,
            settingsRepository.translatorTo,
        ).let {
            if (settingsRepository.mathLatexHints)
                "$it\nFor mathematical expressions, always use \$...\$ for inline math and \$\$...\$\$ for block math."
            else it
        }

        val messages = chatRepository.getMessagesSync(conversation.id)
        val history = messages.map { it.role to it.content }

        modelManager.loadModel(
            modelId = modelId,
            systemPrompt = systemPrompt,
            conversationHistory = history,
            onSuccess = {},
            onError = { e ->
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        )
    }

    private fun getSystemPrompt(): String {
        return SystemPrompts.getPrompt(
            settingsRepository.systemPromptKey,
            settingsRepository.customSystemPrompt,
            settingsRepository.translatorFrom,
            settingsRepository.translatorTo,
        ).let {
            if (settingsRepository.mathLatexHints)
                "$it\nFor mathematical expressions, always use \$...\$ for inline math and \$\$...\$\$ for block math."
            else it
        }
    }

    private suspend fun getAugmentedSystemPrompt(query: String): String {
        val base = getSystemPrompt()
        return try {
            val kbPrompt = knowledgeRepository.search(query = query, contextLength = settingsRepository.effectiveContextSize)
            if (kbPrompt.isNotBlank()) "$base\n\n$kbPrompt" else base
        } catch (e: Exception) {
            base
        }
    }

    // Helper to build base64 data urls for a message's attachments (bounded)
    private suspend fun getImageDataUrlsForMessage(messageId: String): List<String> {
        return try {
            val atts = chatRepository.getAttachmentsForMessageSync(messageId)
            atts.take(ImageCompressor.MAX_IMAGES_PER_MESSAGE).mapNotNull { att ->
                try {
                    ImageCompressor.uriToBase64DataUrl(getApplication(), att.localPath)
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Failed to base64 for ${att.localPath}", e)
                    null
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun buildHistoryWithImages(conversationId: String, excludeLast: Boolean = false): Pair<List<Pair<String,String>>, List<List<String>>> {
        val enriched = chatRepository.getMessagesWithAttachmentsSync(conversationId)
        val sliced = if (excludeLast && enriched.isNotEmpty()) enriched.dropLast(1) else enriched
        val history = sliced.map { it.message.role to it.message.content }
        val imageLists = mutableListOf<List<String>>()
        // Keep up to 12 recent images for long vision chats (~50 msgs + 10 images) — cap payload
        val allImageCounts = sliced.sumOf { it.attachments.size }
        var imagesToInclude = 12
        // Walk backwards to keep recent images
        val reversedImageLists = sliced.reversed().map { mwa ->
            if (mwa.message.role == "user" && mwa.attachments.isNotEmpty() && imagesToInclude > 0) {
                val urls = mwa.attachments.take(ImageCompressor.MAX_IMAGES_PER_MESSAGE).mapNotNull { att ->
                    try { ImageCompressor.uriToBase64DataUrl(getApplication(), att.localPath) } catch (_: Exception) { null }
                }.take(imagesToInclude)
                imagesToInclude -= urls.size
                urls
            } else emptyList()
        }.reversed()
        // Now reversedImageLists is in order; assign to imageLists
        imageLists.addAll(reversedImageLists)
        return history to imageLists
    }

    // Legacy text-only entry — delegates
    fun sendMessage(text: String) {
        sendMessageWithImages(text, emptyList())
    }

    fun sendMessageWithImages(text: String, imageUris: List<Uri>) {
        // During compaction: allow typing but not sending/queuing — block send
        if (_uiState.value.isCompacting) {
            _uiState.update { it.copy(errorMessage = "Compacting conversation — you can keep typing, but sending is paused until compaction finishes.") }
            return
        }
        val sanitized = SecurityUtils.sanitizePrompt(text)
        if (sanitized.isBlank() && imageUris.isEmpty()) return
        val displayText = sanitized
        val conversation = _uiState.value.currentConversation ?: return
        viewModelScope.launch {
            // Compress images first (max 4)
            val limitedUris = imageUris.take(ImageCompressor.MAX_IMAGES_PER_MESSAGE)
            val compressed = mutableListOf<CompressedImage>()
            for (uri in limitedUris) {
                try {
                    val ci = ImageCompressor.compressForVision(getApplication(), uri)
                    compressed.add(ci)
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = e.message) }
                }
            }
            if (compressed.isNotEmpty() && !settingsRepository.remoteEnabled) {
                _uiState.update { it.copy(errorMessage = "Vision needs Local Model enabled — turn it on in Settings to let the model see images. Image saved but will be described as text-only.") }
            }
            // Save user message immediately so queued messages appear in chat
            val saved: MessageWithAttachments = if (compressed.isNotEmpty()) {
                chatRepository.addMessageWithAttachments(conversation.id, "user", displayText, compressed)
            } else {
                val msg = chatRepository.addMessage(conversation.id, "user", displayText)
                MessageWithAttachments(msg, emptyList())
            }
            if (conversation.title.startsWith("Chat ") || conversation.title == "New Chat") {
                val titleBase = displayText.ifBlank { if (compressed.isNotEmpty()) "Image ${compressed.size} 📷" else "New Chat" }
                val autoTitle = titleBase.take(40).let { if (titleBase.length > 40) "$it..." else it }
                val updated = conversation.copy(title = autoTitle)
                chatRepository.updateConversation(updated)
                _uiState.update { it.copy(currentConversation = updated) }
            }
            // If already generating or queue not empty, enqueue for later (queuing allowed during generation, not during compaction)
            if (_uiState.value.isGenerating || generationQueue.isNotEmpty()) {
                generationQueue.addLast(saved.message.id)
                updateQueuedCount()
                Log.i("ChatViewModel", "Queued ${saved.message.id} while generating, queue=${generationQueue.size}")
                return@launch
            }
            // No queue — start generation immediately for this message
            launchGenerationForMessage(conversation.id, saved.message.id, displayText, compressed)
        }
    }

    private fun launchGenerationForMessage(conversationId: String, triggerMessageId: String, triggerContent: String, triggerCompressed: List<CompressedImage>) {
        viewModelScope.launch {
            // Compaction check before each generation
            if (settingsRepository.compactionEnabled) {
                try {
                    maybeCompactBeforeGeneration(conversationId, getSystemPrompt())
                    if (!settingsRepository.remoteEnabled) {
                        val convAfter = chatRepository.getConversation(conversationId)
                        if (!convAfter?.compactionSummary.isNullOrBlank() && inferenceEngine.isModelLoaded.get()) {
                            val keep = settingsRepository.compactionKeepRecent.coerceIn(4, 20)
                            val enrichedForLoad = chatRepository.getMessagesWithAttachmentsSync(conversationId).takeLast(keep)
                            val historyForLoad = enrichedForLoad.map { it.message.role to it.message.content }
                            val systemForLoad = getCompactionAugmentedPrompt(getSystemPrompt(), conversationId)
                            try {
                                modelManager.unloadModel()
                                _uiState.update { it.copy(contextUsed = 0) }
                                val modelId = settingsRepository.activeModelId
                                if (modelId != -1L) {
                                    suspendCancellableCoroutine<Unit> { cont ->
                                        viewModelScope.launch {
                                            modelManager.loadModel(
                                                modelId = modelId,
                                                systemPrompt = systemForLoad,
                                                conversationHistory = historyForLoad,
                                                onSuccess = { cont.resume(Unit) },
                                                onError = { e -> cont.resumeWithException(e) }
                                            )
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("ChatViewModel", "Compaction local reload failed: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Compaction check failed: ${e.message}")
                }
            }
            if (_uiState.value.isCompacting) {
                // Compaction started — re-queue and wait
                generationQueue.addFirst(triggerMessageId)
                updateQueuedCount()
                // Retry after compaction
                viewModelScope.launch {
                    var tries = 0
                    while (_uiState.value.isCompacting && tries < 50) {
                        delay(200)
                        tries++
                    }
                    if (!_uiState.value.isCompacting) {
                        val next = if (generationQueue.isNotEmpty() && generationQueue.first() == triggerMessageId) generationQueue.removeFirst() else triggerMessageId
                        updateQueuedCount()
                        val dataUrls = getImageDataUrlsForMessage(triggerMessageId)
                        launchGenerationForQueued(conversationId, triggerMessageId, triggerContent, dataUrls)
                    }
                }
                return@launch
            }
            _uiState.update { it.copy(isGenerating = true, partialResponse = "", partialReasoning = "", tokensPerSecond = null) }
            val queryDataUrls = triggerCompressed.mapNotNull { ci ->
                try { ImageCompressor.toBase64DataUrl(ci.file) } catch (e: Exception) { null }
            }
            val (history, historyImages) = buildHistoryForTrigger(conversationId, triggerMessageId)
            if (queryDataUrls.isNotEmpty() && !settingsRepository.remoteEnabled) {
                val imageNotice = "\n\n[User attached ${queryDataUrls.size} image(s) — vision is off, so describe that you cannot see images and ask to enable Local Model.]"
                val queryForLocal = triggerContent + imageNotice
                inferenceEngine.generateResponse(
                    query = queryForLocal,
                    onToken = { partial -> _uiState.update { it.copy(partialResponse = partial) } },

                                        onReasoning = { reasoning -> _uiState.update { it.copy(partialReasoning = reasoning) } },
                    onComplete = { result ->
                        viewModelScope.launch {
                            chatRepository.addMessage(conversationId, "assistant", result.response, reasoningContent = result.reasoning)
                            _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", tokensPerSecond = result.tokensPerSecond, contextUsed = result.contextLengthUsed, contextMax = settingsRepository.effectiveContextSize) }
                            processNextQueued()
                        }
                    },
                    onCancelled = {
                        val partial = _uiState.value.partialResponse
                        if (partial.isNotBlank()) { val reasoning = _uiState.value.partialReasoning; viewModelScope.launch { chatRepository.addMessage(conversationId, "assistant", partial, reasoningContent = reasoning.ifEmpty { null }) } }
                        _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "") }
                        processNextQueued()
                    },
                    onError = { e ->
                        _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", errorMessage = e.message) }
                        processNextQueued()
                    }
                )
                return@launch
            }
            if (settingsRepository.remoteEnabled) {
                val augmentedPromptBase = getAugmentedSystemPrompt(triggerContent.ifBlank { "Describe the images" })
                val augmentedPrompt = getCompactionAugmentedPrompt(augmentedPromptBase, conversationId)
                remoteInferenceEngine.generateResponse(
                    query = triggerContent,
                    queryImageDataUrls = queryDataUrls,
                    conversationHistory = history,
                    conversationHistoryImageDataUrls = historyImages,
                    systemPrompt = augmentedPrompt,
                    onToken = { partial -> _uiState.update { it.copy(partialResponse = partial) } },

                                        onReasoning = { reasoning -> _uiState.update { it.copy(partialReasoning = reasoning) } },
                    onComplete = { result ->
                        viewModelScope.launch {
                            chatRepository.addMessage(conversationId, "assistant", result.response, reasoningContent = result.reasoning)
                            _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", tokensPerSecond = result.tokensPerSecond) }
                            processNextQueued()
                        }
                    },
                    onCancelled = {
                        val partial = _uiState.value.partialResponse
                        if (partial.isNotBlank()) { val reasoning = _uiState.value.partialReasoning; viewModelScope.launch { chatRepository.addMessage(conversationId, "assistant", partial, reasoningContent = reasoning.ifEmpty { null }) } }
                        _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "") }
                        processNextQueued()
                    },
                    onError = { e ->
                        _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", errorMessage = "Remote: ${e.message}") }
                        processNextQueued()
                    }
                )
                return@launch
            }
            inferenceEngine.generateResponse(
                query = triggerContent,
                onToken = { partial -> _uiState.update { it.copy(partialResponse = partial) } },

                                    onReasoning = { reasoning -> _uiState.update { it.copy(partialReasoning = reasoning) } },
                onComplete = { result ->
                    viewModelScope.launch {
                        chatRepository.addMessage(conversationId, "assistant", result.response, reasoningContent = result.reasoning)
                        _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", tokensPerSecond = result.tokensPerSecond, contextUsed = result.contextLengthUsed, contextMax = settingsRepository.effectiveContextSize) }
                        processNextQueued()
                    }
                },
                onCancelled = {
                    val partial = _uiState.value.partialResponse
                    if (partial.isNotBlank()) { val reasoning = _uiState.value.partialReasoning; viewModelScope.launch { chatRepository.addMessage(conversationId, "assistant", partial, reasoningContent = reasoning.ifEmpty { null }) } }
                    _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "") }
                    processNextQueued()
                },
                onError = { e ->
                    _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", errorMessage = e.message) }
                    processNextQueued()
                }
            )
        }
    }

    private suspend fun buildHistoryForTrigger(conversationId: String, triggerMessageId: String): Pair<List<Pair<String,String>>, List<List<String>>> {
        val enriched = chatRepository.getMessagesWithAttachmentsSync(conversationId)
        val idx = enriched.indexOfFirst { it.message.id == triggerMessageId }
        if (idx <= 0) return emptyList<Pair<String,String>>() to emptyList()
        val before = enriched.take(idx)
        val conv = try { chatRepository.getConversation(conversationId) } catch (_:Exception) { null }
        val hasCompaction = !conv?.compactionSummary.isNullOrBlank() && settingsRepository.compactionEnabled
        val keep = settingsRepository.compactionKeepRecent.coerceIn(4,20)
        val sliced = if (hasCompaction && before.size > keep) before.takeLast(keep) else before
        val history = sliced.map { it.message.role to it.message.content }
        var imagesToInclude = 12
        val reversed = sliced.reversed().map { mwa ->
            if (mwa.message.role == "user" && mwa.attachments.isNotEmpty() && imagesToInclude >0) {
                val urls = mwa.attachments.take(ImageCompressor.MAX_IMAGES_PER_MESSAGE).mapNotNull { att ->
                    try { ImageCompressor.uriToBase64DataUrl(getApplication(), att.localPath) } catch (_:Exception) { null }
                }.take(imagesToInclude)
                imagesToInclude -= urls.size
                urls
            } else emptyList()
        }.reversed()
        return history to reversed
    }

    private fun processNextQueued() {
        if (_uiState.value.isCompacting) {
            viewModelScope.launch {
                var tries = 0
                while (_uiState.value.isCompacting && tries < 50) {
                    delay(200)
                    tries++
                }
                if (!_uiState.value.isCompacting) processNextQueuedInner()
            }
            return
        }
        processNextQueuedInner()
    }
    private fun processNextQueuedInner() {
        if (_uiState.value.isGenerating) return
        if (generationQueue.isEmpty()) {
            updateQueuedCount()
            return
        }
        val nextId = generationQueue.removeFirst()
        updateQueuedCount()
        val conv = _uiState.value.currentConversation ?: return
        viewModelScope.launch {
            val all = chatRepository.getMessagesSync(conv.id)
            val msg = all.find { it.id == nextId }
            if (msg == null) {
                processNextQueued()
                return@launch
            }
            val dataUrls = getImageDataUrlsForMessage(nextId)
            launchGenerationForQueued(conv.id, nextId, msg.content, dataUrls)
        }
    }
    private fun launchGenerationForQueued(conversationId: String, triggerMessageId: String, triggerContent: String, triggerDataUrls: List<String>) {
        viewModelScope.launch {
            if (settingsRepository.compactionEnabled) {
                try { maybeCompactBeforeGeneration(conversationId, getSystemPrompt()) } catch (_:Exception) {}
            }
            if (_uiState.value.isCompacting) {
                generationQueue.addFirst(triggerMessageId)
                updateQueuedCount()
                viewModelScope.launch {
                    var tries = 0
                    while (_uiState.value.isCompacting && tries < 50) { delay(200); tries++ }
                    if (!_uiState.value.isCompacting) {
                        val nid = if (generationQueue.isNotEmpty() && generationQueue.first() == triggerMessageId) generationQueue.removeFirst() else triggerMessageId
                        updateQueuedCount()
                        val urls = getImageDataUrlsForMessage(nid)
                        val all2 = chatRepository.getMessagesSync(conversationId)
                        val msg2 = all2.find { it.id == nid } ?: return@launch
                        launchGenerationForQueued(conversationId, nid, msg2.content, urls)
                    }
                }
                return@launch
            }
            _uiState.update { it.copy(isGenerating = true, partialResponse = "", partialReasoning = "", tokensPerSecond = null) }
            val (history, historyImages) = buildHistoryForTrigger(conversationId, triggerMessageId)
            if (triggerDataUrls.isNotEmpty() && !settingsRepository.remoteEnabled) {
                val imageNotice = "\n\n[User attached ${triggerDataUrls.size} image(s) — vision is off, so describe that you cannot see images and ask to enable Local Model.]"
                val queryForLocal = triggerContent + imageNotice
                inferenceEngine.generateResponse(
                    query = queryForLocal,
                    onToken = { partial -> _uiState.update { it.copy(partialResponse = partial) } },

                                        onReasoning = { reasoning -> _uiState.update { it.copy(partialReasoning = reasoning) } },
                    onComplete = { result ->
                        viewModelScope.launch {
                            chatRepository.addMessage(conversationId, "assistant", result.response, reasoningContent = result.reasoning)
                            _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", tokensPerSecond = result.tokensPerSecond, contextUsed = result.contextLengthUsed, contextMax = settingsRepository.effectiveContextSize) }
                            processNextQueued()
                        }
                    },
                    onCancelled = {
                        val partial = _uiState.value.partialResponse
                        if (partial.isNotBlank()) { val reasoning = _uiState.value.partialReasoning; viewModelScope.launch { chatRepository.addMessage(conversationId, "assistant", partial, reasoningContent = reasoning.ifEmpty { null }) } }
                        _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "") }
                        processNextQueued()
                    },
                    onError = { e -> _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", errorMessage = e.message) }; processNextQueued() }
                )
                return@launch
            }
            if (settingsRepository.remoteEnabled) {
                val augmentedPromptBase = getAugmentedSystemPrompt(triggerContent.ifBlank { "Describe the images" })
                val augmentedPrompt = getCompactionAugmentedPrompt(augmentedPromptBase, conversationId)
                remoteInferenceEngine.generateResponse(
                    query = triggerContent,
                    queryImageDataUrls = triggerDataUrls,
                    conversationHistory = history,
                    conversationHistoryImageDataUrls = historyImages,
                    systemPrompt = augmentedPrompt,
                    onToken = { partial -> _uiState.update { it.copy(partialResponse = partial) } },

                                        onReasoning = { reasoning -> _uiState.update { it.copy(partialReasoning = reasoning) } },
                    onComplete = { result ->
                        viewModelScope.launch {
                            chatRepository.addMessage(conversationId, "assistant", result.response, reasoningContent = result.reasoning)
                            _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", tokensPerSecond = result.tokensPerSecond) }
                            processNextQueued()
                        }
                    },
                    onCancelled = {
                        val partial = _uiState.value.partialResponse
                        if (partial.isNotBlank()) { val reasoning = _uiState.value.partialReasoning; viewModelScope.launch { chatRepository.addMessage(conversationId, "assistant", partial, reasoningContent = reasoning.ifEmpty { null }) } }
                        _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "") }
                        processNextQueued()
                    },
                    onError = { e -> _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", errorMessage = "Remote: ${e.message}") }; processNextQueued() }
                )
                return@launch
            }
            inferenceEngine.generateResponse(
                query = triggerContent,
                onToken = { partial -> _uiState.update { it.copy(partialResponse = partial) } },

                                    onReasoning = { reasoning -> _uiState.update { it.copy(partialReasoning = reasoning) } },
                onComplete = { result ->
                    viewModelScope.launch {
                        chatRepository.addMessage(conversationId, "assistant", result.response, reasoningContent = result.reasoning)
                        _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", tokensPerSecond = result.tokensPerSecond, contextUsed = result.contextLengthUsed, contextMax = settingsRepository.effectiveContextSize) }
                        processNextQueued()
                    }
                },
                onCancelled = {
                    val partial = _uiState.value.partialResponse
                    if (partial.isNotBlank()) { val reasoning = _uiState.value.partialReasoning; viewModelScope.launch { chatRepository.addMessage(conversationId, "assistant", partial, reasoningContent = reasoning.ifEmpty { null }) } }
                    _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "") }
                    processNextQueued()
                },
                onError = { e -> _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", errorMessage = e.message) }; processNextQueued() }
            )
        }
    }

    fun editMessage(messageId: String, newContent: String) {
        if (_uiState.value.isCompacting) {
            _uiState.update { it.copy(errorMessage = "Compacting conversation — please wait before editing.") }
            return
        }
        generationQueue.clear()
        updateQueuedCount()
        val sanitized = SecurityUtils.sanitizePrompt(newContent)
        if (sanitized.isBlank()) return
        val conversation = _uiState.value.currentConversation ?: return
        viewModelScope.launch {
            val all = chatRepository.getMessagesSync(conversation.id)
            val target = all.find { it.id == messageId } ?: return@launch
            if (target.role != "user") return@launch
            stopGeneration()
            chatRepository.updateMessageContent(messageId, sanitized)
            chatRepository.deleteMessagesAfter(conversation.id, messageId)
            triggerRegeneration(conversation, editedUserId = messageId, editedContent = sanitized)
        }
    }

    fun regenerateFromMessage(messageId: String) {
        if (_uiState.value.isCompacting) {
            _uiState.update { it.copy(errorMessage = "Compacting conversation — please wait before regenerating.") }
            return
        }
        generationQueue.clear()
        updateQueuedCount()
        val conversation = _uiState.value.currentConversation ?: return
        viewModelScope.launch {
            val all = chatRepository.getMessagesSync(conversation.id)
            val target = all.find { it.id == messageId } ?: return@launch
            stopGeneration()
            if (target.role == "user") {
                chatRepository.deleteMessagesAfter(conversation.id, target.id)
                triggerRegeneration(conversation, editedUserId = target.id, editedContent = target.content)
            } else {
                val idx = all.indexOf(target)
                val prevUser = all.take(idx).lastOrNull { it.role == "user" } ?: return@launch
                chatRepository.deleteMessagesAfter(conversation.id, prevUser.id)
                triggerRegeneration(conversation, editedUserId = prevUser.id, editedContent = prevUser.content)
            }
        }
    }

    fun regenerateLastResponse() {
        val conversation = _uiState.value.currentConversation ?: return
        viewModelScope.launch {
            val all = chatRepository.getMessagesSync(conversation.id)
            if (all.isEmpty()) return@launch
            val lastAssistant = all.lastOrNull { it.role == "assistant" }
            if (lastAssistant != null) {
                regenerateFromMessage(lastAssistant.id)
            } else {
                val lastUser = all.lastOrNull { it.role == "user" } ?: return@launch
                regenerateFromMessage(lastUser.id)
            }
        }
    }

    private suspend fun triggerRegeneration(conversation: Conversation, editedUserId: String, editedContent: String) {
        val sanitized = SecurityUtils.sanitizePrompt(editedContent)
        if (sanitized.isBlank()) return
        _uiState.update {
            it.copy(isGenerating = true, partialResponse = "", partialReasoning = "", tokensPerSecond = null, errorMessage = null)
        }
        // Compaction check after edit (opt-in, server-driven)
        if (settingsRepository.compactionEnabled) {
            try { maybeCompactBeforeGeneration(conversation.id, getSystemPrompt()) } catch (_: Exception) {}
        }
        val allAfterEdit = chatRepository.getMessagesSync(conversation.id)
        val idx = allAfterEdit.indexOfFirst { it.id == editedUserId }
        val historyBefore = if (idx > 0) allAfterEdit.take(idx).map { it.role to it.content } else emptyList()
        // Build image lists for historyBefore
        val enrichedBefore = chatRepository.getMessagesWithAttachmentsSync(conversation.id)
        val historyBeforeEnriched = enrichedBefore.filter { mwa -> historyBefore.any { it.second == mwa.message.content && it.first == mwa.message.role } }
        // Simpler: reuse buildHistoryWithImages but exclude edited user
        val allEnriched = chatRepository.getMessagesWithAttachmentsSync(conversation.id)
        val idxEnriched = allEnriched.indexOfFirst { it.message.id == editedUserId }
        val rawHistoryBeforeList = if (idxEnriched > 0) allEnriched.take(idxEnriched) else emptyList()
        // Truncate if compaction active
        val convAfter = try { chatRepository.getConversation(conversation.id) } catch (_: Exception) { null }
        val hasCompaction = !convAfter?.compactionSummary.isNullOrBlank() && settingsRepository.compactionEnabled
        val keep = settingsRepository.compactionKeepRecent.coerceIn(4, 20)
        val historyBeforeList = if (hasCompaction && rawHistoryBeforeList.size > keep) rawHistoryBeforeList.takeLast(keep) else rawHistoryBeforeList
        val historyBeforePairs = historyBeforeList.map { it.message.role to it.message.content }
        val historyBeforeImages = historyBeforeList.map { mwa ->
            if (mwa.message.role == "user") {
                mwa.attachments.take(ImageCompressor.MAX_IMAGES_PER_MESSAGE).mapNotNull { att ->
                    try { ImageCompressor.uriToBase64DataUrl(getApplication(), att.localPath) } catch (_: Exception) { null }
                }
            } else emptyList()
        }

        val editedDataUrls = getImageDataUrlsForMessage(editedUserId)

        val augmentedBase = getAugmentedSystemPrompt(sanitized)
        val augmentedPrompt = getCompactionAugmentedPrompt(augmentedBase, conversation.id)

        if (settingsRepository.remoteEnabled) {
            remoteInferenceEngine.generateResponse(
                query = sanitized,
                queryImageDataUrls = editedDataUrls,
                conversationHistory = historyBeforePairs,
                conversationHistoryImageDataUrls = historyBeforeImages,
                systemPrompt = augmentedPrompt,
                onToken = { partial -> _uiState.update { it.copy(partialResponse = partial) } },

                                    onReasoning = { reasoning -> _uiState.update { it.copy(partialReasoning = reasoning) } },
                onComplete = { result ->
                    viewModelScope.launch {
                        chatRepository.addMessage(conversation.id, "assistant", result.response, reasoningContent = result.reasoning)
                        _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", tokensPerSecond = result.tokensPerSecond) }
                    }
                },
                onCancelled = {
                    val partial = _uiState.value.partialResponse
                    if (partial.isNotBlank()) { val reasoning = _uiState.value.partialReasoning; viewModelScope.launch { chatRepository.addMessage(conversation.id, "assistant", partial, reasoningContent = reasoning.ifEmpty { null }) } }
                    _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "") }
                },
                onError = { e ->
                    _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", errorMessage = "Remote: ${e.message}") }
                }
            )
            return
        }

        try {
            val systemPromptBase = getSystemPrompt()
            val systemPrompt = getCompactionAugmentedPrompt(systemPromptBase, conversation.id)
            modelManager.unloadModel()
            _uiState.update { it.copy(contextUsed = 0) }
            val historyForLoad = if (historyBeforeList.isNotEmpty()) historyBeforeList.map { it.message.role to it.message.content } else historyBefore
            val modelId = settingsRepository.activeModelId
            if (modelId != -1L) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    viewModelScope.launch {
                        modelManager.loadModel(
                            modelId = modelId,
                            systemPrompt = systemPrompt,
                            conversationHistory = historyForLoad,
                            onSuccess = { cont.resume(Unit, null) },
                            onError = { e -> cont.resumeWithException(e) }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isGenerating = false, errorMessage = e.message) }
            return
        }

        inferenceEngine.generateResponse(
            query = sanitized,
            onToken = { partial -> _uiState.update { it.copy(partialResponse = partial) } },

                                onReasoning = { reasoning -> _uiState.update { it.copy(partialReasoning = reasoning) } },
            onComplete = { result ->
                viewModelScope.launch {
                    chatRepository.addMessage(conversation.id, "assistant", result.response, reasoningContent = result.reasoning)
                    _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", tokensPerSecond = result.tokensPerSecond, contextUsed = result.contextLengthUsed, contextMax = settingsRepository.effectiveContextSize) }
                }
            },
            onCancelled = {
                val partial = _uiState.value.partialResponse
                if (partial.isNotBlank()) { val reasoning = _uiState.value.partialReasoning; viewModelScope.launch { chatRepository.addMessage(conversation.id, "assistant", partial, reasoningContent = reasoning.ifEmpty { null }) } }
                _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "") }
            },
            onError = { e -> _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "", errorMessage = e.message) } }
        )
    }

    fun stopGeneration() {
        inferenceEngine.stopGeneration()
        remoteInferenceEngine.stopGeneration()
        _uiState.update { it.copy(isGenerating = false, partialReasoning = "") }
    }

    fun newConversation() {
        generationQueue.clear()
        updateQueuedCount()
        navigationJob?.cancel()
        navigationJob = viewModelScope.launch {
            val partial = _uiState.value.partialResponse
            val currentConv = _uiState.value.currentConversation
            if (partial.isNotBlank() && currentConv != null) { val reasoning = _uiState.value.partialReasoning; chatRepository.addMessage(currentConv.id, "assistant", partial, reasoningContent = reasoning.ifEmpty { null }) }
            _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "") }

            modelManager.unloadModel()
            _uiState.update { it.copy(contextUsed = 0) }

            val conversation = chatRepository.createConversation(
                title = "Chat ${chatRepository.getConversationCount() + 1}"
            )
            _uiState.update {
                it.copy(
                    currentConversation = conversation,
                    showConversationDrawer = false,
                )
            }
            loadModelForConversation(conversation)
        }
    }

    fun switchConversation(conversation: Conversation) {
        if (conversation.id == _uiState.value.currentConversation?.id) {
            _uiState.update { it.copy(showConversationDrawer = false) }
            return
        }
        generationQueue.clear()
        updateQueuedCount()
        navigationJob?.cancel()
        navigationJob = viewModelScope.launch {
            val partial = _uiState.value.partialResponse
            val currentConv = _uiState.value.currentConversation
            if (partial.isNotBlank() && currentConv != null) { val reasoning = _uiState.value.partialReasoning; chatRepository.addMessage(currentConv.id, "assistant", partial, reasoningContent = reasoning.ifEmpty { null }) }
            _uiState.update { it.copy(isGenerating = false, partialResponse = "", partialReasoning = "") }

            modelManager.unloadModel()
            _uiState.update { it.copy(contextUsed = 0) }
            _uiState.update {
                it.copy(
                    currentConversation = conversation,
                    showConversationDrawer = false,
                )
            }
            loadModelForConversation(conversation)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            chatRepository.deleteConversation(conversationId)
            if (_uiState.value.currentConversation?.id == conversationId) {
                val next = chatRepository.getMostRecentConversation()
                    ?: chatRepository.createConversation()
                _uiState.update { it.copy(currentConversation = next) }
                loadModelForConversation(next)
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessage(messageId)
        }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            val conversation = chatRepository.getConversation(conversationId) ?: return@launch
            val updated = conversation.copy(title = newTitle.trim(), updatedAt = System.currentTimeMillis())
            chatRepository.updateConversation(updated)
            if (_uiState.value.currentConversation?.id == conversationId) {
                _uiState.update { it.copy(currentConversation = updated) }
            }
        }
    }

    fun toggleDrawer() {
        _uiState.update { it.copy(showConversationDrawer = !it.showConversationDrawer) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    suspend fun exportChats(): String {
        val conversations = _uiState.value.conversations
        val exportedChats = conversations.map { conv ->
            val messages = chatRepository.getMessagesSync(conv.id)
            ExportedChat(
                title = conv.title,
                systemPromptKey = conv.systemPromptKey,
                createdAt = conv.createdAt,
                messages = messages.map { msg ->
                    ExportedMessage(
                        role = msg.role,
                        content = msg.content,
                        timestamp = msg.timestamp,
                        reasoningContent = msg.reasoningContent,
                    )
                }
            )
        }
        val data = ExportData(chats = exportedChats)
        return exportJson.encodeToString(data)
    }

    fun speakMessage(messageId: String, text: String) {
        _uiState.update { it.copy(speakingMessageId = messageId) }
        ttsHelper.speak(text) {
            _uiState.update { it.copy(speakingMessageId = null) }
        }
    }

    fun stopSpeaking() {
        ttsHelper.stop()
        _uiState.update { it.copy(speakingMessageId = null) }
    }

    override fun onCleared() {
        super.onCleared()
        memoryMonitor.stopMonitoring()
        ttsHelper.shutdown()
    }
}
