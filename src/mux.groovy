import groovy.json.JsonSlurper
import groovy.transform.Field
import org.apache.commons.io.FilenameUtils
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.groovy.PicocliScript2

import java.nio.file.FileSystems
import java.nio.file.Paths

@Grab('commons-io:commons-io:2.11.0')
@Grab('org.yaml:snakeyaml:1.30')
@Grab('info.picocli:picocli-groovy:4.6.3')
@GrabConfig(systemClassLoader = true)
@CommandLine.Command(name = "mkv-mux", mixinStandardHelpOptions = true,
                     description = "Mux MKV files from multiple sources using mkvmerge.")
@PicocliScript2

@CommandLine.Option(names = ["-c", "--config"], paramLabel = "PATH",
                    description = "Path to the config file (default: config.yaml in the current directory)")
@Field String configPath = null

@CommandLine.Option(names = ["-n", "--dry-run"],
                    description = "Print the mkvmerge command line for every matching file without executing it")
@Field boolean dryRun = false

@CommandLine.Option(names = ["--no-check"],
                    description = "Skip the automatic pre-flight consistency check before muxing")
@Field boolean noCheck = false

@CommandLine.Option(names = ["--strict"],
                    description = "Abort instead of warning when the consistency check finds a discrepancy " +
                                  "affecting a track that config.yaml selects")
@Field boolean strict = false

@CommandLine.Option(names = ["--color"], paramLabel = "WHEN",
                    description = "Colorize output: auto (default, only on a terminal and not under NO_COLOR), " +
                                  "always, or never")
@Field String colorMode = "auto"

@CommandLine.Option(names = ["-x", "--exclude"], paramLabel = "PATTERN",
                    description = "File name or glob pattern to skip; may be given more than once")
@Field List<String> excludeMasks = []

@CommandLine.Parameters(index = "0..*", arity = "0..*", paramLabel = "FILE",
                        description = "File names or glob patterns to process; may be given more than once " +
                                      "(default: every file in the current directory)")
@Field List<String> fileMasks = []

// Shared console-output helpers (colours, error/warning forms), resolved
// relative to this script's own location — never the CWD, which is the media
// directory. Loaded explicitly by path rather than through Groovy's implicit
// sibling-class resolution, which is CWD-dependent (see CLAUDE.md). First
// script-body statement on purpose: closures below capture `ui` from their
// enclosing scope, so it has to exist before any of them is defined.
def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def ui = evaluate(new File(scriptDir, 'lib/output.groovy'))(colorMode)

// Config lives with the media: config.yaml in the current directory, or an
// explicit --config path. There is deliberately no fall-back to a config beside
// the script — the file shipped there (config.example.yaml) is a template, and
// silently applying it to whatever directory you are in produced confidently
// wrong output (its track selections never match). Copy it next to your media,
// or point --config at your own.
//
// Inspecting files without a config — track tables, the batch structure report —
// is what mkv-inspect is for; this script only muxes, and muxing always needs a
// config.
// The three ways a YAML mapping can be unusable are classified in yaml.groovy,
// shared with inspect.groovy; what to do about one is this script's own call.
// Here it is always the same clean exit 2 rather than a stack trace, because
// muxing against a config that could not be understood is exactly the
// confidently-wrong output this script refuses to produce.
def loadYaml = evaluate(new File(scriptDir, 'lib/yaml.groovy'))({ String text -> new Yaml().load(text) })

def configFile = configPath ? new File(configPath) : new File("config.yaml")
def config = null
if (configFile.isFile()) {
    def loaded = loadYaml.loadMapping(configFile)
    if (loaded.problem) {
        ui.error("${loaded.problem}; there is nothing to mux with.")
        System.exit(2)
    }
    config = loaded.value
} else {
    if (configPath) {
        ui.error("Config file not found: ${configFile.absolutePath}")
    } else {
        ui.error("No config.yaml in the current directory (${new File('.').absoluteFile.parent}).")
        System.err.println "Copy ${new File(scriptDir, 'config.example.yaml').absolutePath} next to your media"
        System.err.println "and edit it, or pass --config <path>."
    }
    System.exit(2)
}

