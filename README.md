<div align="center">

<img src="Screenshots/orbal-icon.png" width="220" />

# Orbal

**Private AI for Android, on-device or your own server**

On-device inference via **llama.cpp** · Optional **Remote Mode** for LM Studio / Ollama / vLLM (OpenAI-compatible) · Tools, Vision, RAG, and auto-compaction: all in one Material 3 app.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-111111.svg?logo=kotlin&logoColor=white&labelColor=0A1020&color=0066FF)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-13%2B-111111.svg?logo=android&logoColor=white&labelColor=0A1020&color=0066FF)](https://developer.android.com)
[![Version](https://img.shields.io/badge/Version-1.0.0-111111.svg?labelColor=0A1020&color=0066FF)](https://github.com/Arcevalis/Orbal/releases)
[![License](https://img.shields.io/badge/License-MIT-111111.svg?labelColor=0A1020&color=0066FF)](LICENSE)
[![llama.cpp](https://img.shields.io/badge/llama.cpp-GGUF-111111.svg?labelColor=0A1020&color=0066FF)](https://github.com/ggerganov/llama.cpp)
[![GPU](https://img.shields.io/badge/GPU-Vulkan-111111.svg?labelColor=0A1020&color=0066FF)]()
[![Jetpack Compose](https://img.shields.io/badge/Compose-Material%203-111111.svg?logo=jetpackcompose&logoColor=white&labelColor=0A1020&color=0066FF)](https://developer.android.com/jetpack/compose)

</div>

> **Package:** `me.fss.orbal` · **Min SDK 33 · Target 37 · arm64-v8a + x86_64**

## Why Orbal

- **Your model, your device:** run any GGUF locally via llama.cpp with no cloud, no account.
- **Or your server:** flip **Remote Model** in Settings and point Orbal at `http://host:1234/v1` (LM Studio, Ollama, vLLM). Same chat UI, streaming, tools: bypasses the on-device model when enabled.
- **Built for long conversations:** auto-compaction with headroom estimation, queued follow-ups, true-bottom scroll, and a context bar that reads the server's *set limit* (e.g. 32k), not just the model's theoretical max.

<details>
<summary><b>📱 Screenshots</b></summary>

<p align="center"><em>Fresh Orbal screenshots coming soon. See `Screenshots/orbal-icon.png` above for the new icon.</em></p>

</details>

## Features

- **Dual inference:** on-device GGUF (Vulkan GPU + CPU dispatch with 7 kernel variants) *or* OpenAI-compatible remote (`POST /v1/chat/completions` streaming, `GET /v1/models` + `/api/v0/models` context probing).
- **Vision:** remote-only image input (up to 4×1024px JPEG80, 20 MB input → 2 MB output), Camera + Gallery + lightbox, base64 `data:image/jpeg` multipart, works together with tools (image + tool calls in one turn).
- **Tools (Standard):** clock, calculator, web search, URL read, knowledge-base search, expression parser. Runs locally; only `web_search` hits the network. Loop capped at 5 steps (configurable 1–10), 24k char result cap.
- **Knowledge Base:** `knowledge_documents` / `knowledge_chunks` (800/100, Room), `LIKE` top-5 RAG injected before generation.
- **Compaction:** opt-in summarization at ~60–85% of effective context (default 85%). LLM summary via `google_gemma-4-e2b-it` (temp 0.2, 600 tokens) with heuristic fallback. Keeps last 4–20 messages verbatim (12-image cap).
- **Queue and Compaction UX:** queue follow-ups while generating; keep typing (send locked) during compaction; `Effective: 32768 tokens` + server `loaded_context_length` display; `isAtBottom` true-bottom detection with overhang `scrollBy`; `Scroll to bottom` FAB.
- **Streaming and Tools:** `streamChatCompletionWithTools` loop, reasoning content separated, token/sec live.
- **Chat Polish:** single-line header with ellipsis (`Orbal ● model … · tools`), edit-only-user prompts, regenerated-delete-after, markdown + TTS, thinking blocks (`<think>`, Gemma `<|channel>thought`, Qwen `<|channel|>analysis`) with strip toggle, context bar.
- **Theming:** 11 Ptyxis palettes (Cobalt Neon), Catppuccin ×4 + Dracula ×7, System/Light/Dark/AMOLED, Material You, monochrome toggle, 13 fonts + text scale.
- **Performance Controls:** GPU layers, CPU threads, mmap, ML ock, KV cache quantized, next-load semantics.
- **Security:** EncryptedSharedPreferences, biometric lock, `memtagMode="sync"`, FileProvider, cleartext allowed for local net only, tamper detection (trusted certs).

## Install

**Requires Android 13+ and arm64-v8a** (most devices since 2019).

1. Download the APK from [Releases](https://github.com/Arcevalis/Orbal/releases)
2. **Settings → Apps → Install unknown apps** → allow your file manager
3. Install the APK, finish onboarding
4. Either:
   - **Local:** Settings → Model → Import GGUF, *or*
   - **Remote:** Settings → Remote Model → enable, set `http://YOUR_HOST:1234/v1`, Load a model in LM Studio / Ollama first.

Or via ADB:

```bash
adb install Orbal_v1.0.0.apk
```

> **Signing:** release builds are signed with the Orbal debug keystore for now (`359a362...`). If you re-sign, update `SignatureVerifier.TRUSTED_DIGEST`.

## Remote Model Setup

- **Emulator:** `http://10.0.2.2:1234/v1`
- **Physical device:** `http://192.168.1.x:1234/v1` (your host LAN IP)
- In LM Studio: enable server + CORS, `lms load google_gemma-4-e2b-it -y` (auto-loads its `mmproj` for vision). Or with Ollama/vLLM: any OpenAI-compatible endpoint at `/v1`.
- Orbal polls `GET /api/v0/models` → `loaded_context_length` (fallback probes `/v1/models`, `/props`, `/api/show`). Effective context drives the bar + compaction thresholds.

## Recommended Models (GGUF, Q4_K_M)

| Model | Size | Best for |
| :--- | :--- | :--- |
| `gemma-3-270m-it-qat-Q4_K_M.gguf` | ~300 MB | 2–4 GB RAM, fast |
| `Qwen3 0.8B Q4_K_M` | ~530 MB | 4–6 GB RAM |
| `gemma-4-E2B-it` (2.3B eff.) + `mmproj` | ~1.3 GB | **6–8 GB RAM** |
| `gemma-4-E4B-it` (4.5B eff.) | ~2.5 GB | **8 GB+ RAM** |

Search `+ GGUF` on [HuggingFace](https://huggingface.co). For vision, pair an `mmproj-*.gguf` with the text model (Gemma 4 loads it automatically).

## Build from Source

**Prereqs:** JDK 17, Android SDK (compileSdk 37), NDK 27.3, CMake 3.22.1+, host gcc/g++ for Vulkan shader gen.

```bash
git clone --recurse-submodules https://github.com/Arcevalis/Orbal.git
cd Orbal

# Khronos headers for Vulkan (inside project root, gitignored)
git clone --branch v1.4.351 --depth 1 https://github.com/KhronosGroup/Vulkan-Headers.git
git clone https://github.com/KhronosGroup/SPIRV-Headers.git
cmake -S SPIRV-Headers -B SPIRV-Headers/build -DCMAKE_INSTALL_PREFIX=SPIRV-Headers/install
cmake --install SPIRV-Headers/build

# Optional: bundle a GGUF
cp /path/to/model.gguf app/src/main/assets/model/

# Debug APK (fast)
CCACHE_DISABLE=1 ./gradlew assembleDebug

# Release (minified, 100M+ universal)
./gradlew clean assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

First build compiles llama.cpp + ~1,400 Vulkan shaders + 7 CPU variants (~20–30 min). Later builds are incremental.

<details>
<summary><b>Project structure</b></summary>

- **`smollm/`**: Native llama.cpp JNI module (`me.fss.orbal.smollm`)
  - `src/main/cpp/`: `smollm.cpp`, `GGUFReader.cpp`, `LLMInference.*`
  - `src/main/java/`: `SmolLM.kt`, `GGUFReader.kt`
- **`app/`**: Android app (`me.fss.orbal`)
  - `ai/`: `InferenceEngine`, `ModelManager`, `remote/*` (OpenAI, LmStudioClient, RemoteInferenceEngine), `tools/*`, `compaction/*`
  - `data/`: Room v4, DAOs, `rag/`, repositories
  - `di/`: Hilt modules
  - `ui/`: Compose screens, components, `theme/` (`OrbalTheme`), `navigation/`
  - `utils/`: `BiometricHelper`, `SignatureVerifier`, etc.
- **`llama.cpp/`**: submodule
</details>

## Performance Notes

- **CPU:** 7 ggml-cpu variants (armv8.0 → armv9.2) scored at load; prompt uses all cores, generation on big cores. `DOTPROD + MATMUL_INT8` = fast path; `NEON` only = baseline.
- **GPU:** Vulkan opt-in, per-layer offload. Adreno + cooperative-matrix wins; Mali midrange may tie CPU. Auto fallback to CPU on load failure.
- **Long chats:** incremental prompt: only new message re-processed.
- **Compaction:** when `input > threshold × effective` (headroom `maxTokens+512` reserved so output never capped). Keeps `keepRecent` verbatim.

## Security & Privacy

- On-device path has no required network (when Remote disabled, your data stays on device).
- Remote path: **you** choose the server on your LAN; only that host + `web_search` (if used) touches the network. No analytics, no Firebase, no tracking.
- Encrypted settings, biometric lock, `memtagMode="sync"`, secure deletion, `network_security_config` cleartext only for local net.

## License

MIT. llama.cpp is also MIT. Wrapper adapted from [SmolChat-Android](https://github.com/shubham0204/SmolChat-Android) (Apache 2.0).

---

<div align="center">

**Orbal** · `me.fss.orbal` · built with Kotlin, Compose, and llama.cpp

</div>
