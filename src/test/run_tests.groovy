@Grab('info.picocli:picocli-groovy:4.6.3')
@Grab('commons-io:commons-io:2.11.0')
@GrabConfig(systemClassLoader=true)
import groovy.transform.Field
import picocli.CommandLine
import picocli.groovy.PicocliScript2
import org.apache.commons.io.FileUtils
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import com.sun.net.httpserver.HttpServer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

@CommandLine.Command(name='run_tests', mixinStandardHelpOptions=true,
                     description='Run the mkv.groovy test suite')
@PicocliScript2

@CommandLine.Option(names=['-f', '--filter'], description='Run only tests whose name contains this string')
@Field String filterPattern = null

@CommandLine.Option(names=['-k', '--keep'], description='Preserve work/ directory after run')
@Field boolean keepWork = false

@CommandLine.Option(names=['--mkvmerge-exe'], paramLabel='PATH',
                    description='Path to mkvmerge executable (default: auto-detect from PATH)')
@Field String mkvmergeExeOverride = null

// ─── Paths ──────────────────────────────────────────────────────────────────

def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def repoRoot  = scriptDir.parentFile.parentFile          // …/mkv-script
def testMkv   = new File(scriptDir, 'test.mkv')
def mkvgroovy = new File(repoRoot, 'src/mux.groovy')
def binDir    = new File(repoRoot, 'bin')
def workRoot  = new File(scriptDir, 'work')

def isWindows = System.getProperty('os.name').toLowerCase().contains('win')
def groovyBin = isWindows ? 'bin/groovy.bat' : 'bin/groovy'
def groovyExe = new File(System.getProperty('groovy.home', ''), groovyBin).with {
    exists() ? absolutePath : 'groovy'
}

def findMkvTool = { String name ->
    try {
        def proc = [name, '--version'].execute()
        proc.waitFor()
        if (proc.exitValue() == 0) return name
    } catch (ignored) {}
    if (isWindows) {
        def path = "C:\\Program Files\\MKVToolNix\\${name}.exe"
        if (new File(path).exists()) return path
    }
    throw new RuntimeException("'$name' not found on PATH or in default install location. Install MKVToolNix.")
}

def mkvmergeExe = mkvmergeExeOverride ?: findMkvTool('mkvmerge')

// Optional: propedit tests skip themselves when this is absent
def mkvpropeditExe = null
try {
    mkvpropeditExe = findMkvTool('mkvpropedit')
} catch (ignored) {}

assert testMkv.exists()   : "test.mkv not found at $testMkv"
assert mkvgroovy.exists() : "mux.groovy not found at $mkvgroovy"

// ─── Helpers (closures so they capture script-scope variables) ───────────────

def failures = []
def passes   = []
def lastMkvOutput = null

/** Run a command list; return [exitCode, stdout+stderr combined]. */
def exec = { cmd, File cwd = null ->
    def pb = new ProcessBuilder(cmd.collect { it.toString() })
    pb.redirectErrorStream(true)
    // Ensure JAVA_HOME points at the JVM actually running this process
    pb.environment().put('JAVA_HOME', System.getProperty('java.home'))
    if (cwd) pb.directory(cwd)
    def proc = pb.start()
    def out = proc.inputStream.text
    proc.waitFor()
    [proc.exitValue(), out]
}

/** mkvmerge -J on a file; return parsed JSON map.
 *  NOTE: read the "properties" key of the result via .get('properties') — on
 *  Groovy 4+ both map.properties and map['properties'] resolve to the bean
 *  properties of the map object itself, not the JSON key of that name. */
def identify = { File f ->
    def (code, out) = exec([mkvmergeExe, '-J', f.absolutePath])
    assert code == 0 : "mkvmerge -J failed on $f"
    new JsonSlurper().parseText(out)
}

/** Extract a single track from src into destFile.
 *  trackType: 'audio' | 'subtitle'. trackId: integer track id in source. */
def extractTrack = { File src, File destFile, String trackType, int trackId ->
    def flag   = trackType == 'audio' ? '--audio-tracks' : '--subtitle-tracks'
    def noFlag = trackType == 'audio' ? '--no-subtitles' : '--no-audio'
    destFile.parentFile?.mkdirs()
    def (code, out) = exec([
        mkvmergeExe, '--output', destFile.absolutePath,
        '--no-video', noFlag, flag, "$trackId",
        src.absolutePath
    ])
    assert (code == 0 || code == 1) : "extractTrack failed (exit $code):\n$out"
}

/** Write config.yaml into workDir (mkv.groovy reads it from the CWD). */
def writeConfig = { File workDir, String yaml ->
    new File(workDir, 'config.yaml').text = yaml
}

/** Write episodes.txt into workDir, one title per line.
 *  UTF-8 explicitly, matching what fetch_episodes.groovy writes and what
 *  rename.groovy reads — the harness must not depend on the platform default
 *  any more than the scripts do. */
def writeEpisodes = { File workDir, List<String> titles ->
    new File(workDir, 'episodes.txt').setText(titles.join('\n') + '\n', 'UTF-8')
}

/** Copy test.mkv into workDir under the given name. */
def stageInput = { File workDir, String name = 'test.mkv' ->
    def dest = new File(workDir, name)
    FileUtils.copyFile(testMkv, dest)
    dest
}

/** Run any script from the repo's src/ in workDir; return [exitCode, output].
 *  The output is also kept for diagnostics if the test fails. */
def runScript = { String scriptName, File workDir, List extraArgs = [] ->
    def script = new File(repoRoot, "src/${scriptName}")
    assert script.exists() : "script not found at $script"
    def result = exec([groovyExe, script.absolutePath] + extraArgs, workDir)
    lastMkvOutput = result[1]
    result
}

/** Run mux.groovy from workDir; return [exitCode, output]. */
def runMkvGroovy = { File workDir, List extraArgs = [] ->
    runScript('mux.groovy', workDir, extraArgs)
}

/** Find the single output MKV in workDir/<destDir>. */
def findOutput = { File workDir, String destDir = 'mkv' ->
    new File(workDir, destDir).listFiles()?.find { it.name.endsWith('.mkv') }
}

