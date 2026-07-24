package org.plukh.mkvtool.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.ConversionRun
import org.plukh.mkvtool.core.FileConversion
import org.plukh.mkvtool.core.FileOutcome
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.TextStyle
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Pins the v1 text of every `to-utf8` result line, rendered through a [TextStyle] wired to captured
 * streams. This is the renderer tier — the text lives in [ToUtf8ResultRenderer], not in command logic
 * or the generic `TextRenderer`.
 */
class ToUtf8ResultRendererTest : FunSpec({

    val esc = Char(27).toString()

    fun render(colorEnabled: Boolean, result: CommandResult): Pair<String, String> {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val style = TextStyle(
            colorEnabled = colorEnabled,
            out = PrintStream(outBytes, true, StandardCharsets.UTF_8),
            err = PrintStream(errBytes, true, StandardCharsets.UTF_8),
        )
        ToUtf8ResultRenderer.render(result, style)
        return outBytes.toString(StandardCharsets.UTF_8) to errBytes.toString(StandardCharsets.UTF_8)
    }

    test("UTF-16 skip line is uncolored on stdout") {
        val (out, err) = render(colorEnabled = true, FileConversion("wide.ssa", FileOutcome.Utf16Bom))
        out shouldContain "*** wide.ssa: looks like UTF-16 (BOM), leaving it alone"
        out shouldNotContain esc
        err.shouldBeEmpty()
    }

    test("already-UTF-8 skip lines are uncolored") {
        render(colorEnabled = true, FileConversion("a.srt", FileOutcome.Utf8Bom))
            .first shouldContain "*** a.srt: already UTF-8 (BOM), skipping"
        render(colorEnabled = true, FileConversion("a.srt", FileOutcome.Utf8Clean))
            .first shouldContain "*** a.srt: already valid UTF-8, skipping"
    }

    test("would-convert line names the charset, uncolored") {
        val (out, _) = render(colorEnabled = true, FileConversion("a.srt", FileOutcome.WouldConvert("windows-1251")))
        out shouldContain "*** a.srt: would convert from windows-1251 to UTF-8"
        out shouldNotContain esc
    }

    test("converted with backup prints the backup line then the converted line, uncolored") {
        val (out, _) = render(colorEnabled = true, FileConversion("a.srt", FileOutcome.Converted("windows-1251", "a.srt.orig")))
        out shouldContain "*** a.srt: backed up as a.srt.orig"
        out shouldContain "*** a.srt: converted from windows-1251 to UTF-8"
        out shouldNotContain esc
    }

    test("converted without backup omits the backup line") {
        val (out, _) = render(colorEnabled = false, FileConversion("a.srt", FileOutcome.Converted("windows-1251", null)))
        out shouldNotContain "backed up"
        out shouldContain "*** a.srt: converted from windows-1251 to UTF-8"
    }

    test("invalid source is a red error on stderr with an uncolored hint") {
        val (out, err) = render(
            colorEnabled = true,
            FileConversion("bad.srt", FileOutcome.NotValidSource("Shift_JIS", "MalformedInputException")),
        )
        err shouldContain "${esc}[31m*** Error: bad.srt: not valid Shift_JIS (MalformedInputException), leaving it alone${esc}[0m"
        err shouldContain "      Pass the right --encoding; converting anyway would produce mojibake."
        out.shouldBeEmpty()
    }

    test("clean summary is green with the escapes at the line edges") {
        val (out, _) = render(
            colorEnabled = true,
            ConversionRun("windows-1251", false, listOf(FileConversion("a.srt", FileOutcome.Converted("windows-1251", null))), 1, 0, 0),
        )
        out shouldContain "${esc}[32m*** 1 converted, 0 skipped, 0 failed${esc}[0m"
    }

    test("failed summary is red") {
        val (out, _) = render(
            colorEnabled = true,
            ConversionRun("windows-1251", false, listOf(FileConversion("bad.srt", FileOutcome.NotValidSource("Shift_JIS", "X"))), 0, 0, 1),
        )
        out shouldContain "${esc}[31m*** 0 converted, 0 skipped, 1 failed${esc}[0m"
    }

    test("an empty run prints no summary at all") {
        val (out, err) = render(colorEnabled = true, ConversionRun("windows-1251", false, emptyList(), 0, 0, 0))
        out.shouldBeEmpty()
        err.shouldBeEmpty()
    }
})
