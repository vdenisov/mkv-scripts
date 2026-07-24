package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.Header
import org.plukh.mkvtool.out.Notice
import org.plukh.mkvtool.out.Renderer
import java.io.File

/**
 * The `propedit` engine: run `mkvpropedit` with a set of pass-through options against every `.mkv` in a
 * directory. A verbatim port of `src/propedit.groovy` — a generic wrapper that inserts the file name as
 * the first argument and forwards everything else untouched, so any property mkvpropedit can set works
 * without editing this code.
 */

/** What happened to one file. */
sealed interface PropeditOutcome {
    /** mkvpropedit exited 0. */
    data object Succeeded : PropeditOutcome

    /** mkvpropedit exited non-zero: [exitCode] is that code (rendered into the per-file error line). */
    data class Failed(val exitCode: Int) : PropeditOutcome
}

/** One file's result, carrying its own identity. */
data class FileProped(val fileName: String, val outcome: PropeditOutcome) : CommandResult

/** The run root: the children verbatim, plus the parent's own projection (the counts). */
data class PropeditRun(val files: List<FileProped>, val total: Int, val failed: Int) : CommandResult

/**
 * Build the mkvpropedit command line for one file: `[exe, "<baseName>.mkv", <args...>]`. The user's
 * [args] are forwarded verbatim, after the file name.
 *
 * v1 reconstructs the name as `baseName + ".mkv"` (via `FilenameUtils.getBaseName`), which lower-cases
 * the extension — reproduced here with [File.nameWithoutExtension]. For the ordinary `Show.mkv` this is
 * identical to the real name; the divergence on an upper-case `Show.MKV` is a latent v1 bug recorded
 * under Discovered work.
 */
fun buildPropeditCommand(exe: String, file: File, args: List<String>): List<String> =
    listOf(exe, "${file.nameWithoutExtension}.mkv") + args

/**
 * Run mkvpropedit ([exe]) with [args] against every `.mkv` in [dir]. Each file gets a `*** Processing`
 * header (narration); mkvpropedit's own stdout/stderr are inherited so its progress reaches the console
 * unchanged (v1 `consumeProcessOutput(System.out, System.err)`). A non-zero child exit is a per-file
 * [PropeditOutcome.Failed] and increments the failed count; the batch never aborts. The caller derives
 * the exit code from `root.failed` (1 on any failure, else 0).
 *
 * Files are name-sorted for a deterministic order — v1 relied on the platform's undefined `listFiles`
 * order, which no test pins. [dir] is passed in explicitly so the orchestrator is exercisable in-process.
 */
fun propeditDirectory(dir: File, exe: String, args: List<String>, renderer: Renderer): PropeditRun {
    val files = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() == "mkv" }
        .sortedBy { it.name }

    var failed = 0
    val results = ArrayList<FileProped>(files.size)

    for (file in files) {
        renderer.render(Header("*** Processing ${file.name}"))
        renderer.render(Notice(""))

        val code = ProcessBuilder(buildPropeditCommand(exe, file, args))
            .inheritIO()
            .start()
            .waitFor()

        val outcome = if (code == 0) {
            PropeditOutcome.Succeeded
        } else {
            failed++
            PropeditOutcome.Failed(code)
        }

        val result = FileProped(file.name, outcome)
        results.add(result)
        renderer.render(result)
    }

    val root = PropeditRun(results, files.size, failed)
    renderer.render(root)
    return root
}
