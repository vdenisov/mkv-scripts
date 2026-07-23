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
@CommandLine.Command(name = "mkv-inspect", mixinStandardHelpOptions = true,
                     description = "Inspect media files: per-file track tables and a batch consistency report. " +
                                   "Never modifies anything.")
@PicocliScript2

@CommandLine.Option(names = ["-c", "--config"], paramLabel = "PATH",
                    description = "Path to a config file (default: config.yaml in the current directory if " +
                                  "present). Optional: with one, findings are classified against the tracks it " +
                                  "selects and configured sources are resolved per episode")
@Field String configPath = null

@CommandLine.Option(names = ["--identify"],
                    description = "Print a track table for every matching file (default mode is --check)")
@Field boolean identifyOnly = false

@CommandLine.Option(names = ["--check"],
                    description = "Compare track structure across all matching files (the default when no mode " +
                                  "is given; name it explicitly to combine with --identify)")
@Field boolean checkOnly = false

@CommandLine.Option(names = ["--check-verbose"],
                    description = "List every file in the consistency report instead of truncating long lists")
@Field boolean checkVerbose = false

@CommandLine.Option(names = ["--strict"],
                    description = "Exit 2 when the consistency check finds a discrepancy affecting a track that " +
                                  "the config selects")
@Field boolean strict = false

@CommandLine.Option(names = ["--color"], paramLabel = "WHEN",
                    description = "Colorize output: auto (default, only on a terminal and not under NO_COLOR), " +
                                  "always, or never")
@Field String colorMode = "auto"

@CommandLine.Option(names = ["-x", "--exclude"], paramLabel = "PATTERN",
                    description = "File name or glob pattern to skip; may be given more than once")
@Field List<String> excludeMasks = []

@CommandLine.Parameters(index = "0..*", arity = "0..*", paramLabel = "FILE",
                        description = "File names or glob patterns to inspect; may be given more than once " +
                                      "(default: every media file in the current directory)")
@Field List<String> fileMasks = []

// Shared console-output helpers (colours, error/warning forms), resolved relative
// to this script's own location — never the CWD, which is the media directory.
// First script-body statement on purpose: closures below capture `ui` from their
// enclosing scope, so it has to exist before any of them is defined.
def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def ui = evaluate(new File(scriptDir, 'lib/output.groovy'))(colorMode)

// Inspection describes files as they are, so a config is optional throughout: it
// only adds the classification of findings against selected tracks and the
// per-episode resolution of configured sources.
//
// **Nothing about a config stops this script.** Missing, unreadable, malformed,
// not even a mapping — each is reported and the run continues without it, for
// the same reason an unreadable media file is reported rather than fatal: the
// job is to show what can be shown. A stale config.yaml lying in the directory
// must not stand between you and the track table you came for — least of all
// when reading that table is how you would fix the config. Only --strict turns
// any of this into a non-zero exit.
// The three ways a YAML mapping can be unusable are classified in yaml.groovy,
// shared with mux.groovy; what to do about one is this script's own call, and
// here it is never anything but a warning.
def loadYaml = evaluate(new File(scriptDir, 'lib/yaml.groovy'))({ String text -> new Yaml().load(text) })

def configProblems = 0
def configFile = configPath ? new File(configPath) : new File("config.yaml")
def config = null
if (configFile.isFile()) {
    def loaded = loadYaml.loadMapping(configFile)
    if (loaded.problem) {
        ui.warn("${loaded.problem}; continuing without it.")
        System.err.println "***          Findings will not be classified against selected tracks."
        configProblems++
    }
    config = loaded.value
} else if (configPath) {
    ui.warn("Config file not found: ${configFile.absolutePath} - continuing without it.")
    configProblems++
}

def findMkvTool = evaluate(new File(scriptDir, 'lib/tools.groovy'))

// With no config the extension filter falls back to the common containers, so
// there is still something to inspect, and mkvmerge is auto-detected from PATH.
def DEFAULT_EXTENSIONS = ['mkv', 'mp4', 'm4v', 'avi', 'mov', 'ts', 'm2ts', 'webm', 'mka', 'mks'] as Set
def allowedExtensions = (config?.general?.allowedExtensions as Set) ?: DEFAULT_EXTENSIONS
def mkvmergeExe = (config?.general?.containsKey('mkvmergeExe') && config.general.mkvmergeExe)
    ? config.general.mkvmergeExe
    : findMkvTool('mkvmerge')

