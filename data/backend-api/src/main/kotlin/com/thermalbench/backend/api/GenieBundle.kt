package com.thermalbench.backend.api

import com.thermalbench.domain.model.Delegate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * A Qualcomm Genie model bundle: a folder holding `genie_config.json`, the
 * tokenizer and the compiled model files. The config is what a spec points at.
 *
 * Which compute unit a bundle runs on is fixed when it is built, not chosen at
 * load time: QnnHtp bundles hold NPU context binaries compiled for one
 * Hexagon version, QnnGenAiTransformer bundles are CPU weights. So the
 * delegate is read from the bundle rather than offered as a free choice.
 */
object GenieBundle {

    const val CONFIG_FILE_NAME = "genie_config.json"

    private val json = Json { prettyPrint = false }

    /** The engine backend a config names: "QnnHtp", "QnnGenAiTransformer" or "QnnGpu"; null if unreadable. */
    fun backendType(configText: String): String? = runCatching {
        json.parseToJsonElement(configText).jsonObject
            .obj("dialog")?.obj("engine")?.obj("backend")
            ?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrNull()

    /** The compute unit a bundle was built for; null for a backend this tool does not know. */
    fun delegateOf(backendType: String?): Delegate? = when (backendType) {
        "QnnHtp" -> Delegate.NPU
        "QnnGenAiTransformer" -> Delegate.CPU
        "QnnGpu" -> Delegate.GPU
        else -> null
    }

    /**
     * The config as one run uses it.
     *
     * - Paths are made absolute against [bundleDir]. Genie resolves relative
     *   paths against the process's working directory, which on Android is
     *   not the bundle.
     * - Sampling is greedy with [seed], as on every other backend: sampling
     *   noise would read as throughput noise.
     * - Generation is capped at [maxTokens].
     */
    fun prepare(configText: String, bundleDir: File, seed: Long, maxTokens: Int): String {
        val root = json.parseToJsonElement(configText).jsonObject
        val absolute = resolvePaths(root, bundleDir) as JsonObject
        val dialog = absolute.obj("dialog") ?: error("$CONFIG_FILE_NAME has no \"dialog\" section")
        val sampler = dialog.obj("sampler")
        val greedySampler = sampler?.let {
            JsonObject(
                it + buildMap {
                    put("seed", JsonPrimitive(seed))
                    put("top-k", JsonPrimitive(1))
                    if ("greedy" in it) put("greedy", JsonPrimitive(true))
                },
            )
        }
        val preparedDialog = JsonObject(
            dialog + buildMap {
                put("max-num-tokens", JsonPrimitive(maxTokens))
                if (greedySampler != null) put("sampler", greedySampler)
            },
        )
        return JsonObject(absolute + ("dialog" to preparedDialog)).toString()
    }

    /** Every string that names a file inside the bundle becomes that file's absolute path. */
    private fun resolvePaths(element: JsonElement, bundleDir: File): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> resolvePaths(value, bundleDir) })
        is JsonArray -> JsonArray(element.map { resolvePaths(it, bundleDir) })
        is JsonPrimitive -> {
            val text = element.contentOrNull
            val candidate = text?.takeIf { element.isString && it.isNotBlank() }?.let { File(bundleDir, it) }
            if (candidate != null && !File(text).isAbsolute && candidate.isFile) {
                JsonPrimitive(candidate.absolutePath)
            } else {
                element
            }
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
