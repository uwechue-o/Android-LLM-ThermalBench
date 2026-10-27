package com.thermalbench.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Canonical JSON: equal documents produce byte-identical text on every platform.
 *
 * - object members sorted by key (UTF-16 code-unit order)
 * - no insignificant whitespace
 * - strings escaped minimally (`"`, `\`, control characters)
 * - floating-point numbers rendered as the *shortest* decimal that reparses to
 *   the identical double, found by exact [BigDecimal] arithmetic rather than by
 *   `Double.toString`, whose output has differed between OpenJDK and ART
 * - non-finite numbers become `null` (JSON cannot express them)
 *
 * Encoding is lossless: decoding a document and re-encoding it reproduces the
 * same bytes. Rounding to a fixed digit count does not have that property —
 * 17 significant digits are needed in the worst case — and a lossy encoder
 * would silently change measurements as they pass through the file format.
 *
 * The digest of this text is the identity of the document.
 */
object CanonicalJson {

    /** Enough to round-trip any IEEE 754 double. */
    private const val MAX_SIGNIFICANT_DIGITS = 17

    fun encode(element: JsonElement): String = buildString { write(element) }

    private fun StringBuilder.write(element: JsonElement) {
        when (element) {
            is JsonNull -> append("null")
            is JsonObject -> {
                append('{')
                var first = true
                for (key in element.keys.sorted()) {
                    if (!first) append(',')
                    first = false
                    writeString(key)
                    append(':')
                    write(element.getValue(key))
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                element.forEachIndexed { i, child ->
                    if (i > 0) append(',')
                    write(child)
                }
                append(']')
            }
            is JsonPrimitive -> writePrimitive(element)
        }
    }

    private fun StringBuilder.writePrimitive(p: JsonPrimitive) {
        when {
            p.isString -> writeString(p.content)
            p.content == "true" || p.content == "false" || p.content == "null" -> append(p.content)
            else -> append(formatNumber(p.content))
        }
    }

    /** Integers pass through; anything with a fraction or exponent is normalized. */
    fun formatNumber(content: String): String {
        val isIntegral = content.all { it.isDigit() || it == '-' } && content.isNotEmpty()
        if (isIntegral) return content
        val d = content.toDoubleOrNull() ?: return "null"
        return formatDouble(d)
    }

    fun formatDouble(d: Double): String {
        if (!d.isFinite()) return "null"
        // Preserve the sign of negative zero: it survives a JSON round trip and
        // Double.equals() distinguishes it from positive zero.
        if (d == 0.0) return if (1.0 / d < 0.0) "-0.0" else "0.0"

        // BigDecimal(double) is the exact binary value. Shorten it until one more
        // digit would change which double it parses back to.
        val exact = BigDecimal(d)
        for (precision in 1..MAX_SIGNIFICANT_DIGITS) {
            val candidate = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
            if (candidate.toDouble() == d) return plain(candidate)
        }
        return plain(exact.round(MathContext(MAX_SIGNIFICANT_DIGITS, RoundingMode.HALF_EVEN)))
    }

    /** Plain notation, never scientific, always with a fractional part. */
    private fun plain(value: BigDecimal): String {
        val text = value.stripTrailingZeros().toPlainString()
        return if (text.contains('.')) text else "$text.0"
    }

    private fun StringBuilder.writeString(s: String) {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\f")
                else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
