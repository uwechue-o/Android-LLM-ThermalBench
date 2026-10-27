package com.thermalbench.domain

import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.BackendCapabilities
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.BackendRef
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.GenerationResult
import com.thermalbench.domain.model.LoadedModel
import com.thermalbench.domain.model.ModelFormat
import com.thermalbench.domain.model.ModelRef
import com.thermalbench.domain.model.PreconditionPolicy
import com.thermalbench.domain.model.RunProtocol
import com.thermalbench.domain.model.SemVer
import com.thermalbench.domain.model.Sha256
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.model.StopCondition
import com.thermalbench.domain.model.Workload
import com.thermalbench.domain.port.BackendRegistry
import com.thermalbench.domain.port.InferenceBackend
import com.thermalbench.domain.usecase.SpecValidation
import com.thermalbench.domain.usecase.ValidateSpecUseCase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ValidateSpecUseCaseTest {

    private class FakeBackend(
        override val id: BackendId,
        private val capabilities: BackendCapabilities,
    ) : InferenceBackend {
        override suspend fun capabilities() = capabilities
        override suspend fun load(model: ModelRef, delegate: Delegate, options: Map<String, String>): LoadedModel =
            error("not used")
        override suspend fun generate(model: LoadedModel, workload: Workload, seed: Long): GenerationResult =
            error("not used")
        override suspend fun unload(model: LoadedModel) = Unit
    }

    private fun registry(vararg backends: InferenceBackend) = object : BackendRegistry {
        override fun all() = backends.toList()
        override fun find(id: BackendId) = backends.firstOrNull { it.id == id }
    }

    private fun capabilities(
        available: Boolean = true,
        formats: Set<ModelFormat> = setOf(ModelFormat.LITERT_LM),
        delegates: Set<Delegate> = setOf(Delegate.CPU, Delegate.GPU),
        tokenCountStop: Boolean = true,
        gpuApis: List<String> = emptyList(),
    ) = BackendCapabilities(
        id = BackendId.LITERT_LM,
        available = available,
        unavailableReason = if (available) null else "no native library",
        formats = formats,
        delegates = delegates,
        supportsTokenCountStop = tokenCountStop,
        supportsStreaming = true,
        runtimeVersion = "test",
        gpuApis = gpuApis,
    )

    private fun spec(
        outputTokens: Int = 128,
        stopTokens: Int = 128,
        measuredIterations: Int = 50,
        delegate: Delegate = Delegate.GPU,
        format: ModelFormat = ModelFormat.LITERT_LM,
        backend: BackendId = BackendId.LITERT_LM,
        options: Map<String, String> = emptyMap(),
    ) = BenchmarkSpec(
        specId = SpecId("test"),
        model = ModelRef("m", format, Sha256("0".repeat(64)), 1, "/tmp/m"),
        backend = BackendRef(backend, delegate, options),
        workload = Workload(128, outputTokens, Sha256("1".repeat(64)), StopCondition.TokenCount(stopTokens), "prompt"),
        protocol = RunProtocol(3, measuredIterations, kotlin.time.Duration.ZERO, true, 45.minutes),
        preconditions = PreconditionPolicy.DEFAULT,
        seed = 1,
        toolVersion = SemVer(0, 1, 0),
    )

    private val validBackend = FakeBackend(BackendId.LITERT_LM, capabilities())

    @Test
    fun `a well formed spec passes`() = runTest {
        val validate = ValidateSpecUseCase(registry(validBackend))
        assertThat(validate(spec())).isEqualTo(SpecValidation.Valid)
    }

    @Test
    fun `a stop count that disagrees with the output length is fatal`() = runTest {
        val validate = ValidateSpecUseCase(registry(validBackend))
        val result = validate(spec(outputTokens = 128, stopTokens = 64))
        assertThat(result).isInstanceOf(SpecValidation.Invalid::class.java)
        val problems = (result as SpecValidation.Invalid).problems
        assertThat(problems.single().field).isEqualTo("workload.stopCondition")
    }

    @Test
    fun `too few iterations is a warning, not a rejection`() = runTest {
        val validate = ValidateSpecUseCase(registry(validBackend))
        // The operator may deliberately want a short smoke run; the analysis
        // will mark it INSUFFICIENT_DATA rather than the validator refusing it.
        assertThat(validate(spec(measuredIterations = 10))).isEqualTo(SpecValidation.Valid)
    }

    @Test
    fun `an unregistered backend is rejected before any device time is spent`() = runTest {
        val validate = ValidateSpecUseCase(registry(validBackend))
        val result = validate(spec(backend = BackendId.LLAMA_CPP))
        assertThat(result).isInstanceOf(SpecValidation.Invalid::class.java)
    }

    @Test
    fun `an unavailable backend is rejected with its reason`() = runTest {
        val backend = FakeBackend(BackendId.LITERT_LM, capabilities(available = false))
        val result = ValidateSpecUseCase(registry(backend))(spec())
        val problems = (result as SpecValidation.Invalid).problems
        assertThat(problems.first().message).contains("no native library")
    }

    @Test
    fun `a delegate the backend does not offer is rejected`() = runTest {
        val validate = ValidateSpecUseCase(registry(validBackend))
        val result = validate(spec(delegate = Delegate.NPU))
        assertThat(result).isInstanceOf(SpecValidation.Invalid::class.java)
    }

    @Test
    fun `a format the backend cannot load is rejected`() = runTest {
        val validate = ValidateSpecUseCase(registry(validBackend))
        val result = validate(spec(format = ModelFormat.GGUF))
        assertThat(result).isInstanceOf(SpecValidation.Invalid::class.java)
    }

    @Test
    fun `a backend that cannot stop on a token count is rejected as incomparable`() = runTest {
        val backend = FakeBackend(BackendId.LITERT_LM, capabilities(tokenCountStop = false))
        val result = ValidateSpecUseCase(registry(backend))(spec())
        val problems = (result as SpecValidation.Invalid).problems
        assertThat(problems.first().message).contains("not comparable")
    }

    @Test
    fun `a stabilization window longer than the maximum wait can never be satisfied`() = runTest {
        val validate = ValidateSpecUseCase(registry(validBackend))
        val impossible = spec().copy(
            preconditions = PreconditionPolicy.DEFAULT.copy(
                stabilizationWindow = 20.minutes,
                maxWaitForPreconditions = 30.seconds,
            ),
        )
        assertThat(validate(impossible)).isInstanceOf(SpecValidation.Invalid::class.java)
    }

    @Test
    fun `a gpu api the device offers passes, in any case`() = runTest {
        val backend = FakeBackend(BackendId.LITERT_LM, capabilities(gpuApis = listOf("OpenCL", "Vulkan")))
        val result = ValidateSpecUseCase(registry(backend))(spec(options = mapOf(BackendRef.OPTION_GPU_API to "vulkan")))
        assertThat(result).isEqualTo(SpecValidation.Valid)
    }

    @Test
    fun `a gpu api the device does not offer is rejected, naming what it does offer`() = runTest {
        val backend = FakeBackend(BackendId.LITERT_LM, capabilities(gpuApis = listOf("Vulkan")))
        val result = ValidateSpecUseCase(registry(backend))(spec(options = mapOf(BackendRef.OPTION_GPU_API to "OpenCL")))
        val problem = (result as SpecValidation.Invalid).problems.single()
        assertThat(problem.field).isEqualTo("backend.options.gpuApi")
        assertThat(problem.message).contains("[Vulkan]")
    }

    @Test
    fun `a gpu api on a cpu run is rejected`() = runTest {
        val backend = FakeBackend(BackendId.LITERT_LM, capabilities(gpuApis = listOf("Vulkan")))
        val cpu = spec(delegate = Delegate.CPU, options = mapOf(BackendRef.OPTION_GPU_API to "Vulkan"))
        assertThat(ValidateSpecUseCase(registry(backend))(cpu)).isInstanceOf(SpecValidation.Invalid::class.java)
    }
}
