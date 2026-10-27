package com.thermalbench.backend.genie

/**
 * JNI surface of the Genie adapter. Mirrors `genie_jni.cpp`.
 *
 * Two libraries can be missing and both are captured rather than thrown: the
 * adapter's own (a build without thermalbench.qairtDir), and Qualcomm's
 * libGenie.so with its runtime (a build that did not package them, or a
 * device they cannot load on).
 */
internal object GenieNative {

    /** One streamed piece of a response, with Genie's sentence code. */
    fun interface PieceListener {
        fun onPiece(text: String, sentenceCode: Int)
    }

    /** Null when Genie is ready; the reason it is not, otherwise. */
    val loadFailure: String? = try {
        System.loadLibrary(LIBRARY_NAME)
        open()
    } catch (e: Throwable) {
        "The Genie adapter's native library is not in this build: ${e.javaClass.simpleName}: ${e.message}. " +
            "Rebuild with -Pthermalbench.qairtDir=<QAIRT SDK>."
    }

    val available: Boolean get() = loadFailure == null

    private external fun open(): String?

    external fun apiVersion(): String?

    /** Returns a dialog handle; throws IllegalStateException with Genie's status on failure. */
    external fun create(configJson: String): Long

    external fun setMaxTokens(handle: Long, tokens: Int): Int

    /** Blocks until the response is complete, calling [listener] for every piece. Returns Genie's status. */
    external fun query(handle: Long, prompt: String, listener: PieceListener): Int

    /** Clears the dialog's context, so the next query starts from an empty KV cache. */
    external fun reset(handle: Long): Int

    external fun abort(handle: Long): Int

    external fun free(handle: Long)

    const val STATUS_SUCCESS = 0
    const val STATUS_ABORTED = 1
    const val STATUS_CONTEXT_EXCEEDED = 4

    /** Sentence codes that carry generated text. */
    const val SENTENCE_COMPLETE = 0
    const val SENTENCE_BEGIN = 1
    const val SENTENCE_CONTINUE = 2
    const val SENTENCE_END = 3

    private const val LIBRARY_NAME = "thermalbench_genie"
}
