@Grab('info.picocli:picocli-groovy:4.6.3')
@Grab('commons-io:commons-io:2.11.0')
@Grab('org.yaml:snakeyaml:1.30')
@GrabConfig(systemClassLoader=true)
import groovy.transform.Field
import org.yaml.snakeyaml.Yaml
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

@CommandLine.Option(names=['--target'], paramLabel='scripts|app',
                    description='What each case runs against: the Groovy scripts (default) or the mkvtool binary')
@Field String runTarget = 'scripts'

@CommandLine.Option(names=['--app-bin'], paramLabel='PATH',
                    description='Override the mkvtool binary used by --target app (default: installDist launcher)')
@Field String appBinOverride = null

// ─── Paths ──────────────────────────────────────────────────────────────────

def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def repoRoot  = scriptDir.parentFile.parentFile          // …/mkv-script
def testMkv   = new File(scriptDir, 'test.mkv')
def mkvgroovy = new File(repoRoot, 'src/mux.groovy')
def binDir    = new File(repoRoot, 'bin')
def workRoot  = new File(scriptDir, 'work')

// Shared colour helpers — auto mode: coloured on a dev terminal, plain when
// piped (CI logs) or under NO_COLOR. See src/output.groovy.
def ui = evaluate(new File(repoRoot, 'src/lib/output.groovy'))('auto')

def isWindows = System.getProperty('os.name').toLowerCase().contains('win')
def groovyBin = isWindows ? 'bin/groovy.bat' : 'bin/groovy'
def groovyExe = new File(System.getProperty('groovy.home', ''), groovyBin).with {
    exists() ? absolutePath : 'groovy'
}

// --target app runs the mkvtool binary instead of the Groovy scripts. Default is the
// installDist launcher; --app-bin / MKVTOOL_APP_BIN overrides it (e.g. the native binary).
def appBin = {
    def override = appBinOverride ?: System.getenv('MKVTOOL_APP_BIN')
    if (override) return new File(override)
    def launcher = isWindows ? 'build/install/mkvtool/bin/mkvtool.bat'
                             : 'build/install/mkvtool/bin/mkvtool'
    new File(repoRoot, launcher)
}()

// Shared with the scripts under test; see src/tools.groovy.
def findMkvTool = evaluate(new File(repoRoot, 'src/lib/tools.groovy'))

def mkvmergeExe = mkvmergeExeOverride ?: findMkvTool('mkvmerge')

// Optional: propedit tests skip themselves when this is absent
def mkvpropeditExe = null
try {
    mkvpropeditExe = findMkvTool('mkvpropedit')
} catch (ignored) {}

assert testMkv.exists()   : "test.mkv not found at $testMkv"
assert mkvgroovy.exists() : "mux.groovy not found at $mkvgroovy"

// A missing binary under --target app is a setup error, not a per-case skip: fail loudly
// and point at the build step instead of silently passing.
if (runTarget == 'app') {
    assert appBin.exists() : "mkvtool binary not found at $appBin — run './gradlew installDist' first (or pass --app-bin)"
}

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

/** Write episodes.yaml into workDir. `episodes` is a map of episode number to
 *  raw (unsanitized) name; the rest of the metadata is optional.
 *  Built with snakeyaml rather than by hand so that the titles under test —
 *  which contain colons and quotes on purpose — are quoted correctly. */
def writeEpisodesYaml = { File workDir, Map opts ->
    def data = new LinkedHashMap()
    // containsKey, not truthiness: an empty show name is a case under test
    data.show = opts.containsKey('show') ? opts.show : 'Stub Show'
    if (opts.year) data.year = opts.year
    data.season = opts.containsKey('season') ? opts.season : 1
    if (opts.seasonName) data.seasonName = opts.seasonName
    if (opts.language) data.language = opts.language
    data.episodes = (opts.episodes ?: [:]).collect { number, name ->
        def entry = new LinkedHashMap()
        entry.episode = number as int
        entry.name = name
        entry
    }
    def dumper = new org.yaml.snakeyaml.DumperOptions()
    dumper.defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
    dumper.allowUnicode = true
    new File(workDir, 'episodes.yaml').setText(new Yaml(dumper).dump(data), 'UTF-8')
}

/** Copy test.mkv into workDir under the given name. */
def stageInput = { File workDir, String name = 'test.mkv' ->
    def dest = new File(workDir, name)
    FileUtils.copyFile(testMkv, dest)
    dest
}

/** Write a text external file (subtitles) at a path relative to workDir,
 *  creating directories as needed. The content is deliberately unimportant:
 *  nothing probes these formats, which is half of what the discovery tests
 *  assert. */
def stageExternalText = { File workDir, String relPath, String text = "1\n00:00:01,000 --> 00:00:02,000\nHi\n" ->
    def f = new File(workDir, relPath)
    f.parentFile?.mkdirs()
    f.setText(text, 'UTF-8')
    f
}

/** Extract one track of test.mkv to a path relative to workDir, for the external
 *  files that DO get probed (.mka/.mks carry real language and track names).
 *  Pass a language to override the extracted track's own — 'und' is how Matroska
 *  spells "untagged", which is what the path-based language guess is for. */
def stageExternalTrack = { File workDir, String relPath, String trackType, int trackId,
                           String language = null ->
    def dest = new File(workDir, relPath)
    if (language == null) {
        extractTrack(testMkv, dest, trackType, trackId)
        return dest
    }
    dest.parentFile?.mkdirs()
    def flag   = trackType == 'audio' ? '--audio-tracks' : '--subtitle-tracks'
    def noFlag = trackType == 'audio' ? '--no-subtitles' : '--no-audio'
    def (code, out) = exec([mkvmergeExe, '--output', dest.absolutePath, '--no-video', noFlag,
                            flag, "$trackId", '--language', "${trackId}:${language}",
                            testMkv.absolutePath])
    assert (code == 0 || code == 1) : "stageExternalTrack failed (exit $code):\n$out"
    dest
}

/** The shared multi-directory fixture, modelled on a real anime release: three
 *  episodes, two dub groups with different coverage, one of them also supplying
 *  subtitles from a second category directory (the merge case), a suffixed
 *  sibling in the media directory itself, one file belonging to nothing, and an
 *  extras folder holding a stray .mkv. */
def stageTree = { File workDir ->
    ['01', '02', '03'].each { ep ->
        stageInput(workDir, "Show - S01E${ep} - Title.mkv".toString())
        stageExternalTrack(workDir, "Rus sound/[GroupA]/Show - S01E${ep} - Title.mka".toString(), 'audio', 3)
    }
    ['01', '02'].each { ep ->
        stageExternalText(workDir, "Rus subs/[GroupA]/Show - S01E${ep} - Title.ass".toString())
    }
    // Untagged on purpose: Matroska reports it as 'und', which has to fall back
    // to the folder's language guess.
    stageExternalTrack(workDir, 'Rus sound/[GroupB]/Show - S01E01 - Title.mka', 'audio', 2, 'und')
    stageExternalText(workDir, 'Show - S01E01 - Title.rus.srt')
    stageExternalText(workDir, 'Rus subs/[GroupA]/Bonus.ass')
    stageInput(workDir, 'extras/Sample.mkv')
}

/** Run any script from the repo's src/ in workDir; return [exitCode, output].
 *  The output is also kept for diagnostics if the test fails. */
def runScript = { String scriptName, File workDir, List extraArgs = [], Map env = [:] ->
    List cmd
    if (runTarget == 'app') {
        // scriptName carries the .groovy suffix; map it to the subcommand (strip suffix,
        // underscores to hyphens) — the inverse of the bin/ wrapper rule.
        def subcmd = scriptName.replaceAll(/\.groovy$/, '').replace('_', '-')
        // ProcessBuilder cannot launch a .bat directly, so route it through cmd /c;
        // a native .exe (via --app-bin) is launched directly.
        def prefix = appBin.name.toLowerCase().endsWith('.bat') ? ['cmd', '/c'] : []
        cmd = prefix + [appBin.absolutePath, subcmd] + extraArgs
    } else {
        def script = new File(repoRoot, "src/${scriptName}")
        assert script.exists() : "script not found at $script"
        cmd = [groovyExe, script.absolutePath] + extraArgs
    }
    def result = exec(cmd, workDir, env)
    lastMkvOutput = result[1]
    result
}

/** Run mux.groovy from workDir; return [exitCode, output]. */
def runMkvGroovy = { File workDir, List extraArgs = [] ->
    runScript('mux.groovy', workDir, extraArgs)
}

