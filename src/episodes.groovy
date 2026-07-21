// episodes.groovy — shared episode-metadata helpers for the scripts in src/.
//
// Loaded at runtime with evaluate(new File(scriptDir, 'episodes.groovy')), the
// same explicit-path pattern output.groovy and tools.groovy use, and for the
// same reason: implicit sibling-class resolution depends on how and from where
// a script is launched, an absolute-path evaluate() does not.
//
// No @Grab and no imports, so loading this file never touches the network or
// the caller's classloader setup. That constraint is also why nothing here
// parses YAML: snakeyaml cannot be imported from this file. Callers own the
// I/O and the parsing and hand the already-parsed map to normalizeYaml — this
// file owns the shape and the semantics, nothing else.
//
// Episode numbers are **two-digit zero-padded strings** ('01'), both as map keys
// and in what the parsers return, because they come from and go back into file
// names. Looking one up with an int silently misses. Three-digit numbering is
// out of scope: the SxxEyy pattern matches exactly two digits.
//
// The last expression is the exported map of closures.

// Season and episode numbers from a file name, or null when absent. Tolerates
// the "s01.e01" variant. This is the join key between a media file and its
// episode metadata, and the one pattern every script must agree on.
def parseSeasonEpisode = { String baseName ->
    def matcher = (baseName ?: '').toLowerCase() =~ /s(\d\d)\.?e(\d\d)/
    matcher ? [season: matcher.group(1), episode: matcher.group(2)] : null
}

// Characters Windows rejects in a file name, plus the trailing dots and spaces
// it also rejects. Applied when a raw name is turned into a file name — never
// when it is used as a track or segment title, where the original spelling is
// exactly what is wanted.
def sanitizeForFilename = { String name ->
    (name ?: '')
        .replaceAll(/[\\\/:*?"<>|]/, '')
        .replaceAll(/[. ]+$/, '')
}

// The inverse of rename.groovy's output pattern, "Show - SxxEyy - Title", with
// an optional trailing "[Studio]" suffix stripped from the title. Anchored and
// non-greedy on the show name, so it takes the shortest prefix — matching what
// rename.groovy itself writes. A show name that itself contains " - S01E01 - "
// cannot be disambiguated and is not worth the complexity of trying.
def parseCanonicalName = { String baseName ->
    def matcher = (baseName ?: '') =~ /^(.+?) - [Ss](\d\d)[Ee](\d\d) - (.+)$/
    if (!matcher) return null
    def title = matcher.group(4).replaceAll(/\s*\[.+\]$/, '')
    [showName: matcher.group(1), season: matcher.group(2), episode: matcher.group(3), title: title]
}

// Episode number -> name from episodes.txt lines, which carry names only.
//
// `offset` is the episode number **of the first line**, not an amount added to a
// zero-based index: the default of 1 maps line 0 to '01'. It is what makes a
// partial file readable — titles for episodes 11-20 with offset 11 — and it is
// meaningful for this format alone, since episodes.txt has no numbers in it and
// line order is the only thing tying a name to an episode.
def indexFromLines = { List lines, int offset ->
    def index = [:]
    lines.eachWithIndex { line, i ->
        index[String.format('%02d', i + offset)] = line
    }
    index
}

// The parsed episodes.yaml map, normalized into the shape every consumer wants.
// Episode numbers are TheMovieDB's own, so the join against a file name is exact
// and needs no positional reasoning.
//
// `byEpisode` may be **sparse**: a season with a gap, or an entry carrying no
// episode number, simply has no key for it. Every consumer must handle a miss
// rather than assume a contiguous 1..n run.
def normalizeYaml = { Map yaml ->
    if (yaml == null) return null
    def index = [:]
    (yaml.episodes ?: []).each { ep ->
        if (ep?.episode == null) return
        index[String.format('%02d', ep.episode as int)] = (ep.name ?: '').toString()
    }
    [
        show      : yaml.show?.toString(),
        year      : yaml.year?.toString(),
        season    : yaml.season == null ? null : String.format('%02d', yaml.season as int),
        seasonName: yaml.seasonName?.toString(),
        language  : yaml.language?.toString(),
        byEpisode : index,
    ]
}

[
    parseSeasonEpisode : parseSeasonEpisode,
    sanitizeForFilename: sanitizeForFilename,
    parseCanonicalName : parseCanonicalName,
    indexFromLines     : indexFromLines,
    normalizeYaml      : normalizeYaml,
]
