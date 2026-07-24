package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.plukh.mkvtool.out.Advisory
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.OutputEvent
import org.plukh.mkvtool.out.ProgressHandle
import org.plukh.mkvtool.out.Renderer
import java.io.File

/**
 * The pure matcher (`unusedFonts`) as a data table plus the orchestrator (`findUnusedFonts`) in-process
 * over a temp dir, including the missing-`fonts/` fix — v1 crashed there; this reports an advisory and an
 * empty result instead.
 */
class FindUnusedFontsTest : FunSpec({

    data class Case(
        val label: String,
        val subtitleLines: List<String>,
        val fontBaseNames: List<String>,
        val expected: List<String>,
    )

    context("unusedFonts") {
        withData(
            nameFn = { it.label },
            Case(
                "a referenced font is not reported",
                listOf("style: arial,20"),
                listOf("Arial"),
                emptyList(),
            ),
            Case(
                "an unreferenced font is reported",
                listOf("style: arial,20"),
                listOf("Verdana"),
                listOf("Verdana"),
            ),
            Case(
                "matching is case-insensitive (font upper, lines already lower)",
                listOf("style: arial,20"),
                listOf("ARIAL"),
                emptyList(),
            ),
            Case(
                "substring counts as referenced (v1's loose match)",
                listOf("fontname: arialblack"),
                listOf("Arial"),
                emptyList(),
            ),
            Case(
                "mixed set reports only the truly-absent names",
                listOf("dialogue: arial line", "dialogue: times line"),
                listOf("Arial", "Verdana", "Times"),
                listOf("Verdana"),
            ),
            Case(
                "no subtitle lines means every font is unused",
                emptyList(),
                listOf("Arial", "Verdana"),
                listOf("Arial", "Verdana"),
            ),
        ) { unusedFonts(it.subtitleLines, it.fontBaseNames) shouldBe it.expected }
    }

    context("findUnusedFonts") {
        test("reports fonts absent from the .ass subtitles, sorted") {
            val dir = tempdir()
            File(dir, "ep.ass").writeText("Style: Default,Arial,20\nDialogue: uses Times too\n")
            File(dir, "fonts").mkdir()
            File(dir, "fonts/Arial.ttf").writeText("x")
            File(dir, "fonts/Times.otf").writeText("x")
            File(dir, "fonts/Verdana.ttf").writeText("x")
            File(dir, "fonts/Comic.ttf").writeText("x")

            val report = findUnusedFonts(dir, FontsSilentRenderer)

            report.unusedFonts shouldContainExactly listOf("Comic", "Verdana")
        }

        test("a missing fonts/ directory yields an advisory and an empty report, not a crash") {
            val dir = tempdir()
            File(dir, "ep.ass").writeText("Style: Default,Arial,20\n")
            val recorder = RecordingRenderer()

            val report = findUnusedFonts(dir, recorder)

            report.unusedFonts.shouldBeEmpty()
            recorder.events.filterIsInstance<Advisory>().map { it.text } shouldContainExactly
                listOf("*** No fonts/ directory in the current directory")
        }
    }
})

/** Discards everything: the model is the assertion surface. */
private object FontsSilentRenderer : Renderer {
    override fun render(event: OutputEvent) {}
    override fun render(result: CommandResult) {}
    override fun progress(label: String, total: Int, interactive: Boolean?): ProgressHandle =
        object : ProgressHandle {
            override fun tick() {}
            override fun finish() {}
        }
}

/** Captures diagnostics events so the missing-fonts advisory can be asserted. */
private class RecordingRenderer : Renderer {
    val events = mutableListOf<OutputEvent>()

    override fun render(event: OutputEvent) {
        events += event
    }

    override fun render(result: CommandResult) {}

    override fun progress(label: String, total: Int, interactive: Boolean?): ProgressHandle =
        object : ProgressHandle {
            override fun tick() {}
            override fun finish() {}
        }
}
