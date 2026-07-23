@Grab('commons-io:commons-io:2.11.0')
import org.apache.commons.io.FilenameUtils

// Set each MKV's segment title and video track name from its file name, via
// mkvpropedit — no remux needed.

// Shared console-output helpers, resolved relative to this script's own
// location — see output.groovy for why they are loaded explicitly by path.
// No arg parsing here (the script takes no options), so colour is controlled
// by auto-detection and NO_COLOR only.
def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def ui = evaluate(new File(scriptDir, 'lib/output.groovy'))('auto')
def findMkvTool = evaluate(new File(scriptDir, 'lib/tools.groovy'))

def mkvpropeditExe
try {
    mkvpropeditExe = findMkvTool('mkvpropedit')
} catch (RuntimeException e) {
    ui.error(e.message)
    System.exit(2)
}

def fileName = null
def title = "${-> fileName}"

// List-exec passes each element as exactly one argument, so no manual quoting:
// an embedded \" here used to end up as a literal quote character inside the
// written title on Linux (Windows argv re-quoting made it redundant there).
def commandLine = [
    mkvpropeditExe,
    "${-> fileName}.mkv", // Input file name
    "--edit",
    "info",
    "--set",
    "title=${-> title}", // Segment Title
    "--edit",
    "track:v1",
    "--set",
    "name=${-> title}" // Video Track Name
] as ArrayList

def currentDir = new File(".")

//Read files first to be able to write to the same directory
def files = currentDir
    .listFiles({ it.isFile() && FilenameUtils.getExtension(it.getName().toLowerCase()) == "mkv"} as FileFilter) as List<File>

Process proc = null
def failed = 0

files.forEach { file ->
    {
        ui.header("*** Processing ${file.name}")
        println()

        fileName = FilenameUtils.getBaseName(file.name)

        proc = commandLine.execute()
        proc.consumeProcessOutput(System.out, System.err)
        proc.waitFor()

        if (proc.exitValue() != 0) {
            println()
            ui.error("mkvpropedit exited with code ${proc.exitValue()}")
            failed++
        }

        proc = null
    }
}

// The summary is the batch's result, so it stays on stdout in both colours;
// the per-file errors already went to stderr as they happened. Exit non-zero
// if anything failed, so this is usable from a script.
println()
if (failed > 0) {
    println ui.red("*** ${files.size() - failed} processed, ${failed} failed")
    System.exit(1)
}
ui.success("*** ${files.size()} processed, 0 failed")