// Probing and the consistency report live in check.groovy, shared with mux.groovy
// so its pre-flight and this report can never drift.
def check = evaluate(new File(scriptDir, 'lib/check.groovy'))(
    [ui: ui, mkvmergeExe: mkvmergeExe, parseJson: { String text -> new JsonSlurper().parseText(text) }])

def probeFile = check.probeFile
def plural = ui.plural
def probedInfos = [:]

// Episode metadata, for resolving templated source paths per episode. Read here
// and passed in already parsed — snakeyaml stays in the scripts.
//
// episodes.yaml is hand-editable, so it fails the same three ways config.yaml
// does, and for the same reason as the block above none of them may stop a
// report about the media files. It is deliberately *not* counted into
// configProblems: --strict says "treat the findings as a failure", and episode
// metadata produces no findings — it only decorates the configured-source paths
// --identify resolves, which degrade to their unsubstituted selves without it.
def episodesHelper = evaluate(new File(scriptDir, 'lib/episodes.groovy'))
def episodeData = null
def episodesYaml = new File('episodes.yaml')
def episodesText = new File('episodes.txt')
if (episodesYaml.isFile()) {
    def loaded = loadYaml.loadMapping(episodesYaml,
                                      [charset: 'UTF-8', transform: episodesHelper.normalizeYaml])
    if (loaded.problem) ui.warn("${loaded.problem}; continuing without episode metadata.")
    episodeData = loaded.value
} else if (episodesText.isFile()) {
    episodeData = [byEpisode: episodesHelper.indexFromLines(episodesText.readLines(), 1)]
}

def substEngine = evaluate(new File(scriptDir, 'lib/subst.groovy'))(
    [ui: ui, episodes: episodesHelper, episodeData: episodeData])
def substitute = substEngine.substitute
def fileVarsFor = substEngine.fileVarsFor

// Stage-one template validation runs here too — finding a typo while inspecting
// is the cheapest place to find it — but as a warning, not a verdict: a broken
// ${epsiodeName} says nothing about the files this run is here to describe.
configProblems += substEngine.validateTemplates(substEngine.collectTemplateFields(config as Map),
                                                [fatal: false]).problems

// External-file discovery: the dubs and subtitle variants that live in
// subdirectories or as suffixed siblings rather than next to the main file. One
// recursive walk, shared by --identify and the coverage report; it probes
// nothing by itself.
def discovery = evaluate(new File(scriptDir, 'lib/discovery.groovy'))(episodesHelper)

def discovered = null
def runDiscovery = { List mains ->
    if (discovered != null) return discovered
    def excluded = [] as Set
    // Muxed output carries the same base names as its sources, so an output
    // directory left in place would come back as an external file of itself.
    def destinationDir = config?.general?.destinationDir
    if (destinationDir) {
        def dest = new File(destinationDir.toString())
        if (dest.isDirectory()) excluded << dest.canonicalPath
    }
    discovered = discovery.discoverCompanions(mains, discovery.walkTree(new File('.'), excluded),
                                              [mainExtensions: allowedExtensions])
    discovered
}

// One `mkvmerge -J` per path, whoever asks for it: discovered external files and
// the configured additionalSources both come through here, so nothing is probed
// twice and everything can be counted into the progress meter up front.
def pathProbeCache = [:]
def probeCached = { File f ->
    def key = f.absolutePath
    if (!pathProbeCache.containsKey(key)) pathProbeCache[key] = f.isFile() ? probeFile(f) : null
    pathProbeCache[key]
}

// Discovered files are probed per extension class (see discovery.groovy): only
// the formats that actually carry metadata mkvmerge can read are worth a
// subprocess. The extension is passed in rather than re-derived: discovery
// already computed it once per file, and splitting the name a second time here
// is a second implementation to keep in step with that one.
def probeExternal = { File f, String ext ->
    discovery.PROBE_EXTENSIONS.contains(ext) ? probeCached(f) : null
}

