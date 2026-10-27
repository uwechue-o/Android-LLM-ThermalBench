package com.thermalbench.backend.api

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GenieBundleArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val config = """{"dialog": {"engine": {"backend": {"type": "QnnGenAiTransformer"}}}}"""

    private fun zip(vararg entries: Pair<String, String?>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                content?.let { out.write(it.toByteArray()) }
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun unpack(bytes: ByteArray, name: String = "tinyllama cpu.zip", maxBytes: Long = Long.MAX_VALUE) =
        GenieBundleArchive(temp.root).unpack(ByteArrayInputStream(bytes), name, maxBytes)

    @Test
    fun `a bundle zipped at its top level unpacks into a folder named after the zip`() {
        val outcome = unpack(zip("genie_config.json" to config, "tokenizer.json" to "{}", "model.bin" to "weights"))

        val dir = (outcome as GenieBundleArchive.Outcome.Unpacked).bundleDir
        assertThat(dir.name).isEqualTo("tinyllama-cpu")
        assertThat(File(dir, "genie_config.json").readText()).isEqualTo(config)
        assertThat(File(dir, "model.bin").readText()).isEqualTo("weights")
        assertThat(temp.root.list()).asList().containsExactly("tinyllama-cpu")
    }

    @Test
    fun `a bundle zipped as its folder unpacks the folder's contents`() {
        val outcome = unpack(zip("bundle/" to null, "bundle/genie_config.json" to config, "bundle/model.bin" to "w"))

        val dir = (outcome as GenieBundleArchive.Outcome.Unpacked).bundleDir
        assertThat(File(dir, "genie_config.json").isFile).isTrue()
        assertThat(File(dir, "bundle").exists()).isFalse()
    }

    @Test
    fun `an archive without a config is refused and leaves nothing behind`() {
        val outcome = unpack(zip("model.bin" to "w", "notes.txt" to "x"))

        assertThat((outcome as GenieBundleArchive.Outcome.Rejected).reason).contains("not a Genie bundle")
        assertThat(temp.root.list()).isEmpty()
    }

    @Test
    fun `an entry that climbs out of the bundle is refused`() {
        val outcome = unpack(zip("genie_config.json" to config, "../escaped.txt" to "x"))

        assertThat(outcome).isInstanceOf(GenieBundleArchive.Outcome.Rejected::class.java)
        assertThat(File(temp.root.parentFile, "escaped.txt").exists()).isFalse()
        assertThat(temp.root.list()).isEmpty()
    }

    @Test
    fun `an archive that unpacks past the free space is stopped`() {
        val outcome = unpack(zip("genie_config.json" to config, "model.bin" to "x".repeat(4_096)), maxBytes = 1_024)

        assertThat((outcome as GenieBundleArchive.Outcome.Rejected).reason).contains("more than")
        assertThat(temp.root.list()).isEmpty()
    }

    @Test
    fun `a cancelled unpack keeps nothing`() {
        val bytes = zip("genie_config.json" to config, "model.bin" to "w")
        // Coroutine cancellation surfaces as a CancellationException from the check.
        val cancelled = runCatching {
            GenieBundleArchive(temp.root).unpack(
                input = ByteArrayInputStream(bytes),
                archiveName = "b.zip",
                maxBytes = Long.MAX_VALUE,
                checkCancelled = { throw java.util.concurrent.CancellationException("cancelled") },
            )
        }
        assertThat(cancelled.exceptionOrNull()).isInstanceOf(java.util.concurrent.CancellationException::class.java)
        assertThat(temp.root.list()).isEmpty()
    }

    @Test
    fun `a newer import of the same bundle replaces the older one`() {
        unpack(zip("genie_config.json" to config, "model.bin" to "old"))
        val dir = (unpack(zip("genie_config.json" to config, "model.bin" to "new")) as GenieBundleArchive.Outcome.Unpacked)
            .bundleDir
        assertThat(File(dir, "model.bin").readText()).isEqualTo("new")
    }

    @Test
    fun `folder names are made safe for a path`() {
        assertThat(GenieBundleArchive.bundleName("Llama 3.2 1B (8 Gen 3).zip")).isEqualTo("Llama-3.2-1B-8-Gen-3")
        assertThat(GenieBundleArchive.bundleName("...zip")).isNull()
    }
}
