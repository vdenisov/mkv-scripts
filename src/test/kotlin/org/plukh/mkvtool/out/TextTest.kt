package org.plukh.mkvtool.out

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

/**
 * Pins the pure text helpers [plural] and [pluralize], including n=0/1/2 and an irregular plural
 * supplied explicitly ("discrepancy" -> "discrepancies").
 */
class TextTest : FunSpec({

    context("plural: count plus the correctly-pluralized noun") {
        data class Case(val n: Int, val noun: String, val plural: String?, val expected: String)

        withData(
            nameFn = { "plural(${it.n}, ${it.noun}, ${it.plural}) = ${it.expected}" },
            Case(0, "file", null, "0 files"),
            Case(1, "file", null, "1 file"),
            Case(2, "file", null, "2 files"),
            Case(1, "discrepancy", "discrepancies", "1 discrepancy"),
            Case(3, "discrepancy", "discrepancies", "3 discrepancies"),
        ) { (n, noun, plural, expected) ->
            plural(n, noun, plural) shouldBe expected
        }
    }

    context("pluralize: the noun alone") {
        data class Case(val n: Int, val noun: String, val plural: String?, val expected: String)

        withData(
            nameFn = { "pluralize(${it.n}, ${it.noun}, ${it.plural}) = ${it.expected}" },
            Case(0, "file", null, "files"),
            Case(1, "file", null, "file"),
            Case(2, "file", null, "files"),
            Case(1, "discrepancy", "discrepancies", "discrepancy"),
            Case(2, "discrepancy", "discrepancies", "discrepancies"),
        ) { (n, noun, plural, expected) ->
            pluralize(n, noun, plural) shouldBe expected
        }
    }
})
