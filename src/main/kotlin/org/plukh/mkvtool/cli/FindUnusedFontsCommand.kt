package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.findUnusedFonts
import org.plukh.mkvtool.out.TextRenderer
import org.plukh.mkvtool.out.colorModeOf
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable

/**
 * `mkvtool find-unused-fonts` — list fonts in `fonts/` that no `.ass` subtitle in the current directory
 * references. A port of `src/find_unused_fonts.groovy`. Thin by design: wires the renderer and delegates
 * to [findUnusedFonts]. Always exits 0 — this is a reporter, not a batch operation with a failure count.
 */
@Command(
    name = "find-unused-fonts",
    mixinStandardHelpOptions = true,
    description = ["List fonts in fonts/ not referenced by any .ass subtitle in the current directory."],
)
class FindUnusedFontsCommand : Callable<Int> {

    @Option(
        names = ["--color"],
        paramLabel = "WHEN",
        description = ["Colorize output: auto (default, only on a terminal and not under NO_COLOR), always, or never"],
    )
    var color: String = "auto"

    override fun call(): Int {
        val renderer = TextRenderer(colorModeOf(color), results = FindUnusedFontsResultRenderer)
        findUnusedFonts(File("."), renderer)
        return 0
    }
}
