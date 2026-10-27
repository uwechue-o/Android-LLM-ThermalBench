package com.thermalbench.backend.llamacpp

/**
 * JNI surface of the llama.cpp adapter. Mirrors `llamacpp_jni.cpp`.
 *
 * The library is loaded lazily and the failure is captured rather than thrown,
 * because a device without the native build must report the backend as
 * unavailable, not crash a run that is already an hour deep.
 */
internal object LlamaCppNative {

    /** Null when the native library loaded; the reason it did not, otherwise. */
    val loadFailure: String? = try {
        System.loadLibrary(LIBRARY_NAME)
        null
    } catch (e: Throwable) {
        "llama.cpp native library '$LIBRARY_NAME' not present in this build: " +
            "${e.javaClass.simpleName}: ${e.message}. Rebuild with -Pthermalbench.nativeLlama=true."
    }

    val available: Boolean get() = loadFailure == null

    /**
     * Returns an opaque handle, or 0 on failure. With [gpuLayers] > 0 the model
     * is offloaded to the one GPU whose API is [gpuApi] ("Vulkan", "OpenCL").
     */
    external fun loadModel(path: String, threads: Int, contextSize: Int, gpuLayers: Int, gpuApi: String?): Long

    external fun freeModel(handle: Long)

    /** Tokenizes without generating; used to verify a spec's declared prompt length. */
    external fun tokenCount(handle: Long, text: String): Int

    /** Runs prompt ingestion only. Returns the number of prompt tokens processed. */
    external fun prefill(handle: Long, prompt: String, seed: Long): Int

    /**
     * Decodes exactly one token and returns its text, or null at end of stream.
     * One token per call is what makes an exact token-count stop possible.
     */
    external fun decodeToken(handle: Long): String?

    external fun resetContext(handle: Long)

    external fun runtimeVersion(): String

    /**
     * The GPUs llama.cpp can drive here, each "api	device	description".
     * Empty when no GPU backend is built in or none found a device; offloading
     * would then be silently ignored.
     */
    external fun gpuDevices(): Array<String>

    private const val LIBRARY_NAME = "thermalbench_llama"
}
