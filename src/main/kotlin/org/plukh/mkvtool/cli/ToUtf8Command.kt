package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.convertDirectory
import org.plukh.mkvtool.out.Error
import org.plukh.mkvtool.out.TextRenderer
import org.plukh.mkvtool.out.colorModeOf
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException
import java.util.concurrent.Callable

/**
 * `mkvtool to-utf8` — convert `.srt/.ass/.ssa/.vtt` files in the current directory to UTF-8 in place.
 * A verbatim port of `src/to_utf8.groovy`. Thin by design: it resolves the charset, wires the renderer
 * (diagnostics + [ToUtf8ResultRenderer]), and delegates to [convertDirectory], deriving the exit code from the
 * returned model — 2 on an unusable charset name (before any file is touched), 1 if any file failed,
 * else 0. Exit codes flow through picocli's `Callable<Int>` return.
 */
@Command(
    name = "to-utf8",
    mixinStandardHelpOptions = true,
    description = ["Convert subtitle files in the current directory to UTF-8, in place."],
)
class ToUtf8Command : Callable<Int> {

    @Option(
        names = ["-e", "--encoding"],
        paramLabel = "CHARSET",
        description = ["Source encoding to decode from (default: \${DEFAULT-VALUE})"],
    )
    var encoding: String = "windows-1251"

    @Option(names = ["-b", "--backup"], description = ["Keep the original as <name>.orig before overwriting it"])
    var backup: Boolean = false

    @Option(names = ["-n", "--dry-run"], description = ["Report what would be converted without writing anything"])
    var dryRun: Boolean = false

    @Option(
        names = ["--color"],
        paramLabel = "WHEN",
        description = ["Colorize output: auto (default, only on a terminal and not under NO_COLOR), always, or never"],
    )
    var color: String = "auto"

    override fun call(): Int {
        val renderer = TextRenderer(colorModeOf(color), results = ToUtf8ResultRenderer)

        // Fail on an unusable charset name before touching any file, not partway through a batch.
        val charset: Charset = try {
            Charset.forName(encoding)
        } catch (e: UnsupportedCharsetException) {
            return unknownEncoding(renderer, e)
        } catch (e: IllegalCharsetNameException) {
            return unknownEncoding(renderer, e)
        }

        val run = convertDirectory(File("."), charset, backup, dryRun, renderer)
        // Non-zero on failure so this is usable from a shell script (matching propedit, not mux).
        return if (run.failed > 0) 1 else 0
    }

    private fun unknownEncoding(renderer: TextRenderer, e: RuntimeException): Int {
        renderer.render(
            Error(
                "Unknown source encoding '$encoding': ${e.javaClass.simpleName}",
                hint = "Use a charset name your JVM knows, e.g. windows-1251, windows-1250, Shift_JIS, Big5.",
            ),
        )
        return 2
    }
}
