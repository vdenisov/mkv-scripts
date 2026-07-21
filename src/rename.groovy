import groovy.transform.Field
import org.apache.commons.io.FilenameUtils
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.groovy.PicocliScript2

@Grab('commons-io:commons-io:2.11.0')
@Grab('info.picocli:picocli-groovy:4.6.3')
@Grab('org.yaml:snakeyaml:1.30')
@GrabConfig(systemClassLoader=true)
@CommandLine.Command(name = "mkv-rename", mixinStandardHelpOptions = true,
                     description = "Rename episode files to 'Show - SxxEyy - Title.ext' using " +
                                   "episodes.yaml (preferred) or episodes.txt.")
@PicocliScript2

@CommandLine.Parameters(index = "0", arity = "0..1",
                        description = "Show name. Optional when episodes.yaml supplies one")
@Field String showName

@CommandLine.Parameters(index = "1", arity = "0..1", defaultValue = "1",
                        description = "Episode number of the first line of episodes.txt. " +
                                      "Applies to that file only; episodes.yaml carries real episode numbers")
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
def episodes = evaluate(new File(scriptDir, 'episodes.groovy'))

def extensions = [".mkv", ".mp4", ".avi", ".srt", ".ass", ".mks", ".idx", ".sub", ".mka"] as String[]

// episodes.yaml is preferred when present: it carries real episode numbers, so
// a season with a gap stays correctly aligned, and it supplies the show name.
// episodes.txt remains supported as the format that can be typed by hand.
def yamlFile = new File("episodes.yaml")
def textFile = new File("episodes.txt")

def episodeData = null
def sourceName = yamlFile.isFile() ? 'episodes.yaml' : 'episodes.txt'
if (yamlFile.isFile()) {
    // Explicit UTF-8, unlike episodes.txt below: this file is machine-written
    // by fetch_episodes.groovy, so its charset is a fixed contract rather than
    // something that has to be guessed. episodeOffset plays no part here — the
    // yaml carries real episode numbers, so the join is exact.
    episodeData = episodes.normalizeYaml(new Yaml().load(yamlFile.getText('UTF-8')) as Map)
} else if (textFile.isFile()) {
    // Deliberately no explicit charset: Groovy's no-arg reader runs CharsetToolkit
    // auto-detection, which picks UTF-8 for what fetch_episodes.groovy writes and
    // still falls back to the platform default for an episodes.txt typed or pasted
    // together by hand (Notepad on a Cyrillic Windows saves cp1251). Forcing UTF-8
    // here would mangle exactly that case and gain nothing.
    episodeData = [byEpisode: episodes.indexFromLines(textFile.readLines(), episodeOffset)]
} else {
    ui.error("No episode data: expected episodes.yaml or episodes.txt in the current directory")
    System.err.println "  - run mkv-fetch-episodes, or write episodes.txt by hand (one episode name per line)"
    System.exit(2)
}

// Both sources carry names exactly as TheMovieDB spells them, ':' and '?'
// included, so that mux.groovy can use the real spelling in a title. Making a
// name safe for a file name is this script's job alone. Sanitizing is
// idempotent, so a hand-written episodes.txt that is already free of those
// characters passes through untouched.
def episodeNames = episodeData.byEpisode.collectEntries { number, name ->
    [number, episodes.sanitizeForFilename(name)]
} as Map<String, String>

if (!showName) {
    showName = episodes.sanitizeForFilename(episodeData.show)
    if (!showName) {
        ui.error("No show name: pass one as the first argument, or fetch episodes.yaml first")
        System.exit(2)
    }
    println "*** Show name from episodes.yaml: ${showName}"
}

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

    def seasonAndEpisode = episodes.parseSeasonEpisode(filename)
    if (seasonAndEpisode == null) {
        problems << "no season/episode (sXXeYY) in the file name: '${file.name}'"
        return
    }

    def episodeName = episodeNames[seasonAndEpisode.episode]
    if (episodeName == null) {
        problems << "no title for episode ${seasonAndEpisode.episode} in ${sourceName} (needed by '${file.name}')"
        return
    }

    def suffix = parseSuffix(filename)
    def newName = "${showName} - S${seasonAndEpisode.season}E${seasonAndEpisode.episode} - ${episodeName}${suffix}.${extension}"

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