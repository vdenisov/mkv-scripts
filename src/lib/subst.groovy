// The substitution-variable engine: the file- and track-scope variables that
// templated config values expand, and the two-stage validation around them.
// Shared by mux.groovy (titles, companion paths) and inspect.groovy (resolving
// configured sources for --identify).
//
// Loaded by explicit path from the calling script's own location, never through
// Groovy's implicit sibling-class resolution, which is CWD-dependent (CLAUDE.md).
//
// No @Grab and no imports. Episode metadata is passed in already parsed — the
// same seam as episodes.groovy's normalizeYaml: snakeyaml stays in the scripts,
// the semantics live here. File name splitting is done with plain string ops
// rather than commons-io for the same reason.
//
// The last expression of this file is a factory closure; call it with the
// dependency map to get the map of helpers.
{ Map deps ->

    def ui = deps.ui
    def episodesHelper = deps.episodes
    def episodeData = deps.episodeData

    def baseNameOf = { String name ->
        def i = name.lastIndexOf('.')
        i < 0 ? name : name.substring(0, i)
    }
    def extensionOf = { String name ->
        def i = name.lastIndexOf('.')
        i < 0 ? '' : name.substring(i + 1)
    }

    // Config values are templates: "${languageName} ${codec}" rather than a
    // literal "Russian SRT". Variables come in two scopes — file-scope ones
    // describe the episode being muxed and are valid everywhere, track-scope ones
    // describe the track a title belongs to and are only valid in a title.
    // Unknown names are a config error caught up front (validateTemplates below),
    // never a literal passed through to mkvmerge.
    def FILE_VARS = ['fileName', 'extension', 'showName', 'seasonNum',
                     'episodeNum', 'episodeName', 'seasonName', 'showYear'] as Set
    def TRACK_VARS = ['language', 'languageName', 'languageNative', 'codec'] as Set

    // Language display names come from the JDK's CLDR data, so no table of our
    // own has to be maintained. Both the two-letter codes config.yaml uses and the
    // three-letter codes Matroska carries resolve to the same locale.
    def localeByCode = [:]
    Locale.getISOLanguages().each { code ->
        def loc = new Locale(code)
        localeByCode[code] = loc
        try {
            localeByCode[loc.getISO3Language()] = loc
        } catch (ignored) {
            // A handful of codes have no three-letter form; the two-letter one stands.
        }
    }
    // ISO 639-2/B ("bibliographic") codes, which differ from the /T codes the JDK
    // returns. Matroska files in the wild carry these, so they have to resolve too.
    [ger: 'de', fre: 'fr', dut: 'nl', chi: 'zh', cze: 'cs', gre: 'el', per: 'fa',
     rum: 'ro', slo: 'sk', alb: 'sq', arm: 'hy', baq: 'eu', bur: 'my', geo: 'ka',
     ice: 'is', mac: 'mk', mao: 'mi', may: 'ms', tib: 'bo', wel: 'cy'].each { bib, code ->
        localeByCode[bib] = new Locale(code)
    }

    def localeFor = { String code -> code ? localeByCode[code.toLowerCase()] : null }

    def languageNameOf = { String code ->
        def loc = localeFor(code)
        if (loc == null) return null
        def name = loc.getDisplayLanguage(Locale.ENGLISH)
        // The JDK echoes the code back when it has no display name for it
        (!name || name.equalsIgnoreCase(code)) ? null : name
    }

    def languageNativeOf = { String code ->
        def loc = localeFor(code)
        if (loc == null) return null
        def name = loc.getDisplayLanguage(loc)
        if (!name || name.equalsIgnoreCase(code)) return null
        // Many languages spell their own name in lower case ("русский"), which
        // reads wrong in a track title. Upper-case in that language's own rules.
        name.substring(0, 1).toUpperCase(loc) + name.substring(1)
    }

    // Keyed on codec_id, a Matroska specification identifier, rather than on
    // mkvmerge's `codec` display string: the display string is not stable across
    // mkvmerge versions (v99 reports "AVC/H.264/MPEG-4p10" where older releases
    // report the components in the opposite order), and CI runs both a distro
    // mkvtoolnix and the newest release.
    def CODEC_BY_ID = [
        'V_MPEG4/ISO/AVC' : 'H.264', 'V_MPEGH/ISO/HEVC': 'H.265', 'V_AV1' : 'AV1',
        'V_MPEG2'         : 'MPEG-2', 'V_VP9'          : 'VP9',
        'A_AAC'           : 'AAC',   'A_AC3'           : 'AC-3',  'A_EAC3': 'E-AC-3',
        'A_DTS'           : 'DTS',   'A_TRUEHD'        : 'TrueHD', 'A_FLAC': 'FLAC',
        'A_OPUS'          : 'Opus',  'A_MPEG/L3'       : 'MP3',   'A_VORBIS': 'Vorbis',
        'A_PCM/INT/LIT'   : 'PCM',
        'S_TEXT/UTF8'     : 'SRT',   'S_TEXT/ASS'      : 'ASS',   'S_TEXT/SSA': 'SSA',
        'S_TEXT/WEBVTT'   : 'WebVTT', 'S_HDMV/PGS'     : 'PGS',   'S_VOBSUB': 'VobSub',
    ]
    // Raw (non-Matroska) companion files carry no codec_id at all — a bare .ass
    // probes with an empty codec_id and only a display string — so that is the
    // second tier. Anything unmapped falls through to mkvmerge's own name, which
    // is already readable.
    def CODEC_BY_DISPLAY = [
        'SubStationAlpha': 'ASS', 'SubRip/SRT': 'SRT', 'HDMV PGS': 'PGS', 'VobSub': 'VobSub',
    ]

    def friendlyCodec = { track ->
        if (track == null) return null
        def codecId = track.get('properties')?.get('codec_id')
        def mapped = codecId ? CODEC_BY_ID[codecId.toString()] : null
        if (mapped) return mapped
        def display = track.codec?.toString()
        display ? (CODEC_BY_DISPLAY[display] ?: display) : null
    }

    // File-scope variables for one episode, memoized. Sources are tried in order:
    // episodes.yaml (or episodes.txt), then the canonical "Show - SxxEyy - Title"
    // file name. Anything still unresolved is reported per file by the caller's
    // stage-two pre-flight, so a season with one episode missing from the metadata
    // loses that one episode rather than the whole batch.
    def fileVarCache = [:]
    def fileVarsFor = { File file ->
        def cached = fileVarCache[file]
        if (cached != null) return cached

        def base = baseNameOf(file.name)
        def parsed = episodesHelper.parseSeasonEpisode(base)
        def canonical = episodesHelper.parseCanonicalName(base)

        // Episode numbers are only meaningful within one season, so metadata for a
        // different season must not be joined against this file.
        def seasonMatches = !(episodeData?.season != null && parsed?.season != null &&
                              episodeData.season != parsed.season)
        def usable = episodeData != null && seasonMatches
        def fromData = (usable && parsed?.episode) ? episodeData.byEpisode[parsed.episode] : null

        def vars = [
            fileName   : base,
            extension  : extensionOf(file.name),
            seasonNum  : parsed?.season ?: (usable ? episodeData.season : null),
            episodeNum : parsed?.episode,
            episodeName: fromData ?: canonical?.title,
            showName   : (usable ? episodeData.show : null) ?: canonical?.showName,
            seasonName : usable ? episodeData.seasonName : null,
            showYear   : usable ? episodeData.year : null,
        ]

        def result = [vars: vars, missing: vars.findAll { k, v -> !v }.keySet()]
        fileVarCache[file] = result
        result
    }

    // Track-scope variables. The probed track is supplied by the caller, which
    // owns the probe caches; it is null whenever ${codec} is not in use, so a run
    // without it costs no extra subprocesses.
    def trackVarsFor = { track, probed ->
        def code = track?.language?.toString()
        [language      : code,
         languageName  : languageNameOf(code),
         languageNative: languageNativeOf(code),
         codec         : friendlyCodec(probed)]
    }

    def VAR_PATTERN = /\$\{([A-Za-z][A-Za-z0-9]*)\}/
    // Deliberately looser than VAR_PATTERN, so that a malformed body — ${file name},
    // ${var:modifier} — is caught by validation instead of silently surviving as a
    // literal because it failed to look like a variable.
    def LOOSE_PATTERN = /\$\{[^}]*\}/

    def substitute = { String template, Map vars ->
        template.replaceAll(VAR_PATTERN) { full, name -> vars[name] ?: '' }
    }

    // Every templated config value, with the variable scope legal in it. Built
    // once so that validation, the pre-flight and the command line all agree on
    // exactly which fields are templates.
    def collectTemplateFields = { Map config ->
        def templateFields = []
        if (config == null) return templateFields

        def all = FILE_VARS + TRACK_VARS
        if (config.general?.containsKey('title')) {
            templateFields << [path: 'general.title', value: config.general.title, allowed: FILE_VARS]
        }
        def videoTrack = config.mainSource?.videoTrack
        if (videoTrack?.containsKey('title')) {
            templateFields << [path: 'mainSource.videoTrack.title', value: videoTrack.title,
                               allowed: all, track: videoTrack]
        }
        ['audioTracks', 'subtitleTracks'].each { key ->
            (config.mainSource?.get(key) ?: []).eachWithIndex { track, i ->
                if (track?.containsKey('title')) {
                    templateFields << [path: "mainSource.${key}[${i}].title", value: track.title,
                                       allowed: all, track: track]
                }
            }
        }
        (config.additionalSources ?: []).eachWithIndex { source, i ->
            if (source?.file) {
                templateFields << [path: "additionalSources[${i}].file", value: source.file, allowed: FILE_VARS]
            }
            (source?.tracks ?: []).eachWithIndex { track, j ->
                if (track?.containsKey('title')) {
                    templateFields << [path: "additionalSources[${i}].tracks[${j}].title", value: track.title,
                                       allowed: all, track: track]
                }
            }
        }
        templateFields
    }

    // Validation, stage one: a name that is not a variable, or not a variable
    // legal in this field, is a config error — fatal, before anything is probed or
    // muxed, in every mode. A typo'd ${epsiodeName} would otherwise be stamped
    // verbatim into the track names of an entire season.
    //
    // Returns which variables the config actually uses, so everything derived from
    // them can be gated and a config with no templates costs nothing at all.
    def validateTemplates = { List templateFields, Map opts = [:] ->
        def usedFileVars = [] as Set
        def usesCodec = false
        def offenses = []
        def badLanguages = []

        templateFields.each { field ->
            def text = field.value?.toString() ?: ''
            def usesLanguageName = false

            (text =~ LOOSE_PATTERN).each { occurrence ->
                def matcher = occurrence =~ /^\$\{([A-Za-z][A-Za-z0-9]*)\}$/
                def name = matcher ? matcher.group(1) : null
                if (name == null || !field.allowed.contains(name)) {
                    offenses << [path: field.path, token: occurrence, allowed: field.allowed]
                    return
                }
                if (FILE_VARS.contains(name)) usedFileVars << name
                if (name == 'codec') usesCodec = true
                if (name == 'languageName' || name == 'languageNative') usesLanguageName = true
            }

            // A language code that has no display name is equally config-static,
            // so it belongs in the same fail-fast pass rather than surfacing per
            // file.
            if (usesLanguageName) {
                def code = field.track?.language?.toString()
                if (languageNameOf(code) == null || languageNativeOf(code) == null) {
                    badLanguages << [path: field.path, code: code]
                }
            }
        }

        def problems = offenses.size() + badLanguages.size()
        if (problems) {
            // Diagnosed here so the wording is identical everywhere, but never
            // fatal here: whether a config problem should stop the run is the
            // caller's question. It stops mux.groovy, which cannot mux without a
            // config it can trust, and does not stop inspect.groovy, which
            // reports on files and can report on them just as well without one.
            def message = "config.yaml has ${ui.plural(problems, 'substitution problem')}:"
            if (opts.fatal == false) ui.warn(message) else ui.error(message)
            offenses.each {
                System.err.println "  ${it.path}: ${it.token}"
                System.err.println "      valid here: ${it.allowed.sort().join(', ')}"
            }
            badLanguages.each {
                System.err.println "  ${it.path}: no language name for '${it.code ?: '(none)'}'"
            }
        }

        [usedFileVars: usedFileVars, usesCodec: usesCodec, problems: problems]
    }

    [FILE_VARS           : FILE_VARS,
     TRACK_VARS          : TRACK_VARS,
     languageNameOf      : languageNameOf,
     languageNativeOf    : languageNativeOf,
     friendlyCodec       : friendlyCodec,
     substitute          : substitute,
     fileVarsFor         : fileVarsFor,
     trackVarsFor        : trackVarsFor,
     collectTemplateFields: collectTemplateFields,
     validateTemplates   : validateTemplates]
}
