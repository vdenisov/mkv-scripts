package org.plukh.mkvtool.out

/**
 * Marker for a typed result routed through the seam — what a command *computes*, as opposed to what it
 * *says while working* (an [OutputEvent]). A result is a dumb data shape: no logic, no self-presentation.
 * Field names describe domain meaning, never rendering.
 *
 * Results are emitted as they complete (via [Renderer.render]) and the root is also the core function's
 * plain return value, so unit tests read the returned model rather than capturing emissions. The text
 * for a result is composed by a command-specific [ResultTextRenderer]; this package never names a
 * concrete result type, which keeps the layering acyclic (`out` ← `core` ← `cli`).
 */
interface CommandResult
