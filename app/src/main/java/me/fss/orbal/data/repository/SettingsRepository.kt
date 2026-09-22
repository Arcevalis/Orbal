package me.fss.orbal.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    // Prefer a StrongBox-backed master key when available. Fall back gracefully when not.
    private val masterKeyAndBackend: Pair<MasterKey, String> by lazy { createMasterKeyAndBackend() }
    val secureStorageBackend: String
        get() = masterKeyAndBackend.second

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            "orbal_secure_prefs",
            MASTER_KEY_ALIAS,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val MASTER_KEY_ALIAS = "orbal_secure_prefs_master_key"

        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_CONTEXT_SIZE = "context_size"
        const val KEY_TOP_P = "top_p"
        const val KEY_TOP_K = "top_k"
        const val KEY_MIN_P = "min_p"
        const val KEY_REPEAT_PENALTY = "repeat_penalty"
        const val KEY_BIOMETRIC_LOCK = "biometric_lock"
        const val KEY_SCREENSHOT_PROTECTION = "screenshot_protection"
        const val KEY_TAPJACKING_PROTECTION = "tapjacking_protection"
        const val KEY_SENSITIVE_DATA_ACCESSIBILITY = "sensitive_data_accessibility"
        const val KEY_AUTO_LOCK_ON_BACKGROUND = "auto_lock_on_background"
        const val KEY_ACTIVE_MODEL_ID = "active_model_id"
        const val KEY_SYSTEM_PROMPT_KEY = "system_prompt_key"
        const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        const val KEY_NUM_THREADS = "num_threads"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_DISABLE_THINKING = "disable_thinking"
        const val KEY_MATH_LATEX_HINTS = "math_latex_hints"
        const val KEY_TRANSLATOR_FROM = "translator_from"
        const val KEY_TRANSLATOR_TO = "translator_to"
        const val KEY_CATPPUCCIN_ACCENT = "catppuccin_accent"
        const val KEY_DRACULA_ACCENT = "dracula_accent"
        const val KEY_CATPPUCCIN_FLAVOR = "catppuccin_flavor"
        const val KEY_PTYXIS_PALETTE = "ptyxis_palette"
        const val KEY_MONOCHROME_ACCENTS = "monochrome_accents"
        const val KEY_APP_FONT = "app_font"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_GPU_LAYERS = "gpu_layers"
        const val KEY_USE_GPU = "use_gpu"
        const val KEY_USE_MMAP = "use_mmap"
        const val KEY_USE_MLOCK = "use_mlock"
        const val KEY_KV_CACHE_Q8 = "kv_cache_q8"
        // --- Remote Local Model (OpenAI-compatible API) ---
        const val KEY_REMOTE_ENABLED = "remote_enabled"
        const val KEY_REMOTE_BASE_URL = "remote_base_url"
        const val KEY_REMOTE_API_KEY = "remote_api_key"
        const val KEY_REMOTE_MODEL_ID = "remote_model_id"
        const val DEFAULT_REMOTE_ENABLED = false
        const val DEFAULT_REMOTE_BASE_URL = "http://10.0.2.2:1234/v1" // emulator host loopback; for physical device use 192.168.x.x:1234/v1
        // --- Tools (Standard) ---
        const val KEY_TOOLS_ENABLED = "tools_enabled"
        const val KEY_ENABLED_TOOLS = "enabled_tools" // comma-separated ids
        const val KEY_MAX_TOOL_CALLS = "max_tool_calls"
        const val DEFAULT_TOOLS_ENABLED = true
        const val DEFAULT_MAX_TOOL_CALLS = 5
        val DEFAULT_ENABLED_TOOLS: Set<String> = setOf("web_search", "read_url", "search_knowledge_base", "calculator", "get_current_datetime", "get_device_info")
        // --- Compaction (Standard) ---
        const val KEY_COMPACTION_ENABLED = "compaction_enabled"
        const val KEY_COMPACTION_THRESHOLD = "compaction_threshold"
        const val KEY_COMPACTION_KEEP_RECENT = "compaction_keep_recent"
        const val KEY_SERVER_CTX = "server_reported_ctx"
        const val KEY_SERVER_CTX_MODEL = "server_reported_ctx_model"
        const val DEFAULT_COMPACTION_ENABLED = false
        const val DEFAULT_COMPACTION_THRESHOLD = 0.85f
        const val DEFAULT_COMPACTION_KEEP_RECENT = 10
        const val DEFAULT_REMOTE_MODEL_ID = ""
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_MAX_TOKENS = 2048
        const val DEFAULT_CONTEXT_SIZE = 4096
        const val DEFAULT_TOP_P = 0.9f
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_MIN_P = 0.1f
        const val DEFAULT_REPEAT_PENALTY = 1.1f
        val DEFAULT_NUM_THREADS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(4, 8)

        const val DEFAULT_SCREENSHOT_PROTECTION = false
        const val DEFAULT_TAPJACKING_PROTECTION = true
        const val DEFAULT_SENSITIVE_DATA_ACCESSIBILITY = true
        const val DEFAULT_AUTO_LOCK_ON_BACKGROUND = false
    }

    private fun createMasterKeyAndBackend(): Pair<MasterKey, String> {
        val strongBoxAvailable = try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } catch (_: Exception) {
            false
        }

        return try {
            val requestedKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()

            // Jetpack Security doesn't consistently expose StrongBox state across versions.
            // Use best-effort reflection; fall back to feature detection.
            val reflectStrongBox = runCatching {
                val m = requestedKey.javaClass.getMethod("isStrongBoxBacked")
                (m.invoke(requestedKey) as? Boolean) ?: false
            }.getOrDefault(false)

            val backend = when {
                strongBoxAvailable -> "StrongBox"  // Prioritize system feature detection
                reflectStrongBox -> "StrongBox"    // Fallback to reflection
                else -> "TEE"
            }
            requestedKey to backend
        } catch (_: Exception) {
            val fallbackKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            fallbackKey to "TEE"
        }
    }

    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
        set(value) = prefs.edit().putFloat(KEY_TEMPERATURE, value).apply()

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value).apply()

    var contextSize: Int
        get() = prefs.getInt(KEY_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_SIZE, value).apply()

    var topP: Float
        get() = prefs.getFloat(KEY_TOP_P, DEFAULT_TOP_P)
        set(value) = prefs.edit().putFloat(KEY_TOP_P, value).apply()

    var topK: Int
        get() = prefs.getInt(KEY_TOP_K, DEFAULT_TOP_K)
        set(value) = prefs.edit().putInt(KEY_TOP_K, value).apply()

    var minP: Float
        get() = prefs.getFloat(KEY_MIN_P, DEFAULT_MIN_P)
        set(value) = prefs.edit().putFloat(KEY_MIN_P, value).apply()

    var repeatPenalty: Float
        get() = prefs.getFloat(KEY_REPEAT_PENALTY, DEFAULT_REPEAT_PENALTY)
        set(value) = prefs.edit().putFloat(KEY_REPEAT_PENALTY, value).apply()

    var biometricLock: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, value).apply()

    var screenshotProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREENSHOT_PROTECTION, DEFAULT_SCREENSHOT_PROTECTION)
        set(value) = prefs.edit().putBoolean(KEY_SCREENSHOT_PROTECTION, value).apply()

    var tapjackingProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_TAPJACKING_PROTECTION, DEFAULT_TAPJACKING_PROTECTION)
        set(value) = prefs.edit().putBoolean(KEY_TAPJACKING_PROTECTION, value).apply()

    var sensitiveDataAccessibilityEnabled: Boolean
        get() = prefs.getBoolean(KEY_SENSITIVE_DATA_ACCESSIBILITY, DEFAULT_SENSITIVE_DATA_ACCESSIBILITY)
        set(value) = prefs.edit().putBoolean(KEY_SENSITIVE_DATA_ACCESSIBILITY, value).apply()

    var autoLockOnBackgroundEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LOCK_ON_BACKGROUND, DEFAULT_AUTO_LOCK_ON_BACKGROUND)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LOCK_ON_BACKGROUND, value).apply()

    var activeModelId: Long
        get() = prefs.getLong(KEY_ACTIVE_MODEL_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_ACTIVE_MODEL_ID, value).apply()

    var systemPromptKey: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT_KEY, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT_KEY, value).apply()

    var customSystemPrompt: String
        get() = prefs.getString(KEY_CUSTOM_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SYSTEM_PROMPT, value).apply()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    var numThreads: Int
        get() = prefs.getInt(KEY_NUM_THREADS, DEFAULT_NUM_THREADS)
        set(value) = prefs.edit().putInt(KEY_NUM_THREADS, value).apply()

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "AMOLED") ?: "AMOLED"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    var accentColor: String
        get() = prefs.getString(KEY_ACCENT_COLOR, "blue") ?: "blue"
        set(value) = prefs.edit().putString(KEY_ACCENT_COLOR, value).apply()

    var disableThinking: Boolean
        get() = prefs.getBoolean(KEY_DISABLE_THINKING, true)
        set(value) = prefs.edit().putBoolean(KEY_DISABLE_THINKING, value).apply()

    var mathLatexHints: Boolean
        get() = prefs.getBoolean(KEY_MATH_LATEX_HINTS, false)
        set(value) = prefs.edit().putBoolean(KEY_MATH_LATEX_HINTS, value).apply()

    var translatorFrom: String
        get() = prefs.getString(KEY_TRANSLATOR_FROM, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_TRANSLATOR_FROM, value).apply()

    var translatorTo: String
        get() = prefs.getString(KEY_TRANSLATOR_TO, "es") ?: "es"
        set(value) = prefs.edit().putString(KEY_TRANSLATOR_TO, value).apply()

    var catppuccinAccent: String
        get() = prefs.getString(KEY_CATPPUCCIN_ACCENT, "mauve") ?: "mauve"
        set(value) = prefs.edit().putString(KEY_CATPPUCCIN_ACCENT, value).apply()

    var draculaAccent: String
        get() = prefs.getString(KEY_DRACULA_ACCENT, "purple") ?: "purple"
        set(value) = prefs.edit().putString(KEY_DRACULA_ACCENT, value).apply()

    var catppuccinFlavor: String
        get() = prefs.getString(KEY_CATPPUCCIN_FLAVOR, "mocha") ?: "mocha"
        set(value) = prefs.edit().putString(KEY_CATPPUCCIN_FLAVOR, value).apply()

    var ptyxisPalette: String
        get() = prefs.getString(KEY_PTYXIS_PALETTE, "cobalt_neon") ?: "cobalt_neon"
        set(value) = prefs.edit().putString(KEY_PTYXIS_PALETTE, value).apply()

    var monochromeAccents: Boolean
        get() = prefs.getBoolean(KEY_MONOCHROME_ACCENTS, false)
        set(value) = prefs.edit().putBoolean(KEY_MONOCHROME_ACCENTS, value).apply()

    var appFont: String
        get() = prefs.getString(KEY_APP_FONT, "quicksand") ?: "quicksand"
        set(value) = prefs.edit().putString(KEY_APP_FONT, value).apply()

    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()

    // 99 = "offload all layers"; only takes effect when useGpu is on.
    var gpuLayers: Int
        get() = prefs.getInt(KEY_GPU_LAYERS, 99)
        set(value) = prefs.edit().putInt(KEY_GPU_LAYERS, value).apply()

    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_USE_GPU, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_GPU, value).apply()

    // Defaults off: mapping the weights leaves them as file-backed pages the kernel
    // can evict under pressure, so the first reply stalls re-faulting them back in.
    // Reading the model into anonymous memory once is measurably quicker to first
    // token on phones. Users who are tight on RAM can turn it back on.
    var useMmap: Boolean
        get() = prefs.getBoolean(KEY_USE_MMAP, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_MMAP, value).apply()

    var useMlock: Boolean
        get() = prefs.getBoolean(KEY_USE_MLOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_MLOCK, value).apply()

    var kvCacheQ8: Boolean
        get() = prefs.getBoolean(KEY_KV_CACHE_Q8, false)
        set(value) = prefs.edit().putBoolean(KEY_KV_CACHE_Q8, value).apply()

    // --- Remote LM Studio ---
    var remoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_ENABLED, DEFAULT_REMOTE_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_ENABLED, value).apply()

    var remoteBaseUrl: String
        get() = prefs.getString(KEY_REMOTE_BASE_URL, DEFAULT_REMOTE_BASE_URL) ?: DEFAULT_REMOTE_BASE_URL
        set(value) = prefs.edit().putString(KEY_REMOTE_BASE_URL, value).apply()

    var remoteApiKey: String
        get() = prefs.getString(KEY_REMOTE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REMOTE_API_KEY, value).apply()

    var remoteModelId: String
        get() = prefs.getString(KEY_REMOTE_MODEL_ID, DEFAULT_REMOTE_MODEL_ID) ?: DEFAULT_REMOTE_MODEL_ID
        set(value) = prefs.edit().putString(KEY_REMOTE_MODEL_ID, value).apply()

    val isRemoteConfigured: Boolean
        get() = remoteEnabled && remoteBaseUrl.isNotBlank() && remoteModelId.isNotBlank()

    // --- Tools ---
    var toolsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOOLS_ENABLED, DEFAULT_TOOLS_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_TOOLS_ENABLED, value).apply()

    var maxToolCalls: Int
        get() = prefs.getInt(KEY_MAX_TOOL_CALLS, DEFAULT_MAX_TOOL_CALLS)
        set(value) = prefs.edit().putInt(KEY_MAX_TOOL_CALLS, value).apply()

    var enabledTools: Set<String>
        get() = prefs.getString(KEY_ENABLED_TOOLS, null)?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: DEFAULT_ENABLED_TOOLS
        set(value) = prefs.edit().putString(KEY_ENABLED_TOOLS, value.joinToString(",")).apply()

    fun isToolEnabled(toolId: String): Boolean = toolsEnabled && enabledTools.contains(toolId)

    // --- Compaction ---
    var compactionEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMPACTION_ENABLED, DEFAULT_COMPACTION_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_COMPACTION_ENABLED, value).apply()

    var compactionThreshold: Float
        get() = prefs.getFloat(KEY_COMPACTION_THRESHOLD, DEFAULT_COMPACTION_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_COMPACTION_THRESHOLD, value).apply()

    var compactionKeepRecent: Int
        get() = prefs.getInt(KEY_COMPACTION_KEEP_RECENT, DEFAULT_COMPACTION_KEEP_RECENT)
        set(value) = prefs.edit().putInt(KEY_COMPACTION_KEEP_RECENT, value).apply()

    var serverReportedCtx: Int
        get() = prefs.getInt(KEY_SERVER_CTX, 0)
        set(value) = prefs.edit().putInt(KEY_SERVER_CTX, value).apply()

    var serverReportedCtxModel: String
        get() = prefs.getString(KEY_SERVER_CTX_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_CTX_MODEL, value).apply()

    /** Effective context window for chats — server value when remote+reported, else local slider */
    val effectiveContextSize: Int
        get() {
            if (remoteEnabled && serverReportedCtx > 0) return serverReportedCtx
            return contextSize
        }
}
