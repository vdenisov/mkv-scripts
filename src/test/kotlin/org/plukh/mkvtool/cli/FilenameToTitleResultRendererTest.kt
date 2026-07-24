package org.plukh.mkvtool.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.FileTitled
import org.plukh.mkvtool.core.TitleOutcome
import org.plukh.mkvtool.core.TitleRun
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.TextStyle
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Pins the v1 text of every `filename-to-title` result line, rendered through a [TextStyle] wired to
 * captured streams. The per-file failure carries the `*** Error:` prefix the harness pins (case 86); the
 * summary reads `<succeeded> processed, <failed> failed`, matching v1's `total - failed` arithmetic
 * (case 85 pins the clean form).
 */
class FilenameToTitleResultRendererTest : FunSpec({

    val esc = Char(27).toString()

    fun render(colorEnabled: Boolean, result: CommandResult): Pair<String, String> {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val style = TextStyle(
            colorEnabled = colorEnabled,
            out = PrintStream(outBytes, true, StandardCharsets.UTF_8),
            err = PrintStream(errBytes, true, StandardCharsets.UTF_8),
        )
        FilenameToTitleResultRenderer.render(result, style)
        return outBytes.toString(StandardCharsets.UTF_8) to errBytes.toString(StandardCharsets.UTF_8)
    }

    test("a succeeded file prints nothing (its header is a diagnostics event)") {
        val (out, err) = render(colorEnabled = true, FileTitled("ep.mkv", "ep", TitleOutcome.Succeeded))
        out.shouldBeEmpty()
        err.shouldBeEmpty()
    }

    test("the derived title never reaches the text — it lives in the model only") {
        val (out, err) = render(
            colorEnabled = false,
            FileTitled("My Episode.mkv", "My Episode", TitleOutcome.Failed(2)),
        )
        out shouldNotContain "My Episode"
        err shouldNotContain "My Episode"
    }

    test("a failed file is a red error on stderr naming mkvpropedit's exit code") {
        val (_, err) = render(colorEnabled = true, FileTitled("bad.mkv", "bad", TitleOutcome.Failed(2)))
        err shouldContain "${esc}[31m*** Error: mkvpropedit exited with code 2${esc}[0m"
    }

    test("a summary with a failure is red and counts successes as total minus failed") {
        val (out, _) = render(
            colorEnabled = true,
            TitleRun(listOf(FileTitled("bad.mkv", "bad", TitleOutcome.Failed(2))), total = 2, failed = 1),
        )
        out shouldContain "${esc}[31m*** 1 processed, 1 failed${esc}[0m"
    }

    test("a clean summary is green") {
        val (out, _) = render(
            colorEnabled = true,
            TitleRun(listOf(FileTitled("a.mkv", "a", TitleOutcome.Succeeded)), total = 1, failed = 0),
        )
        out shouldContain "${esc}[32m*** 1 processed, 0 failed${esc}[0m"
    }

    test("an empty run still prints a clean green summary (v1 prints it unconditionally)") {
        val (out, _) = render(colorEnabled = true, TitleRun(emptyList(), total = 0, failed = 0))
        out shouldContain "${esc}[32m*** 0 processed, 0 failed${esc}[0m"
    }
})
