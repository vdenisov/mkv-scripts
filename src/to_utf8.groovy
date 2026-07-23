import groovy.transform.Field
import org.apache.commons.io.FilenameUtils
import picocli.CommandLine
import picocli.groovy.PicocliScript2

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException

@Grab('commons-io:commons-io:2.11.0')
@Grab('info.picocli:picocli-groovy:4.6.3')
@GrabConfig(systemClassLoader = true)
@CommandLine.Command(name = "mkv-to-utf8", mixinStandardHelpOptions = true,
                     description = "Convert subtitle files in the current directory to UTF-8, in place.")
@PicocliScript2

@CommandLine.Option(names = ["-e", "--encoding"], paramLabel = "CHARSET",
                    description = "Source encoding to decode from (default: \${DEFAULT-VALUE})")
@Field String sourceEncoding = "windows-1251"

@CommandLine.Option(names = ["-b", "--backup"],
                    description = "Keep the original as <name>.orig before overwriting it")
@Field boolean backup = false

@CommandLine.Option(names = ["-n", "--dry-run"],
                    description = "Report what would be converted without writing anything")
@Field boolean dryRun = false

@CommandLine.Option(names = ["--color"], paramLabel = "WHEN",
                    description = "Colorize output: auto (default, only on a terminal and not under NO_COLOR), " +
                                  "always, or never")
@Field String colorMode = "auto"

// Shared console-output helpers, resolved relative to this script's own
// location — see output.groovy for why they are loaded explicitly by path.
def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def ui = evaluate(new File(scriptDir, 'lib/output.groovy'))(colorMode)

// Text subtitle formats worth converting. '.sub' is deliberately absent: the
// extension is ambiguous between MicroDVD text and the binary VobSub half of a
// .idx/.sub pair, and rewriting a VobSub .sub would destroy it.
def extensions = ['srt', 'ass', 'ssa', 'vtt'] as Set

// Fail on an unusable charset name before touching any file, not partway through
def charset
try {
    charset = Charset.forName(sourceEncoding)
} catch (UnsupportedCharsetException | IllegalCharsetNameException e) {
    ui.error("Unknown source encoding '${sourceEncoding}': ${e.class.simpleName}")
    System.err.println "Use a charset name your JVM knows, e.g. windows-1251, windows-1250, Shift_JIS, Big5."
    System.exit(2)
}

// Decode strictly, so that bytes which are not valid in `cs` raise an error
// instead of being silently replaced. Java's default decoder substitutes U+FFFD,
// which is exactly how decoding Windows-1251 bytes as Windows-1250 "succeeds"
// and produces mojibake nobody notices until playback.
def strictDecode = { byte[] bytes, Charset cs ->
    cs.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString()
}

def startsWith = { byte[] bytes, int[] prefix ->
    bytes.length >= prefix.length && (0..<prefix.length).every { (bytes[it] & 0xFF) == prefix[it] }
}

ui.header("*** Converting ${extensions.sort().join(', ')} files from ${charset.name()} to UTF-8")
if (dryRun) println "*** Dry run: nothing will be written"
println()

def files = (new File(".").listFiles({
    it.isFile() && extensions.contains(FilenameUtils.getExtension(it.name.toLowerCase()))
} as FileFilter) as List<File>)?.sort { it.name } ?: []

if (!files) {
    println ui.yellow("*** No ${extensions.sort().join('/')} files in the current directory")
    return
}

def converted = 0
def skipped = 0
def failed = 0

files.each { file ->
    def bytes = file.bytes

    // A UTF-16 file decoded as a single-byte charset would "succeed" — every
    // byte maps — and be written back as mojibake, so refuse rather than corrupt
    if (startsWith(bytes, [0xFF, 0xFE] as int[]) || startsWith(bytes, [0xFE, 0xFF] as int[])) {
        println "*** ${file.name}: looks like UTF-16 (BOM), leaving it alone"
        skipped++
        return
    }

    // Already UTF-8, by BOM or by decoding cleanly as UTF-8. Converting again is
    // how a previously fixed file gets corrupted, and this is what makes the
    // script safe to re-run over a directory. Pure ASCII lands here too, which
    // is correct: it is already valid UTF-8 and needs no conversion.
    if (startsWith(bytes, [0xEF, 0xBB, 0xBF] as int[])) {
        println "*** ${file.name}: already UTF-8 (BOM), skipping"
        skipped++
        return
    }
    try {
        strictDecode(bytes, StandardCharsets.UTF_8)
        println "*** ${file.name}: already valid UTF-8, skipping"
        skipped++
        return
    } catch (CharacterCodingException ignored) {
        // Not UTF-8, so it is a genuine conversion candidate
    }

    def text
    try {
        text = strictDecode(bytes, charset)
    } catch (CharacterCodingException e) {
        ui.error("${file.name}: not valid ${charset.name()} (${e.class.simpleName}), leaving it alone")
        System.err.println "      Pass the right --encoding; converting anyway would produce mojibake."
        failed++
        return
    }

    if (dryRun) {
        println "*** ${file.name}: would convert from ${charset.name()} to UTF-8"
        converted++
        return
    }

    if (backup) {
        def orig = new File(file.parentFile, "${file.name}.orig")
        orig.bytes = bytes
        println "*** ${file.name}: backed up as ${orig.name}"
    }

    // Write the decoded text back whole rather than line by line, so the file's
    // existing line endings survive instead of being normalised to this
    // platform's — SRT in the wild is usually CRLF and players care.
    file.bytes = text.getBytes(StandardCharsets.UTF_8)
    println "*** ${file.name}: converted from ${charset.name()} to UTF-8"
    converted++
}

println()
// The summary is the batch's result, so it stays on stdout in both colours;
// the per-file details already went to stderr as they happened.
def summary = "*** ${converted} converted, ${skipped} skipped, ${failed} failed"
if (failed > 0) println ui.red(summary) else ui.success(summary)

// Non-zero on failure so this is usable from a shell script, matching
// propedit.groovy rather than mux.groovy's always-exit-0 batch behaviour
if (failed > 0) {
    System.exit(1)
}
