package com.thermalbench.backend.api

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.thermalbench.domain.model.Delegate
import com.thermalbench.domain.model.ModelFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A model file found on the device, with its format inferred from the extension. */
data class DiscoveredModel(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val format: ModelFormat,
    /** The compute unit the model was built for, where that is fixed (a Genie bundle); null otherwise. */
    val builtFor: Delegate? = null,
) {
    /** Rounded for display. Model files are large enough that a decimal place is enough. */
    val displaySize: String
        get() = when {
            sizeBytes >= GIGABYTE -> String.format(Locale.US, "%.1f GB", sizeBytes / GIGABYTE.toDouble())
            sizeBytes >= MEGABYTE -> String.format(Locale.US, "%.0f MB", sizeBytes / MEGABYTE.toDouble())
            else -> "$sizeBytes bytes"
        }

    private companion object {
        const val MEGABYTE = 1024L * 1024L
        const val GIGABYTE = MEGABYTE * 1024L
    }
}

/**
 * Finds model files the app can actually open.
 *
 * The app targets a scoped-storage SDK, so an arbitrary path under `/sdcard`
 * cannot be opened as a plain file no matter what permission is declared. The
 * app-specific external directory can, needs no permission at all, and is
 * reachable from `adb push`, so that is where models belong.
 *
 * Listing what is already there beats a document picker for this workload: the
 * Storage Access Framework hands back a `content://` URI, and every native
 * runtime here needs a real filesystem path, which would mean copying a
 * multi-gigabyte file into app storage before a run could start.
 */
