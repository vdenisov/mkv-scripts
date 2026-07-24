package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.FileTitled
import org.plukh.mkvtool.core.TitleOutcome
import org.plukh.mkvtool.core.TitleRun
import org.plukh.mkvtool.out.ResultTextRenderer

/**
 * Composes the text for `filename-to-title`'s results — the per-file failure line and the counts
 * summary. Injected into `TextRenderer`; command logic never composes this prose. Writes through the
 * shared [org.plukh.mkvtool.out.TextStyle], so it obeys the same palette and routing as the diagnostics
 * channel.
 *
 * Coloring is verbatim to v1: a successful file prints nothing here (only the `*** Processing` header,
 * a diagnostics event); a failure is a red `*** Error:` line on stderr, preceded by a blank stdout line;
 * the summary reads `*** <succeeded> processed, <failed> failed` — the first count is `total - failed`,
 * exactly as v1 computed it — green when clean and red on any failure.
 *
 * [FileTitled.title] is deliberately not rendered: v1 prints no title line, and the field is there so
 * the result document is complete for a machine-readable renderer, not for the text one. Do not "fix"
 * this.
 */
val FilenameToTitleResultRenderer = ResultTextRenderer { result, s ->
    when (result) {
        is FileTitled -> when (val o = result.outcome) {
            TitleOutcome.Succeeded -> {
                // A processed file prints only its header (a diagnostics event); nothing more here.
            }
            is TitleOutcome.Failed -> {
                s.out.println()
                s.err.println(s.red("*** Error: mkvpropedit exited with code ${o.exitCode}"))
            }
        }

        is TitleRun -> {
            s.out.println()
            val text = "*** ${result.total - result.failed} processed, ${result.failed} failed"
            s.out.println(if (result.failed > 0) s.red(text) else s.green(text))
        }

        else -> error("FilenameToTitleResultRenderer cannot render ${result::class.simpleName}")
    }
}
