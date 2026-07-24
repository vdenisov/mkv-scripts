package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.Header
import org.plukh.mkvtool.out.Notice
import org.plukh.mkvtool.out.Renderer
import java.io.File

/**
 * The `filename-to-title` engine: set each MKV's segment title and video track name from its file name,
 * via `mkvpropedit` — no remux needed. A verbatim port of `src/filename_to_title.groovy`.
 *
 * This is the second `mkvpropedit` batch command and its loop, message text and exit discipline are
 * identical to [propeditDirectory]'s; the two are kept apart on purpose. Only the pure helpers are
 * shared ([findMkvTool], [buildPropeditCommand]) — a shared engine would need one result model to serve
 * both commands, and `propedit` has no title to report, so that model could not be complete for either.
 */

/** What happened to one file. */
sealed interface TitleOutcome {
    /** mkvpropedit exited 0. */
    data object Succeeded : TitleOutcome

    /** mkvpropedit exited non-zero: [exitCode] is that code (rendered into the per-file error line). */
    data class Failed(val exitCode: Int) : TitleOutcome
}

/**
 * One file's result: its name, the [title] written into both the segment title and the video track
 * name, and what mkvpropedit did. The title is part of the document even though no text rendering
 * prints it — it is what the command computed.
 */
data class FileTitled(val fileName: String, val title: String, val outcome: TitleOutcome) : CommandResult

/** The run root: the children verbatim, plus the parent's own projection (the counts). */
data class TitleRun(val files: List<FileTitled>, val total: Int, val failed: Int) : CommandResult

/**
 * The title for a file: its name with the last extension stripped, unchanged otherwise — no cleanup,
 * no dot-to-space, no trimming (v1 `FilenameUtils.getBaseName`).
 */
fun titleFromFileName(file: File): String = file.nameWithoutExtension

/**
 * Build the mkvpropedit command line that stamps [file]'s own name into it, as v1's ten-element argv:
 * `[exe, "<base>.mkv", "--edit", "info", "--set", "title=<base>", "--edit", "track:v1", "--set", "name=<base>"]`.
 *
 * The file name and the title come from the same expression, which is what v1's shared `${-> fileName}`
 * lazy achieved; the `<base>.mkv` reconstruction (and its lower-cased-extension quirk) is
 * [buildPropeditCommand]'s, so it lives in one place for both mkvpropedit commands.
 *
 * Nothing is quoted here: each element reaches the process as exactly one argument, which is why an
 * embedded `"` in a file name ends up in the title verbatim rather than as a literal quote character.
 */
fun buildTitleCommand(exe: String, file: File): List<String> {
    val title = titleFromFileName(file)
    return buildPropeditCommand(
        exe,
        file,
        listOf("--edit", "info", "--set", "title=$title", "--edit", "track:v1", "--set", "name=$title"),
    )
}

/**
 * Retitle every `.mkv` in [dir] from its file name, running mkvpropedit ([exe]) once per file. Each file
 * gets a `*** Processing` header (narration); mkvpropedit's own stdout/stderr are inherited so its
 * progress reaches the console unchanged (v1 `consumeProcessOutput(System.out, System.err)`). A non-zero
 * child exit is a per-file [TitleOutcome.Failed] and increments the failed count; the batch never aborts.
 * The caller derives the exit code from `root.failed` (1 on any failure, else 0).
 *
 * The title itself is never printed — v1 prints only the header — so it travels in [FileTitled] alone.
 *
 * Files are selected and name-sorted by the same rule as [propeditDirectory]: `.mkv` compared
 * case-insensitively, sorted for a deterministic order where v1 relied on the platform's undefined
 * `listFiles` order. [dir] is passed in explicitly so the orchestrator is exercisable in-process.
 */
fun retitleDirectory(dir: File, exe: String, renderer: Renderer): TitleRun {
    val files = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() == "mkv" }
        .sortedBy { it.name }

    var failed = 0
    val results = ArrayList<FileTitled>(files.size)

    for (file in files) {
        renderer.render(Header("*** Processing ${file.name}"))
        renderer.render(Notice(""))

        val code = ProcessBuilder(buildTitleCommand(exe, file))
            .inheritIO()
            .start()
            .waitFor()

        val outcome = if (code == 0) {
            TitleOutcome.Succeeded
        } else {
            failed++
            TitleOutcome.Failed(code)
        }

        val result = FileTitled(file.name, titleFromFileName(file), outcome)
        results.add(result)
        renderer.render(result)
    }

    val root = TitleRun(results, files.size, failed)
    renderer.render(root)
    return root
}
