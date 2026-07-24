package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.charset.Charset

/**
 * The charset-decision matrix — the poster child for a data-driven table. `classify` is pure (no I/O),
 * so the whole v1 decision order is pinned here from faked bytes, with no mkvmerge and no filesystem.
 */
class Utf8ConversionTest : FunSpec({

    val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    val utf16Bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    data class Case(
        val label: String,
        val bytes: ByteArray,
        val source: Charset,
        val assert: (Utf8Verdict) -> Unit,
    )

    context("classify") {
        withData(
            nameFn = { it.label },
            Case("windows-1251 Cyrillic is a conversion candidate",
                "Привет".toByteArray(charset("windows-1251")), charset("windows-1251")) {
                it.shouldBeInstanceOf<Utf8Verdict.Convertible>()
            },
            Case("clean UTF-8 is already valid",
                "Привет".toByteArray(Charsets.UTF_8), charset("windows-1251")) {
                it shouldBe Utf8Verdict.Utf8Clean
            },
            Case("UTF-8 with BOM is recognised by BOM",
                utf8Bom + "Привет".toByteArray(Charsets.UTF_8), charset("windows-1251")) {
                it shouldBe Utf8Verdict.Utf8Bom
            },
            Case("pure ASCII counts as already-valid UTF-8",
                "WEBVTT".toByteArray(Charsets.US_ASCII), charset("windows-1251")) {
                it shouldBe Utf8Verdict.Utf8Clean
            },
            Case("UTF-16 (BOM) is refused",
                utf16Bom + "Привет".toByteArray(Charsets.UTF_16LE), charset("windows-1251")) {
                it shouldBe Utf8Verdict.Utf16Bom
            },
            Case("windows-1250 bytes convert under windows-1250",
                byteArrayOf(0xC8.toByte(), 0x61, 0x6A), charset("windows-1250")) {
                it.shouldBeInstanceOf<Utf8Verdict.Convertible>()
            },
            Case("invalid Shift_JIS bytes are rejected",
                byteArrayOf(0x81.toByte(), 0x20, 0x41), charset("Shift_JIS")) {
                it.shouldBeInstanceOf<Utf8Verdict.Invalid>()
            },
        ) { it.assert(classify(it.bytes, it.source)) }
    }

    test("a conversion candidate keeps CRLF in its decoded text") {
        val bytes = "Привет\r\nмир\r\n".toByteArray(charset("windows-1251"))
        val verdict = classify(bytes, charset("windows-1251"))
        verdict.shouldBeInstanceOf<Utf8Verdict.Convertible>()
        verdict.text shouldBe "Привет\r\nмир\r\n"
    }

    test("windows-1250 decodes to the expected text") {
        val verdict = classify(byteArrayOf(0xC8.toByte(), 0x61, 0x6A), charset("windows-1250"))
        verdict.shouldBeInstanceOf<Utf8Verdict.Convertible>()
        verdict.text shouldBe "Čaj"
    }
})