/** Serve a canned JSON response per path on a random local port for the duration
 *  of the body, which receives the base URL. Lets fetch_episodes.groovy be tested
 *  offline and deterministically via its --base-url seam; the JDK's own
 *  HttpServer keeps this dependency-free.
 *
 *  routes: path (e.g. '/3/tv/2260') -> JSON string. Unknown paths return 404. */
def withStubServer = { Map<String, String> routes, Closure body ->
    def server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
    server.createContext('/') { exchange ->     // query string is not under test
        def json  = routes[exchange.requestURI.path]
        def bytes = (json ?: '{"status_message":"stub: no route"}').getBytes('UTF-8')
        exchange.responseHeaders.add('Content-Type', 'application/json; charset=utf-8')
        exchange.sendResponseHeaders(json ? 200 : 404, bytes.length)
        exchange.responseBody.withStream { it.write(bytes) }
    }
    server.start()
    try { body("http://127.0.0.1:${server.address.port}".toString()) }
    finally { server.stop(0) }
}

def check = { boolean cond, String msg ->
    if (!cond) throw new AssertionError(msg)
}

def checkEquals = { actual, expected, String label ->
    if (actual != expected) throw new AssertionError("$label: expected <$expected> but got <$actual>")
}

/** Run a named test case; handle pass/fail bookkeeping. */
def runTest = { String name, Closure body ->
    if (filterPattern && !name.contains(filterPattern)) return

    def workDir = new File(workRoot, name.replaceAll(/[^a-zA-Z0-9_-]/, '_'))
    if (workDir.exists()) try { FileUtils.deleteDirectory(workDir) } catch (ignored) {}
    workDir.mkdirs()
    lastMkvOutput = null

    try {
        body(workDir)
        passes << name
        println "[PASS] $name"
    } catch (Throwable t) {
        failures << [name: name, msg: t.message ?: t.toString()]
        println "[FAIL] $name: ${t.message ?: t}"
        if (lastMkvOutput) {
            println "  --- mkv.groovy output ---"
            lastMkvOutput.eachLine { println "  $it" }
            println "  -------------------------"
        }
    } finally {
        if (!keepWork && !failures.find { it.name == name }) {
            try { FileUtils.deleteDirectory(workDir) } catch (ignored) {}
        }
    }
}

// ─── Config builder ──────────────────────────────────────────────────────────

/** Build a config.yaml string from a map of options. */
def cfg = { Map opts = [:] ->
    def dest   = opts.destinationDir ?: 'mkv'
    def exts   = opts.extensions     ?: ['mkv', 'avi', 'mp4']
    def extStr = exts.collect { "\"$it\"" }.join(', ')

    def sb = new StringBuilder()
    sb << "general:\n"
    sb << "  destinationDir: \"$dest\"\n"
    sb << "  allowedExtensions: [$extStr]\n"
    sb << "  mkvmergeExe: \"${mkvmergeExe.replace('\\', '\\\\')}\"\n"

    sb << "mainSource:\n"
    sb << "  videoTrack:\n"
    sb << "    language: \"${opts.videoLang ?: 'en'}\"\n"
    if (opts.videoTitle) sb << "    title: \"${opts.videoTitle}\"\n"

    if (opts.containsKey('audioTracks')) {
        if (!opts.audioTracks) {
            sb << "  audioTracks: []\n"
        } else {
            sb << "  audioTracks:\n"
            opts.audioTracks.each { t ->
                sb << "    - id: ${t.id}\n"
                sb << "      language: \"${t.language}\"\n"
                sb << "      title: \"${t.title}\"\n"
                sb << "      default: ${t.default}\n"
            }
        }
    }

    if (opts.containsKey('subtitleTracks')) {
        if (!opts.subtitleTracks) {
            sb << "  subtitleTracks: []\n"
        } else {
            sb << "  subtitleTracks:\n"
            opts.subtitleTracks.each { t ->
                sb << "    - id: ${t.id}\n"
                sb << "      language: \"${t.language}\"\n"
                sb << "      title: \"${t.title}\"\n"
                if (t.charset) sb << "      charset: \"${t.charset}\"\n"
                sb << "      default: ${t.default}\n"
            }
        }
    }

    if (opts.mainAdditionalOptions) {
        sb << "  additionalOptions:\n"
        opts.mainAdditionalOptions.each { sb << "    - \"$it\"\n" }
    }

    // Omit the key entirely when not supplied, so tests can exercise derivation
    if (opts.containsKey('trackOrder') && opts.trackOrder != null) {
        sb << "trackOrder: \"${opts.trackOrder}\"\n"
    }

    if (opts.additionalSources) {
        sb << "additionalSources:\n"
        opts.additionalSources.each { src ->
            sb << "  - file: \"${src.file.replace('\\', '\\\\')}\"\n"
            sb << "    tracks:\n"
            src.tracks.each { t ->
                sb << "      - language: \"${t.language}\"\n"
                sb << "        title: \"${t.title}\"\n"
                if (t.charset) sb << "        charset: \"${t.charset}\"\n"
                sb << "        default: ${t.default}\n"
            }
            if (src.additionalOptions) {
                sb << "    additionalOptions:\n"
                src.additionalOptions.each { sb << "      - \"$it\"\n" }
            }
        }
    }

    sb.toString()
}

// ═══════════════════════════════════════════════════════════════════════════
// TEST.MKV layout (track IDs as reported by mkvmerge):
//   0  video   und  H.264
//   1  audio   jpn  AAC    "Audio A"
//   2  audio   eng  AAC    "Audio B"
//   3  audio   rus  AAC    "Audio C"
//   4  sub     eng  SRT    "Subtitle A"
//   5  sub     rus  SRT    "Subtitle B" (forced)
//   6  sub     jpn  SRT    "Subtitle C"
// ═══════════════════════════════════════════════════════════════════════════

