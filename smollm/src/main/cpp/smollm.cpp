#include "LLMInference.h"
#include "ggml-backend.h"
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>

// Loads the ggml backend plugins (libggml-cpu-android_*.so variants + libggml-vulkan.so)
// from the app's nativeLibraryDir. ggml scores each CPU variant against the device's
// actual features (dotprod/fp16/i8mm/SVE...) and keeps only the best one. Idempotent.
extern "C" JNIEXPORT void JNICALL
Java_me_fss_orbal_smollm_SmolLM_initBackends(JNIEnv* env, jobject thiz, jstring nativeLibDir) {
    static std::atomic_bool initialized{false};
    if (initialized.exchange(true)) {
        return;
    }
    jboolean isCopy = true;
    const char* dirCstr = env->GetStringUTFChars(nativeLibDir, &isCopy);
    if (dirCstr != nullptr && dirCstr[0] != '\0') {
        __android_log_print(ANDROID_LOG_INFO, "smollm-backends",
                            "loading backend plugins from %s", dirCstr);
        ggml_backend_load_all_from_path(dirCstr);
    } else {
        __android_log_print(ANDROID_LOG_WARN, "smollm-backends",
                            "nativeLibraryDir EMPTY — falling back to ggml_backend_load_all() "
                            "(likely loads nothing on Android)");
        ggml_backend_load_all();
    }
    env->ReleaseStringUTFChars(nativeLibDir, dirCstr);

    // Which plugins actually won the load-time scoring. Seven ggml-cpu variants
    // ship in the APK and exactly one is kept; without this there is no way to
    // tell a device running the dotprod/i8mm kernels from one that silently fell
    // back to the armv8.0 baseline, and no way to see whether Vulkan registered.
    const size_t devCount = ggml_backend_dev_count();
    __android_log_print(ANDROID_LOG_INFO, "smollm-backends",
                        "registry after load: %zu device(s)", devCount);
    for (size_t i = 0; i < devCount; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const char* name = ggml_backend_dev_name(dev);
        const char* desc = ggml_backend_dev_description(dev);
        const char* type;
        switch (ggml_backend_dev_type(dev)) {
            case GGML_BACKEND_DEVICE_TYPE_CPU:   type = "CPU";   break;
            case GGML_BACKEND_DEVICE_TYPE_GPU:   type = "GPU";   break;
            case GGML_BACKEND_DEVICE_TYPE_IGPU:  type = "IGPU";  break;
            case GGML_BACKEND_DEVICE_TYPE_ACCEL: type = "ACCEL"; break;
            default:                             type = "OTHER"; break;
        }
        __android_log_print(ANDROID_LOG_INFO, "smollm-backends",
                            "  device %zu: [%s] name=%s desc=%s",
                            i, type, name ? name : "?", desc ? desc : "?");
    }
}

// Returns the description of the first GPU-type ggml backend device (e.g. the
// Vulkan device name like "Adreno (TM) 640"), or "" when no GPU backend
// registered — either not compiled in or Vulkan init failed on this device.
extern "C" JNIEXPORT jstring JNICALL
Java_me_fss_orbal_smollm_SmolLM_getGpuDeviceName(JNIEnv* env, jobject thiz) {
    std::string result;
    try {
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
            if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
                const char* desc = ggml_backend_dev_description(dev);
                if (desc != nullptr) {
                    result = desc;
                }
                break;
            }
        }
    } catch (...) {
        result.clear();
    }
    return env->NewStringUTF(result.c_str());
}