// Configured sources (additionalSources) resolved per episode: [path, file] per
// entry, main file as the key. Declared here, filled by the probing pass far
// below, because identifyFile captures it from the enclosing scope at definition
// time — the same reason probedInfos is declared up here. Named
// configuredSourcePaths, not configuredSources, so it cannot collide with a
// closure-local of that name.
def configuredSourcePaths = [:]

// Cells are padded before any colour is applied, so escapes never count toward
// the column width.
def pad = { value, int width -> String.format("%-${width}s", value == null ? '' : value.toString()) }

// One display row per track of an external file. A probed value always wins,
// field by field; what is missing falls back to the extension (codec) and to the
// path/suffix language guess, which is marked with a trailing '?' so it can
// never be mistaken for a tag the file actually carries.
//
// 'und' counts as missing. Matroska has no way to say "untagged" other than
// 'und', and an untagged .mka is the common case in the releases this exists
// for — a directory of Russian dubs where three of five files report 'und'
// tells you nothing the directory name did not already say.
def externalRows = { Map entry, Map variant ->
    def guess = variant.langGuess ? "${variant.langGuess}?".toString() : '-'
    def probed = probeExternal(entry.file, entry.ext)

    // A file we tried to read and could not is the one thing --identify exists to
    // surface, so it is said outright rather than being folded into the
    // never-probed path, where a truncated .mka would print as a healthy track.
    if (probed != null && !probed.ok) {
        return [[note: "(mkvmerge could not read this file: ${probed.reason})"]]
    }
    def tracks = probed?.ok ? (probed.raw.tracks ?: []) : null
    if (probed?.ok && !tracks) return [[note: '(no tracks)']]

    if (!tracks) {
        // mkvmerge numbers the single track of a raw .ass or .srt as 0, and that
        // is the id an additionalSources entry has to name, so print it rather
        // than dashing it out for not having been probed.
        return [[id     : 0,
                 type   : discovery.typeClassOf(entry.ext),
                 codec  : discovery.CODEC_BY_EXTENSION[entry.ext] ?: entry.ext.toUpperCase(),
                 lang   : guess,
                 guessed: variant.langGuess != null,
                 deflt  : '-', forced: '-', name: '']]
    }

    tracks.collect { track ->
        def props = track.get('properties') ?: [:]
        def lang = props.get('language')
        if (lang == 'und') lang = null
        [id     : track.id,
         type   : track.type ?: '?',
         codec  : track.codec ?: '?',
         lang   : lang ?: guess,
         guessed: !lang && variant.langGuess != null,
         deflt  : props.get('default_track') ? 'yes' : 'no',
         forced : props.get('forced_track') ? 'yes' : 'no',
         name   : props.get('track_name') ?: '']
    }
}

// The legend, printed once above the per-file tables: label, kind, variant name,
// the path pattern it follows and how many files it holds. One row per kind, so a
// merged variant (audio in one directory, subtitles in another) shows both under
// the same label.
def printExternalLegend = { Map result ->
    if (!result.variants) return
    def rows = []
    result.variants.each { v ->
        v.sections.each { sec -> rows << [v.label, sec.typeClass, v.name, sec.pattern, sec.entries.size()] }
    }
    def nameWidth = Math.min(40, Math.max(12, rows.collect { it[2].length() }.max()))
    def patternWidth = Math.min(60, Math.max(20, rows.collect { it[3].length() }.max()))

    ui.header("*** External files: ${plural(result.variants.size(), 'variant')} discovered")
    println ui.cyan("  ${pad('LBL', 4)} ${pad('TYPE', 10)} ${pad('VARIANT', nameWidth)} " +
                    "${pad('PATTERN', patternWidth)} FILES")
    rows.each { r ->
        println "  ${pad(r[0], 4)} ${pad(r[1], 10)} ${pad(r[2], nameWidth)} ${pad(r[3], patternWidth)} ${r[4]}"
    }
    println()
}

// Everything discovered for one episode, ordered by label so the blocks under
// each file read in the same order as the legend.
def externalsFor = { Map result, File main ->
    def out = []
    result.variants.each { v ->
        v.entries.findAll { it.main.absolutePath == main.absolutePath }
                 .each { out << [variant: v, entry: it] }
    }
    out.sort { "${it.variant.label}${it.entry.relPath}" }
}

