import groovy.transform.Field
import org.apache.commons.io.FilenameUtils
import picocli.CommandLine
import picocli.groovy.PicocliScript2

@Grab('commons-io:commons-io:2.11.0')
@Grab('info.picocli:picocli-groovy:4.6.3')
@GrabConfig(systemClassLoader=true)
@CommandLine.Command(name = "renamer")
@PicocliScript2

@CommandLine.Parameters(index = "0", description = "Show name")
@Field String showName

@CommandLine.Parameters(index = "1", description = "Episode offset", defaultValue = "1")
@Field int episodeOffset = 1

def extensions = [".mkv", ".mp4", ".avi", ".srt", ".ass", ".mks", ".idx", ".sub", ".mka"] as String[]

def episodeNames = new File("episodes.txt")
    .readLines()
    .withIndex()
    .collectEntries(nwi -> [ String.format("%02d", nwi.v2 + episodeOffset), nwi.v1 ]) as Map<String, String>

def files = new File(".")
    .listFiles({ it.file && it.name.toLowerCase().endsWithAny(extensions) } as FileFilter) as List<File>

files.each { file ->
    def filename = FilenameUtils.getBaseName(file.name)
    def extension = FilenameUtils.getExtension(file.name)
    def seasonAndEpisode = parseSeasonAndEpisode(filename)
    def suffix = parseSuffix(filename)

    def episodeName = episodeNames[seasonAndEpisode.v2]

    println "Renaming '${filename}.${extension}'"

    def newName = "${showName} - S${seasonAndEpisode.v1}E${seasonAndEpisode.v2} - ${episodeName}${suffix}.${extension}"
    println "to '${newName}'"
    file.renameTo(newName)
}

static Tuple2<String, String> parseSeasonAndEpisode(String filename) {
    def matcher = filename.toLowerCase() =~ /s(\d\d)\.?e(\d\d)/
    if (matcher) {
        return Tuple.tuple(matcher.group(1), matcher.group(2))
    } else {
        throw new RuntimeException("Cannot find season and episode in the filename: ${filename}")
    }
}

// Returns an optional suffix at the end of the file name in square brackets
static String parseSuffix(String filename) {
    // Parse suffix like [Dub Studio]
    def matcher = filename =~ /(\[.+\])$/
    if (matcher) {
        return matcher.group(1)
    } else {
        return ""
    }
}