// ─── 1. Pick one audio + one subtitle ────────────────────────────────────────
runTest('01_one_audio_one_subtitle') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        subtitleTracks: [[id:4, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2,0:4'
    ))
    runMkvGroovy(workDir)
    def tracks = identify(findOutput(workDir)).tracks
    checkEquals(tracks.count { it.type == 'video' },     1, 'video count')
    checkEquals(tracks.count { it.type == 'audio' },     1, 'audio count')
    checkEquals(tracks.count { it.type == 'subtitles' }, 1, 'subtitle count')
    check(tracks.find { it.type == 'audio' }.get('properties').language == 'eng',     'audio lang eng')
    check(tracks.find { it.type == 'subtitles' }.get('properties').language == 'eng', 'sub lang eng')
}

// ─── 2. Multiple audio, jpn default ──────────────────────────────────────────
runTest('02_multiple_audio_jpn_default') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [
            [id:1, language:'ja', title:'Japanese', default:true],
            [id:2, language:'en', title:'English',  default:false]
        ],
        trackOrder: '0:0,0:1,0:2'
    ))
    runMkvGroovy(workDir)
    def audioTracks = identify(findOutput(workDir)).tracks.findAll { it.type == 'audio' }
    checkEquals(audioTracks.size(), 2, 'audio count')
    checkEquals(audioTracks[0].get('properties').language,      'jpn',  'first audio lang')
    checkEquals(audioTracks[0].get('properties').default_track, true,   'jpn default=true')
    checkEquals(audioTracks[1].get('properties').default_track, false,  'eng default=false')
}

// ─── 3. No audio (key omitted) ───────────────────────────────────────────────
runTest('03_no_audio_key_omitted') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        subtitleTracks: [[id:4, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:4'
    ))
    runMkvGroovy(workDir)
    def tracks = identify(findOutput(workDir)).tracks
    checkEquals(tracks.count { it.type == 'audio' },     0, 'audio count')
    checkEquals(tracks.count { it.type == 'subtitles' }, 1, 'sub count')
}

// ─── 4. No audio (empty list) ────────────────────────────────────────────────
runTest('04_no_audio_empty_list') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [],
        subtitleTracks: [[id:4, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:4'
    ))
    runMkvGroovy(workDir)
    checkEquals(identify(findOutput(workDir)).tracks.count { it.type == 'audio' }, 0, 'audio count')
}

// ─── 5. No subtitles (key omitted) ───────────────────────────────────────────
runTest('05_no_subtitles_key_omitted') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)
    checkEquals(identify(findOutput(workDir)).tracks.count { it.type == 'subtitles' }, 0, 'subtitle count')
}

// ─── 6. No subtitles (empty list) ────────────────────────────────────────────
runTest('06_no_subtitles_empty_list') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        subtitleTracks: [],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)
    checkEquals(identify(findOutput(workDir)).tracks.count { it.type == 'subtitles' }, 0, 'subtitle count')
}

// ─── 7. Video language override ──────────────────────────────────────────────
runTest('07_video_language_override') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        videoLang: 'ja',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)
    def vid = identify(findOutput(workDir)).tracks.find { it.type == 'video' }
    checkEquals(vid.get('properties').language, 'jpn', 'video language')
}

// ─── 8. Video title defaults to filename ─────────────────────────────────────
runTest('08_video_title_defaults_to_filename') { workDir ->
    stageInput(workDir, 'MyShow.S01E01.mkv')
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)
    def outFile = new File(workDir, 'mkv').listFiles()?.find { it.name.endsWith('.mkv') }
    def baseName = outFile.name.replace('.mkv', '')
    def info = identify(outFile)
    checkEquals(info.container.get('properties').title,                                       baseName, 'segment title')
    checkEquals(info.tracks.find { it.type == 'video' }.get('properties').track_name, baseName, 'video track name')
}

// ─── 9. Video title override ──────────────────────────────────────────────────
runTest('09_video_title_override') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        videoTitle: 'Custom Title',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)
    def info = identify(findOutput(workDir))
    checkEquals(info.tracks.find { it.type == 'video' }.get('properties').track_name, 'Custom Title', 'video track name')
    check(info.container.get('properties').title != 'Custom Title', 'segment title is filename, not override')
}

// ─── 10. Default flags on multiple audio tracks ───────────────────────────────
runTest('10_default_flags_audio') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [
            [id:1, language:'ja', title:'Japanese', default:false],
            [id:2, language:'en', title:'English',  default:true],
            [id:3, language:'ru', title:'Russian',  default:false]
        ],
        trackOrder: '0:0,0:1,0:2,0:3'
    ))
    runMkvGroovy(workDir)
    def a = identify(findOutput(workDir)).tracks.findAll { it.type == 'audio' }
    checkEquals(a.size(), 3, 'audio count')
    checkEquals(a[0].get('properties').default_track, false, 'jpn default=false')
    checkEquals(a[1].get('properties').default_track, true,  'eng default=true')
    checkEquals(a[2].get('properties').default_track, false, 'rus default=false')
}

// ─── 11. Subtitle without charset (must not crash) ───────────────────────────
runTest('11_subtitle_no_charset') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        subtitleTracks: [[id:4, language:'en', title:'English', default:true]],  // no charset
        trackOrder: '0:0,0:2,0:4'
    ))
    runMkvGroovy(workDir)
    def outFile = findOutput(workDir)
    check(outFile != null && outFile.exists(), 'output file created')
    checkEquals(identify(outFile).tracks.count { it.type == 'subtitles' }, 1, 'sub count')
}

// ─── 12. Subtitle with explicit charset ──────────────────────────────────────
runTest('12_subtitle_with_charset') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        subtitleTracks: [[id:5, language:'ru', title:'Russian', charset:'UTF-8', default:false]],
        trackOrder: '0:0,0:2,0:5'
    ))
    runMkvGroovy(workDir)
    def subs = identify(findOutput(workDir)).tracks.findAll { it.type == 'subtitles' }
    checkEquals(subs.size(), 1, 'sub count')
    checkEquals(subs[0].get('properties').language, 'rus', 'sub lang')
}

// ─── 13. Forced subtitle flag preserved ──────────────────────────────────────
runTest('13_forced_subtitle_preserved') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        subtitleTracks: [[id:5, language:'ru', title:'Forced', default:false]],
        trackOrder: '0:0,0:2,0:5'
    ))
    runMkvGroovy(workDir)
    def sub = identify(findOutput(workDir)).tracks.find { it.type == 'subtitles' }
    check(sub != null, 'subtitle present')
    checkEquals(sub.get('properties').forced_track, true, 'forced flag preserved')
}

