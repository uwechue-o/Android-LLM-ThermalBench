package com.thermalbench.backend.api

import com.google.common.truth.Truth.assertThat
import com.thermalbench.domain.model.Delegate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GenieBundleTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val htpConfig = """
        {"dialog": {"version": 1, "type": "basic", "max-num-tokens": 100,
          "sampler": {"version": 1, "seed": 42, "temp": 0.8, "top-k": 40, "top-p": 0.9},
          "tokenizer": {"version": 1, "path": "tokenizer.json"},
          "engine": {"version": 1,
            "backend": {"version": 1, "type": "QnnHtp", "extensions": "htp_backend_ext_config.json"},
            "model": {"version": 1, "type": "binary",
              "binary": {"version": 1, "ctx-bins": ["part_1_of_2.bin", "part_2_of_2.bin", "missing.bin"]}}}}}
    """.trimIndent()

    private val cpuConfig = """
        {"dialog": {"sampler": {"version": 1, "seed": 100, "temp": 1.2, "top-k": 20, "greedy": false},
          "engine": {"backend": {"type": "QnnGenAiTransformer"}}}}
    """.trimIndent()

    @Test
    fun `the backend a bundle was compiled for fixes its compute unit`() {
        assertThat(GenieBundle.delegateOf(GenieBundle.backendType(htpConfig))).isEqualTo(Delegate.NPU)
        assertThat(GenieBundle.delegateOf(GenieBundle.backendType(cpuConfig))).isEqualTo(Delegate.CPU)
        assertThat(GenieBundle.delegateOf("QnnGpu")).isEqualTo(Delegate.GPU)
        assertThat(GenieBundle.backendType("not json")).isNull()
        assertThat(GenieBundle.delegateOf("SomethingElse")).isNull()
    }

    @Test
    fun `files in the bundle are given absolute paths, and nothing else is touched`() {
        val dir = temp.newFolder("bundle")
        listOf("tokenizer.json", "htp_backend_ext_config.json", "part_1_of_2.bin", "part_2_of_2.bin")
            .forEach { File(dir, it).writeText("x") }

        val prepared = Json.parseToJsonElement(GenieBundle.prepare(htpConfig, dir, seed = 7, maxTokens = 128)).jsonObject
        val dialog = prepared.getValue("dialog").jsonObject
        val engine = dialog.getValue("engine").jsonObject

        assertThat(dialog.getValue("tokenizer").jsonObject.getValue("path").jsonPrimitive.content)
            .isEqualTo(File(dir, "tokenizer.json").absolutePath)
        assertThat(engine.getValue("backend").jsonObject.getValue("extensions").jsonPrimitive.content)
            .isEqualTo(File(dir, "htp_backend_ext_config.json").absolutePath)
        val bins = engine.getValue("model").jsonObject.getValue("binary").jsonObject.getValue("ctx-bins").jsonArray
            .map { it.jsonPrimitive.content }
        // A name that is not in the bundle is left for Genie to report.
        assertThat(bins).containsExactly(
            File(dir, "part_1_of_2.bin").absolutePath,
            File(dir, "part_2_of_2.bin").absolutePath,
            "missing.bin",
        ).inOrder()
        assertThat(engine.getValue("backend").jsonObject.getValue("type").jsonPrimitive.content).isEqualTo("QnnHtp")
    }

    @Test
    fun `sampling is made greedy with the run's seed, and the token cap is the spec's`() {
        val dir = temp.newFolder("cpu")
        val dialog = Json.parseToJsonElement(GenieBundle.prepare(cpuConfig, dir, seed = 7, maxTokens = 32))
            .jsonObject.getValue("dialog").jsonObject
        val sampler = dialog.getValue("sampler").jsonObject

        assertThat(sampler.getValue("top-k").jsonPrimitive.int).isEqualTo(1)
        assertThat(sampler.getValue("seed").jsonPrimitive.long).isEqualTo(7L)
        assertThat(sampler.getValue("greedy").jsonPrimitive.content).isEqualTo("true")
        assertThat(dialog.getValue("max-num-tokens").jsonPrimitive.int).isEqualTo(32)
    }

    @Test
    fun `greedy is only set where the sampler already has that key`() {
        val sampler = Json.parseToJsonElement(GenieBundle.prepare(htpConfig, temp.newFolder(), 1, 8))
            .jsonObject.getValue("dialog").jsonObject.getValue("sampler").jsonObject
        assertThat(sampler.keys).doesNotContain("greedy")
    }
}
