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

// Shared colour helpers — auto mode: coloured on a dev terminal, plain when
// piped (CI logs) or under NO_COLOR. See src/output.groovy.
def ui = evaluate(new File(repoRoot, 'src/output.groovy'))('auto')

def isWindows = System.getProperty('os.name').toLowerCase().contains('win')
def groovyBin = isWindows ? 'bin/groovy.bat' : 'bin/groovy'
def groovyExe = new File(System.getProperty('groovy.home', ''), groovyBin).with {
    exists() ? absolutePath : 'groovy'
}

// Shared with the scripts under test; see src/tools.groovy.
def findMkvTool = evaluate(new File(repoRoot, 'src/tools.groovy'))

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

/** Run a command list; return [exitCode, stdout+stderr combined].
 *  env: extra environment variables for the child (e.g. NO_COLOR). */
def exec = { cmd, File cwd = null, Map env = [:] ->
    def pb = new ProcessBuilder(cmd.collect { it.toString() })
    pb.redirectErrorStream(true)
    // Ensure JAVA_HOME points at the JVM actually running this process
    pb.environment().put('JAVA_HOME', System.getProperty('java.home'))
    env.each { k, v -> pb.environment().put(k.toString(), v.toString()) }
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

/** Build a derivative MKV from test.mkv: choose which tracks survive and
 *  override names/languages/flags, for the consistency-check tests.
 *
 *  opts: audio / subs  -> list of source track IDs to keep, or null to drop the
 *        whole type; names / langs / defaults -> maps keyed by the OUTPUT track
 *        id; noChapters, chaptersFile.
 *
 *  NOTE mkvmerge renumbers the surviving tracks from 0 in source order, so the
 *  output IDs are not the source IDs. Assert on the variant's own identify()
 *  output when the exact id matters. */
def buildVariant = { File dest, Map opts = [:] ->
    def cmd = [mkvmergeExe, '--output', dest.absolutePath]
    if (!opts.containsKey('audio'))   cmd << '--no-audio'
    else if (opts.audio != null)      cmd.addAll(['--audio-tracks', opts.audio.join(',')])
    if (!opts.containsKey('subs'))    cmd << '--no-subtitles'
    else if (opts.subs != null)       cmd.addAll(['--subtitle-tracks', opts.subs.join(',')])
    if (opts.noChapters)              cmd << '--no-chapters'
    opts.names?.each    { id, n -> cmd.addAll(['--track-name',        "$id:$n"]) }
    opts.langs?.each    { id, l -> cmd.addAll(['--language',          "$id:$l"]) }
    opts.defaults?.each { id, v -> cmd.addAll(['--default-track-flag', "$id:${v ? 'yes' : 'no'}"]) }
    if (opts.chaptersFile)            cmd.addAll(['--chapters', opts.chaptersFile.absolutePath])
    cmd << testMkv.absolutePath
    def (code, out) = exec(cmd)
    assert (code == 0 || code == 1) : "buildVariant failed (exit $code):\n$out"
    dest
}

/** Write a minimal OGM-simple chapter file; mkvmerge reads it as text, so the
 *  chapter tests need no binary fixture. */
def writeChapters = { File workDir ->
    def f = new File(workDir, 'chapters.txt')
    f.text = "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\n" +
             "CHAPTER02=00:00:02.000\nCHAPTER02NAME=Main\n"
    f
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
def runScript = { String scriptName, File workDir, List extraArgs = [], Map env = [:] ->
    def script = new File(repoRoot, "src/${scriptName}")
    assert script.exists() : "script not found at $script"
    def result = exec([groovyExe, script.absolutePath] + extraArgs, workDir, env)
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
        println ui.green("[PASS] $name")
    } catch (Throwable t) {
        failures << [name: name, msg: t.message ?: t.toString()]
        println ui.red("[FAIL] $name: ${t.message ?: t}")
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

// ─── Companion file pre-flight ───────────────────────────────────────────────
// A dub studio releasing 22 of 24 episodes is normal. Without the pre-flight
// that surfaces as an mkvmerge failure partway through a long batch; with it,
// the two bad episodes are named up front and the other 22 still get muxed.

/** Config with one ${fileName} companion source. */
def companionCfg = {
    cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true]],
        trackOrder: '0:0,0:1,1:0',
        additionalSources: [[
            file: '${fileName}[Studio].mka',
            tracks: [[language: 'ru', title: 'Studio Dub', default: false]]
        ]])
}

// ─── 46. a missing companion skips that episode and muxes the rest ───────────
runTest('46_missing_companion_skips_that_episode') { workDir ->
    stageInput(workDir, 'Show.S01E01.mkv')
    stageInput(workDir, 'Show.S01E02.mkv')
    // Only episode 1 has its companion
    extractTrack(testMkv, new File(workDir, 'Show.S01E01[Studio].mka'), 'audio', 2)
    writeConfig(workDir, companionCfg())

    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('companion files are missing'), 'announces the skip')
    check(out.contains('${fileName}[Studio].mka'), 'names the source pattern')
    check(out.contains('Show.S01E02.mkv'), 'names the affected episode')

    checkEquals(outputNames(workDir), ['Show.S01E01.mkv'], 'only the complete episode was muxed')

    // and the one that was muxed really did get its companion track
    def audio = identify(new File(workDir, 'mkv/Show.S01E01.mkv')).tracks.findAll { it.type == 'audio' }
    check(audio.any { it.get('properties').track_name == 'Studio Dub' }, 'companion track muxed in')
}

