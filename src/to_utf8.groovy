@Grab('commons-io:commons-io:2.11.0')
import org.apache.commons.io.FilenameUtils

def currentDir = new File(".")
def files = currentDir
    .listFiles({ it.isFile() && FilenameUtils.getExtension(it.getName().toLowerCase()) == "srt"} as FileFilter) as List<File>

// Read file into memory in win1251 encoding and write it back in utf8 appending .utf8 to the file name
files.forEach { file ->
    {
        println "*** Processing ${file.name}"
        println()

        def fileName = FilenameUtils.getBaseName(file.name) + ".utf8." + FilenameUtils.getExtension(file.name)

        def lines = file.readLines("windows-1251")
        new File(fileName).withWriter("utf8") { writer ->
            lines.each { line ->
                writer.writeLine(line)
            }
        }
    }
}
