// output.groovy — shared console-output helpers for every script in src/.
//
// Loaded at runtime with evaluate(new File(scriptDir, 'lib/output.groovy')), where
// scriptDir is resolved from the calling script's own location — never from the
// CWD, since the scripts run from arbitrary media directories. The file is
// deliberately never referenced as a class: Groovy's implicit sibling-class
// resolution depends on how and from where a script is launched, which is
// exactly what makes shared helpers break in some environments. An explicit
// evaluate() by absolute path has no such dependence.
//
// No @Grab and no imports, so loading this file never touches the network or
// the caller's classloader setup (mux.groovy grabs with systemClassLoader).
//
// The last expression is a factory closure: call it with 'auto', 'always' or
// 'never' and get back a map of output helpers. Any other value behaves as
// 'never', matching what mux.groovy's --color did before this file existed.
//
// Palette — one meaning per colour, the same in every script:
//   red    (31)  errors, failure summaries, [FAIL]
//   green  (32)  terminal success: "*** Done", clean summaries, [PASS]
//   yellow (33)  warnings, and the differing-cell highlight in --check tables
//   cyan   (36)  section/file headers and table column headers, for navigation
//   gray   (90)  de-emphasis of secondary detail (the --check file-evidence
//                lists), so the primary rows stand out; never used as an accent
//
// Colour wraps a WHOLE line or a WHOLE pre-padded table cell — never a fragment
// inside a phrase. Mid-phrase escapes read as flicker, and the test suite's
// substring assertions must keep matching the uncoloured text between them.
// Table cells are padded to width *before* colouring, so the escapes never
// count toward a column width and break alignment.

{ String colorMode ->
    def esc = (char) 27
    def reset = "${esc}[0m"

    // JDK 22+ returns a Console object even when output is piped, and added
    // Console.isTerminal() to tell the difference. Call it only where it
    // exists, so JDK 11/21 keep the plain null-check semantics: the console is
    // null there whenever input or output is redirected — including under the
    // test harness, which is why auto never pollutes a test assertion.
    def onTerminal = {
        def console = System.console()
        console != null && (!console.respondsTo('isTerminal') || console.isTerminal())
    }

    // NO_COLOR (https://no-color.org): any non-empty value disables 'auto'.
    // An explicit --color always still wins — the flag is a direct request,
    // the environment variable only a standing preference.
    def enabled = colorMode == 'always' ||
                  (colorMode == 'auto' && onTerminal() && !System.getenv('NO_COLOR'))

    def paint = { String code, value ->
        def s = value == null ? '' : value.toString()
        enabled ? "${esc}[${code}m${s}${reset}" : s
    }

    def red    = { s -> paint('31', s) }
    def green  = { s -> paint('32', s) }
    def yellow = { s -> paint('33', s) }
    def cyan   = { s -> paint('36', s) }
    def gray   = { s -> paint('90', s) }

    [
        enabled: enabled,
        red    : red,
        green  : green,
        yellow : yellow,
        cyan   : cyan,
        gray   : gray,

        // Section and per-file headers ("*** Processing X"), stdout.
        header : { s -> println cyan(s) },

        // Terminal success lines ("*** Done", clean summaries), stdout.
        success: { s -> println green(s) },

        // The one error form shared by every script, stderr. Continuation
        // lines are printed by the caller with System.err.println directly.
        error  : { s -> System.err.println red("*** Error: ${s}") },

        // The one warning form, stderr. Aligned continuation lines are the
        // caller's job: System.err.println yellow('***          ...').
        warn   : { s -> System.err.println yellow("*** Warning: ${s}") },

        // "1 file" / "3 files". Every count printed by these scripts knows its
        // own number, so there is never a reason to fall back on "file(s)".
        // Pass an explicit plural for anything that does not simply take an -s:
        // plural(n, 'discrepancy', 'discrepancies').
        plural : { int n, String noun, String plural = null ->
            "${n} ${n == 1 ? noun : (plural ?: noun + 's')}"
        },

        // The noun alone, for a sentence that has already printed the count or
        // needs the word in another position ("2 files use a different layout").
        pluralize: { int n, String noun, String plural = null ->
            n == 1 ? noun : (plural ?: noun + 's')
        },

        // A progress meter for the probing passes, which are seconds of silence
        // on a slow share. Call tick() per item and finish() at the end.
        //
        // Two renderings, chosen by the same terminal probe that gates colour.
        // On a terminal the line is rewritten in place with a bar and a
        // percentage. Redirected to a file or a pipe it degrades to appended
        // dots, because '\r' does not erase anything there — every frame would be
        // retained, smearing "10%^M20%^M30%^M" through the log, and the test
        // harness captures through a pipe. The bar is ASCII for the same reason
        // the file lists are: this reaches Windows consoles on legacy codepages.
        //
        // Dots are emitted per slice of the total rather than per item, so a
        // 200-file batch no longer wraps the terminal with 200 of them.
        // `opts.interactive` overrides the terminal probe. It exists so the tests
        // can drive both renderings — they run through a pipe, where the probe is
        // false by definition, and the bar would otherwise ship untested.
        progress: { String label, int total, Map opts = [:] ->
            def interactive = opts.containsKey('interactive') ? opts.interactive : onTerminal()
            def width = 24
            def count = 0
            def shown = -1
            def perDot = Math.max(1, (int) Math.ceil(total / 40.0d))

            if (!interactive) {
                print label
                System.out.flush()
            }

            [tick  : {
                count++
                def pct = total > 0 ? Math.min(100, (int) (count * 100L / total)) : 100
                if (interactive) {
                    if (pct == shown) return
                    shown = pct
                    def filled = (int) (pct * width / 100)
                    print "\r${label}  [${'#' * filled}${'-' * (width - filled)}] ${pct}%"
                } else {
                    if (count % perDot != 0 && count != total) return
                    print '.'
                }
                System.out.flush()
            },
             finish: {
                 // The completed bar stays on screen: it is the record of how long
                 // the wait was for, and clearing it would leave a gap above the
                 // report with nothing to explain the pause.
                 if (interactive && shown < 0) print label
                 println()
             }]
        },
    ]
}
