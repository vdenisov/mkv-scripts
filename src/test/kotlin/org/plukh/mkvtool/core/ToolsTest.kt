package org.plukh.mkvtool.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * `findMkvTool`'s not-found path, which is deterministic on every OS: a clearly-bogus name is neither on
 * PATH nor at the Windows default location, so it throws with the exact v1 message. The positive path
 * (a real tool resolves) needs a real MKVToolNix install and is covered by the Groovy harness.
 */
class ToolsTest : FunSpec({

    test("an unresolvable tool throws with the verbatim v1 message") {
        val e = shouldThrow<MkvToolNotFoundException> {
            findMkvTool("mkvtool-definitely-not-a-real-executable-xyz")
        }
        e.message shouldBe
            "'mkvtool-definitely-not-a-real-executable-xyz' not found on PATH or in default install location. Install MKVToolNix."
    }
})