// ─── 47. every companion present: no message, nothing skipped ────────────────
runTest('47_companions_all_present') { workDir ->
    stageInput(workDir, 'Show.S01E01.mkv')
    stageInput(workDir, 'Show.S01E02.mkv')
    extractTrack(testMkv, new File(workDir, 'Show.S01E01[Studio].mka'), 'audio', 2)
    extractTrack(testMkv, new File(workDir, 'Show.S01E02[Studio].mka'), 'audio', 2)
    writeConfig(workDir, companionCfg())

    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, 'exit code')
    check(!out.contains('companion files are missing'), 'no skip message when nothing is missing')
    checkEquals(outputNames(workDir), ['Show.S01E01.mkv', 'Show.S01E02.mkv'], 'both muxed')
}

// ─── 48. the pre-flight reports under --dry-run too ──────────────────────────
runTest('48_missing_companion_reported_in_dry_run') { workDir ->
    stageInput(workDir, 'Show.S01E01.mkv')
    stageInput(workDir, 'Show.S01E02.mkv')
    extractTrack(testMkv, new File(workDir, 'Show.S01E01[Studio].mka'), 'audio', 2)
    writeConfig(workDir, companionCfg())

    def (code, out) = runMkvGroovy(workDir, ['--dry-run'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('companion files are missing'), 'announces the skip')
    check(out.contains('Show.S01E01.mkv'),  'still previews the complete episode')
    // The blocked episode must not get a command line printed for it
    check(!out.contains('Show.S01E02.mkv.mkv'), 'no output path previewed for the blocked episode')
    check(!new File(workDir, 'mkv').exists(), 'dry run creates nothing')
}

// ─── 49. every episode blocked: says so rather than printing a bare Done ─────
runTest('49_all_companions_missing') { workDir ->
    stageInput(workDir, 'Show.S01E01.mkv')
    stageInput(workDir, 'Show.S01E02.mkv')
    writeConfig(workDir, companionCfg())

    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('companion files are missing'), 'announces the skip')
    check(out.contains('Nothing left to mux'), 'says the batch is empty rather than just Done')
    checkEquals(outputNames(workDir), [], 'nothing muxed')
    // The pre-flight bails before the mkdirs, so an empty batch leaves no litter
    check(!new File(workDir, 'mkv').exists(), 'destination directory not created')
}

// ─── to_utf8.groovy ──────────────────────────────────────────────────────────

/** Concatenate byte arrays — Groovy has no byte[] + byte[]. */
def catBytes = { byte[]... parts ->
    def out = new ByteArrayOutputStream()
    parts.each { out.write(it) }
    out.toByteArray()
}

def UTF8_BOM  = [0xEF, 0xBB, 0xBF] as byte[]
def UTF16_BOM = [0xFF, 0xFE] as byte[]

/** Write raw bytes into workDir under the given name. */
def writeBytes = { File workDir, String name, byte[] bytes ->
    def f = new File(workDir, name)
    f.bytes = bytes
    f
}

def runToUtf8 = { File workDir, List extraArgs = [] ->
    runScript('to_utf8.groovy', workDir, extraArgs)
}

// ─── 50. converts windows-1251 in place, keeping the file's line endings ─────
runTest('50_to_utf8_converts_in_place') { workDir ->
    def lf   = writeBytes(workDir, 'lf.srt',   'Привет, мир!\n'.getBytes('windows-1251'))
    def crlf = writeBytes(workDir, 'crlf.srt', 'Привет\r\nмир\r\n'.getBytes('windows-1251'))

    def (code, out) = runToUtf8(workDir)
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains('windows-1251'), 'states the source encoding it used')

    checkEquals(lf.getText('UTF-8'), 'Привет, мир!\n', 'LF file converted')
    checkEquals(crlf.getText('UTF-8'), 'Привет\r\nмир\r\n',
                'CRLF preserved — writing line by line would normalise these')
}

// ─── 51. already-UTF-8 input is left alone, so a re-run is a no-op ───────────
// Double-converting is how a previously fixed file gets corrupted, and this is
// what makes the script safe to point at a directory more than once.
runTest('51_to_utf8_skips_already_utf8') { workDir ->
    def plain = writeBytes(workDir, 'plain.srt', 'Привет\n'.getBytes('UTF-8'))
    def bom   = writeBytes(workDir, 'bom.ass',   catBytes(UTF8_BOM, 'Привет\n'.getBytes('UTF-8')))
    def ascii = writeBytes(workDir, 'ascii.vtt', 'WEBVTT\n\nplain\n'.getBytes('UTF-8'))
    def before = [plain, bom, ascii].collectEntries { [it.name, it.bytes.toList()] }

    def (code, out) = runToUtf8(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('3 skipped'), "all three skipped; got:\n$out")

    [plain, bom, ascii].each {
        checkEquals(it.bytes.toList(), before[it.name], "${it.name} byte-for-byte unchanged")
    }
}

