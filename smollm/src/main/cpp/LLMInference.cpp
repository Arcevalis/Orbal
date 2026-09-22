#include "LLMInference.h"
#include <android/log.h>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <cmath>
#include <algorithm>

#define TAG "[Orbal-Cpp]"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

std::string LLMInference::_lastErrorLog;

// Local replacements for the llama.cpp `common` helpers — the common library is
// no longer linked (it drags in upstream's HTTP/download code, unwanted in a
// zero-network app, and stopped exporting these symbols anyway).
static std::vector<llama_token>
tokenizeText(const llama_vocab *vocab, const std::string &text, bool addSpecial, bool parseSpecial) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, addSpecial,
                            parseSpecial);
    std::vector<llama_token> tokens(n > 0 ? n : 0);
    if (n > 0) {
        llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, addSpecial,
                       parseSpecial);
    }
    return tokens;
}

static std::string
tokenToPiece(const llama_context *ctx, llama_token token, bool special) {
    const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(ctx));
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, special);
    if (n < 0) {
        n = 0;
    }
    return std::string(buf, n);
}

static void
batchClear(llama_batch &batch) {
    batch.n_tokens = 0;
}

static void
batchAdd(llama_batch &batch, llama_token id, llama_pos pos,
         const std::vector<llama_seq_id> &seqIds, bool logits) {
    batch.token[batch.n_tokens] = id;
    batch.pos[batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = (int32_t) seqIds.size();
    for (size_t i = 0; i < seqIds.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seqIds[i];
    }
    batch.logits[batch.n_tokens] = logits;
    batch.n_tokens++;
}

void
LLMInference::_logCallback(ggml_log_level level, const char *text, void *userData) {
    (void) userData;
    if (!text) return;

    android_LogPriority priority;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  priority = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  priority = ANDROID_LOG_INFO;  break;
        default:                   priority = ANDROID_LOG_DEBUG; break;
    }
    __android_log_print(priority, "[ggml]", "%s", text);

    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        std::string line(text);
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) {
            line.pop_back();
        }
        // Keep the first few lines: llama's load errors cascade from specific
        // ("missing tensor X") to generic ("failed to load model"), and the
        // earliest line is the informative one.
        if (!line.empty() && _lastErrorLog.size() < 512) {
            if (!_lastErrorLog.empty()) {
                _lastErrorLog += " | ";
            }
            _lastErrorLog += line;
        }
    }
}

