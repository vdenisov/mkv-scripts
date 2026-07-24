package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The legacy-SRT reformatting rules, pinned from faked line lists — `reformatSrt` is pure (no I/O), so the
 * whole v1 state machine and both failure messages live here in one data-driven table.
 */
class SrtFixTest : FunSpec({

    data class Case(
        val label: String,
        val lines: List<String>,
        val assert: (ReformatOutcome) -> Unit,
    )

    context("reformatSrt") {
        withData(
            nameFn = { it.label },
            Case(
                "two cues with a [br] break become numbered SRT cues",
                listOf(
                    "00:01:41.42,00:01:42.30",
                    "Line one[br]Line two",
                    "",
                    "00:01:43.00,00:01:44.00",
                    "Another line",
                    "",
                ),
            ) {
                it.shouldBeInstanceOf<ReformatOutcome.Reformatted>()
                it.lines shouldBe listOf(
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
            },
            Case(
                "preamble before the first 00: line is discarded",
                listOf(
                    "; a comment header",
                    "[Script Info]",
                    "00:00:01.00,00:00:02.00",
                    "Hello",
                    "",
                ),
            ) {
                it.shouldBeInstanceOf<ReformatOutcome.Reformatted>()
                it.lines shouldBe listOf("1", "00:00:01,000 --> 00:00:02,000", "Hello", "")
            },
            Case(
                "a non-blank line where a blank separator belongs is malformed",
                listOf(
                    "00:01:41.42,00:01:42.30",
                    "Text",
                    "not-blank where a blank line belongs",
                ),
            ) {
                it shouldBe ReformatOutcome.Malformed("expected a blank line, got: 'not-blank where a blank line belongs'")
            },
            Case(
                "an unparseable timing line is malformed",
                listOf(
                    "00:01:41.42 00:01:42.30",
                    "Text",
                    "",
                ),
            ) {
                it shouldBe ReformatOutcome.Malformed("Invalid time format: 00:01:41.42 00:01:42.30")
            },
            Case(
                "a file whose only timestamp is not hour 00 reformats to no lines",
                listOf(
                    "01:00:00.00,01:00:01.00",
                    "Never reached",
                    "",
                ),
            ) {
                it shouldBe ReformatOutcome.Reformatted(emptyList())
            },
            Case(
                "an entirely empty file reformats to no lines",
                emptyList(),
            ) {
                it shouldBe ReformatOutcome.Reformatted(emptyList())
            },
        ) { it.assert(reformatSrt(it.lines)) }
    }

    test("a text line with a trailing [br] drops the trailing empty (Java split semantics)") {
        val result = reformatSrt(listOf("00:00:01.00,00:00:02.00", "text[br]", ""))
        result.shouldBeInstanceOf<ReformatOutcome.Reformatted>()
        // Groovy/Java String.split drops trailing empties, so "text[br]" -> ["text"], not ["text", ""].
        result.lines shouldBe listOf("1", "00:00:01,000 --> 00:00:02,000", "text", "")
    }

    test("the centiseconds get one 0 appended to fake milliseconds") {
        val result = reformatSrt(listOf("00:00:00.05,00:00:00.09", "x", ""))
        result.shouldBeInstanceOf<ReformatOutcome.Reformatted>()
        result.lines[1] shouldBe "00:00:00,050 --> 00:00:00,090"
    }
})