// The external files attached to one episode, as slots the consistency check can
// group by exactly as it groups internal tracks. The slot key is the variant, the
// kind of file and its extension, never an index: external files have no order,
// and what the reader tracks through a season is "does this episode have the
// [Омикрон] dub", not "what is at position 2".
//
// This is what makes the check answer the question it is really asked. Two
// episodes with identical .mkv files but different dubs available are two muxing
// passes, and a report that grouped them together would say one config where two
// are needed.
//
// The extension is part of the key because one variant can hand a single episode
// two files of the same kind — a group shipping both .ass and .srt for E01 and
// only .ass for E02. Keyed on the kind alone they collide and the second silently
// overwrites the first, so the two episodes come out looking like one pass while
// the legend two lines up correctly says one of them holds two files. It also
// separates a variant that switched format mid-season, which is a real second
// pass: mkvmerge is being handed a different file.
def externalSlotsFor = { Map result, File main ->
    def slots = new LinkedHashMap()
    externalsFor(result, main).each { hit ->
        def variant = hit.variant
        def typeClass = discovery.typeClassOf(hit.entry.ext)
        def key = "${variant.label}/${typeClass}/${hit.entry.ext}".toString()
        def rows = externalRows(hit.entry, variant)
        def row = rows.find { !it.note }

        // A file mkvmerge could not read still occupies its slot — the episode
        // has it, and pretending otherwise would move the file into a different
        // muxing group over a defect that is reported elsewhere.
        slots[key] = [type    : typeClass,
                      codec   : row?.codec ?: discovery.CODEC_BY_EXTENSION[hit.entry.ext] ?: hit.entry.ext.toUpperCase(),
                      language: row?.lang ?: '-',
                      // Carried for display, deliberately outside SIG_KEYS so it
                      // cannot enter a group key (the same as videoName): the check
                      // report grays a guessed language, matching --identify and
                      // the documented palette. All files in a slot share one
                      // extension, so this is uniform within the slot.
                      guessed : row?.guessed ?: false,
                      name    : row?.name ?: '',
                      default : row?.deflt == 'yes',
                      forced  : row?.forced == 'yes',
                      label   : variant.label,
                      slot    : variant.name]
    }
    slots
}

// What was found but belongs to nothing. Names only: these are never probed and
// never treated as sources, they are listed so their presence is not a surprise.
def printLeftovers = { Map result ->
    if (result.unmatched) {
        ui.header("*** Unmatched external files (${result.unmatched.size()})")
        check.formatFileList(result.unmatched.collect { it.relPath }, '      ').each { println it }
        println()
    }
    if (result.extras) {
        ui.header("*** Extras: ${plural(result.extras.size(), 'file')} of a main type in subdirectories, " +
                  "not scanned as sources")
        check.formatFileList(result.extras.collect { it.relPath }, '      ').each { println it }
        println()
    }
}

