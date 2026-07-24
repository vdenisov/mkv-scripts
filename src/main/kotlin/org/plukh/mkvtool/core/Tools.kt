package org.plukh.mkvtool.core

import java.io.File

/**
 * Locating the MKVToolNix executables. A verbatim port of `src/lib/tools.groovy`.
 *
 * This is pure resolution logic — no presentation — so it lives in `core`; the caller catches
 * [MkvToolNotFoundException] and decides what to say and which exit code to use. `propedit` is the
 * first consumer; `filename-to-title` reuses it.
 */

/**
 * Raised by [findMkvTool] when a tool resolves neither on `PATH` nor at the Windows default install
 * location. The message is verbatim v1 text — the caller renders it (with the shared `*** Error: `
 * prefix) and exits 2.
 */
class MkvToolNotFoundException(val toolName: String) :
    RuntimeException("'$toolName' not found on PATH or in default install location. Install MKVToolNix.")

/**
 * Resolve an MKVToolNix executable [name] (e.g. `mkvpropedit`): try `PATH` first by probing
 * `<name> --version`, then — on Windows only — the default install location
 * `C:\Program Files\MKVToolNix\<name>.exe`. Returns the bare name on a `PATH` hit (so the OS resolves
 * it at run time) or the full path on the fallback. Throws [MkvToolNotFoundException] when neither works.
 *
 * The probe's own output is discarded (v1 never printed it), and any launch failure — the executable
 * not existing at all — is swallowed so the Windows fallback still gets its turn, matching v1's empty
 * `catch`.
 */
fun findMkvTool(name: String): String {
    try {
        val probe = ProcessBuilder(name, "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        probe.waitFor()
        if (probe.exitValue() == 0) return name
    } catch (_: Exception) {
        // The executable is not on PATH; fall through to the Windows default location.
    }

    if (System.getProperty("os.name").lowercase().contains("win")) {
        val path = "C:\\Program Files\\MKVToolNix\\$name.exe"
        if (File(path).exists()) return path
    }

    throw MkvToolNotFoundException(name)
}
