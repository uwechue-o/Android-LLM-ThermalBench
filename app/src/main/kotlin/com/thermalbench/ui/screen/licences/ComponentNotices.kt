package com.thermalbench.ui.screen.licences

/** One component's notice within a vendor's combined notice file. */
data class ComponentNotice(val title: String, val text: String)

/**
 * A combined notice file: the vendor's preamble, licences several components
 * share (each kept once), then one notice per component.
 */
data class ComponentNotices(
    val preamble: String,
    val components: List<ComponentNotice>,
    val sharedLicences: List<ComponentNotice> = emptyList(),
)

/**
 * Splits a combined notice file at its `---- Component ----` headings, the
 * layout of Qualcomm's QAIRT NOTICE.txt. Nothing is dropped or reworded: the
 * preamble and every component's text are kept verbatim, only taken apart so
 * the screen can list components instead of showing one 129 KB block. A file
 * without such headings comes back as all preamble.
 */
fun parseComponentNotices(text: String): ComponentNotices {
    val headings = HEADING.findAll(text).toList()
    if (headings.isEmpty()) return ComponentNotices(text.trim(), emptyList())
    val components = headings.mapIndexed { index, heading ->
        val end = headings.getOrNull(index + 1)?.range?.first ?: text.length
        ComponentNotice(
            title = heading.groupValues[1].trim(),
            text = stripRules(text.substring(heading.range.last + 1, end)),
        )
    }
    return ComponentNotices(stripRules(text.substring(0, headings.first().range.first)), components)
}

/**
 * Keeps one copy of the Apache License 2.0 where several components each
 * carry its full text, and points them at it. In QAIRT's notice five
 * components repeat it (about 47 KB of the 129 KB). Everything around the
 * licence stays verbatim: a component's copyright lines, its other notices,
 * and anything after the licence. A component's copy is replaced only when it
 * is the same licence: its terms match the kept copy word for word once
 * whitespace and section 4's (a)-(d) item markers are set aside, the one way
 * the copies in that file differ.
 */
fun ComponentNotices.withSharedApacheLicence(): ComponentNotices {
    val found = components.mapNotNull { component -> apacheBlock(component.text)?.let { component to it } }
    if (found.size < 2) return this
    // The copy most components carry, exactly as laid out, is the one kept;
    // layout only stops mattering when deciding whether a copy is the same.
    val kept = found.groupBy { (component, range) -> component.text.substring(range).replace(WHITESPACE, " ") }
        .maxBy { it.value.size }.value.first()
        .let { (component, range) -> component.text.substring(range) }
    val keptTerms = comparable(kept)
    val replaced = components.map { component ->
        val range = apacheBlock(component.text) ?: return@map component
        if (comparable(component.text.substring(range)) != keptTerms) return@map component
        val text = component.text.replaceRange(range, APACHE_POINTER).trim()
        component.copy(text = text)
    }
    return copy(components = replaced, sharedLicences = sharedLicences + ComponentNotice(APACHE_TITLE, kept))
}

/**
 * Where the full Apache License 2.0 sits in [text]: from its title through
 * "END OF TERMS AND CONDITIONS", with the "How to apply" appendix when it
 * directly follows. Null when the text carries no full copy.
 */
private fun apacheBlock(text: String): IntRange? {
    // From the start of the title's line: the licence centres its title with
    // spaces, which would otherwise indent the pointer that replaces it.
    val title = APACHE_TITLE_LINES.find(text)?.range?.first ?: return null
    val start = text.lastIndexOf('\n', title - 1) + 1
    val termsEnd = text.indexOf(END_OF_TERMS, start).takeIf { it >= 0 }?.plus(END_OF_TERMS.length) ?: return null
    val appendix = APPENDIX.find(text, termsEnd)?.takeIf { text.substring(termsEnd, it.range.first).isBlank() }
    val end = appendix?.let { text.indexOf(APPENDIX_END, it.range.first) }
        ?.takeIf { it >= 0 }?.plus(APPENDIX_END.length)
        ?: termsEnd
    return start until end
}

/** The licence's terms only, compared without layout: whitespace and item markers. */
private fun comparable(block: String): String {
    val terms = block.substringBefore(END_OF_TERMS)
    return terms.replace(ITEM_MARKER, " ").replace(WHITESPACE, " ").trim()
}

const val APACHE_TITLE = "Apache License 2.0 (full text, shared by several components)"
private const val APACHE_POINTER = "[Apache License, Version 2.0. The full text is the \"$APACHE_TITLE\" entry above.]"
private const val END_OF_TERMS = "END OF TERMS AND CONDITIONS"
private const val APPENDIX_END = "limitations under the License."
private val APACHE_TITLE_LINES = Regex("""Apache License\s+Version 2\.0, January 2004""")
private val APPENDIX = Regex("""APPENDIX: How to apply the Apache License""")
private val ITEM_MARKER = Regex("""\([a-d]\)""")
private val WHITESPACE = Regex("""\s+""")

/** The `=====` separator lines between sections carry nothing to read. */
private fun stripRules(section: String): String =
    section.lines().filterNot { RULE.matches(it.trim()) }.joinToString("\n").trim()

private val HEADING = Regex("""(?m)^-{4} (.+?) -{4,}\s*$""")
private val RULE = Regex("""={10,}""")
