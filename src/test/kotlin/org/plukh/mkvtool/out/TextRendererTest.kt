package org.plukh.mkvtool.out

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Pins the shared message forms of [TextRenderer]: routing (stdout vs stderr), the `*** Error: ` /
 * `*** Warning: ` prefixes, whole-line coloring under `always`, plain passthrough under `never`, and
 * the whole-line color invariant (the uncolored text stays a contiguous substring).
 */
class TextRendererTest : FunSpec({

    val esc = Char(27).toString()

    // Renders one event through a TextRenderer wired to captured streams, off a real terminal.
    // Returns (stdout, stderr).
    fun render(colorMode: ColorMode, event: OutputEvent): Pair<String, String> {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        TextRenderer(
            colorMode = colorMode,
            out = PrintStream(outBytes, true, StandardCharsets.UTF_8),
            err = PrintStream(errBytes, true, StandardCharsets.UTF_8),
            isTerminal = { false },
            noColor = false,
        ).render(event)
        return outBytes.toString(StandardCharsets.UTF_8) to errBytes.toString(StandardCharsets.UTF_8)
    }

    test("Header goes to stdout, cyan under always") {
        val (out, err) = render(ColorMode.ALWAYS, Header("Processing X"))
        out shouldContain "${esc}[36mProcessing X${esc}[0m"
        err.shouldBeEmpty()
    }

    test("Success goes to stdout, green under always") {
        val (out, err) = render(ColorMode.ALWAYS, Success("*** Done"))
        out shouldContain "${esc}[32m*** Done${esc}[0m"
        err.shouldBeEmpty()
    }

    test("Error goes to stderr with the *** Error: prefix, red under always") {
        val (out, err) = render(ColorMode.ALWAYS, Error("bad config"))
        err shouldContain "${esc}[31m*** Error: bad config${esc}[0m"
        out.shouldBeEmpty()
    }

    test("Warning goes to stderr with the *** Warning: prefix, yellow under always") {
        val (out, err) = render(ColorMode.ALWAYS, Warning("stale cache"))
        err shouldContain "${esc}[33m*** Warning: stale cache${esc}[0m"
        out.shouldBeEmpty()
    }

    test("never mode emits plain text with no escapes") {
        val (out, _) = render(ColorMode.NEVER, Header("plain"))
        out shouldContain "plain"
        out shouldNotContain esc
    }

    test("error prefix and text are plain under never") {
        val (_, err) = render(ColorMode.NEVER, Error("boom"))
        err shouldContain "*** Error: boom"
        err shouldNotContain esc
    }

    test("whole-line invariant: uncolored text stays a contiguous substring under always") {
        val (out, _) = render(ColorMode.ALWAYS, Header("Layout 1 (3 files)"))
        out shouldContain "Layout 1 (3 files)"
    }

    test("Notice is plain uncolored text on stdout, even under always") {
        val (out, err) = render(ColorMode.ALWAYS, Notice("*** Dry run: nothing will be written"))
        out shouldContain "*** Dry run: nothing will be written"
        out shouldNotContain esc
        err.shouldBeEmpty()
    }

    test("Advisory is yellow on stdout with no prefix under always") {
        val (out, err) = render(ColorMode.ALWAYS, Advisory("*** No files"))
        out shouldContain "${esc}[33m*** No files${esc}[0m"
        err.shouldBeEmpty()
    }

    test("Error hint is a verbatim uncolored continuation line on stderr") {
        val (out, err) = render(ColorMode.ALWAYS, Error("bad encoding", hint = "      try windows-1251"))
        err shouldContain "${esc}[31m*** Error: bad encoding${esc}[0m"
        err shouldContain "\n      try windows-1251"
        out.shouldBeEmpty()
    }
})
