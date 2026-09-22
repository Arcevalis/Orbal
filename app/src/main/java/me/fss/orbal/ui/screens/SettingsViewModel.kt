package me.fss.orbal.ui.screens

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import me.fss.orbal.ai.ModelManager
import me.fss.orbal.ai.remote.LmStudioClient
import me.fss.orbal.data.local.entities.ModelInfo
import me.fss.orbal.data.repository.ChatRepository
import me.fss.orbal.data.repository.ExportData
import me.fss.orbal.data.repository.ExportedChat
import me.fss.orbal.data.repository.ExportedMessage
import me.fss.orbal.data.repository.SettingsRepository
import me.fss.orbal.ui.theme.ThemeMode
import me.fss.orbal.utils.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SettingsUiState(
    val models: List<ModelInfo> = emptyList(),
    val activeModel: ModelInfo? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val contextSize: Int = 4096,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val minP: Float = 0.1f,
    val repeatPenalty: Float = 1.1f,
    val biometricLock: Boolean = false,
    val autoLockOnBackground: Boolean = false,
    val screenshotProtectionEnabled: Boolean = false,
    val tapjackingProtectionEnabled: Boolean = true,
    val sensitiveDataAccessibilityEnabled: Boolean = true,
    val secureStorageBackend: String = "Unknown",
    val systemPromptKey: String = "default",
    val customSystemPrompt: String = "",
    val themeMode: String = "AMOLED",
    val accentColor: String = "blue",
    val disableThinking: Boolean = true,
    val mathLatexHints: Boolean = false,
    val translatorFrom: String = "en",
    val translatorTo: String = "es",
    val catppuccinAccent: String = "mauve",
    val draculaAccent: String = "purple",
    val catppuccinFlavor: String = "mocha",
    val ptyxisPalette: String = "cobalt_neon",
    val monochromeAccents: Boolean = false,
    val appFont: String = "quicksand",
    val fontScale: Float = 1.0f,
    val gpuLayers: Int = 99,
    val useGpu: Boolean = false,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val kvCacheQ8: Boolean = false,
    val numThreads: Int = 4,
    val gpuDeviceName: String = "",
    val backendInfo: String = "",
    val isImportingModel: Boolean = false,
    // Remote Local Model
    val remoteEnabled: Boolean = false,
    val remoteBaseUrl: String = SettingsRepository.DEFAULT_REMOTE_BASE_URL,
    val remoteApiKey: String = "",
    val remoteModelId: String = "",
    val remoteModels: List<String> = emptyList(),
    val remoteStatus: String? = null,
    val isFetchingRemoteModels: Boolean = false,
    // Tools (Standard)
    val toolsEnabled: Boolean = SettingsRepository.DEFAULT_TOOLS_ENABLED,
    val enabledTools: Set<String> = SettingsRepository.DEFAULT_ENABLED_TOOLS,
    val maxToolCalls: Int = SettingsRepository.DEFAULT_MAX_TOOL_CALLS,
    // Compaction (Standard)
    val compactionEnabled: Boolean = SettingsRepository.DEFAULT_COMPACTION_ENABLED,
    val compactionThreshold: Float = SettingsRepository.DEFAULT_COMPACTION_THRESHOLD,
    val compactionKeepRecent: Int = SettingsRepository.DEFAULT_COMPACTION_KEEP_RECENT,
    val serverCtx: Int = 0,
    val effectiveCtx: Int = 4096,
)
class SettingsViewModel(
    private val application: Application,
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val modelManager: ModelManager,
    private val lmStudioClient: LmStudioClient,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(loadState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            chatRepository.getAllModels().collect { models ->
                val activeId = settingsRepository.activeModelId
                _uiState.update {
                    it.copy(
                        models = models,
                        activeModel = models.find { m -> m.id == activeId }
                    )
                }
            }
        }
        // Off the main thread: first call may initialise the Vulkan instance.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val gpuName = modelManager.getGpuDeviceInfo()
            // Same backend registry, already loaded by the call above.
            val backends = modelManager.getBackendInfo()
            _uiState.update { it.copy(gpuDeviceName = gpuName, backendInfo = backends) }
        }
    }

    private fun loadState(): SettingsUiState {
        return SettingsUiState(
            temperature = settingsRepository.temperature,
            maxTokens = settingsRepository.maxTokens,
            contextSize = settingsRepository.contextSize,
            topP = settingsRepository.topP,
            topK = settingsRepository.topK,
            minP = settingsRepository.minP,
            repeatPenalty = settingsRepository.repeatPenalty,
            biometricLock = settingsRepository.biometricLock,
            autoLockOnBackground = settingsRepository.autoLockOnBackgroundEnabled,
            screenshotProtectionEnabled = settingsRepository.screenshotProtectionEnabled,
            tapjackingProtectionEnabled = settingsRepository.tapjackingProtectionEnabled,
            sensitiveDataAccessibilityEnabled = settingsRepository.sensitiveDataAccessibilityEnabled,
            secureStorageBackend = settingsRepository.secureStorageBackend,
            systemPromptKey = settingsRepository.systemPromptKey,
            customSystemPrompt = settingsRepository.customSystemPrompt,
            themeMode = settingsRepository.themeMode,
            accentColor = settingsRepository.accentColor,
            disableThinking = settingsRepository.disableThinking,
            mathLatexHints = settingsRepository.mathLatexHints,
            translatorFrom = settingsRepository.translatorFrom,
            translatorTo = settingsRepository.translatorTo,
            catppuccinAccent = settingsRepository.catppuccinAccent,
            draculaAccent = settingsRepository.draculaAccent,
            catppuccinFlavor = settingsRepository.catppuccinFlavor,
            ptyxisPalette = settingsRepository.ptyxisPalette,
            monochromeAccents = settingsRepository.monochromeAccents,
            appFont = settingsRepository.appFont,
            fontScale = settingsRepository.fontScale,
            gpuLayers = settingsRepository.gpuLayers,
            useGpu = settingsRepository.useGpu,
            useMmap = settingsRepository.useMmap,
            useMlock = settingsRepository.useMlock,
            kvCacheQ8 = settingsRepository.kvCacheQ8,
            numThreads = settingsRepository.numThreads,
            remoteEnabled = settingsRepository.remoteEnabled,
            remoteBaseUrl = settingsRepository.remoteBaseUrl,
            remoteApiKey = settingsRepository.remoteApiKey,
            remoteModelId = settingsRepository.remoteModelId,
            toolsEnabled = settingsRepository.toolsEnabled,
            enabledTools = settingsRepository.enabledTools,
            maxToolCalls = settingsRepository.maxToolCalls,
            compactionEnabled = settingsRepository.compactionEnabled,
            compactionThreshold = settingsRepository.compactionThreshold,
            compactionKeepRecent = settingsRepository.compactionKeepRecent,
            serverCtx = settingsRepository.serverReportedCtx,
            effectiveCtx = settingsRepository.effectiveContextSize,
        )
    }

    fun setTemperature(value: Float) {
        settingsRepository.temperature = value
        _uiState.update { it.copy(temperature = value) }
    }

    fun setMaxTokens(value: Int) {
        settingsRepository.maxTokens = value
        _uiState.update { it.copy(maxTokens = value) }
    }

    fun setContextSize(value: Int) {
        settingsRepository.contextSize = value
        _uiState.update { it.copy(contextSize = value) }
    }

    fun setTopP(value: Float) {
        settingsRepository.topP = value
        _uiState.update { it.copy(topP = value) }
    }

    fun setTopK(value: Int) {
        settingsRepository.topK = value
        _uiState.update { it.copy(topK = value) }
    }

    fun setMinP(value: Float) {
        settingsRepository.minP = value
        _uiState.update { it.copy(minP = value) }
    }

    fun setRepeatPenalty(value: Float) {
        settingsRepository.repeatPenalty = value
        _uiState.update { it.copy(repeatPenalty = value) }
    }

    fun setBiometricLock(enabled: Boolean) {
        settingsRepository.biometricLock = enabled
        _uiState.update { it.copy(biometricLock = enabled) }
    }

    fun setAutoLockOnBackground(enabled: Boolean) {
        settingsRepository.autoLockOnBackgroundEnabled = enabled
        _uiState.update { it.copy(autoLockOnBackground = enabled) }
    }

    fun setScreenshotProtectionEnabled(enabled: Boolean) {
        settingsRepository.screenshotProtectionEnabled = enabled
        _uiState.update { it.copy(screenshotProtectionEnabled = enabled) }
    }

    fun setTapjackingProtectionEnabled(enabled: Boolean) {
        settingsRepository.tapjackingProtectionEnabled = enabled
        _uiState.update { it.copy(tapjackingProtectionEnabled = enabled) }
    }

    fun setSensitiveDataAccessibilityEnabled(enabled: Boolean) {
        settingsRepository.sensitiveDataAccessibilityEnabled = enabled
        _uiState.update { it.copy(sensitiveDataAccessibilityEnabled = enabled) }
    }

    fun setSystemPrompt(key: String) {
        settingsRepository.systemPromptKey = key
        _uiState.update { it.copy(systemPromptKey = key) }
    }

    fun setCustomSystemPrompt(value: String) {
        settingsRepository.customSystemPrompt = value
        _uiState.update { it.copy(customSystemPrompt = value) }
    }

    fun setTheme(mode: ThemeMode) {
        settingsRepository.themeMode = mode.name
        _uiState.update { it.copy(themeMode = mode.name) }
    }

    fun setAccentColor(key: String) {
        settingsRepository.accentColor = key
        _uiState.update { it.copy(accentColor = key) }
    }

    fun setDisableThinking(disabled: Boolean) {
        settingsRepository.disableThinking = disabled
        _uiState.update { it.copy(disableThinking = disabled) }
    }

    fun setMathLatexHints(enabled: Boolean) {
        settingsRepository.mathLatexHints = enabled
        _uiState.update { it.copy(mathLatexHints = enabled) }
    }

    fun setTranslatorFrom(code: String) {
        settingsRepository.translatorFrom = code
        _uiState.update { it.copy(translatorFrom = code) }
    }

    fun setTranslatorTo(code: String) {
        settingsRepository.translatorTo = code
        _uiState.update { it.copy(translatorTo = code) }
    }

    fun setCatppuccinAccent(key: String) {
        settingsRepository.catppuccinAccent = key
        _uiState.update { it.copy(catppuccinAccent = key) }
    }

    fun setDraculaAccent(key: String) {
        settingsRepository.draculaAccent = key
        _uiState.update { it.copy(draculaAccent = key) }
    }

    fun setCatppuccinFlavor(key: String) {
        settingsRepository.catppuccinFlavor = key
        _uiState.update { it.copy(catppuccinFlavor = key) }
    }

    fun setPtyxisPalette(key: String) {
        settingsRepository.ptyxisPalette = key
        _uiState.update { it.copy(ptyxisPalette = key) }
    }

    fun setMonochromeAccents(enabled: Boolean) {
        settingsRepository.monochromeAccents = enabled
        _uiState.update { it.copy(monochromeAccents = enabled) }
    }

    fun setAppFont(key: String) {
        settingsRepository.appFont = key
        _uiState.update { it.copy(appFont = key) }
    }

    fun setFontScale(value: Float) {
        settingsRepository.fontScale = value
        _uiState.update { it.copy(fontScale = value) }
    }

    fun setGpuLayers(value: Int) {
        settingsRepository.gpuLayers = value
        _uiState.update { it.copy(gpuLayers = value) }
    }

    fun setUseGpu(enabled: Boolean) {
        settingsRepository.useGpu = enabled
        _uiState.update { it.copy(useGpu = enabled) }
    }

    fun setUseMmap(enabled: Boolean) {
        settingsRepository.useMmap = enabled
        _uiState.update { it.copy(useMmap = enabled) }
    }

    fun setUseMlock(enabled: Boolean) {
        settingsRepository.useMlock = enabled
        _uiState.update { it.copy(useMlock = enabled) }
    }

    fun setKvCacheQ8(enabled: Boolean) {
        settingsRepository.kvCacheQ8 = enabled
        _uiState.update { it.copy(kvCacheQ8 = enabled) }
    }

    fun setNumThreads(value: Int) {
        settingsRepository.numThreads = value
        _uiState.update { it.copy(numThreads = value) }
    }

    fun selectModel(modelId: Long) {
        settingsRepository.activeModelId = modelId
        _uiState.update { state ->
            state.copy(activeModel = state.models.find { it.id == modelId })
        }
    }

    fun deleteModel(modelId: Long) {
        viewModelScope.launch {
            modelManager.deleteModel(modelId)
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImportingModel = true) }
            val result = modelManager.importModel(uri)
            _uiState.update { it.copy(isImportingModel = false) }
            result.fold(
                onSuccess = { model ->
                    Toast.makeText(application, "Model imported: ${model.name}", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(application, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    fun exportChats(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = Json { prettyPrint = true }
                val exportData = ExportData(chats = emptyList())
                val jsonString = json.encodeToString(exportData)
                FileUtils.writeTextToUri(application, uri, jsonString)
                Toast.makeText(application, "Chats exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(application, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun importChats(uri: Uri) {
        viewModelScope.launch {
            try {
                val jsonString = FileUtils.readTextFromUri(application, uri)
                if (jsonString != null) {
                    val result = chatRepository.importChatsFromJson(jsonString)
                    result.fold(
                        onSuccess = { count ->
                            Toast.makeText(application, "Imported $count chats", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { e ->
                            Toast.makeText(application, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            } catch (e: Exception) {
                Toast.makeText(application, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun clearAllChats() {
        viewModelScope.launch {
            chatRepository.deleteAllConversations()
            Toast.makeText(application, "All chats cleared", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Remote LM Studio ---
    fun setRemoteEnabled(enabled: Boolean) {
        settingsRepository.remoteEnabled = enabled
        _uiState.update { it.copy(remoteEnabled = enabled) }
        if (enabled) {
            viewModelScope.launch {
                val base = settingsRepository.remoteBaseUrl.ifBlank { _uiState.value.remoteBaseUrl }
                val key = settingsRepository.remoteApiKey
                if (base.isBlank()) {
                    _uiState.update { it.copy(remoteStatus = "Set Base URL first, then enable.") }
                    return@launch
                }
                _uiState.update { it.copy(isFetchingRemoteModels = true, remoteStatus = "Auto-detecting loaded model…") }
                val result = lmStudioClient.fetchLoadedModelIds(base, key)
                result.fold(
                    onSuccess = { ids ->
                        if (ids.isEmpty()) {
                            _uiState.update {
                                it.copy(
                                    remoteModels = emptyList(),
                                    isFetchingRemoteModels = false,
                                    remoteStatus = "Connected — no loaded models (load one on server)"
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    remoteModels = ids,
                                    isFetchingRemoteModels = false,
                                    remoteStatus = if (ids.size == 1) "Auto-selected loaded model: ${ids.first()}" else "Found ${ids.size} loaded model(s) — auto-selected ${ids.first()}"
                                )
                            }
                            val current = settingsRepository.remoteModelId
                            if (current.isBlank() || !ids.contains(current)) {
                                setRemoteModelId(ids.first())
                            } else {
                                // Current is already a loaded model, just refresh its context
                                val ctxRes = lmStudioClient.fetchModelContextLength(base, current, key)
                                ctxRes.onSuccess { ctx ->
                                    if (ctx > 512) {
                                        settingsRepository.serverReportedCtx = ctx
                                        settingsRepository.serverReportedCtxModel = current
                                        _uiState.update { it.copy(serverCtx = ctx, effectiveCtx = ctx) }
                                    }
                                }
                            }
                            Toast.makeText(application, "Auto-selected ${ids.first()}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(isFetchingRemoteModels = false, remoteStatus = "Auto-detect failed: ${e.message} — tap Refresh") }
                        Toast.makeText(application, "Auto-detect failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    fun setRemoteBaseUrl(value: String) {
        settingsRepository.remoteBaseUrl = value.trim()
        _uiState.update { it.copy(remoteBaseUrl = value) }
    }

    fun setRemoteApiKey(value: String) {
        settingsRepository.remoteApiKey = value.trim()
        _uiState.update { it.copy(remoteApiKey = value) }
    }

    fun setRemoteModelId(value: String) {
        settingsRepository.remoteModelId = value.trim()
        _uiState.update { it.copy(remoteModelId = value.trim(), remoteStatus = "Selected: $value") }
        // Auto-fetch server-reported context for this model (so compaction & bar match server)
        viewModelScope.launch {
            val base = settingsRepository.remoteBaseUrl
            val key = settingsRepository.remoteApiKey
            val ctxRes = lmStudioClient.fetchModelContextLength(base, value.trim(), key)
            ctxRes.onSuccess { ctx ->
                if (ctx > 512) {
                    settingsRepository.serverReportedCtx = ctx
                    settingsRepository.serverReportedCtxModel = value.trim()
                    _uiState.update { it.copy(serverCtx = ctx, effectiveCtx = ctx) }
                }
            }
        }
    }

    fun fetchRemoteModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingRemoteModels = true, remoteStatus = "Fetching models…") }
            // persist current text field in case user hasn't blurred
            val base = _uiState.value.remoteBaseUrl.ifBlank { settingsRepository.remoteBaseUrl }
            val key = _uiState.value.remoteApiKey
            val result = lmStudioClient.fetchModels(base, key)
            result.fold(
                onSuccess = { models ->
                    val ids = models.map { it.id }
                    _uiState.update {
                        it.copy(
                            remoteModels = ids,
                            isFetchingRemoteModels = false,
                            remoteStatus = if (ids.isEmpty()) "Connected — no models (load one on the server)" else "Found ${ids.size} model(s)"
                        )
                    }
                    // Auto-select first if none chosen
                    if (ids.isNotEmpty() && _uiState.value.remoteModelId.isBlank()) {
                        setRemoteModelId(ids.first())
                    }
                    // Also probe server context for the selected/first model
                    val modelForCtx = _uiState.value.remoteModelId.ifBlank { ids.firstOrNull() ?: "" }
                    if (modelForCtx.isNotBlank()) {
                        val ctxRes = lmStudioClient.fetchModelContextLength(base, modelForCtx, key)
                        ctxRes.onSuccess { ctx ->
                            if (ctx > 512) {
                                settingsRepository.serverReportedCtx = ctx
                                settingsRepository.serverReportedCtxModel = modelForCtx
                                _uiState.update { it.copy(serverCtx = ctx, effectiveCtx = ctx) }
                            }
                        }
                    }
                    Toast.makeText(application, "Found ${ids.size} remote model(s)", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isFetchingRemoteModels = false, remoteStatus = "Failed: ${e.message}") }
                    Toast.makeText(application, "Fetch failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    fun testRemoteConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(remoteStatus = "Testing…") }
            val base = _uiState.value.remoteBaseUrl.ifBlank { settingsRepository.remoteBaseUrl }
            val key = _uiState.value.remoteApiKey
            val result = lmStudioClient.testConnection(base, key)
            result.fold(
                onSuccess = { msg ->
                    _uiState.update { it.copy(remoteStatus = "✓ $msg") }
                    Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(remoteStatus = "✗ ${e.message}") }
                    Toast.makeText(application, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    // --- Tools ---
    fun setToolsEnabled(enabled: Boolean) {
        settingsRepository.toolsEnabled = enabled
        _uiState.update { it.copy(toolsEnabled = enabled) }
    }

    fun toggleTool(toolId: String, enabled: Boolean) {
        val current = _uiState.value.enabledTools.toMutableSet()
        if (enabled) current.add(toolId) else current.remove(toolId)
        settingsRepository.enabledTools = current
        _uiState.update { it.copy(enabledTools = current) }
    }

    fun setMaxToolCalls(value: Int) {
        val clamped = value.coerceIn(1, 10)
        settingsRepository.maxToolCalls = clamped
        _uiState.update { it.copy(maxToolCalls = clamped) }
    }

    // --- Compaction ---
    fun setCompactionEnabled(enabled: Boolean) {
        settingsRepository.compactionEnabled = enabled
        _uiState.update { it.copy(compactionEnabled = enabled) }
    }

    fun setCompactionThreshold(value: Float) {
        val clamped = value.coerceIn(0.6f, 0.95f)
        settingsRepository.compactionThreshold = clamped
        _uiState.update { it.copy(compactionThreshold = clamped) }
    }

    fun setCompactionKeepRecent(value: Int) {
        val clamped = value.coerceIn(4, 20)
        settingsRepository.compactionKeepRecent = clamped
        _uiState.update { it.copy(compactionKeepRecent = clamped) }
    }

    fun refreshServerCtx() {
        viewModelScope.launch {
            val base = _uiState.value.remoteBaseUrl.ifBlank { settingsRepository.remoteBaseUrl }
            val model = _uiState.value.remoteModelId.ifBlank { settingsRepository.remoteModelId }
            val key = _uiState.value.remoteApiKey
            if (base.isBlank() || model.isBlank()) {
                _uiState.update { it.copy(remoteStatus = "Need Base URL and Model ID to fetch server ctx") }
                return@launch
            }
            _uiState.update { it.copy(remoteStatus = "Fetching server context…") }
            val res = lmStudioClient.fetchModelContextLength(base, model, key)
            res.fold(
                onSuccess = { ctx ->
                    settingsRepository.serverReportedCtx = ctx
                    settingsRepository.serverReportedCtxModel = model
                    _uiState.update { it.copy(serverCtx = ctx, effectiveCtx = ctx, remoteStatus = "Server context: $ctx tokens for $model") }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(remoteStatus = "Ctx fetch failed: ${e.message}") }
                }
            )
        }
    }
}