// ─── 14. trackOrder controls output track order ───────────────────────────────
runTest('14_track_order_reorders_audio') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [
            [id:1, language:'ja', title:'Japanese', default:false],
            [id:2, language:'en', title:'English',  default:true]
        ],
        trackOrder: '0:0,0:2,0:1'   // eng before jpn
    ))
    runMkvGroovy(workDir)
    def a = identify(findOutput(workDir)).tracks.findAll { it.type == 'audio' }
    checkEquals(a[0].get('properties').language, 'eng', 'first audio lang')
    checkEquals(a[1].get('properties').language, 'jpn', 'second audio lang')
}

// ─── 15. External audio companion via ${fileName} ────────────────────────────
runTest('15_external_audio_companion') { workDir ->
    stageInput(workDir)
    extractTrack(testMkv, new File(workDir, 'test[Extra].mka'), 'audio', 2)
    writeConfig(workDir, cfg(
        audioTracks: [[id:1, language:'ja', title:'Japanese', default:true]],
        trackOrder: '0:0,0:1,1:0',
        additionalSources: [[
            file: '${fileName}[Extra].mka',
            tracks: [[language:'en', title:'Extra English', default:false]]
        ]]
    ))
    runMkvGroovy(workDir)
    def a = identify(findOutput(workDir)).tracks.findAll { it.type == 'audio' }
    checkEquals(a.size(), 2, 'audio count')
    check(a.any { it.get('properties').track_name == 'Extra English' }, 'Extra English track present')
}

// ─── 16. External SRT companion with charset ─────────────────────────────────
runTest('16_external_srt_with_charset') { workDir ->
    stageInput(workDir)
    extractTrack(testMkv, new File(workDir, 'test.srt'), 'subtitle', 4)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2,1:0',
        additionalSources: [[
            file: '${fileName}.srt',
            tracks: [[language:'en', title:'ExtSub', charset:'UTF-8', default:false]]
        ]]
    ))
    runMkvGroovy(workDir)
    def subs = identify(findOutput(workDir)).tracks.findAll { it.type == 'subtitles' }
    checkEquals(subs.size(), 1, 'sub count')
    checkEquals(subs[0].get('properties').track_name, 'ExtSub', 'sub title')
}

// ─── 17. External SRT without charset ────────────────────────────────────────
runTest('17_external_srt_no_charset') { workDir ->
    stageInput(workDir)
    extractTrack(testMkv, new File(workDir, 'test.srt'), 'subtitle', 4)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2,1:0',
        additionalSources: [[
            file: '${fileName}.srt',
            tracks: [[language:'en', title:'ExtSubNoCharset', default:false]]
        ]]
    ))
    runMkvGroovy(workDir)
    checkEquals(identify(findOutput(workDir)).tracks.count { it.type == 'subtitles' }, 1, 'sub count')
}

// ─── 18. Two additional sources ───────────────────────────────────────────────
runTest('18_two_additional_sources') { workDir ->
    stageInput(workDir)
    extractTrack(testMkv, new File(workDir, 'test[ExtraAudio].mka'), 'audio',    2)
    extractTrack(testMkv, new File(workDir, 'test[ExtraSub].srt'),   'subtitle', 4)
    writeConfig(workDir, cfg(
        audioTracks: [[id:1, language:'ja', title:'Japanese', default:true]],
        trackOrder: '0:0,0:1,1:0,2:0',
        additionalSources: [
            [file: '${fileName}[ExtraAudio].mka', tracks: [[language:'en', title:'ExtraAudio', default:false]]],
            [file: '${fileName}[ExtraSub].srt',   tracks: [[language:'en', title:'ExtraSub',   default:false]]]
        ]
    ))
    runMkvGroovy(workDir)
    def tracks = identify(findOutput(workDir)).tracks
    checkEquals(tracks.count { it.type == 'audio' },     2, 'audio count')
    checkEquals(tracks.count { it.type == 'subtitles' }, 1, 'sub count')
    check(tracks.any { it.get('properties').track_name == 'ExtraAudio' }, 'ExtraAudio present')
    check(tracks.any { it.get('properties').track_name == 'ExtraSub' },   'ExtraSub present')
}

// ─── 19. allowedExtensions filters non-matching files ────────────────────────
runTest('19_extensions_filter') { workDir ->
    stageInput(workDir)
    new File(workDir, 'notes.txt').text = 'not a video'
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    def (code, out) = runMkvGroovy(workDir)
    check(out.contains('Skipping notes.txt'), 'notes.txt reported as skipped')
    def outputs = new File(workDir, 'mkv').listFiles()?.findAll { it.name.endsWith('.mkv') } ?: []
    checkEquals(outputs.size(), 1, 'exactly one output')
}

// ─── 20. Multiple inputs produce multiple outputs ─────────────────────────────
runTest('20_multiple_inputs') { workDir ->
    stageInput(workDir, 'episode01.mkv')
    stageInput(workDir, 'episode02.mkv')
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)
    def outputs = new File(workDir, 'mkv').listFiles()?.findAll { it.name.endsWith('.mkv') } ?: []
    checkEquals(outputs.size(), 2, 'two outputs produced')
}

// ─── 21. destinationDir created if missing ────────────────────────────────────
runTest('21_destination_dir_created') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        destinationDir: 'output_new',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)
    def destDir = new File(workDir, 'output_new')
    check(destDir.exists() && destDir.isDirectory(), 'destinationDir was created')
    check(destDir.listFiles()?.any { it.name.endsWith('.mkv') }, 'output file in new dir')
}

