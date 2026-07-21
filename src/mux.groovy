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

@CommandLine.Option(names = ["--identify"],
                    description = "Print a track table for every matching file and exit without muxing")
@Field boolean identifyOnly = false

@CommandLine.Option(names = ["-n", "--dry-run"],
                    description = "Print the mkvmerge command line for every matching file without executing it")
@Field boolean dryRun = false

@CommandLine.Option(names = ["--check"],
                    description = "Compare track structure across all matching files and exit without muxing")
@Field boolean checkOnly = false

@CommandLine.Option(names = ["--no-check"],
                    description = "Skip the automatic pre-flight consistency check before muxing")
@Field boolean noCheck = false

@CommandLine.Option(names = ["--strict"],
                    description = "Abort instead of warning when the consistency check finds a discrepancy " +
                                  "affecting a track that config.yaml selects")
@Field boolean strict = false

@CommandLine.Option(names = ["--check-verbose"],
                    description = "List every file in the consistency report instead of truncating long lists")
@Field boolean checkVerbose = false

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
def ui = evaluate(new File(scriptDir, 'output.groovy'))(colorMode)

// Config lives with the media: config.yaml in the current directory, or an
// explicit --config path. There is deliberately no fall-back to a config beside
// the script — the file shipped there (config.example.yaml) is a template, and
// silently applying it to whatever directory you are in produced confidently
// wrong output (its track selections never match). Copy it next to your media,
// or point --config at your own.
//
// The inspection modes (--identify, --check, --check-verbose) describe the files
// as they are and do not mux, so they run without a config — useful for checking
// a season's structure before writing the config against it. Only a run that
// actually muxes requires one. (--check-verbose implies --check, but the
// checkOnly reassignment for that happens later, so it is named here directly.)
def inspecting = identifyOnly || checkOnly || checkVerbose
def configFile = configPath ? new File(configPath) : new File("config.yaml")
def config = null
if (configFile.isFile()) {
    config = new Yaml().load(configFile.text)
} else if (!inspecting) {
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
def findMkvTool = evaluate(new File(scriptDir, 'tools.groovy'))

// Extract general settings from config. All are null-safe: when inspecting
// without a config, destinationDir is unused (nothing is muxed), the extension
// filter falls back to the common video containers so there is still something
// to inspect, and mkvmergeExe is auto-detected from PATH.
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

// One indented file name per line, truncating past `limit`. File names are long
// and comma-joining several per line runs them together; a plain column is far
// easier to skim and to count. ASCII only ("..." not "…"), since this output
// lands on Windows consoles running a legacy codepage.
//
// Declared here, before every closure that calls it: a closure resolves a
// script-level `def` local through its enclosing scope, so the variable has to
// exist by the time the calling closure is created, not merely by the time it
// runs.
def formatFileList = { List<String> names, String indent, int limit = 8 ->
    def show = Math.min(limit, names.size())   // limit may be Integer.MAX_VALUE (verbose)
    def lines = names.take(show).collect { indent + it }
    if (names.size() > show) lines << "${indent}... and ${names.size() - show} more"
    lines
}

// Fields compared per track by the consistency check. This is a blocklist model:
// everything mkvmerge reports that should be stable across a season is compared,
// and what legitimately varies per episode is left out. Duration, file size,
// muxing application and writing library are never read at all. The video
// track's name is excluded below rather than here, because it routinely carries
// the episode title and so differs by design.
//
// default/forced are included because a flag that flips halfway through a season
// is exactly the silent wrong-output failure this check exists to catch.
def SIG_KEYS = ['type', 'codec', 'language', 'name', 'default', 'forced']

// NOTE: the per-track JSON key "properties" must be read with .get('properties').
// On Groovy 4+ both track.properties and track['properties'] resolve to the bean
// properties of the map object itself, not the JSON key of that name.
def trackSignature = { Map track ->
    def props = track.get('properties') ?: [:]
    def type = track.type ?: '?'
    [
        type    : type,
        codec   : track.codec ?: '?',
        language: props.get('language') ?: 'und',
        // Nulled at construction time so it can never leak into a group key
        name    : type == 'video' ? null : (props.get('track_name') ?: ''),
        default : props.get('default_track') ? true : false,
        forced  : props.get('forced_track') ? true : false
    ]
}

// One `mkvmerge -J` per file, parsed once. Both --identify and --check read this
// same record, so asking for both does not double the number of subprocesses.
def probeFile = { File file ->
    def proc = [mkvmergeExe, '-J', file.absolutePath].execute()
    def json = proc.inputStream.text
    proc.waitFor()

    if (proc.exitValue() != 0) {
        return [file: file, ok: false, reason: "mkvmerge exit ${proc.exitValue()}", tracks: [:], chapters: 0]
    }

    def parsed = new JsonSlurper().parseText(json)

    // mkvmerge -J exits 0 even for a file it cannot read, reporting the failure
    // in container.recognized/supported instead. Checking only the exit code
    // would let a corrupt file into the comparison as a file with no tracks,
    // which reads as "every track is absent here" and poisons the whole report.
    def container = parsed.container ?: [:]
    if (!container.recognized) {
        return [file: file, ok: false, reason: 'not recognised as a media file', tracks: [:], chapters: 0]
    }
    if (!container.supported) {
        return [file: file, ok: false, reason: 'container not supported by mkvmerge', tracks: [:], chapters: 0]
    }

    def tracks = new LinkedHashMap()
    (parsed.tracks ?: []).each { track ->
        def id = track.id as Integer
        tracks[id] = trackSignature(track) + [id: id]
    }

    def chapterCount = 0
    (parsed.chapters ?: []).each { chapterCount += (it.num_entries ?: 0) }

    [file: file, ok: true, raw: parsed, tracks: tracks, chapters: chapterCount]
}

// Print a readable track table for one file, for --identify.
def identifyFile = { File file, Map info ->
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
    println()
}

// ── Consistency check ───────────────────────────────────────────────────────
//
// config.yaml selects tracks by numeric ID, which assumes every episode has the
// same track layout. When that breaks — a translation added mid-season, an old
// one dropped, a different release group ordering tracks differently — mkvmerge
// does not complain. It muxes whatever sits at that ID, and the result is a
// season where some episodes have the wrong dub labelled as something else.

// Group files by the value they carry at each track ID.
//
// Deliberately does NOT anchor on the first file: the reference is the largest
// population. If a translation was dropped from episode 8 onward, anchoring on
// file one would report 17 files as deviant against a sample of one. Which group
// is correct is the user's call, not this script's.
def groupTracks = { List infos ->
    def allIds = (infos.collectMany { it.tracks.keySet() } as Set).sort()

    allIds.collect { id ->
        def byKey = new LinkedHashMap()
        infos.each { info ->
            def sig = info.tracks[id]
            def key = (sig == null) ? null : SIG_KEYS.collect { sig[it] }
            if (!byKey.containsKey(key)) byKey.put(key, [sig: sig, files: []])
            byKey.get(key).files << info.file.name
        }

        def groups = byKey.values().toList().sort { a, b ->
            (b.files.size() <=> a.files.size()) ?: (a.files[0] <=> b.files[0])
        }
        // On an even split nothing is the minority, so nothing gets singled out
        def maxSize = groups[0].files.size()
        groups.each { it.minority = it.files.size() < maxSize }

        def present = groups.findAll { it.sig != null }
        def varying = SIG_KEYS.findAll { k -> present.collect { it.sig[k] }.unique().size() > 1 }

        [id        : id,
         type      : present ? present[0].sig.type : '?',
         groups    : groups,
         varying   : varying,
         missing   : groups.find { it.sig == null }?.files ?: [],
         consistent: groups.size() == 1]
    }
}

// Two tracks are only genuinely ambiguous when type, language, codec AND name
// all match — including both being unnamed. AC-3 "English" and DTS "English" are
// perfectly distinguishable, and so is a track named "Director's Commentary".
// Flag only the case where ID-based selection cannot be reasoned about at all.
//
// Aggregated by (signature, ids) rather than reported per file, so a 24-file
// batch that all shares an ambiguity prints one note instead of twenty-four.
def findDuplicates = { List infos ->
    def acc = new LinkedHashMap()
    infos.each { info ->
        info.tracks.values()
            .findAll { it.type != 'video' }
            .groupBy { [it.type, it.language, it.codec, it.name] }
            .findAll { key, group -> group.size() > 1 }
            .each { key, group ->
                def ids = group.collect { it.id }.sort()
                def acckey = [key, ids]
                if (!acc.containsKey(acckey)) {
                    acc.put(acckey, [type: key[0], language: key[1], codec: key[2], name: key[3],
                                     ids: ids, files: []])
                }
                acc.get(acckey).files << info.file.name
            }
    }
    acc.values().toList()
}

// ── Blocking vs informational ───────────────────────────────────────────────
// A discrepancy only corrupts output when it lands on a track the config picks
// by ID. If every track of that type is being copied, IDs cannot select the
// wrong thing, so the difference is informational. buildCommandLine hardcodes
// 0: for video, hence the fixed video ID below.
// Empty when inspecting without a config: nothing is "selected", so the check
// prints structure only and skips the blocking/informational classification.
def selectedVideoIds = config ? ([0] as Set) : ([] as Set)
def selectedAudioIds = (config?.mainSource?.audioTracks ?: []).collect { it.id as Integer } as Set
def selectedSubIds = (config?.mainSource?.subtitleTracks ?: []).collect { it.id as Integer } as Set
def selectedIds = selectedVideoIds + selectedAudioIds + selectedSubIds

def configTitleFor = { Integer id ->
    ((config?.mainSource?.audioTracks ?: []) + (config?.mainSource?.subtitleTracks ?: []))
        .find { (it.id as Integer) == id }?.title
}

def copiesAllOfType = { String type, List infos ->
    def selected = (type == 'audio') ? selectedAudioIds
                 : (type == 'subtitles') ? selectedSubIds
                 : selectedVideoIds
    def seen = infos.collectMany { info ->
        info.tracks.values().findAll { it.type == type }.collect { it.id }
    } as Set
    !seen.isEmpty() && selected.containsAll(seen)
}

def isBlocking = { Integer trackId, String type, List infos ->
    selectedIds.contains(trackId) && !copiesAllOfType(type, infos)
}

// ── Report ──────────────────────────────────────────────────────────────────
// A short type name for a track, for the table and the layout descriptions.
def shortType = { String type -> type == 'subtitles' ? 'subs' : type }

// A file's track LAYOUT: the type at each ID, ignoring codec/name/flags. Two
// files share a layout when they have the same track IDs with the same type at
// each. This is what separates a genuinely different release (tracks in a
// different order, or one missing) from the same release with a value changed.
def layoutKey = { Map info ->
    info.tracks.sort { it.key }.collect { id, sig -> "${id}:${sig.type}" }.join(' ')
}
def layoutDesc = { Map info ->
    info.tracks.sort { it.key }.collect { id, sig -> "${id} ${shortType(sig.type)}" }.join('   ')
}

// Truncate an over-long track name so it cannot break the table's alignment.
// ASCII "..." rather than an ellipsis, for the same Windows-console reason.
def fitName = { String name, int width ->
    name.length() > width ? name[0..<(width - 3)] + '...' : name
}

def plural = ui.plural

// The differing-cell highlight in the check tables. Terminal detection and the
// --color/NO_COLOR gating live in output.groovy, shared with the other scripts.
def hl = ui.yellow

// A fixed-width table cell, padded *before* any colour is applied so the ANSI
// escapes never count toward the width and break alignment.
def cell = { value, int width, boolean diff ->
    def s = String.format("%-${width}s", value == null ? '' : value.toString())
    diff ? hl(s) : s
}

// Print a file list with the "<-" marker on the last named file, since the list
// sits above the row it describes — the marker adjacent to that row reads more
// clearly than one at the top, next to the unrelated row above. The rest of the
// list is a plain hanging indent, so a multi-line list is not mistaken for
// several groups. The "... and N more" summary (if any) stays below the marker.
def printMinority = { List<String> names, int limit ->
    def lines = formatFileList(names, '              ', limit)   // 14-space hanging indent
    if (!lines) return
    def markIdx = lines.findLastIndexOf { !it.contains('... and ') }
    if (markIdx < 0) markIdx = 0
    // The list is evidence, not primary data: gray, so the table rows stand
    // out against it. The marker keeps the default foreground — its job is to
    // stay findable inside the gray block — so this is the one place where a
    // line holds two colour segments (still whole segments, never mid-word).
    lines.eachWithIndex { line, i ->
        if (i == markIdx) {
            println('           <- ' + ui.gray(line.substring(14)))
        } else {
            println ui.gray(line)
        }
    }
}

// Row count is bounded by track count, not file count, so a 200-episode batch
// prints as compactly as a 3-episode one. All tracks are listed, not only the
// varying ones: this table doubles as the batch's authoritative track map, which
// is what you read to check config.yaml's numeric IDs against reality.
def runConsistencyCheck = { List mediaFiles, Map infos ->
    def ok = mediaFiles.collect { infos[it] }.findAll { it != null && it.ok }
    def bad = mediaFiles.collect { infos[it] }.findAll { it != null && !it.ok }

    def header = "*** Pre-flight check: ${ok.size()} file(s)"
    if (bad) header += " (${bad.size()} could not be identified by mkvmerge and are excluded)"
    ui.header(header)
    if (bad) {
        formatFileList(bad.collect { "${it.file.name} (${it.reason})".toString() }, '      ')
            .each { println it }
    }
    if (!ok) {
        println()
        return 0
    }
    println()

    def limit = checkVerbose ? Integer.MAX_VALUE : 8
    def blocking = []
    def informational = []

    // Split files by track layout — the type at each ID. Files that share a
    // layout are the same release and can be compared value-by-value; files with
    // a different layout (a shifted track order, or a missing track) are a
    // different release and get their own table. Ordered largest group first,
    // ties broken by name so the output is deterministic.
    def byLayout = ok.groupBy { layoutKey(it) }
    def layoutGroups = byLayout.values().toList()
        .sort { a, b -> (b.size() <=> a.size()) ?: (a[0].file.name <=> b[0].file.name) }
    def largest = layoutGroups[0]
    def largestTypeAt = { Integer id -> largest[0].tracks[id]?.type }

    // Size the NAME column to the longest name actually present, so it is not
    // clipped on wide screens nor padded to a fixed width when everything is
    // short. Clamped so one pathological title cannot blow the line width.
    def displayName = { Map sig -> sig.type == 'video' ? '-' : (sig.name ?: '(no name)') }
    def nameLengths = ok.collectMany { it.tracks.values().collect { displayName(it).length() } }
    def nameWidth = Math.min(60, Math.max(12, (nameLengths ?: [0]).max()))

    // Print one structural group's table. Within a group every ID has a single
    // type, so rows differ only in codec/language/name/flags. When an ID's value
    // varies it is split into a row per distinct value, largest first; the
    // differing cells are highlighted. In the largest group the minority rows
    // name their files just above themselves (the majority is the norm, unnamed);
    // outlier groups already list their files above the table, so they don't
    // repeat them per row.
    def printGroupTable = { List group, boolean isLargest ->
        def tgs = groupTracks(group)
        println ui.cyan("    ${cell('ID', 4, false)} ${cell('TYPE', 6, false)} ${cell('CODEC', 20, false)} " +
                        "${cell('LANG', 5, false)} ${cell('DEF', 4, false)} ${cell('FOR', 4, false)} NAME")

        // NAME is the last column, so it is not padded (no trailing whitespace);
        // it is the only cell that can be highlighted on its own here.
        def rowFor = { id, Map sig, Set diff ->
            def nm = sig.type == 'video' ? '-' : fitName(sig.name ?: '(no name)', nameWidth)
            "    ${cell(id, 4, false)} " +
            "${cell(shortType(sig.type), 6, diff.contains('type'))} " +
            "${cell(sig.codec, 20, diff.contains('codec'))} " +
            "${cell(sig.language, 5, diff.contains('language'))} " +
            "${cell(sig.default ? 'yes' : 'no', 4, diff.contains('default'))} " +
            "${cell(sig.forced ? 'yes' : 'no', 4, diff.contains('forced'))} " +
            "${diff.contains('name') ? hl(nm) : nm}"
        }

        tgs.each { tg ->
            if (tg.consistent) {
                println rowFor(tg.id, tg.groups[0].sig, [] as Set)
                return
            }
            def varying = tg.varying as Set
            def maxSize = tg.groups[0].files.size()
            // In the common (largest) group the strict-majority row is the
            // reference: unhighlighted and unnamed, since listing the norm would
            // be dozens of files. An outlier group has no reference — every value
            // is a deviation, so every row names its files.
            def strictMajority = isLargest && tg.groups.size() > 1 && tg.groups[1].files.size() < maxSize
            tg.groups.eachWithIndex { g, i ->
                def isReference = i == 0 && strictMajority
                if (!isReference) printMinority(g.files, limit)   // files above their row
                println rowFor(tg.id, g.sig, isReference ? ([] as Set) : varying)
            }
        }
    }

    def multi = layoutGroups.size() > 1
    layoutGroups.eachWithIndex { group, gi ->
        def uniform = groupTracks(group).every { it.consistent }
        if (multi) {
            def shape = group[0].tracks.sort { it.key }.collect { id, sig -> shortType(sig.type) }.join(', ')
            ui.header("*** Layout ${gi + 1} (${plural(group.size(), 'file')}): ${shape}")
            // A uniform outlier group has no per-row split to hang its files on,
            // so list them together here. The largest group is the norm and is
            // never enumerated; a non-uniform outlier names its files per row.
            if (gi > 0 && uniform) printMinority(group.collect { it.file.name }, limit)
        }
        printGroupTable(group, gi == 0)
        println()

        // Classification. Non-largest groups are structural outliers, blocking
        // when the layout change lands on a selected ID. The largest group is
        // classified on its per-ID value differences.
        if (gi > 0) {
            def affected = selectedIds.findAll { id -> group[0].tracks[id]?.type != largestTypeAt(id) }.sort()
            def verb = group.size() == 1 ? 'uses' : 'use'
            if (affected) {
                blocking << "${plural(group.size(), 'file')} ${verb} a different track layout, at " +
                            "selected track${affected.size() == 1 ? '' : 's'} ${affected.join(', ')}"
            } else {
                informational << "${plural(group.size(), 'file')} ${verb} a different track layout " +
                                 "(selected tracks unaffected)"
            }
        } else {
            groupTracks(group).findAll { !it.consistent }.each { tg ->
                def title = configTitleFor(tg.id)
                def label = "track ${tg.id} (${tg.type}${title ? ", config title \"${title}\"" : ''}) - " +
                            "${tg.varying.join(', ')} differ${tg.varying.size() == 1 ? 's' : ''} across ${tg.groups.size()} groups"
                if (isBlocking(tg.id as Integer, tg.type, largest)) blocking << label
                else informational << label
            }
        }
    }

    // Ambiguous duplicates and chapters are observations across the whole batch,
    // reported once regardless of layout.
    def duplicates = findDuplicates(ok)
    if (duplicates) {
        ui.header("*** Ambiguous track IDs")
        duplicates.each { dup ->
            def name = dup.name ? "\"${dup.name}\"" : 'no name'
            println "    Tracks ${dup.ids.join(' and ')} are both ${dup.type} / ${dup.language} / " +
                    "${dup.codec} with ${name}, in ${dup.files.size()} file(s)."
            println "    ID-based selection cannot distinguish them; check which one config.yaml means."
            def selected = dup.ids.findAll { isBlocking(it as Integer, dup.type, ok) }
            def label = "tracks ${dup.ids.join(', ')} are ambiguous" +
                        (selected ? " and config.yaml selects ${selected.size() > 1 ? 'tracks' : 'track'} ${selected.join(', ')}" : '')
            if (selected) blocking << label else informational << label
        }
        println()
    }

    def withChapters = ok.findAll { it.chapters > 0 }
    def withoutChapters = ok.findAll { it.chapters == 0 }
    if (withChapters && withoutChapters) {
        ui.header("*** Chapters: present in ${withChapters.size()} file(s), absent in ${withoutChapters.size()}")
        def minority = withChapters.size() < withoutChapters.size() ? withChapters : withoutChapters
        printMinority(minority.collect { it.file.name }, limit)
        informational << "chapters are present in some files and not others"
        println()
    }

    // Without a config there is nothing to classify against, so report the count
    // of differences (already detailed in the tables above) and point at how to
    // classify them. The per-item labels assume selected tracks, so they are only
    // printed when a config is present.
    if (config == null) {
        def findings = blocking + informational
        if (findings) {
            println ui.yellow("*** ${findings.size()} difference(s) across the batch (see the tables above).")
            println "***   Add a config.yaml, or --config <path>, to classify which affect selected tracks."
        } else {
            ui.success("*** Track structure is consistent across all ${ok.size()} file(s).")
        }
    } else {
        if (blocking) {
            println ui.yellow("*** ${blocking.size()} discrepanc${blocking.size() == 1 ? 'y affects a track' : 'ies affect tracks'} that config.yaml selects:")
            blocking.each { println "      ${it}" }
        }
        if (informational) {
            println "*** ${informational.size()} informational (does not affect what gets muxed):"
            informational.each { println "      ${it}" }
        }
        if (!blocking && !informational) {
            ui.success("*** Track structure is consistent across all ${ok.size()} file(s).")
        }
    }
    println()

    blocking.size()
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