// Locate an MKVToolNix executable: PATH first, then the Windows default
// install location. Shared with the other scripts via tools.groovy.
def findMkvTool = evaluate(new File(scriptDir, 'lib/tools.groovy'))

// Extract general settings from config. allowedExtensions falls back to the
// common video containers, and mkvmergeExe to PATH auto-detection, so both are
// optional in the config.
def DEFAULT_EXTENSIONS = ['mkv', 'mp4', 'm4v', 'avi', 'mov', 'ts', 'm2ts', 'webm', 'mka', 'mks'] as Set
def destinationDir = config?.general?.destinationDir
def allowedExtensions = (config?.general?.allowedExtensions as Set) ?: DEFAULT_EXTENSIONS
def mkvmergeExe = (config?.general?.containsKey('mkvmergeExe') && config.general.mkvmergeExe)
    ? config.general.mkvmergeExe
    : findMkvTool('mkvmerge')

// Hardcoded values
// mkvmerge's UI language codes changed across versions ('en_US' up to at least
// v82, 'en' in later versions), so probe which spelling this mkvmerge accepts
// and omit the option entirely if neither works
def uiLanguage = ["en", "en_US"].find { lang ->
    try {
        def proc = [mkvmergeExe, "--ui-language", lang, "--version"].execute()
        proc.waitFor()
        proc.exitValue() == 0
    } catch (ignored) {
        false
    }
}
def priority = "lower"

// Probing and the whole consistency-check report live in check.groovy, shared
// with inspect.groovy so the pre-flight run here and the standalone report there
// can never drift. JSON parsing is injected rather than imported there: the
// shared helpers carry no imports, so groovy.json stays on this side of the seam.
//
// Loaded here, before every closure that uses it: a closure resolves a
// script-level `def` local through its enclosing scope, so the variable has to
// exist by the time the calling closure is created, not merely by the time it
// runs.
def check = evaluate(new File(scriptDir, 'lib/check.groovy'))(
    [ui: ui, mkvmergeExe: mkvmergeExe, parseJson: { String text -> new JsonSlurper().parseText(text) }])

def formatFileList = check.formatFileList
def plural = ui.plural
def probeFile = check.probeFile

// Probe results, keyed by file. Declared here rather than next to the probing
// loop far below because the closures in this section capture it by enclosing
// scope at definition time — the same rule that makes every helper here a
// closure in the first place.
def probedInfos = [:]

// ── Substitution variables ──────────────────────────────────────────────────
//
// The engine itself lives in subst.groovy, shared with inspect.groovy. Episode
// metadata is read here and passed in already parsed: snakeyaml stays in the
// scripts, the semantics live in the helper — the same seam as episodes.groovy's
// normalizeYaml.
def episodesHelper = evaluate(new File(scriptDir, 'lib/episodes.groovy'))

// Episode metadata, read from the media directory only — mux never goes to the
// network. episodes.yaml carries real episode numbers and the raw spelling of
// each name, which is the whole point: the ':' and '?' that cannot appear in a
// file name survive here and can go into a title.
def episodeData = null
def episodeSource = null
def episodesYaml = new File('episodes.yaml')
def episodesText = new File('episodes.txt')
if (episodesYaml.isFile()) {
    // Hand-editable, so it fails the same three ways config.yaml does and gets
    // the same clean exit 2: a title stamped from metadata that could not be read
    // is the same confidently-wrong output. Explicit UTF-8 because this file is
    // machine-written and that is a fixed contract; normalizeYaml goes through
    // the guard because `episode: "one"` throws there rather than at parse time.
    def loaded = loadYaml.loadMapping(episodesYaml,
                                      [charset: 'UTF-8', transform: episodesHelper.normalizeYaml])
    if (loaded.problem) {
        ui.error("${loaded.problem}; delete it or fix it.")
        System.exit(2)
    }
    episodeData = loaded.value
    episodeSource = 'episodes.yaml'
} else if (episodesText.isFile()) {
    // Line N is episode N. There is no offset option here — mux is not the
    // script that decides numbering — so a trimmed episodes.txt, holding only
    // the episodes on hand from partway through a season, misses. The pre-flight
    // reports those files as dropped rather than stamping a plausible wrong
    // title into them; episodes.yaml is the answer for any season not from 1.
    episodeData = [byEpisode: episodesHelper.indexFromLines(episodesText.readLines(), 1)]
    episodeSource = 'episodes.txt'
}