/** Run inspect.groovy from workDir; return [exitCode, output]. */
def runInspect = { File workDir, List extraArgs = [] ->
    runScript('inspect.groovy', workDir, extraArgs)
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

/** Skip a case under --target app (it tests script-only mechanics). Returns true when
 *  skipped so the caller can `return`; prints a note like the other skip guards. */
def skipUnderApp = { String reason ->
    if (runTarget == 'app') { println "  (skipped under --target app: $reason)"; return true }
    false
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
    // Segment (container) title. Omitted unless asked for, so the default —
    // the file name — stays the thing every other test exercises.
    if (opts.generalTitle) sb << "  title: \"${opts.generalTitle}\"\n"

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
// 18 hand-written files; this catches typos for the price of a directory listing.
runTest('25_wrappers_exist_and_resolve') { workDir ->
    if (skipUnderApp('tests the Groovy bin/ wrappers')) return
    def wrappers = [
        'mkv-mux'                : 'mux.groovy',
        'mkv-inspect'            : 'inspect.groovy',
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
    if (skipUnderApp('tests the Groovy bin/ wrappers')) return
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

    def (code, out) = runInspect(workDir, ['--identify'])
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

// ─── 35. fetch_episodes against a local stub: both files, raw names ──────────
// Offline and deterministic, so it runs everywhere including CI.
//
// Asserts that fetch_episodes strips nothing: both episodes.yaml and
// episodes.txt must carry the name exactly as TheMovieDB spells it, so that
// mux.groovy can put the real ':' and '?' into a title. Making a name safe for
// a file name belongs to rename.groovy — tests 94 and 95 cover that end.
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
        '/3/tv/2260/season/1': JsonOutput.toJson([
            name    : 'Season 1',
            episodes: rawTitles.withIndex().collect { t, i -> [episode_number: i + 1, name: t] }
        ])
    ]

    withStubServer(routes) { String baseUrl ->
        def (code, out) = runScript('fetch_episodes.groovy', workDir,
                                    ['--api-key', 'stub', '--show-id', '2260',
                                     '--season', '1', '--base-url', baseUrl])
        checkEquals(code, 0, 'exit code')
        check(out.contains('Stub Show'), 'prints the show name')
        check(out.contains('2006'), 'prints the first-air year')

        checkEquals(new File(workDir, 'episodes.txt').readLines('UTF-8'), rawTitles,
                    'episodes.txt carries raw names')

        def yaml = new Yaml().load(new File(workDir, 'episodes.yaml').getText('UTF-8'))
        checkEquals(yaml.show, 'Stub Show', 'episodes.yaml show name')
        checkEquals(yaml.year, 2006, 'episodes.yaml year')
        checkEquals(yaml.season, 1, 'episodes.yaml season')
        checkEquals(yaml.seasonName, 'Season 1', 'episodes.yaml season name')
        checkEquals(yaml.episodes.collect { it.episode }, (1..6).toList(), 'episodes.yaml episode numbers')
        checkEquals(yaml.episodes.collect { it.name }, rawTitles, 'episodes.yaml raw names')

        // The characters that cannot survive a file name are precisely the ones
        // this file exists to preserve
        check(yaml.episodes[1].name.contains(':') && yaml.episodes[1].name.contains('?'),
              'colon and question mark survive into episodes.yaml')
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
        '/3/tv/2260/season/1': JsonOutput.toJson([
            episodes: titles.withIndex().collect { t, i -> [episode_number: i + 1, name: t] }
        ])
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

    // Raw: the colon is stripped where the name becomes a file name, below, not
    // where it is written here.
    checkEquals(decoded.readLines(), ['Волчица и пряности', 'Тест: второй эпизод'],
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
    def (code, out) = runInspect(workDir, ['--identify', 'Show.S01E02.mkv'])
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

    def (code, out) = runInspect(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('consistent across 2 files'), 'reports consistent')
    check(!out.contains('<-'), 'no minority markers on a clean batch')
    check(!new File(workDir, 'mkv').exists(), '--check muxes nothing')
}

// ─── 60. a track-name split is grouped, minority named, majority not ─────────
runTest('60_check_name_split_grouping') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'))
    fullCopy(new File(workDir, 'S01E02.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, checkCfg())

    def (code, out) = runInspect(workDir, ['--check'])
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

    def (code, out) = runInspect(workDir, ['--check'])
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

    def (code, out) = runInspect(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('different track layout'), 'reports a layout difference')
    // Membership lists carry no "<-": that marker means "these rows deviate",
    // which is a different question from "these files are in this group".
    check(out.contains('nosub.mkv'), 'names the file with the different layout')
    check(out.contains('selected track 6'), 'blocking label names the missing selected track')
}

// ─── 63. a per-file video title difference produces no discrepancy ───────────
runTest('63_check_video_title_ignored') { workDir ->
    fullCopy(new File(workDir, 'S01E01.mkv'), [names: [0: 'Episode One']])
    fullCopy(new File(workDir, 'S01E02.mkv'), [names: [0: 'Episode Two']])
    writeConfig(workDir, checkCfg())

    def (code, out) = runInspect(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('consistent'), 'video title differences are ignored')
}

// ─── 64. chapters present in some files and not others is detected ───────────
runTest('64_check_chapters_split') { workDir ->
    fullCopy(new File(workDir, 'withchap.mkv'), [chaptersFile: writeChapters(workDir)])
    fullCopy(new File(workDir, 'nochap.mkv'))
    writeConfig(workDir, checkCfg())

    def (code, out) = runInspect(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('Chapters: present in 1 file, absent in 1'), 'reports the chapter split')
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

    def (code, out) = runInspect(workDir, ['--check'])
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

    def (code, out) = runInspect(workDir, ['--check'])
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

    def (code, out) = runInspect(workDir, ['--check'])
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

    def (code, out) = runInspect(workDir, ['--check'])
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

    def (code, out) = runInspect(workDir, ['--check'])
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

    def (code, out) = runInspect(workDir, ['--identify', '--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('Consistency check'), 'cross-file report present')
    check(out.contains('*** S01E01.mkv'), 'per-file identify table present')
    check(!new File(workDir, 'mkv').exists(), 'combined mode muxes nothing')
}

// ─── 75. an unidentifiable file is excluded, not crashed on ──────────────────
runTest('75_check_survives_unidentifiable_file') { workDir ->
    fullCopy(new File(workDir, 'good.mkv'))
    new File(workDir, 'broken.mkv').bytes = new byte[2048]   // zero bytes: not a media file
    writeConfig(workDir, checkCfg())

    def (code, out) = runInspect(workDir, ['--check'])
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

    def (code, out) = runInspect(workDir, ['--check'])
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

    def (code, out) = runInspect(workDir, ['--check'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('different track layout'), 'shifted files reported as a layout group')

    // Every group names its files now, the largest included: each group is one
    // muxing pass, so each is an answer. These names carry no episode number, so
    // membership falls back to the file list.
    def named = (out =~ /[\w.]+\.mkv/).collect { it } as Set
    check(['x.mkv', 'y.mkv'].every { it in named }, 'the shifted files are named')
    check(['a.mkv', 'b.mkv', 'c.mkv'].every { it in named }, 'and so are the common-layout files')
    check(out.contains('Layout 1 (3 files)'), 'the three common-layout files are the largest group')
    check(out.contains('Layout 2 (2 files)'), 'the two shifted files are their own layout group')
    // The shift lands audio/subs on IDs the config selects, so it must block
    check(out.contains('a different track layout, at selected track'), 'shift classified as blocking')
}

// ─── 78. --check-verbose is a report modifier and writes nothing ─────────────
// Inspection has no muxing path to fall through into, which is half the reason
// it lives in its own script: a bare --check-verbose once printed the report and
// then muxed the whole batch.
runTest('78_check_verbose_does_not_mux') { workDir ->
    fullCopy(new File(workDir, 'a.mkv'))
    fullCopy(new File(workDir, 'b.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, checkCfg())

    def (code, out) = runInspect(workDir, ['--check-verbose'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('Consistency check'), 'the report is printed')
    check(!out.contains('Processing'), 'nothing is muxed')
    check(!new File(workDir, 'mkv').exists(), 'no output directory created')
}

// ─── 79. a muxing run with no config.yaml is a clean error, not a stack trace ─
// The script no longer falls back to the example config next to itself, so a
// media directory with no config gets told what to do rather than silently
// muxing with unrelated selections. (inspect.groovy needs no config; see test 81.)
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

    def (code, out) = runInspect(workDir, ['--check', '--config', 'configs/show.yaml'])
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains('Consistency check: 1 file'), 'ran the check using the pointed-at config')
}

// ─── 81. the check runs without a config, reporting structure but not classifying ─
// Useful before a config exists: check a season is consistent, then write the
// config against it. Only a muxing run needs the config.
runTest('81_check_without_config') { workDir ->
    fullCopy(new File(workDir, 'a.mkv'))
    fullCopy(new File(workDir, 'b.mkv'), [names: [2: 'Other Studio']])
    // deliberately no config.yaml

    def (code, out) = runInspect(workDir, ['--check'])
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains('Consistency check'), 'the structural report still runs')
    check(out.contains('difference') && out.contains('across the batch'), 'counts the differences it found')
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

    def (code, out) = runInspect(workDir, ['--check', '--color', 'always'])
    checkEquals(code, 0, 'exit code')
    check(out.contains("${esc}[33m"), 'differing cells and warnings are yellow')
    check(out.contains("${esc}[36m"), 'section and table headers are cyan')
    check(out.contains("${esc}[32m"), 'the final Done is green')
    check(out.contains("${esc}[90m"), 'file-evidence lists are gray')
    check(out.contains("${esc}[0m"), 'escapes are reset')
    // The whole-line/whole-cell invariant: colour must not break the pinned
    // phrases the other check tests assert on.
    check(out.contains('*** Consistency check'), 'header text survives colouring intact')
}

// ─── 83. --color never suppresses ANSI; explicit always beats NO_COLOR ───────
runTest('83_color_never_and_no_color_precedence') { workDir ->
    fullCopy(new File(workDir, 'a.mkv'))
    fullCopy(new File(workDir, 'odd.mkv'), [names: [2: 'Other Studio']])
    writeConfig(workDir, checkCfg())

    def (c1, out1) = runInspect(workDir, ['--check', '--color', 'never'])
    checkEquals(c1, 0, 'exit code (never)')
    check(!out1.contains("${esc}["), 'no escapes under --color never')

    // NO_COLOR (no-color.org) disables auto-detection, but an explicit
    // --color always is a direct request and wins. NO_COLOR-under-auto cannot
    // be exercised through a subprocess at all: the pipe already turns auto
    // off, so that path is covered by every other (escape-free) test instead.
    def (c2, out2) = runScript('inspect.groovy', workDir, ['--check', '--color', 'always'], [NO_COLOR: '1'])
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
    def (c1, out1) = runInspect(workDir, ['--check'])
    checkEquals(c1, 0, 'exit code (empty dir)')
    check(out1.contains('No media files'), 'says the directory has nothing to work on')
    check(out1.contains('mkv'), 'names the extensions it looked for')

    // A mask that matches a file, just not a media file
    new File(workDir, 'notes.txt').text = 'not media'
    def (c2, out2) = runInspect(workDir, ['--identify', 'notes.txt'])
    checkEquals(c2, 0, 'exit code (non-media match)')
    check(out2.contains('No media files match: notes.txt'), 'names the fruitless pattern')
    check(!new File(workDir, 'mkv').exists(), 'no output directory created')
}

// ═══════════════════════════════════════════════════════════════════════════
// v1.4.0 — substitution variables
// ═══════════════════════════════════════════════════════════════════════════

// ─── 91. --show-id accepts a TheMovieDB URL ─────────────────────────────────
runTest('91_show_id_from_url') { workDir ->
    def routes = [
        '/3/tv/1920'         : JsonOutput.toJson([name: 'Twin Peaks', first_air_date: '1990-04-08']),
        '/3/tv/1920/season/1': JsonOutput.toJson([episodes: [[episode_number: 1, name: 'Pilot']]])
    ]

    withStubServer(routes) { String baseUrl ->
        ['https://www.themoviedb.org/tv/1920-twin-peaks',
         'https://www.themoviedb.org/tv/1920',
         'https://www.themoviedb.org/tv/1920-twin-peaks?language=ru'].each { url ->
            def (code, out) = runScript('fetch_episodes.groovy', workDir,
                                        ['--api-key', 'stub', '--show-id', url,
                                         '--season', '1', '--base-url', baseUrl])
            checkEquals(code, 0, "exit code for ${url} (output was:\n$out\n)")
            check(out.contains('Twin Peaks'), "resolved the id from ${url}")
        }

        def (badCode, badOut) = runScript('fetch_episodes.groovy', workDir,
                                          ['--api-key', 'stub', '--show-id', 'not-a-url',
                                           '--season', '1', '--base-url', baseUrl])
        checkEquals(badCode, 2, 'junk --show-id exits 2')
        check(badOut.contains('neither a number nor'), 'says why the value was rejected')
    }
}

// ─── 92. season comes from the URL, and conflicts are refused ───────────────
runTest('92_season_from_url') { workDir ->
    def routes = [
        '/3/tv/1920'         : JsonOutput.toJson([name: 'Twin Peaks', first_air_date: '1990-04-08']),
        '/3/tv/1920/season/2': JsonOutput.toJson([episodes: [[episode_number: 1, name: 'May the Giant']]])
    ]

    withStubServer(routes) { String baseUrl ->
        // No --season at all: the URL supplies it
        def (code, out) = runScript('fetch_episodes.groovy', workDir,
                                    ['--api-key', 'stub', '--base-url', baseUrl,
                                     '--show-id', 'https://www.themoviedb.org/tv/1920-twin-peaks/season/2'])
        checkEquals(code, 0, "exit code (output was:\n$out\n)")
        checkEquals(new File(workDir, 'episodes.txt').readLines('UTF-8'), ['May the Giant'], 'fetched season 2')

        // Explicit --season that disagrees is a mistake worth stopping for
        def (conflictCode, conflictOut) = runScript('fetch_episodes.groovy', workDir,
                                                    ['--api-key', 'stub', '--base-url', baseUrl, '--season', '1',
                                                     '--show-id', 'https://www.themoviedb.org/tv/1920/season/2'])
        checkEquals(conflictCode, 2, 'conflicting season exits 2')
        check(conflictOut.contains('conflicts with'), 'names the conflict')

        // Neither source supplies one
        def (noneCode, noneOut) = runScript('fetch_episodes.groovy', workDir,
                                            ['--api-key', 'stub', '--show-id', '1920', '--base-url', baseUrl])
        checkEquals(noneCode, 2, 'missing season exits 2')
        check(noneOut.contains('No season'), 'says a season is needed')
    }
}

// ─── 93. --language, with an en-US fallback for untranslated names ──────────
runTest('93_fetch_language_fallback') { workDir ->
    // The second episode is untranslated, which TheMovieDB answers with an
    // empty string rather than by falling back on its own.
    def routes = [
        '/3/tv/1920'         : JsonOutput.toJson([name: 'Твин Пикс', first_air_date: '1990-04-08']),
        '/3/tv/1920/season/1': JsonOutput.toJson([
            name: 'Сезон 1',
            episodes: [[episode_number: 1, name: 'Пилот'], [episode_number: 2, name: '']]
        ])
    ]

    withStubServer(routes) { String baseUrl ->
        def (code, out) = runScript('fetch_episodes.groovy', workDir,
                                    ['--api-key', 'stub', '--show-id', '1920', '--season', '1',
                                     '--language', 'ru-RU', '--base-url', baseUrl])
        checkEquals(code, 0, "exit code (output was:\n$out\n)")

        def yaml = new Yaml().load(new File(workDir, 'episodes.yaml').getText('UTF-8'))
        checkEquals(yaml.language, 'ru-RU', 'records the locale it fetched in')
        checkEquals(yaml.show, 'Твин Пикс', 'localized show name')
        checkEquals(yaml.episodes[0].name, 'Пилот', 'localized episode name')
        // The stub serves the same route regardless of query string, so the
        // fallback fetch returns the same empty name; what matters here is that
        // the script noticed and said so rather than writing a blank silently.
        check(out.contains('untranslated'), 'reports that it filled gaps from en-US')
    }
}

// ─── 94. rename prefers episodes.yaml and sanitizes on the way to a file name ─
runTest('94_rename_prefers_yaml') { workDir ->
    stageInput(workDir, 'Show.s01e01.mkv')
    stageInput(workDir, 'Show.s01e02.mkv')
    // The two sources disagree on purpose: yaml must win
    writeEpisodes(workDir, ['From Text One', 'From Text Two'])
    writeEpisodesYaml(workDir, [show: 'My Show', episodes: [1: 'Slash/Colon: Question?', 2: 'Plain Two']])

    def (code, out) = runScript('rename.groovy', workDir, [])
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains('Show name from episodes.yaml'), 'says where the show name came from')

    def names = workDir.listFiles().collect { it.name } as Set
    check('My Show - S01E01 - SlashColon Question.mkv' in names,
          "raw name sanitized into the file name; got ${names}")
    check('My Show - S01E02 - Plain Two.mkv' in names, "second file renamed; got ${names}")
}

// ─── 94b. episodeOffset shifts episodes.txt only, never episodes.yaml ────────
// The trap this pins: a season downloaded in halves is exactly what the offset
// was invented for, and with episodes.txt it still works that way. With a yaml
// fetched for the whole season the numbers are already real, so applying the
// offset out of habit would map episode 1's title onto E11 — plausible-looking
// and wrong. The yaml join ignores the offset entirely.
runTest('94b_offset_applies_to_txt_only') { workDir ->
    stageInput(workDir, 'Show.s01e11.mkv')

    // episodes.txt trimmed to the second half of the season: offset 11 means
    // "the first line is episode 11"
    writeEpisodes(workDir, ['Eleventh', 'Twelfth'])
    def (txtCode, txtOut) = runScript('rename.groovy', workDir, ['My Show', '11'])
    checkEquals(txtCode, 0, "txt exit code (output was:\n$txtOut\n)")
    check(new File(workDir, 'My Show - S01E11 - Eleventh.mkv').exists(),
          "offset applied to episodes.txt; got ${workDir.listFiles().collect { it.name }}")

    // Same offset, but now a full-season yaml is present and wins
    def yamlDir = new File(workDir, 'yaml')
    yamlDir.mkdirs()
    stageInput(yamlDir, 'Show.s01e11.mkv')
    writeEpisodesYaml(yamlDir, [show: 'My Show',
                                episodes: (1..12).collectEntries { [it, "Episode ${it}".toString()] }])
    def (yamlCode, yamlOut) = runScript('rename.groovy', yamlDir, ['My Show', '11'])
    checkEquals(yamlCode, 0, "yaml exit code (output was:\n$yamlOut\n)")
    check(new File(yamlDir, 'My Show - S01E11 - Episode 11.mkv').exists(),
          "offset ignored for yaml; got ${yamlDir.listFiles().collect { it.name }}")
    check(!new File(yamlDir, 'My Show - S01E11 - Episode 1.mkv').exists(),
          'the offset did not shift the yaml numbering')
}

// ─── 95. rename falls back to episodes.txt; missing sources are clean errors ──
runTest('95_rename_fallbacks_and_errors') { workDir ->
    // No episode data at all
    def (noneCode, noneOut) = runScript('rename.groovy', workDir, ['My Show'])
    checkEquals(noneCode, 2, 'no episode data exits 2')
    check(noneOut.contains('episodes.yaml or episodes.txt'), 'names both accepted files')
    check(!noneOut.contains('FileNotFoundException'), 'no stack trace')

    // episodes.txt only: still works, and a legacy already-sanitized line is
    // unchanged by being sanitized again
    stageInput(workDir, 'Show.s01e01.mkv')
    writeEpisodes(workDir, ['Already Sanitized'])
    def (code, out) = runScript('rename.groovy', workDir, ['My Show'])
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(new File(workDir, 'My Show - S01E01 - Already Sanitized.mkv').exists(), 'renamed from episodes.txt')

    // yaml present but no show name anywhere
    def bare = new File(workDir, 'bare')
    bare.mkdirs()
    stageInput(bare, 'Show.s01e01.mkv')
    writeEpisodesYaml(bare, [show: '', episodes: [1: 'One']])
    def (bareCode, bareOut) = runScript('rename.groovy', bare, [])
    checkEquals(bareCode, 2, 'no show name exits 2')
    check(bareOut.contains('No show name'), 'says a show name is needed')
}

// ─── 96. segment title is templated, independently of the video track name ───
runTest('96_general_title_substitution') { workDir ->
    stageInput(workDir, 'My Show - S01E03 - Ignored.mkv')
    writeEpisodesYaml(workDir, [show: 'My Show', season: 1, seasonName: 'Season One',
                                episodes: [3: 'Real: Title?']])
    writeConfig(workDir, cfg(
        generalTitle: '${showName} - S${seasonNum}E${episodeNum} - ${episodeName}',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)

    def info = identify(findOutput(workDir))
    checkEquals(info.container.get('properties').title, 'My Show - S01E03 - Real: Title?',
                'segment title uses the raw episode name')
    // The video track name is a separate field and keeps its own default
    def video = info.tracks.find { it.type == 'video' }
    checkEquals(video.get('properties').track_name, 'My Show - S01E03 - Ignored',
                'video track name still defaults to the file name')
}

// ─── 97. video track title is templated, and the segment title is not ────────
runTest('97_video_title_substitution') { workDir ->
    stageInput(workDir, 'My Show - S01E04 - From Name.mkv')
    writeConfig(workDir, cfg(
        videoTitle: '${episodeName} [${languageName}]',
        videoLang: 'ja',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)

    def info = identify(findOutput(workDir))
    def video = info.tracks.find { it.type == 'video' }
    // No episodes.yaml here: episodeName falls back to the canonical file name
    checkEquals(video.get('properties').track_name, 'From Name [Japanese]',
                'video track name substituted from the file name and language')
    checkEquals(info.container.get('properties').title, 'My Show - S01E04 - From Name',
                'segment title still defaults to the file name')
}

// ─── 98. language name variables, in English and natively ────────────────────
runTest('98_language_name_variables') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [
            [id:2, language:'en',  title:'${languageName}',   default:true],
            [id:3, language:'ru',  title:'${languageNative}', default:false]
        ],
        subtitleTracks: [[id:6, language:'jpn', title:'${languageName}', default:false]],
        trackOrder: '0:0,0:2,0:3,0:6'
    ))
    runMkvGroovy(workDir)

    def tracks = identify(findOutput(workDir)).tracks
    def audio = tracks.findAll { it.type == 'audio' }
    checkEquals(audio[0].get('properties').track_name, 'English', 'English display name')
    // Capitalized in Russian's own rules: the JDK returns "русский"
    checkEquals(audio[1].get('properties').track_name, 'Русский', 'native name, capitalized')
    // A three-letter code resolves the same as a two-letter one
    checkEquals(tracks.find { it.type == 'subtitles' }.get('properties').track_name, 'Japanese',
                'ISO 639-2 code resolves')
}

// ─── 99. ${codec} comes from the probe, and works without the check ──────────
runTest('99_codec_variable') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'${languageName} ${codec}', default:true]],
        subtitleTracks: [[id:4, language:'en', title:'${languageName} ${codec}', default:true]],
        trackOrder: '0:0,0:2,0:4'
    ))
    // --no-check so nothing has pre-populated the probe cache: this exercises
    // the probe-on-demand path rather than the pre-flight's leftovers.
    runMkvGroovy(workDir, ['--no-check'])

    def tracks = identify(findOutput(workDir)).tracks
    checkEquals(tracks.find { it.type == 'audio' }.get('properties').track_name, 'English AAC',
                'audio codec name')
    checkEquals(tracks.find { it.type == 'subtitles' }.get('properties').track_name, 'English SRT',
                'subtitle codec name')
}

// ─── 100. an unknown variable is fatal before anything is muxed ──────────────
// The whole point of failing fast: a typo would otherwise be stamped into the
// track names of an entire season, one file at a time.
runTest('100_unknown_variable_fails_fast') { workDir ->
    stageInput(workDir, 'Show.s01e01.mkv')
    stageInput(workDir, 'Show.s01e02.mkv')
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'${languagName}', default:true]],
        trackOrder: '0:0,0:2'
    ))
    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 2, 'exits 2')
    check(out.contains('${languagName}'), 'quotes the offending token')
    check(out.contains('mainSource.audioTracks[0].title'), 'names the config path')
    check(out.contains('languageName'), 'lists what would have been valid')
    check(!new File(workDir, 'mkv').exists(), 'no output directory created')
}

