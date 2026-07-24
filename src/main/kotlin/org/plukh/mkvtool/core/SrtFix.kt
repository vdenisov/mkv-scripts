package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.Advisory
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.Header
import org.plukh.mkvtool.out.Renderer
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * The `fix-srt` engine: reformats a legacy subtitle format into standard numbered SRT cues, plus its
 * result model and the directory orchestrator. A verbatim port of `src/fix_srt.groovy`.
 *
 * The legacy input has no cue numbers; each block is a timing line `hh:mm:ss.cc,hh:mm:ss.cc`, a single
 * physical text line using `[br]` for internal breaks, and a blank separator. The fixer generates the
 * cue numbers, rewrites the timing to `hh:mm:ss,ccc --> hh:mm:ss,ccc`, and splits `[br]` into real lines.
 */

/** A three-digit millisecond field is faked by appending one `"0"` to the two centisecond digits. */
private enum class State { TIME, TEXT, NEWLINE }

/** Raised for a malformed line; caught at the [reformatSrt] boundary and turned into [ReformatOutcome.Malformed].
 *  A regular exception (not an `assert`/`Error`) on purpose: v1 caught `Exception`, and an `AssertionError`
 *  would escape that and abort the whole batch. */
private class SrtFormatException(message: String) : Exception(message)

/** The literal `[br]` token that separates lines within one legacy text line. Compiled once. */
private val BR: Pattern = Pattern.compile("\\[br]")

/**
 * The legacy timing line, e.g. `00:01:41.42,00:01:42.30`. The `.` before each centisecond pair is
 * unescaped **on purpose** (it matches any single char, as in v1); the two timestamps are separated by a
 * literal `,`. Anchored to the whole line.
 */
private val TIME_LINE = Regex("^(\\d\\d):(\\d\\d):(\\d\\d).(\\d\\d),(\\d\\d):(\\d\\d):(\\d\\d).(\\d\\d)$")

/** The result of reformatting one file's lines: the rewritten lines, or the reason it is malformed. */
sealed interface ReformatOutcome {
    data class Reformatted(val lines: List<String>) : ReformatOutcome
    data class Malformed(val message: String) : ReformatOutcome
}

/**
 * Reformat one file's [lines]. Skips any preamble before the first line starting with `"00:"` (once off,
 * skipping never resumes), then runs the TIME → TEXT → NEWLINE state machine: emit the generated cue
 * number and rewritten timing, emit the `[br]`-split text, then a blank separator. A non-blank line where
 * the separator belongs, or a timing line that does not parse, yields [ReformatOutcome.Malformed].
 *
 * A file whose only timestamp starts with a non-`00` hour never trips the skip gate, so it reformats to an
 * empty line list — v1's behavior, which then writes an empty `.fixed` file counted as fixed.
 */
fun reformatSrt(lines: List<String>): ReformatOutcome {
    val out = ArrayList<String>()
    var state = State.TIME
    var count = 1
    var skip = true
    return try {
        for (line in lines) {
            if (line.startsWith("00:")) skip = false
            if (skip) continue
            when (state) {
                State.TIME -> {
                    out.add((count++).toString())
                    out.add(parseTime(line))
                    state = State.TEXT
                }
                State.TEXT -> {
                    out.addAll(parseText(line))
                    state = State.NEWLINE
                }
                State.NEWLINE -> {
                    out.add("")
                    if (line.isNotEmpty()) throw SrtFormatException("expected a blank line, got: '$line'")
                    state = State.TIME
                }
            }
        }
        ReformatOutcome.Reformatted(out)
    } catch (e: SrtFormatException) {
        ReformatOutcome.Malformed(e.message!!)
    }
}

/** Rewrite a legacy timing line to SRT `hh:mm:ss,ccc --> hh:mm:ss,ccc`, faking the ms by appending `"0"`. */
private fun parseTime(line: String): String {
    val m = TIME_LINE.matchEntire(line) ?: throw SrtFormatException("Invalid time format: $line")
    val g = m.groupValues
    return "${g[1]}:${g[2]}:${g[3]},${g[4]}0 --> ${g[5]}:${g[6]}:${g[7]},${g[8]}0"
}

/** Split one physical text line on the literal `[br]`. Uses [Pattern.split] (Java semantics: trailing
 *  empty strings dropped) to match Groovy's `String.split` — Kotlin's `Regex.split` keeps them. */
private fun parseText(line: String): List<String> = BR.split(line).toList()

/** What happened to one file. */
sealed interface FixOutcome {
    /** Reformatted; a `.srt.fixed` sibling was written. */
    data object Fixed : FixOutcome

    /** Malformed: [message] is the reason (verbatim v1 text); no `.fixed` was written. */
    data class Failed(val message: String) : FixOutcome
}

/** One file's result, carrying its own identity. */
data class FileFix(val fileName: String, val outcome: FixOutcome) : CommandResult

/** The run root: the children verbatim, plus the parent's own projection (the counts). */
data class FixRun(val files: List<FileFix>, val fixed: Int, val failed: Int) : CommandResult

/**
 * Reformat every `.srt` file in [dir] (non-recursive, name-sorted), writing each result to a sibling
 * `<name>.srt.fixed`. One malformed file is reported and skipped, never aborting the batch; the exit code
 * derives from `root.failed`. The per-file `*** Fixing <name>` header is narration (a diagnostics event);
 * the per-file failure line and the counts summary are results, rendered by the caller's renderer.
 *
 * `.srt.fixed` outputs do not end in `.srt`, so a re-run does not re-pick them. [dir] is passed in
 * explicitly (rather than read from `File(".")`) so the engine is unit-testable in-process.
 */
fun fixDirectory(dir: File, renderer: Renderer): FixRun {
    val files = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".srt") }
        .sortedBy { it.name }

    if (files.isEmpty()) {
        renderer.render(Advisory("*** No .srt files in the current directory"))
    }

    var fixed = 0
    var failed = 0
    val results = ArrayList<FileFix>(files.size)

    for (file in files) {
        renderer.render(Header("*** Fixing ${file.name}"))

        val outcome: FixOutcome = when (val result = reformatSrt(file.readLines(StandardCharsets.UTF_8))) {
            is ReformatOutcome.Reformatted -> {
                // The .fixed output is written only after a clean parse. Each line gets a trailing newline
                // (v1 println'd them one by one); an empty line list writes an empty file.
                val output = File(file.parentFile, "${file.name}.fixed")
                output.writeText(result.lines.joinToString("") { "$it\n" }, StandardCharsets.UTF_8)
                fixed++
                FixOutcome.Fixed
            }
            is ReformatOutcome.Malformed -> {
                failed++
                FixOutcome.Failed(result.message)
            }
        }

        val fileFix = FileFix(file.name, outcome)
        results.add(fileFix)
        renderer.render(fileFix)
    }

    // v1 prints no summary for an empty directory; the result renderer omits it when there are no files.
    val root = FixRun(results, fixed, failed)
    renderer.render(root)
    return root
}
