package me.fss.orbal.ai

import android.content.Context
import android.net.Uri
import me.fss.orbal.data.local.entities.ModelInfo
import me.fss.orbal.data.repository.ChatRepository
import me.fss.orbal.data.repository.SettingsRepository
import me.fss.orbal.smollm.GGUFReader
import me.fss.orbal.smollm.SmolLM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

class ModelManager(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val inferenceEngine: InferenceEngine,
) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    private val _copyProgress = MutableStateFlow(0f)
    val copyProgress: StateFlow<Float> = _copyProgress

    sealed class ModelState {
        data object NotLoaded : ModelState()
        data object Loading : ModelState()
        data object Ready : ModelState()
        data class Error(val message: String) : ModelState()
    }

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val modelState: StateFlow<ModelState> = _modelState

    suspend fun copyBundledModelIfNeeded(): Result<File> = withContext(Dispatchers.IO) {
        try {
            val assetModels = try {
                context.assets.list("model")?.filter { it.endsWith(".gguf") } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            if (assetModels.isEmpty()) {
                return@withContext Result.failure(Exception("No bundled model found in assets/model/"))
            }

            val assetFileName = assetModels.first()
            val modelFile = File(modelsDir, assetFileName)

            if (modelFile.exists()) {
                registerModelIfNeeded(modelFile, isBundled = true)
                return@withContext Result.success(modelFile)
            }

            context.assets.open("model/$assetFileName").use { input ->
                val totalSize = input.available().toLong()
                modelFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var copied = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        copied += bytesRead
                        if (totalSize > 0) {
                            _copyProgress.value = copied.toFloat() / totalSize.toFloat()
                        }
                    }
                }
            }

            _copyProgress.value = 1f
            registerModelIfNeeded(modelFile, isBundled = true)
            Result.success(modelFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importModel(uri: Uri): Result<ModelInfo> = withContext(Dispatchers.IO) {
        // Copy to a .part temp file and rename only after the copy is verified —
        // otherwise an interrupted import leaves a truncated .gguf with a valid
        // header that passes the magic check but fails at load with
        // "tensor data is not within the file bounds".
        val fileName = getFileNameFromUri(uri) ?: "imported_${System.currentTimeMillis()}.gguf"
        val tempFile = File(modelsDir, "$fileName.part")
        try {
            if (!fileName.endsWith(".gguf")) {
                return@withContext Result.failure(Exception("Only GGUF format models are supported"))
            }

            val expectedSize = getSizeFromUri(uri)

            val copiedBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: return@withContext Result.failure(Exception("Could not read selected file"))

            if (expectedSize != null && copiedBytes != expectedSize) {
                tempFile.delete()
                return@withContext Result.failure(
                    Exception(
                        "Import incomplete: copied $copiedBytes of $expectedSize bytes. " +
                        "Check free storage and try again."
                    )
                )
            }

            if (!validateGGUF(tempFile)) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Invalid GGUF file format"))
            }

            val destFile = File(modelsDir, fileName)
            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tempFile.renameTo(destFile)) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Could not finalise imported file"))
            }

            val modelInfo = registerModelIfNeeded(destFile, isBundled = false)
            Result.success(modelInfo)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    /** Size in bytes the source content provider declares for [uri], or null if unknown. */
    private fun getSizeFromUri(uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex).takeIf { it > 0 }
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadModel(
        modelId: Long,
        systemPrompt: String = "",
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {},
    ) {
        _modelState.value = ModelState.Loading

        val model = chatRepository.getModel(modelId)
        if (model == null) {
            _modelState.value = ModelState.Error("Model not found")
            onError(Exception("Model not found"))
            return
        }

        if (!File(model.path).exists()) {
            _modelState.value = ModelState.Error("Model file missing")
            onError(Exception("Model file missing at ${model.path}"))
            return
        }

        inferenceEngine.unloadModel()
        ensureBackendsLoaded()

        val params = SmolLM.InferenceParams(
            temperature = settingsRepository.temperature,
            topP = settingsRepository.topP,
            topK = settingsRepository.topK,
            minP = settingsRepository.minP,
            repeatPenalty = settingsRepository.repeatPenalty,
            contextSize = settingsRepository.contextSize.toLong(),
            numThreads = settingsRepository.numThreads,
            numThreadsBatch = Runtime.getRuntime().availableProcessors(),
            nGpuLayers = if (settingsRepository.useGpu) settingsRepository.gpuLayers else 0,
            useMmap = settingsRepository.useMmap,
            useMlock = settingsRepository.useMlock,
            kvCacheQ8 = settingsRepository.kvCacheQ8,
        )

        inferenceEngine.loadModel(
            modelPath = model.path,
            params = params,
            systemPrompt = systemPrompt,
            conversationHistory = conversationHistory,
            onSuccess = {
                _modelState.value = ModelState.Ready
                settingsRepository.activeModelId = modelId
                onSuccess()
            },
            onError = { e ->
                _modelState.value = ModelState.Error(e.message ?: "Unknown error")
                onError(e)
            }
        )
    }

    private fun ensureBackendsLoaded() {
        inferenceEngine.initBackends(context.applicationInfo.nativeLibraryDir ?: "")
    }

    /** GPU device description (e.g. "Adreno (TM) 640"), or "" if no usable GPU backend. */
    fun getGpuDeviceInfo(): String {
        ensureBackendsLoaded()
        return inferenceEngine.getGpuDeviceInfo()
    }

    /**
     * Registered ggml backends and the CPU feature variant picked for this device.
     * Intended for the settings/about screen so slow-inference reports can name the
     * variant that was actually selected.
     */
    fun getBackendInfo(): String {
        ensureBackendsLoaded()
        return inferenceEngine.getBackendInfo()
    }

    suspend fun unloadModel() {
        inferenceEngine.unloadModel()
        _modelState.value = ModelState.NotLoaded
    }

    suspend fun getAvailableModels(): List<ModelInfo> {
        val models = chatRepository.getAllModelsSync()
        for (model in models) {
            if (!File(model.path).exists()) {
                chatRepository.deleteModel(model.id)
            }
        }
        return chatRepository.getAllModelsSync()
    }

    suspend fun deleteModel(modelId: Long) {
        val model = chatRepository.getModel(modelId)
        if (model != null) {
            File(model.path).delete()
            chatRepository.deleteModel(modelId)
            if (settingsRepository.activeModelId == modelId) {
                settingsRepository.activeModelId = -1
            }
        }
    }

    private suspend fun registerModelIfNeeded(file: File, isBundled: Boolean): ModelInfo {
        val existing = chatRepository.getModelByPath(file.absolutePath)
        if (existing != null) return existing

        var contextSize = 2048
        var chatTemplate = ""
        try {
            val reader = GGUFReader()
            reader.load(file.absolutePath)
            contextSize = reader.getContextSize()?.toInt() ?: 2048
            chatTemplate = reader.getChatTemplate() ?: ""
        } catch (_: Exception) {}

        val modelInfo = ModelInfo(
            name = file.nameWithoutExtension.replace("_", " ").replace("-", " "),
            path = file.absolutePath,
            sizeBytes = file.length(),
            contextSize = contextSize,
            chatTemplate = chatTemplate,
            isBundled = isBundled
        )
        val id = chatRepository.addModel(modelInfo)
        val savedModel = modelInfo.copy(id = id)

        if (settingsRepository.activeModelId == -1L) {
            settingsRepository.activeModelId = id
        }

        return savedModel
    }

    private fun validateGGUF(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                if (input.read(magic) != 4) return false
                magic[0] == 'G'.code.toByte() &&
                    magic[1] == 'G'.code.toByte() &&
                    magic[2] == 'U'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else null
        }
    }
}
