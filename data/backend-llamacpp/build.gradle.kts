// data/backend-llamacpp — llama.cpp JNI adapter (GGUF). The differentiator:
// the format Google's stack does not ship.
//
// The native build needs the NDK + CMake and a llama.cpp checkout. It is gated
// behind `thermalbench.nativeLlama=true` (gradle.properties) so the project
// builds on machines without an NDK; without it the Kotlin adapter still
// compiles and reports the backend as unavailable at runtime.
plugins {
    alias(libs.plugins.thermalbench.android.library)
    alias(libs.plugins.thermalbench.android.hilt)
}

val nativeLlama: Boolean =
    providers.gradleProperty("thermalbench.nativeLlama").map { it.toBoolean() }.getOrElse(false)
val llamaCppDir: String =
    providers.gradleProperty("thermalbench.llamaCppDir").getOrElse("")
val llamaCppTag: String =
    providers.gradleProperty("thermalbench.llamaCppTag").getOrElse("")

// Vulkan GPU offload, off by default: it needs Khronos header checkouts and, on
// Windows, Visual Studio Build Tools to compile llama.cpp's shader generator.
val llamaVulkan: Boolean =
    providers.gradleProperty("thermalbench.llamaVulkan").map { it.toBoolean() }.getOrElse(false)
val vulkanHeadersDir: String =
    providers.gradleProperty("thermalbench.vulkanHeadersDir").getOrElse("")
val spirvHeadersDir: String =
    providers.gradleProperty("thermalbench.spirvHeadersDir").getOrElse("")

// OpenCL GPU offload, llama.cpp's Adreno-tuned path. Needs only an
// OpenCL-Headers checkout; the library itself is the device vendor's.
val llamaOpenCl: Boolean =
    providers.gradleProperty("thermalbench.llamaOpenCl").map { it.toBoolean() }.getOrElse(false)
val openClHeadersDir: String =
    providers.gradleProperty("thermalbench.openClHeadersDir").getOrElse("")

// The OpenCL version ggml's OpenCL backend is built against. 200 loads on the
// OpenCL 2.0 drivers of Snapdragon 865-era phones, which lack the 2.1 and 3.0
// entry points a higher target links to (clGetKernelSubGroupInfo,
// clCreateBufferWithProperties), so the backend would fail to load there.
// Newer Adrenos then forgo only a large-buffer fallback and two
// subgroup-specialised kernels. Raise it for a fleet of recent phones only.
val openClTargetVersion: String =
    providers.gradleProperty("thermalbench.openClTargetVersion").getOrElse("200")

// Fail at configuration with a readable message rather than deep inside CMake.
if (nativeLlama && !file("$llamaCppDir/include/llama.h").exists()) {
    throw GradleException(
        "thermalbench.nativeLlama=true but thermalbench.llamaCppDir='$llamaCppDir' is not a " +
            "llama.cpp checkout. Clone https://github.com/ggml-org/llama.cpp and point the " +
            "property at it, or set thermalbench.nativeLlama=false.",
    )
}

android {
    namespace = "com.thermalbench.backend.llamacpp"

    if (nativeLlama) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
        defaultConfig {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLLAMA_CPP_DIR=$llamaCppDir",
                        "-DLLAMA_CPP_EXPECTED_TAG=$llamaCppTag",
                        "-DANDROID_STL=c++_shared",
                        // 16 KB page alignment for every library this builds,
                        // llama.cpp's included. NDK r27 defaults to 4 KB, which
                        // fails to load on 16 KB-page devices.
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        // Always optimised, even in a debug variant. AGP would
                        // otherwise build the native code as Debug, and an
                        // unoptimised llama.cpp is roughly an order of magnitude
                        // slower. A benchmark built that way measures its own
                        // compiler flags rather than the device, and would look
                        // like a valid thermal curve while being nothing of the
                        // sort.
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DTHERMALBENCH_VULKAN=${if (llamaVulkan) "ON" else "OFF"}",
                        "-DVULKAN_HEADERS_DIR=$vulkanHeadersDir",
                        "-DSPIRV_HEADERS_DIR=$spirvHeadersDir",
                        "-DTHERMALBENCH_OPENCL=${if (llamaOpenCl) "ON" else "OFF"}",
                        "-DOPENCL_HEADERS_DIR=$openClHeadersDir",
                        "-DGGML_OPENCL_TARGET_VERSION=$openClTargetVersion",
                    )
                    cppFlags += listOf("-std=c++17")
                }
            }
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":data:backend-api"))
    implementation(libs.androidx.tracing.ktx)
}
