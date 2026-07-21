import groovy.transform.Field
import org.apache.commons.io.FilenameUtils
import picocli.CommandLine
import picocli.groovy.PicocliScript2

@Grab('commons-io:commons-io:2.11.0')
@Grab('info.picocli:picocli-groovy:4.6.3')
@GrabConfig(systemClassLoader=true)
@CommandLine.Command(name = "mkv-rename", mixinStandardHelpOptions = true,
                     description = "Rename episode files to 'Show - SxxEyy - Title.ext' using episodes.txt.")
@PicocliScript2

@CommandLine.Parameters(index = "0", description = "Show name")
@Field String showName

@CommandLine.Parameters(index = "1", description = "Episode offset", defaultValue = "1")
@Field int episodeOffset = 1

@CommandLine.Option(names = ["-n", "--dry-run"], description = "Print planned renames without touching any files")
@Field boolean dryRun = false

def extensions = [".mkv", ".mp4", ".avi", ".srt", ".ass", ".mks", ".idx", ".sub", ".mka"] as String[]

// Deliberately no explicit charset: Groovy's no-arg reader runs CharsetToolkit
// auto-detection, which picks UTF-8 for what fetch_episodes.groovy writes and
// still falls back to the platform default for an episodes.txt typed or pasted
// together by hand (Notepad on a Cyrillic Windows saves cp1251). Forcing UTF-8
// here would mangle exactly that case and gain nothing.
def episodeNames = new File("episodes.txt")
    .readLines()
    .withIndex()
    .collectEntries(nwi -> [ String.format("%02d", nwi.v2 + episodeOffset), nwi.v1 ]) as Map<String, String>

def files = new File(".")
    .listFiles({ it.file && it.name.toLowerCase().endsWithAny(extensions) } as FileFilter) as List<File>

// Phase 1: work out every rename before performing any of them. Renaming is
// destructive and the sXXeYY pattern is what links a file to its episode, so a
// failure halfway through would leave the directory in a state that has to be
// untangled by hand.
def plan = []
def problems = []

files.each { file ->
    def filename = FilenameUtils.getBaseName(file.name)
    def extension = FilenameUtils.getExtension(file.name)

    def seasonAndEpisode = parseSeasonAndEpisode(filename)
    if (seasonAndEpisode == null) {
        problems << "no season/episode (sXXeYY) in the file name: '${file.name}'"
        return
    }

    def episodeName = episodeNames[seasonAndEpisode.v2]
    if (episodeName == null) {
        problems << "no title for episode ${seasonAndEpisode.v2} in episodes.txt (needed by '${file.name}')"
        return
    }

    def suffix = parseSuffix(filename)
    def newName = "${showName} - S${seasonAndEpisode.v1}E${seasonAndEpisode.v2} - ${episodeName}${suffix}.${extension}"

    def target = new File(file.parentFile, newName)
    if (target.exists() && target.absolutePath != file.absolutePath) {
        problems << "target already exists: '${newName}' (would overwrite it with '${file.name}')"
        return
    }

    plan << [file: file, newName: newName]
}

def duplicates = plan.groupBy { it.newName }.findAll { name, entries -> entries.size() > 1 }
duplicates.each { name, entries ->
    problems << "multiple files would be renamed to '${name}': ${entries.collect { it.file.name }.join(', ')}"
}

if (problems) {
    println "*** Refusing to rename anything, ${problems.size()} problem(s) found:"
    problems.each { println "  - ${it}" }

    // Still show what the rest of the batch would have done, so the scope of
    // what is blocked is visible rather than having to be inferred
    if (plan) {
        println()
        println "*** The other ${plan.size()} file(s) would have been renamed:"
        plan.each { println "  '${it.file.name}' -> '${it.newName}'" }
    }

    System.exit(1)
}

if (!plan) {
    println "*** Nothing to rename"
    return
}

// Phase 2: everything checks out, so apply (or just show) the plan
def failed = 0
plan.each { entry ->
    if (dryRun) {
        println "'${entry.file.name}' -> '${entry.newName}'"
    } else {
        println "Renaming '${entry.file.name}'"
        println "to '${entry.newName}'"
        if (!entry.file.renameTo(new File(entry.file.parentFile, entry.newName))) {
            println "*** Error: could not rename '${entry.file.name}'"
            failed++
        }
    }
}

if (dryRun) {
    println()
    println "*** Dry run: ${plan.size()} file(s) would be renamed, nothing changed"
} else if (failed > 0) {
    println()
    println "*** ${failed} of ${plan.size()} file(s) could not be renamed"
    System.exit(1)
}

/** Season and episode numbers from a file name, or null when absent. */
static Tuple2<String, String> parseSeasonAndEpisode(String filename) {
    def matcher = filename.toLowerCase() =~ /s(\d\d)\.?e(\d\d)/
    return matcher ? Tuple.tuple(matcher.group(1), matcher.group(2)) : null
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