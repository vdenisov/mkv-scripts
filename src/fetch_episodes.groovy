import groovy.transform.Field
import groovyx.net.http.HttpBuilder
import picocli.CommandLine
import picocli.groovy.PicocliScript2

import java.time.LocalDate

import static groovyx.net.http.ContentTypes.JSON

@Grab("io.github.http-builder-ng:http-builder-ng-core:1.0.4")
@Grab('info.picocli:picocli-groovy:4.6.3')
@Grab('org.slf4j:slf4j-simple:1.7.36')

// Fix for missing JAXB API
@Grab('javax.xml.bind:jaxb-api:2.3.1')
@Grab('com.sun.xml.bind:jaxb-core:2.3.0.1')
@Grab('com.sun.xml.bind:jaxb-impl:2.3.2')
@Grab('javax.activation:activation:1.1.1')

@GrabConfig(systemClassLoader=true)
@CommandLine.Command(name = "fetch_episodes")
@PicocliScript2

@CommandLine.Option(names = ["-a", "--api-key"], description = "TheMovieDB API key. If one is not supplied, will try to read it from 'apikey.txt' file")
@Field String apiKey

@CommandLine.Option(names = ["-i", "--show-id"], description = "TheMovieDB show ID", required = true)
@Field String showId

@CommandLine.Option(names = ["-s", "--season"], description = "The season number", required = true)
@Field String season

// Resolve the API key file the same way mux.groovy resolves config.yaml: the
// current directory takes precedence, falling back to the copy next to this
// script, so the key does not have to be copied into every media directory
if (apiKey == null || "" == apiKey) {
    def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
    def keyFile = [new File("apikey.txt"), new File(scriptDir, "apikey.txt")].find { it.exists() }
    if (keyFile == null) {
        System.err.println "No API key: pass --api-key, or create apikey.txt in the current directory or next to fetch_episodes.groovy"
        System.exit(2)
    }
    apiKey = keyFile.readLines()[0].trim()
    if (!apiKey) {
        System.err.println "API key file is empty: ${keyFile.absolutePath}"
        System.exit(2)
    }
}

println "Fetching episodes from TheMovieDB..."

def builder = HttpBuilder.configure {
    request.uri = "https://api.themoviedb.org"
    request.accept = JSON[0]
    request.uri.query = [
        api_key: apiKey,
        language: "en-US"
    ]
}

def show = builder.get {
    request.uri.path = "/3/tv/$showId"
}

println "The show is ${show.name} (${LocalDate.parse(show.first_air_date).year})"

def episodes = builder.get {
    request.uri.path = "/3/tv/$showId/season/$season"
}

def episodeNames = episodes.episodes.collect {
    //Filter out characters incompatible with Windows filenames
    def filteredName = it.name.replaceAll("[\\/:*?\"<>|]", "")
    if (filteredName != it.name) {
        println "Name ${it.name} contains invalid characters, replaced with ${filteredName}"
    }
    filteredName
}

println "Fetched ${episodeNames.size()} episode names"

new File("episodes.txt").withWriter {out ->
    episodeNames.each { out.println it}
}

println "Done."