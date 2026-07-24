package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.decodeWindows1251Strict
import org.plukh.mkvtool.core.nativeLanguageName
import picocli.CommandLine.Command
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable

/**
 * Hidden diagnostic that verifies a native-image build kept its charsets and locale
 * data. It self-asserts and returns a non-zero exit code on any mismatch, so CI needs
 * only to run this against the native binary and check the exit code - encoding-
 * independent String equality inside the runtime, not fragile console-output grepping.
 * The printed values are for humans.
 *
 * Hidden because it is a build probe, not a user feature. It is also temporary: once the
 * `to-utf8` command and the external-track language guesser land their real charset and
 * locale-name utilities, this can point at them or be removed (see core/NativeSmoke.kt).
 */
@Command(
    name = "native-smoke",
    hidden = true,
    description = ["Diagnostic: verify native-image charset and locale-data inclusion."],
)
class NativeSmokeCommand : Callable<Int> {

    override fun call(): Int {
        // Windows-1251 bytes for "Русский" (Р у с с к и й). The native locale name for
        // "ru" is the same string, so both probes converge on one expected value.
        val cp1251Sample = byteArrayOf(
            0xD0.toByte(), 0xF3.toByte(), 0xF1.toByte(), 0xF1.toByte(),
            0xEA.toByte(), 0xE8.toByte(), 0xE9.toByte(),
        )
        val expected = "Русский"

        // Print diagnostics as explicit UTF-8: a native binary on a legacy Windows
        // console codepage would otherwise mangle the Cyrillic and hide the real result.
        val utf8Out = PrintStream(System.out, true, StandardCharsets.UTF_8.name())

        val decoded = decodeWindows1251Strict(cp1251Sample)
        val nativeName = nativeLanguageName("ru")

        utf8Out.println("charset (windows-1251 decode): $decoded")
        utf8Out.println("locale (ru native name):       $nativeName")

        val charsetOk = decoded == expected
        val localeOk = nativeName == expected
        if (charsetOk && localeOk) {
            utf8Out.println("native-smoke: OK")
            return 0
        }

        if (!charsetOk) utf8Out.println("native-smoke: FAIL charset - expected '$expected', got '$decoded'")
        if (!localeOk) utf8Out.println("native-smoke: FAIL locale - expected '$expected', got '$nativeName'")
        return 1
    }
}
