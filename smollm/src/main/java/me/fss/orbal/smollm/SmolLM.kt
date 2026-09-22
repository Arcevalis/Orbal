package me.fss.orbal.smollm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

class SmolLM {
    companion object {
        private const val TAG = "SmolLM"

        init {
            // CPU-specific optimisation now lives in the ggml-cpu plugin variants
            // (libggml-cpu-android_*.so) selected at runtime by initBackends();
            // the JNI wrapper itself has a single build.
            System.loadLibrary("smollm")
        }
    }

    @Volatile private var nativePtr = 0L

    data class InferenceParams(
        val minP: Float = 0.1f,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val topK: Int = 40,
        val repeatPenalty: Float = 1.1f,
        val storeChats: Boolean = true,
        val contextSize: Long? = null,
        val chatTemplate: String? = null,
        val numThreads: Int = 4,
        val useMmap: Boolean = true,
        val useMlock: Boolean = false,
        val nGpuLayers: Int = 0,
        // Threads for the compute-bound prompt-processing phase; <= 0 means
        // "same as numThreads". Typically set to all cores incl. efficiency ones.
        val numThreadsBatch: Int = -1,
        // Q8_0-quantized KV cache — halves KV memory at long contexts.
        val kvCacheQ8: Boolean = false,
    )

    object DefaultParams {
        const val CONTEXT_SIZE: Long = 2048L
        const val CHAT_TEMPLATE: String =
            "{% for message in messages %}{% if loop.first and messages[0]['role'] != 'system' %}{{ '<|im_start|>system You are a helpful AI assistant.<|im_end|> ' }}{% endif %}{{'<|im_start|>' + message['role'] + ' ' + message['content'] + '<|im_end|>' + ' '}}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant ' }}{% endif %}"
    }

    suspend fun load(modelPath: String, params: InferenceParams = InferenceParams()) =
        withContext(Dispatchers.IO) {
            val ggufReader = GGUFReader()
            ggufReader.load(modelPath)
            // Clamp the GGUF's declared context to something a phone can actually
            // hold. Modern models advertise enormous training contexts — Qwen3.5-2B
            // declares 262144 — and honouring that verbatim allocates a KV cache and
            // compute buffers far past what the device has. The bigger the weights,
            // the less headroom is left for the cache, so the cap tightens with file
            // size. An explicit user setting (params.contextSize) still wins.
            val fileSizeBytes = File(modelPath).length()
            val maxContextBySize = when {
                fileSizeBytes > 2L * 1024 * 1024 * 1024 -> 4096L // >2 GB
                fileSizeBytes > 1L * 1024 * 1024 * 1024 -> 8192L // 1–2 GB
                else -> 8192L
            }
            val rawContextSize = ggufReader.getContextSize() ?: DefaultParams.CONTEXT_SIZE
            val modelContextSize = minOf(rawContextSize, maxContextBySize)
            val modelChatTemplate = ggufReader.getChatTemplate() ?: DefaultParams.CHAT_TEMPLATE
            nativePtr = loadModel(
                modelPath,
                params.minP,
                params.temperature,
                params.topP,
                params.topK,
                params.repeatPenalty,
                params.storeChats,
                params.contextSize ?: modelContextSize,
                params.chatTemplate ?: modelChatTemplate,
                params.numThreads,
                params.useMmap,
                params.useMlock,
                params.nGpuLayers,
                params.numThreadsBatch,
                params.kvCacheQ8,
            )
        }

    /**
     * Loads the ggml backend plugins from the app's nativeLibraryDir, picking the
     * best CPU-variant kernels for this device. Must run once before the first
     * [load] or [getGpuDeviceInfo]; subsequent calls are no-ops.
     */
    fun loadBackends(nativeLibDir: String) = initBackends(nativeLibDir)