// Resolve once, not per file, so the warnings are printed only once. Only needed
// (and only possible) when muxing — the inspection modes neither build a command
// line nor necessarily have a config, and the "derived order" note is just noise
// for them.
def effectiveTrackOrder
if (!inspecting) {
    if (config.containsKey('trackOrder') && config.trackOrder) {
        effectiveTrackOrder = config.trackOrder.toString()
        validateTrackOrder(effectiveTrackOrder)
    } else {
        effectiveTrackOrder = deriveTrackOrder()
        println "*** trackOrder not configured; using derived order: ${effectiveTrackOrder}"
        println()
    }
}

def fileName = null
def extension = null

// Build command line based on configuration; closure so it captures script-scope locals
def buildCommandLine = {
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
    def videoTrackLang = config.mainSource.videoTrack.language
    // Allow overriding video track name but use filename as default
    def videoTrackTitle = config.mainSource.videoTrack.containsKey('title') ? 
                          config.mainSource.videoTrack.title : 
                          "${-> fileName}"

    commandLine.addAll([
        "--language",
        "0:${-> videoTrackLang}",
        "--track-name",
        "0:${-> videoTrackTitle}"
    ])

    // Add audio tracks
    if (hasAudioTracks) {
        config.mainSource.audioTracks.each { track ->
            commandLine.addAll([
                "--language",
                "${track.id}:${track.language}",
                "--track-name",
                "${track.id}:${track.title}"
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
            commandLine.addAll([
                "--language",
                "${track.id}:${track.language}",
                "--track-name",
                "${track.id}:${track.title}"
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
            // Add track settings for this source
            source.tracks.each { track ->
                // Assume track ID 0 for additional tracks
                def trackId = 0

                commandLine.addAll([
                    "--language",
                    "${trackId}:${track.language}",
                    "--track-name",
                    "${trackId}:${track.title}"
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
                source.file.replace('${fileName}', fileName),
                ")"
            ])
        }
    }

    // Add title and track order
    commandLine.addAll([
        "--title",
        "${-> fileName}", // Use filename as title
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
def companionSources = config?.additionalSources ?: []
if (companionSources && !identifyOnly) {
    def missingBySource = new LinkedHashMap()
    def blocked = [] as Set

    files.findAll { allowedExtensions.contains(FilenameUtils.getExtension(it.name.toLowerCase())) }
         .each { file ->
             def base = FilenameUtils.getBaseName(file.name)
             companionSources.each { source ->
                 if (!new File(source.file.replace('${fileName}', base)).isFile()) {
                     missingBySource.get(source.file, []) << file.name
                     blocked << file.name
                 }
             }
         }

    if (blocked) {
        println ui.yellow("*** ${blocked.size()} file(s) will be skipped: companion files are missing")
        missingBySource.each { pattern, names ->
            println "      ${pattern}  (missing for ${names.size()} file(s))"
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

// --check-verbose is a modifier on the report and implies --check: it means
// "inspect, in detail", not "mux with a verbose pre-flight". Without this a bare
// `mkv-mux --check-verbose` would print the report and then mux the whole batch.
checkOnly = checkOnly || checkVerbose

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

def wantCheck = checkOnly || (!identifyOnly && !noCheck)
def infos = [:]
if (mediaFiles && (identifyOnly || wantCheck)) {
    // Probing runs `mkvmerge -J` per file, which is seconds of silence on a
    // slow share for a full season. Print a live tick so it never looks hung.
    print "*** Reading ${mediaFiles.size()} file(s)"
    System.out.flush()
    mediaFiles.each { infos[it] = probeFile(it); print '.'; System.out.flush() }
    println()
    println()
}

def blockingCount = 0
if (mediaFiles && wantCheck) {
    blockingCount = runConsistencyCheck(mediaFiles, infos)
}

if (blockingCount > 0 && strict) {
    System.err.println ui.red("*** Strict mode: aborting (${blockingCount} discrepanc${blockingCount == 1 ? 'y' : 'ies'} " +
                              "affecting selected tracks).")
    System.err.println ui.red("*** Nothing was muxed. Fix config.yaml or the inputs, or drop --strict to continue.")
    System.exit(2)
}

// --check on its own muxes nothing. When --identify is also present, fall
// through to the loop below, which prints the per-file tables and then returns
// before muxing because identifyOnly is set.
if (checkOnly && !identifyOnly) {
    ui.success("*** Done")
    return
}

Process proc = null

addShutdownHook {
    if (proc != null) {
        println "*** Killing mkvtoolnix process ${-> proc.pid()}"
        proc.destroy()
    }
}

// mkvmerge only creates a missing output directory in recent versions (older
// ones fail to open the output file), so create it here — but not when we are
// only inspecting, which must leave the filesystem untouched
if (!identifyOnly && !dryRun) {
    new File(destinationDir).mkdirs()
}

files.forEach { file ->
    {
        extension = FilenameUtils.getExtension(file.getName().toLowerCase())

        if (allowedExtensions.contains extension) {
            if (identifyOnly) {
                identifyFile(file, infos[file])
                return
            }

            ui.header("*** Processing ${file.name}")
            println()

            fileName = FilenameUtils.getBaseName(file.name)

            // Build command line based on configuration
            def commandLine = buildCommandLine()

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
