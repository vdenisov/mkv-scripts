package org.plukh.mkvtool.out

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Pins [colorModeOf] parsing (exact-match, unknown -> NEVER) and the color gating rule
 * `enabled = always || (auto && terminal && !NO_COLOR)`, observed via whether a rendered [Header]
 * carries the cyan escape.
 */
class ColorModeTest : FunSpec({

    val esc = Char(27).toString()

    context("colorModeOf parses --color values") {
        withData(
            "always" to ColorMode.ALWAYS,
            "auto" to ColorMode.AUTO,
            "never" to ColorMode.NEVER,
            "Always" to ColorMode.NEVER, // exact match only: mixed case is not 'always'
            "yes" to ColorMode.NEVER,
            "" to ColorMode.NEVER,
        ) { (input, expected) ->
            colorModeOf(input) shouldBe expected
        }

        test("null parses to NEVER") {
            colorModeOf(null) shouldBe ColorMode.NEVER
        }
    }

    context("color gating") {
        data class Case(
            val mode: ColorMode,
            val terminal: Boolean,
            val noColor: Boolean,
            val colored: Boolean,
        )

        withData(
            nameFn = { "mode=${it.mode} terminal=${it.terminal} noColor=${it.noColor} -> colored=${it.colored}" },
            Case(ColorMode.ALWAYS, terminal = false, noColor = false, colored = true),
            Case(ColorMode.ALWAYS, terminal = false, noColor = true, colored = true), // always beats NO_COLOR
            Case(ColorMode.AUTO, terminal = true, noColor = false, colored = true),
            Case(ColorMode.AUTO, terminal = false, noColor = false, colored = false), // not a terminal
            Case(ColorMode.AUTO, terminal = true, noColor = true, colored = false), // NO_COLOR set
            Case(ColorMode.NEVER, terminal = true, noColor = false, colored = false),
        ) { (mode, terminal, noColor, colored) ->
            val outBytes = ByteArrayOutputStream()
            TextRenderer(
                colorMode = mode,
                out = PrintStream(outBytes, true, StandardCharsets.UTF_8),
                err = PrintStream(ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                isTerminal = { terminal },
                noColor = noColor,
            ).render(Header("x"))

            val out = outBytes.toString(StandardCharsets.UTF_8)
            if (colored) out shouldContain "${esc}[36m" else out shouldNotContain esc
        }
    }
})