// ─── 52. all four text subtitle formats, and .sub left strictly alone ────────
// .sub is ambiguous: MicroDVD text vs the binary half of a VobSub .idx/.sub
// pair. Rewriting the binary one would destroy it, so the extension is excluded.
runTest('52_to_utf8_extension_coverage') { workDir ->
    ['srt', 'ass', 'ssa', 'vtt'].each {
        writeBytes(workDir, "sub.${it}", 'Привет\n'.getBytes('windows-1251'))
    }
    // A fragment of a real MPEG program stream header, as a VobSub .sub starts
    def vobsub = writeBytes(workDir, 'movie.sub', [0x00, 0x00, 0x01, 0xBA, 0x44] as byte[])
    def vobsubBefore = vobsub.bytes.toList()   // capture as read back: Java bytes are signed

    def (code, out) = runToUtf8(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('4 converted'), "all four formats converted; got:\n$out")

    ['srt', 'ass', 'ssa', 'vtt'].each {
        checkEquals(new File(workDir, "sub.${it}").getText('UTF-8'), 'Привет\n', "sub.${it} converted")
    }
    checkEquals(vobsub.bytes.toList(), vobsubBefore, 'VobSub .sub untouched')
    check(!out.contains('movie.sub'), '.sub is not even considered')
}

// ─── 53. --encoding selects the source charset; an unknown one aborts ────────
runTest('53_to_utf8_explicit_encoding') { workDir ->
    // 0xC8 0x61 0x6A is "Čaj" in windows-1250, and not valid UTF-8
    def f = writeBytes(workDir, 'cz.srt', [0xC8, 0x61, 0x6A] as byte[])

    def (code, out) = runToUtf8(workDir, ['--encoding', 'windows-1250'])
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    checkEquals(f.getText('UTF-8'), 'Čaj', 'decoded with the charset that was asked for')
    check(out.contains('windows-1250'), 'names the encoding in use')
}

// ─── 54. an unusable charset name fails before any file is touched ───────────
runTest('54_to_utf8_unknown_encoding_aborts') { workDir ->
    def f = writeBytes(workDir, 'a.srt', 'Привет\n'.getBytes('windows-1251'))
    def before = f.bytes.toList()

    def (code, out) = runToUtf8(workDir, ['--encoding', 'not-a-charset'])
    checkEquals(code, 2, 'exits 2 on an unusable charset name')
    checkEquals(f.bytes.toList(), before, 'nothing was touched')
}

// ─── 55. bytes invalid in the source charset are refused, not mangled ────────
// Java's default decoder replaces malformed input with U+FFFD, so a wrong
// --encoding would otherwise "succeed" and write mojibake. Strict decoding is
// what turns that into a visible failure.
runTest('55_to_utf8_strict_decode_refuses') { workDir ->
    // 0x81 0x20 is a Shift_JIS lead byte followed by an invalid trail byte,
    // and is not valid UTF-8 either
    def f = writeBytes(workDir, 'bad.srt', [0x81, 0x20, 0x41] as byte[])
    def before = f.bytes.toList()

    def (code, out) = runToUtf8(workDir, ['--encoding', 'Shift_JIS'])
    checkEquals(code, 1, 'exits non-zero so a shell script can react')
    check(out.contains('not valid Shift_JIS'), 'says why it refused')
    checkEquals(f.bytes.toList(), before, 'file left exactly as it was')
}

// ─── 56. --backup keeps the original bytes alongside ─────────────────────────
runTest('56_to_utf8_backup') { workDir ->
    def original = 'Привет\n'.getBytes('windows-1251')
    def f = writeBytes(workDir, 'a.srt', original)

    def (code, out) = runToUtf8(workDir, ['--backup'])
    checkEquals(code, 0, 'exit code')

    checkEquals(f.getText('UTF-8'), 'Привет\n', 'original converted in place')
    def orig = new File(workDir, 'a.srt.orig')
    check(orig.exists(), 'backup written')
    checkEquals(orig.bytes.toList(), original.toList(), 'backup holds the original bytes')
}

