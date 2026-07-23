// episodes.groovy — shared episode-metadata helpers for the scripts in src/.
//
// Loaded at runtime with evaluate(new File(scriptDir, 'lib/episodes.groovy')), the
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

// "01-04, 07, 09-10" — a set of episode numbers as compact runs. Anything that
// is not a plain number is passed through as itself, so a mixed list degrades to
// a comma-joined one rather than lying about a range.
def formatRanges = { Collection labels ->
    def sorted = labels.collect { it.toString() }.unique().sort()
    if (!sorted) return ''
    if (!sorted.every { it ==~ /\d+/ }) return sorted.join(', ')

    def runs = []
    def start = sorted[0]
    def prev = sorted[0]
    sorted.drop(1).each { n ->
        if ((n as Integer) == (prev as Integer) + 1) {
            prev = n
        } else {
            runs << [start, prev]
            start = n
            prev = n
        }
    }
    runs << [start, prev]
    runs.collect { it[0] == it[1] ? it[0] : "${it[0]}-${it[1]}" }.join(', ')
}

// A short label per file for a *batch*, to say which episodes are in a group
// without printing ten sixty-character file names.
//
// **Display only.** This is deliberately not parseSeasonEpisode: it is
// batch-relative — it needs the other names to know where the number starts —
// whereas identity has to be answerable for one file on its own. A wrong guess
// here costs a slightly odd line in a report; a wrong guess in identity stamps
// the wrong title into a file. Numbering that drives renames or ${episodeNum} is
// the separate, opt-in job described in ROADMAP.md.
//
// Two tiers. If every name carries an SxxEyy, use the episode number. Otherwise
// anchor on what the whole batch shares: the longest common prefix, with any
// trailing digits trimmed off it, and take the run of digits that follows.
// Trimming is what keeps 10-19 from collapsing to 0-9 (their common prefix ends
// mid-number) and what preserves the padding of 01-09. Anchoring is what makes
// this safe at all: "1080p" and "x264" sit inside the common prefix, so nothing
// can mistake them for an episode.
def batchLabels = { Collection baseNames ->
    def names = baseNames.collect { it.toString() }
    if (!names) return [:]

    if (names.every { parseSeasonEpisode(it) != null }) {
        return names.collectEntries { [it, parseSeasonEpisode(it).episode] }
    }

    def prefix = names.inject(names[0]) { common, name ->
        def limit = Math.min(common.length(), name.length())
        def i = 0
        while (i < limit && common.charAt(i) == name.charAt(i)) i++
        common.substring(0, i)
    }
    prefix = prefix.replaceAll(/\d+$/, '')

    def labels = [:]
    for (String name : names) {
        def rest = name.length() > prefix.length() ? name.substring(prefix.length()) : ''
        def matcher = rest =~ /^(\d{1,4})/
        if (!matcher) return [:]          // one unnumbered name and the whole idea is off
        labels[name] = matcher.group(1)
    }
    labels
}

// The membership line a layout group's header carries: "3 episodes 01-03", or
// null when the batch is not numbered and the caller has to fall back to a plain
// file list. Display only, like everything else here that touches numbering.
//
// It lives here rather than in each caller because inspect.groovy's report and
// mux.groovy's pre-flight are the same report and must render a group the same
// way; two copies of the composition drift the moment one of them is edited.
// Base names come in already split (commons-io is the caller's, not ours) and
// `pluralize` is injected for the same reason — output.groovy is loaded there.
def membershipLabel = { Collection baseNames, Closure pluralize ->
    def labels = batchLabels(baseNames)
    if (!labels) return null
    def mine = baseNames.collect { labels[it.toString()] }.unique()
    "${pluralize(mine.size(), 'episode')} ${formatRanges(mine)}"
}

[
    parseSeasonEpisode : parseSeasonEpisode,
    sanitizeForFilename: sanitizeForFilename,
    parseCanonicalName : parseCanonicalName,
    indexFromLines     : indexFromLines,
    normalizeYaml      : normalizeYaml,
    formatRanges       : formatRanges,
    batchLabels        : batchLabels,
    membershipLabel    : membershipLabel,
]
