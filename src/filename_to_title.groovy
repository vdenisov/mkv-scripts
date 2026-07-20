@Grab('commons-io:commons-io:2.11.0')
import org.apache.commons.io.FilenameUtils

def fileName = null
def title = "${-> fileName}"

def commandLine = [
    "mkvpropedit",
    "${-> fileName}.mkv", // Input file name
    "--edit",
    "info",
    "--set",
    "\"title=${-> title}\"", // Segment Title
    "--edit",
    "track:v1",
    "--set",
    "\"name=${-> title}\"" // Video Track Name
] as ArrayList<GString>

def currentDir = new File(".")

//Read files first to be able to write to the same directory
def files = currentDir
    .listFiles({ it.isFile() && FilenameUtils.getExtension(it.getName().toLowerCase()) == "mkv"} as FileFilter) as List<File>

Process proc = null

files.forEach { file ->
    {
        println "*** Processing ${file.name}"
        println()

        fileName = FilenameUtils.getBaseName(file.name)

        proc = commandLine.execute()
        proc.consumeProcessOutput(System.out, System.err)
        proc.waitFor()

        if (proc.exitValue() != 0) {
            println()
            println "*** Error: ${proc.exitValue()}"
        }

        proc = null
    }
}