// ─── 57. --dry-run reports without writing ───────────────────────────────────
runTest('57_to_utf8_dry_run') { workDir ->
    def original = 'Привет\n'.getBytes('windows-1251')
    def f = writeBytes(workDir, 'a.srt', original)

    def (code, out) = runToUtf8(workDir, ['--dry-run'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('would convert'), 'reports the planned conversion')
    checkEquals(f.bytes.toList(), original.toList(), 'nothing written')
    check(!new File(workDir, 'a.srt.orig').exists(), 'no backup written either')
}

// ─── 58. UTF-16 is refused rather than silently mangled ──────────────────────
// Every byte of a UTF-16 file maps to something in a single-byte charset, so a
// strict decode alone would "succeed" and write the mojibake back.
runTest('58_to_utf8_utf16_left_alone') { workDir ->
    def bytes = catBytes(UTF16_BOM, 'Привет\n'.getBytes('UTF-16LE'))
    def f = writeBytes(workDir, 'wide.ssa', bytes)

    def (code, out) = runToUtf8(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('UTF-16'), 'says it recognised UTF-16')
    checkEquals(f.bytes.toList(), bytes.toList(), 'left exactly as it was')
}

// ─── Consistency check (--check) ─────────────────────────────────────────────
// Fixtures are synthesised from test.mkv with buildVariant. Keeping all seven
// tracks preserves the source IDs 0-6, so name/flag splits land on a known ID;
// dropping a track produces a genuine absence at that ID.

/** A full 7-track copy of test.mkv with optional per-id overrides. */
def fullCopy = { File dest, Map overrides = [:] ->
    buildVariant(dest, [audio: [1, 2, 3], subs: [4, 5, 6]] + overrides)
}

/** config selecting audio 1,2 and subtitle 6 — the IDs the split tests touch. */
def checkCfg = {
    cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true],
                      [id: 2, language: 'ru', title: 'Russian',  default: false]],
        subtitleTracks: [[id: 6, language: 'ja', title: 'Signs', default: false]])
}

// ─── 59. a clean batch reports consistent, with no minority markers ──────────
runTest('59_check_clean_batch') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('consistent across all 2 file(s)'), 'reports consistent')
    check(!out.contains('<-'), 'no minority markers on a clean batch')
    check(!new File(workDir, 'mkv').exists(), '--check muxes nothing')
}

// ─── 60. a track-name split is grouped, minority named, majority not ─────────
runTest('60_check_name_split_grouping') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    // The minority (one file) is named after a <- marker; the majority is not
    def arrowLines = out.readLines().findAll { it.contains('<-') }
    check(arrowLines.any { it.contains('odd.mkv') }, 'minority file named')
    check(!arrowLines.any { it.contains('S01E01.mkv') || it.contains('S01E02.mkv') },
          'majority files not named as deviant')
    check(out.contains('config title "Russian"'), 'blocking label names the config title')
}

// ─── 61. grouping anchors on the majority, not the first file ────────────────
// The odd file sorts first alphabetically. First-file anchoring would flag the
// three normal files against it; majority anchoring flags the one odd file.
runTest('61_check_never_anchors_on_first_file') { workDir ->
    fullCopy(new File(workDir, 'a_odd.mkv'), [names: [2: 'Other Studio']])
    fullCopy(new File(workDir, 'b.mkv'))
    fullCopy(new File(workDir, 'c.mkv'))
    fullCopy(new File(workDir, 'd.mkv'))
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    // Tokenise the flagged names — a substring test would trip on "a_odd.mkv"
    // containing "d.mkv"
    def flagged = out.readLines().findAll { it.contains('<-') }
                     .collectMany { (it =~ /[\w.]+\.mkv/).collect { m -> m } } as Set
    check('a_odd.mkv' in flagged, 'the odd file is flagged')
    check(!(['b.mkv', 'c.mkv', 'd.mkv'].any { it in flagged }),
          'the three matching files are not flagged despite one sorting after the odd one')
}

// ─── 62. a file missing a track is a layout outlier, not a per-ID absence ────
// A dropped track changes the file's overall layout, so it is hoisted into the
// "different track layout" section rather than smeared across the per-ID table.
runTest('62_check_missing_track_is_layout_outlier') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    buildVariant(new File(workDir, 'nosub.mkv'), [audio: [1, 2, 3], subs: [4, 5]])  // no track 6
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('different track layout'), 'reports a layout difference')
    check(out.readLines().findAll { it.contains('<-') }.any { it.contains('nosub.mkv') },
          'names the file with the different layout')
    check(out.contains('selected track 6'), 'blocking label names the missing selected track')
}

// ─── 63. a per-file video title difference produces no discrepancy ───────────
runTest('63_check_video_title_ignored') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'), [names: [0: 'Episode One']])
    fullCopy(new File(workDir, 'S01E02.mkv'), [names: [0: 'Episode Two']])
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('consistent'), 'video title differences are ignored')
}

// ─── 64. chapters present in some files and not others is detected ───────────
runTest('64_check_chapters_split') { workDir ->
    fullCopy(new File(workDir, 'withchap.mkv'), [chaptersFile: writeChapters(workDir)])
    fullCopy(new File(workDir, 'nochap.mkv'))
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('Chapters: present in 1 file(s), absent in 1'), 'reports the chapter split')
    // Chapters are always informational, never blocking
    check(out.contains('informational'), 'chapters land under informational')
}

// ─── 65. two indistinguishable same-language tracks are flagged ──────────────
runTest('65_check_ambiguous_duplicate') { workDir ->
    // Two audio tracks, both eng AAC with no name: build from two sources. The
    // track-name targets source id 2, which is the audio track being kept.
    def dest = new File(workDir, 'amb.mkv')
    def (c, o) = exec([mkvmergeExe, '--output', dest.absolutePath,
                       '--no-subtitles', '--audio-tracks', '2', '--track-name', '2:', testMkv.absolutePath,
                       '--no-video', '--no-subtitles', '--audio-tracks', '2', '--track-name', '2:', testMkv.absolutePath])
    assert (c == 0 || c == 1) : "build failed:\n$o"
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'en', title: 'English', default: true]]))

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('Ambiguous track IDs'), 'reports the ambiguity')
    check(out.contains('cannot distinguish'), 'explains why it matters')
}

