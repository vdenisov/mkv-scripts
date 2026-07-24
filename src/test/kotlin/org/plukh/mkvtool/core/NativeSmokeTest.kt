package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the probe *logic* (see core/NativeSmoke.kt). On any JVM every charset and locale
 * is present, so these always pass here - which is the point: catching a native binary
 * that dropped them is the job of the `native-smoke` command run against the actual
 * native image in CI, not of this in-process tier.
 */
class NativeSmokeTest : FunSpec({

    // Windows-1251 bytes for "Русский" (Р у с с к и й).
    val cp1251Rus = byteArrayOf(
        0xD0.toByte(), 0xF3.toByte(), 0xF1.toByte(), 0xF1.toByte(),
        0xEA.toByte(), 0xE8.toByte(), 0xE9.toByte(),
    )

    test("strict Windows-1251 decode yields Русский") {
        decodeWindows1251Strict(cp1251Rus) shouldBe "Русский"
    }

    test("native language name for ru is Русский (upper-cased in-locale)") {
        nativeLanguageName("ru") shouldBe "Русский"
    }
})
