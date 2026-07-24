package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.fixDirectory
import org.plukh.mkvtool.out.TextRenderer
import org.plukh.mkvtool.out.colorModeOf
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable

/**
 * `mkvtool fix-srt` — reformat legacy-format `.srt` files in the current directory into `<name>.srt.fixed`.
 * A verbatim port of `src/fix_srt.groovy`. Thin by design: it wires the renderer (diagnostics +
 * [FixSrtResultRenderer]) and delegates to [fixDirectory], returning 1 if any file failed, else 0. Exit
 * codes flow through picocli's `Callable<Int>` return; there is no input-validation (exit-2) path here.
 */
@Command(
    name = "fix-srt",
    mixinStandardHelpOptions = true,
    description = ["Reformat legacy-format SRT files in the current directory into <name>.srt.fixed."],
)
class FixSrtCommand : Callable<Int> {

    @Option(
        names = ["--color"],
        paramLabel = "WHEN",
        description = ["Colorize output: auto (default, only on a terminal and not under NO_COLOR), always, or never"],
    )
    var color: String = "auto"

    override fun call(): Int {
        val renderer = TextRenderer(colorModeOf(color), results = FixSrtResultRenderer)
        val run = fixDirectory(File("."), renderer)
        // Non-zero on failure so this is usable from a shell script (matching v1's exit discipline).
        return if (run.failed > 0) 1 else 0
    }
}
