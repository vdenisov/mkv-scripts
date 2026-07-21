import groovy.json.JsonSlurper
import groovy.transform.Field
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine
import picocli.groovy.PicocliScript2

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate

@Grab('info.picocli:picocli-groovy:4.6.3')
@Grab('org.yaml:snakeyaml:1.30')
@GrabConfig(systemClassLoader = true)
@CommandLine.Command(name = "mkv-fetch-episodes", mixinStandardHelpOptions = true,
                     description = "Fetch episode names for one season from TheMovieDB into " +
                                   "episodes.yaml and episodes.txt.")
@PicocliScript2

@CommandLine.Option(names = ["-a", "--api-key"],
                    description = "TheMovieDB API key. If one is not supplied, will try to read it from 'apikey.txt' file")
@Field String apiKey

@CommandLine.Option(names = ["-i", "--show-id"], required = true,
                    description = "TheMovieDB show ID, or the show's URL " +
                                  "(https://www.themoviedb.org/tv/1920-twin-peaks)")
@Field String showId

@CommandLine.Option(names = ["-s", "--season"],
                    description = "The season number. Optional when the --show-id URL names a season")
@Field String season

@CommandLine.Option(names = ["-l", "--language"], paramLabel = "LOCALE",
                    description = "TheMovieDB locale for names, e.g. ru-RU. Defaults to en-US. " +
                                  "Names untranslated in that locale fall back to en-US")
@Field String language

// Test seam: lets the offline test suite point the script at a local stub server
// instead of the real API. Hidden because it is of no use in normal operation.
@CommandLine.Option(names = ["--base-url"], hidden = true,
                    description = "Override the API base URL (for testing)")
@Field String baseUrl = "https://api.themoviedb.org"

@CommandLine.Option(names = ["--color"], paramLabel = "WHEN",
                    description = "Colorize output: auto (default, only on a terminal and not under NO_COLOR), " +
                                  "always, or never")
@Field String colorMode = "auto"

// Shared console-output helpers, resolved relative to this script's own
// location — see output.groovy for why they are loaded explicitly by path.
def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def ui = evaluate(new File(scriptDir, 'output.groovy'))(colorMode)

// The current directory takes precedence, falling back to a copy next to this
// script, so the key does not have to be copied into every media directory. This
// is a personal secret the user places (unlike mux.groovy's config, which no
// longer falls back to the script directory).
if (apiKey == null || "" == apiKey) {
    def keyFile = [new File("apikey.txt"), new File(scriptDir, "apikey.txt")].find { it.exists() }
    if (keyFile == null) {
        ui.error("No API key: pass --api-key, or create apikey.txt in the current directory or next to fetch_episodes.groovy")
        System.exit(2)
    }
    apiKey = keyFile.readLines().find { it.trim() }?.trim()
    if (!apiKey) {
        ui.error("API key file is empty: ${keyFile.absolutePath}")
        System.exit(2)
    }
}

// --show-id doubles as a URL parameter, because copying the show's address out
// of the browser is what people actually have to hand; the numeric id has to be
// picked out of it by eye otherwise. A season in the URL supplies --season when
// that was not passed separately, so a pasted season address needs nothing else.
if (!(showId ==~ /\d+/)) {
    def url = showId
    def idMatch = url =~ '/tv/(\\d+)'
    if (!idMatch) {
        ui.error("--show-id is neither a number nor a TheMovieDB show URL: ${url}")
        System.exit(2)
    }
    showId = idMatch.group(1)

    def seasonMatch = url =~ '/season/(\\d+)'
    if (seasonMatch) {
        def urlSeason = seasonMatch.group(1)
        // Disagreement is always a mistake worth stopping for: silently
        // preferring either one fetches a season the user did not ask for.
        if (season != null && season != urlSeason) {
            ui.error("--season ${season} conflicts with season ${urlSeason} in the --show-id URL")
            System.exit(2)
        }
        season = urlSeason
    }
}

if (season == null) {
    ui.error("No season: pass --season, or a --show-id URL that names one")
    System.exit(2)
}

if (!(season ==~ /\d+/)) {
    ui.error("--season is not a number: ${season}")
    System.exit(2)
}

def client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

def encode = { String s -> URLEncoder.encode(s, StandardCharsets.UTF_8.name()) }