void
LLMInference::loadModel(const char *model_path, float minP, float temperature, float topP, int topK,
                        float repeatPenalty, bool storeChats, long contextSize,
                        const char *chatTemplate, int nThreads, bool useMmap, bool useMlock,
                        int nGpuLayers, int nThreadsBatch, bool kvCacheQ8) {
    _lastErrorLog.clear();
    llama_log_set(_logCallback, nullptr);

    LOGi("loading model with"
         "\n\tmodel_path = %s"
         "\n\tminP = %f"
         "\n\ttemperature = %f"
         "\n\ttopP = %f"
         "\n\ttopK = %d"
         "\n\trepeatPenalty = %f"
         "\n\tstoreChats = %d"
         "\n\tcontextSize = %li"
         "\n\tnThreads = %d"
         "\n\tnThreadsBatch = %d"
         "\n\tuseMmap = %d"
         "\n\tuseMlock = %d"
         "\n\tnGpuLayers = %d"
         "\n\tkvCacheQ8 = %d",
         model_path, minP, temperature, topP, topK, repeatPenalty, storeChats, contextSize,
         nThreads, nThreadsBatch, useMmap, useMlock, nGpuLayers, kvCacheQ8);

    llama_model_params model_params = llama_model_default_params();
    // Upstream replaced the use_mmap/use_mlock booleans with a single load_mode
    // enum; the two flags map onto its combinations one-for-one.
    if (useMmap) {
        model_params.load_mode = useMlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP;
    } else {
        model_params.load_mode = useMlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE;
    }
    model_params.n_gpu_layers = nGpuLayers;
    // CPU mode must actually mean CPU: with a GPU backend registered (Vulkan is
    // always loaded by initBackends), llama.cpp still adds it to the scheduler at
    // n_gpu_layers=0 and routes large-batch prompt processing through it, copying
    // weights to the GPU per batch. On desktop dGPUs that's a win; on mobile the
    // copies are so slow that "CPU" chats crawl, and a large Vulkan compute buffer
    // gets reserved for a chat that never touches the GPU. It also pushes
    // Vulkan_Host ahead of the CPU repack buffer type, so quantized weights are
    // never repacked into the layouts ARM's dotprod/i8mm GEMM kernels need.
    // Restricting the device list keeps CPU chats pure CPU end to end; GPU mode
    // (nGpuLayers > 0) is unaffected. The vector only needs to outlive the load
    // call — llama copies the device list into the model.
    std::vector<ggml_backend_dev_t> cpuOnlyDevices;
    if (nGpuLayers <= 0) {
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            enum ggml_backend_dev_type devType = ggml_backend_dev_type(dev);
            if (devType == GGML_BACKEND_DEVICE_TYPE_CPU ||
                devType == GGML_BACKEND_DEVICE_TYPE_ACCEL) {
                cpuOnlyDevices.push_back(dev);
            }
        }
        cpuOnlyDevices.push_back(nullptr);
        model_params.devices = cpuOnlyDevices.data();
    }
    _model = llama_model_load_from_file(model_path, model_params);
    if (!_model) {
        LOGe("failed to load model from %s", model_path);
        std::string reason = _lastErrorLog.empty() ? "no further detail from ggml" : _lastErrorLog;
        throw std::runtime_error("loadModel() failed: " + reason);
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_batch = contextSize;
    ctx_params.n_threads = nThreads;
    // Prompt processing is compute-bound and scales well across all cores
    // (including efficiency cores); generation is memory-bound and prefers
    // the smaller big-core count in nThreads.
    ctx_params.n_threads_batch = (nThreadsBatch > 0) ? nThreadsBatch : nThreads;
    // Cap the logits buffer at a single position. n_outputs_max defaults to
    // n_batch, and n_batch is set to the context size above, so the graph
    // reserves logits for a whole ubatch: n_ubatch(512) * n_vocab * 4 bytes.
    // On a 248k-vocab model that is ~485 MiB of compute buffer to hold outputs
    // that are never read — this wrapper samples one token at a time and only
    // ever asks for the last position's logits (llama_sampler_sample(..., -1)).
    // Large-vocab models made this scale into serious memory pressure on phones.
    // Raise this if multi-token output is ever needed in one decode.
    ctx_params.n_outputs_max = 1;
    if (kvCacheQ8) {
        // Halves KV-cache memory at long contexts. Requires flash attention,
        // which llama.cpp enables automatically where supported.
        ctx_params.type_k = GGML_TYPE_Q8_0;
        ctx_params.type_v = GGML_TYPE_Q8_0;
    }
    ctx_params.no_perf = true;
    _ctx = llama_init_from_model(_model, ctx_params);
    if (!_ctx) {
        LOGe("llama_new_context_with_model() returned null");
        std::string reason = _lastErrorLog.empty() ? "no further detail from ggml" : _lastErrorLog;
        throw std::runtime_error("llama_new_context_with_model() returned null: " + reason);
    }

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    _sampler = llama_sampler_chain_init(sampler_params);

    if (repeatPenalty > 1.0f) {
        // n_vocab is a new leading argument on this sampler upstream.
        const int32_t nVocab = llama_vocab_n_tokens(llama_model_get_vocab(_model));
        llama_sampler_chain_add(_sampler,
                                llama_sampler_init_penalties(nVocab, 256, repeatPenalty, 0.0f, 0.0f));
    }

    if (topK > 0) {
        llama_sampler_chain_add(_sampler, llama_sampler_init_top_k(topK));
    }

    if (topP < 1.0f) {
        llama_sampler_chain_add(_sampler, llama_sampler_init_top_p(topP, 1));
    }

    if (minP > 0.0f) {
        llama_sampler_chain_add(_sampler, llama_sampler_init_min_p(minP, 1));
    }

    llama_sampler_chain_add(_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    _formattedMessages = std::vector<char>(llama_n_ctx(_ctx));
    _messages.clear();
    _prevLen = 0;

    if (chatTemplate == nullptr || strlen(chatTemplate) == 0) {
        _chatTemplate = llama_model_chat_template(_model, nullptr);
    } else {
        _chatTemplate = strdup(chatTemplate);
    }
    
    if (_chatTemplate != nullptr) {
        std::string tmpl(_chatTemplate);
        if (tmpl.find("gemma") != std::string::npos || 
            tmpl.find("<start_of_turn>") != std::string::npos ||
            tmpl.find("<turn|") != std::string::npos) {
            _assistantRole = "model";
        } else {
            _assistantRole = "assistant";
        }
    }

    this->_storeChats = storeChats;

    // Warmup: one throwaway decode so backend buffer allocation happens now
    // rather than adding latency to the user's first message.
    const llama_vocab *vocab = llama_model_get_vocab(_model);
    llama_token bosToken = llama_vocab_bos(vocab);
    if (bosToken != LLAMA_TOKEN_NULL) {
        llama_batch warmup = llama_batch_get_one(&bosToken, 1);
        if (llama_decode(_ctx, warmup) != 0) {
            LOGe("warmup decode failed (non-fatal)");
        }
        llama_memory_clear(llama_get_memory(_ctx), true);
    }
}

void
LLMInference::_updatePrevLen() {
    int len = llama_chat_apply_template(_chatTemplate, _messages.data(), _messages.size(), false,
                                        nullptr, 0);
    _prevLen = len < 0 ? 0 : (size_t) len;
}

void
LLMInference::addChatMessage(const char *message, const char *role) {
    const char* actualRole = role;
    if (strcmp(role, "assistant") == 0) {
        actualRole = _assistantRole.c_str();
    }
    _messages.push_back({strdup(actualRole), strdup(message)});
}

float
LLMInference::getResponseGenerationTime() const {
    if (_responseGenerationTime == 0) return 0.0f;
    return (float) _responseNumTokens / (_responseGenerationTime / 1e6);
}

int
LLMInference::getContextSizeUsed() const {
    return _nCtxUsed;
}

void
LLMInference::startCompletion(const char *query) {
    if (!_storeChats) {
        _formattedMessages.clear();
        _formattedMessages = std::vector<char>(llama_n_ctx(_ctx));
        _prevLen = 0;
        llama_memory_clear(llama_get_memory(_ctx), true);
    }
    _responseGenerationTime = 0;
    _responseNumTokens = 0;
    _response.clear();
    _cacheResponseTokens.clear();

    std::string queryString(query);
    if (queryString.find("<turn|") != std::string::npos || queryString.find("<start_of_turn>") != std::string::npos) {
         _promptTokens = tokenizeText(llama_model_get_vocab(_model), queryString, true, true);
    } else {
        addChatMessage(query, "user");

        int new_len = llama_chat_apply_template(
            _chatTemplate,
            _messages.data(),
            _messages.size(),
            true,
            _formattedMessages.data(),
            _formattedMessages.size()
        );
        if (new_len > (int)_formattedMessages.size()) {
            _formattedMessages.resize(new_len);
            new_len = llama_chat_apply_template(
                _chatTemplate,
                _messages.data(),
                _messages.size(),
                true,
                _formattedMessages.data(),
                _formattedMessages.size()
            );
        }

        if (new_len < 0) {
            LOGe("llama_chat_apply_template() failed, using fallback formatting");
            std::stringstream fallback;
            for (auto &msg : _messages) {
                fallback << msg.role << ": " << msg.content << "\n";
            }
            fallback << _assistantRole << ":";
            std::string prompt = fallback.str();
            _promptTokens = tokenizeText(llama_model_get_vocab(_model), prompt, true, true);
        } else {
            // Incremental prompt: everything before _prevLen is already in the
            // KV cache from earlier turns — only feed the new suffix (this turn's
            // user message + template glue). BOS only on the very first chunk.
            if (_prevLen > (size_t) new_len) {
                _prevLen = 0;
                llama_memory_clear(llama_get_memory(_ctx), true);
            }
            std::string prompt(_formattedMessages.begin() + _prevLen,
                               _formattedMessages.begin() + new_len);
            _promptTokens = tokenizeText(llama_model_get_vocab(_model), prompt,
                                            /*add_special=*/_prevLen == 0, /*parse_special=*/true);
        }
    }

    _batch.token = _promptTokens.data();
    _batch.n_tokens = _promptTokens.size();

    // Generation had no logging at all, so a stall was indistinguishable from
    // slowness in a logcat capture. Keep it to one line per turn.
    LOGi("startCompletion: %zu prompt tokens, ctxUsed=%d",
         _promptTokens.size(), llama_memory_seq_pos_max(llama_get_memory(_ctx), 0) + 1);
}

bool
LLMInference::_isValidUtf8(const char *response) {
    if (!response) {
        return true;
    }
    const unsigned char *bytes = (const unsigned char *) response;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

std::string
LLMInference::completionLoop() {
    uint32_t contextSize = llama_n_ctx(_ctx);
    _nCtxUsed = llama_memory_seq_pos_max(llama_get_memory(_ctx), 0) + 1;
    if (_nCtxUsed + _batch.n_tokens > (int) contextSize) {
        throw std::runtime_error("context size reached");
    }

    auto start = ggml_time_us();
    if (llama_decode(_ctx, _batch) < 0) {
        throw std::runtime_error("llama_decode() failed");
    }

    _currToken = llama_sampler_sample(_sampler, _ctx, -1);
    
    // Check if token is EOG or if the text so far contains stop markers
    bool is_eog = llama_vocab_is_eog(llama_model_get_vocab(_model), _currToken);
    
    std::string piece = tokenToPiece(_ctx, _currToken, true);
    
    // Turn-boundary safety net for models whose EOG token detection misses.
    // Plain-text stops like "###" were removed: they truncated legitimate
    // responses containing markdown headings.
    static const std::vector<std::string> stop_sequences = {
        "<turn|", "<|turn_end|>", "<turn_end|>", "<start_of_turn>", "<end_of_turn>"
    };

    std::string current_full = _response + _cacheResponseTokens + piece;
    for (const auto& stop : stop_sequences) {
        if (current_full.find(stop) != std::string::npos) {
            is_eog = true;
            break;
        }
    }

    if (is_eog) {
        addChatMessage(strdup(_response.data()), "assistant");
        if (_storeChats) {
            _updatePrevLen();
        }
        _response.clear();
        return "[EOG]";
    }

    auto end = ggml_time_us();
    _responseGenerationTime += (end - start);
    _responseNumTokens += 1;
    _cacheResponseTokens += piece;

    _batch.token = &_currToken;
    _batch.n_tokens = 1;

    if (_isValidUtf8(_cacheResponseTokens.c_str())) {
        _response += _cacheResponseTokens;
        std::string valid_utf8_piece = _cacheResponseTokens;
        _cacheResponseTokens.clear();
        return valid_utf8_piece;
    }

    return "";
}

void
LLMInference::stopCompletion() {
    if (_storeChats && !_response.empty()) {
        addChatMessage(_response.c_str(), "assistant");
        _updatePrevLen();
    }
    _response.clear();
    _cacheResponseTokens.clear();
}

LLMInference::~LLMInference() {
    for (llama_chat_message &message: _messages) {
        free(const_cast<char *>(message.role));
        free(const_cast<char *>(message.content));
    }
    if (_ctx) llama_free(_ctx);
    if (_model) llama_model_free(_model);
    if (_sampler) llama_sampler_free(_sampler);
}

std::string
LLMInference::benchModel(int pp, int tg, int pl, int nr) {
    g_batch     = llama_batch_init(pp, 0, pl);
    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        batchClear(g_batch);
        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            batchAdd(g_batch, 1, i, { 0 }, false);
        }
        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(this->_ctx), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(this->_ctx, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(this->_ctx), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            batchClear(g_batch);
            for (j = 0; j < pl; j++) {
                batchAdd(g_batch, 0, i, { j }, true);
            }
            if (llama_decode(this->_ctx, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(this->_ctx), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;
        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;
        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;
    }

    llama_batch_free(g_batch);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(this->_model, model_desc, sizeof(model_desc));
    const auto model_size     = double(llama_model_size(this->_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(this->_model)) / 1e9;

    std::stringstream result;
    result << std::setprecision(3);
    result << "Model: " << model_desc << " | " << model_size << " GiB | " << model_n_params << "B params\n";
    result << "PP " << pp << ": " << pp_avg << " +/- " << pp_std << " t/s\n";
    result << "TG " << tg << ": " << tg_avg << " +/- " << tg_std << " t/s\n";
    return result.str();
}
