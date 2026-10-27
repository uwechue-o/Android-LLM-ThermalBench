package com.thermalbench.tools.cli

import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.ModelFormat
import com.thermalbench.domain.model.ModelRef
import com.thermalbench.domain.model.RunProtocol
import com.thermalbench.domain.model.Sha256
import com.thermalbench.schema.Fixtures
import com.thermalbench.schema.JsonResultCodec
import java.util.Locale

/** Emits a ready-to-edit spec document with the identity already derived. */
object SpecTemplates {

    private const val HARNESS_MODELS_DIR =
        "/sdcard/Android/data/com.thermalbench.harness/files/models"

    /**
     * The extension names the format. Guessing it from anything else would let
     * a GGUF file be submitted as a LiteRT-LM model, which the validator would
     * then reject after the file had already been pushed to a fleet.
     */
    fun formatForPath(path: String): ModelFormat =
        when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
            "gguf" -> ModelFormat.GGUF
            "task", "bin" -> ModelFormat.MEDIAPIPE_TASK
            "pte" -> ModelFormat.EXECUTORCH_PTE
            else -> ModelFormat.LITERT_LM
        }

    /** A plausible default path for a backend, so `spec --backend x` alone works. */
    fun defaultPathFor(backendId: String): String = when (backendId) {
        BackendId.LLAMA_CPP.value -> "$HARNESS_MODELS_DIR/model.gguf"
        BackendId.MEDIAPIPE.value -> "$HARNESS_MODELS_DIR/model.task"
        BackendId.EXECUTORCH.value -> "$HARNESS_MODELS_DIR/model.pte"
        else -> "$HARNESS_MODELS_DIR/gemma-3n-e2b-it-q4.litertlm"
    }

    fun template(
        modelPath: String = defaultPathFor(BackendId.LITERT_LM.value),
        modelName: String = "gemma-3n-e2b-it-q4",
        backend: BackendId = BackendId.LITERT_LM,
        delegate: Delegate = Delegate.GPU,
        format: ModelFormat = ModelFormat.LITERT_LM,
        measuredIterations: Int? = null,
        outputTokens: Int? = null,
    ): String {
        val codec = JsonResultCodec()
        val protocol = measuredIterations
            ?.let { RunProtocol.DEFAULT.copy(measuredIterations = it) }
            ?: RunProtocol.DEFAULT
        val workload = outputTokens
            ?.let { Fixtures.referenceWorkload(it) }
            ?: Fixtures.referenceWorkload()

        val spec = Fixtures.spec(
            model = ModelRef(
                name = modelName,
                format = format,
                // Left unpinned in a template; the harness verifies existence and
                // the pinned digest goes in once the file is on the device.
                sha256 = Sha256("0".repeat(SHA256_HEX_LENGTH)),
                sizeBytes = 0L,
                path = modelPath,
                quantization = null,
            ),
            backend = backend,
            delegate = delegate,
            protocol = protocol,
            workload = workload,
            label = "$modelName on ${backend.value}/${delegate.name}",
            codec = codec,
        )
        return codec.encodeSpec(spec)
    }

    private const val SHA256_HEX_LENGTH = 64
}
