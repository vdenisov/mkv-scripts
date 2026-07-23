@Grab('commons-io:commons-io:2.11.0')
import org.apache.commons.io.FilenameUtils

// Batch mkvpropedit: run the given options against every .mkv in the current
// directory. All arguments are passed through verbatim, so anything mkvpropedit
// accepts works here without editing this script.

def usage = """\
Usage: propedit.groovy <mkvpropedit options...>

Runs mkvpropedit with the given options against every .mkv file in the current
directory. All arguments are passed through verbatim; the file name is inserted
as the first argument.

Examples:
  propedit.groovy --edit track:a2 --set flag-forced=0
  propedit.groovy --edit track:s1 --set flag-default=1
  propedit.groovy --edit info --set title="My Show"
  propedit.groovy --add-track-statistics-tags

Run 'mkvpropedit --help' for the full option list.

Note: -h/--help is handled here only when it is the sole argument; in any other
combination it is passed through to mkvpropedit."""

// Intercept help only when it is the *sole* argument, so every other
// combination reaches mkvpropedit untouched
if (args.length == 0 || (args.length == 1 && args[0] in ['-h', '--help'])) {
    println usage
    return
}

// Shared console-output helpers, resolved relative to this script's own
// location — see output.groovy for why they are loaded explicitly by path.
// Loaded after the usage/early-return above, so a bare -h stays instant.
// Deliberately no --color option: every argument is passed through to
// mkvpropedit verbatim, so intercepting one would break the passthrough
// contract. Colour is controlled by auto-detection and NO_COLOR only.
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
def commandLine = ([mkvpropeditExe, "${-> fileName}.mkv"] + (args as List)) as ArrayList

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
