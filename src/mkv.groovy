@Grab('commons-io:commons-io:2.11.0')
@Grab('org.yaml:snakeyaml:1.30')
import org.apache.commons.io.FilenameUtils
import org.yaml.snakeyaml.Yaml

// Load configuration from YAML file
def configFile = new File("src/config.yaml")
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
def uiLanguage = "en"
def priority = "lower"

def fileName = null
def extension = null

// Build command line based on configuration; closure so it captures script-scope locals
def buildCommandLine = {
    def commandLine = [
        mkvmergeExe,
        "--ui-language",
        uiLanguage,
        "--priority",
        priority,
        "--output",
        "${destinationDir}/${-> fileName}.mkv", // Output file name
    ] as ArrayList

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
        config.trackOrder
    ])

    return commandLine
}

def currentDir = new File(".")

//Read files first to be able to write to the same directory
def files = currentDir.listFiles({ it.isFile() } as FileFilter) as List<File>

Process proc = null

addShutdownHook {
    if (proc != null) {
        println "Killing mkvtoolnix process ${-> proc.pid()}"
        proc.destroy()
    }
}

files.forEach { file ->
    {
        extension = FilenameUtils.getExtension(file.getName().toLowerCase())

        if (allowedExtensions.contains extension) {
            println "*** Processing ${file.name}"
            println()

            fileName = FilenameUtils.getBaseName(file.name)

            // Build command line based on configuration
            def commandLine = buildCommandLine()

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
