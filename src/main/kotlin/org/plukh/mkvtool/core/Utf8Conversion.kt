package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.Advisory
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.Header
import org.plukh.mkvtool.out.Notice
import org.plukh.mkvtool.out.Renderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The `to-utf8` conversion engine: the charset-decision logic, its result model, and the directory
 * orchestrator. Behavior is a verbatim port of `src/to_utf8.groovy`; the design subtleties it encodes
 * (strict-decode to refuse mojibake, UTF-16 refusal, idempotent re-run, whole-content re-encode to
 * keep CRLF) are documented on `classify` and in CLAUDE.md.
 */

/** Text subtitle formats worth converting. `.sub` is deliberately absent — ambiguous between MicroDVD
 *  text and the binary VobSub half of a `.idx`/`.sub` pair, and rewriting a VobSub `.sub` destroys it. */
val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

/**
 * Decode strictly, so bytes that are not valid/mappable in [cs] raise a [CharacterCodingException]
 * instead of being silently replaced with U+FFFD — which is exactly how decoding Windows-1251 bytes as
 * Windows-1250 "succeeds" and produces mojibake nobody notices until playback.
 */
fun strictDecode(bytes: ByteArray, cs: Charset): String =
    cs.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

/** The verdict `classify` reaches for one file. Convertible carries the decoded text to write back;
 *  Invalid carries the decode exception's simple name for the message. */
sealed interface Utf8Verdict {
    data object Utf16Bom : Utf8Verdict
    data object Utf8Bom : Utf8Verdict
    data object Utf8Clean : Utf8Verdict
    data class Convertible(val text: String) : Utf8Verdict
    data class Invalid(val exceptionName: String) : Utf8Verdict
}

/**
 * Decide what to do with a file's [bytes], in v1's order:
 *  1. A UTF-16 BOM (`FF FE` / `FE FF`) → refuse: a UTF-16 file decodes "successfully" under a single-byte
 *     charset (every byte maps) and would be rewritten as mojibake.
 *  2. A UTF-8 BOM (`EF BB BF`) → already UTF-8.
 *  3. Decodes cleanly as strict UTF-8 → already UTF-8 (pure ASCII lands here too). The decoded string is
 *     discarded — it is only a validity probe — which is what makes conversion idempotent/safe to re-run.
 *  4. Otherwise decode with [source]; success → a conversion candidate carrying the text, a
 *     [CharacterCodingException] → invalid for that source charset (leave the file alone).
 */
fun classify(bytes: ByteArray, source: Charset): Utf8Verdict {
    if (startsWith(bytes, 0xFF, 0xFE) || startsWith(bytes, 0xFE, 0xFF)) return Utf8Verdict.Utf16Bom
    if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) return Utf8Verdict.Utf8Bom
    try {
        strictDecode(bytes, StandardCharsets.UTF_8)
        return Utf8Verdict.Utf8Clean
    } catch (_: CharacterCodingException) {
        // Not UTF-8, so it is a genuine conversion candidate.
    }
    return try {
        Utf8Verdict.Convertible(strictDecode(bytes, source))
    } catch (e: CharacterCodingException) {
        Utf8Verdict.Invalid(e.javaClass.simpleName)
    }
}

/** What happened to one file. Each variant carries the canonical charset name it was judged against, so
 *  the renderer needs no run-level state to render a file line. */
sealed interface FileOutcome {
    /** Skipped: a UTF-16 BOM was found. */
    data object Utf16Bom : FileOutcome

    /** Skipped: already UTF-8 by BOM. */
    data object Utf8Bom : FileOutcome

    /** Skipped: already valid UTF-8 (pure ASCII included). */
    data object Utf8Clean : FileOutcome

    /** Failed: the bytes are not valid for the source charset. [exceptionName] is the decoder's exception. */
    data class NotValidSource(val charsetName: String, val exceptionName: String) : FileOutcome

