package com.thermalbench.ui.screen.composer

import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.Delegate

/**
 * Human-readable names for the inference backends.
 *
 * The domain identifies a backend by a stable machine id such as `litert-lm`,
 * because that string goes into result documents and must never change. That is
 * the wrong thing to put in front of an operator, so the display text lives
 * here in the UI layer and the id stays untouched.
 */
data class BackendPresentation(
    val displayName: String,
    /** What the runtime is, and what is worth knowing before choosing it. */
    val description: String,
    /** The model file extension this backend loads. */
    val fileExtension: String,
)

fun BackendId.presentation(): BackendPresentation = when (value) {
    BackendId.LITERT_LM.value -> BackendPresentation(
        displayName = "LiteRT-LM",
        description = "Google's on-device LLM runtime, and this tool's reference backend. " +
            "It reports prefill and decode separately, so time-to-first-token is measured rather than inferred.",
        fileExtension = ".litertlm",
    )

    BackendId.MEDIAPIPE.value -> BackendPresentation(
        displayName = "MediaPipe GenAI",
        description = "Google's MediaPipe Tasks runtime. It also stops on end-of-sequence, so the " +
            "measurement window closes at the requested token count.",
        fileExtension = ".task",
    )

    BackendId.LLAMA_CPP.value -> BackendPresentation(
        displayName = "llama.cpp",
        description = "The GGUF runtime the Google stack does not ship, which is how this tool covers " +
            "models the vendor benchmarks cannot.",
        fileExtension = ".gguf",
    )

    BackendId.QUALCOMM_GENIE.value -> BackendPresentation(
        displayName = "Qualcomm Genie",
        description = "Qualcomm's own LLM runtime (QAIRT). Runs on the Hexagon NPU or on Qualcomm's CPU path; " +
            "which one is fixed by how the model bundle was compiled.",
        fileExtension = "genie bundle",
    )

    BackendId.EXECUTORCH.value -> BackendPresentation(
        displayName = "ExecuTorch",
        description = "PyTorch's on-device runtime.",
        fileExtension = ".pte",
    )

    else -> BackendPresentation(
        displayName = value,
        description = "",
        fileExtension = "",
    )
}

/** What each compute unit means, kept short enough to sit under a row of chips. */
fun Delegate.explanation(): String = when (this) {
    Delegate.CPU -> "Runs on the main processor cores. Available everywhere, and usually the slowest."
    Delegate.GPU -> "Runs on the graphics processor. Commonly the fastest, and often the first to throttle."
    Delegate.NPU -> "Runs on the dedicated neural accelerator, where the device has one."
}