// Print a readable track table for one file, for --identify.
def identifyFile = { File file, Map info, Map discoveredResult = null ->
    ui.header("*** ${file.name}")

    if (info == null || !info.ok) {
        println "  (mkvmerge could not identify this file: ${info?.reason ?: 'unknown error'})"
        println()
        return
    }

    def tracks = info.raw.tracks
    if (!tracks) {
        println "  (no tracks)"
        println()
        return
    }

    println ui.cyan(String.format("  %-4s %-10s %-22s %-5s %-4s %-4s %s",
                                  'ID', 'TYPE', 'CODEC', 'LANG', 'DEF', 'FOR', 'NAME'))

    tracks.each { track ->
        def props = track.get('properties') ?: [:]
        printf("  %-4s %-10s %-22s %-5s %-4s %-4s %s%n",
               track.id,
               track.type ?: '?',
               track.codec ?: '?',
               props.get('language') ?: '?',
               props.get('default_track') ? 'yes' : 'no',
               props.get('forced_track') ? 'yes' : 'no',
               props.get('track_name') ?: '')
    }

    // Sources declared in the config, resolved for this episode. The resolved
    // path is printed as well as its tracks: with templated paths, what a pattern
    // actually expands to per episode is half of what one wants to see here.
    // Never fatal — --identify describes what is there, so a source that is
    // missing is a line in the report, not an error.
    // Every table on this page shares one column grid — main tracks, configured
    // sources, discovered files — so the columns line up straight down the report
    // instead of each block starting its own little table. The "+" header lines
    // are what separates the blocks; a blank line per block as well made six
    // externals fill a screen.
    // Resolved and probed in the batch's own probing pass, not here: N episodes
    // times M sources is N*M subprocesses, and running them after the meter has
    // printed a completed bar is exactly the silent wait the meter exists to
    // remove.
    def sources = configuredSourcePaths[file] ?: []
    if (sources) println()
    sources.each { source ->
        def companion = source.file
        println ui.cyan("  + ${source.path}")
        if (!companion.isFile()) {
            println "    (not found)"
            return
        }
        def probed = probeCached(companion)
        if (!probed.ok) {
            println "    (mkvmerge could not identify this file: ${probed.reason})"
            return
        }
        (probed.raw.tracks ?: []).each { track ->
            def props = track.get('properties') ?: [:]
            printf("  %-4s %-10s %-22s %-5s %-4s %-4s %s%n",
                   track.id,
                   track.type ?: '?',
                   track.codec ?: '?',
                   // A raw .ass or .srt has no language and no codec_id at all,
                   // so this cell is routinely empty for external files.
                   props.get('language') ?: '-',
                   props.get('default_track') ? 'yes' : 'no',
                   props.get('forced_track') ? 'yes' : 'no',
                   props.get('track_name') ?: '')
        }
    }

    // Discovered external files, bundled under this episode by variant label.
    // The path is not repeated per episode — the legend above carries it — except
    // for a file matched only by episode number, where the name is exactly what
    // is worth seeing.
    def hits = discoveredResult ? externalsFor(discoveredResult, file) : []
    if (hits) println()
    // One block per variant, not per file: a merged variant contributing both a
    // dub and a subtitle file to this episode is one thing with two files, and
    // reads as one thing with its extensions named in the header.
    hits.groupBy { it.variant.label }.each { label, group ->
        def variant = group[0].variant
        def extensions = group.collect { ".${it.entry.ext}" }.unique().join(', ')
        def head = "  + [${label}] ${variant.name} (${extensions})"
        def byNumber = group.findAll { it.entry.tier == 2 }
        if (byNumber) {
            println head + ui.gray("  (episode match: ${byNumber.collect { it.entry.file.name }.join(', ')})")
        } else {
            println head
        }
        group.collectMany { externalRows(it.entry, it.variant) }.each { r ->
            if (r.note) {
                println "    ${r.note}"
                return
            }
            // NAME is last and unpadded, so an unnamed track would otherwise
            // leave the line with nothing but trailing blanks to show for it.
            def row = "  ${pad(r.id, 4)} ${pad(r.type, 10)} ${pad(r.codec, 22)} " +
                      "${r.guessed ? ui.gray(pad(r.lang, 5)) : pad(r.lang, 5)} " +
                      "${pad(r.deflt, 4)} ${pad(r.forced, 4)} ${r.name}"
            println row.replaceAll(/\s+$/, '')
        }
    }
    println()
}

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

def currentDir = new File(".")
def files = ((currentDir.listFiles({ it.isFile() } as FileFilter) as List<File>) ?: []).sort { it.name }

def isMedia = { File f -> allowedExtensions.contains(FilenameUtils.getExtension(f.name.toLowerCase())) }

// External files are matched against every episode in the directory, not only
// the masked ones: otherwise narrowing to one episode would dump the whole
// season's dubs into "unmatched". What the masks narrow is what gets *reported*.
def allMedia = files.findAll(isMedia)

if (includeMatchers) {
    files = files.findAll { file -> includeMatchers.any { it(file) } }
}
if (excludeMatchers) {
    files = files.findAll { file -> !excludeMatchers.any { it(file) } }
}

def mediaFiles = files.findAll(isMedia)

// An empty batch must say why. A bare green "Done" after pointing the script at
// the wrong directory looks identical to a successful run that had no work.
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

// --check is the default mode: the batch-level report is what one usually wants,
// and --identify is the per-file drill-down. Naming both runs both.
def wantCheck = checkOnly || checkVerbose || !identifyOnly

