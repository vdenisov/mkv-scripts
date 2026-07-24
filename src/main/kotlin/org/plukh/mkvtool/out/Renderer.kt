package org.plukh.mkvtool.out

/**
 * Turns [OutputEvent]s into output. [TextRenderer] renders them as text; a machine-readable (JSON)
 * renderer can implement the same interface over the same events.
 */
interface Renderer {

    /** Renders one event to its destination (stdout/stderr, colored per the renderer's policy). */
    fun render(event: OutputEvent)

    /**
     * Starts a progress meter for a run of [total] items, returning a handle to drive it. Progress
     * is stateful and streamed over time, so it is a handle rather than a discrete event.
     *
     * [interactive] overrides the renderer's terminal probe: `true` forces the in-place bar, `false`
     * the appended-dots form, `null` (the default) lets the renderer decide. The override exists so
     * both renderings are testable through a pipe, where the probe is always false.
     */
    fun progress(label: String, total: Int, interactive: Boolean? = null): ProgressHandle
}

/** Drives a progress meter started by [Renderer.progress]: [tick] once per item, [finish] at the end. */
interface ProgressHandle {
    fun tick()
    fun finish()
}

/**
 * "3 files" / "1 file" — a count with its correctly-pluralized noun. Every count these tools print
 * knows its own number, so there is never a reason to fall back on "file(s)". Pass [plural] for a
 * noun that does not simply take an `-s`: `plural(n, "discrepancy", "discrepancies")`.
 */
fun plural(n: Int, noun: String, plural: String? = null): String = "$n ${pluralize(n, noun, plural)}"

/**
 * The correctly-pluralized noun alone, for a sentence that has already printed the count or needs the
 * word elsewhere ("2 files use a different layout"). See [plural] for the [plural] override.
 */
fun pluralize(n: Int, noun: String, plural: String? = null): String =
    if (n == 1) noun else (plural ?: (noun + "s"))
