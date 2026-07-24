package org.plukh.mkvtool.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

/**
 * The headline proof that verbatim passthrough survives picocli. `propedit` declares no options, so the
 * only thing keeping `--edit`/`--set`/`-h` from erroring as unknown options is the
 * `unmatchedOptionsArePositionalParams` config in [mkvtoolCommandLine]. This parses through the real
 * shipping wiring and reads back the captured tokens — without launching mkvpropedit.
 */
class PropeditPassthroughTest : FunSpec({

    fun parsePassthrough(vararg args: String): List<String> {
        val cmd = mkvtoolCommandLine()
        cmd.parseArgs(*args)
        val propedit: PropeditCommand = cmd.subcommands["propedit"]!!.getCommand()
        return propedit.passthrough
    }

    test("option-like tokens are captured verbatim and in order") {
        parsePassthrough("propedit", "--edit", "info", "--set", "title=SmokeTest") shouldContainExactly
            listOf("--edit", "info", "--set", "title=SmokeTest")
    }

    test("no arguments captures nothing") {
        parsePassthrough("propedit").shouldBeEmpty()
    }

    test("a sole -h is captured (the command decides to show usage, not picocli)") {
        parsePassthrough("propedit", "-h") shouldContainExactly listOf("-h")
    }

    test("--help alongside other args is passed through, not intercepted") {
        parsePassthrough("propedit", "--help", "--edit", "x") shouldContainExactly
            listOf("--help", "--edit", "x")
    }
})