def substEngine = evaluate(new File(scriptDir, 'lib/subst.groovy'))(
    [ui: ui, episodes: episodesHelper, episodeData: episodeData])

def substitute = substEngine.substitute
def fileVarsFor = substEngine.fileVarsFor
def trackVarsFor = substEngine.trackVarsFor

// The probed track behind a config track entry, for ${codec}. Probing is only
// ever triggered when a template actually uses ${codec}; the record is normally
// already there from the pre-flight check.
def probedTrackFor = { File file, Integer trackId ->
    def info = probedInfos[file]
    if (info == null) {
        info = probeFile(file)
        probedInfos[file] = info
    }
    if (!info.ok) return null
    info.raw.tracks.find { (it.id as Integer) == trackId }
}

def companionProbeCache = [:]
def companionTrackFor = { String path ->
    if (!companionProbeCache.containsKey(path)) {
        def f = new File(path)
        companionProbeCache[path] = f.isFile() ? probeFile(f) : null
    }
    def info = companionProbeCache[path]
    (info?.ok) ? info.raw.tracks.find { (it.id as Integer) == 0 } : null
}

// Validation, stage one: a name that is not a variable, or not a variable legal
// in this field, is a config error — fatal, before anything is probed or muxed,
// in every mode. A typo'd ${epsiodeName} would otherwise be stamped verbatim
// into the track names of an entire season. What comes back is which variables
// the config actually uses; everything derived from them is gated on that, so a
// config with no templates costs nothing at all.
def templateUsage = substEngine.validateTemplates(substEngine.collectTemplateFields(config as Map))
if (templateUsage.problems) System.exit(2)
def usedFileVars = templateUsage.usedFileVars
def usesCodec = templateUsage.usesCodec

// The consistency check itself lives in check.groovy. Everything config-derived
// that the report needs — which track IDs are selected, what config.yaml titles
// them, what counts as blocking — is built here and passed in, so the helper
// stays free of config knowledge.
def selection = check.makeSelection(config as Map)

// Which files are in a layout group, said as episode ranges where the names
// allow it. Display only, and composed in episodes.groovy so this report and
// inspect.groovy's render a group identically — see membershipLabel there.
// The parameter is `batch`, not `files`: both scripts already have a script-level
// `files`, and a closure parameter of the same name is a compile error here — the
// same collision the probedInfos/infos note in CLAUDE.md warns about.
def membershipFor = { List batch ->
    episodesHelper.membershipLabel(batch.collect { FilenameUtils.getBaseName(it.name) }, ui.pluralize)
}

def runConsistencyCheck = { List batch, Map infos ->
    check.runConsistencyCheck(batch, infos, selection + [membershipFor: membershipFor])
}

// The track order the config expresses: video first, then audio tracks and
// subtitle tracks in the order they are listed, then one track per additional
// source (which always contributes exactly one track, with ID 0).
def deriveTrackOrder = {
    def parts = ['0:0']
    config.mainSource.audioTracks?.each { parts << "0:${it.id}" }
    config.mainSource.subtitleTracks?.each { parts << "0:${it.id}" }
    config.additionalSources?.eachWithIndex { source, i -> parts << "${i + 1}:0" }
    parts.join(',')
}

