package com.thermalbench.backend.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelCatalogTest {

    private val entries = ModelCatalog.entries

    @Test
    fun `every file name maps to the format the entry declares`() {
        entries.forEach { entry ->
            assertThat(ModelLibrary.formatOf(entry.fileName)).isEqualTo(entry.format)
        }
    }

    @Test
    fun `file names are unique, because they are also the names saved on disk`() {
        assertThat(entries.map { it.fileName }).containsNoDuplicates()
    }

    @Test
    fun `a published digest is 64 lower-case hex characters`() {
        entries.mapNotNull { it.sha256 }.forEach { digest ->
            assertThat(digest).matches("[0-9a-f]{64}")
        }
    }

    @Test
    fun `nothing downloads without a digest to verify it against`() {
        entries.filter { it.downloadUrl != null }.forEach { entry ->
            assertThat(entry.sha256).isNotNull()
            assertThat(entry.downloadable).isTrue()
        }
    }

    @Test
    fun `links are https and a download link names the file it saves`() {
        entries.forEach { entry ->
            assertThat(entry.pageUrl).startsWith("https://")
            entry.downloadUrl?.let { url ->
                assertThat(url).startsWith("https://")
                assertThat(url.substringAfterLast('/')).isEqualTo(entry.fileName)
            }
            assertThat(entry.sizeBytes).isGreaterThan(0L)
        }
    }

    @Test
    fun `a licence-gated entry is a link, never a download`() {
        val gated = entries.filter { it.sha256 == null }
        assertThat(gated).isNotEmpty()
        gated.forEach { assertThat(it.downloadable).isFalse() }
    }

    @Test
    fun `a file matches an entry only on both name and exact size`() {
        val entry = entries.first { it.downloadable }
        assertThat(ModelCatalog.match(entry.fileName, entry.sizeBytes)).isEqualTo(entry)
        assertThat(ModelCatalog.match(entry.fileName, entry.sizeBytes - 1)).isNull()
        assertThat(ModelCatalog.match("renamed-${entry.fileName}", entry.sizeBytes)).isNull()
    }
}