// ─── 66. a name on one of the pair removes the ambiguity ─────────────────────
// The false-positive guard: type+language+codec+name must ALL match.
runTest('66_check_named_duplicate_not_flagged') { workDir ->
    def dest = new File(workDir, 'named.mkv')
    def (c, o) = exec([mkvmergeExe, '--output', dest.absolutePath,
                       '--no-subtitles', '--audio-tracks', '2', '--track-name', '2:', testMkv.absolutePath,
                       '--no-video', '--no-subtitles', '--audio-tracks', '2', '--track-name', '2:Commentary', testMkv.absolutePath])
    assert (c == 0 || c == 1) : "build failed:\n$o"
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'en', title: 'English', default: true]]))

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(!out.contains('Ambiguous track IDs'), 'a distinguishing name suppresses the flag')
}

// ─── 67. a discrepancy on a selected track is blocking ───────────────────────
runTest('67_check_blocking_when_selected') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    // config selects track 2, and not all audio is copied (3 is left out)
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true],
                                           [id: 2, language: 'ru', title: 'Russian',  default: false]]))

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('affect tracks that config.yaml selects') ||
          out.contains('affects a track that config.yaml selects'), 'classified blocking')
    check(out.contains('track 2'), 'names the selected track')
}

// ─── 68. the same discrepancy on an unselected track is informational ────────
runTest('68_check_informational_when_unselected') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    // config selects only track 1, so a split on track 2 cannot mis-select
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true]]))

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('informational'), 'classified informational')
    check(!out.contains('affect tracks that config.yaml selects') &&
          !out.contains('affects a track that config.yaml selects'), 'no blocking section')
}

// ─── 69. informational when all tracks of the type are copied ────────────────
// A split on track 2 cannot mis-select when audio 1,2,3 are ALL selected: IDs
// cannot point at the wrong track if every track is being taken.
runTest('69_check_informational_when_all_copied') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true],
                                           [id: 2, language: 'ru', title: 'Russian',  default: false],
                                           [id: 3, language: 'ru', title: 'Russian 2', default: false]]))

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('informational'), 'classified informational when the whole type is copied')
}

// ─── 70. --strict aborts on a blocking discrepancy ───────────────────────────
runTest('70_check_strict_aborts_on_blocking') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true],
                                           [id: 2, language: 'ru', title: 'Russian',  default: false]]))

    def (code, out) = runMkvGroovy(workDir, ['--strict'])
    checkEquals(code, 2, 'exits 2 on a blocking discrepancy under --strict')
    check(out.contains('aborting'), 'says it aborted')
    check(!new File(workDir, 'mkv').exists(), 'nothing muxed, no output dir')
}

// ─── 71. --strict continues when findings are only informational ─────────────
runTest('71_check_strict_continues_on_informational') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true]]))

    def (code, out) = runMkvGroovy(workDir, ['--strict'])
    checkEquals(code, 0, 'informational findings do not abort under --strict')
    checkEquals(outputNames(workDir), ['S01E01.mkv', 'S01E02.mkv', 'odd.mkv'], 'all three muxed')
}

// ─── 72. --no-check suppresses the report and still muxes ────────────────────
runTest('72_check_no_check_suppresses') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true],
                                           [id: 2, language: 'ru', title: 'Russian',  default: false]]))

    def (code, out) = runMkvGroovy(workDir, ['--no-check'])
    checkEquals(code, 0, 'exit code')
    check(!out.contains('Pre-flight'), 'no report printed')
    checkEquals(outputNames(workDir), ['S01E01.mkv', 'odd.mkv'], 'both still muxed')
}

// ─── 73. the default run checks first, then muxes ────────────────────────────
runTest('73_check_runs_by_default') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('Pre-flight'), 'the check runs without being asked')
    check(out.indexOf('Pre-flight') < out.indexOf('Processing'), 'check comes before muxing')
    checkEquals(outputNames(workDir), ['S01E01.mkv', 'S01E02.mkv'], 'files still muxed')
}

// ─── 74. --identify and --check combined print both, mux nothing, probe once ─
runTest('74_check_and_identify_combined') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--identify', '--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('Pre-flight'), 'cross-file report present')
    check(out.contains('*** S01E01.mkv'), 'per-file identify table present')
    check(!new File(workDir, 'mkv').exists(), 'combined mode muxes nothing')
}

// ─── 75. an unidentifiable file is excluded, not crashed on ──────────────────
runTest('75_check_survives_unidentifiable_file') { workDir ->
    fullCopy(new File(workDir, 'good.mkv'))
    new File(workDir, 'broken.mkv').bytes = new byte[2048]   // zero bytes: not a media file
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('could not be identified'), 'reports the bad file')
    check(out.contains('broken.mkv'), 'names it')
    check(!out.contains('Exception'), 'no stack trace')
}