// One GET returning parsed JSON. TheMovieDB reports failures both as HTTP status
// codes and as a status_message in the body, so surface the body's message when
// there is one — "Invalid API key" is far more useful than "HTTP 401".
def get = { String path, String locale = 'en-US' ->
    def url = "${baseUrl}${path}?api_key=${encode(apiKey)}&language=${encode(locale)}"
    def request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(30))
        .GET()
        .build()

    HttpResponse<String> response
    try {
        response = client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (IOException e) {
        ui.error("Request to ${baseUrl}${path} failed: ${e.message}")
        System.exit(3)
    }

    def body = null
    try {
        body = new JsonSlurper().parseText(response.body())
    } catch (ignored) {
        // Leave body null; handled below. A non-JSON body is itself a failure.
    }

    if (response.statusCode() != 200) {
        def detail = (body instanceof Map && body.status_message) ? body.status_message : response.body()
        ui.error("TheMovieDB returned HTTP ${response.statusCode()} for ${path}: ${detail}")
        System.exit(3)
    }

    if (body == null) {
        ui.error("TheMovieDB returned a non-JSON response for ${path}")
        System.exit(3)
    }

    body
}

ui.header("*** Fetching episodes from TheMovieDB...")

def locale = language ?: 'en-US'

def show = get("/3/tv/${showId}", locale)

// first_air_date is absent or empty for unaired shows, so do not assume it parses
def airYear = show.first_air_date ? LocalDate.parse(show.first_air_date).year : null
println "*** The show is ${show.name} (${airYear ?: 'year unknown'})"

def seasonData = get("/3/tv/${showId}/season/${season}", locale)

if (!seasonData.episodes) {
    ui.error("No episodes returned for season ${season} - check the season number")
    System.exit(3)
}

// TheMovieDB answers an untranslated field with an empty string rather than
// falling back, so a partially translated season would otherwise write blank
// names. Re-fetch in en-US only when something actually came back empty, and
// fill just the gaps.
def needsFallback = language && (
    seasonData.episodes.any { !it.name } || !show.name || !seasonData.name
)
def fallbackShow = null
def fallbackSeason = null
if (needsFallback) {
    println "*** Some names are untranslated in ${locale}; filling them from en-US"
    fallbackShow = get("/3/tv/${showId}")
    fallbackSeason = get("/3/tv/${showId}/season/${season}")
}

def fallbackNameFor = { episodeNumber ->
    fallbackSeason?.episodes?.find { it.episode_number == episodeNumber }?.name
}

// episode_number is always present in a real response; falling back to the
// position keeps a stub or a truncated payload from writing an episodes.yaml
// with no usable numbers in it at all.
def episodes = seasonData.episodes.withIndex().collect { ep, i ->
    def number = ep.episode_number ?: (i + 1)
    [number: number, name: (ep.name ?: fallbackNameFor(number) ?: '').toString()]
}

def showName = (show.name ?: fallbackShow?.name ?: '').toString()
def seasonName = (seasonData.name ?: fallbackSeason?.name ?: '').toString()

println "*** Fetched ${episodes.size()} episode names"

// Names are written exactly as TheMovieDB spells them, including the ':' and
// '?' that cannot appear in a Windows file name. Sanitizing is rename.groovy's
// job, at the point a name becomes a file name; mux.groovy needs the original
// spelling for titles, and once stripped here it could never be recovered.
def data = new LinkedHashMap()
data.show = showName
if (airYear != null) data.year = airYear
data.season = season as int
if (seasonName) data.seasonName = seasonName
if (language) data.language = language
data.episodes = episodes.collect { ep ->
    def entry = new LinkedHashMap()
    entry.episode = ep.number
    entry.name = ep.name
    entry
}

def dumperOptions = new DumperOptions()
dumperOptions.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
// Without this snakeyaml escapes every non-ASCII character as \uXXXX, which is
// valid YAML but unreadable for exactly the titles this feature exists to keep.
dumperOptions.allowUnicode = true

// Always UTF-8, never the platform default: both files are handed to
// rename.groovy and mux.groovy, which must read them back with the same
// charset. Relying on the ambient default makes that contract depend on JVM
// version and locale rather than on anything either script states.
new File("episodes.yaml").withWriter('UTF-8') { out ->
    out.write(new Yaml(dumperOptions).dump(data))
}

new File("episodes.txt").withWriter('UTF-8') { out ->
    episodes.each { out.println it.name }
}

ui.success("*** Done")