// mkvmerge silently ignores --track-order entries that match no muxed track, so
// a stale trackOrder fails quietly. Warn instead; never fail, since an existing
// config that works today must keep working.
def validateTrackOrder = { String order ->
    def configured = deriveTrackOrder().split(',') as Set
    def entries = order.split(',').collect { it.trim() }.findAll { it }

    def malformed = entries.findAll { !(it ==~ /^\d+:\d+$/) }
    if (malformed) {
        ui.warn("trackOrder contains malformed entries: ${malformed.join(', ')}")
        System.err.println ui.yellow("***          Expected comma-separated sourceIndex:trackId pairs, e.g. \"0:0,0:1,1:0\".")
    }

    def unknown = entries.findAll { it ==~ /^\d+:\d+$/ } - configured
    if (unknown) {
        ui.warn("trackOrder references track IDs not configured: ${unknown.join(', ')}")
        System.err.println ui.yellow("***          mkvmerge silently ignores unknown IDs, so these have no effect.")
        System.err.println ui.yellow("***          Check trackOrder against mainSource.audioTracks / subtitleTracks / additionalSources.")
    }

    def missing = configured - entries
    if (missing) {
        ui.warn("trackOrder omits configured track IDs: ${missing.join(', ')}")
        System.err.println ui.yellow("***          These tracks are still muxed, but their position in the output is left to mkvmerge.")
    }
}

// Resolve once, not per file, so the warnings are printed only once.
def effectiveTrackOrder
if (config.containsKey('trackOrder') && config.trackOrder) {
    effectiveTrackOrder = config.trackOrder.toString()
    validateTrackOrder(effectiveTrackOrder)
} else {
    effectiveTrackOrder = deriveTrackOrder()
    println "*** trackOrder not configured; using derived order: ${effectiveTrackOrder}"
    println()
}

def fileName = null
def extension = null