// ─── 101. a track variable in a file-scope field is equally fatal ────────────
runTest('101_scope_violation_fails_fast') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        generalTitle: '${languageName}',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 2, 'exits 2')
    check(out.contains('general.title'), 'names the field')
    check(!new File(workDir, 'mkv').exists(), 'nothing was muxed')
}

// ─── 102. an unresolvable language code is caught up front too ───────────────
runTest('102_bad_language_fails_fast') { workDir ->
    stageInput(workDir)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'xx', title:'${languageName}', default:true]],
        trackOrder: '0:0,0:2'
    ))
    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 2, 'exits 2')
    check(out.contains('no language name'), 'says the code has no display name')
    check(out.contains('xx'), 'quotes the code')
}

// ─── 103. missing episode data drops just that episode ──────────────────────
// Data-shaped and per-file, unlike a typo: the rest of the batch still muxes.
runTest('103_missing_episode_data_drops_file') { workDir ->
    stageInput(workDir, 'My Show - S01E01 - One.mkv')
    stageInput(workDir, 'NoEpisodeNumberHere.mkv')
    writeEpisodesYaml(workDir, [show: 'My Show', episodes: [1: 'One']])
    writeConfig(workDir, cfg(
        generalTitle: '${showName} - S${seasonNum}E${episodeNum} - ${episodeName}',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    def (code, out) = runMkvGroovy(workDir)
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains('NoEpisodeNumberHere.mkv'), 'names the dropped file')
    check(out.contains('${episodeNum}'), 'names the variable that had no value')

    def outputs = new File(workDir, 'mkv').listFiles().collect { it.name }
    checkEquals(outputs, ['My Show - S01E01 - One.mkv'], 'only the resolvable episode was muxed')

    // --strict turns the same situation into an abort
    FileUtils.deleteDirectory(new File(workDir, 'mkv'))
    def (strictCode, strictOut) = runMkvGroovy(workDir, ['--strict'])
    checkEquals(strictCode, 2, 'strict exits 2')
    check(!new File(workDir, 'mkv').exists(), 'strict muxed nothing')
}

// ─── 104. episodes.txt is still a usable source for ${episodeName} ──────────
runTest('104_episodes_txt_fallback_in_mux') { workDir ->
    stageInput(workDir, 'Show.s01e02.mkv')
    // Hand-written, raw: the colon is exactly what a file name cannot hold and
    // a title can
    writeEpisodes(workDir, ['First One', 'Second: With Colon?'])
    writeConfig(workDir, cfg(
        generalTitle: '${episodeName}',
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        trackOrder: '0:0,0:2'
    ))
    runMkvGroovy(workDir)
    checkEquals(identify(findOutput(workDir)).container.get('properties').title, 'Second: With Colon?',
                'episode name taken from the right line of episodes.txt')
}

// ─── 105. companion paths and titles are templated ──────────────────────────
runTest('105_companion_substitution') { workDir ->
    def input = stageInput(workDir, 'My Show - S01E01 - Pilot.mkv')
    extractTrack(input, new File(workDir, 'My Show - S01E01 - Pilot.rus.mka'), 'audio', 3)
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        additionalSources: [[file: '${fileName}.rus.mka',
                             tracks: [[language:'ru', title:'${languageNative} ${codec}', default:false]]]],
        trackOrder: '0:0,0:2,1:0'
    ))
    runMkvGroovy(workDir)

    def audio = identify(findOutput(workDir)).tracks.findAll { it.type == 'audio' }
    checkEquals(audio.size(), 2, 'companion track was muxed in')
    checkEquals(audio[1].get('properties').track_name, 'Русский AAC',
                'companion title substituted from its own language and codec')
}

