import groovy.io.FileType
import groovy.transform.Field
import picocli.CommandLine
import picocli.groovy.PicocliScript2

@Grab('info.picocli:picocli-groovy:4.6.3')
@GrabConfig(systemClassLoader = true)
@CommandLine.Command(name = "mkv-fix-srt", mixinStandardHelpOptions = true,
                     description = "Reformat legacy-format SRT files in the current directory into <name>.srt.fixed.")
@PicocliScript2

@CommandLine.Option(names = ["--color"], paramLabel = "WHEN",
                    description = "Colorize output: auto (default, only on a terminal and not under NO_COLOR), " +
                                  "always, or never")
@Field String colorMode = "auto"

// Shared console-output helpers, resolved relative to this script's own
// location — see output.groovy for why they are loaded explicitly by path.
def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def ui = evaluate(new File(scriptDir, 'lib/output.groovy'))(colorMode)

// Collect first, sorted by name, so the batch is processed in a predictable
// order rather than whatever the filesystem returns.
def srtFiles = []
new File(".").eachFile(FileType.FILES) { file ->
    if (file.name.endsWith(".srt")) srtFiles << file
}
srtFiles.sort { it.name }

if (!srtFiles) {
    println ui.yellow("*** No .srt files in the current directory")
    return
}

def fixed = 0
def failed = 0

srtFiles.each { file ->
    ui.header("*** Fixing ${file.name}")

    // One malformed file must not kill the batch: report it, leave it as it
    // is (the .fixed output is only written after a clean parse), continue.
    try {
        def fixedLines = new ArrayList<String>()
        def state = State.TIME

        def count = 1
        def skip = true
        file.readLines().each { line ->
            if (line.startsWith("00:")) {
                skip = false
            }

            if (!skip) {
                switch (state) {
                    case State.TIME:
                        fixedLines.add(count++ as String)
                        fixedLines.add(parseTime(line))
                        state = State.TEXT
                        break
                    case State.TEXT:
                        fixedLines.addAll(parseText(line))
                        state = State.NEWLINE
                        break
                    case State.NEWLINE:
                        fixedLines.add("")
                        // An explicit throw, not an assert: an AssertionError
                        // is an Error and would escape the catch below.
                        if (!line.isEmpty()) {
                            throw new RuntimeException("expected a blank line, got: '${line}'")
                        }
                        state = State.TIME
                        break
                }
            }
        }

        def output = new File(file.parent, file.name + ".fixed")
        output.withWriter { out ->
            fixedLines.each { line -> out.println(line) }
        }
        fixed++
    } catch (Exception e) {
        ui.error("${file.name}: ${e.message} (left unfixed)")
        failed++
    }
}

// The summary is the batch's result, so it stays on stdout in both colours;
// the per-file errors already went to stderr as they happened. Exit non-zero
// if anything failed, so this is usable from a script.
println()
if (failed > 0) {
    println ui.red("*** ${fixed} fixed, ${failed} failed")
    System.exit(1)
}
ui.success("*** ${fixed} fixed, ${failed} failed")

enum State {
    TIME, TEXT, NEWLINE
}

static String parseTime(String line) {
    //00:01:41.42,00:01:42.30
    def matcher = line =~ "^(\\d\\d):(\\d\\d):(\\d\\d).(\\d\\d),(\\d\\d):(\\d\\d):(\\d\\d).(\\d\\d)\$"

    if (!matcher) {
        throw new RuntimeException("Invalid time format: ${line}")
    }

    return matcher.group(1) + ":" + matcher.group(2) + ":" + matcher.group(3) + "," + matcher.group(4) + "0" +
        " --> " +
        matcher.group(5) + ":" + matcher.group(6) + ":" + matcher.group(7) + "," + matcher.group(8) + "0"
}

static List<String> parseText(String line) {
    return line.split("\\[br]") as List<String>
}
