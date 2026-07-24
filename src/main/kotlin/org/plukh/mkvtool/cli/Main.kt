package org.plukh.mkvtool.cli

import picocli.CommandLine
import kotlin.system.exitProcess

/**
 * Entry point. Compiled to `org.plukh.mkvtool.cli.MainKt`, which is the [application]
 * mainClass in build.gradle.kts.
 */
fun main(args: Array<String>) {
    exitProcess(CommandLine(MkvtoolCommand()).execute(*args))
}
