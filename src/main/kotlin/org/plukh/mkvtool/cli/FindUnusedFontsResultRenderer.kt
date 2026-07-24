package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.FontUsageReport
import org.plukh.mkvtool.out.ResultTextRenderer

/**
 * Composes the text for `find-unused-fonts` — one bare font base name per line, to stdout, with no
 * prefix and no summary, exactly as v1 printed them. Injected into `TextRenderer`; command logic never
 * composes this prose.
 */
val FindUnusedFontsResultRenderer = ResultTextRenderer { result, s ->
    when (result) {
        is FontUsageReport -> result.unusedFonts.forEach { s.out.println(it) }
        else -> error("FindUnusedFontsResultRenderer cannot render ${result::class.simpleName}")
    }
}