// ─── 22. Invalid track id: error reported, script does not crash ──────────────
runTest('22_invalid_track_id') { workDir ->
    stageInput(workDir, 'episode01.mkv')
    stageInput(workDir, 'episode02.mkv')
    writeConfig(workDir, cfg(
        audioTracks: [[id:99, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:99'
    ))
    def (code, out) = runMkvGroovy(workDir)
    check(out.contains('*** Done'),  'script completed without crash')
    check(out.contains('*** Error:'), 'error reported for bad track id')
}

// ─── 23. Stale trackOrder with nonexistent ID: warned about, still muxes ─────
// mkvmerge silently discards track IDs in --track-order that don't correspond
// to any muxed track, exits 0, and still produces a valid output. mux.groovy
// warns about this rather than failing, so the config error is visible.
runTest('23_stale_track_order') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2,0:999'    // 999 doesn't exist — mkvmerge ignores it silently
    ))
    def (code, out) = runMkvGroovy(workDir)
    def outFile = findOutput(workDir)
    check(out.contains('*** Done'), 'script completes normally')
    check(outFile != null && outFile.exists(), 'output file still produced')
    def tracks = identify(outFile).tracks
    checkEquals(tracks.count { it.type == 'audio' }, 1, 'audio track still present')

    check(out.contains('references track IDs not configured'), 'warns about the unknown ID')
    check(out.contains('0:999'), 'warning names the offending ID')
}

// ─── 24. Realistic season-episode golden test ─────────────────────────────────
runTest('24_realistic_season_episode') { workDir ->
    stageInput(workDir, 'ShowName.S01E01.mkv')
    writeConfig(workDir, cfg(
        videoLang: 'ja',
        audioTracks: [
            [id:1, language:'ja', title:'Japanese', default:true],
            [id:2, language:'en', title:'English',  default:false]
        ],
        subtitleTracks: [
            [id:4, language:'en', title:'English',  default:true],
            [id:6, language:'ja', title:'Japanese', default:false]
        ],
        trackOrder: '0:0,0:1,0:2,0:4,0:6'
    ))
    runMkvGroovy(workDir)
    def info   = identify(findOutput(workDir))
    def tracks = info.tracks

    checkEquals(info.container.get('properties').title,            'ShowName.S01E01', 'segment title')
    checkEquals(tracks.count { it.type == 'video' },     1, 'video count')
    checkEquals(tracks.count { it.type == 'audio' },     2, 'audio count')
    checkEquals(tracks.count { it.type == 'subtitles' }, 2, 'subtitle count')

    def a = tracks.findAll { it.type == 'audio' }
    checkEquals(a[0].get('properties').language,      'jpn',  'audio[0] lang')
    checkEquals(a[0].get('properties').default_track, true,   'audio[0] default')
    checkEquals(a[1].get('properties').language,      'eng',  'audio[1] lang')
    checkEquals(a[1].get('properties').default_track, false,  'audio[1] default')

    def s = tracks.findAll { it.type == 'subtitles' }
    checkEquals(s[0].get('properties').language,      'eng',  'sub[0] lang')
    checkEquals(s[0].get('properties').default_track, true,   'sub[0] default')
    checkEquals(s[1].get('properties').language,      'jpn',  'sub[1] lang')
    checkEquals(s[1].get('properties').default_track, false,  'sub[1] default')

    checkEquals(a[0].get('properties').track_name, 'Japanese', 'audio[0] name')
    checkEquals(a[1].get('properties').track_name, 'English',  'audio[1] name')
    checkEquals(s[0].get('properties').track_name, 'English',  'sub[0] name')
    checkEquals(s[1].get('properties').track_name, 'Japanese', 'sub[1] name')

    def langs = tracks.collect { it.get('properties').language }
    check(!langs.contains('rus'), 'no Russian tracks in output')
}

// ─── 25. bin/ wrappers exist and point at real scripts ───────────────────────
// 16 hand-written files; this catches typos for the price of a directory listing.
runTest('25_wrappers_exist_and_resolve') { workDir ->
    def wrappers = [
        'mkv-mux'                : 'mux.groovy',
        'mkv-rename'             : 'rename.groovy',
        'mkv-propedit'           : 'propedit.groovy',
        'mkv-fix-srt'            : 'fix_srt.groovy',
        'mkv-fetch-episodes'     : 'fetch_episodes.groovy',
        'mkv-filename-to-title'  : 'filename_to_title.groovy',
        'mkv-to-utf8'            : 'to_utf8.groovy',
        'mkv-find-unused-fonts'  : 'find_unused_fonts.groovy',
    ]

    wrappers.each { name, target ->
        def sh  = new File(binDir, name)
        def bat = new File(binDir, "${name}.bat")

        check(sh.exists(),  "$name exists")
        check(bat.exists(), "${name}.bat exists")
        check(sh.length()  > 0, "$name is not empty")
        check(bat.length() > 0, "${name}.bat is not empty")

        // Both wrappers must name the same script, and it must be a real file
        check(sh.text.contains("../src/${target}"),  "$name references src/$target")
        check(bat.text.contains("..\\src\\${target}"), "${name}.bat references src\\$target")
        check(new File(repoRoot, "src/${target}").exists(), "src/$target exists (referenced by $name)")
    }
}

// ─── 26. Wrapper actually runs ────────────────────────────────────────────────
// Bare invocation in a directory with a config but no media files: the script
// should start up, find nothing to do, and exit cleanly. Deliberately avoids
// any CLI flags so this test does not depend on later features.
runTest('26_wrapper_smoke') { workDir ->
    // The wrappers hardcode a bare 'groovy'; the harness may be using groovy.home.
    // On Windows 'groovy' is a .bat, which ProcessBuilder cannot launch directly —
    // probe through cmd, the same way the wrapper itself is invoked below.
    def groovyOnPath = { ->
        try {
            def probe = isWindows ? ['cmd', '/c', 'groovy', '--version'] : ['groovy', '--version']
            def p = probe.execute()
            p.waitFor()
            return p.exitValue() == 0
        } catch (ignored) {
            return false
        }
    }()

    if (!groovyOnPath) {
        println "  (skipped: 'groovy' is not on PATH; wrappers require it)"
        return
    }

    writeConfig(workDir, cfg(
        audioTracks: [[id: 2, language: 'en', title: 'English', default: true]],
        trackOrder: '0:0,0:2'
    ))

    def cmd = isWindows
        ? ['cmd', '/c', new File(binDir, 'mkv-mux.bat').absolutePath]
        : [new File(binDir, 'mkv-mux').absolutePath]

    def (code, out) = exec(cmd, workDir)
    checkEquals(code, 0, 'wrapper exit code')
    check(out.contains('*** Done'), 'wrapper ran mux.groovy to completion')
}

