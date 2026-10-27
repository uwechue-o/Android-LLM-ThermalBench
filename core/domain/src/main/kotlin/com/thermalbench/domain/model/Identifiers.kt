package com.thermalbench.domain.model

/**
 * Identity of a [BenchmarkSpec]. Derived from the canonical hash of the spec so
 * two people who write the same spec obtain the same id, and the id appears in
 * every result that spec produced.
 */
@JvmInline
value class SpecId(val value: String) {
    init {
        require(value.isNotBlank()) { "SpecId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        /** Hex characters of the spec digest kept in the id. */
        const val LENGTH = 16

        fun fromDigest(digest: String): SpecId = SpecId(digest.take(LENGTH))
    }
}

/** Identity of a persisted [BenchmarkResult]. */
@JvmInline
value class ResultId(val value: String) {
    init {
        require(value.isNotBlank()) { "ResultId must not be blank" }
    }

    override fun toString(): String = value
}

/** Stable identifier of an inference backend adapter, e.g. `litert-lm`, `llamacpp`. */
@JvmInline
value class BackendId(val value: String) {
    init {
        require(value.matches(ID_PATTERN)) { "BackendId '$value' must match ${ID_PATTERN.pattern}" }
    }

    override fun toString(): String = value

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val LITERT_LM = BackendId("litert-lm")
        val LLAMA_CPP = BackendId("llamacpp")
        val MEDIAPIPE = BackendId("mediapipe-genai")
        val EXECUTORCH = BackendId("executorch")
        val QUALCOMM_GENIE = BackendId("qualcomm-genie")
    }
}

/** Lower-case hex SHA-256 digest. */
@JvmInline
value class Sha256(val hex: String) {
    init {
        require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Sha256 must be 64 lower-case hex characters"
        }
    }

    override fun toString(): String = hex
}

/** Semantic version of the tool that produced a document. */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) :
    Comparable<SemVer> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "SemVer components must be non-negative" }
    }

    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String =
        "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        fun parse(text: String): SemVer {
            val (core, pre) = text.split('-', limit = 2).let { it[0] to it.getOrNull(1) }
            val parts = core.split('.')
            require(parts.size == 3) { "Not a semantic version: $text" }
            return SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), pre)
        }
    }
}
