package org.plukh.mkvtool.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import org.plukh.mkvtool.core.FontUsageReport
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.TextStyle
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * `find-unused-fonts` prints one bare font base name per line to stdout — no prefix, no summary, no
 * color — exactly as v1 did.
 */
class FindUnusedFontsResultRendererTest : FunSpec({

    fun render(result: CommandResult): Pair<String, String> {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val style = TextStyle(
            colorEnabled = true,
            out = PrintStream(outBytes, true, StandardCharsets.UTF_8),
            err = PrintStream(errBytes, true, StandardCharsets.UTF_8),
        )
        FindUnusedFontsResultRenderer.render(result, style)
        return outBytes.toString(StandardCharsets.UTF_8) to errBytes.toString(StandardCharsets.UTF_8)
    }

    test("each unused font is a bare line to stdout, no color or prefix") {
        val (out, err) = render(FontUsageReport(listOf("Comic", "Verdana")))
        // Bare names, in order, one per line; trimEnd/lines keeps this platform line-separator agnostic.
        out.trimEnd().lines() shouldBe listOf("Comic", "Verdana")
        err.shouldBeEmpty()
    }

    test("an empty report prints nothing") {
        val (out, err) = render(FontUsageReport(emptyList()))
        out.shouldBeEmpty()
        err.shouldBeEmpty()
    }
})