// ─── 27. --dry-run prints the command and touches nothing ────────────────────
runTest('27_dry_run_produces_no_output') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id: 2, language: 'en', title: 'English', default: true]],
        trackOrder: '0:0,0:2'
    ))

    def (code, out) = runMkvGroovy(workDir, ['--dry-run'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('Dry run'), 'announces dry run')
    check(out.contains('--track-order'), 'prints the built command line')

    // Nothing may be written — not even the destination directory
    check(!new File(workDir, 'mkv').exists(), 'destination dir not created')
}

// ─── 28. --identify lists tracks without muxing ──────────────────────────────
// Assertions are deliberately loose: column formatting is expected to drift.
runTest('28_identify_lists_tracks') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id: 2, language: 'en', title: 'English', default: true]],
        trackOrder: '0:0,0:2'
    ))

    def (code, out) = runMkvGroovy(workDir, ['--identify'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('video'),     'lists the video track')
    check(out.contains('subtitles'), 'lists subtitle tracks')
    check(out.contains('jpn'),       'lists track languages')
    check(!new File(workDir, 'mkv').exists(), 'destination dir not created')
}

// ─── 29. trackOrder omitted: derived from the configured tracks ──────────────
runTest('29_derived_track_order') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [
            [id: 1, language: 'ja', title: 'Japanese', default: true],
            [id: 2, language: 'en', title: 'English',  default: false]
        ],
        subtitleTracks: [[id: 4, language: 'en', title: 'English', default: true]]
        // trackOrder deliberately omitted
    ))

    def (code, out) = runMkvGroovy(workDir)
    check(out.contains('using derived order: 0:0,0:1,0:2,0:4'), 'reports the derived order')

    def tracks = identify(findOutput(workDir)).tracks
    def audio  = tracks.findAll { it.type == 'audio' }
    def subs   = tracks.findAll { it.type == 'subtitles' }

    checkEquals(tracks[0].type, 'video', 'video first')
    checkEquals(audio[0].get('properties').language, 'jpn', 'audio in listed order (jpn first)')
    checkEquals(audio[1].get('properties').language, 'eng', 'audio in listed order (eng second)')
    checkEquals(subs.size(), 1, 'subtitle count')
}

// ─── 30. trackOrder omitting a configured track: warned about ────────────────
runTest('30_track_order_missing_id_warns') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [
            [id: 1, language: 'ja', title: 'Japanese', default: true],
            [id: 2, language: 'en', title: 'English',  default: false]
        ],
        trackOrder: '0:0,0:1'    // 0:2 is configured but not ordered
    ))

    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('omits configured track IDs'), 'warns about the omitted ID')
    check(out.contains('0:2'), 'warning names the omitted ID')

    def outFile = findOutput(workDir)
    check(outFile != null && outFile.exists(), 'output still produced')
    checkEquals(identify(outFile).tracks.count { it.type == 'audio' }, 2, 'both audio tracks muxed')
}

// ─── 31. propedit with no arguments changes nothing ──────────────────────────
runTest('31_propedit_no_args_is_noop') { workDir ->
    def input = stageInput(workDir)
    def sizeBefore = input.length()
    def mtimeBefore = input.lastModified()

    def (code, out) = runScript('propedit.groovy', workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('Usage'), 'prints usage')

    checkEquals(input.length(), sizeBefore, 'file size unchanged')
    checkEquals(input.lastModified(), mtimeBefore, 'file mtime unchanged')
}

// ─── 32. propedit passes its arguments through to mkvpropedit ────────────────
runTest('32_propedit_passthrough') { workDir ->
    if (!mkvpropeditExe) {
        println "  (skipped: mkvpropedit not available)"
        return
    }

    def input = stageInput(workDir)
    def (code, out) = runScript('propedit.groovy', workDir,
                                ['--edit', 'info', '--set', 'title=SmokeTest'])
    checkEquals(code, 0, 'exit code')

    checkEquals(identify(input).container.get('properties').title, 'SmokeTest',
                'title set via passthrough')
}

// ─── 33. rename aborts before touching anything when a title is missing ──────
// The data-loss guard: renaming destroys the sXXeYY pattern that links a file
// to its episode, so a partial rename is expensive to untangle by hand.
runTest('33_rename_missing_episode_aborts') { workDir ->
    stageInput(workDir, 'Show.s01e01.mkv')
    stageInput(workDir, 'Show.s01e02.mkv')
    writeEpisodes(workDir, ['First Episode'])   // nothing for episode 02

    def (code, out) = runScript('rename.groovy', workDir, ['My Show'])
    check(code != 0, 'exits non-zero')
    check(out.contains('Refusing to rename'), 'announces that nothing was renamed')
    check(out.contains('02'), 'names the offending episode')

    // The valid part of the batch is still shown, so the blocked scope is visible
    check(out.contains('My Show - S01E01 - First Episode.mkv'), 'previews the renames that would have happened')

    // Both originals must survive untouched, and nothing may contain 'null'
    check(new File(workDir, 'Show.s01e01.mkv').exists(), 'first original still present')
    check(new File(workDir, 'Show.s01e02.mkv').exists(), 'second original still present')
    check(!workDir.listFiles().any { it.name.contains('null') }, "no file named with a literal 'null'")
}

// ─── 34. rename --dry-run previews without renaming ──────────────────────────
runTest('34_rename_dry_run') { workDir ->
    stageInput(workDir, 'Show.s01e01.mkv')
    stageInput(workDir, 'Show.s01e02.mkv')
    writeEpisodes(workDir, ['First Episode', 'Second Episode'])

    def (code, out) = runScript('rename.groovy', workDir, ['My Show', '1', '--dry-run'])
    checkEquals(code, 0, 'exit code')
    check(out.contains("My Show - S01E01 - First Episode.mkv"),  'previews the first rename')
    check(out.contains("My Show - S01E02 - Second Episode.mkv"), 'previews the second rename')

    check(new File(workDir, 'Show.s01e01.mkv').exists(), 'first original untouched')
    check(new File(workDir, 'Show.s01e02.mkv').exists(), 'second original untouched')
    check(!new File(workDir, 'My Show - S01E01 - First Episode.mkv').exists(), 'nothing renamed')
}

