package org.plukh.mkvtool.out

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Pins both [TextRenderer.progress] renderings: the in-place `\r` bar (interactive) and the appended
 * dots (non-interactive), the percentage dedup, the per-slice dot cadence, and the finish behavior.
 * The `interactive` override is what makes both paths testable through a pipe.
 */
class ProgressTest : FunSpec({

    // A progress meter writing into `buffer`, never treating the pipe as a terminal on its own.
    fun meter(buffer: ByteArrayOutputStream, label: String, total: Int, interactive: Boolean) =
        TextRenderer(
            colorMode = ColorMode.NEVER,
            out = PrintStream(buffer, true, StandardCharsets.UTF_8),
            err = PrintStream(ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            isTerminal = { false },
            noColor = true,
        ).progress(label, total, interactive = interactive)

    test("interactive bar: label, ASCII bar, percentages, one in-place frame per distinct percent") {
        val buffer = ByteArrayOutputStream()
        val p = meter(buffer, "Reading", total = 4, interactive = true)
        repeat(4) { p.tick() }

        val out = buffer.toString(StandardCharsets.UTF_8)
        out shouldContain "\rReading  ["
        out shouldContain "] 25%"
        out shouldContain "] 100%"
        out shouldContain "#".repeat(24) // fully filled at 100%
        out.count { it == '\r' } shouldBe 4
    }

    test("interactive bar suppresses frames when the percentage is unchanged") {
        val buffer = ByteArrayOutputStream()
        val p = meter(buffer, "Reading", total = 300, interactive = true)
        p.tick() // 1*100/300 = 0%
        p.tick() // 2*100/300 = 0%, unchanged -> suppressed

        val out = buffer.toString(StandardCharsets.UTF_8)
        out.count { it == '\r' } shouldBe 1
        out shouldContain "] 0%"
    }

    test("interactive finish prints the label when nothing ticked") {
        val buffer = ByteArrayOutputStream()
        val p = meter(buffer, "Reading", total = 5, interactive = true)
        p.finish()

        buffer.toString(StandardCharsets.UTF_8).trimEnd('\r', '\n') shouldBe "Reading"
    }

    test("non-interactive: label up front, one dot per slice, no carriage returns") {
        val buffer = ByteArrayOutputStream()
        val p = meter(buffer, "Reading", total = 100, interactive = false)
        repeat(100) { p.tick() }

        // Inspect the tick output before finish, so the finishing line separator (\r\n on Windows)
        // does not disturb the carriage-return assertion.
        val ticks = buffer.toString(StandardCharsets.UTF_8)
        ticks shouldStartWith "Reading"
        ticks shouldNotContain "\r"
        // perDot = ceil(100/40) = 3: multiples of 3 up to 99 (33) plus the final tick at count==total.
        ticks.count { it == '.' } shouldBe 34

        p.finish()
        buffer.toString(StandardCharsets.UTF_8) shouldContain "\n"
    }

    test("non-interactive with fewer items than dot slices ticks once per item") {
        val buffer = ByteArrayOutputStream()
        val p = meter(buffer, "Scan", total = 5, interactive = false)
        repeat(5) { p.tick() }

        // perDot clamps to 1, so every tick emits a dot.
        buffer.toString(StandardCharsets.UTF_8).count { it == '.' } shouldBe 5
    }
})
