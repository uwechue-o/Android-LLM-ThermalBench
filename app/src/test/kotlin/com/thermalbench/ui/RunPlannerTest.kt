package com.thermalbench.ui

import com.google.common.truth.Truth.assertThat
import com.thermalbench.backend.api.DiscoveredModel
import com.thermalbench.domain.model.BackendId
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.ModelFormat
import org.junit.Test

class RunPlannerTest {

    private val llama = BackendOption(
        id = BackendId.LLAMA_CPP,
        available = true,
        unavailableReason = null,
        delegates = setOf(Delegate.GPU, Delegate.CPU),
        formats = setOf(ModelFormat.GGUF),
        gpuApis = listOf("OpenCL", "Vulkan"),
    )
    private val litert = BackendOption(
        id = BackendId.LITERT_LM,
        available = true,
        unavailableReason = null,
        delegates = setOf(Delegate.CPU, Delegate.GPU, Delegate.NPU),
        formats = setOf(ModelFormat.LITERT_LM),
    )
    private val backends = listOf(llama, litert)

    private val qwen = DiscoveredModel("qwen.gguf", "/m/qwen.gguf", 1L, ModelFormat.GGUF)
    private val gemma = DiscoveredModel("gemma.litertlm", "/m/gemma.litertlm", 1L, ModelFormat.LITERT_LM)

    private val form = SpecForm(
        modelPath = qwen.path,
        modelName = "qwen",
        modelFormat = ModelFormat.GGUF,
        backendId = BackendId.LLAMA_CPP,
        delegate = Delegate.GPU,
        gpuApi = "Vulkan",
        label = "evening",
    )

    @Test
    fun `a single run is the form as it stands`() {
        val runs = form.planRuns(backends, listOf(qwen))
        assertThat(runs.map { it.form }).containsExactly(form)
    }

    @Test
    fun `compute units cover cpu and every gpu api, cpu first`() {
        val runs = form.copy(comparison = Comparison.COMPUTE_UNITS).planRuns(backends, listOf(qwen))
        assertThat(runs.map { it.form.delegate to it.form.gpuApi }).containsExactly(
            Delegate.CPU to null,
            Delegate.GPU to "OpenCL",
            Delegate.GPU to "Vulkan",
        ).inOrder()
        // Everything else held fixed, and each run named in its label.
        assertThat(runs.map { it.form.modelPath }.distinct()).containsExactly(qwen.path)
        assertThat(runs.map { it.form.label }).containsExactly(
            "evening · llama.cpp · CPU",
            "evening · llama.cpp · GPU (OpenCL)",
            "evening · llama.cpp · GPU (Vulkan)",
        ).inOrder()
        assertThat(runs.map { it.form.comparison }.distinct()).containsExactly(Comparison.SINGLE)
    }

    @Test
    fun `a model comparison needs its second model`() {
        val waiting = form.copy(comparison = Comparison.OTHER_MODEL).planRuns(backends, listOf(qwen, gemma))
        assertThat(waiting).hasSize(1)
    }

    @Test
    fun `the second model follows its format to a backend that can load it`() {
        val runs = form.copy(comparison = Comparison.OTHER_MODEL, compareModelPath = gemma.path)
            .planRuns(backends, listOf(qwen, gemma))
        val second = runs[1].form
        assertThat(second.modelPath).isEqualTo(gemma.path)
        assertThat(second.backendId).isEqualTo(BackendId.LITERT_LM)
        // GPU is kept, but LiteRT-LM offers no choice of GPU API, so none is named.
        assertThat(second.delegate).isEqualTo(Delegate.GPU)
        assertThat(second.gpuApi).isNull()
        assertThat(runs[1].label).isEqualTo("gemma · LiteRT-LM · GPU")
    }

    @Test
    fun `a gpu run names the preferred api when none was chosen, and a cpu run names none`() {
        assertThat(form.copy(gpuApi = null).withGpuApiFor(llama).gpuApi).isEqualTo("OpenCL")
        assertThat(form.copy(gpuApi = "Metal").withGpuApiFor(llama).gpuApi).isEqualTo("OpenCL")
        assertThat(form.copy(delegate = Delegate.CPU).withGpuApiFor(llama).gpuApi).isNull()
    }
}
