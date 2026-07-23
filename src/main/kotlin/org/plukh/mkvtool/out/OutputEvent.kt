package org.plukh.mkvtool.out

/**
 * A single unit of output, produced by command logic and consumed by a [Renderer].
 *
 * Command logic emits these events and never writes to a stream or colors anything itself; a
 * [Renderer] owns all presentation. That separation is what lets a text renderer and a
 * machine-readable (JSON) renderer share the same command logic unchanged.
 *
 * Naming rule for this hierarchy and every event added later: fields describe *what a thing means*
 * (e.g. a differing value, a guessed language), never *how it renders* (no "grayed", "highlighted").
 * The renderer maps meaning to presentation (routing, color, padding), so one visual treatment can
 * serve several distinct meanings without the model conflating them.
 */
sealed interface OutputEvent

/** A section or per-file header ("*** Processing X"). Rendered cyan to stdout. */
data class Header(val text: String) : OutputEvent

/** A terminal success line ("*** Done", a clean summary). Rendered green to stdout. */
data class Success(val text: String) : OutputEvent

/**
 * An error. Rendered red to **stderr**; the renderer applies the shared `*** Error: ` prefix, so
 * [text] carries the message only. This shadows the star-imported `kotlin.Error` within this package;
 * reference `java.lang.Error` by its full name in the rare case one is needed here.
 */
data class Error(val text: String) : OutputEvent

/**
 * A warning. Rendered yellow to **stderr**; the renderer applies the shared `*** Warning: ` prefix,
 * so [text] carries the message only.
 */
data class Warning(val text: String) : OutputEvent
