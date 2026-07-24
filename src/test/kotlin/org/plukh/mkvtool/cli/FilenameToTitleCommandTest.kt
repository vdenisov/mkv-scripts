package org.plukh.mkvtool.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import picocli.CommandLine

/**
 * Parses through the real shipping wiring ([mkvtoolCommandLine]) — no mkvpropedit involved. Pins the
 * two deliberate differences from v1, which parsed nothing at all: `--color` exists and defaults to
 * `auto` (the v1 behavior), and a stray argument is a usage error rather than being silently ignored.
 */
class FilenameToTitleCommandTest : FunSpec({

    fun parse(vararg args: String): FilenameToTitleCommand {
        val cmd = mkvtoolCommandLine()
        cmd.parseArgs(*args)
        return cmd.subcommands["filename-to-title"]!!.getCommand()
    }

    test("the subcommand is registered and colors automatically by default") {
        parse("filename-to-title").color shouldBe "auto"
    }

    test("--color binds") {
        parse("filename-to-title", "--color", "never").color shouldBe "never"
    }

    test("a stray argument is a usage error (v1 ignored anything passed)") {
        shouldThrow<CommandLine.UnmatchedArgumentException> { parse("filename-to-title", "stray") }
    }
})
