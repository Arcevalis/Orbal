plugins {
    alias(libs.plugins.android.library)
}

val sdkDir: String = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.startsWith("sdk.dir=") }
    ?.removePrefix("sdk.dir=")
    ?.trim()
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: ""

// Toolchain versions are named once here — the NDK version and the CMake version
// are each referenced from several places below (ndkVersion, the glslc path, the
// ninja path, the AGP cmake block), and those drifting apart silently produces a
// path to a directory that no longer exists.
val ndkVersionPinned = "27.3.13750724"

// Newest CMake present in the SDK, so installing a newer one from the SDK Manager
// is picked up without editing this file. llama.cpp declares
// cmake_minimum_required(3.14...3.28), so any SDK CMake satisfies it. Falls back
// to the version AGP bundles when the SDK dir can't be read.
val cmakeVersionPinned: String =
    file("$sdkDir/cmake")
        .takeIf { it.isDirectory }
        ?.listFiles()
        ?.filter { it.isDirectory && File(it, "bin/ninja").exists() }
        ?.maxByOrNull { dir ->
            val p = dir.name.split('.').map { it.toIntOrNull() ?: 0 }
            p.getOrElse(0) { 0 } * 1_000_000L + p.getOrElse(1) { 0 } * 1_000L + p.getOrElse(2) { 0 }
        }
        ?.name
        ?: "3.22.1"

// Host toolchain for llama.cpp's vulkan-shaders-gen, which must run on the build
// machine (not Android). Mirrors upstream's host-toolchain.cmake.in but also pins
// CMAKE_MAKE_PROGRAM to the SDK's ninja when present, since the host may have no
// ninja on PATH and the inner ExternalProject configure fails without one.
val vulkanHostToolchain: File =
    layout.buildDirectory.file("vulkan-host-toolchain.cmake").get().asFile.apply {
        parentFile.mkdirs()
        val sdkNinja = file("$sdkDir/cmake/$cmakeVersionPinned/bin/ninja")
        val makeProgramLine =
            if (sdkDir.isNotEmpty() && sdkNinja.exists())
                "\nset(CMAKE_MAKE_PROGRAM \"${sdkNinja.absolutePath}\" CACHE FILEPATH \"\")"
            else ""
        writeText(
            """
            set(CMAKE_BUILD_TYPE Release)
            set(CMAKE_C_FLAGS -O2)
            set(CMAKE_CXX_FLAGS -O2)
            set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
            set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
            set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)
            set(CMAKE_C_COMPILER /usr/bin/gcc)
            set(CMAKE_CXX_COMPILER /usr/bin/g++)
            """.trimIndent() + makeProgramLine
        )
    }

android {
    namespace = "me.fss.orbal.smollm"
    compileSdk = 37
    ndkVersion = ndkVersionPinned

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O3")
                // The SPIRV include dir is passed as a cache var further down
                // (-DSPIRV_HEADERS_INCLUDE_DIR) and consumed in our CMakeLists, rather
                // than pushed through cppFlags — cppFlags becomes CMAKE_CXX_FLAGS, which
                // is whitespace-split and applies to every C++ target in the build.
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                // common drags in HTTP/download code and is no longer needed —
                // the JNI wrapper uses the llama.h C API directly.
                arguments += "-DLLAMA_BUILD_COMMON=OFF"
                // Vulkan GPU backend (opt-in at runtime via Settings; devices without
                // Vulkan 1.2 fail registration gracefully and stay CPU-only).
                arguments += "-DGGML_VULKAN=ON"
                // Backends stay runtime-loadable so the Vulkan plugin can register (or
                // fail) independently of the CPU one.
                arguments += "-DGGML_BACKEND_DL=ON"
                // One ggml-cpu plugin per ARM feature level, scored against the device
                // at load time — this is what puts dotprod/fp16/i8mm/SVE kernels on the
                // SoCs that have them, while still running on armv8.0. Mutually exclusive
                // with a fixed GGML_CPU_ARM_ARCH pin, which would trade the whole ladder
                // for one hard-coded floor.
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                if (sdkDir.isNotEmpty()) {
                    // Only pin ninja when the SDK actually ships one — hosts like
                    // F-Droid's buildserver ship a different one and AGP falls back fine.
                    val ninja = file("$sdkDir/cmake/$cmakeVersionPinned/bin/ninja")
                    if (ninja.exists()) {
                        arguments += "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}"
                    }
                    // glslc ships with the NDK; FindVulkan won't discover it in the
                    // cross-compile sysroot on its own.
                    arguments += "-DVulkan_GLSLC_EXECUTABLE=$sdkDir/ndk/$ndkVersionPinned/shader-tools/linux-x86_64/glslc"
                    arguments += "-DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=${vulkanHostToolchain.absolutePath}"
                    // The NDK sysroot has only the Vulkan C headers; ggml-vulkan.cpp
                    // needs Vulkan-Hpp (vulkan.hpp). Point FindVulkan at a checkout of
                    // KhronosGroup/Vulkan-Headers, whose include dir carries a
                    // self-consistent set of both C and C++ headers.
                    val vulkanHeaders = rootProject.file("Vulkan-Headers/include")
                    if (vulkanHeaders.exists()) {
                        arguments += "-DVulkan_INCLUDE_DIR=${vulkanHeaders.absolutePath}"
                    } else {
                        logger.warn(
                            "Vulkan-Headers not found at ${vulkanHeaders.absolutePath} — " +
                            "ggml-vulkan will fail to compile. Clone " +
                            "https://github.com/KhronosGroup/Vulkan-Headers.git into the project root."
                        )
                    }
                    // Raw include dir for the direct <spirv/unified1/spirv.hpp> include,
                    // consumed by include_directories(SYSTEM ...) in our CMakeLists.
                    val spirvInclude = rootProject.file("SPIRV-Headers/include")
                    if (spirvInclude.exists()) {
                        arguments += "-DSPIRV_HEADERS_INCLUDE_DIR=${spirvInclude.absolutePath}"
                    }
                    // SPIRV-Headers CMake package (header-only; installed locally from a
                    // clone of KhronosGroup/SPIRV-Headers via `cmake --install`) — this is
                    // what ggml-vulkan's find_package(SPIRV-Headers CONFIG REQUIRED) needs.
                    val spirvHeaders = rootProject.file("SPIRV-Headers/install")
                    if (spirvHeaders.exists()) {
                        arguments += "-DSPIRV-Headers_DIR=${spirvHeaders.absolutePath}/share/cmake/SPIRV-Headers"
                    } else {
                        logger.warn(
                            "SPIRV-Headers install not found at ${spirvHeaders.absolutePath} — " +
                            "ggml-vulkan will fail to configure."
                        )
                    }
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = cmakeVersionPinned
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.coroutines.core)
}
