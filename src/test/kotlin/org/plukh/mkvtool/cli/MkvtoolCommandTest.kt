package org.plukh.mkvtool.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.plukh.mkvtool.BuildInfo
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Smoke test for the build/test wiring (Kotest on JUnit platform, generated BuildInfo,
 * picocli driven in-process). Real per-command tests arrive with each ported command.
 */
class MkvtoolCommandTest : StringSpec({

    "BuildInfo version is populated from the build" {
        BuildInfo.VERSION.shouldNotBeBlank()
    }

    "--version prints the build version" {
        val out = StringWriter()
        val exit = CommandLine(MkvtoolCommand())
            .setOut(PrintWriter(out))
            .execute("--version")

        exit shouldBe 0
        out.toString() shouldContain "mkvtool"
        out.toString() shouldContain BuildInfo.VERSION
    }
})