// ─── 76. an even split names every file, since neither group is the reference ─
runTest('76_check_even_split_names_all') { workDir ->
    fullCopy(new File(workDir, 'a.mkv'))
    fullCopy(new File(workDir, 'b.mkv'))
    fullCopy(new File(workDir, 'c.mkv'), [names: [2: 'Other']])
    fullCopy(new File(workDir, 'd.mkv'), [names: [2: 'Other']])
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    // With no strict majority there is no unnamed reference row, so both value
    // groups list their files. Scan the whole report for names, not just the
    // "<-" lines: with one name per line, only the first of each list has the
    // arrow. Safe here because no track name in this fixture ends in .mkv.
    def flagged = (out =~ /[\w.]+\.mkv/).collect { it } as Set
    check(['a.mkv', 'b.mkv', 'c.mkv', 'd.mkv'].every { it in flagged },
          'every file is named when the split is even')
}

// ─── 77. structurally different files are grouped by layout, not scattered ───
// The Blindspot case: some episodes have the subtitle track first (a different
// track order), so a per-ID comparison would show the same files at three
// different IDs. They must instead be collected into one "different layout"
// group, leaving the common-layout files to compare cleanly.
runTest('77_check_layout_grouping') { workDir ->
    // Common 3-track layout: video, audio, subs
    ['a.mkv', 'b.mkv', 'c.mkv'].each { buildVariant(new File(workDir, it), [audio: [1], subs: [4]]) }
    // The same three tracks with the subtitle first — a pure track-order shift
    ['x.mkv', 'y.mkv'].each { name ->
        def (cc, oo) = exec([mkvmergeExe, '--output', new File(workDir, name).absolutePath,
                             '--audio-tracks', '1', '--subtitle-tracks', '4',
                             '--track-order', '0:4,0:0,0:1', testMkv.absolutePath])
        assert (cc == 0 || cc == 1) : "build failed:\n$oo"
    }
    writeConfig(workDir, cfg(audioTracks:    [[id: 1, language: 'ja', title: 'Japanese', default: true]],
                             subtitleTracks: [[id: 2, language: 'ja', title: 'Signs',    default: false]]))

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('different track layout'), 'shifted files reported as a layout group')

    def flagged = (out =~ /[\w.]+\.mkv/).collect { it } as Set
    check('x.mkv' in flagged && 'y.mkv' in flagged, 'both shifted files named in the layout group')
    check(!(['a.mkv', 'b.mkv', 'c.mkv'].any { it in flagged }), 'common-layout files not flagged')
    check(out.contains('Layout 1 (3 files)'), 'the three common-layout files are the largest group')
    check(out.contains('Layout 2 (2 files)'), 'the two shifted files are their own layout group')
    // The shift lands audio/subs on IDs the config selects, so it must block
    check(out.contains('a different track layout, at selected track'), 'shift classified as blocking')
}

// ─── 78. --check-verbose implies --check and muxes nothing ───────────────────
// It is a modifier on the report, not "mux with a verbose pre-flight" — a bare
// --check-verbose used to fall through and mux the whole batch.
runTest('78_check_verbose_does_not_mux') { workDir ->
    fullCopy(new File(workDir, 'a.mkv'))
    fullCopy(new File(workDir, 'b.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--check-verbose'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('Pre-flight'), 'the report is printed')
    check(!out.contains('Processing'), 'nothing is muxed')
    check(!new File(workDir, 'mkv').exists(), 'no output directory created')
}

// ─── 79. a muxing run with no config.yaml is a clean error, not a stack trace ─
// The script no longer falls back to the example config next to itself, so a
// media directory with no config gets told what to do rather than silently
// muxing with unrelated selections. (--check is exempt; see test 81.)
runTest('79_missing_config_clean_error') { workDir ->
    stageInput(workDir, 'E01.mkv')   // media present, but no config.yaml
    def (code, out) = runMkvGroovy(workDir, ['--dry-run'])
    checkEquals(code, 2, 'exits 2 when a muxing run finds no config')
    check(out.contains('No config.yaml'), 'explains the problem')
    check(out.contains('config.example.yaml'), 'points at the shipped template')
    check(!out.contains('Exception'), 'no stack trace')
}

// ─── 80. --config points at a config file outside the current directory ──────
runTest('80_explicit_config_path') { workDir ->
    stageInput(workDir, 'E01.mkv')
    def cfgDir = new File(workDir, 'configs'); cfgDir.mkdirs()
    new File(cfgDir, 'show.yaml').text =
        cfg(audioTracks: [[id: 1, language: 'ja', title: 'JP', default: true]])

    def (code, out) = runMkvGroovy(workDir, ['--check', '--config', 'configs/show.yaml'])
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains('Pre-flight check: 1 file'), 'ran the check using the pointed-at config')
}

