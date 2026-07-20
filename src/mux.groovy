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

@CommandLine.Option(names = ["--identify"],
                    description = "Print a track table for every matching file and exit without muxing")
@Field boolean identifyOnly = false

@CommandLine.Option(names = ["-n", "--dry-run"],
                    description = "Print the mkvmerge command line for every matching file without executing it")
@Field boolean dryRun = false

@CommandLine.Option(names = ["-x", "--exclude"], paramLabel = "PATTERN",
                    description = "File name or glob pattern to skip; may be given more than once")
@Field List<String> excludeMasks = []

@CommandLine.Parameters(index = "0..*", arity = "0..*", paramLabel = "FILE",
                        description = "File names or glob patterns to process; may be given more than once " +
                                      "(default: every file in the current directory)")
@Field List<String> fileMasks = []

// Load configuration from YAML file: the current directory takes precedence
// (per-show config next to the media files), falling back to the config
// shipped next to this script
def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def configFile = [new File("config.yaml"), new File(scriptDir, "config.yaml")].find { it.exists() }
if (configFile == null) {
    throw new RuntimeException("config.yaml not found in the current directory or next to mkv.groovy")
}
def config = new Yaml().load(configFile.text)

// Locate an MKVToolNix executable: try PATH first, then the Windows default install location
def findMkvTool = { String name ->
    try {
        def proc = [name, '--version'].execute()
        proc.waitFor()
        if (proc.exitValue() == 0) return name
    } catch (ignored) {}
    if (System.getProperty('os.name').toLowerCase().contains('win')) {
        def path = "C:\\Program Files\\MKVToolNix\\${name}.exe"
        if (new File(path).exists()) return path
    }
    throw new RuntimeException("'$name' not found on PATH or in default install location. Install MKVToolNix.")
}

// Extract general settings from config
def destinationDir = config.general.destinationDir
def allowedExtensions = config.general.allowedExtensions as Set
def mkvmergeExe = (config.general.containsKey('mkvmergeExe') && config.general.mkvmergeExe)
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

// Print a readable track table for one file, for --identify.
//
// NOTE: the per-track JSON key "properties" must be read with .get('properties').
// On Groovy 4+ both track.properties and track['properties'] resolve to the bean
// properties of the map object itself, not the JSON key of that name.
def identifyFile = { File file ->
    println "*** ${file.name}"

    def proc = [mkvmergeExe, '-J', file.absolutePath].execute()
    def json = proc.inputStream.text
    proc.waitFor()

    if (proc.exitValue() != 0) {
        println "  (mkvmerge could not identify this file: exit ${proc.exitValue()})"
        println()
        return
    }

    def tracks = new JsonSlurper().parseText(json).tracks
    if (!tracks) {
        println "  (no tracks)"
        println()
        return
    }

    printf("  %-4s %-10s %-22s %-5s %-4s %-4s %s%n",
           'ID', 'TYPE', 'CODEC', 'LANG', 'DEF', 'FOR', 'NAME')

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
        println "*** Warning: trackOrder contains malformed entries: ${malformed.join(', ')}"
        println "***          Expected comma-separated sourceIndex:trackId pairs, e.g. \"0:0,0:1,1:0\"."
    }

    def unknown = entries.findAll { it ==~ /^\d+:\d+$/ } - configured
    if (unknown) {
        println "*** Warning: trackOrder references track IDs not configured: ${unknown.join(', ')}"
        println "***          mkvmerge silently ignores unknown IDs, so these have no effect."
        println "***          Check trackOrder against mainSource.audioTracks / subtitleTracks / additionalSources."
    }

    def missing = configured - entries
    if (missing) {
        println "*** Warning: trackOrder omits configured track IDs: ${missing.join(', ')}"
        println "***          These tracks are still muxed, but their position in the output is left to mkvmerge."
    }
}

// Resolve once, not per file, so the warnings are printed only once
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
    println "*** No files match: ${(fileMasks + excludeMasks.collect { "--exclude $it" }).join(', ')}"
    println()
    println "*** Done"
    return
}

Process proc = null

addShutdownHook {
    if (proc != null) {
        println "Killing mkvtoolnix process ${-> proc.pid()}"
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
                identifyFile(file)
                return
            }

            println "*** Processing ${file.name}"
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
                println "*** Error: ${proc.exitValue()}"
            }

            proc = null
        } else {
            println "*** Skipping ${file.name}"
        }
    }
}

println()
println "*** Done"