// ─── 106. --identify shows configured companions, missing ones included ─────
runTest('106_identify_shows_companions') { workDir ->
    def input = stageInput(workDir, 'My Show - S01E01 - Pilot.mkv')
    extractTrack(input, new File(workDir, 'My Show - S01E01 - Pilot.rus.mka'), 'audio', 3)
    stageInput(workDir, 'My Show - S01E02 - Second.mkv')     // no companion for this one
    writeConfig(workDir, cfg(
        audioTracks: [[id:2, language:'en', title:'English', default:true]],
        additionalSources: [[file: '${fileName}.rus.mka',
                             tracks: [[language:'ru', title:'Russian', default:false]]]],
        trackOrder: '0:0,0:2,1:0'
    ))

    def (code, out) = runInspect(workDir, ['--identify'])
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains('+ My Show - S01E01 - Pilot.rus.mka'), 'shows the resolved companion path')
    check(out.contains('(not found)'), 'reports the episode whose companion is absent')
    check(!new File(workDir, 'mkv').exists(), '--identify muxed nothing')
}

// ─── 107. --dry-run resolves; --check keeps the raw template ────────────────
runTest('107_dry_run_and_check_rendering') { workDir ->
    stageInput(workDir, 'My Show - S01E01 - Pilot.mkv')
    writeConfig(workDir, cfg(
        generalTitle: '${showName}: ${episodeName}',
        audioTracks: [[id:2, language:'en', title:'${languageName}', default:true]],
        trackOrder: '0:0,0:2'
    ))

    def (dryCode, dryOut) = runMkvGroovy(workDir, ['--dry-run'])
    checkEquals(dryCode, 0, "dry-run exit code (output was:\n$dryOut\n)")
    check(dryOut.contains('My Show: Pilot'), 'dry run shows the resolved title')
    check(!dryOut.contains('${showName}'), 'dry run leaves no unresolved token')
    check(!new File(workDir, 'mkv').exists(), 'dry run muxed nothing')

    // The check report identifies which config entry a finding refers to, and
    // the literal template is what does that — the check runs over the whole
    // batch and so cannot resolve a per-file variable at all.
    //
    // Keeping every track preserves the source ids, so the name override below
    // lands on id 2 — the audio track the config selects, which is what makes
    // the difference blocking and gets the config title printed.
    buildVariant(new File(workDir, 'My Show - S01E02 - Second.mkv'),
                 [audio: [1, 2, 3], subs: [4, 5, 6], names: [2: 'Different']])
    def (checkCode, checkOut) = runInspect(workDir, ['--check'])
    checkEquals(checkCode, 0, 'check exit code')
    check(checkOut.contains('${languageName}'), "check prints the raw template; got:\n$checkOut")
}

