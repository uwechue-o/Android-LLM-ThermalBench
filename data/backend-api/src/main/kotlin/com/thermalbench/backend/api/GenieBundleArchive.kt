package com.thermalbench.backend.api

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks a zipped Genie bundle into the models directory.
 *
 * A Genie model is a folder, and neither the document picker (one file at a
 * time) nor adb (whose folders the app cannot read) can deliver one. A zip
 * can: the app unpacks it itself, so it owns every file.
 *
 * Everything is unpacked into a hidden staging folder first and moved into
 * place only once it is complete and holds a `genie_config.json`, so a
 * failed, cancelled or wrong archive never leaves a half-made model behind.
 * Entries are streamed one at a time; a multi-gigabyte bundle is never held
 * in memory.
 */
class GenieBundleArchive(private val modelsDirectory: File) {

    sealed interface Outcome {
        /** The bundle's folder, now in the models directory. */
        data class Unpacked(val bundleDir: File) : Outcome

        /** Nothing was kept, and the reason is fit to put in front of a user. */
        data class Rejected(val reason: String) : Outcome
    }

    /**
     * Unpacks [input], a zip named [archiveName], into a folder named after it.
     *
     * [maxBytes] caps what may be written, so an archive that expands past the
     * free space is stopped rather than filling the disk. [onProgress] reports
     * compressed bytes read; [checkCancelled] is called between buffers and
     * aborts the unpacking by throwing.
     */
    fun unpack(
        input: InputStream,
        archiveName: String,
        maxBytes: Long,
        onProgress: (readBytes: Long) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): Outcome {
        val name = bundleName(archiveName)
            ?: return Outcome.Rejected("$archiveName does not give a usable folder name.")
        val staging = File(modelsDirectory, "$STAGING_PREFIX$name")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "could not create ${staging.name}" }
        // However this ends, by refusal, failure or cancellation, the staging
        // folder goes; only a complete bundle is moved out of it first.
        try {
            val counted = CountingInputStream(input, onProgress)
            ZipInputStream(counted).use { zip ->
                extract(zip, staging, maxBytes, checkCancelled)?.let { return Outcome.Rejected(it) }
            }
            // The config sits at the top of the archive or inside one folder.
            val root = configRoot(staging) ?: return Outcome.Rejected(
                "$archiveName holds no ${GenieBundle.CONFIG_FILE_NAME}; it is not a Genie bundle.",
            )
            val target = File(modelsDirectory, name)
            target.deleteRecursively()
            check(root.renameTo(target)) { "could not move the bundle into ${target.name}" }
            return Outcome.Unpacked(target)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Writes every entry under [staging]; returns why the archive was refused, or null. */
    private fun extract(zip: ZipInputStream, staging: File, maxBytes: Long, checkCancelled: () -> Unit): String? {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        val stagingPath = staging.canonicalPath + File.separator
        while (true) {
            val entry = zip.nextEntry ?: return null
            // Zip slip: an entry may not name a place outside the bundle.
            val target = File(staging, entry.name)
            if (!target.canonicalPath.startsWith(stagingPath)) {
                return "The archive contains a path that leaves its folder (${entry.name}); it was not unpacked."
            }
            if (entry.isDirectory) {
                target.mkdirs()
                continue
            }
            target.parentFile?.mkdirs()
            written += copyEntry(zip, target, buffer, maxBytes - written, checkCancelled)
                ?: return "The archive unpacks to more than the ${maxBytes / MEGABYTE} MB free."
        }
    }

    /** Copies the current entry to [target]; its size, or null once it would pass [budget]. */
    private fun copyEntry(
        zip: ZipInputStream,
        target: File,
        buffer: ByteArray,
        budget: Long,
        checkCancelled: () -> Unit,
    ): Long? {
        var copied = 0L
        target.outputStream().use { output ->
            while (true) {
                checkCancelled()
                val read = zip.read(buffer)
                if (read < 0) return copied
                copied += read
                if (copied > budget) return null
                output.write(buffer, 0, read)
            }
        }
    }

    private fun configRoot(staging: File): File? {
        if (File(staging, GenieBundle.CONFIG_FILE_NAME).isFile) return staging
        val children = staging.listFiles().orEmpty()
        val folder = children.singleOrNull { it.isDirectory }?.takeIf { children.size == 1 } ?: return null
        return folder.takeIf { File(it, GenieBundle.CONFIG_FILE_NAME).isFile }
    }

    private class CountingInputStream(input: InputStream, private val onProgress: (Long) -> Unit) :
        FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) report(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) report(it.toLong()) }

        private fun report(bytes: Long) {
            count += bytes
            onProgress(count)
        }
    }

    companion object {
        /** Staging folders start with a dot, so a scan never lists one as a model. */
        const val STAGING_PREFIX = ".unpacking-"

        /** The folder a zip unpacks to: its name without `.zip`, made safe for a path. */
        fun bundleName(archiveName: String): String? = archiveName
            .removeSuffix(".zip").removeSuffix(".ZIP")
            .replace(UNSAFE, "-")
            .trim('-', '.')
            .takeIf { it.isNotEmpty() }

        private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
        private const val BUFFER_BYTES = 1 shl 16
        private const val MEGABYTE = 1024L * 1024L
    }
}
