package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.OutputEvent
import org.plukh.mkvtool.out.ProgressHandle
import org.plukh.mkvtool.out.Renderer
import java.io.File

/**
 * The orchestrator, tested in-process: call `convertDirectory` with a no-op renderer and assert on the
 * returned [ConversionRun] and on-disk side effects. Per the seam, unit tests read the model — no
 * recording renderer, no emission-order assertions (that is the renderer tier's job).
 */
class ConvertDirectoryTest : FunSpec({

    val cp1251 = charset("windows-1251")

    fun run(dir: File, backup: Boolean = false, dryRun: Boolean = false) =
        convertDirectory(dir, cp1251, backup, dryRun, NoopRenderer)

    test("converts in place and preserves CRLF") {
        val dir = tempdir()
        val lf = write(dir, "lf.srt", "Привет, мир!\n".toByteArray(cp1251))
        val crlf = write(dir, "crlf.srt", "Привет\r\nмир\r\n".toByteArray(cp1251))

        val result = run(dir)

        result.converted shouldBe 2
        result.skipped shouldBe 0
        result.failed shouldBe 0
        result.files shouldHaveSize 2
        result.files.forEach { it.outcome.shouldBeInstanceOf<FileOutcome.Converted>() }
        lf.readText(Charsets.UTF_8) shouldBe "Привет, мир!\n"
        crlf.readText(Charsets.UTF_8) shouldBe "Привет\r\nмир\r\n"
    }

    test("skips files that are already UTF-8, byte-for-byte") {
        val dir = tempdir()
        val plain = write(dir, "plain.srt", "Привет\n".toByteArray(Charsets.UTF_8))
        val bom = write(dir, "bom.ass", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Привет\n".toByteArray(Charsets.UTF_8))
        val ascii = write(dir, "ascii.vtt", "WEBVTT\n\nplain\n".toByteArray(Charsets.US_ASCII))
        val before = listOf(plain, bom, ascii).map { it.readBytes().toList() }

        val result = run(dir)

        result.skipped shouldBe 3
        result.converted shouldBe 0
        result.files.map { it.outcome }.shouldBe(
            listOf(FileOutcome.Utf8Clean, FileOutcome.Utf8Bom, FileOutcome.Utf8Clean),
        )
        listOf(plain, bom, ascii).forEachIndexed { i, f -> f.readBytes().toList() shouldBe before[i] }
    }

    test("refuses invalid source bytes and leaves the file untouched") {
        val dir = tempdir()
        val bad = write(dir, "bad.srt", byteArrayOf(0x81.toByte(), 0x20, 0x41))
        val before = bad.readBytes().toList()

        val result = convertDirectory(dir, charset("Shift_JIS"), backup = false, dryRun = false, NoopRenderer)

        result.failed shouldBe 1
        val outcome = result.files.single().outcome
        outcome.shouldBeInstanceOf<FileOutcome.NotValidSource>()
        outcome.charsetName shouldBe "Shift_JIS"
        bad.readBytes().toList() shouldBe before
    }

    test("--backup writes the original bytes to <name>.orig before overwriting") {
        val dir = tempdir()
        val original = "Привет\n".toByteArray(cp1251)
        val a = write(dir, "a.srt", original)

        val result = run(dir, backup = true)

        a.readText(Charsets.UTF_8) shouldBe "Привет\n"
        val orig = File(dir, "a.srt.orig")
        orig.exists() shouldBe true
        orig.readBytes().toList() shouldBe original.toList()
        (result.files.single().outcome as FileOutcome.Converted).backupName shouldBe "a.srt.orig"
    }

    test("--dry-run writes nothing and no backup") {
        val dir = tempdir()
        val original = "Привет\n".toByteArray(cp1251)
        val a = write(dir, "a.srt", original)

        val run = run(dir, backup = true, dryRun = true)

        run.converted shouldBe 1
        run.files.single().outcome.shouldBeInstanceOf<FileOutcome.WouldConvert>()
        a.readBytes().toList() shouldBe original.toList()
        File(dir, "a.srt.orig").exists() shouldBe false
    }

    test("leaves UTF-16 files alone") {
        val dir = tempdir()
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Привет\n".toByteArray(Charsets.UTF_16LE)
        val wide = write(dir, "wide.ssa", bytes)

        val result = run(dir)

        result.skipped shouldBe 1
        result.files.single().outcome shouldBe FileOutcome.Utf16Bom
        wide.readBytes().toList() shouldBe bytes.toList()
    }

    test("never considers .sub files") {
        val dir = tempdir()
        val vobsub = write(dir, "movie.sub", byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte(), 0x44))
        val before = vobsub.readBytes().toList()

        val result = run(dir)

        result.files.map { it.fileName }.shouldBe(emptyList())
        vobsub.readBytes().toList() shouldBe before
    }

    test("empty directory yields an empty run with zero counts") {
        val result = run(tempdir())

        result.files.shouldBeEmpty()
        result.converted shouldBe 0
        result.skipped shouldBe 0
        result.failed shouldBe 0
    }
})

/** Writes raw bytes and returns the file. */
private fun write(dir: File, name: String, bytes: ByteArray): File =
    File(dir, name).apply { writeBytes(bytes) }

/** A renderer that discards everything: the model is the assertion surface, not the emissions. */
private object NoopRenderer : Renderer {
    override fun render(event: OutputEvent) {}
    override fun render(result: CommandResult) {}
    override fun progress(label: String, total: Int, interactive: Boolean?): ProgressHandle =
        object : ProgressHandle {
            override fun tick() {}
            override fun finish() {}
        }
}
