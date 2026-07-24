package org.plukh.mkvtool.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

/**
 * Root command. User-facing subcommands are added as they are built; alongside the hidden
 * `native-smoke` build probe, `to-utf8`, `fix-srt`, `propedit`, `filename-to-title`, and
 * `find-unused-fonts` are the ported leaf commands. `mixinStandardHelpOptions` supplies `--help` and
 * `--version`.
 */
@Command(
    name = "mkvtool",
    mixinStandardHelpOptions = true,
    versionProvider = MkvtoolVersionProvider::class,
    description = ["MKV muxing toolkit."],
    subcommands = [
        NativeSmokeCommand::class,
        ToUtf8Command::class,
        FixSrtCommand::class,
        PropeditCommand::class,
        FilenameToTitleCommand::class,
        FindUnusedFontsCommand::class,
    ],
)
class MkvtoolCommand : Callable<Int> {

    override fun call(): Int {
        // Invoked with no subcommand: print usage and exit cleanly.
        CommandLine.usage(this, System.out)
        return 0
    }
}
