package com.thermalbench.backend.api

import com.thermalbench.domain.model.ModelFormat

/**
 * A model file published online that this tool knows how to fetch and verify.
 *
 * ThermalBench still ships no models: the catalog is a list of links, not a
 * bundle. What it adds over a plain link is that the exact bytes are known in
 * advance. A downloaded file is checked against [sha256] before it is kept,
 * and a spec built from it pins that digest, so two devices that ran "Qwen2.5
 * 1.5B" provably ran the same file rather than two files with the same name.
 */
data class CatalogModel(
    /** Shown in the list, and used as the spec's model name. */
    val displayName: String,
    val format: ModelFormat,
    /** Exactly as published. It is also the name the file is saved under. */
    val fileName: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256, or null when the publisher does not expose it. */
    val sha256: String?,
    val quantization: String,
    val parameterCount: Long,
    val license: String,
    /** Where a person can read about the model and its licence. */
    val pageUrl: String,
    /**
     * Direct download link, or null when the publisher requires accepting a
     * licence first. Those can only be fetched from a browser that is signed in,
     * so they are offered as a page link and imported with the file picker.
     */
    val downloadUrl: String?,
    /** One line on why someone would pick this one. */
    val note: String,
) {
    /** Only a file whose digest is known can be fetched without a person checking it. */
    val downloadable: Boolean
        get() = downloadUrl != null && sha256 != null
}

/**
 * The curated list. Every size and digest below was read from the publisher's
 * own repository metadata, not computed from a copy, so a mismatch at download
 * time means the bytes changed in transit or upstream, and either way the file
 * is refused.
 *
 * Qwen2.5 1.5B Instruct appears in all three formats on purpose: it is the one
 * model here that lets backends be compared on the same weights.
 */
object ModelCatalog {

