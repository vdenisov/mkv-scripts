package org.plukh.mkvtool.core

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Probe logic for the two native-image failure modes that fail *silently*: a native
 * binary built without `-H:+AddAllCharsets` cannot decode single-byte charsets like
 * Windows-1251, and one built without locale-data inclusion returns empty CLDR display
 * names. Both work on any ordinary JVM, so only an end-to-end run of the actual native
 * binary catches a broken build - which is what the hidden `native-smoke` command does.
 *
 * These two functions are deliberately minimal and self-contained. They mirror the v1
 * idioms (`to_utf8.groovy`'s strict decoder and `subst.groovy`'s native-name lookup) and
 * exist only until the real utilities land: the `to-utf8` command will own strict
 * decoding and the external-track language guesser will own native language names. Once
 * those exist the probe can point at them, or retire. Do not grow shared abstractions
 * here in the meantime.
 */

/**
 * Decodes [bytes] as Windows-1251, rejecting any byte that is not valid in that
 * charset rather than substituting U+FFFD. Strictness (REPORT, not the default
 * REPLACE) is what makes this a real charset probe: on a native binary missing the
 * charset, `Charset.forName` throws outright.
 */
fun decodeWindows1251Strict(bytes: ByteArray): String =
    Charset.forName("windows-1251")
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

/**
 * The native (self-referential) CLDR display name for a language [code], e.g. "ru"
 * -> "Русский". First letter upper-cased with the language's own rules, since many
 * languages spell their own name in lower case. Returns null when the JDK has no
 * display name and just echoes the code back - which is exactly the empty-locale-data
 * symptom on a misconfigured native binary.
 */
fun nativeLanguageName(code: String): String? {
    val locale = Locale.of(code)
    val name = locale.getDisplayLanguage(locale)
    if (name.isBlank() || name.equals(code, ignoreCase = true)) return null
    return name.substring(0, 1).uppercase(locale) + name.substring(1)
}