// ─── 108. mkv-inspect: bare run checks, --identify identifies ────────────────
// The default mode is the batch report, since that is the question one usually
// arrives with; --identify is the per-file drill-down and suppresses the report
// unless --check names it too. Neither writes anything, with or without a config.
runTest('108_inspect_default_mode') { workDir ->
    fullCopy(new File(workDir, 'a.mkv'))
    fullCopy(new File(workDir, 'b.mkv'))

    def (bareCode, bareOut) = runInspect(workDir)
    checkEquals(bareCode, 0, 'bare run exits 0')
    check(bareOut.contains('Consistency check'), 'bare run prints the check report')
    check(!bareOut.contains('*** a.mkv'), 'bare run prints no per-file table')

    def (idCode, idOut) = runInspect(workDir, ['--identify'])
    checkEquals(idCode, 0, '--identify exits 0')
    check(idOut.contains('*** a.mkv'), '--identify prints the per-file table')
    check(!idOut.contains('Consistency check'), '--identify alone skips the report')

    def (bothCode, bothOut) = runInspect(workDir, ['--identify', '--check'])
    checkEquals(bothCode, 0, 'combined exits 0')
    check(bothOut.contains('Consistency check') && bothOut.contains('*** a.mkv'),
          'naming both runs both')

    check(!new File(workDir, 'mkv').exists(), 'inspection creates no output directory')
    check(!new File(workDir, 'config.yaml').exists(), 'no config was needed at all')
}

// ─── 109. mux.groovy rejects the inspection flags it no longer owns ──────────
// They moved to mkv-inspect outright rather than living on as aliases: two entry
// points for one report is surface to keep in sync, and picocli already rejects
// an unknown option clearly.
runTest('109_mux_rejects_inspection_flags') { workDir ->
    stageInput(workDir, 'a.mkv')
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'jpn', title: 'JP', default: true]]))

    ['--identify', '--check', '--check-verbose'].each { flag ->
        def (code, out) = runMkvGroovy(workDir, [flag])
        check(out.contains("Unknown option: '${flag}'"), "mux names ${flag} as not its own; got:\n${out}")
        check(!out.contains('Processing'), "mux muxed nothing for ${flag}")
        check(!new File(workDir, 'mkv').exists(), "mux created no output directory for ${flag}")
    }

    // NOTE: picocli's script runner reports a usage error and returns without
    // setting an exit code, so this — like every unknown option in every picocli
    // script here — still exits 0. Asserted on the message and the absence of
    // output instead, rather than pinning behaviour worth changing separately.
}

// ─── 110. the discovery engine's matching rules ──────────────────────────────
// Loaded in-process rather than through a script: these are the rules both
// mkv-inspect and mkv-rename depend on, and asserting on the data structure is
// far more precise than reading them back out of a report.
runTest('110_discovery_engine_matching') { workDir ->
    def episodes = evaluate(new File(repoRoot, 'src/lib/episodes.groovy'))
    def discovery = evaluate(new File(repoRoot, 'src/lib/discovery.groovy'))(episodes)

    // Whole-word language tokens only, two- and three-letter alike.
    checkEquals(discovery.guessLanguage(['Rus sound']), 'rus', 'three-letter token in a directory')
    checkEquals(discovery.guessLanguage(['Ru subs']), 'rus', 'two-letter token as a word')
    checkEquals(discovery.guessLanguage(['Ru.subs']), 'rus', 'punctuation separates words')
    checkEquals(discovery.guessLanguage(['Rusubs']), null, 'no match inside a longer word')
    // Spellings come from CLDR, so a language answers to its own name too.
    checkEquals(discovery.guessLanguage(['Русский']), 'rus', 'native name in its own script')
    checkEquals(discovery.guessLanguage(['Espanol subs', 'Español']), 'spa', 'native name with diacritics')
    checkEquals(discovery.guessLanguage(['English subs']), 'eng', 'English name')
    // Two-letter codes that are ordinary words are excluded on purpose.
    checkEquals(discovery.guessLanguage(['No subs']), null, "'No' is not Norwegian")
    checkEquals(discovery.guessLanguage(['Extras (to be done)']), null, "'to' is not a language here")
    // Only the bare two-letter form is withheld, which is what makes the entry
    // cheap: the language still answers to its three-letter code and both names.
    checkEquals(discovery.guessLanguage(['UK BluRay']), null, "'UK' is a region, not Ukrainian")
    checkEquals(discovery.guessLanguage(['Ukr sound']), 'ukr', 'but the three-letter code still matches')
    checkEquals(discovery.guessLanguage(['Українська']), 'ukr', 'and so does the native name')
    checkEquals(discovery.guessLanguage(['El Bosque']), null, "'El' is a Spanish article, not Greek")
    checkEquals(discovery.guessLanguage(['Greek subs']), 'gre', 'Greek reports its bibliographic code')
    // Dropped from BIBLIOGRAPHIC because /B and /T agree on it — the fallback to
    // getISO3Language has to produce the same answer the table used to.
    checkEquals(discovery.guessLanguage(['Serbian']), 'srp', 'Serbian has no separate /B code')
    checkEquals(discovery.trimForDisplay('.rus'), 'rus', 'display trims leading punctuation')
    checkEquals(discovery.trimForDisplay('[Studio]'), 'Studio', 'display trims brackets')
    // Range collapsing lives in episodes.groovy, where the episode semantics are
    // — see test 122; discovery has no business knowing about episode numbers.

    // A file name that is a prefix of another main file's must attach to the
    // longest match, not be read as the shorter one plus a suffix.
    ['Title', 'Title 2'].each { stageInput(workDir, "Show - S01E01 - ${it}.mkv".toString()) }
    stageInput(workDir, 'Show - S01E02 - Second.mkv')
    stageExternalText(workDir, 'Show - S01E01 - Title 2.srt')
    stageExternalText(workDir, 'Show - S01E01 - Title!odd{sep}.srt')
    // Its own name relates to no main file, so only the episode number can place
    // it — and it has to be an episode only one main file claims, since two files
    // for E01 make that episode ambiguous by design.
    stageExternalText(workDir, 'Rus sound/[X]/Other Release S01E02.mka')

    def mains = workDir.listFiles({ it.name.endsWith('.mkv') } as FileFilter).toList().sort { it.name }
    def result = discovery.discoverCompanions(
        mains, discovery.walkTree(workDir, [] as Set), [mainExtensions: ['mkv'] as Set])

    def byFile = [:]
    result.variants.each { v -> v.entries.each { byFile[it.file.name] = [variant: v, entry: it] } }

    checkEquals(byFile['Show - S01E01 - Title 2.srt'].entry.main.name, 'Show - S01E01 - Title 2.mkv',
                'longest main name wins over a shorter one plus a suffix')
    checkEquals(byFile['Show - S01E01 - Title 2.srt'].entry.suffix, '', 'and so has no suffix at all')
    checkEquals(byFile['Show - S01E01 - Title!odd{sep}.srt'].entry.suffix, '!odd{sep}',
                'any separator starts a suffix')
    checkEquals(byFile['Other Release S01E02.mka'].entry.tier, 2, 'matched by episode number only')
    checkEquals(result.unmatched.size(), 0, 'nothing else is left over')
}

// ─── 111. --identify bundles discovered external files under each episode ────
runTest('111_identify_external_files') { workDir ->
    stageTree(workDir)

    def (code, out) = runInspect(workDir, ['--identify'])
    checkEquals(code, 0, 'exit code')

    check(out.contains('External files:'), 'the legend is printed')
    check(out.contains('Rus sound/[GroupA]/<name>.mka'), 'legend shows the path pattern')
    check(out.contains('Rus subs/[GroupA]/<name>.ass'),
          'a merged variant lists both kinds of file it holds')
    check(out.contains('<name>.rus.srt'), 'the suffix layout is a variant too')

    // A probed .mka reports the language it actually carries; an unprobed .ass
    // falls back to the directory guess, marked as a guess.
    check(out.contains('[GroupA]'), 'variants are labelled by their directory')
    check(out.contains('rus?'), 'a guessed language is marked with a question mark')
    check(out.contains('ASS'), 'codec of an unprobed file comes from its extension')

    // A merged variant is one block with both its files under it, not two
    // blocks that happen to share a label.
    check(out.contains('[GroupA] (.mka, .ass)'), "the merged variant is one block; got:\n${out}")

    // The [GroupB] fixture is staged with no language, so mkvmerge reports 'und'
    // — Matroska's only way of saying "untagged" — which has to fall back to the
    // folder's guess. Matched as a whole track row: the fixture's own video track
    // is genuinely undefined, and "Rus sound" contains the letters too.
    def untagged = out.readLines().findAll { it ==~ /^\s+\d+\s+audio\s+\S+\s+und\s.*/ }
    check(untagged.isEmpty(), "an untagged external file falls back to the guess; got: ${untagged}")

    check(out.contains('Unmatched external files'), 'the leftovers are reported')
    check(out.contains('Bonus.ass'), 'and named')
    check(out.contains('Extras:'), 'main-type files in subdirectories are reported')
    check(out.contains('Sample.mkv'), 'and named')
    check(!out.contains('*** extras/Sample.mkv'), 'but never inspected as a source')
}

