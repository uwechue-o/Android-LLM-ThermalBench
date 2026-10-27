package com.thermalbench.ui

import com.google.common.truth.Truth.assertThat
import com.thermalbench.backend.api.ModelCatalog
import com.thermalbench.backend.api.ModelFiles
import com.thermalbench.schema.JsonResultCodec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class SpecFormTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val codec = JsonResultCodec()
    private val entry = ModelCatalog.entries.first { it.downloadable }

    /** A sparse stand-in: the catalog match looks only at name and size. */
    private fun fileOfSize(name: String, size: Long): File =
        temp.newFile(name).also { RandomAccessFile(it, "rw").use { raf -> raf.setLength(size) } }

    @Test
    fun `a catalog file pins its published digest and metadata`() {
        val file = fileOfSize(entry.fileName, entry.sizeBytes)

        val model = SpecForm(modelPath = file.absolutePath).toSpec(codec).model

        assertThat(model.sha256.hex).isEqualTo(entry.sha256)
        assertThat(model.quantization).isEqualTo(entry.quantization)
        assertThat(model.parameterCount).isEqualTo(entry.parameterCount)
    }

    @Test
    fun `a same-named file of another size stays unpinned`() {
        val file = fileOfSize(entry.fileName, entry.sizeBytes - 1)

        val model = SpecForm(modelPath = file.absolutePath).toSpec(codec).model

        assertThat(model.sha256.hex).isEqualTo(ModelFiles.UNPINNED)
        assertThat(model.quantization).isNull()
    }

    @Test
    fun `pinning changes the spec identity, because the identity names the bytes`() {
        val pinned = SpecForm(modelPath = fileOfSize(entry.fileName, entry.sizeBytes).absolutePath)
        val other = temp.newFolder("other")
        val unpinned = SpecForm(
            modelPath = File(other, entry.fileName).also {
                RandomAccessFile(it, "rw").use { raf -> raf.setLength(entry.sizeBytes - 1) }
            }.absolutePath,
        )

        assertThat(pinned.toSpec(codec).specId).isNotEqualTo(unpinned.toSpec(codec).specId)
    }
}
