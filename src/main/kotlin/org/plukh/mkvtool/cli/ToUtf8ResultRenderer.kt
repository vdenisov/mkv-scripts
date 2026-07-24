package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.ConversionRun
import org.plukh.mkvtool.core.FileConversion
import org.plukh.mkvtool.core.FileOutcome
import org.plukh.mkvtool.out.ResultTextRenderer

/**
 * Composes the text for `to-utf8`'s results — the per-file status lines and the counts summary. This is
 * the seam's first result renderer: command logic never composes this prose; it lives here and is
 * injected into `TextRenderer`. Writes through the shared [org.plukh.mkvtool.out.TextStyle], so it obeys
 * the same palette and routing as the diagnostics channel.
 *
 * Coloring is deliberate and verbatim to v1: the per-file skip/convert lines and the invalid-source
 * hint are bare (uncolored); only the invalid-source `*** Error:` line (red) and the summary (green when
 * clean, red on any failure) carry color. The summary is omitted for an empty directory (no files), so
 * neither the blank nor the summary print — v1 printed no summary block in that case.
 */
val ToUtf8ResultRenderer = ResultTextRenderer { result, s ->
    when (result) {
        is FileConversion -> when (val o = result.outcome) {
            FileOutcome.Utf16Bom ->
                s.out.println("*** ${result.fileName}: looks like UTF-16 (BOM), leaving it alone")
            FileOutcome.Utf8Bom ->
                s.out.println("*** ${result.fileName}: already UTF-8 (BOM), skipping")
            FileOutcome.Utf8Clean ->
                s.out.println("*** ${result.fileName}: already valid UTF-8, skipping")
            is FileOutcome.WouldConvert ->
                s.out.println("*** ${result.fileName}: would convert from ${o.charsetName} to UTF-8")
            is FileOutcome.Converted -> {
                if (o.backupName != null) s.out.println("*** ${result.fileName}: backed up as ${o.backupName}")
                s.out.println("*** ${result.fileName}: converted from ${o.charsetName} to UTF-8")
            }
            is FileOutcome.NotValidSource -> {
                s.err.println(s.red("*** Error: ${result.fileName}: not valid ${o.charsetName} (${o.exceptionName}), leaving it alone"))
                s.err.println("      Pass the right --encoding; converting anyway would produce mojibake.")
            }
        }

        is ConversionRun -> if (result.files.isNotEmpty()) {
            s.out.println()
            val text = "*** ${result.converted} converted, ${result.skipped} skipped, ${result.failed} failed"
            s.out.println(if (result.failed > 0) s.red(text) else s.green(text))
        }

        else -> error("ToUtf8Results cannot render ${result::class.simpleName}")
    }
}
