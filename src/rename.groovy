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

@CommandLine.Option(names = ["--color"], paramLabel = "WHEN",
                    description = "Colorize output: auto (default, only on a terminal and not under NO_COLOR), " +
                                  "always, or never")
@Field String colorMode = "auto"

// Shared console-output helpers, resolved relative to this script's own
// location — see output.groovy for why they are loaded explicitly by path.
def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def ui = evaluate(new File(scriptDir, 'output.groovy'))(colorMode)

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
    // The whole report goes to stderr, preview included: splitting one report
    // across two streams scrambles its line order under buffering when both
    // are redirected to the same place.
    ui.error("Refusing to rename anything, ${problems.size()} problem(s) found:")
    problems.each { System.err.println "  - ${it}" }

    // Still show what the rest of the batch would have done, so the scope of
    // what is blocked is visible rather than having to be inferred
    if (plan) {
        System.err.println()
        System.err.println "*** The other ${plan.size()} file(s) would have been renamed:"
        plan.each { System.err.println "  '${it.file.name}' -> '${it.newName}'" }
    }

    System.exit(1)
}

if (!plan) {
    println ui.yellow("*** Nothing to rename")
    return
}

// Phase 2: everything checks out, so apply (or just show) the plan
def failed = 0
plan.each { entry ->
    if (dryRun) {
        println "'${entry.file.name}' -> '${entry.newName}'"
    } else {
        // Both lines are one logical header, so both carry the header colour
        ui.header("*** Renaming '${entry.file.name}'")
        ui.header("***       to '${entry.newName}'")
        if (!entry.file.renameTo(new File(entry.file.parentFile, entry.newName))) {
            ui.error("could not rename '${entry.file.name}'")
            failed++
        }
    }
}

// The summary is the batch's result, so it stays on stdout in both colours;
// the per-file errors already went to stderr as they happened.
println()
if (dryRun) {
    println "*** Dry run: ${plan.size()} file(s) would be renamed, nothing changed"
} else if (failed > 0) {
    println ui.red("*** ${plan.size() - failed} renamed, ${failed} failed")
    System.exit(1)
} else {
    ui.success("*** ${plan.size()} file(s) renamed")
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