package org.plukh.mkvtool.out

import java.io.PrintStream
import kotlin.math.ceil

/**
 * A [Renderer] that produces plain text for any destination — an interactive terminal, a pipe, or a
 * file. (The machine-readable counterpart is a separate JSON renderer over the same events.)
 *
 * The palette has one meaning per color (red 31 errors, green 32 success, yellow 33 warnings, cyan 36
 * headers, gray 90 de-emphasis); color wraps a whole line so the reset lands at the end and any
 * substring of the uncolored text stays contiguous — assertions and search over piped output keep
 * working, and cell padding done before coloring never counts toward a column width.
 *
 * Everything the tests need to steer is injected: the two streams, the terminal probe, and the
 * `NO_COLOR` reading. Defaults wire the real environment.
 */
class TextRenderer(
    colorMode: ColorMode,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val isTerminal: () -> Boolean = ::probeTerminal,
    noColor: Boolean = !System.getenv("NO_COLOR").isNullOrEmpty(),
) : Renderer {

    // Computed once: --color always wins outright; auto colors only on a real terminal with NO_COLOR
    // unset. An explicit flag is a direct request; the environment variable is a mere preference.
    private val ansi = Ansi(
        enabled = colorMode == ColorMode.ALWAYS ||
            (colorMode == ColorMode.AUTO && isTerminal() && !noColor),
    )

    override fun render(event: OutputEvent) {
        when (event) {
            is Header -> out.println(ansi.cyan(event.text))
            is Success -> out.println(ansi.green(event.text))
            is Error -> err.println(ansi.red("*** Error: ${event.text}"))
            is Warning -> err.println(ansi.yellow("*** Warning: ${event.text}"))
        }
    }

    override fun progress(label: String, total: Int, interactive: Boolean?): ProgressHandle {
        val active = interactive ?: isTerminal()
        val width = 24
        // Dots are emitted per slice of the total, not per item, so a 200-file batch cannot wrap the
        // terminal with 200 of them.
        val perDot = maxOf(1, ceil(total / 40.0).toInt())

        // Non-interactive prints the label up front; the interactive bar carries its own label per frame.
        if (!active) {
            out.print(label)
            out.flush()
        }

        return object : ProgressHandle {
            private var count = 0
            private var shown = -1

            override fun tick() {
                count++
                val pct = if (total > 0) minOf(100, (count * 100L / total).toInt()) else 100
                if (active) {
                    if (pct == shown) return
                    shown = pct
                    val filled = pct * width / 100
                    out.print("\r$label  [${"#".repeat(filled)}${"-".repeat(width - filled)}] $pct%")
                } else {
                    // \r would not erase in a pipe/file, so every frame would be retained; append dots.
                    if (count % perDot != 0 && count != total) return
                    out.print(".")
                }
                out.flush()
            }

            override fun finish() {
                // The completed bar stays on screen: it is the record of how long the wait was for.
                // If nothing ticked, still print the label so the pause is explained.
                if (active && shown < 0) out.print(label)
                out.println()
            }
        }
    }
}

/**
 * The ANSI palette. Color wraps a whole line or a whole pre-padded table cell — never a fragment —
 * so escapes never split a substring an assertion pins, and cell padding done before coloring never
 * counts toward a column width. The ESC byte is built with `Char(27)` rather than a literal escape,
 * so no control character ever sits in the source.
 */
private class Ansi(private val enabled: Boolean) {

    private val esc = Char(27).toString()
    private val reset = "$esc[0m"

    private fun paint(code: String, s: String): String = if (enabled) "$esc[${code}m$s$reset" else s

    fun red(s: String): String = paint("31", s)

    fun green(s: String): String = paint("32", s)

    fun yellow(s: String): String = paint("33", s)

    fun cyan(s: String): String = paint("36", s)

    // Gray de-emphasis (e.g. guessed values, secondary evidence lists). Not yet called: it is here so
    // the consistency-check report can color whole cells through this same helper when it lands.
    @Suppress("unused")
    fun gray(s: String): String = paint("90", s)
}

/**
 * The default terminal probe: a console exists and is a terminal. `Console.isTerminal()` is JDK 22+,
 * where `System.console()` returns non-null even when redirected; called reflectively so the JDK 21
 * target keeps the plain null-check (console is null there whenever a stream is redirected, including
 * under the test harness) while a shadow-jar run on JDK 22+ still behaves correctly.
 */
private fun probeTerminal(): Boolean {
    val console = System.console() ?: return false
    return try {
        val isTerminal = console.javaClass.getMethod("isTerminal")
        isTerminal.invoke(console) as Boolean
    } catch (_: NoSuchMethodException) {
        true
    }
}
