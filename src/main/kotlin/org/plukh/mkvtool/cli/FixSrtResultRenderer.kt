package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.FileFix
import org.plukh.mkvtool.core.FixOutcome
import org.plukh.mkvtool.core.FixRun
import org.plukh.mkvtool.out.ResultTextRenderer

/**
 * Composes the text for `fix-srt`'s results — the per-file failure line and the counts summary. Injected
 * into `TextRenderer`; command logic never composes this prose. Writes through the shared
 * [org.plukh.mkvtool.out.TextStyle], so it obeys the same palette and routing as the diagnostics channel.
 *
 * Coloring is verbatim to v1: a successful file prints nothing here (only the `*** Fixing` header, which is
 * a diagnostics event); a failure is a red `*** Error:` line on stderr; the summary is green when clean and
 * red on any failure. The summary is omitted for an empty directory — v1 printed no summary block there.
 */
val FixSrtResultRenderer = ResultTextRenderer { result, s ->
    when (result) {
        is FileFix -> when (val o = result.outcome) {
            FixOutcome.Fixed -> {
                // A fixed file prints only its header (a diagnostics event); nothing more here.
            }
            is FixOutcome.Failed ->
                s.err.println(s.red("*** Error: ${result.fileName}: ${o.message} (left unfixed)"))
        }

        is FixRun -> if (result.files.isNotEmpty()) {
            s.out.println()
            val text = "*** ${result.fixed} fixed, ${result.failed} failed"
            s.out.println(if (result.failed > 0) s.red(text) else s.green(text))
        }

        else -> error("FixSrtResults cannot render ${result::class.simpleName}")
    }
}