// Build command line based on configuration; closure so it captures script-scope locals
def buildCommandLine = { File currentFile ->
    // Substitution resolves eagerly here: this closure is re-invoked per file,
    // after fileName is set, so there is no need for the lazy-GString treatment
    // the file name itself gets.
    def fileVars = fileVarsFor(currentFile).vars
    def subst = { value, Map extra = [:] ->
        substitute(value.toString(), extra ? fileVars + extra : fileVars)
    }

    def commandLine = [mkvmergeExe] as ArrayList
    if (uiLanguage != null) {
        commandLine.addAll(["--ui-language", uiLanguage])
    }
    commandLine.addAll([
        "--priority",
        priority,
        "--output",
        "${destinationDir}/${-> fileName}.mkv", // Output file name
    ])

    // Auto-generate track selection options from track IDs
    def hasAudioTracks = config.mainSource.containsKey('audioTracks') && !config.mainSource.audioTracks.isEmpty()
    def hasSubtitleTracks = config.mainSource.containsKey('subtitleTracks') && !config.mainSource.subtitleTracks.isEmpty()

    // Handle missing/empty audio tracks
    if (!hasAudioTracks) {
        commandLine.add("--no-audio")
    } else {
        def audioTrackIds = config.mainSource.audioTracks.collect { it.id }.join(',')
        commandLine.addAll(["--audio-tracks", audioTrackIds])
    }

    // Handle missing/empty subtitle tracks
    if (!hasSubtitleTracks) {
        commandLine.add("--no-subtitles")
    } else {
        def subtitleTrackIds = config.mainSource.subtitleTracks.collect { it.id }.join(',')
        commandLine.addAll(["--subtitle-tracks", subtitleTrackIds])
    }

    // Add additional command-line options for main source if specified
    if (config.mainSource.containsKey('additionalOptions')) {
        config.mainSource.additionalOptions.each { option ->
            commandLine.add(option)
        }
    }

    // Add video track settings (always first track)
    def videoTrack = config.mainSource.videoTrack
    def videoTrackLang = videoTrack.language
    // Allow overriding video track name but use filename as default. This is
    // the video *track* name, distinct from the segment title set below — many
    // players conflate the two, but they are separate fields and are worth
    // setting separately ("Original Japanese" against the episode title).
    def videoTrackTitle = videoTrack.containsKey('title')
        ? subst(videoTrack.title, usesCodec ? trackVarsFor(videoTrack, probedTrackFor(currentFile, 0))
                                            : trackVarsFor(videoTrack, null))
        : "${-> fileName}"

    commandLine.addAll([
        "--language",
        "0:${-> videoTrackLang}",
        "--track-name",
        "0:${-> videoTrackTitle}"
    ])

    // Add audio tracks
    if (hasAudioTracks) {
        config.mainSource.audioTracks.each { track ->
            def probed = usesCodec ? probedTrackFor(currentFile, track.id as Integer) : null
            commandLine.addAll([
                "--language",
                "${track.id}:${track.language}",
                "--track-name",
                "${track.id}:${subst(track.title, trackVarsFor(track, probed))}"
            ])

            commandLine.addAll([
                "--default-track-flag",
                "${track.id}:${track.default ? 'yes' : 'no'}"
            ])
        }
    }

    // Add subtitle tracks
    if (hasSubtitleTracks) {
        config.mainSource.subtitleTracks.each { track ->
            def probed = usesCodec ? probedTrackFor(currentFile, track.id as Integer) : null
            commandLine.addAll([
                "--language",
                "${track.id}:${track.language}",
                "--track-name",
                "${track.id}:${subst(track.title, trackVarsFor(track, probed))}"
            ])

            if (track.containsKey('charset')) {
                commandLine.addAll([
                    "--sub-charset",
                    "${track.id}:${track.charset}"
                ])
            }

            commandLine.addAll([
                "--default-track-flag",
                "${track.id}:${track.default ? 'yes' : 'no'}"
            ])
        }
    }

    // Add primary source file
    commandLine.addAll([
        "(",
        "${-> fileName}.${-> extension}",
        ")"
    ])

    // Add additional sources if configured
    if (config.containsKey('additionalSources')) {
        config.additionalSources.each { source ->
            def sourcePath = subst(source.file)

            // Add track settings for this source
            source.tracks.each { track ->
                // Assume track ID 0 for additional tracks
                def trackId = 0
                def probed = usesCodec ? companionTrackFor(sourcePath) : null

                commandLine.addAll([
                    "--language",
                    "${trackId}:${track.language}",
                    "--track-name",
                    "${trackId}:${subst(track.title, trackVarsFor(track, probed))}"
                ])

                // Add charset for subtitle tracks if specified
                if (track.containsKey('charset')) {
                    commandLine.addAll([
                        "--sub-charset",
                        "${trackId}:${track.charset}"
                    ])
                }

                commandLine.addAll([
                    "--default-track-flag",
                    "${trackId}:${track.default ? 'yes' : 'no'}"
                ])
            }

            // Add additional command-line options if specified
            if (source.containsKey('additionalOptions')) {
                source.additionalOptions.each { option ->
                    commandLine.add(option)
                }
            }

            // Add source file
            commandLine.addAll([
                "(",
                sourcePath,
                ")"
            ])
        }
    }

    // Add title and track order. The segment title defaults to the file name,
    // as it always has; general.title overrides it independently of the video
    // track name above.
    commandLine.addAll([
        "--title",
        config?.general?.containsKey('title') ? subst(config.general.title) : "${-> fileName}",
        "--track-order",
        effectiveTrackOrder
    ])

    return commandLine
}

def currentDir = new File(".")