// Which files are in a layout group, said as compactly as the names allow:
// episode ranges when the batch is numbered, the file names themselves when it
// is not. Display only — see episodes.groovy for why this is not identity, and
// why the composition lives there rather than here and in mux.groovy both.
// The parameter is `batch`, not `files`: both scripts already have a script-level
// `files`, and a closure parameter of the same name is a compile error here — the
// same collision the probedInfos/infos note in CLAUDE.md warns about.
def membershipFor = { List batch ->
    episodesHelper.membershipLabel(batch.collect { FilenameUtils.getBaseName(it.name) }, ui.pluralize)
}

// Discovery first: it is name matching only, no subprocesses, and it decides
// which external files are worth probing.
def externals = runDiscovery(allMedia)

// Everything is probed in one pass, up front, so the report is built from what
// the files actually say rather than from what their names suggest. Probing runs
// `mkvmerge -J` per file, which is seconds of silence on a slow share for a full
// season, so the tick counts both kinds and keeps moving.
def probeWorthy = externals.variants
    .collectMany { it.entries }
    .findAll { discovery.PROBE_EXTENSIONS.contains(it.ext) && it.main in mediaFiles }
    .unique { it.file.absolutePath }

// The configured sources --identify prints, resolved per episode. The raw
// resolved string is kept alongside the File: it is what gets printed, and
// putting it through File would normalise the separators of a path the config
// wrote by hand.
if (identifyOnly) {
    mediaFiles.each { file ->
        configuredSourcePaths[file] = (config?.additionalSources ?: []).findAll { it?.file }.collect {
            def path = substitute(it.file.toString(), fileVarsFor(file).vars)
            [path: path, file: new File(path)]
        }
    }
}

// Everything that costs a subprocess goes into one list, so the meter's total is
// the real one and nothing runs on after it says it is finished.
def companionProbes = (probeWorthy.collect { it.file } +
                       configuredSourcePaths.values().flatten().collect { it.file }.findAll { it.isFile() })
    .unique { it.absolutePath }

def probeLabel = "*** Reading ${plural(mediaFiles.size(), 'file')}" +
                 (companionProbes ? " + ${plural(companionProbes.size(), 'companion file')}" : '')
def probeProgress = ui.progress(probeLabel, mediaFiles.size() + companionProbes.size())
mediaFiles.each { probedInfos[it] = probeFile(it); probeProgress.tick() }
companionProbes.each { probeCached(it); probeProgress.tick() }
probeProgress.finish()
println()

// The external files each episode carries, as slots the check groups by. Built
// after probing so the values compared are the real ones.
mediaFiles.each { probedInfos[it] = probedInfos[it] + [externals: externalSlotsFor(externals, it)] }

// The legend belongs to both modes, not just --identify: the coverage section in
// the check report labels its rows [A], [B], … too, and a label whose key was
// never printed is a reference to nothing.
printExternalLegend(externals)

def blockingCount = 0
if (wantCheck) {
    blockingCount = check.runConsistencyCheck(mediaFiles, probedInfos,
                                              check.makeSelection(config as Map) +
                                              [verbose      : checkVerbose,
                                               headerLabel  : 'Consistency check',
                                               membershipFor: membershipFor])
}

if (identifyOnly) {
    mediaFiles.each { identifyFile(it, probedInfos[it], externals) }
}

printLeftovers(externals)

// --strict is the only thing that can make this script exit non-zero: it is the
// caller saying "treat what you found as a failure", which is the one context in
// which a report is also a verdict. A config we could not read counts, since the
// findings above were classified without the selections it was supposed to
// supply.
if (strict && (blockingCount > 0 || configProblems > 0)) {
    if (blockingCount > 0) {
        System.err.println ui.red("*** Strict mode: ${plural(blockingCount, 'discrepancy', 'discrepancies')} " +
                                  "affecting selected tracks.")
    }
    if (configProblems > 0) {
        System.err.println ui.red("*** Strict mode: ${plural(configProblems, 'config problem')} " +
                                  "(the report above was not classified against a config).")
    }
    System.exit(2)
}

ui.success("*** Done")
