package org.plukh.mkvtool.cli

import picocli.CommandLine
import kotlin.system.exitProcess

/**
 * Entry point. Compiled to `org.plukh.mkvtool.cli.MainKt`, which is the [application]
 * mainClass in build.gradle.kts.
 */
fun main(args: Array<String>) {
    exitProcess(mkvtoolCommandLine().execute(*args))
}

/**
 * Builds the root [CommandLine] with the per-subcommand parser configuration applied. This is the single
 * place that config lives, so tests exercise the shipping wiring rather than a replica.
 *
 * `propedit` forwards every argument to `mkvpropedit` verbatim; `unmatchedOptionsArePositionalParams`
 * makes picocli treat option-like tokens (`--edit`, `--set`, `-h`) as positional parameters, so they
 * reach the command's catch-all `@Parameters` list instead of erroring as unknown options.
 */
fun mkvtoolCommandLine(): CommandLine {
    val cmd = CommandLine(MkvtoolCommand())
    cmd.subcommands["propedit"]?.isUnmatchedOptionsArePositionalParams = true
    return cmd
}