// ─── 112. external files are part of the layout, so they split the groups ────
// The question the check is really asked is "how many muxing passes will this
// season need", and an episode's external files are part of its pass. Files with
// identical tracks but different dubs available are two jobs, and a report that
// grouped them together would say one config where two are needed.
runTest('112_externals_split_layout_groups') { workDir ->
    stageTree(workDir)

    def (code, out) = runInspect(workDir)
    checkEquals(code, 0, 'exit code')

    // The fixture's three episodes carry three different sets: E01 has both dub
    // groups plus the suffixed sibling, E02 has [GroupA] audio + subs, E03 has
    // [GroupA] audio only.
    check(out.contains('Layout 1'), 'the batch is split into layout groups')
    check(out.contains('Layout 3'), 'one group per distinct set of external files')
    // The header carries the external files as labels only — the table below
    // lists them in full, so spelling them out twice just wraps the line.
    check((out =~ /Layout 1 [^\n]*\+ [A-Z]/).find(),
          "the group header names its external files by label; got:\n${out}")

    // Every group's table lists its external files as rows alongside the tracks,
    // labelled by variant rather than by an ID they do not have.
    check((out =~ /\n\s+[A-Z]\s+(audio|subs)\s+\S+/).find(),
          "external rows sit in the table under their label; got:\n${out}")

    // The legend is what decodes a label into the variant it stands for.
    check(out.contains('[GroupA]'), 'the legend names the variant')

    check(!out.contains('External file coverage'), 'the separate coverage section is gone')
    check(!new File(workDir, 'mkv').exists(), 'still writes nothing')
}

// ─── 112b. a uniform batch stays one group, and says so about both halves ────
runTest('112b_uniform_externals_stay_one_group') { workDir ->
    ['01', '02'].each { ep ->
        stageInput(workDir, "Show - S01E${ep} - Title.mkv".toString())
        stageExternalTrack(workDir, "Rus sound/[Group]/Show - S01E${ep} - Title.mka".toString(), 'audio', 3)
    }

    def (code, out) = runInspect(workDir)
    checkEquals(code, 0, 'exit code')
    check(!out.contains('Layout 2'), 'one set of external files is one group')
    check(out.contains('Track structure and external files are consistent'),
          "the green line now asserts both halves; got:\n${out}")
}

// ─── 112c. an external value that changes mid-season is reported ─────────────
// The external analogue of a flag flipping halfway through a season: the same
// dub, tagged Russian for some episodes and untagged for others.
runTest('112c_external_value_split_reported') { workDir ->
    ['01', '02'].each { ep -> stageInput(workDir, "Show - S01E${ep} - Title.mkv".toString()) }
    stageExternalTrack(workDir, 'Rus sound/[Group]/Show - S01E01 - Title.mka', 'audio', 3)
    stageExternalTrack(workDir, 'Rus sound/[Group]/Show - S01E02 - Title.mka', 'audio', 3, 'jpn')
    // A config is what turns findings into labelled items; without one the report
    // prints the count alone (test 81).
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'jpn', title: 'JP', default: true]]))

    def (code, out) = runInspect(workDir)
    checkEquals(code, 0, 'exit code')
    check(!out.contains('Layout 2'), 'both episodes carry the same external file, so one group')
    check(out.contains('external A [Group] (audio) - language differs'),
          "the differing value is named and attributed to its variant; got:\n${out}")
    check(out.contains('informational'), 'an external difference never blocks')
}

// ─── 113. discovery skips dot-directories and the output directory ───────────
// Muxed output carries the same base names as its sources, so an output folder
// left in place would come back as an external file of the episode it was made
// from.
runTest('113_discovery_excludes_output_and_dot_dirs') { workDir ->
    stageInput(workDir, 'Show - S01E01 - Title.mkv')
    stageExternalTrack(workDir, 'mkv/Show - S01E01 - Title.mka', 'audio', 3)
    stageExternalText(workDir, '.stash/Show - S01E01 - Title.srt')
    stageExternalText(workDir, 'Rus subs/[Real]/Show - S01E01 - Title.ass')
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'jpn', title: 'JP', default: true]]))

    def (code, out) = runInspect(workDir, ['--identify'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('[Real]'), 'a real external file is still found')
    check(!out.contains('mkv/'), 'destinationDir is not scanned')
    check(!out.contains('.stash'), 'dot-directories are not scanned')
}