    /** A dry run recorded that this file would be converted; nothing was written. */
    data class WouldConvert(val charsetName: String) : FileOutcome

    /** Converted in place. [backupName] is the `.orig` sibling written first, or null when `--backup` was off. */
    data class Converted(val charsetName: String, val backupName: String?) : FileOutcome
}

/** One file's result, carrying its own identity. */
data class FileConversion(val fileName: String, val outcome: FileOutcome) : CommandResult

/** The run root: the children verbatim, plus the parent's own projection (the counts). */
data class ConversionRun(
    val charsetName: String,
    val dryRun: Boolean,
    val files: List<FileConversion>,
    val converted: Int,
    val skipped: Int,
    val failed: Int,
) : CommandResult

/**
 * Convert every subtitle file in [dir] to UTF-8 in place, emitting each file's result live and the root
 * at the end, and returning the root (the exit code derives from `root.failed`). Narration — the banner,
 * the dry-run notice, the leading blank, the no-files advisory — goes over the diagnostics channel; the
 * per-file lines and the summary are results, rendered by the caller's [ResultTextRenderer].
 *
 * [dir] is passed explicitly rather than read from `File(".")` so the engine is unit-testable in-process
 * (the process CWD is global and cannot be set per test).
 */
fun convertDirectory(
    dir: File,
    charset: Charset,
    backup: Boolean,
    dryRun: Boolean,
    renderer: Renderer,
): ConversionRun {
    val sorted = SUBTITLE_EXTENSIONS.sorted()
    renderer.render(Header("*** Converting ${sorted.joinToString(", ")} files from ${charset.name()} to UTF-8"))
    if (dryRun) renderer.render(Notice("*** Dry run: nothing will be written"))
    renderer.render(Notice(""))

    val files = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in SUBTITLE_EXTENSIONS }
        .sortedBy { it.name }

    if (files.isEmpty()) {
        renderer.render(Advisory("*** No ${sorted.joinToString("/")} files in the current directory"))
    }

    var converted = 0
    var skipped = 0
    var failed = 0
    val results = ArrayList<FileConversion>(files.size)

    for (file in files) {
        val bytes = file.readBytes()
        val outcome: FileOutcome = when (val verdict = classify(bytes, charset)) {
            Utf8Verdict.Utf16Bom -> { skipped++; FileOutcome.Utf16Bom }
            Utf8Verdict.Utf8Bom -> { skipped++; FileOutcome.Utf8Bom }
            Utf8Verdict.Utf8Clean -> { skipped++; FileOutcome.Utf8Clean }
            is Utf8Verdict.Invalid -> { failed++; FileOutcome.NotValidSource(charset.name(), verdict.exceptionName) }
            is Utf8Verdict.Convertible -> {
                if (dryRun) {
                    converted++
                    FileOutcome.WouldConvert(charset.name())
                } else {
                    // Backup holds the original raw bytes and is written before the overwrite; dry-run
                    // returned above, so `--dry-run --backup` writes no `.orig`.
                    var backupName: String? = null
                    if (backup) {
                        val orig = File(file.parentFile, "${file.name}.orig")
                        orig.writeBytes(bytes)
                        backupName = orig.name
                    }
                    // Write the decoded text back whole, not line by line, so existing line endings
                    // (SRT in the wild is usually CRLF) survive instead of being normalised.
                    file.writeBytes(verdict.text.toByteArray(StandardCharsets.UTF_8))
                    converted++
                    FileOutcome.Converted(charset.name(), backupName)
                }
            }
        }
        val fileConversion = FileConversion(file.name, outcome)
        results.add(fileConversion)
        renderer.render(fileConversion)
    }

    val root = ConversionRun(charset.name(), dryRun, results, converted, skipped, failed)
    renderer.render(root)
    return root
}

/** True when [bytes] begins with the unsigned byte sequence [prefix] (JVM bytes are signed; mask to 0–255). */
private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean =
    bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
