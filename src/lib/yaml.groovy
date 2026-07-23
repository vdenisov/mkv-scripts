// Loading a YAML file that is supposed to be a mapping — config.yaml and
// episodes.yaml, in both mux.groovy and inspect.groovy.
//
// Loaded by explicit path from the calling script's own location, never through
// Groovy's implicit sibling-class resolution, which is CWD-dependent (CLAUDE.md).
//
// No @Grab and no imports, so snakeyaml cannot be imported here: the parser is
// injected, the same seam as check.groovy's parseJson and episodes.groovy's
// normalizeYaml. The caller owns the I/O dependency, this file owns the
// semantics.
//
// **This classifies, it does not decide.** All four call sites end up in exactly
// one of four states — unparseable, empty, parsed-but-not-a-mapping, or a usable
// Map — and every one of them used to spell that out for itself, four times, with
// four chances to drift. What they do *about* a problem is genuinely different
// and stays with them: mux.groovy exits 2 because it cannot mux against a config
// it did not understand, inspect.groovy warns and reports on the files anyway,
// and only inspect.groovy's config.yaml counts toward --strict. So `problem` is a
// bare, uncapitalised fragment that the caller finishes in its own words and
// prints through its own ui.error/ui.warn.
//
// The last expression of this file is a factory closure; call it with the parse
// closure to get the map of helpers.
{ Closure parseYaml ->

    // Returns [value: Map, problem: null] or [value: null, problem: String].
    //
    // opts.charset  read the file with this charset instead of Groovy's
    //               auto-detection. episodes.yaml passes 'UTF-8' because it is
    //               machine-written and that is a fixed contract; config.yaml
    //               passes nothing and keeps auto-detection. Do not unify these.
    // opts.transform  applied to the mapping INSIDE the guard. episodes.yaml
    //               passes normalizeYaml, which throws on its own account for
    //               `episode: "one"` — at normalize time, not at parse time, so
    //               running it outside would put the stack trace back.
    def loadMapping = { File file, Map opts = [:] ->
        try {
            def loaded = parseYaml(opts.charset ? file.getText(opts.charset.toString()) : file.text)
            if (loaded == null) {
                return [value: null, problem: "${file.name} is empty".toString()]
            }
            if (!(loaded instanceof Map)) {
                return [value: null,
                        problem: "${file.name} is not a mapping (found ${loaded.getClass().simpleName})".toString()]
            }
            [value: opts.transform ? opts.transform(loaded) : loaded, problem: null]
        } catch (Exception e) {
            // First non-blank line only: snakeyaml's messages carry a multi-line
            // context dump that turns one warning into a screenful.
            [value  : null,
             problem: "could not parse ${file.name}: ${e.message?.readLines()?.find { it } ?: e}".toString()]
        }
    }

    [loadMapping: loadMapping]
}
