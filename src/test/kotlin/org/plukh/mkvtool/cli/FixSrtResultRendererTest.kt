package org.plukh.mkvtool.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.FileFix
import org.plukh.mkvtool.core.FixOutcome
import org.plukh.mkvtool.core.FixRun
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.TextStyle
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Pins the v1 text of every `fix-srt` result line, rendered through a [TextStyle] wired to captured
 * streams. This is the renderer tier — the text lives in [FixSrtResultRenderer], not in command logic.
 */
class FixSrtResultRendererTest : FunSpec({

    val esc = Char(27).toString()

    fun render(colorEnabled: Boolean, result: CommandResult): Pair<String, String> {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val style = TextStyle(
            colorEnabled = colorEnabled,
            out = PrintStream(outBytes, true, StandardCharsets.UTF_8),
            err = PrintStream(errBytes, true, StandardCharsets.UTF_8),
        )
        FixSrtResultRenderer.render(result, style)
        return outBytes.toString(StandardCharsets.UTF_8) to errBytes.toString(StandardCharsets.UTF_8)
    }

    test("a fixed file prints nothing (its header is a diagnostics event)") {
        val (out, err) = render(colorEnabled = true, FileFix("ep.srt", FixOutcome.Fixed))
        out.shouldBeEmpty()
        err.shouldBeEmpty()
    }

    test("a failed file is a red error on stderr, naming the file and the reason") {
        val (out, err) = render(
            colorEnabled = true,
            FileFix("bad.srt", FixOutcome.Failed("expected a blank line, got: 'x'")),
        )
        err shouldContain "${esc}[31m*** Error: bad.srt: expected a blank line, got: 'x' (left unfixed)${esc}[0m"
        out.shouldBeEmpty()
    }

    test("the failed error carries no stack trace") {
        val (_, err) = render(colorEnabled = false, FileFix("bad.srt", FixOutcome.Failed("Invalid time format: nope")))
        err shouldContain "*** Error: bad.srt: Invalid time format: nope (left unfixed)"
        err shouldNotContain "Exception"
    }

    test("a clean summary is green with the escapes at the line edges") {
        val (out, _) = render(
            colorEnabled = true,
            FixRun(listOf(FileFix("ep.srt", FixOutcome.Fixed)), 1, 0),
        )
        out shouldContain "${esc}[32m*** 1 fixed, 0 failed${esc}[0m"
    }

    test("a summary with a failure is red") {
        val (out, _) = render(
            colorEnabled = true,
            FixRun(listOf(FileFix("bad.srt", FixOutcome.Failed("x"))), 1, 1),
        )
        out shouldContain "${esc}[31m*** 1 fixed, 1 failed${esc}[0m"
    }

    test("an empty run prints no summary at all") {
        val (out, err) = render(colorEnabled = true, FixRun(emptyList(), 0, 0))
        out.shouldBeEmpty()
        err.shouldBeEmpty()
    }
})