// ─── 35. fetch_episodes against a local stub: parsing and name sanitising ────
// Offline and deterministic, so it runs everywhere including CI. The character
// filtering only matters on Windows and CI is Linux-only, which is exactly why
// it is asserted here rather than left to manual testing.
runTest('35_fetch_episodes_stub') { workDir ->
    def rawTitles = [
        'Plain Title',
        'Slash/Colon: Question?',
        'Quote"Star*Pipe|',
        'Back\\slash<gt>',
        'Trailing dots...',
        'Trailing space   '
    ]

    def routes = [
        '/3/tv/2260'         : JsonOutput.toJson([name: 'Stub Show', first_air_date: '2006-07-07']),
        '/3/tv/2260/season/1': JsonOutput.toJson([episodes: rawTitles.collect { [name: it] }])
    ]

    withStubServer(routes) { String baseUrl ->
        def (code, out) = runScript('fetch_episodes.groovy', workDir,
                                    ['--api-key', 'stub', '--show-id', '2260',
                                     '--season', '1', '--base-url', baseUrl])
        checkEquals(code, 0, 'exit code')
        check(out.contains('Stub Show'), 'prints the show name')
        check(out.contains('2006'), 'prints the first-air year')

        def lines = new File(workDir, 'episodes.txt').readLines('UTF-8')
        checkEquals(lines, [
            'Plain Title',
            'SlashColon Question',
            'QuoteStarPipe',
            'Backslashgt',
            'Trailing dots',
            'Trailing space'
        ], 'episodes.txt contents')

        // Every character Windows rejects in a file name must be gone
        check(!lines.any { it =~ /[\\\/:*?"<>|]/ }, 'no characters invalid on Windows survive')
        check(!lines.any { it ==~ /.*[. ]$/ },      'no trailing dots or spaces survive')
    }
}

// ─── 36. fetch_episodes stub: a failing request reports, never stack-traces ──
runTest('36_fetch_episodes_stub_error') { workDir ->
    withStubServer([:]) { String baseUrl ->
        def (code, out) = runScript('fetch_episodes.groovy', workDir,
                                    ['--api-key', 'stub', '--show-id', '2260',
                                     '--season', '1', '--base-url', baseUrl])
        check(code != 0, 'exits non-zero')
        check(out.contains('404'), 'reports the HTTP status')
        check(!out.contains('at fetch_episodes'), 'no stack trace in the output')
        check(!new File(workDir, 'episodes.txt').exists(), 'no episodes.txt written on failure')
    }
}

// ─── 37. fetch_episodes live contract test against TheMovieDB ────────────────
// Answers a different question from test 35: not "does our code work" but "has
// the API changed shape". Needs network and a key, so it skips when either is
// missing — that is also what makes it safe on fork PRs, where GitHub withholds
// secrets. Assertions are deliberately loose: TheMovieDB may legitimately edit
// episode titles, so asserting on them would produce failures that are not bugs.
runTest('37_fetch_episodes_live_contract') { workDir ->
    def key = System.getenv('TMDB_API_KEY')
    if (!key) {
        def keyFile = new File(repoRoot, 'src/apikey.txt')
        if (keyFile.exists()) key = keyFile.readLines().find { it.trim() }?.trim()
    }
    if (!key) {
        println "  (skipped: no TheMovieDB API key in TMDB_API_KEY or src/apikey.txt)"
        return
    }

    // Show 2260 is "H2O: Just Add Water", a finished Australian series, so its
    // season 1 episode count is not going to move.
    def (code, out) = runScript('fetch_episodes.groovy', workDir,
                                ['--api-key', key, '--show-id', '2260', '--season', '1'])
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains('H2O'), 'show name came back')

    def lines = new File(workDir, 'episodes.txt').readLines('UTF-8').findAll { it.trim() }
    check(lines.size() >= 20, "plausible episode count, got ${lines.size()}")
}

// ─── 38. non-ASCII titles survive fetch_episodes -> episodes.txt -> rename ───
// episodes.txt is a contract between two scripts: fetch_episodes.groovy writes
// it, rename.groovy reads it back. The write side names UTF-8 explicitly because
// Groovy's no-arg writer would use the platform default and silently replace
// every unmappable character with '?'; the read side deliberately does not, so
// Groovy's charset auto-detection can still cope with a hand-made episodes.txt.
//
// What this covers: the whole path end to end with non-ASCII, which nothing else
// does. What it does NOT do is guard the writer's explicit charset — under a
// UTF-8 default (both CI legs, and any modern JDK) the no-arg writer produces
// identical bytes, so reverting that fix would not turn this test red. Do not
// mistake it for a regression guard on the encoding itself; forcing a hostile
// default to get one also needs -Dgroovy.source.encoding=UTF-8, or the Cyrillic
// literals below are mangled consistently with everything else and it passes
// for the wrong reason.
runTest('38_non_ascii_titles_round_trip') { workDir ->
    def titles = ['Волчица и пряности', 'Тест: второй эпизод']

    def routes = [
        '/3/tv/2260'         : JsonOutput.toJson([name: 'Спайс и Волк', first_air_date: '2008-01-09']),
        '/3/tv/2260/season/1': JsonOutput.toJson([episodes: titles.collect { [name: it] }])
    ]

    stageInput(workDir, 'Show.s01e01.mkv')
    stageInput(workDir, 'Show.s01e02.mkv')

    withStubServer(routes) { String baseUrl ->
        def (fetchCode, fetchOut) = runScript('fetch_episodes.groovy', workDir,
                                              ['--api-key', 'stub', '--show-id', '2260',
                                               '--season', '1', '--base-url', baseUrl])
        checkEquals(fetchCode, 0, "fetch exit code (output was:\n$fetchOut\n)")
    }

    // The bytes on disk must be valid UTF-8 regardless of what the platform
    // default happens to be, so decode strictly rather than trusting readLines
    def epFile = new File(workDir, 'episodes.txt')
    def decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    def decoded
    try {
        decoded = decoder.decode(ByteBuffer.wrap(epFile.bytes)).toString()
    } catch (Exception e) {
        throw new AssertionError("episodes.txt is not valid UTF-8: ${e}")
    }

    checkEquals(decoded.readLines(), ['Волчица и пряности', 'Тест второй эпизод'],
                'episodes.txt decoded as UTF-8')

    // Show name stays ASCII deliberately: a Cyrillic argument would test
    // subprocess argument encoding instead, and a failure there would look
    // identical to the episodes.txt regression this test exists to catch.
    def (code, out) = runScript('rename.groovy', workDir, ['My Show'])
    checkEquals(code, 0, "rename exit code (output was:\n$out\n)")

    def names = workDir.listFiles().collect { it.name } as Set
    check('My Show - S01E01 - Волчица и пряности.mkv' in names,
          "first file renamed with its Cyrillic title; got ${names}")
    check('My Show - S01E02 - Тест второй эпизод.mkv' in names,
          "second file renamed with its Cyrillic title; got ${names}")
    check(!names.any { it.contains('?') || it.contains('�') },
          "no replacement characters in the renamed files; got ${names}")
}

// ─── File masks ──────────────────────────────────────────────────────────────
// Note these run the script through ProcessBuilder, which passes argv straight
// through without a shell, so no pattern below is ever expanded before the
// script sees it. That is exactly the cmd.exe situation, which a Linux-only CI
// would otherwise never cover.

/** Names of the MKVs produced in workDir/mkv, sorted. */
def outputNames = { File workDir ->
    (new File(workDir, 'mkv').listFiles() ?: []).collect { it.name }.sort()
}

/** Stage three episodes plus a sample and a non-media file. */
def stageBatch = { File workDir ->
    ['Show.S01E01.mkv', 'Show.S01E02.mkv', 'Show.S01E03.mkv',
     'Show.S01E01.sample.mkv'].each { stageInput(workDir, it) }
    new File(workDir, 'notes.txt').text = 'not a video'
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true]]))
}