    val entries: List<CatalogModel> = listOf(
        // ---- llama.cpp (GGUF)
        huggingFace(
            displayName = "Qwen2.5 0.5B Instruct",
            format = ModelFormat.GGUF,
            repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 491_400_032L,
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            quantization = "Q4_K_M",
            parameterCount = 494_000_000L,
            license = "Apache-2.0",
            note = "Smallest here. Confirms the pipeline works; too light to heat a phone much.",
        ),
        huggingFace(
            displayName = "Llama 3.2 1B Instruct",
            format = ModelFormat.GGUF,
            repo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 807_694_464L,
            sha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
            quantization = "Q4_K_M",
            parameterCount = 1_240_000_000L,
            license = "Llama 3.2 Community",
            note = "Around a billion parameters, where sustained slowdown starts to show.",
        ),
        huggingFace(
            displayName = "Qwen2.5 1.5B Instruct",
            format = ModelFormat.GGUF,
            repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            quantization = "Q4_K_M",
            parameterCount = 1_540_000_000L,
            license = "Apache-2.0",
            note = "Also listed for LiteRT-LM and MediaPipe, for cross-backend comparison.",
        ),
        huggingFace(
            displayName = "Llama 3.2 3B Instruct",
            format = ModelFormat.GGUF,
            repo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 2_019_377_696L,
            sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
            quantization = "Q4_K_M",
            parameterCount = 3_210_000_000L,
            license = "Llama 3.2 Community",
            note = "Heaviest here. Throttles most phones within a few minutes.",
        ),

        // ---- LiteRT-LM (.litertlm)
        huggingFace(
            displayName = "Qwen3 0.6B",
            format = ModelFormat.LITERT_LM,
            repo = "litert-community/Qwen3-0.6B",
            fileName = "Qwen3-0.6B.litertlm",
            sizeBytes = 613_722_264L,
            sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
            quantization = "mixed",
            parameterCount = 600_000_000L,
            license = "Apache-2.0",
            note = "A quick first run on the reference backend.",
        ),
        huggingFace(
            displayName = "Qwen2.5 1.5B Instruct",
            format = ModelFormat.LITERT_LM,
            repo = "litert-community/Qwen2.5-1.5B-Instruct",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 1_597_922_856L,
            sha256 = "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e",
            quantization = "q8",
            parameterCount = 1_540_000_000L,
            license = "Apache-2.0",
            note = "Same model as the GGUF and MediaPipe entries.",
        ),
        huggingFace(
            displayName = "Gemma 3 1B IT",
            format = ModelFormat.LITERT_LM,
            repo = "litert-community/Gemma3-1B-IT",
            fileName = "gemma3-1b-it-int4.litertlm",
            sizeBytes = 584_417_280L,
            sha256 = null,
            quantization = "int4",
            parameterCount = 1_000_000_000L,
            license = "Gemma",
            note = "Requires accepting Google's licence on Hugging Face, then importing the file.",
            gated = true,
        ),
        huggingFace(
            displayName = "Gemma 3 1B IT · NPU, Snapdragon 8 Gen 3",
            format = ModelFormat.LITERT_LM,
            repo = "litert-community/Gemma3-1B-IT",
            fileName = "Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm",
            sizeBytes = 690_094_080L,
            sha256 = null,
            quantization = "q4",
            parameterCount = 1_000_000_000L,
            license = "Gemma",
            note = "Compiled for the SM8650 NPU only (e.g. Honor Magic V3); needs an NPU build. " +
                "Gated like the other Gemma files.",
            gated = true,
        ),

        // ---- MediaPipe GenAI (.task)
        huggingFace(
            displayName = "Qwen2.5 0.5B Instruct",
            format = ModelFormat.MEDIAPIPE_TASK,
            repo = "litert-community/Qwen2.5-0.5B-Instruct",
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeBytes = 546_660_344L,
            sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
            quantization = "q8",
            parameterCount = 494_000_000L,
            license = "Apache-2.0",
            note = "A quick cross-check against the other two backends.",
        ),
        huggingFace(
            displayName = "Qwen2.5 1.5B Instruct",
            format = ModelFormat.MEDIAPIPE_TASK,
            repo = "litert-community/Qwen2.5-1.5B-Instruct",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeBytes = 1_597_913_616L,
            sha256 = "8d867a7c93a6acf2892f08e0174e2f6f351ad256b7e3cfb6d6cd9c89794b42e0",
            quantization = "q8",
            parameterCount = 1_540_000_000L,
            license = "Apache-2.0",
            note = "Same model as the GGUF and LiteRT-LM entries.",
        ),
        huggingFace(
            displayName = "Gemma 3 1B IT",
            format = ModelFormat.MEDIAPIPE_TASK,
            repo = "litert-community/Gemma3-1B-IT",
            fileName = "gemma3-1b-it-int4.task",
            sizeBytes = 554_661_243L,
            sha256 = null,
            quantization = "int4",
            parameterCount = 1_000_000_000L,
            license = "Gemma",
            note = "Requires accepting Google's licence on Hugging Face, then importing the file.",
            gated = true,
        ),
    )

    /**
     * The catalog entry a file on disk came from, judged by name and exact size.
     * A same-named file of a different size is not that entry, so it gets no
     * digest pinned on its behalf.
     */
    fun match(fileName: String, sizeBytes: Long): CatalogModel? =
        entries.firstOrNull { it.fileName == fileName && it.sizeBytes == sizeBytes }

    @Suppress("LongParameterList")
    private fun huggingFace(
        displayName: String,
        format: ModelFormat,
        repo: String,
        fileName: String,
        sizeBytes: Long,
        sha256: String?,
        quantization: String,
        parameterCount: Long,
        license: String,
        note: String,
        gated: Boolean = false,
    ) = CatalogModel(
        displayName = displayName,
        format = format,
        fileName = fileName,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        quantization = quantization,
        parameterCount = parameterCount,
        license = license,
        pageUrl = "https://huggingface.co/$repo",
        downloadUrl = if (gated) null else "https://huggingface.co/$repo/resolve/main/$fileName",
        note = note,
    )
}
