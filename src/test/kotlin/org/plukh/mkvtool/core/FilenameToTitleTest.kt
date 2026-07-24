package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The title derivation and the command line built from it are pure — the whole unit-testable slice of
 * `filename-to-title`; the actual mkvpropedit subprocess is exercised by the Groovy harness (cases 85
 * and 86), as it is for `propedit`.
 */
class FilenameToTitleTest : FunSpec({

    context("titleFromFileName") {
        data class Case(val label: String, val file: File, val expected: String)

        withData(
            nameFn = { it.label },
            Case("spaces survive untouched", File("My Episode.mkv"), "My Episode"),
            Case("a multi-dot name keeps everything before the last dot", File("Show.S01E01.mkv"), "Show.S01E01"),
            Case("an upper-case extension is still stripped", File("Show.MKV"), "Show"),
            Case("a dot-less name is its own title", File("Show"), "Show"),
            Case("no cleanup: dots and brackets stay as they are", File("Show.S01E01[Grp].mkv"), "Show.S01E01[Grp]"),
        ) { titleFromFileName(it.file) shouldBe it.expected }
    }

    context("buildTitleCommand") {
        data class Case(val label: String, val file: File, val expected: List<String>)

        withData(
            nameFn = { it.label },
            Case(
                "the v1 ten-element argv, title and track name from the same base name",
                File("My Episode.mkv"),
                listOf(
                    "mkvpropedit", "My Episode.mkv",
                    "--edit", "info", "--set", "title=My Episode",
                    "--edit", "track:v1", "--set", "name=My Episode",
                ),
            ),
            Case(
                // The regression the v1 comment (and harness case 85) guards: list-exec needs no manual
                // quoting, so an embedded quote reaches the title verbatim rather than as a literal.
                "an embedded quote is carried verbatim, with nothing escaped or added",
                File("""Say "Hi".mkv"""),
                listOf(
                    "mkvpropedit", """Say "Hi".mkv""",
                    "--edit", "info", "--set", """title=Say "Hi"""",
                    "--edit", "track:v1", "--set", """name=Say "Hi"""",
                ),
            ),
            Case(
                // The shared v1 quirk at its second call site: the name is reconstructed with a
                // lower-case .mkv while the title keeps the base as it was.
                "an upper-case extension is reconstructed lower-case (v1 quirk)",
                File("Show.MKV"),
                listOf(
                    "mkvpropedit", "Show.mkv",
                    "--edit", "info", "--set", "title=Show",
                    "--edit", "track:v1", "--set", "name=Show",
                ),
            ),
            Case(
                "a multi-dot name titles with everything before the last dot",
                File("Show.S01E01.mkv"),
                listOf(
                    "mkvpropedit", "Show.S01E01.mkv",
                    "--edit", "info", "--set", "title=Show.S01E01",
                    "--edit", "track:v1", "--set", "name=Show.S01E01",
                ),
            ),
        ) { buildTitleCommand("mkvpropedit", it.file) shouldBe it.expected }
    }
})