@Singleton
class ModelLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Where models are read from, and whether adb can write there.
     *
     * External storage is the normal answer and is reachable from adb. The
     * fallback to internal storage is not, so it is reported rather than
     * hidden: a push into a directory the app never scans would look exactly
     * like a scan that is broken.
     */
    data class ModelsLocation(val path: String, val adbAccessible: Boolean)

    /** Created on first access, so the push target exists before anyone pushes to it. */
    val modelsDirectory: File
        get() = resolve().first

    fun location(): ModelsLocation = resolve().let { (dir, external) ->
        ModelsLocation(dir.absolutePath, adbAccessible = external)
    }

    /**
     * The command that puts a model where this app will actually look for it.
     * Derived from the resolved directory rather than assembled separately, so
     * the advertised path and the scanned path cannot disagree.
     */
    fun pushCommand(fileName: String = "<model-file>"): String =
        "adb push $fileName ${location().path}/"

    private fun resolve(): Pair<File, Boolean> {
        val external = context.getExternalFilesDir(DIRECTORY)
        val directory = (external ?: context.filesDir.resolve(DIRECTORY)).also { it.mkdirs() }
        return directory to (external != null)
    }

    // ---- importing a file chosen from the document picker -------------------

    sealed interface ImportOutcome {
        /** The file is now in the models directory and is ready to run. */
        data class Imported(val model: DiscoveredModel) : ImportOutcome

        /** Nothing was copied, and the reason is fit to put in front of a user. */
        data class Rejected(val reason: String) : ImportOutcome

        /** The file was already here, so nothing was copied. */
        data class AlreadyPresent(val model: DiscoveredModel) : ImportOutcome
    }

    /** Name and size as the document provider reports them. */
    private data class DocumentInfo(val name: String, val sizeBytes: Long)

    /**
     * Copies a document-picker selection into the models directory.
     *
     * The picker hands back a `content://` URI, and every native runtime here
     * needs a real filesystem path, so there is no way to avoid the copy. These
     * files run to several gigabytes, so the copy reports progress, honours
     * cancellation, and refuses up front rather than filling the disk and
     * failing near the end.
     */
    suspend fun importFrom(
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val info = documentInfo(uri)
            ?: return@withContext ImportOutcome.Rejected("Could not read that file's name from the picker.")

        if (info.name.endsWith(".zip", ignoreCase = true)) return@withContext importGenieZip(uri, info, onProgress)

        if (formatOf(info.name) == null) {
            return@withContext ImportOutcome.Rejected(
                "${info.name} has no recognised model extension. Expected one of " +
                    ".litertlm, .gguf, .task or .pte, or a .zip of a Genie model folder.",
            )
        }

        val destination = File(modelsDirectory, info.name)
        if (destination.isFile && info.sizeBytes > 0 && destination.length() == info.sizeBytes) {
            // Re-copying gigabytes to reach a file already sitting here would be
            // a long wait for no change.
            return@withContext ImportOutcome.AlreadyPresent(describe(destination))
        }

        val free = modelsDirectory.usableSpace
        if (info.sizeBytes > 0 && free < info.sizeBytes + FREE_SPACE_MARGIN_BYTES) {
            return@withContext ImportOutcome.Rejected(
                "Not enough free space. ${info.name} needs " +
                    "${info.sizeBytes / MEGABYTE} MB and only ${free / MEGABYTE} MB is available.",
            )
        }

        // Copy beside the target and rename, so a cancelled or failed import
        // never leaves a truncated file that looks like a usable model.
        val partial = File(modelsDirectory, "${info.name}$PARTIAL_SUFFIX")
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportOutcome.Rejected("The picker would not open that file.")

            var copied = 0L
            stream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, info.sizeBytes)
                    }
                    output.fd.sync()
                }
            }

            if (destination.exists()) destination.delete()
            check(partial.renameTo(destination)) { "could not finalize ${destination.name}" }
            ImportOutcome.Imported(describe(destination))
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    /**
     * A zipped Genie model folder: unpacked by the app into its own folder,
     * because a folder pushed by adb is one the app may not read.
     */
    private suspend fun importGenieZip(
        uri: Uri,
        info: DocumentInfo,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    ): ImportOutcome {
        val free = modelsDirectory.usableSpace - FREE_SPACE_MARGIN_BYTES
        if (info.sizeBytes > 0 && free < info.sizeBytes) {
            return ImportOutcome.Rejected(
                "Not enough free space. ${info.name} needs at least " +
                    "${info.sizeBytes / MEGABYTE} MB and only ${modelsDirectory.usableSpace / MEGABYTE} MB is available.",
            )
        }
        val stream = context.contentResolver.openInputStream(uri)
            ?: return ImportOutcome.Rejected("The picker would not open that file.")
        val context = currentCoroutineContext()
        val outcome = stream.use { input ->
            GenieBundleArchive(modelsDirectory).unpack(
                input = input,
                archiveName = info.name,
                maxBytes = free,
                onProgress = { read -> onProgress(read, info.sizeBytes) },
                checkCancelled = { context.ensureActive() },
            )
        }
        return when (outcome) {
            is GenieBundleArchive.Outcome.Rejected -> ImportOutcome.Rejected(outcome.reason)
            is GenieBundleArchive.Outcome.Unpacked ->
                list().firstOrNull { File(it.path).parentFile == outcome.bundleDir }
                    ?.let { ImportOutcome.Imported(it) }
                    ?: ImportOutcome.Rejected("${info.name} unpacked, but its config could not be read.")
        }
    }

    // ---- downloading a catalog entry ----------------------------------------

    /**
     * Fetches a [CatalogModel] into the models directory and verifies it.
     *
     * The digest is computed while the bytes arrive, so verification costs no
     * second pass over a multi-gigabyte file. A file whose size or digest does
     * not match the catalog is deleted rather than kept: a model that is not the
     * one the catalog names would be benchmarked under the wrong identity.
     *
     * A connection that drops leaves the partial file in place, and the next
     * attempt resumes from it, re-hashing what is already there. An explicit
     * cancel deletes it, so a download nobody intends to finish does not sit on
     * gigabytes of storage out of sight.
     */
    suspend fun download(
        entry: CatalogModel,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val url = entry.downloadUrl
        val expectedDigest = entry.sha256
        if (url == null || expectedDigest == null) {
            return@withContext ImportOutcome.Rejected(
                "${entry.displayName} has to be fetched from its page after accepting the licence.",
            )
        }

        val destination = File(modelsDirectory, entry.fileName)
        if (destination.isFile && destination.length() == entry.sizeBytes) {
            return@withContext ImportOutcome.AlreadyPresent(describe(destination))
        }

        val partial = File(modelsDirectory, "${entry.fileName}$PARTIAL_SUFFIX")
        if (partial.length() > entry.sizeBytes) partial.delete()

        val remaining = entry.sizeBytes - partial.length()
        val free = modelsDirectory.usableSpace
        if (free < remaining + FREE_SPACE_MARGIN_BYTES) {
            return@withContext ImportOutcome.Rejected(
                "Not enough free space. ${entry.fileName} needs ${remaining / MEGABYTE} MB more " +
                    "and only ${free / MEGABYTE} MB is available.",
            )
        }

        try {
            val digest = fetchVerifying(url, partial, entry.sizeBytes, onProgress)
            if (partial.length() != entry.sizeBytes) {
                partial.delete()
                return@withContext ImportOutcome.Rejected(
                    "The download ended at ${partial.length()} bytes; ${entry.sizeBytes} were expected.",
                )
            }
            if (digest != expectedDigest) {
                partial.delete()
                return@withContext ImportOutcome.Rejected(
                    "${entry.fileName} did not match its published SHA-256 and was discarded. " +
                        "Expected $expectedDigest, received $digest.",
                )
            }
            if (destination.exists()) destination.delete()
            check(partial.renameTo(destination)) { "could not finalize ${destination.name}" }
            ImportOutcome.Imported(describe(destination))
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        }
    }

    /**
     * Appends the rest of [url] to [partial] and returns the SHA-256 of the whole
     * file. Resumes with a Range request when part of it is already on disk; a
     * server that answers with the full body instead is restarted from zero.
     */
    private suspend fun fetchVerifying(
        url: String,
        partial: File,
        totalBytes: Long,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var existing = partial.length()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = connection.responseCode
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                else -> throw IOException("Download failed: HTTP $code from ${connection.url.host}")
            }
            if (append) {
                // The digest must cover the whole file, including what an
                // earlier attempt already wrote.
                partial.inputStream().use { input -> pump(input, digest) { _ -> } }
            } else {
                existing = 0L
            }

            var copied = existing
            onProgress(copied, totalBytes)
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    pump(input, digest) { chunk ->
                        output.write(chunk.bytes, 0, chunk.length)
                        copied += chunk.length
                        onProgress(copied, totalBytes)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** One buffer's worth of a stream; [bytes] is reused, so only the first [length] are valid. */
    private class Chunk(val bytes: ByteArray, var length: Int)

    /** Reads [input] to the end, feeding every byte to [digest] and each chunk to [onChunk]. */
    private suspend fun pump(input: InputStream, digest: MessageDigest, onChunk: (Chunk) -> Unit) {
        val chunk = Chunk(ByteArray(COPY_BUFFER_BYTES), 0)
        while (true) {
            currentCoroutineContext().ensureActive()
            chunk.length = input.read(chunk.bytes)
            if (chunk.length < 0) break
            digest.update(chunk.bytes, 0, chunk.length)
            onChunk(chunk)
        }
    }

    private fun describe(file: File) = DiscoveredModel(
        name = file.name,
        path = file.absolutePath,
        sizeBytes = file.length(),
        format = requireNotNull(formatOf(file.name)) { "unreachable: extension already checked" },
    )

    private fun documentInfo(uri: Uri): DocumentInfo? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
            name?.let { DocumentInfo(it, size) }
        }

    suspend fun list(): List<DiscoveredModel> = withContext(Dispatchers.IO) {
        val entries = modelsDirectory.listFiles().orEmpty()
        val files = entries
            .filter { it.isFile && it.length() > 0 }
            .mapNotNull { file ->
                val format = formatOf(file.name) ?: return@mapNotNull null
                DiscoveredModel(file.name, file.absolutePath, file.length(), format)
            }
        // Hidden folders are unpacks in progress, not models.
        (files + entries.filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull(::genieBundle))
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    /**
     * A folder holding a `genie_config.json` is a Genie bundle, listed by the
     * folder's name with the size of everything in it. Its config says which
     * compute unit it was compiled for.
     */
    private fun genieBundle(dir: File): DiscoveredModel? {
        val config = File(dir, GenieBundle.CONFIG_FILE_NAME).takeIf { it.isFile } ?: return null
        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val builtFor = GenieBundle.delegateOf(runCatching { GenieBundle.backendType(config.readText()) }.getOrNull())
        return DiscoveredModel(dir.name, config.absolutePath, size, ModelFormat.GENIE_BUNDLE, builtFor)
    }

    /** Files with an unrecognised extension, so an operator is told rather than left guessing. */
    suspend fun unrecognised(): List<String> = withContext(Dispatchers.IO) {
        modelsDirectory.listFiles()
            .orEmpty()
            // A partial file is an import in flight or one that was cancelled,
            // not something the operator got wrong.
            .filter { it.isFile && formatOf(it.name) == null && !it.name.endsWith(PARTIAL_SUFFIX) }
            .map { it.name }
            .sorted()
    }

    companion object {
        const val DIRECTORY = "models"

        /** Every extension the picker will accept, for the empty-state hint. */
        val ACCEPTED_EXTENSIONS = listOf(".litertlm", ".gguf", ".task", ".pte")

        private const val COPY_BUFFER_BYTES = 1 shl 16
        private const val MEGABYTE = 1024L * 1024L

        /** Refuse a copy that would leave the volume essentially full. */
        private const val FREE_SPACE_MARGIN_BYTES = 128L * 1024L * 1024L

        private const val PARTIAL_SUFFIX = ".part"

        private const val CONNECT_TIMEOUT_MILLIS = 15_000

        /** Long enough for a slow mobile link, short enough that a dead one is reported. */
        private const val READ_TIMEOUT_MILLIS = 60_000

        /**
         * Model formats are not interchangeable between runtimes, and the
         * extension is the only thing that identifies one without parsing the
         * file. An unknown extension returns null rather than a guess.
         */
        fun formatOf(fileName: String): ModelFormat? =
            when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
                "litertlm" -> ModelFormat.LITERT_LM
                "gguf" -> ModelFormat.GGUF
                "task", "bin" -> ModelFormat.MEDIAPIPE_TASK
                "pte" -> ModelFormat.EXECUTORCH_PTE
                else -> null
            }

        /** Human-readable name for a format, for use in the UI. */
        fun extensionFor(format: ModelFormat): String = when (format) {
            ModelFormat.LITERT_LM -> ".litertlm"
            ModelFormat.GGUF -> ".gguf"
            ModelFormat.MEDIAPIPE_TASK -> ".task"
            ModelFormat.EXECUTORCH_PTE -> ".pte"
            // A folder, not a file: the bundle's config names the model.
            ModelFormat.GENIE_BUNDLE -> "/${GenieBundle.CONFIG_FILE_NAME}"
        }
    }
}
