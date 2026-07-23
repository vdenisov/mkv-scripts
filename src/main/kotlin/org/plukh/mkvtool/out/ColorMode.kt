package org.plukh.mkvtool.out

/**
 * Whether a [Renderer] emits ANSI color, as selected by the `--color` option.
 *
 * - [ALWAYS] — color unconditionally.
 * - [AUTO] — color only on a real terminal with `NO_COLOR` unset (the renderer decides).
 * - [NEVER] — never color.
 */
enum class ColorMode {
    ALWAYS,
    AUTO,
    NEVER,
}

/**
 * Parses a `--color` value into a [ColorMode]. Only the exact strings `always` and `auto` are
 * recognized; anything else — including `never`, an unknown word, or `null` — is [NEVER], so an
 * unrecognized value fails safe to no color rather than erroring.
 */
fun colorModeOf(value: String?): ColorMode = when (value) {
    "always" -> ColorMode.ALWAYS
    "auto" -> ColorMode.AUTO
    else -> ColorMode.NEVER
}