// Unix shells expand "*.mkv" before the script ever sees it, but cmd.exe passes
// the literal string through, so the expansion has to happen here to behave the
// same on both. A pattern that names an existing file is taken literally — that
// is the only way to select a file whose own name contains glob metacharacters,
// e.g. "Show.S01E0[1].mkv". Everything else is a glob, matched against the bare
// file name so a pattern never has to account for the leading "./".
def compileMasks = { List<String> patterns ->
    patterns.collect { pattern ->
        if (new File(pattern).isFile()) {
            def literal = new File(pattern).name
            return { File candidate -> candidate.name == literal }
        }
        def matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.replace('\\', '/'))
        return { File candidate -> matcher.matches(Paths.get(candidate.name)) }
    }
}

def includeMatchers = compileMasks(fileMasks)
def excludeMatchers = compileMasks(excludeMasks)

// Read files first to be able to write to the same directory. Sorted by name so
// that a batch is processed in a predictable order rather than whatever order
// the filesystem happens to return.
def files = ((currentDir.listFiles({ it.isFile() } as FileFilter) as List<File>) ?: []).sort { it.name }

if (includeMatchers) {
    files = files.findAll { file -> includeMatchers.any { it(file) } }
}
if (excludeMatchers) {
    files = files.findAll { file -> !excludeMatchers.any { it(file) } }
}

// A mask that matches nothing must say so. Falling through to a bare "Done"
// after a typo'd pattern looks identical to a successful run that had no work.
if ((fileMasks || excludeMasks) && !files) {
    println ui.yellow("*** No files match: ${(fileMasks + excludeMasks.collect { "--exclude $it" }).join(', ')}")
    println()
    ui.success("*** Done")
    return
}

// Companion files are resolved per episode through the ${fileName} placeholder,
// so a dub or subtitle release covering only part of a season shows up as an
// mkvmerge failure partway through a long batch. Check them all up front and
// drop just the affected episodes: those would have failed anyway, and the rest
// of the batch is still worth muxing.
// Validation, stage two: a variable that is perfectly valid but has no data for
// one particular episode — a season where TheMovieDB is missing episode 25, a
// stray file with no episode number in its name. That is data-shaped and
// per-file, so it drops the affected episodes and muxes the rest, exactly like
// the companion check below; a typo, which would affect every file, was already
// fatal above. --strict turns it into an abort, as it does for the check.
if (usedFileVars) {
    def unresolvedByVar = new LinkedHashMap()
    def blocked = [] as Set

    files.findAll { allowedExtensions.contains(FilenameUtils.getExtension(it.name.toLowerCase())) }
         .each { file ->
             def missing = fileVarsFor(file).missing.intersect(usedFileVars)
             missing.each { unresolvedByVar.get(it, []) << file.name }
             if (missing) blocked << file.name
         }

    if (blocked) {
        if (strict) {
            System.err.println ui.red("*** Strict mode: aborting (${plural(blocked.size(), 'file')} with " +
                                      "unresolved substitution variables).")
            System.exit(2)
        }
        println ui.yellow("*** ${plural(blocked.size(), 'file')} will be skipped: " +
                          "substitution variables have no value")
        if (episodeSource == null) {
            println "      no episodes.yaml or episodes.txt in this directory"
        }
        unresolvedByVar.each { name, names ->
            println "      \${${name}}  (unresolved for ${plural(names.size(), 'file')})"
            formatFileList(names, '        ').each { println it }
        }
        println()

        files = files.findAll { !blocked.contains(it.name) }

        if (!files.any { allowedExtensions.contains(FilenameUtils.getExtension(it.name.toLowerCase())) }) {
            println ui.yellow("*** Nothing left to mux")
            println()
            ui.success("*** Done")
            return
        }
    }
}

