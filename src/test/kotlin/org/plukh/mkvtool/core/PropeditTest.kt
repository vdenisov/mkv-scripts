package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * `buildPropeditCommand` is pure — the file name is reconstructed as `<baseName>.mkv` and the user's
 * args are forwarded verbatim, in order, after it. This is the unit-testable slice of `propedit`; the
 * actual mkvpropedit subprocess is exercised by the Groovy harness.
 */
class PropeditTest : FunSpec({

    data class Case(
        val label: String,
        val file: File,
        val args: List<String>,
        val expected: List<String>,
    )

    context("buildPropeditCommand") {
        withData(
            nameFn = { it.label },
            Case(
                "forwards args verbatim after the file name",
                File("Show.mkv"),
                listOf("--edit", "info", "--set", "title=SmokeTest"),
                listOf("mkvpropedit", "Show.mkv", "--edit", "info", "--set", "title=SmokeTest"),
            ),
            Case(
                "no args yields just the executable and file name",
                File("Show.mkv"),
                emptyList(),
                listOf("mkvpropedit", "Show.mkv"),
            ),
            Case(
                "reconstructs the name with a lower-case .mkv (v1 quirk on an upper-case extension)",
                File("Show.MKV"),
                listOf("--add-track-statistics-tags"),
                listOf("mkvpropedit", "Show.mkv", "--add-track-statistics-tags"),
            ),
            Case(
                "a multi-dot base name keeps everything before the last dot",
                File("Show.S01E01.mkv"),
                emptyList(),
                listOf("mkvpropedit", "Show.S01E01.mkv"),
            ),
        ) { buildPropeditCommand("mkvpropedit", it.file, it.args) shouldBe it.expected }
    }
})
