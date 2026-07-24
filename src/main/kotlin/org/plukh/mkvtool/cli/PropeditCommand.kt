package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.MkvToolNotFoundException
import org.plukh.mkvtool.core.findMkvTool
import org.plukh.mkvtool.core.propeditDirectory
import org.plukh.mkvtool.out.ColorMode
import org.plukh.mkvtool.out.Error
import org.plukh.mkvtool.out.Notice
import org.plukh.mkvtool.out.TextRenderer
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import java.io.File
import java.util.concurrent.Callable

/**
 * `mkvtool propedit` — run `mkvpropedit` with the given options against every `.mkv` in the current
 * directory. A verbatim port of `src/propedit.groovy`.
 *
 * Every argument is forwarded to mkvpropedit untouched, so this command declares **no options of its
 * own** (not even `--color`, which would be ambiguous against a pass-through flag): it takes a single
 * catch-all [passthrough] list. picocli would normally reject an unknown token like `--edit`, so the
 * `propedit` subcommand is configured in [mkvtoolCommandLine] with
 * `unmatchedOptionsArePositionalParams` — that reclassifies option-like tokens as positional
 * parameters, which is how they reach [passthrough] verbatim and in order. Color therefore comes from
 * auto-detection only (v1 used `'auto'`).
 *
 * `-h`/`--help` is intercepted here only when it is the **sole** argument (they arrive as positionals,
 * since this command has no help option); in any other combination they are passed through to
 * mkvpropedit. Exit codes: 2 when mkvpropedit is not found (before touching anything), 1 if any file
 * failed, else 0.
 */
@Command(
    name = "propedit",
    description = ["Run mkvpropedit with the given options against every .mkv in the current directory."],
)
class PropeditCommand : Callable<Int> {

    @Parameters(
        paramLabel = "ARGS",
        arity = "0..*",
        description = ["mkvpropedit options, passed through verbatim; the file name is inserted first."],
    )
    var passthrough: MutableList<String> = mutableListOf()

    override fun call(): Int {
        val renderer = TextRenderer(ColorMode.AUTO, results = PropeditResultRenderer)

        // Help is intercepted only as the sole argument, so every other combination reaches mkvpropedit.
        if (passthrough.isEmpty() || (passthrough.size == 1 && passthrough[0] in listOf("-h", "--help"))) {
            renderer.render(Notice(USAGE))
            return 0
        }

        val exe = try {
            findMkvTool("mkvpropedit")
        } catch (e: MkvToolNotFoundException) {
            renderer.render(Error(e.message!!))
            return 2
        }

        val run = propeditDirectory(File("."), exe, passthrough, renderer)
        // Non-zero on failure so this is usable from a shell script (matching v1's exit discipline).
        return if (run.failed > 0) 1 else 0
    }
}

/** The v1 usage banner, adapted to the `mkvtool propedit` invocation. Printed on no args or a sole `-h`/`--help`. */
private val USAGE = """
    Usage: mkvtool propedit <mkvpropedit options...>

    Runs mkvpropedit with the given options against every .mkv file in the current
    directory. All arguments are passed through verbatim; the file name is inserted
    as the first argument.

    Examples:
      mkvtool propedit --edit track:a2 --set flag-forced=0
      mkvtool propedit --edit track:s1 --set flag-default=1
      mkvtool propedit --edit info --set title="My Show"
      mkvtool propedit --add-track-statistics-tags

    Run 'mkvpropedit --help' for the full option list.

    Note: -h/--help is handled here only when it is the sole argument; in any other
    combination it is passed through to mkvpropedit.
""".trimIndent()