// ─── 81. --check runs without a config, reporting structure but not classifying ─
// Useful before a config exists: check a season is consistent, then write the
// config against it. Only a muxing run needs the config.
runTest('81_check_without_config') { workDir ->
    fullCopy(new File(workDir, 'a.mkv'))
    fullCopy(new File(workDir, 'b.mkv'), [names: [2: 'Other Studio']])
    // deliberately no config.yaml

    def (code, out) = runMkvGroovy(workDir, ['--check'])
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains('Pre-flight'), 'the structural report still runs')
    check(out.contains('difference(s) across the batch'), 'counts the differences it found')
    check(out.contains('Add a config'), 'notes that classifying them needs a config')
    check(!out.contains('config.yaml selects'), 'no blocking classification without a config')
    check(!new File(workDir, 'mkv').exists(), 'still muxes nothing')
}

// ─── 82. --color always emits ANSI escapes ───────────────────────────────────
// The harness captures output through a pipe, so auto-mode colour is off in
// every other test; this is the one place the escapes themselves are asserted.
// ESC is built at runtime so no raw control byte lives in this source file.
def esc = "${(char) 27}"
runTest('82_color_always_emits_ansi') { workDir ->
    fullCopy(new File(workDir, 'a.mkv'))
    fullCopy(new File(workDir, 'b.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, checkCfg())

    def (code, out) = runMkvGroovy(workDir, ['--check', '--color', 'always'])
    checkEquals(code, 0, 'exit code')
    check(out.contains("${esc}[33m"), 'differing cells and warnings are yellow')
    check(out.contains("${esc}[36m"), 'section and table headers are cyan')
    check(out.contains("${esc}[32m"), 'the final Done is green')
    check(out.contains("${esc}[90m"), 'file-evidence lists are gray')
    check(out.contains("${esc}[0m"), 'escapes are reset')
    // The whole-line/whole-cell invariant: colour must not break the pinned
    // phrases the other check tests assert on.
    check(out.contains('*** Pre-flight check'), 'header text survives colouring intact')
}

// ─── 83. --color never suppresses ANSI; explicit always beats NO_COLOR ───────
runTest('83_color_never_and_no_color_precedence') { workDir ->
    fullCopy(new File(workDir, 'a.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, checkCfg())

    def (c1, out1) = runMkvGroovy(workDir, ['--check', '--color', 'never'])
    checkEquals(c1, 0, 'exit code (never)')
    check(!out1.contains("${esc}["), 'no escapes under --color never')

    // NO_COLOR (no-color.org) disables auto-detection, but an explicit
    // --color always is a direct request and wins. NO_COLOR-under-auto cannot
    // be exercised through a subprocess at all: the pipe already turns auto
    // off, so that path is covered by every other (escape-free) test instead.
    def (c2, out2) = runScript('mux.groovy', workDir, ['--check', '--color', 'always'], [NO_COLOR: '1'])
    checkEquals(c2, 0, 'exit code (always + NO_COLOR)')
    check(out2.contains("${esc}["), 'explicit --color always beats NO_COLOR')
}

// ─── 84. to_utf8 colours its summary by outcome, whole-line only ─────────────
runTest('84_to_utf8_color_summary') { workDir ->
    writeBytes(workDir, 'ok.srt', 'Привет\n'.getBytes('windows-1251'))

    def (c1, out1) = runToUtf8(workDir, ['--color', 'always'])
    checkEquals(c1, 0, 'exit code (clean)')
    check(out1.contains("${esc}[32m*** 1 converted, 0 skipped, 0 failed${esc}[0m"),
          "clean summary is green with the escapes at the line edges; got:\n$out1")

    // Same Shift_JIS-invalid bytes as test 55: not valid UTF-8 either, so the
    // file reaches the strict decode and fails there.
    writeBytes(workDir, 'bad.srt', [0x81, 0x20, 0x41] as byte[])
    def (c2, out2) = runToUtf8(workDir, ['--encoding', 'Shift_JIS', '--color', 'always'])
    checkEquals(c2, 1, 'exit code (failure)')
    check(out2.contains("${esc}[31m"), 'failure output carries red')
    check(out2.contains('1 failed'), 'pinned summary substring survives colouring')
}

// ─── 85. filename_to_title writes the bare name, no stray quotes ─────────────
// Regression guard for the embedded-\" bug: list-exec needs no manual quoting,
// so the title must be exactly the base file name on every platform.
runTest('85_filename_to_title_sets_title') { workDir ->
    if (!mkvpropeditExe) {
        println "  (skipped: mkvpropedit not available)"
        return
    }

    def input = stageInput(workDir, 'My Episode.mkv')
    def (code, out) = runScript('filename_to_title.groovy', workDir)
    checkEquals(code, 0, 'exit code')

    def parsed = identify(input)
    checkEquals(parsed.container.get('properties').title, 'My Episode', 'segment title, unquoted')
    def video = parsed.tracks.find { it.type == 'video' }
    checkEquals(video.get('properties').track_name, 'My Episode', 'video track name, unquoted')
    check(out.contains('1 processed, 0 failed'), 'summary printed')
}

// ─── 86. filename_to_title continues past a bad file and exits 1 ─────────────
runTest('86_filename_to_title_exit_on_failure') { workDir ->
    if (!mkvpropeditExe) {
        println "  (skipped: mkvpropedit not available)"
        return
    }

    new File(workDir, 'bad.mkv').text = 'not an mkv at all'
    def good = stageInput(workDir, 'good.mkv')

    def (code, out) = runScript('filename_to_title.groovy', workDir)
    checkEquals(code, 1, 'exits 1 when any file failed')
    check(out.contains('*** Error:'), 'reports the failure')
    check(out.contains('1 processed, 1 failed'), 'summary counts both outcomes')
    checkEquals(identify(good).container.get('properties').title, 'good',
                'the good file was still processed')
}

// ─── 87. fix_srt reformats the legacy format into .srt.fixed ─────────────────
// First-ever coverage of fix_srt: legacy "hh:mm:ss.cc,hh:mm:ss.cc" timing plus
// [br] line breaks become numbered SRT cues with "-->" timing.
runTest('87_fix_srt_fixes_valid_file') { workDir ->
    new File(workDir, 'ep.srt').text =
        '00:01:41.42,00:01:42.30\n' +
        'Line one[br]Line two\n' +
        '\n' +
        '00:01:43.00,00:01:44.00\n' +
        'Another line\n' +
        '\n'

    def (code, out) = runScript('fix_srt.groovy', workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('Fixing ep.srt'), 'names the file')
    check(out.contains('1 fixed, 0 failed'), 'summary printed')

    def fixedFile = new File(workDir, 'ep.srt.fixed')
    check(fixedFile.exists(), '.fixed output written next to the source')
    def lines = fixedFile.readLines()
    checkEquals(lines[0], '1', 'first cue number')
    checkEquals(lines[1], '00:01:41,420 --> 00:01:42,300', 'SRT arrow timing')
    checkEquals(lines[2], 'Line one', '[br] split, first half')
    checkEquals(lines[3], 'Line two', '[br] split, second half')
    checkEquals(lines[5], '2', 'second cue numbered')
}

// ─── 88. fix_srt survives a malformed file, fixes the rest, exits 1 ──────────
// One bad file used to kill the whole run with a stack trace (a bare assert).
runTest('88_fix_srt_exit_on_failure') { workDir ->
    new File(workDir, 'bad.srt').text =
        '00:01:41.42,00:01:42.30\n' +
        'Text\n' +
        'not-blank where a blank line belongs\n'
    new File(workDir, 'good.srt').text =
        '00:01:43.00,00:01:44.00\n' +
        'Fine\n' +
        '\n'

    def (code, out) = runScript('fix_srt.groovy', workDir)
    checkEquals(code, 1, 'exits 1 when any file failed')
    check(out.contains('*** Error:') && out.contains('bad.srt'), 'error names the bad file')
    check(!out.contains('Exception'), 'no stack trace')
    check(out.contains('1 fixed, 1 failed'), 'summary counts both outcomes')
    check(new File(workDir, 'good.srt.fixed').exists(), 'the good file was still fixed')
    check(!new File(workDir, 'bad.srt.fixed').exists(), 'no partial output for the bad file')
}

// ─── 89. propedit exits 1 when a file fails, and says so ─────────────────────
// The exit-1 contract was documented from the start but never test-pinned.
runTest('89_propedit_failure_exit') { workDir ->
    if (!mkvpropeditExe) {
        println "  (skipped: mkvpropedit not available)"
        return
    }

    new File(workDir, 'bad.mkv').text = 'not an mkv at all'
    def (code, out) = runScript('propedit.groovy', workDir,
                                ['--edit', 'info', '--set', 'title=X'])
    checkEquals(code, 1, 'exits 1 when any file failed')
    check(out.contains('*** Error:'), 'reports the failure')
    check(out.contains('0 processed, 1 failed'), 'summary counts the failure')
}

// ─── 90. an empty batch says why instead of a bare "Done" ────────────────────
// A green "Done" in an empty (or all-non-media) directory looks identical to a
// successful run that had no work — e.g. after a typo'd cd.
runTest('90_no_media_files_reported') { workDir ->
    // Empty directory, no masks
    def (c1, out1) = runMkvGroovy(workDir, ['--check'])
    checkEquals(c1, 0, 'exit code (empty dir)')
    check(out1.contains('No media files'), 'says the directory has nothing to work on')
    check(out1.contains('mkv'), 'names the extensions it looked for')

    // A mask that matches a file, just not a media file
    new File(workDir, 'notes.txt').text = 'not media'
    def (c2, out2) = runMkvGroovy(workDir, ['--identify', 'notes.txt'])
    checkEquals(c2, 0, 'exit code (non-media match)')
    check(out2.contains('No media files match: notes.txt'), 'names the fruitless pattern')
    check(!new File(workDir, 'mkv').exists(), 'no output directory created')
}

// ─── Summary ─────────────────────────────────────────────────────────────────

println()
println '═' * 50
def summary = "${passes.size()} passed, ${failures.size()} failed"
if (failures) {
    println ui.red(summary)
    println()
    println 'FAILURES:'
    failures.each { println ui.red("  [FAIL] ${it.name}: ${it.msg}") }
    System.exit(1)
}
println ui.green(summary)
