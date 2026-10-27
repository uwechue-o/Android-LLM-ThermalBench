package com.thermalbench.backend.api

import com.thermalbench.domain.model.ModelRef
import com.thermalbench.domain.model.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Model file resolution and verification. The digest is streamed with Okio so a
 * multi-gigabyte GGUF never lands in memory.
 */
@Singleton
class ModelFiles @Inject constructor() {

    sealed interface Verification {
        data object Ok : Verification
        data class Missing(val path: String) : Verification
        data class DigestMismatch(val expected: String, val actual: String) : Verification
        data class SizeMismatch(val expected: Long, val actual: Long) : Verification
    }

    fun resolve(ref: ModelRef): File = File(ref.path)

    fun exists(ref: ModelRef): Boolean = resolve(ref).isFile

    suspend fun sha256(file: File): Sha256 = withContext(Dispatchers.IO) {
        HashingSink.sha256(blackholeSink()).use { hashing ->
            file.source().buffer().use { source -> source.readAll(hashing) }
            Sha256(hashing.hash.hex())
        }
    }

    /**
     * Confirms the file on disk is the one the spec names. A placeholder digest
     * (all zeros) means "not yet pinned" and only the file's existence is checked.
     */
    suspend fun verify(ref: ModelRef): Verification {
        val file = resolve(ref)
        if (!file.isFile) return Verification.Missing(ref.path)
        if (ref.sizeBytes > 0 && file.length() != ref.sizeBytes) {
            return Verification.SizeMismatch(ref.sizeBytes, file.length())
        }
        if (ref.sha256.hex == UNPINNED) return Verification.Ok
        val actual = sha256(file)
        return if (actual == ref.sha256) Verification.Ok else Verification.DigestMismatch(ref.sha256.hex, actual.hex)
    }

    /** Builds a [ModelRef] from a file on disk, computing its digest and size. */
    suspend fun describe(
        file: File,
        name: String = file.nameWithoutExtension,
        format: com.thermalbench.domain.model.ModelFormat,
        quantization: String? = null,
    ): ModelRef = ModelRef(
        name = name,
        format = format,
        sha256 = sha256(file),
        sizeBytes = file.length(),
        path = file.absolutePath,
        quantization = quantization,
    )

    companion object {
        const val UNPINNED: String = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