// ─── 39. a mask that is an exact file name selects only that file ────────────
runTest('39_file_mask_literal_name') { workDir ->
    stageBatch(workDir)
    def (code, out) = runMkvGroovy(workDir, ['Show.S01E02.mkv'])
    checkEquals(code, 0, 'exit code')
    checkEquals(outputNames(workDir), ['Show.S01E02.mkv'], 'only the named file muxed')
}

// ─── 40. a glob arrives unexpanded and is expanded by the script ─────────────
runTest('40_file_mask_glob_unexpanded') { workDir ->
    stageBatch(workDir)
    def (code, out) = runMkvGroovy(workDir, ['Show.S01E0[12].mkv'])
    checkEquals(code, 0, 'exit code')
    checkEquals(outputNames(workDir), ['Show.S01E01.mkv', 'Show.S01E02.mkv'],
                'bracket glob expanded by the script, and did not match the sample')
}

// ─── 41. several masks union together ────────────────────────────────────────
runTest('41_file_mask_multiple') { workDir ->
    stageBatch(workDir)
    def (code, out) = runMkvGroovy(workDir, ['Show.S01E01.mkv', 'Show.S01E03.mkv'])
    checkEquals(code, 0, 'exit code')
    checkEquals(outputNames(workDir), ['Show.S01E01.mkv', 'Show.S01E03.mkv'], 'both masks applied')
}

// ─── 42. --exclude removes files from an otherwise full batch ────────────────
runTest('42_file_mask_exclude') { workDir ->
    stageBatch(workDir)
    def (code, out) = runMkvGroovy(workDir, ['--exclude', '*.sample.mkv'])
    checkEquals(code, 0, 'exit code')
    checkEquals(outputNames(workDir),
                ['Show.S01E01.mkv', 'Show.S01E02.mkv', 'Show.S01E03.mkv'],
                'sample excluded, everything else still muxed')
}

// ─── 43. a mask matching nothing says so instead of silently doing nothing ───
runTest('43_file_mask_no_match') { workDir ->
    stageBatch(workDir)
    def (code, out) = runMkvGroovy(workDir, ['Nope.*.mkv'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('No files match'), 'reports that nothing matched')
    check(out.contains('Nope.*.mkv'), 'names the offending pattern')
    checkEquals(outputNames(workDir), [], 'nothing muxed')
    check(!new File(workDir, 'mkv').exists(), 'destination directory not created')
}

// ─── 44. a file whose own name contains glob metacharacters ──────────────────
// The reason masks are matched literally when they name an existing file: read
// as a glob, 'Odd[1].mkv' would also match 'Odd1.mkv', so there would otherwise
// be no way to select such a file at all.
runTest('44_file_mask_literal_beats_glob') { workDir ->
    stageInput(workDir, 'Odd[1].mkv')
    stageInput(workDir, 'Odd1.mkv')
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true]]))

    def (code, out) = runMkvGroovy(workDir, ['Odd[1].mkv'])
    checkEquals(code, 0, 'exit code')
    checkEquals(outputNames(workDir), ['Odd[1].mkv'], 'matched itself, not Odd1.mkv')
}

// ─── 45. masks apply to --identify too, not just muxing ──────────────────────
runTest('45_file_mask_applies_to_identify') { workDir ->
    stageBatch(workDir)
    def (code, out) = runMkvGroovy(workDir, ['--identify', 'Show.S01E02.mkv'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('Show.S01E02.mkv'),  'identifies the masked file')
    check(!out.contains('Show.S01E01.mkv'), 'does not identify the others')
    check(!new File(workDir, 'mkv').exists(), 'identify still muxes nothing')
}

// ─── Summary ─────────────────────────────────────────────────────────────────

println()
println '═' * 50
println "${passes.size()} passed, ${failures.size()} failed"
if (failures) {
    println()
    println 'FAILURES:'
    failures.each { println "  [FAIL] ${it.name}: ${it.msg}" }
    System.exit(1)
}