    /**
     * Description of the first GPU-type ggml backend device (e.g. "Adreno (TM) 640"),
     * or "" when no usable GPU backend registered on this device. Does not require
     * a loaded model.
     */
    fun getGpuDeviceInfo(): String = try {
        getGpuDeviceName()
    } catch (_: Throwable) {
        ""
    }

    /**
     * Registered ggml backends with their active feature flags, plus the backend
     * devices. The CPU line names the variant actually selected for this device —
     * a phone reporting `DOTPROD MATMUL_INT8` is running the fast quantized-matmul
     * kernels, one reporting only `NEON` fell back to the armv8.0 baseline and will
     * be several times slower. Requires [loadBackends] to have run.
     */
    fun getBackendInfo(): String = try {
        getBackendReport()
    } catch (_: Throwable) {
        ""
    }

    /**
     * Generic method to add a message with a specific role.
     * Essential for models like Gemma that use "model" instead of "assistant".
     */
    fun addChatMessage(role: String, message: String) {
        verifyHandle()
        addChatMessage(nativePtr, message, role)
    }

    fun addUserMessage(message: String) {
        addChatMessage("user", message)
    }

    fun addSystemPrompt(prompt: String) {
        addChatMessage("system", prompt)
    }

    fun addAssistantMessage(message: String) {
        addChatMessage("assistant", message)
    }

    fun getResponseGenerationSpeed(): Float {
        verifyHandle()
        return getResponseGenerationSpeed(nativePtr)
    }

    fun getContextLengthUsed(): Int {
        verifyHandle()
        return getContextSizeUsed(nativePtr)
    }

    fun stop() {
        val ptr = nativePtr
        if (ptr != 0L) stopCompletion(ptr)
    }

    fun getResponseAsFlow(query: String): Flow<String> = flow {
        verifyHandle()
        val ptr = nativePtr
        startCompletion(ptr, query)
        try {
            while (nativePtr != 0L) {
                val piece = completionLoop(nativePtr)
                if (piece == "[EOG]") break
                emit(piece)
            }
        } finally {
            if (nativePtr != 0L) stopCompletion(nativePtr)
        }
    }

    fun getResponse(query: String): String {
        verifyHandle()
        startCompletion(nativePtr, query)
        var piece = completionLoop(nativePtr)
        var response = ""
        while (piece != "[EOG]") {
            response += piece
            piece = completionLoop(nativePtr)
        }
        stopCompletion(nativePtr)
        return response
    }

    fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String {
        verifyHandle()
        return benchModel(nativePtr, pp, tg, pl, nr)
    }

    fun close() {
        if (nativePtr != 0L) {
            val ptr = nativePtr
            nativePtr = 0L  // zero before native free so flow loops see it immediately
            close(ptr)
        }
    }

    fun isLoaded(): Boolean = nativePtr != 0L

    private fun verifyHandle() {
        check(nativePtr != 0L) { "Model is not loaded. Call SmolLM.load() first." }
    }

    private external fun loadModel(
        modelPath: String, minP: Float, temperature: Float, topP: Float, topK: Int,
        repeatPenalty: Float, storeChats: Boolean, contextSize: Long, chatTemplate: String,
        nThreads: Int, useMmap: Boolean, useMlock: Boolean, nGpuLayers: Int,
        nThreadsBatch: Int, kvCacheQ8: Boolean
    ): Long
    private external fun initBackends(nativeLibDir: String)

    private external fun getGpuDeviceName(): String
    private external fun getBackendReport(): String
    private external fun addChatMessage(modelPtr: Long, message: String, role: String)
    private external fun getResponseGenerationSpeed(modelPtr: Long): Float
    private external fun getContextSizeUsed(modelPtr: Long): Int
    private external fun close(modelPtr: Long)
    private external fun startCompletion(modelPtr: Long, prompt: String)
    private external fun completionLoop(modelPtr: Long): String
    private external fun stopCompletion(modelPtr: Long)
    private external fun benchModel(modelPtr: Long, pp: Int, tg: Int, pl: Int, nr: Int): String
}
