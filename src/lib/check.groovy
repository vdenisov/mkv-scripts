// Probing and the track-consistency check, shared by mux.groovy (its pre-flight)
// and inspect.groovy (--check, --identify).
//
// Loaded by explicit path from the calling script's own location, never through
// Groovy's implicit sibling-class resolution, which is CWD-dependent (CLAUDE.md).
//
// No @Grab and no imports here, so loading never touches the network or the
// caller's classloader setup. Everything outside the JDK's default imports comes
// in through the dependency map: `parseJson` because groovy.json would have to be
// imported, `mkvmergeExe` because locating it is the caller's job, `ui` for the
// shared palette and message forms.
//
// The last expression of this file is a factory closure; call it with the
// dependency map to get the map of helpers.
{ Map deps ->

    def ui = deps.ui
    def parseJson = deps.parseJson
    def mkvmergeExe = deps.mkvmergeExe

    // One indented file name per line, truncating past `limit`. File names are
    // long and comma-joining several per line runs them together; a plain column
    // is far easier to skim and to count. ASCII only ("..." not "…"), since this
    // output lands on Windows consoles running a legacy codepage.
    def formatFileList = { List<String> names, String indent, int limit = 8 ->
        def show = Math.min(limit, names.size())   // limit may be Integer.MAX_VALUE (verbose)
        def lines = names.take(show).collect { indent + it }
        if (names.size() > show) lines << "${indent}... and ${names.size() - show} more"
        lines
    }

    // Fields compared per track by the consistency check. This is a blocklist
    // model: everything mkvmerge reports that should be stable across a season is
    // compared, and what legitimately varies per episode is left out. Duration,
    // file size, muxing application and writing library are never read at all.
    // The video track's name is excluded below rather than here, because it
    // routinely carries the episode title and so differs by design.
    //
    // default/forced are included because a flag that flips halfway through a
    // season is exactly the silent wrong-output failure this check exists to
    // catch.
    def SIG_KEYS = ['type', 'codec', 'language', 'name', 'default', 'forced']

    // NOTE: the per-track JSON key "properties" must be read with
    // .get('properties'). On Groovy 4+ both track.properties and
    // track['properties'] resolve to the bean properties of the map object
    // itself, not the JSON key of that name.
    def trackSignature = { Map track ->
        def props = track.get('properties') ?: [:]
        def type = track.type ?: '?'
        [
            type    : type,
            codec   : track.codec ?: '?',
            language: props.get('language') ?: 'und',
            // Nulled at construction time so it can never leak into a group key
            name    : type == 'video' ? null : (props.get('track_name') ?: ''),
            // ...and carried alongside for display only. SIG_KEYS does not
            // include it, so it cannot affect grouping, but the report can still
            // show a video title where every file in a group agrees on one.
            videoName: type == 'video' ? (props.get('track_name') ?: '') : null,
            default : props.get('default_track') ? true : false,
            forced  : props.get('forced_track') ? true : false
        ]
    }

    // One `mkvmerge -J` per file, parsed once. Both --identify and --check read
    // this same record, so asking for both does not double the number of
    // subprocesses.
    def probeFile = { File file ->
        def proc = [mkvmergeExe, '-J', file.absolutePath].execute()
        def json = proc.inputStream.text
        proc.waitFor()

        if (proc.exitValue() != 0) {
            return [file: file, ok: false, reason: "mkvmerge exit ${proc.exitValue()}", tracks: [:], chapters: 0]
        }

        def parsed = parseJson(json)

        // mkvmerge -J exits 0 even for a file it cannot read, reporting the
        // failure in container.recognized/supported instead. Checking only the
        // exit code would let a corrupt file into the comparison as a file with
        // no tracks, which reads as "every track is absent here" and poisons the
        // whole report.
        def container = parsed.container ?: [:]
        if (!container.recognized) {
            return [file: file, ok: false, reason: 'not recognised as a media file', tracks: [:], chapters: 0]
        }
        if (!container.supported) {
            return [file: file, ok: false, reason: 'container not supported by mkvmerge', tracks: [:], chapters: 0]
        }

        def tracks = new LinkedHashMap()
        (parsed.tracks ?: []).each { track ->
            def id = track.id as Integer
            tracks[id] = trackSignature(track) + [id: id]
        }

        def chapterCount = 0
        (parsed.chapters ?: []).each { chapterCount += (it.num_entries ?: 0) }

        [file: file, ok: true, raw: parsed, tracks: tracks, chapters: chapterCount]
    }

    // ── Consistency check ───────────────────────────────────────────────────
    //
    // config.yaml selects tracks by numeric ID, which assumes every episode has
    // the same track layout. When that breaks — a translation added mid-season,
    // an old one dropped, a different release group ordering tracks differently —
    // mkvmerge does not complain. It muxes whatever sits at that ID, and the
    // result is a season where some episodes have the wrong dub labelled as
    // something else.

    // Group files by the value they carry at each track ID.
    //
    // Deliberately does NOT anchor on the first file: the reference is the
    // largest population. If a translation was dropped from episode 8 onward,
    // anchoring on file one would report 17 files as deviant against a sample of
    // one. Which group is correct is the user's call, not this script's.
    // The same grouping serves two kinds of slot: a numeric track ID inside the
    // file, and an external file attached to the episode, which the caller keys
    // by whatever identity it has (a label, not an ID — external files have no
    // order and no ID to select them by). `slotsOf` says which of the two.
    def groupSlots = { List infos, Closure slotsOf ->
        def allIds = (infos.collectMany { (slotsOf(it) ?: [:]).keySet() } as Set).sort()

        allIds.collect { id ->
            def byKey = new LinkedHashMap()
            infos.each { info ->
                def sig = (slotsOf(info) ?: [:])[id]
                def key = (sig == null) ? null : SIG_KEYS.collect { sig[it] }
                if (!byKey.containsKey(key)) byKey.put(key, [sig: sig, files: []])
                byKey.get(key).files << info.file.name
            }

            def groups = byKey.values().toList().sort { a, b ->
                (b.files.size() <=> a.files.size()) ?: (a.files[0] <=> b.files[0])
            }
            // On an even split nothing is the minority, so nothing gets singled out
            def maxSize = groups[0].files.size()
            groups.each { it.minority = it.files.size() < maxSize }

            def present = groups.findAll { it.sig != null }
            def varying = SIG_KEYS.findAll { k -> present.collect { it.sig[k] }.unique().size() > 1 }

            [id        : id,
             type      : present ? present[0].sig.type : '?',
             label     : present ? present[0].sig.label : null,
             groups    : groups,
             varying   : varying,
             missing   : groups.find { it.sig == null }?.files ?: [],
             consistent: groups.size() == 1]
        }
    }

    def groupTracks = { List infos -> groupSlots(infos) { it.tracks } }

    // External slots are supplied by the caller on each probed record as
    // `externals`: a map of slot key to a signature of the same shape as an
    // internal track's, plus a `label` for the ID column. mux.groovy sets none,
    // so everything downstream of this is inert there.
    def groupExternals = { List infos -> groupSlots(infos) { it.externals } }
    def hasExternals = { List infos -> infos.any { it.externals } }

    // Two tracks are only genuinely ambiguous when type, language, codec AND name
    // all match — including both being unnamed. AC-3 "English" and DTS "English"
    // are perfectly distinguishable, and so is a track named "Director's
    // Commentary". Flag only the case where ID-based selection cannot be reasoned
    // about at all.
    //
    // Aggregated by (signature, ids) rather than reported per file, so a 24-file
    // batch that all shares an ambiguity prints one note instead of twenty-four.
    def findDuplicates = { List infos ->
        def acc = new LinkedHashMap()
        infos.each { info ->
            info.tracks.values()
                .findAll { it.type != 'video' }
                .groupBy { [it.type, it.language, it.codec, it.name] }
                .findAll { key, group -> group.size() > 1 }
                .each { key, group ->
                    def ids = group.collect { it.id }.sort()
                    def acckey = [key, ids]
                    if (!acc.containsKey(acckey)) {
                        acc.put(acckey, [type: key[0], language: key[1], codec: key[2], name: key[3],
                                         ids: ids, files: []])
                    }
                    acc.get(acckey).files << info.file.name
                }
        }
        acc.values().toList()
    }

    // ── Blocking vs informational ───────────────────────────────────────────
    // A discrepancy only corrupts output when it lands on a track the config
    // picks by ID. If every track of that type is being copied, IDs cannot select
    // the wrong thing, so the difference is informational. mux.groovy hardcodes
    // 0: for video, hence the fixed video ID below.
    //
    // Everything is empty when inspecting without a config: nothing is
    // "selected", so the check prints structure only and skips the
    // blocking/informational classification.
    def makeSelection = { Map config ->
        def selectedVideoIds = config ? ([0] as Set) : ([] as Set)
        def selectedAudioIds = (config?.mainSource?.audioTracks ?: []).collect { it.id as Integer } as Set
        def selectedSubIds = (config?.mainSource?.subtitleTracks ?: []).collect { it.id as Integer } as Set
        def selectedIds = selectedVideoIds + selectedAudioIds + selectedSubIds

        def configTitleFor = { Integer id ->
            ((config?.mainSource?.audioTracks ?: []) + (config?.mainSource?.subtitleTracks ?: []))
                .find { (it.id as Integer) == id }?.title
        }

        def copiesAllOfType = { String type, List infos ->
            def selected = (type == 'audio') ? selectedAudioIds
                         : (type == 'subtitles') ? selectedSubIds
                         : selectedVideoIds
            def seen = infos.collectMany { info ->
                info.tracks.values().findAll { it.type == type }.collect { it.id }
            } as Set
            !seen.isEmpty() && selected.containsAll(seen)
        }

        def isBlocking = { Integer trackId, String type, List infos ->
            selectedIds.contains(trackId) && !copiesAllOfType(type, infos)
        }

        [hasConfig      : config != null,
         selectedIds    : selectedIds,
         configTitleFor : configTitleFor,
         copiesAllOfType: copiesAllOfType,
         isBlocking     : isBlocking]
    }

    // ── Report ──────────────────────────────────────────────────────────────
    // A short type name for a track, for the table and the layout descriptions.
    def shortType = { String type -> type == 'subtitles' ? 'subs' : type }

    // A file's track LAYOUT: the type at each ID, ignoring codec/name/flags. Two
    // files share a layout when they have the same track IDs with the same type
    // at each. This is what separates a genuinely different release (tracks in a
    // different order, or one missing) from the same release with a value changed.
    // Two files share a layout when they would need the same muxing command:
    // the same track types at the same IDs, AND the same set of external files
    // attached. The external part is a SET, not a sequence — external files have
    // no order to preserve — and it is what makes the group count answer the
    // question the report is really asked: how many configs will this season
    // need. A season whose dubs arrive at different episodes is not one job
    // however uniform its .mkv files are.
    //
    // The two halves stay addressable on their own, because the report has to
    // say which of them differs ("a different track layout" about a set of
    // identical .mkv files with a dub missing sends the reader looking in the
    // wrong place). Taking the composite key apart again by splitting on its
    // separator would work only for as long as no slot key can contain one.
    def internalLayoutKey = { Map info ->
        info.tracks.sort { it.key }.collect { id, sig -> "${id}:${sig.type}" }.join(' ')
    }
    def externalLayoutKey = { Map info -> (info.externals ?: [:]).keySet().sort().join(' ') }
    def layoutKey = { Map info ->
        def internal = internalLayoutKey(info)
        def external = externalLayoutKey(info)
        external ? "${internal} + ${external}" : internal
    }

    // Truncate an over-long track name so it cannot break the table's alignment.
    // ASCII "..." rather than an ellipsis, for the same Windows-console reason.
    def fitName = { String name, int width ->
        name.length() > width ? name[0..<(width - 3)] + '...' : name
    }

    def plural = ui.plural
    def pluralize = ui.pluralize

    // The differing-cell highlight in the check tables. Terminal detection and
    // the --color/NO_COLOR gating live in output.groovy, shared with the other
    // scripts.
    def hl = ui.yellow

    // A fixed-width table cell, padded *before* any colour is applied so the ANSI
    // escapes never count toward the width and break alignment.
    def cell = { value, int width, boolean diff ->
        def s = String.format("%-${width}s", value == null ? '' : value.toString())
        diff ? hl(s) : s
    }

    // The LANG cell, which grays a guessed value (rus?) — a language inferred from
    // a folder name rather than read from the file — the sanctioned de-emphasis
    // matching --identify and the documented palette. A differing value still wins
    // the cell: the yellow diff-highlight is this table's whole job, and a guess
    // that also varied would be better shown as varying. (In practice a guess
    // never varies within its slot, since every file there shares one extension
    // and one folder guess, but the precedence is the correct one to state.)
    def langCell = { Map sig, boolean diff ->
        def s = String.format("%-5s", sig.language == null ? '' : sig.language.toString())
        diff ? hl(s) : (sig.guessed ? ui.gray(s) : s)
    }

    // Print a file list with the "<-" marker on the last named file, since the
    // list sits above the row it describes — the marker adjacent to that row reads
    // more clearly than one at the top, next to the unrelated row above. The rest
    // of the list is a plain hanging indent, so a multi-line list is not mistaken
    // for several groups. The "... and N more" summary (if any) stays below the
    // marker.
    def printMinority = { List<String> names, int limit ->
        def lines = formatFileList(names, '              ', limit)   // 14-space hanging indent
        if (!lines) return
        def markIdx = lines.findLastIndexOf { !it.contains('... and ') }
        if (markIdx < 0) markIdx = 0
        // The list is evidence, not primary data: gray, so the table rows stand
        // out against it. The marker keeps the default foreground — its job is to
        // stay findable inside the gray block — so this is the one place where a
        // line holds two colour segments (still whole segments, never mid-word).
        lines.eachWithIndex { line, i ->
            if (i == markIdx) {
                println('           <- ' + ui.gray(line.substring(14)))
            } else {
                println ui.gray(line)
            }
        }
    }

    // Row count is bounded by track count, not file count, so a 200-episode batch
    // prints as compactly as a 3-episode one. All tracks are listed, not only the
    // varying ones: this table doubles as the batch's authoritative track map,
    // which is what you read to check config.yaml's numeric IDs against reality.
    //
    // `opts` carries what the caller knows and this file does not: `verbose` (the
    // --check-verbose modifier) and the selection map from makeSelection above.
    def runConsistencyCheck = { List mediaFiles, Map infos, Map opts ->
        def verbose = opts.verbose ?: false
        def hasConfig = opts.hasConfig
        def selectedIds = opts.selectedIds
        def configTitleFor = opts.configTitleFor
        def isBlocking = opts.isBlocking

        def ok = mediaFiles.collect { infos[it] }.findAll { it != null && it.ok }
        def bad = mediaFiles.collect { infos[it] }.findAll { it != null && !it.ok }

        // Named by the caller: the same report is a pre-flight when mux runs it
        // before muxing and the whole point of the run when mkv-inspect does.
        def header = "*** ${opts.headerLabel ?: 'Pre-flight check'}: ${plural(ok.size(), 'file')}"
        if (bad) header += " (${bad.size()} could not be identified by mkvmerge and are excluded)"
        ui.header(header)
        if (bad) {
            formatFileList(bad.collect { "${it.file.name} (${it.reason})".toString() }, '      ')
                .each { println it }
        }
        if (!ok) {
            println()
            return 0
        }
        println()

        def limit = verbose ? Integer.MAX_VALUE : 8
        def blocking = []
        def informational = []

        // Split files by track layout — the type at each ID. Files that share a
        // layout are the same release and can be compared value-by-value; files
        // with a different layout (a shifted track order, or a missing track) are
        // a different release and get their own table. Ordered largest group
        // first, ties broken by name so the output is deterministic.
        def byLayout = ok.groupBy { layoutKey(it) }
        def layoutGroups = byLayout.values().toList()
            .sort { a, b -> (b.size() <=> a.size()) ?: (a[0].file.name <=> b[0].file.name) }
        def largest = layoutGroups[0]
        def largestTypeAt = { Integer id -> largest[0].tracks[id]?.type }

        // Size the NAME column to the longest name actually present, so it is not
        // clipped on wide screens nor padded to a fixed width when everything is
        // short. Clamped so one pathological title cannot blow the line width.
        // The NAME column carries the track's own name and nothing else, external
        // rows included: which variant a row belongs to is already said by its
        // label in the ID column and spelled out in the legend, so composing the
        // two here would invent "[Viki & Azazel] - Viki & Azazel".
        // "-" is the one glyph for "nothing here". It replaced "(no name)"
        // because it reads better in a column that competes for width, and it
        // still highlights visibly when a name splits between empty and set —
        // which an empty cell never did.
        def displayName = { Map sig -> sig.type == 'video' ? '-' : (sig.name ?: '-') }
        def nameLengths = ok.collectMany { info ->
            (info.tracks.values() + (info.externals ?: [:]).values()).collect { displayName(it).length() } +
            info.tracks.values().collect { (it.videoName ?: '').length() }
        }
        def nameWidth = Math.min(60, Math.max(12, (nameLengths ?: [0]).max()))

        // Print one structural group's table. Within a group every ID has a single
        // type, so rows differ only in codec/language/name/flags. When an ID's
        // value varies it is split into a row per distinct value, largest first;
        // the differing cells are highlighted. In the largest group the minority
        // rows name their files just above themselves (the majority is the norm,
        // unnamed); outlier groups already list their files above the table, so
        // they don't repeat them per row.
        // `tgs` is passed in rather than recomputed: the caller already needs the
        // internal and external groupings for its own classification, and each
        // one is a full pass over every track and slot of every file in the group.
        def printGroupTable = { List group, boolean isLargest, List tgs ->
            println ui.cyan("    ${cell('ID', 4, false)} ${cell('TYPE', 6, false)} ${cell('CODEC', 20, false)} " +
                            "${cell('LANG', 5, false)} ${cell('DEF', 4, false)} ${cell('FOR', 4, false)} NAME")

            // NAME is the last column, so it is not padded (no trailing
            // whitespace); it is the only cell that can be highlighted on its own
            // here.
            // A video row stands for every file in the group, and their titles
            // routinely differ — that is why the name is not compared. So show the
            // title when they all agree, and say that it varies when they do not,
            // rather than picking one file's arbitrarily or hiding it entirely.
            def videoNameFor = { id ->
                def names = group.collect { it.tracks[id]?.videoName }.findAll { it != null }.unique()
                if (names.size() != 1) return names ? '(per file)' : '-'
                names[0] ?: '-'
            }

            def rowFor = { id, Map sig, Set diff ->
                // An external row is identified by its label in the ID column; its
                // NAME is its own track name, exactly like an internal track's.
                def nm = sig.type == 'video' ? fitName(videoNameFor(id), nameWidth)
                                             : fitName(sig.name ?: '-', nameWidth)
                "    ${cell(sig.label != null ? sig.label : id, 4, false)} " +
                "${cell(shortType(sig.type), 6, diff.contains('type'))} " +
                "${cell(sig.codec, 20, diff.contains('codec'))} " +
                "${langCell(sig, diff.contains('language'))} " +
                "${cell(sig.default ? 'yes' : 'no', 4, diff.contains('default'))} " +
                "${cell(sig.forced ? 'yes' : 'no', 4, diff.contains('forced'))} " +
                "${diff.contains('name') ? hl(nm) : nm}"
            }

            tgs.each { tg ->
                if (tg.consistent) {
                    println rowFor(tg.id, tg.groups[0].sig, [] as Set)
                    return
                }
                def varying = tg.varying as Set
                def maxSize = tg.groups[0].files.size()
                // In the common (largest) group the strict-majority row is the
                // reference: unhighlighted and unnamed, since listing the norm
                // would be dozens of files. An outlier group has no reference —
                // every value is a deviation, so every row names its files.
                def strictMajority = isLargest && tg.groups.size() > 1 && tg.groups[1].files.size() < maxSize
                tg.groups.eachWithIndex { g, i ->
                    def isReference = i == 0 && strictMajority
                    if (!isReference) printMinority(g.files, limit)   // files above their row
                    println rowFor(tg.id, g.sig, isReference ? ([] as Set) : varying)
                }
            }
        }

        def multi = layoutGroups.size() > 1
        layoutGroups.eachWithIndex { group, gi ->
            // Grouped once per layout group and reused by the table and by the
            // classification below.
            def trackGroups = groupTracks(group)
            def externalGroups = groupExternals(group)
            if (multi) {
                def shape = group[0].tracks.sort { it.key }.collect { id, sig -> shortType(sig.type) }.join(', ')
                // Labels only, not the variant names: the table below lists every
                // external file as a row, so spelling them out here says the same
                // thing twice and wraps the line. The labels still distinguish two
                // groups whose internal tracks are identical, and the legend a few
                // lines up decodes them.
                def externalLabels = (group[0].externals ?: [:]).values()
                                         .collect { it.label }.unique().sort().join(' ')
                if (externalLabels) shape += " + ${externalLabels}"

                // Every group says which files are in it, the largest included.
                // The old rule left the majority unlisted because the report's job
                // was spotting outliers; now the job is "here are your muxing
                // passes and what goes in each", and an unnamed group is a missing
                // answer.
                //
                // When the batch is numbered this rides in the header, so the
                // whole plan can be read off the "***" lines without descending
                // into the tables. Otherwise the file names go below — they cannot
                // fit on one line. Labels come from the whole batch, so within a
                // run every group renders the same way; the reader never has to
                // look in two places. No "<-" marker either way: that means "these
                // rows deviate", a different question from "these files are here".
                def members = opts.membershipFor ? opts.membershipFor(group.collect { it.file }) : null
                def count = plural(group.size(), 'file') + (members ? " - ${members}" : '')
                ui.header("*** Layout ${gi + 1} (${count}): ${shape}")
                if (!members) {
                    formatFileList(group.collect { it.file.name }, '      ', limit)
                        .each { println ui.gray(it) }
                }
            }
            printGroupTable(group, gi == 0, trackGroups + externalGroups)
            println()

            // Classification. Non-largest groups are structural outliers, blocking
            // when the layout change lands on a selected ID. The largest group is
            // classified on its per-ID value differences.
            if (gi > 0) {
                def affected = selectedIds.findAll { id -> group[0].tracks[id]?.type != largestTypeAt(id) }.sort()
                def verb = group.size() == 1 ? 'uses' : 'use'
                // A group can differ from the norm in its tracks, in the external
                // files attached to it, or both, and saying "a different track
                // layout" about a set of identical .mkv files with a dub missing
                // would send the reader looking in the wrong place.
                def internalDiffers = internalLayoutKey(group[0]) != internalLayoutKey(largest[0])
                def externalsDiffer = externalLayoutKey(group[0]) != externalLayoutKey(largest[0])
                def what = internalDiffers && externalsDiffer ? 'a different track layout and a different set of external files'
                         : externalsDiffer ? 'a different set of external files'
                         : 'a different track layout'
                if (affected) {
                    blocking << "${plural(group.size(), 'file')} ${verb} ${what}, at " +
                                "selected ${pluralize(affected.size(), 'track')} ${affected.join(', ')}"
                } else if (internalDiffers) {
                    informational << "${plural(group.size(), 'file')} ${verb} ${what} (selected tracks unaffected)"
                } else {
                    // Never blocking: nothing selects an external file by ID, so
                    // this cannot mux the wrong track. It is a separate muxing
                    // pass, which is the whole point of saying it.
                    informational << "${plural(group.size(), 'file')} ${verb} ${what}, so they need their own pass"
                }
            } else {
                trackGroups.findAll { !it.consistent }.each { tg ->
                    def title = configTitleFor(tg.id)
                    def label = "track ${tg.id} (${tg.type}${title ? ", config title \"${title}\"" : ''}) - " +
                                "${tg.varying.join(', ')} differ${tg.varying.size() == 1 ? 's' : ''} across ${tg.groups.size()} groups"
                    if (isBlocking(tg.id as Integer, tg.type, largest)) blocking << label
                    else informational << label
                }
                // External values never select anything, so a split in one is
                // always informational — but it is worth saying, because a dub
                // that is tagged Russian for half a season and untagged for the
                // rest is the same class of surprise as an internal one.
                externalGroups.findAll { !it.consistent }.each { tg ->
                    informational << "external ${tg.label} ${tg.groups[0].sig.slot} " +
                                     "(${tg.type}) - ${tg.varying.join(', ')} " +
                                     "differ${tg.varying.size() == 1 ? 's' : ''} across ${tg.groups.size()} groups"
                }
            }
        }

        // Ambiguous duplicates and chapters are observations across the whole
        // batch, reported once regardless of layout.
        def duplicates = findDuplicates(ok)
        if (duplicates) {
            ui.header("*** Ambiguous track IDs")
            duplicates.each { dup ->
                def name = dup.name ? "\"${dup.name}\"" : 'no name'
                println "    Tracks ${dup.ids.join(' and ')} are both ${dup.type} / ${dup.language} / " +
                        "${dup.codec} with ${name}, in ${plural(dup.files.size(), 'file')}."
                println "    ID-based selection cannot distinguish them; check which one config.yaml means."
                def selected = dup.ids.findAll { isBlocking(it as Integer, dup.type, ok) }
                def label = "tracks ${dup.ids.join(', ')} are ambiguous" +
                            (selected ? " and config.yaml selects ${pluralize(selected.size(), 'track')} ${selected.join(', ')}" : '')
                if (selected) blocking << label else informational << label
            }
            println()
        }

        def withChapters = ok.findAll { it.chapters > 0 }
        def withoutChapters = ok.findAll { it.chapters == 0 }
        if (withChapters && withoutChapters) {
            ui.header("*** Chapters: present in ${plural(withChapters.size(), 'file')}, " +
                      "absent in ${withoutChapters.size()}")
            def minority = withChapters.size() < withoutChapters.size() ? withChapters : withoutChapters
            printMinority(minority.collect { it.file.name }, limit)
            informational << "chapters are present in some files and not others"
            println()
        }

        // Without a config there is nothing to classify against, so report the
        // count of differences (already detailed in the tables above) and point at
        // how to classify them. The per-item labels assume selected tracks, so
        // they are only printed when a config is present.
        if (!hasConfig) {
            def findings = blocking + informational
            if (findings) {
                println ui.yellow("*** ${plural(findings.size(), 'difference')} across the batch " +
                                  "(see the tables above).")
                println "***   Add a config.yaml, or --config <path>, to classify which affect selected tracks."
            } else {
                ui.success("*** ${hasExternals(ok) ? 'Track structure and external files are' : 'Track structure is'} consistent across ${plural(ok.size(), 'file')}.")
            }
        } else {
            if (blocking) {
                println ui.yellow("*** ${plural(blocking.size(), 'discrepancy', 'discrepancies')} " +
                                  "${blocking.size() == 1 ? 'affects a track' : 'affect tracks'} " +
                                  "that config.yaml selects:")
                blocking.each { println "      ${it}" }
            }
            if (informational) {
                println "*** ${informational.size()} informational (does not affect what gets muxed):"
                informational.each { println "      ${it}" }
            }
            if (!blocking && !informational) {
                ui.success("*** ${hasExternals(ok) ? 'Track structure and external files are' : 'Track structure is'} consistent across ${plural(ok.size(), 'file')}.")
            }
        }
        println()

        blocking.size()
    }

    [formatFileList     : formatFileList,
     SIG_KEYS           : SIG_KEYS,
     trackSignature     : trackSignature,
     probeFile          : probeFile,
     groupTracks        : groupTracks,
     findDuplicates     : findDuplicates,
     makeSelection      : makeSelection,
     shortType          : shortType,
     layoutKey          : layoutKey,
     fitName            : fitName,
     cell               : cell,
     printMinority      : printMinority,
     runConsistencyCheck: runConsistencyCheck]
}
