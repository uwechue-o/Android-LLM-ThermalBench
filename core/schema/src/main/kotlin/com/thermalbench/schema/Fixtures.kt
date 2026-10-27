package com.thermalbench.schema

import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BackendRef
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.ModelFormat
import com.thermalbench.domain.model.ModelRef
import com.thermalbench.domain.model.PreconditionPolicy
import com.thermalbench.domain.model.RunProtocol
import com.thermalbench.domain.model.SemVer
import com.thermalbench.domain.model.Sha256
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.model.StopCondition
import com.thermalbench.domain.model.Workload

/**
 * Reference workload and spec builders shared by the app's spec composer, the
 * CLI, and the tests. The prompt is fixed on purpose: its hash is part of the
 * run identity.
 */
object Fixtures {

    /** ~128 tokens of neutral English prose; deterministic across runs and devices. */
    val REFERENCE_PROMPT: String = buildString {
        append("You are a careful technical writer. In plain English, explain how a mobile ")
        append("phone manages heat while running a demanding application for a long time. ")
        append("Cover the roles of the battery, the system-on-chip, the operating system's ")
        append("thermal policy, and the trade-off between performance and temperature. ")
        append("Write in complete sentences, avoid lists, and keep a steady, measured tone ")
        append("throughout the whole explanation without stopping early.")
    }

    const val REFERENCE_PROMPT_TOKENS: Int = 128
    const val REFERENCE_OUTPUT_TOKENS: Int = 128

    val TOOL_VERSION: SemVer = SemVer(0, 1, 0)

    fun referenceWorkload(outputTokens: Int = REFERENCE_OUTPUT_TOKENS): Workload = Workload(
        promptTokens = REFERENCE_PROMPT_TOKENS,
        maxOutputTokens = outputTokens,
        promptSha256 = JsonResultCodec.sha256(REFERENCE_PROMPT),
        stopCondition = StopCondition.TokenCount(outputTokens),
        prompt = REFERENCE_PROMPT,
    )

    /** Builds a spec and derives its id from its own content. */
    fun spec(
        model: ModelRef,
        backend: BackendId,
        delegate: Delegate,
        protocol: RunProtocol = RunProtocol.DEFAULT,
        preconditions: PreconditionPolicy = PreconditionPolicy.DEFAULT,
        workload: Workload = referenceWorkload(),
        seed: Long = 42L,
        label: String = "",
        options: Map<String, String> = emptyMap(),
        codec: JsonResultCodec = JsonResultCodec(),
    ): BenchmarkSpec = codec.withDerivedId(
        BenchmarkSpec(
            specId = SpecId("pending"),
            model = model,
            backend = BackendRef(backend, delegate, options),
            workload = workload,
            protocol = protocol,
            preconditions = preconditions,
            seed = seed,
            toolVersion = TOOL_VERSION,
            label = label,
        ),
    )

    /** A placeholder model reference for previews and tests. */
    fun placeholderModel(format: ModelFormat = ModelFormat.LITERT_LM): ModelRef = ModelRef(
        name = "gemma-3n-e2b-it-q4",
        format = format,
        sha256 = Sha256("0".repeat(64)),
        sizeBytes = 0L,
        path = "/sdcard/Android/data/com.thermalbench.harness/files/models/gemma-3n-e2b-it-q4.litertlm",
        quantization = "q4",
        parameterCount = 2_000_000_000L,
    )
}