// ─── 114. a .sub is never probed, and an idx/sub pair covers its episode once ─
// On its own a .sub identifies as a zero-track MPEG stream, so probing it would
// report nothing useful and cost a subprocess to do it.
runTest('114_sub_never_probed') { workDir ->
    stageInput(workDir, 'Show - S01E01 - Title.mkv')
    // Deliberately not a valid VobSub pair: if anything probed these, it would
    // show in the output as an error rather than as a clean VobSub row.
    stageExternalText(workDir, 'Subs/[Group]/Show - S01E01 - Title.idx', 'not really an index\n')
    stageExternalText(workDir, 'Subs/[Group]/Show - S01E01 - Title.sub', 'not really a stream\n')

    def (code, out) = runInspect(workDir, ['--identify'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('VobSub'), 'both halves are described from their extension')
    check(!out.contains('could not identify'), 'neither half is reported as unreadable')
    check(!out.contains('Exception'), 'no stack trace')

    def (checkCode, checkOut) = runInspect(workDir)
    checkEquals(checkCode, 0, 'check exit code')
    check(!checkOut.contains('Layout 2'), 'the pair is one episode\'s worth of external files, not two groups')
}

// ─── 115. masks narrow what is reported, not what is matched ─────────────────
// Otherwise narrowing to one episode would dump the rest of the season's dubs
// into "unmatched", which is exactly the opposite of what the mask asked for.
runTest('115_masks_scope_external_display') { workDir ->
    stageTree(workDir)

    def (code, out) = runInspect(workDir, ['--identify', 'Show - S01E01 - Title.mkv'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('*** Show - S01E01 - Title.mkv'), 'the named episode is inspected')
    check(!out.contains('*** Show - S01E02 - Title.mkv'), 'the others are not')
    check(!out.contains('Show - S01E02 - Title.mka'), "another episode's dub is not listed")
    check(!out.contains('Show - S01E02 - Title.ass'), "nor mis-reported as unmatched")
}

// ─── 116. mkv-rename --external renames the whole tree, or nothing ───────────
runTest('116_rename_external_files') { workDir ->
    ['01', '02'].each { ep ->
        stageInput(workDir, "S01E${ep}.mkv")
        stageExternalTrack(workDir, "Rus sound/[GroupA]/S01E${ep}.mka", 'audio', 3)
    }
    stageExternalText(workDir, 'S01E01.rus.srt')
    writeEpisodes(workDir, ['First Episode', 'Second Episode'])

    // Without the flag nothing outside this directory is touched at all.
    def (plainCode, plainOut) = runScript('rename.groovy', workDir, ['My Show', '1', '--dry-run'])
    checkEquals(plainCode, 0, 'plain dry run exit code')
    check(!plainOut.contains('Rus sound'), 'the default run ignores subdirectories entirely')

    def (dryCode, dryOut) = runScript('rename.groovy', workDir, ['My Show', '1', '--external', '--dry-run'])
    checkEquals(dryCode, 0, 'dry run exit code')
    check(dryOut.contains('Rus sound/[GroupA]/S01E01.mka'), 'external files are previewed by path')
    check(dryOut.contains('My Show - S01E01 - First Episode.mka'), 'and take the new base name')
    check(new File(workDir, 'Rus sound/[GroupA]/S01E01.mka').exists(), 'dry run renamed nothing')

    def (code, out) = runScript('rename.groovy', workDir, ['My Show', '1', '--external'])
    checkEquals(code, 0, 'exit code')
    check(new File(workDir, 'Rus sound/[GroupA]/My Show - S01E01 - First Episode.mka').exists(),
          'the external file is renamed in place, in its own directory')
    check(new File(workDir, 'Rus sound/[GroupA]/My Show - S01E02 - Second Episode.mka').exists(),
          'every episode of it')
    check(new File(workDir, 'My Show - S01E01 - First Episode.rus.srt').exists(),
          'a suffixed sibling keeps its suffix verbatim')
    check(!new File(workDir, 'Rus sound/[GroupA]/S01E01.mka').exists(), 'the old name is gone')
    check(out.contains('external'), 'the summary says how many were external')
}

// ─── 117. --external refuses everything when any target is taken ─────────────
// The "check everything before touching anything" invariant has to hold across
// the whole tree, not just the current directory.
runTest('117_rename_external_collision_refuses_all') { workDir ->
    stageInput(workDir, 'S01E01.mkv')
    stageExternalTrack(workDir, 'Rus sound/[GroupA]/S01E01.mka', 'audio', 3)
    // The name the external file would be renamed to is already taken.
    stageExternalText(workDir, 'Rus sound/[GroupA]/My Show - S01E01 - First Episode.mka', 'occupied\n')
    writeEpisodes(workDir, ['First Episode'])

    def (code, out) = runScript('rename.groovy', workDir, ['My Show', '1', '--external'])
    checkEquals(code, 1, 'exits 1')
    check(out.contains('Refusing to rename anything'), 'refuses the whole batch')
    check(new File(workDir, 'S01E01.mkv').exists(), 'the main file is untouched')
    check(new File(workDir, 'Rus sound/[GroupA]/S01E01.mka').exists(), 'the external file is untouched')
}

// ─── 118. a broken config never stops mkv-inspect ────────────────────────────
// The script reports on files; a config is an optional extra that adds
// classification. A stale or half-written one in the directory must not stand
// between the user and the track table — least of all when reading that table is
// how they would fix the config.
runTest('118_inspect_survives_broken_config') { workDir ->
    stageInput(workDir, 'a.mkv')

    def cases = [
        'malformed' : "general:\n  destinationDir: mkv\n   bad indent: [\n",
        'empty'     : "",
        'not a map' : "- just\n- a list\n",
        'bad token' : "mainSource:\n  videoTrack:\n    language: \"jpn\"\n    title: \"\${epsiodeName}\"\n",
    ]

    cases.each { label, text ->
        new File(workDir, 'config.yaml').setText(text, 'UTF-8')

        def (code, out) = runInspect(workDir, ['--identify'])
        checkEquals(code, 0, "${label} config still exits 0")
        check(out.contains('*** a.mkv'), "${label} config still prints the track table; got:\n${out}")
        check(!out.contains('Exception'), "${label} config produces no stack trace")
        check(out.contains('Warning'), "${label} config is reported as a warning; got:\n${out}")

        // --strict is the one thing that turns any of it into a failure.
        def (strictCode, strictOut) = runInspect(workDir, ['--strict'])
        checkEquals(strictCode, 2, "${label} config exits 2 under --strict")
        check(strictOut.contains('config problem'), "${label} config is named as the reason under --strict")
    }

    check(!new File(workDir, 'mkv').exists(), 'nothing was written at any point')
}

// ─── 119. a config that cannot be parsed is a clean error in mux ─────────────
// mux cannot mux against a config it did not understand, so this stays fatal —
// but as the same clean exit 2 a missing config gets, never a stack trace.
runTest('119_mux_rejects_unusable_config') { workDir ->
    stageInput(workDir, 'a.mkv')

    ["general:\n  destinationDir: mkv\n   bad indent: [\n", "", "- just\n- a list\n"].each { text ->
        new File(workDir, 'config.yaml').setText(text, 'UTF-8')
        def (code, out) = runMkvGroovy(workDir, ['--dry-run'])
        checkEquals(code, 2, 'exits 2 on a config it cannot use')
        check(out.contains('*** Error:'), 'says what is wrong')
        check(!out.contains('Exception'), 'no stack trace')
        check(!new File(workDir, 'mkv').exists(), 'muxed nothing')
    }
}

// ─── 120. an external file mkvmerge cannot read is reported, not faked ───────
// The never-probed formats and a failed probe are different things: describing a
// truncated .mka from its extension would present the one file that cannot be
// muxed as the healthiest row in the table.
runTest('120_unreadable_external_reported') { workDir ->
    stageInput(workDir, 'Show - S01E01 - Title.mkv')
    stageExternalText(workDir, 'Rus sound/[Group]/Show - S01E01 - Title.mka', 'not a matroska file at all\n')

    def (code, out) = runInspect(workDir, ['--identify'])
    checkEquals(code, 0, 'exit code')
    check(out.contains('could not read this file'), "the failure is stated; got:\n${out}")
    check(!out.contains('0    audio      Matroska'), 'it is not dressed up as a healthy track')
    check(!out.contains('Exception'), 'no stack trace')
}

// ─── 121. the probing progress meter, in both of its renderings ──────────────
// Probing a season over a slow share is a long silence, so it gets a meter. The
// two renderings exist because '\r' erases nothing in a file or a pipe: every
// frame would be retained, smearing the log the tests themselves read.
runTest('121_progress_meter_renderings') { workDir ->
    // Named for the harness's own script-level `ui`, which this must not shadow.
    def meter = evaluate(new File(repoRoot, 'src/lib/output.groovy'))('never')

    def capture = { Closure body ->
        def buffer = new ByteArrayOutputStream()
        def previous = System.out
        System.setOut(new PrintStream(buffer, true))
        try { body() } finally { System.setOut(previous) }
        buffer.toString()
    }

    def bar = capture {
        def p = meter.progress('*** Reading 5 files', 5, [interactive: true])
        5.times { p.tick() }
        p.finish()
    }
    def frames = bar.split('\r').findAll { it.trim() }
    checkEquals(frames.size(), 5, 'one frame per percentage change')
    check(frames[-1].contains('100%'), "the meter reaches 100%; got:\n${bar}")
    check(frames[-1].contains('[########################]'), 'the bar fills')
    check(!bar.contains('█'), 'the bar is ASCII, for Windows consoles on legacy codepages')

    def dots = capture {
        def p = meter.progress('*** Reading 200 files', 200, [interactive: false])
        200.times { p.tick() }
        p.finish()
    }
    // A bare '\r' is the rewriting kind; the one in a Windows CRLF line ending is
    // not, which is why this looks for a carriage return with no newline after it.
    check(!(dots =~ /\r(?!\n)/).find(), "nothing is rewritten in place when redirected; got:\n${dots}")
    check(dots.startsWith('*** Reading 200 files'), 'the label is still printed')
    // Dots are emitted per slice of the total, not per file, so a long batch
    // cannot wrap the terminal with hundreds of them.
    def dotCount = dots.count('.')
    check(dotCount > 0 && dotCount <= 50, "dots are capped for a long batch, got ${dotCount}")
}

// ─── 122. batch-relative episode labels for the layout groups ────────────────
// Display only, and deliberately not parseSeasonEpisode: this needs the rest of
// the batch to know where the number starts, which is exactly what makes it safe
// (1080p and x264 sit inside the shared prefix) and exactly why it cannot answer
// "which episode is this file" for one file on its own.
runTest('122_batch_episode_labels') { workDir ->
    def episodes = evaluate(new File(repoRoot, 'src/lib/episodes.groovy'))
    def label = { List names -> episodes.formatRanges(episodes.batchLabels(names).values()) }

    checkEquals(label((1..4).collect { "My Show - S01E0${it} - Title".toString() }), '01-04',
                'an SxxEyy batch uses the episode number')
    checkEquals(label((1..10).collect {
        "[Salender-Raws] Hellsing OVA - ${String.format('%02d', it)} (BD 1920x1080 x264 5.1 FLAC)".toString()
    }), '01-10', 'a plain numbered batch is anchored on its common prefix')

    // The prefix of 10..19 ends mid-number, so the trailing digits have to come
    // off it or the batch reads as 0-9.
    checkEquals(label((10..19).collect { "Show - ${it}".toString() }), '10-19', 'trailing digits are trimmed')
    checkEquals(label((1..9).collect { "Show - 0${it}".toString() }), '01-09', 'padding survives the trim')
    checkEquals(label((1..3).collect { "Hellsing${it}".toString() }), '1-3', 'no separator is still a number')
    checkEquals(label((1..3).collect { "Show (2024) 0${it}".toString() }), '01-03',
                'a number in the show name is inside the prefix, so it cannot be mistaken for one')
    checkEquals(label(['Show - 01', 'Show - 02', 'Show - 05']), '01-02, 05', 'gaps break the run')
    checkEquals(episodes.batchLabels(['Alpha', 'Beta']), [:], 'an unnumbered batch has no labels at all')

    // The line a layout-group header carries, composed here rather than in each
    // caller: inspect.groovy's report and mux.groovy's pre-flight are the same
    // report and must render a group identically. The real ui.pluralize is used
    // rather than a stub, so this cannot drift from what the scripts pass in.
    //
    // It yields the noun and the ranges, with no count of its own: the caller
    // has already printed one ("6 files - episodes 05-10"), which is exactly the
    // job pluralize exists for as against plural.
    checkEquals(episodes.membershipLabel((1..3).collect { "Show - S01E0${it} - T".toString() }, ui.pluralize),
                'episodes 01-03', 'membershipLabel composes the noun and the ranges')
    checkEquals(episodes.membershipLabel(['Show - S01E01 - T'], ui.pluralize), 'episode 01',
                'and agrees in number with what it found')
    checkEquals(episodes.membershipLabel(['Alpha', 'Beta'], ui.pluralize), null,
                'declining when the batch is not numbered, so the caller falls back to file names')
}

// ─── 123. every layout group names its files, the largest included ───────────
// The old report left the majority unnamed because its job was spotting
// outliers. Now the job is "here are your muxing passes and what goes in each",
// and the first group is as much an answer as the others.
runTest('123_every_group_names_its_files') { workDir ->
    ['01', '02', '03', '04'].each { ep ->
        stageInput(workDir, "Show - S01E${ep} - Title.mkv".toString())
        stageExternalTrack(workDir, "Rus sound/[Both]/Show - S01E${ep} - Title.mka".toString(), 'audio', 3)
    }
    // Only the first two episodes have the second dub, so the batch splits 2/2.
    ['01', '02'].each { ep ->
        stageExternalTrack(workDir, "Rus sound/[Early]/Show - S01E${ep} - Title.mka".toString(), 'audio', 2)
    }

    def (code, out) = runInspect(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('episodes 01-02'), "the group with both dubs names its episodes; got:\n${out}")
    check(out.contains('episodes 03-04'), 'and so does the other one, though it is not an outlier')
    // A numbered batch puts membership in the header, so the whole plan reads off
    // the "***" lines. It is not a deviation list, so it carries no "<-" marker.
    def headers = out.readLines().findAll { it.contains('*** Layout') }
    check(headers.every { it.contains('episodes ') }, "every group header names its episodes; got:\n${out}")
    check(headers.every { !it.contains('<-') }, 'membership carries no deviation marker')
}

// ─── 124. the video title is shown but never compared ────────────────────────
// It routinely carries the episode name, so comparing it would report every file
// in the season as deviant — but hiding it entirely threw away something worth
// seeing. A video row stands for every file in its group, so there is only "the"
// title when they all agree; when they do not, say so rather than picking one.
runTest('124_video_title_shown_not_compared') { workDir ->
    def build = { String name, String title ->
        def (code, out) = exec([mkvmergeExe, '--output', new File(workDir, name).absolutePath,
                                '--track-name', "0:${title}", '--audio-tracks', '1', '--no-subtitles',
                                testMkv.absolutePath])
        assert (code == 0 || code == 1) : "build failed:\n$out"
    }

    build('a.mkv', 'Original Japanese')
    build('b.mkv', 'Original Japanese')
    def (sameCode, sameOut) = runInspect(workDir)
    checkEquals(sameCode, 0, 'exit code')
    check(sameOut.contains('Original Japanese'), "a shared title is shown; got:\n${sameOut}")
    check(sameOut.contains('consistent'), 'and nothing is flagged')

    // Same files, different titles: the one row now stands for two values.
    build('b.mkv', 'Episode Two')
    def (code, out) = runInspect(workDir)
    checkEquals(code, 0, 'exit code')
    check(out.contains('(per file)'), "a title that varies says so; got:\n${out}")
    check(!out.contains('Episode Two'), 'no single file\'s title is presented as the group\'s')
    check(out.contains('consistent'), 'a differing video title is still never a finding')

    // "-" is the one glyph for absent, everywhere.
    check(!out.contains('(no name)'), 'nothing invents a name for an unnamed track')
}

// ─── 118b. a broken episodes.yaml never stops a report either ────────────────
// The same rule as 118, one file over. episodes.yaml is every bit as
// hand-editable as config.yaml, and it fails the same ways — plus one of its
// own, an episode number that is not a number, which throws inside normalizeYaml
// rather than at parse time and so only stays caught while the transform runs
// inside the guard.
//
// It is deliberately NOT counted into configProblems, which is what the --strict
// leg pins: --strict says "treat the findings as a failure", and episode
// metadata produces no findings. It only decorates the source paths --identify
// resolves, so there is nothing here for a verdict to be about.
runTest('118b_inspect_survives_broken_episodes_yaml') { workDir ->
    stageInput(workDir, 'Show - S01E01 - Title.mkv')

    def cases = [
        'malformed' : "show: Test\nepisodes:\n  - episode: 1\n   name: [\n",
        'empty'     : "",
        'not a map' : "- just\n- a list\n",
        'bad number': "show: Test\nepisodes:\n  - episode: \"one\"\n    name: Pilot\n",
    ]

    cases.each { label, text ->
        new File(workDir, 'episodes.yaml').setText(text, 'UTF-8')

        def (code, out) = runInspect(workDir, ['--identify'])
        checkEquals(code, 0, "${label} episodes.yaml still exits 0")
        check(out.contains('*** Show - S01E01 - Title.mkv'),
              "${label} episodes.yaml still prints the track table; got:\n${out}")
        check(!out.contains('Exception'), "${label} episodes.yaml produces no stack trace; got:\n${out}")
        check(out.contains('Warning'), "${label} episodes.yaml is reported as a warning; got:\n${out}")

        def (strictCode, strictOut) = runInspect(workDir, ['--strict'])
        checkEquals(strictCode, 0, "${label} episodes.yaml does not fail --strict")
        check(!strictOut.contains('config problem'),
              "${label} episodes.yaml is not counted as a config problem; got:\n${strictOut}")
    }
}

// ─── 119b. mux refuses a broken episodes.yaml, cleanly ───────────────────────
// The mirror of 118b: the same four failures, the opposite policy. A title
// stamped from metadata that could not be read is the same confidently-wrong
// output as a mux against a config that was never understood, so it is exit 2 —
// but an error message, never a stack trace.
runTest('119b_mux_rejects_broken_episodes_yaml') { workDir ->
    stageInput(workDir, 'Show - S01E01 - Title.mkv')
    writeConfig(workDir, cfg(audioTracks: [[id: 1, language: 'ja', title: 'Japanese', default: true]]))

    ["show: Test\nepisodes:\n  - episode: 1\n   name: [\n",
     "",
     "- just\n- a list\n",
     "show: Test\nepisodes:\n  - episode: \"one\"\n    name: Pilot\n"].each { text ->
        new File(workDir, 'episodes.yaml').setText(text, 'UTF-8')

        def (code, out) = runMkvGroovy(workDir, ['--dry-run'])
        checkEquals(code, 2, "exits 2 on episode metadata it cannot use; got:\n${out}")
        check(out.contains('*** Error:'), 'says what is wrong')
        check(out.contains('episodes.yaml'), 'and names the file it is talking about')
        check(!out.contains('Exception'), "no stack trace; got:\n${out}")
        check(!new File(workDir, 'mkv').exists(), 'muxed nothing')
    }
}

// ─── 125. two files of one kind from one variant are two slots ───────────────
// A group shipping both .ass and .srt for E01 and only .ass for E02 is two
// muxing passes: mkvmerge is handed a different set of files. Keyed on the kind
// of file alone the two collide in the slot map, the second silently overwrites
// the first, and the report says one pass while the legend correctly counts
// three files — the exact wrong answer the externals-as-slots design exists to
// prevent.
runTest('125_same_kind_externals_split_groups') { workDir ->
    ['01', '02'].each { ep ->
        stageInput(workDir, "Show - S01E${ep} - Title.mkv".toString())
        stageExternalText(workDir, "Subs/[GroupA]/Show - S01E${ep} - Title.ass".toString())
    }
    // Only E01 also gets a .srt, from the same directory and so the same variant.
    stageExternalText(workDir, 'Subs/[GroupA]/Show - S01E01 - Title.srt')

    def (code, out) = runInspect(workDir)
    checkEquals(code, 0, "exit code (output was:\n$out\n)")

    // One variant: same leaf directory, same (empty) suffix. The split is in the
    // slots, not in the discovery — those are different questions.
    check(out.contains('1 variant discovered'),
          "both files belong to one variant; got:\n${out}")
    check(out.contains('Layout 2'),
          "but the episode carrying an extra file of the same kind is its own pass; got:\n${out}")
    check(!out.contains('Layout 3'), 'and there are two passes, not one per file')
}

// ─── 126. the shared YAML-mapping loader classifies, and only classifies ─────
// Four call sites — config.yaml and episodes.yaml, in mux and in inspect — share
// this, and each applies its own policy to the result. So what is pinned here is
// the classification itself, plus the two options that exist precisely because
// the callers differ: the charset and the in-guard transform.
runTest('126_yaml_mapping_loader') { workDir ->
    def loader = evaluate(new File(repoRoot, 'src/lib/yaml.groovy'))({ String t -> new Yaml().load(t) })
    def write = { String name, String text ->
        def f = new File(workDir, name)
        f.setText(text, 'UTF-8')
        f
    }

    def good = loader.loadMapping(write('good.yaml', "a: 1\n"))
    checkEquals(good.value, [a: 1], 'a mapping comes back as itself')
    checkEquals(good.problem, null, 'and reports no problem')

    def empty = loader.loadMapping(write('empty.yaml', ''))
    checkEquals(empty.value, null, 'an empty file has no value')
    check(empty.problem?.contains('is empty'), "and says so; got: ${empty.problem}")

    def list = loader.loadMapping(write('list.yaml', "- one\n- two\n"))
    checkEquals(list.value, null, 'a sequence is not a mapping')
    check(list.problem?.contains('not a mapping'), "and says so; got: ${list.problem}")

    def broken = loader.loadMapping(write('broken.yaml', "a: 1\n  b: [\n"))
    checkEquals(broken.value, null, 'a syntax error has no value')
    // The fragment is lowercase and unpunctuated because the caller finishes it:
    // mux appends "; there is nothing to mux with." and exits, inspect appends
    // "; continuing without it." and does not.
    check(broken.problem?.startsWith('could not parse'),
          "and is a bare fragment for the caller to finish; got: ${broken.problem}")
    checkEquals(broken.problem.readLines().size(), 1,
                'first line only - snakeyaml dumps a screenful of context after it')

    // The transform runs INSIDE the guard, which is the only reason normalizeYaml
    // is safe to pass: `episode: "one"` throws there, not at parse time.
    def blew = loader.loadMapping(write('t.yaml', "a: 1\n"),
                                  [transform: { throw new NumberFormatException('boom') }])
    checkEquals(blew.value, null, 'a transform that throws is a problem, not a stack trace')
    check(blew.problem?.contains('could not parse'), "and is reported like one; got: ${blew.problem}")

    // charset is a parameter because episodes.yaml is a fixed UTF-8 contract while
    // config.yaml keeps Groovy's auto-detection. Unifying them would break one.
    def cyrillic = loader.loadMapping(write('cyr.yaml', "show: Тест\n"), [charset: 'UTF-8'])
    checkEquals(cyrillic.value?.show, 'Тест', 'an explicit charset reads non-ASCII correctly')
}

// ─── 127. the check report grays a guessed external language ─────────────────
// The same de-emphasis --identify already applies, wired through the slot
// signature so the batch report matches it and the documented palette. A probed
// language is the real thing and stays default-coloured even when its own folder
// name would have guessed something else; only an inferred one is grayed. The
// guessed flag rides alongside SIG_KEYS, never inside it, so it cannot change how
// files are grouped.
runTest('127_check_grays_guessed_language') { workDir ->
    ['01', '02'].each { ep ->
        stageInput(workDir, "Show - S01E${ep} - Title.mkv".toString())
        // Probed .mka carrying a real Japanese track, under a folder that would
        // otherwise guess Russian: the probe wins, so this is never a guess.
        stageExternalTrack(workDir, "Rus sound/[Real]/Show - S01E${ep} - Title.mka".toString(), 'audio', 1)
        // Raw .ass, never probed: the only language it can have is the folder's
        // guess, so it is the one that gets grayed.
        stageExternalText(workDir, "Rus subs/[Guess]/Show - S01E${ep} - Title.ass".toString())
    }

    // Both episodes carry both externals, so the batch is one consistent layout
    // group with no minority file-evidence lists — the only gray in the output is
    // therefore a guessed language, which is what makes the assertions clean.
    def (code, out) = runInspect(workDir, ['--color', 'always'])
    checkEquals(code, 0, "exit code (output was:\n$out\n)")
    check(out.contains("${esc}[90mrus? ${esc}[0m"),
          "a guessed external language is grayed in the check table; got:\n${out}")
    check(!out.contains("${esc}[90mjpn"),
          "a probed language is never grayed, even under a mis-suggesting folder; got:\n${out}")

    // Plain mode keeps the '?' (it is in the string, not the colour) and emits no
    // escapes at all — the guess is still marked, just not coloured.
    def (plainCode, plainOut) = runInspect(workDir, ['--color', 'never'])
    checkEquals(plainCode, 0, 'exit code (never)')
    check(plainOut.contains('rus?'), 'the guess is still marked with a ? under --color never')
    check(!plainOut.contains("${esc}["), 'and nothing is coloured')
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
