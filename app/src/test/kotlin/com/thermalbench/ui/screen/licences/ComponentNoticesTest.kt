package com.thermalbench.ui.screen.licences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComponentNoticesTest {

    private val notice = """
        This Notice.txt file contains certain notices.
        Copyright (c) Qualcomm Technologies, Inc.

        ==============================================================================
        ---- Caffe SSD License ----
        COPYRIGHT
        All rights reserved.

        ==============================================================================
        ---- boost_python DWA2002810 ------
        Boost Software License - Version 1.0
        ==============================================================================
        ---- pugixml 1.13 -----
        MIT License
    """.trimIndent()

    @Test
    fun `a combined notice splits into its preamble and one entry per component`() {
        val parsed = parseComponentNotices(notice)

        assertThat(parsed.preamble).isEqualTo(
            "This Notice.txt file contains certain notices.\nCopyright (c) Qualcomm Technologies, Inc.",
        )
        assertThat(parsed.components.map { it.title })
            .containsExactly("Caffe SSD License", "boost_python DWA2002810", "pugixml 1.13").inOrder()
        assertThat(parsed.components.first().text).isEqualTo("COPYRIGHT\nAll rights reserved.")
        assertThat(parsed.components.last().text).isEqualTo("MIT License")
    }

    @Test
    fun `no text is lost apart from the separator lines`() {
        val parsed = parseComponentNotices(notice)
        val kept = (listOf(parsed.preamble) + parsed.components.flatMap { listOf(it.title, it.text) })
            .joinToString("\n")
        notice.lines()
            .filterNot { it.startsWith("=====") || it.isBlank() || it.startsWith("----") }
            .forEach { line -> assertThat(kept).contains(line.trim()) }
    }

    @Test
    fun `a notice without component headings is kept whole`() {
        val parsed = parseComponentNotices("Just one licence.\nNo sections.")
        assertThat(parsed.preamble).isEqualTo("Just one licence.\nNo sections.")
        assertThat(parsed.components).isEmpty()
    }

    private fun apache(markers: Boolean) = """
                                     Apache License
                               Version 2.0, January 2004

        TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION
        4. Redistribution. You may reproduce and distribute copies:
            ${if (markers) "(a) " else ""}You must give any other recipients a copy of this License; and
            ${if (markers) "(b) " else ""}You must cause any modified files to carry notices.
        END OF TERMS AND CONDITIONS
    """.trimIndent()

    private val appendix = """
        APPENDIX: How to apply the Apache License to your work.
           See the License for the specific language governing permissions and
           limitations under the License.
    """.trimIndent()

    private val repeated = ComponentNotices(
        preamble = "Preamble",
        components = listOf(
            ComponentNotice("gradle", apache(markers = false) + "\n\nGradle's own extra notice."),
            ComponentNotice("Tensorflow", "Copyright 2018 The TensorFlow Authors.\n\n" + apache(true) + "\n\n" + appendix),
            ComponentNotice("TVM", apache(true) + "\n\n" + appendix + "\n\nTVM third-party notices follow."),
            ComponentNotice("pugixml", "MIT License"),
        ),
    )

    @Test
    fun `the apache licence is kept once and each copy points at it`() {
        val collapsed = repeated.withSharedApacheLicence()

        val shared = collapsed.sharedLicences.single()
        assertThat(shared.title).isEqualTo(APACHE_TITLE)
        // The kept copy is the one most components carry, with its appendix.
        assertThat(shared.text).contains("(a) You must give")
        assertThat(shared.text).endsWith("limitations under the License.")

        val byTitle = collapsed.components.associate { it.title to it.text }
        assertThat(byTitle.values.none { "TERMS AND CONDITIONS" in it }).isTrue()
        // Text around the licence is untouched.
        assertThat(byTitle["Tensorflow"]).startsWith("Copyright 2018 The TensorFlow Authors.")
        assertThat(byTitle["gradle"]).endsWith("Gradle's own extra notice.")
        assertThat(byTitle["TVM"]).endsWith("TVM third-party notices follow.")
        assertThat(byTitle["TVM"]).doesNotContain("APPENDIX")
        assertThat(byTitle["pugixml"]).isEqualTo("MIT License")
        // The pointer starts its own line, without the licence title's centring.
        assertThat(byTitle["Tensorflow"]).contains("Authors.\n\n[Apache License, Version 2.0.")
    }

    @Test
    fun `a different licence under the same title is not replaced`() {
        val altered = repeated.copy(
            components = repeated.components + ComponentNotice(
                "forked",
                apache(true).replace("a copy of this License", "a copy of some other licence"),
            ),
        ).withSharedApacheLicence()

        assertThat(altered.components.single { it.title == "forked" }.text).contains("some other licence")
    }

    @Test
    fun `a single copy is left where it is`() {
        val one = ComponentNotices("p", listOf(ComponentNotice("only", apache(true))))
        assertThat(one.withSharedApacheLicence()).isEqualTo(one)
    }
}
