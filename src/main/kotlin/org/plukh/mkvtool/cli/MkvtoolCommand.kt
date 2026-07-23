package org.plukh.mkvtool.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

/**
 * Root command. No subcommands are registered yet (they arrive per phase of the
 * port); `mixinStandardHelpOptions` supplies `--help` and `--version`.
 */
@Command(
    name = "mkvtool",
    mixinStandardHelpOptions = true,
    versionProvider = MkvtoolVersionProvider::class,
    description = ["MKV muxing toolkit."],
)
class MkvtoolCommand : Callable<Int> {

    override fun call(): Int {
        // Invoked with no subcommand: print usage and exit cleanly.
        CommandLine.usage(this, System.out)
        return 0
    }
}