// Which backends actually registered, and which ggml-cpu variant won the runtime
// scoring. GGML_CPU_ALL_VARIANTS picks one plugin per device silently, and ggml
// only logs the scores under NDEBUG-disabled GGML_LOG_DEBUG — so on a release
// build a device that fell back to the armv8.0 baseline (no DOTPROD/MATMUL_INT8,
// several times slower on quantized models) is indistinguishable from one that
// picked the right variant. This surfaces the feature list so "it feels slow"
// reports can be checked instead of guessed at.
extern "C" JNIEXPORT jstring JNICALL
Java_me_fss_orbal_smollm_SmolLM_getBackendReport(JNIEnv* env, jobject thiz) {
    std::string out;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        const char* regName = ggml_backend_reg_name(reg);
        out += regName != nullptr ? regName : "(unnamed)";

        auto getFeatures = (ggml_backend_get_features_t)
            ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features");
        if (getFeatures != nullptr) {
            out += " [";
            bool first = true;
            for (ggml_backend_feature* f = getFeatures(reg); f != nullptr && f->name != nullptr; f++) {
                if (!first) {
                    out += ' ';
                }
                out += f->name;
                // Flags report "1"; only value-carrying entries (SVE_CNT, ...) need it spelled out.
                if (f->value != nullptr && strcmp(f->value, "1") != 0) {
                    out += '=';
                    out += f->value;
                }
                first = false;
            }
            out += ']';
        }
        out += '\n';
    }

    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const char* devName = ggml_backend_dev_name(dev);
        const char* devDesc = ggml_backend_dev_description(dev);
        const char* devType;
        switch (ggml_backend_dev_type(dev)) {
            case GGML_BACKEND_DEVICE_TYPE_CPU:   devType = "CPU";   break;
            case GGML_BACKEND_DEVICE_TYPE_GPU:   devType = "GPU";   break;
            case GGML_BACKEND_DEVICE_TYPE_IGPU:  devType = "iGPU";  break;
            case GGML_BACKEND_DEVICE_TYPE_ACCEL: devType = "ACCEL"; break;
            default:                             devType = "?";     break;
        }
        out += "device ";
        out += devName != nullptr ? devName : "(unnamed)";
        out += " (";
        out += devType;
        out += "): ";
        out += devDesc != nullptr ? devDesc : "";
        out += '\n';
    }

    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_fss_orbal_smollm_SmolLM_loadModel(JNIEnv* env, jobject thiz, jstring modelPath, jfloat minP,
                                            jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty,
                                            jboolean storeChats, jlong contextSize,
                                            jstring chatTemplate, jint nThreads, jboolean useMmap, jboolean useMlock,
                                            jint nGpuLayers, jint nThreadsBatch, jboolean kvCacheQ8) {
    jboolean    isCopy           = true;
    const char* modelPathCstr    = env->GetStringUTFChars(modelPath, &isCopy);
    auto*       llmInference     = new LLMInference();
    const char* chatTemplateCstr = env->GetStringUTFChars(chatTemplate, &isCopy);

    try {
        llmInference->loadModel(modelPathCstr, minP, temperature, topP, topK, repeatPenalty,
                                storeChats, contextSize, chatTemplateCstr, nThreads, useMmap, useMlock,
                                nGpuLayers, nThreadsBatch, kvCacheQ8);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(modelPath, modelPathCstr);
        env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
        delete llmInference;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }

    env->ReleaseStringUTFChars(modelPath, modelPathCstr);
    env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
    return reinterpret_cast<jlong>(llmInference);
}

extern "C" JNIEXPORT void JNICALL
Java_me_fss_orbal_smollm_SmolLM_addChatMessage(JNIEnv* env, jobject thiz, jlong modelPtr, jstring message,
                                                 jstring role) {
    jboolean    isCopy       = true;
    const char* messageCstr  = env->GetStringUTFChars(message, &isCopy);
    const char* roleCstr     = env->GetStringUTFChars(role, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->addChatMessage(messageCstr, roleCstr);
    env->ReleaseStringUTFChars(message, messageCstr);
    env->ReleaseStringUTFChars(role, roleCstr);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_me_fss_orbal_smollm_SmolLM_getResponseGenerationSpeed(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getResponseGenerationTime();
}

extern "C" JNIEXPORT jint JNICALL
Java_me_fss_orbal_smollm_SmolLM_getContextSizeUsed(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getContextSizeUsed();
}

extern "C" JNIEXPORT void JNICALL
Java_me_fss_orbal_smollm_SmolLM_close(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    delete llmInference;
}

extern "C" JNIEXPORT void JNICALL
Java_me_fss_orbal_smollm_SmolLM_startCompletion(JNIEnv* env, jobject thiz, jlong modelPtr, jstring prompt) {
    jboolean    isCopy       = true;
    const char* promptCstr   = env->GetStringUTFChars(prompt, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        llmInference->startCompletion(promptCstr);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(prompt, promptCstr);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return;
    }
    env->ReleaseStringUTFChars(prompt, promptCstr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_fss_orbal_smollm_SmolLM_completionLoop(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        std::string response = llmInference->completionLoop();
        return env->NewStringUTF(response.c_str());
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_fss_orbal_smollm_SmolLM_stopCompletion(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->stopCompletion();
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_fss_orbal_smollm_SmolLM_benchModel(JNIEnv* env, jobject /*unused*/, jlong modelPtr, jint pp, jint tg, jint pl,
                                             jint nr) {
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    std::string result       = llmInference->benchModel(pp, tg, pl, nr);
    return env->NewStringUTF(result.c_str());
}
