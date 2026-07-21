import groovy.json.JsonSlurper
import groovy.transform.Field
import picocli.CommandLine
import picocli.groovy.PicocliScript2

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate

@Grab('info.picocli:picocli-groovy:4.6.3')
@GrabConfig(systemClassLoader = true)
@CommandLine.Command(name = "mkv-fetch-episodes", mixinStandardHelpOptions = true,
                     description = "Fetch episode names for one season from TheMovieDB into episodes.txt.")
@PicocliScript2

@CommandLine.Option(names = ["-a", "--api-key"],
                    description = "TheMovieDB API key. If one is not supplied, will try to read it from 'apikey.txt' file")
@Field String apiKey

@CommandLine.Option(names = ["-i", "--show-id"], description = "TheMovieDB show ID", required = true)
@Field String showId

@CommandLine.Option(names = ["-s", "--season"], description = "The season number", required = true)
@Field String season

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

def client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

def encode = { String s -> URLEncoder.encode(s, StandardCharsets.UTF_8.name()) }

// One GET returning parsed JSON. TheMovieDB reports failures both as HTTP status
// codes and as a status_message in the body, so surface the body's message when
// there is one — "Invalid API key" is far more useful than "HTTP 401".
def get = { String path ->
    def url = "${baseUrl}${path}?api_key=${encode(apiKey)}&language=en-US"
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

def show = get("/3/tv/${showId}")

// first_air_date is absent or empty for unaired shows, so do not assume it parses
def airYear = show.first_air_date ? LocalDate.parse(show.first_air_date).year : "year unknown"
println "*** The show is ${show.name} (${airYear})"

def seasonData = get("/3/tv/${showId}/season/${season}")

if (!seasonData.episodes) {
    ui.error("No episodes returned for season ${season} - check the season number")
    System.exit(3)
}

def episodeNames = seasonData.episodes.collect {
    // Filter out characters incompatible with Windows filenames, then strip
    // trailing dots and spaces, which Windows also rejects in a file name
    def filteredName = (it.name ?: '')
        .replaceAll(/[\\\/:*?"<>|]/, '')
        .replaceAll(/[. ]+$/, '')
    if (filteredName != it.name) {
        println "*** Name ${it.name} contains invalid characters, replaced with ${filteredName}"
    }
    filteredName
}

println "*** Fetched ${episodeNames.size()} episode names"

// Always UTF-8, never the platform default: episodes.txt is handed to
// rename.groovy, which must read it back with the same charset. Relying on the
// ambient default makes that contract depend on JVM version and locale rather
// than on anything either script states.
new File("episodes.txt").withWriter('UTF-8') { out ->
    episodeNames.each { out.println it }
}

ui.success("*** Done")
