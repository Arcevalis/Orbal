#ifndef LLMINFERENCE_H
#define LLMINFERENCE_H

#include "llama.h"
#include "ggml.h"
#include <string>
#include <vector>

class LLMInference {
public:
    void loadModel(const char *model_path, float minP, float temperature, float topP, int topK,
                   float repeatPenalty, bool storeChats, long contextSize, const char *chatTemplate,
                   int nThreads, bool useMmap, bool useMlock, int nGpuLayers = 0,
                   int nThreadsBatch = -1, bool kvCacheQ8 = false);
    void addChatMessage(const char *message, const char *role);
    float getResponseGenerationTime() const;
    int getContextSizeUsed() const;
    void startCompletion(const char *query);
    std::string completionLoop();
    void stopCompletion();
    std::string benchModel(int pp, int tg, int pl, int nr);
    ~LLMInference();

private:
    llama_model *_model = nullptr;
    llama_context *_ctx = nullptr;
    llama_sampler *_sampler = nullptr;
    llama_batch _batch = {};
    llama_token _currToken;

    std::vector<llama_chat_message> _messages;
    std::vector<char> _formattedMessages;
    std::vector<llama_token> _promptTokens;
    std::string _response;
    std::string _cacheResponseTokens;
    const char *_chatTemplate = nullptr;
    bool _storeChats = true;
    std::string _assistantRole = "assistant";
    // Length of the templated conversation prefix already fed to the KV cache.
    // Each turn only tokenizes/decodes formatted[_prevLen..new_len) instead of
    // re-feeding the whole history (which grew quadratically and duplicated KV).
    size_t _prevLen = 0;

    int64_t _responseGenerationTime = 0;
    int _responseNumTokens = 0;
    int _nCtxUsed = 0;

    llama_batch g_batch;

    static bool _isValidUtf8(const char *response);
    void _updatePrevLen();

    // Most recent WARN/ERROR line emitted by ggml/llama.cpp's internal logger, captured via
    // llama_log_set() so the real reason llama_model_load_from_file()/llama_init_from_model()
    // returned null (unsupported arch, corrupt file, alloc failure, etc.) can be surfaced in the
    // exception message instead of the generic "loadModel() failed" with no detail.
    static std::string _lastErrorLog;
    static void _logCallback(ggml_log_level level, const char *text, void *userData);
};

#endif // LLMINFERENCE_H