def companionSources = config?.additionalSources ?: []
if (companionSources) {
    def missingBySource = new LinkedHashMap()
    def blocked = [] as Set

    files.findAll { allowedExtensions.contains(FilenameUtils.getExtension(it.name.toLowerCase())) }
         .each { file ->
             def fileVars = fileVarsFor(file).vars
             companionSources.each { source ->
                 if (!new File(substitute(source.file.toString(), fileVars)).isFile()) {
                     missingBySource.get(source.file, []) << file.name
                     blocked << file.name
                 }
             }
         }

    if (blocked) {
        println ui.yellow("*** ${plural(blocked.size(), 'file')} will be skipped: companion files are missing")
        missingBySource.each { pattern, names ->
            println "      ${pattern}  (missing for ${plural(names.size(), 'file')})"
            formatFileList(names, '        ').each { println it }
        }
        println()

        files = files.findAll { !blocked.contains(it.name) }

        if (!files.any { allowedExtensions.contains(FilenameUtils.getExtension(it.name.toLowerCase())) }) {
            println ui.yellow("*** Nothing left to mux")
            println()
            ui.success("*** Done")
            return
        }
    }
}

// `mkvmerge -J` over a season takes a couple of seconds; muxing takes minutes
// per file. Probing first is essentially free, so the check runs by default.
def mediaFiles = files.findAll {
    allowedExtensions.contains(FilenameUtils.getExtension(it.name.toLowerCase()))
}

// An empty batch must say why, in every mode. A bare green "Done" after
// pointing the script at the wrong directory looks identical to a successful
// run that had no work. (Masks that match nothing at all were already
// reported above; this catches matches that are not media files.)
if (!mediaFiles) {
    if (fileMasks || excludeMasks) {
        println ui.yellow("*** No media files match: ${(fileMasks + excludeMasks.collect { "--exclude $it" }).join(', ')}")
    } else {
        println ui.yellow("*** No media files (${allowedExtensions.sort().join(', ')}) in the current directory")
    }
    println()
    ui.success("*** Done")
    return
}

def wantCheck = !noCheck
if (mediaFiles && wantCheck) {
    // Probing runs `mkvmerge -J` per file, which is seconds of silence on a
    // slow share for a full season. Print a live tick so it never looks hung.
    def probeProgress = ui.progress("*** Reading ${plural(mediaFiles.size(), 'file')}", mediaFiles.size())
    mediaFiles.each { probedInfos[it] = probeFile(it); probeProgress.tick() }
    probeProgress.finish()
    println()
}

def blockingCount = 0
if (mediaFiles && wantCheck) {
    blockingCount = runConsistencyCheck(mediaFiles, probedInfos)
}

if (blockingCount > 0 && strict) {
    System.err.println ui.red("*** Strict mode: aborting (${plural(blockingCount, 'discrepancy', 'discrepancies')} " +
                              "affecting selected tracks).")
    System.err.println ui.red("*** Nothing was muxed. Fix config.yaml or the inputs, or drop --strict to continue.")
    System.exit(2)
}

Process proc = null

addShutdownHook {
    if (proc != null) {
        println "*** Killing mkvtoolnix process ${-> proc.pid()}"
        proc.destroy()
    }
}

// mkvmerge only creates a missing output directory in recent versions (older
// ones fail to open the output file), so create it here — but not on a dry run,
// which must leave the filesystem untouched
if (!dryRun) {
    new File(destinationDir).mkdirs()
}

files.forEach { file ->
    {
        extension = FilenameUtils.getExtension(file.getName().toLowerCase())

        if (allowedExtensions.contains extension) {
            ui.header("*** Processing ${file.name}")
            println()

            fileName = FilenameUtils.getBaseName(file.name)

            // Build command line based on configuration
            def commandLine = buildCommandLine(file)

            if (dryRun) {
                // Force the lazy GStrings now that fileName/extension are set
                println "*** Dry run, would execute:"
                println commandLine.collect { it.toString() }
                                   .collect { it.contains(' ') ? "\"$it\"" : it }
                                   .join(' ')
                println()
                return
            }

            proc = commandLine.execute()
            proc.consumeProcessOutput(System.out, System.err)
            proc.waitFor()

            if (proc.exitValue() != 0) {
                println()
                ui.error("mkvmerge exited with code ${proc.exitValue()}")
            }

            proc = null
        } else {
            println "*** Skipping ${file.name}"
        }
    }
}

println()
ui.success("*** Done")
