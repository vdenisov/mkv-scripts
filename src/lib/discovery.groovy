// External-file discovery: finding the audio and subtitle files that belong to a
// main media file but do not sit next to it.
//
// Two layouts are in the wild and both are supported, including combined:
//
//   directory  Rus sound/[Омикрон]/<same base name>.mka   — the variant is the
//              directory; the file name matches the main file exactly.
//   suffix     <main base name>.rus.srt                   — the variant is the
//              trailing text; separators are whatever the release used.
//
// Shared by inspect.groovy (reporting) and rename.groovy (renaming externals
// along with their main file), so the matching rules cannot drift between what
// is reported and what is renamed.
//
// No @Grab and no imports: only java.io.File, regex and collections, all of which
// Groovy imports by default. The episodes helper is injected because
// parseSeasonEpisode is the one season/episode regex every script must agree on.
//
// The last expression of this file is a factory closure; call it with the
// episodes helper map to get the map of helpers.
{ Map episodes ->

    def baseNameOf = { String name ->
        def i = name.lastIndexOf('.')
        i < 0 ? name : name.substring(0, i)
    }
    def extensionOf = { String name ->
        def i = name.lastIndexOf('.')
        i < 0 ? '' : name.substring(i + 1)
    }

    // What can be muxed in as an external track. Deliberately narrower than "any
    // file": a directory full of release notes, screenshots and NFO files must not
    // turn into a wall of unmatched entries.
    def COMPANION_EXTENSIONS = ['mka', 'mks', 'mp3', 'aac', 'ac3', 'dts', 'flac',
                                'ass', 'ssa', 'srt', 'sup', 'idx', 'sub', 'vtt'] as Set

    // The extensions worth one `mkvmerge -J` each, established by probing real
    // files of every format in this list against mkvmerge v99:
    //
    //   mka/mks/mkv  language, track name, codec_id  (Matroska carries it all)
    //   idx          language ("id: ru" in the index text)
    //   flac         language and title from the Vorbis comment block
    //
    // Everything else surfaces nothing a name does not already tell us: .srt and
    // .sup and .vtt have no metadata at all, .ass/.ssa carry a Script Info title
    // that mkvmerge does not expose, and .mp3's ID3 TLAN frame is ignored. A lone
    // .sub is worse than useless — without its .idx it identifies as a zero-track
    // "MPEG program stream" — so the .idx is always the one probed of the pair.
    def PROBE_EXTENSIONS = ['mka', 'mks', 'mkv', 'idx', 'flac'] as Set

    def AUDIO_EXTENSIONS = ['mka', 'mp3', 'aac', 'ac3', 'dts', 'flac'] as Set
    def typeClassOf = { String ext -> AUDIO_EXTENSIONS.contains(ext) ? 'audio' : 'subtitles' }

    // Displayed for files that are never probed, so the codec column says
    // something useful without a subprocess per file.
    def CODEC_BY_EXTENSION = [
        mka : 'Matroska', mks: 'Matroska', mp3: 'MP3',    aac: 'AAC',    ac3: 'AC-3',
        dts : 'DTS',      flac: 'FLAC',    ass: 'ASS',    ssa: 'SSA',    srt: 'SRT',
        sup : 'PGS',      idx: 'VobSub',   sub: 'VobSub', vtt: 'WebVTT',
    ]

    // The languages worth guessing at from a folder name. Curated rather than
    // "every language the JDK knows", because obscure two- and three-letter codes
    // collide with ordinary English words — 'new' is Newari, 'sun' is Sundanese,
    // 'no' is Norwegian — and "New subs" is not a Newari release.
    def LANGUAGE_CODES = ['ru', 'en', 'ja', 'uk', 'de', 'fr', 'es', 'it', 'pt', 'pl', 'zh', 'ko',
                          'cs', 'sk', 'hu', 'ro', 'bg', 'sr', 'hr', 'sl', 'lt', 'lv', 'et', 'fi',
                          'sv', 'da', 'no', 'nl', 'el', 'tr', 'ar', 'he', 'fa', 'hi', 'th', 'vi']

    // Two-letter codes from the list above that are also ordinary words: a folder
    // called "No subs" must not read as Norwegian, and "UK BluRay" must not read
    // as Ukrainian. Only the bare two-letter code is withheld — every one of
    // these is still matched by its three-letter code and by its name, in English
    // or its own, so the cost of an entry here is close to nothing while the cost
    // of a false positive is a wrong language in a report used to write a config.
    //
    // Scoped to LANGUAGE_CODES on purpose: this set is consulted only inside the
    // loop below, so an entry for a code that is not guessed at is dead weight
    // that reads as protection it does not provide. Add both together.
    def AMBIGUOUS_CODES = [
        'no',   // "No subs"
        'it',   // English "it"
        'he',   // English "he"
        'hi',   // English "hi"
        'uk',   // "UK BluRay", the region
        'el',   // the Spanish article
        'et',   // the French conjunction
        'da',   // the Italian/Portuguese preposition
    ] as Set

    // ISO 639-2/B ("bibliographic") codes, which differ from the /T codes the JDK
    // returns. Matroska files in the wild carry these, and so do folder names.
    // Keyed by LANGUAGE_CODES entries only, and only where /B actually differs
    // from what getISO3Language returns — Serbian's are both 'srp'.
    def BIBLIOGRAPHIC = [de: 'ger', fr: 'fre', nl: 'dut', zh: 'chi', cs: 'cze', el: 'gre',
                         fa: 'per', ro: 'rum', sk: 'slo']

    // Every spelling of each language, built from the JDK's CLDR data rather than
    // typed out: the two- and three-letter codes, the English name and the
    // language's own name for itself. That is what makes "Русский", "Español" and
    // "日本語" work as folder names, and it stays correct as CLDR is updated.
    def LANGUAGE_BY_TOKEN = [:]
    LANGUAGE_CODES.each { code ->
        def loc = new Locale(code)
        String iso3
        try {
            iso3 = loc.getISO3Language()
        } catch (ignored) {
            return
        }
        // Matroska carries the bibliographic code where one exists, so that is
        // what the report should say the language is.
        def canonical = BIBLIOGRAPHIC[code] ?: iso3
        def register = { String token ->
            if (token) LANGUAGE_BY_TOKEN[token.toLowerCase(Locale.ROOT)] = canonical
        }
        if (!AMBIGUOUS_CODES.contains(code)) register(code)
        register(iso3)
        register(BIBLIOGRAPHIC[code])
        register(loc.getDisplayLanguage(Locale.ENGLISH))
        register(loc.getDisplayLanguage(loc))
    }
    // Not an ISO code, but the abbreviation anime releases actually use.
    LANGUAGE_BY_TOKEN['jap'] = 'jpn'

    // Words, not substrings: "Ru subs" and "Rus.subs" are Russian, "Rusubs" is
    // not. Without the whole-word rule a short code would fire on any release
    // group or show title that happened to contain the letters. Splitting on
    // non-letters keeps Cyrillic and CJK names intact, which is the point of
    // carrying native names at all.
    def wordsOf = { String text ->
        text.toLowerCase(Locale.ROOT).split(/[^\p{L}\p{N}]+/).findAll { it } as List
    }

    // Texts are tried in order, so the caller decides precedence: the suffix or
    // the file's own directory describes it better than a category directory
    // three levels up.
    def guessLanguage = { List texts ->
        for (def text : texts) {
            if (!text) continue
            def words = wordsOf(text.toString())
            for (String word : words) {
                def hit = LANGUAGE_BY_TOKEN[word]
                if (hit) return hit
            }
            // Names of more than one word ("norsk bokmål") are matched as a run,
            // after every single word has been tried.
            for (int n = 2; n <= 3; n++) {
                for (int i = 0; i + n <= words.size(); i++) {
                    def hit = LANGUAGE_BY_TOKEN[words[i..<(i + n)].join(' ')]
                    if (hit) return hit
                }
            }
        }
        null
    }

    // Suffixes are matched verbatim — a release may separate them with anything,
    // including '[', '(', '{', '!' or a bare space — but reading "[Омикрон]" is
    // easier than reading ".[Омикрон]". Trimming is for display only; identity and
    // renaming always use the raw suffix.
    def trimForDisplay = { String suffix ->
        if (suffix == null) return null
        suffix.replaceAll(/^[^\p{L}\p{N}]+/, '').replaceAll(/[^\p{L}\p{N}]+$/, '')
    }

    // One recursive walk, reused by every mode. Directories starting with '.' are
    // skipped, as is anything the caller excludes (the output directory above all
    // — muxed results carry the same base names as their sources and would come
    // back as externals of themselves). Canonical paths are remembered so a
    // symlink or junction loop on a network share cannot spin forever.
    def walkTree = { File root, Set excluded ->
        def out = []
        def visited = [] as Set

        def recurse
        recurse = { File dir, String prefix ->
            def canonical
            try {
                canonical = dir.canonicalPath
            } catch (ignored) {
                return
            }
            if (!visited.add(canonical)) return

            def kids = dir.listFiles()
            if (kids == null) return
            kids.toList().sort { it.name }.each { File f ->
                def rel = prefix ? "${prefix}/${f.name}".toString() : f.name
                if (f.isDirectory()) {
                    if (f.name.startsWith('.')) return
                    def dirCanonical
                    try {
                        dirCanonical = f.canonicalPath
                    } catch (ignored) {
                        return
                    }
                    if (excluded.contains(dirCanonical)) return
                    recurse(f, rel)
                } else {
                    out << [file  : f,
                            relPath: rel,
                            dirRel : prefix,
                            leaf   : prefix ? prefix.split('/')[-1] : null,
                            base   : baseNameOf(f.name),
                            ext    : extensionOf(f.name).toLowerCase()]
                }
            }
        }

        recurse(root, '')
        out
    }

    // Match every candidate to a main file, then group the matches into variants.
    //
    // Tier 1 is the name relation: the candidate's base name starts with a main
    // file's base name, and whatever trails it is the suffix (empty for the
    // directory layout). The LONGEST such main wins, which is what stops
    // "Show - S01E01 - Title 2.srt" from being read as main "…Title" plus suffix
    // " 2" when "…Title 2.mkv" also exists.
    //
    // Tier 2 is the episode number: no name relation, but both sides carry the
    // same SxxEyy and exactly one main file claims it. Lower confidence by
    // construction — it is reported as such and never drives a rename.
    def discoverCompanions = { List mainFiles, List treeEntries, Map opts = [:] ->
        def mainExtensions = (opts.mainExtensions ?: []) as Set
        def mainPaths = mainFiles.collect { it.absolutePath } as Set

        // Longest first, so the first match found is the longest match.
        def mains = mainFiles
            .collect { [file: it, base: baseNameOf(it.name), lower: baseNameOf(it.name).toLowerCase()] }
            .sort { a, b -> (b.base.length() <=> a.base.length()) ?: (a.base <=> b.base) }

        def episodeIndex = [:]   // "season/episode" -> main entry, or null when ambiguous
        mains.each { m ->
            def parsed = episodes.parseSeasonEpisode(m.base)
            if (parsed == null) return
            def key = "${parsed.season}/${parsed.episode}".toString()
            episodeIndex[key] = episodeIndex.containsKey(key) ? null : (m + [season: parsed.season, episode: parsed.episode])
        }

        def matched = []
        def unmatched = []
        def extras = []

        treeEntries.each { entry ->
            if (mainPaths.contains(entry.file.absolutePath)) return

            if (!COMPANION_EXTENSIONS.contains(entry.ext)) {
                // Main-type files below the top level are extras — BD menus,
                // trailers, creditless openings. Reported so they are not a
                // surprise, never treated as sources and never probed.
                if (entry.dirRel && mainExtensions.contains(entry.ext)) extras << entry
                return
            }

            def lower = entry.base.toLowerCase()
            // The empty base name guard is not hypothetical pedantry: a file
            // called ".mkv" has one, and every string starts with the empty
            // string, so without it that file would silently adopt every
            // otherwise-unmatched companion in the tree.
            def hit = mains.find { it.lower && lower.startsWith(it.lower) }
            if (hit) {
                matched << (entry + [main: hit.file, tier: 1, suffix: entry.base.substring(hit.base.length())])
                return
            }

            def parsed = episodes.parseSeasonEpisode(entry.base)
            def byEpisode = parsed ? episodeIndex["${parsed.season}/${parsed.episode}".toString()] : null
            if (byEpisode) {
                matched << (entry + [main: byEpisode.file, tier: 2, suffix: null])
            } else {
                unmatched << entry
            }
        }

        // Variant identity is (leaf directory, raw suffix): same-named leaves under
        // different category parents ("Rus sound/[MC-Ent]" and "Rus subs/[MC-Ent]")
        // are one dub group with two kinds of file, and merging them is what makes
        // the report read the way the directory was meant to.
        // An episode-number match has no suffix to key on, so on its own it would
        // form a twin of the directory's real variant. When the directory has
        // exactly one name-matched variant, the odd file belongs to it — that is
        // the whole point of the looser tier: one file in the set was named
        // differently from its siblings.
        def suffixesByLeaf = matched.findAll { it.tier == 1 }
                                    .groupBy { it.leaf }
                                    .collectEntries { leaf, items -> [leaf, items.collect { it.suffix }.unique()] }

        def groups = new LinkedHashMap()
        matched.each { m ->
            def suffix = m.suffix
            if (suffix == null) {
                def candidates = suffixesByLeaf[m.leaf]
                if (candidates?.size() == 1) suffix = candidates[0]
            }
            def key = [m.leaf, suffix]
            if (!groups.containsKey(key)) groups.put(key, [])
            groups.get(key) << m
        }

        // ...except when the merge is ambiguous: if one episode ends up with two
        // files of the same kind from different directories, those directories are
        // not the same variant after all. Split them back apart and show paths.
        def variants = []
        groups.each { key, items ->
            def collides = items.groupBy { [it.main.absolutePath, typeClassOf(it.ext)] }
                                .any { k, v -> v.collect { it.dirRel }.unique().size() > 1 }
            if (collides) {
                items.groupBy { it.dirRel }.each { dirRel, subset ->
                    variants << [key: key, entries: subset, collision: true]
                }
            } else {
                variants << [key: key, entries: items, collision: false]
            }
        }

        // Deterministic order, then short labels. Directory variants sort before
        // bare-suffix ones simply because '' sorts first among relative paths.
        variants.sort { a, b ->
            (a.entries[0].dirRel <=> b.entries[0].dirRel) ?:
            ((a.entries[0].suffix ?: '') <=> (b.entries[0].suffix ?: '')) ?:
            (a.entries[0].relPath <=> b.entries[0].relPath)
        }

        // A, B, ... Z, AA, AB — short enough to prefix every line without
        // crowding out the file name it labels.
        def labelFor = { int index ->
            def s = ''
            int n = index
            while (n >= 0) {
                s = String.valueOf((char) (65 + (n % 26))) + s
                n = ((int) (n / 26)) - 1
            }
            s
        }

        variants.eachWithIndex { v, i ->
            def first = v.entries[0]
            def dirs = v.entries.collect { it.dirRel }.findAll { it }.unique().sort()
            def types = v.entries.collect { typeClassOf(it.ext) }.unique().sort()
            def exts = v.entries.collect { it.ext }.unique().sort()
            def rawSuffix = first.suffix
            def trimmed = trimForDisplay(rawSuffix)

            def name
            if (v.collision) {
                name = first.dirRel
            } else if (first.leaf) {
                name = trimmed ? "${first.leaf} ${trimmed}".toString() : first.leaf
            } else if (trimmed) {
                name = trimmed
            } else {
                name = '(same name)'
            }

            // A merged variant holds audio in one directory and subtitles in
            // another, so one path pattern cannot describe it. Everything that is
            // displayed per kind of file — the legend and the coverage report —
            // reads these sections rather than the variant as a whole.
            def sections = v.entries.groupBy { typeClassOf(it.ext) }.collect { typeClass, items ->
                def sectionExts = items.collect { it.ext }.unique().sort()
                def sectionDir = items.collect { it.dirRel }.findAll { it }.unique().sort()[0]
                def prefix = sectionDir ? "${sectionDir}/" : ''
                def suffix = items.find { it.suffix != null }?.suffix
                [typeClass : typeClass,
                 dirs      : items.collect { it.dirRel }.findAll { it }.unique().sort(),
                 extensions: sectionExts,
                 entries   : items.sort { it.relPath },
                 pattern   : (suffix == null)
                     ? "${prefix}<episode number>.${sectionExts.join('/')}".toString()
                     : "${prefix}<name>${suffix}.${sectionExts.join('/')}".toString()]
            }.sort { it.typeClass }

            // Suffix and own directory describe a file better than a category
            // directory further up, so they are tried first.
            def langTokens = [rawSuffix] + (first.dirRel ? first.dirRel.split('/').reverse().toList() : [])

            v.label = labelFor(i)
            v.name = name
            v.dirs = dirs
            v.typeClass = types.size() > 1 ? 'mixed' : (types[0] ?: 'subtitles')
            v.extensions = exts
            v.sections = sections
            v.langGuess = guessLanguage(langTokens)
            v.entries = v.entries.sort { it.relPath }
        }

        [variants : variants,
         unmatched: unmatched.sort { it.relPath },
         extras   : extras.sort { it.relPath }]
    }

    [COMPANION_EXTENSIONS: COMPANION_EXTENSIONS,
     PROBE_EXTENSIONS    : PROBE_EXTENSIONS,
     CODEC_BY_EXTENSION  : CODEC_BY_EXTENSION,
     typeClassOf         : typeClassOf,
     guessLanguage       : guessLanguage,
     trimForDisplay      : trimForDisplay,
     walkTree            : walkTree,
     discoverCompanions  : discoverCompanions]
}
