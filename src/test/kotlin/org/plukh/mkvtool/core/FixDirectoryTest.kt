package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.OutputEvent
import org.plukh.mkvtool.out.ProgressHandle
import org.plukh.mkvtool.out.Renderer
import java.io.File

/**
 * The orchestrator, tested in-process: call `fixDirectory` with a no-op renderer and assert on the
 * returned [FixRun] and on-disk side effects. Per the seam, unit tests read the model, not the emissions.
 */
class FixDirectoryTest : FunSpec({

    test("reformats a legacy file to a numbered .srt.fixed sibling") {
        val dir = tempdir()
        File(dir, "ep.srt").writeText(
            "00:01:41.42,00:01:42.30\nLine one[br]Line two\n\n00:01:43.00,00:01:44.00\nAnother line\n\n",
        )

        val result = fixDirectory(dir, SilentRenderer)

        result.fixed shouldBe 1
        result.failed shouldBe 0
        result.files.single().outcome shouldBe FixOutcome.Fixed
        val fixed = File(dir, "ep.srt.fixed")
        fixed.exists() shouldBe true
        fixed.readLines() shouldBe listOf(
            "1",
            "00:01:41,420 --> 00:01:42,300",
            "Line one",
            "Line two",
            "",
            "2",
            "00:01:43,000 --> 00:01:44,000",
            "Another line",
            "",
        )
    }

    test("a malformed file is failed and skipped while the good one is still fixed") {
        val dir = tempdir()
        File(dir, "bad.srt").writeText("00:01:41.42,00:01:42.30\nText\nnot-blank where a blank line belongs\n")
        File(dir, "good.srt").writeText("00:01:43.00,00:01:44.00\nFine\n\n")

        val result = fixDirectory(dir, SilentRenderer)

        result.fixed shouldBe 1
        result.failed shouldBe 1
        val bad = result.files.single { it.fileName == "bad.srt" }.outcome
        bad.shouldBeInstanceOf<FixOutcome.Failed>()
        bad.message shouldBe "expected a blank line, got: 'not-blank where a blank line belongs'"
        result.files.single { it.fileName == "good.srt" }.outcome shouldBe FixOutcome.Fixed
        File(dir, "good.srt.fixed").exists() shouldBe true
        File(dir, "bad.srt.fixed").exists() shouldBe false
    }

    test("an empty directory yields an empty run with zero counts") {
        val result = fixDirectory(tempdir(), SilentRenderer)

        result.files.shouldBeEmpty()
        result.fixed shouldBe 0
        result.failed shouldBe 0
    }

    test("a re-run ignores an existing .srt.fixed output") {
        val dir = tempdir()
        File(dir, "ep.srt").writeText("00:00:01.00,00:00:02.00\nHello\n\n")

        fixDirectory(dir, SilentRenderer)
        val second = fixDirectory(dir, SilentRenderer)

        // Only ep.srt is a source; ep.srt.fixed ends in .fixed, not .srt, so it is never re-picked.
        second.files.map { it.fileName } shouldBe listOf("ep.srt")
        second.fixed shouldBe 1
    }
})

/** A renderer that discards everything: the model is the assertion surface, not the emissions. */
private object SilentRenderer : Renderer {
    override fun render(event: OutputEvent) {}
    override fun render(result: CommandResult) {}
    override fun progress(label: String, total: Int, interactive: Boolean?): ProgressHandle =
        object : ProgressHandle {
            override fun tick() {}
            override fun finish() {}
        }
}
