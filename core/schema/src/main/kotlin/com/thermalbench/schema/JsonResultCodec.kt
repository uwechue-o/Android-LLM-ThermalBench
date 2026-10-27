package com.thermalbench.schema

import com.thermalbench.domain.model.BenchmarkResult
import com.thermalbench.domain.model.BenchmarkSpec
import com.thermalbench.domain.model.ResultId
import com.thermalbench.domain.model.Sha256
import com.thermalbench.domain.model.SpecId
import com.thermalbench.domain.port.ResultCodec
import com.thermalbench.schema.Mappers.toDocument
import com.thermalbench.schema.Mappers.toDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.encodeUtf8

/**
 * The [ResultCodec] implementation: kotlinx.serialization for structure,
 * [CanonicalJson] for bytes. The spec digest is computed over the spec with
 * its identity-irrelevant fields removed (`specId` itself, `label`, and
 * `model.path`, which differs from device to device).
 */
class JsonResultCodec : ResultCodec {

    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
        allowSpecialFloatingPointValues = true
        prettyPrint = false
    }

    override fun encodeResult(result: BenchmarkResult): String =
        CanonicalJson.encode(json.encodeToJsonElement(result.toDocument()))

    override fun decodeResult(document: String): BenchmarkResult =
        json.decodeFromString<ResultDocument>(document).toDomain()

    override fun encodeSpec(spec: BenchmarkSpec): String =
        CanonicalJson.encode(json.encodeToJsonElement(spec.toDocument()))

    override fun decodeSpec(document: String): BenchmarkSpec =
        json.decodeFromString<SpecDocument>(document).toDomain()

    override fun specDigest(spec: BenchmarkSpec): String =
        CanonicalJson.encode(identityElement(spec)).encodeUtf8().sha256().hex()

    override fun resultCsv(result: BenchmarkResult): String = ResultCsv.iterations(result)

    /** Returns [spec] with its [SpecId] replaced by the derived identity. */
    fun withDerivedId(spec: BenchmarkSpec): BenchmarkSpec =
        spec.copy(specId = SpecId.fromDigest(specDigest(spec)))

    /** True when the spec's id matches its own content. */
    fun verifyId(spec: BenchmarkSpec): Boolean = spec.specId == SpecId.fromDigest(specDigest(spec))

    /** A result id: digest of the result document without the id field. */
    fun deriveResultId(result: BenchmarkResult): ResultId {
        val element = json.encodeToJsonElement(result.copy(resultId = ResultId(PLACEHOLDER)).toDocument()).jsonObject
        val withoutId = JsonObject(element.filterKeys { it != "resultId" })
        return ResultId(CanonicalJson.encode(withoutId).encodeUtf8().sha256().hex().take(RESULT_ID_LENGTH))
    }

    private fun identityElement(spec: BenchmarkSpec): JsonObject {
        val root = json.encodeToJsonElement(spec.toDocument()).jsonObject
        val model = root.getValue("model").jsonObject
        return buildJsonObject {
            for ((k, v) in root) {
                when (k) {
                    "specId", "label" -> Unit
                    "model" -> put(k, JsonObject(model.filterKeys { it != "path" }))
                    else -> put(k, v)
                }
            }
        }
    }

    companion object {
        const val SPEC_ID_LENGTH = SpecId.LENGTH
        const val RESULT_ID_LENGTH = 24
        private const val PLACEHOLDER = "pending"

        /** SHA-256 of a UTF-8 string, for prompt hashing. */
        fun sha256(text: String): Sha256 = Sha256(text.encodeUtf8().sha256().hex())
    }
}

@Suppress("unused")
private fun Json.decodeResultDocument(document: String): ResultDocument = decodeFromJsonElement(parseToJsonElement(document))
