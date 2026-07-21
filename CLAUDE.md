# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A Groovy toolkit for automating MKV video file workflows — primarily for TV show episodes. Scripts are run individually from the command line; there is no build step or package manager beyond `@Grab` annotations. A self-contained test harness lives in `src/test/`.

**Documentation layers:** `README.md` is the front gate — setup, the script tour, and a minimal config quick-start only. User-facing depth (output/colour conventions, the check-report reading guide, the full `config.yaml` field reference) lives in `docs/reference.md`; the README links each trimmed topic to its reference section. This file holds implementation internals. When adding documentation, put it in the right layer instead of growing the README.

## Running scripts

All scripts operate on the current working directory — in practice the directory containing the media files. `mux.groovy` reads `config.yaml` from the CWD (per-show config next to the media files) or from an explicit `--config <path>`; there is **no** fallback to a config next to the script. The repo ships `src/config.example.yaml` as a template — it is never auto-loaded, because silently applying a demo config's track selections to an unrelated directory produced confidently wrong output (a wrong `config title` on the `--check` verdict was the giveaway). The inspection modes (`--identify`, `--check`, `--check-verbose`) run **without** a config; only a muxing run requires one, and a muxing run with no config is a clean exit-2 error pointing at the template, not a stack trace. Only the test suite is run from the repo root:

```bash
groovy src/mux.groovy                                       # Main muxer — reads config.yaml (CWD) or --config PATH
groovy src/mux.groovy --identify                            # Print a track table per file, mux nothing
groovy src/mux.groovy --check                               # Compare track structure across the batch, mux nothing
groovy src/mux.groovy --dry-run                             # Print the mkvmerge command per file, run nothing
groovy src/mux.groovy "Show.S01E0[12].mkv"                  # Operate on a subset: file names or globs, repeatable
groovy src/mux.groovy --exclude "*.sample.mkv"              # Skip files matching a pattern, repeatable
groovy src/fetch_episodes.groovy --show-id 2260 --season 1  # Fetch episode names from TheMovieDB
groovy src/rename.groovy "Show Name" [episodeOffset]        # Batch-rename files (--dry-run to preview)
groovy src/filename_to_title.groovy                         # Set MKV segment title/track name from filename
groovy src/fix_srt.groovy                                   # Validate and reformat SRT files
groovy src/to_utf8.groovy [--encoding CS] [--backup]        # Subtitles → UTF-8 in place (srt/ass/ssa/vtt)
groovy src/find_unused_fonts.groovy                         # Find unused fonts referenced in ASS subtitles
groovy src/propedit.groovy                                  # Batch mkvpropedit — fix properties without remuxing
```

Each script also has a wrapper in `bin/` (`mkv-mux`, `mkv-fetch-episodes`, `mkv-rename`, `mkv-filename-to-title`, `mkv-propedit`, `mkv-to-utf8`, `mkv-fix-srt`, `mkv-find-unused-fonts`) so it can be invoked from any directory once `bin/` is on `PATH`. Mapping rule: strip the `mkv-` prefix, hyphens become underscores, add `.groovy`.

`fetch_episodes.groovy` reads the API key from `src/apikey.txt` if `--api-key` is not supplied.

`propedit.groovy` is a generic wrapper that runs `mkvpropedit` in a loop over all MKV files in the current directory — it can fix any property (track names, forced/default flags, etc.) without remuxing. All arguments are passed through verbatim, with the file name inserted first; no source editing is needed per task. It deliberately does not use picocli, which would try to parse the passthrough options as its own. `-h`/`--help` is intercepted only when it is the sole argument.

`to_utf8.groovy` converts `.srt`/`.ass`/`.ssa`/`.vtt` **in place**. It skips input that is already UTF-8 — by BOM or by a clean strict UTF-8 decode, which also covers pure ASCII — so re-running over a directory is a no-op rather than a corruption. Decoding uses `CodingErrorAction.REPORT`, because Java's default decoder silently replaces invalid bytes and a wrong `--encoding` would otherwise produce mojibake that looks like success. UTF-16 input is refused outright: every byte of it maps to something in a single-byte charset, so strict decoding alone would not catch it. `.sub` is excluded on purpose — ambiguous between MicroDVD text and binary VobSub. Content is decoded and re-encoded whole, not line by line, so CRLF survives.

**Exit-code discipline:** every batch script — `propedit.groovy`, `to_utf8.groovy`, `filename_to_title.groovy`, `fix_srt.groovy`, `rename.groovy` — exits non-zero if any file failed, so all are usable from a shell script. Environment/setup errors exit 2 before touching anything: an unusable `--encoding` name in `to_utf8.groovy`, a missing mkvpropedit in `propedit.groovy`/`filename_to_title.groovy`. `fetch_episodes.groovy` exits 2 on API-key problems and 3 on network/API failures. `mux.groovy` deliberately keeps its continue-on-error, always-exit-0 behaviour (a test depends on it, and a partially-successful batch mux is a normal outcome there) — the one exception is `--strict`, which exits 2 when the consistency check finds a discrepancy affecting a selected track.

## Running tests

```bash
groovy src/test/run_tests.groovy              # Run all 90 tests
groovy src/test/run_tests.groovy --filter 01  # Run a single test by name fragment
groovy src/test/run_tests.groovy --keep       # Preserve src/test/work/ for inspection after run
```

Tests use `src/test/test.mkv` as the input fixture: track 0 video (und), 1–3 audio (jpn/eng/rus), 4–6 subtitles (eng/rus-forced/jpn). The harness stages files into `src/test/work/<case>/`, writes a tailored `config.yaml`, runs the script under test as a subprocess via the `runScript` closure, and asserts on the output via `mkvmerge -J`.

Harness conventions worth knowing before adding a case:

- `cfg(...)` builds a `config.yaml` string from a map; omitting `trackOrder` omits the key entirely, which is how the derivation tests work.
- Tests that need `mkvpropedit` check `mkvpropeditExe` and skip themselves (printing a note) when it is absent, rather than failing.
- The wrapper smoke test needs a bare `groovy` on `PATH`, which the harness itself does not require — it skips when that is unavailable. On Windows the probe must go through `cmd`, since `groovy` is a `.bat` that `ProcessBuilder` cannot launch directly.
- `buildVariant(dest, opts)` builds a derivative MKV from `test.mkv` with a chosen track subset and per-id name/language/flag overrides, for the consistency-check fixtures; `writeChapters` emits an OGM-simple chapter file (text, no binary fixture needed). **mkvmerge renumbers surviving tracks from 0 in source order**, so a variant's IDs are not the source IDs *unless all tracks are kept* — keeping audio `[1,2,3]` + subs `[4,5,6]` preserves `0-6`, which is why the split tests do that and drop a track only when they want a genuine absence. `--track-name` targets the *source* track id, so overriding a kept track means naming the id you kept (`2:` not `1:` when you kept track 2) — getting this wrong silently leaves the name unchanged.
- `withStubServer(routes, body)` stands up a JDK `com.sun.net.httpserver.HttpServer` on a random port and serves canned JSON per path. This is how `fetch_episodes.groovy` is tested offline: the script's hidden `--base-url` option points it at the stub. Deterministic and dependency-free, so it runs everywhere including CI.
- The live TheMovieDB contract test (`37_…`) is skip-guarded on a key in `TMDB_API_KEY` or `src/apikey.txt`, following the same pattern as `mkvpropeditExe`. It runs automatically on the dev machine and skips elsewhere — including on PRs from forks, where GitHub withholds secrets by design.
- When a test fails, the harness prints the captured output of the script under test; this is what makes subprocess failures diagnosable in CI logs.

**Groovy's File I/O is charset-asymmetric**, which drives the `episodes.txt` contract: the no-arg *reader* (`readLines()`, `getText()`) runs `CharsetToolkit` auto-detection and picks UTF-8 for UTF-8 content, but the no-arg *writer* (`withWriter {}`) uses the platform default and silently writes `?` for every unmappable character. So `fetch_episodes.groovy` writes with an explicit `'UTF-8'` (lossy otherwise on a non-UTF-8 default), while `rename.groovy` reads with no charset on purpose — forcing UTF-8 there would break a hand-assembled `episodes.txt` and gain nothing over auto-detection. Verified by probe under `-Dfile.encoding=ISO-8859-1`; note that reproducing this needs `-Dgroovy.source.encoding=UTF-8` too, or Groovy compiles the script's own string literals with the hostile charset and every result is self-consistently wrong.

## External dependencies (must be installed separately)

- **Groovy 3 or newer** (Java 11+) — the runtime for all scripts; CI tests both Groovy 3 and Groovy 5, plus a weekly leg against the newest MKVToolNix release and a weekly-only job for the live TheMovieDB contract test (reads a `TMDB_API_KEY` repo secret; never runs on push or PR, so network flakiness cannot redden the badge)
- **MKVToolNix** — `mkvmerge` is auto-detected from PATH (optionally overridden via `general.mkvmergeExe` in `config.yaml`); `mkvpropedit` is invoked from PATH by `filename_to_title.groovy` and `propedit.groovy`
- JVM library dependencies are declared via `@Grab` annotations inside each script and fetched automatically on first run

## Architecture and workflow

Scripts form a sequential pipeline:

```
TheMovieDB API
  └─ fetch_episodes.groovy → src/episodes.txt
       └─ rename.groovy   → renamed files (Show - SxxEyy - Title.ext)
            └─ mux.groovy (+ config.yaml) → mkvmerge → output MKV in destinationDir/
                 └─ post-processing utilities:
                      filename_to_title.groovy  (embed metadata)
                      propedit.groovy               (fix track flags)
                      to_utf8.groovy            (encoding fixes)
                      fix_srt.groovy           (subtitle repair)
                      find_unused_fonts.groovy  (font cleanup)
```

## Shared helper files: `src/output.groovy` and `src/tools.groovy`

The only two files in `src/` that are not standalone scripts. Both are loaded at runtime by explicit path, resolved from the calling script's own location (never the CWD, which is the media directory):

```groovy
def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def ui = evaluate(new File(scriptDir, 'output.groovy'))(colorMode)   // 'auto' literal in scripts without --color
def findMkvTool = evaluate(new File(scriptDir, 'tools.groovy'))
```

**Never reference these as classes** (`Output.foo()` style). Groovy's implicit sibling-class resolution is CWD- and invocation-dependent — that failure mode is why earlier attempts at shared helpers in this project were abandoned. Explicit `evaluate()` by absolute path (the same script-location resolution `fetch_episodes.groovy` uses for `apikey.txt`) has no such dependence and works identically under the `bin/` wrappers, foreign CWDs, and both CI legs. Both files have **no `@Grab` and no imports**, so loading them never touches the network or the caller's classloader setup (`@GrabConfig(systemClassLoader = true)` is unaffected), and each file's last expression is its exported value: a factory closure in `output.groovy` (call it with the color mode, get a map of helpers), the `findMkvTool` closure itself in `tools.groovy`. The test harness loads both via its `repoRoot`.

`output.groovy` owns the palette — red 31 errors/failure summaries, green 32 success (`*** Done`, clean summaries, `[PASS]`), yellow 33 warnings + the `--check` differing-cell highlight, cyan 36 section/file/table-column headers, gray 90 de-emphasis of the `--check` file-evidence lists (never an accent) — plus the gating (`always`, or `auto` on a terminal without `NO_COLOR`; anything else is `never`) and the shared message forms: `error(msg)`/`warn(msg)` print `*** Error: `/`*** Warning: ` to **stderr**; `header`/`success` print to stdout; `plural(n, noun)` lives here too. The terminal probe calls `Console.isTerminal()` where it exists (JDK 22+ returns a Console even when piped), falling back to the plain null-check on JDK 11/21.

**The colour invariant: escapes wrap whole lines or whole pre-padded table cells, never a fragment inside a phrase.** The suite asserts with substring `contains(...)` on captured output, so a mid-phrase escape breaks tests (and reads as flicker anyway); padding before colour is what keeps the `--check` table aligned. The one sanctioned two-segment line is `printMinority`'s marker row: the `<-` stays default-foreground while the file name after it is gray — still whole segments, and the pinned substrings (`<-`, the file name) each stay contiguous. Tests run the scripts through a pipe, so `auto` colour is off in every test — only the `--color always` cases (82–84) ever see escapes.

Placement follows the closure-scoping rule below: in `mux.groovy` the `ui` load is the first script-body statement, because closures capture it at definition time. In `propedit.groovy` the load sits after the usage/early-return block so a bare `-h` stays instant.

## `mux.groovy` internals

`mux.groovy` is the core script. It reads `config.yaml` from the CWD (or `--config <path>`), discovers all files in the current directory matching `allowedExtensions`, and for each file constructs and executes an `mkvmerge` command.

**Critical pattern:** all helpers in `mux.groovy` must be closures (`def foo = { ... }`), not methods (`def foo() { ... }`). Groovy methods on a Script class cannot access `def`-declared local variables from the script body — closures can because they capture their enclosing scope. `buildCommandLine` is defined as a closure for this reason.

The script uses picocli via `@PicocliScript2` (like `rename.groovy` and `fetch_episodes.groovy`). Annotations and `@Field` declarations must all precede the first script-body statement; the closure pattern above still applies to everything after them. This combination is verified on both Groovy 3/JDK 11 and Groovy 5/JDK 21 — `@GrabConfig(systemClassLoader = true)` now covers snakeyaml as well, which is the part most likely to break on a JDK change.

**The `properties` trap:** when reading `mkvmerge -J` output, the per-track JSON key `properties` must be accessed as `track.get('properties')`. On Groovy 4+ both `track.properties` and `track['properties']` resolve to the bean properties of the map object itself, silently returning the wrong thing. This applies to `identifyFile` and `trackSignature`/`probeFile` in `mux.groovy` as well as the test harness.

**`formatFileList` must be declared before every closure that calls it.** A closure captures a script-body `def` local through its enclosing scope at *definition* time, so a helper closure has to already exist when the calling closure is created, not merely when it runs. `formatFileList` sits high in the file (right after `priority`) because `runConsistencyCheck` and the companion pre-flight both call it. This is the same closure-scoping rule as the "helpers must be closures" note above, one step further: order matters among the closures too.

**File masks do their own glob expansion.** Positional `FILE` arguments and `--exclude` patterns filter the file list in all modes. Unix shells expand `*.mkv` before Groovy sees it, but `cmd.exe` passes the literal string through, so `compileMasks` expands patterns itself via `FileSystems.getDefault().getPathMatcher("glob:…")`, matching against the bare file name. A pattern that names an existing file is matched literally instead — otherwise a file called `Odd[1].mkv` could not be selected at all, since as a glob that also matches `Odd1.mkv`. Getting this wrong passes a Linux-only CI and fails on Windows, which is why tests 39–45 pass patterns through `ProcessBuilder` (no shell, so nothing pre-expands them). The file list is also sorted by name now, so batches process in a predictable order.

Lazy GString closures (`${-> fileName}`) are used throughout `buildCommandLine` so that `fileName` and `extension` are evaluated at command execution time, not at closure definition time.

`additionalSources` entries support a `${fileName}` placeholder that resolves to the base filename of the current main source file, enabling per-episode companion files like `${fileName}[Studio].mka`.

**Consistency check (`--check`, and the default pre-flight).** Compares track structure across the whole batch and reports it before muxing. One `mkvmerge -J` per file via `probeFile`, shared by `--identify` and `--check` so combining them does not double the subprocesses (`identifyFile` was refactored to take a probed record rather than shell out itself). A live `*** Reading N file(s)...` tick prints during probing, since that is seconds of silence on a slow share.

The report works in **two layers**, which is the key design decision and came out of a real mixed-layout season (some episodes had the subtitle track first). First it groups files by **layout** (`layoutKey` — the type at each ID), printed largest group first as `*** Layout N (K files): <type sequence>` (the header appears only when there is more than one layout). A per-ID comparison alone put the same shifted file at three different IDs and was unreadable. Then, within each group, `groupTracks` compares **values** per ID — codec, language, name, default, forced (`SIG_KEYS`); the video track's name is nulled *at construction* in `trackSignature` so it can never leak into a group key. Grouping ranks by population and never anchors on the first file. Because the value table runs per layout, every ID has one type there, so the `(absent)`/`tg.missing` branches are dead for that set (a missing track is a whole different layout) — kept only as defensive guards.

Output specifics that were hard-won in review: the differing cell is highlighted (`hl`/`cell`, ANSI yellow) under `--color`, with the gating shared via `output.groovy` (`auto` = on only on a real terminal, so piped/CI/test output is plain and never breaks an assertion); the NAME column is the last column and dynamically sized to the longest name (`nameWidth`, clamped 12–60), so no trailing padding and no clipping; there is no FILES count column — the `<-` lists (one file name per line via `formatFileList`) carry that. In the largest group the strict-majority row is the unnamed reference and only minorities are named; an outlier group has no reference (all files named — a uniform one lists them above its table, a split one names each value's files). `formatFileList` must clamp `take` to `names.size()` because `--check-verbose` passes `limit = Integer.MAX_VALUE` and `take(MAX)` allocates a 2-billion-element array (OOM). **`--check-verbose` implies `--check`** (`checkOnly = checkOnly || checkVerbose`): it is a report modifier, and without this a bare `--check-verbose` printed the report and then muxed the whole batch.

`findDuplicates` flags only genuinely ambiguous same-language tracks (type+language+codec+name all equal). Classification: a layout outlier is blocking when it changes the type at a selected ID or drops one; a per-ID value discrepancy is blocking via `isBlocking` (selected ID *and* `copiesAllOfType` false). `--strict` exits 2 on any blocking finding; default warns and continues. `--check` alone returns before muxing; `--check --identify` falls through to the identify loop, which prints per-file tables then returns before muxing because `identifyOnly` is set.

**Config is optional for the inspection modes** (`inspecting = identifyOnly || checkOnly || checkVerbose`, computed before the config load so it can gate it). `config` may be `null`: `allowedExtensions` falls back to `DEFAULT_EXTENSIONS`, `mkvmergeExe` to PATH auto-detect, `selectedIds` to empty, and every other config access is null-safe (`config?.…`). With no config `runConsistencyCheck` prints the structural report plus a difference count but skips the blocking/informational classification (there are no selected tracks to classify against). The trackOrder resolution is also gated on `!inspecting` — it is only needed for the command line and would otherwise NPE.

**`mkvmerge -J` exits 0 on a file it cannot read** — it signals failure through `container.recognized`/`container.supported`, not the exit code. `probeFile` checks those; relying on the exit code alone would let a corrupt file into the comparison as a file with zero tracks, which reads as "every track is absent here" and poisons the whole report.

**Companion pre-flight.** Before muxing (and before `--dry-run` previews, but not `--identify`), every `additionalSources` path is resolved per episode and checked for existence. Episodes with missing companions are reported and dropped from the batch; the rest still mux. This never aborts — those episodes would have failed in mkvmerge anyway, and a partially-released dub is a normal situation. The check runs before the `destinationDir` `mkdirs()`, so a fully-blocked batch leaves no empty output directory behind. `formatFileList` (one file name per line, truncated past a limit, ASCII only — this output reaches Windows consoles on legacy codepages) is shared with the `--check` report.

## `bin/` wrappers

Each script has a matching pair in `bin/`: `mkv-<name>.bat` for Windows and an extension-less `mkv-<name>` shell script for Linux/macOS. Both locate their target relative to the wrapper itself (`%~dp0..\src\…` / `$(dirname "$0")/../src/…`), so `bin/` works from any `PATH` entry.

When adding a wrapper, two things are easy to get wrong and are not caught by CI:

- **`.gitattributes` ordering.** `bin/mkv-* text eol=lf` must come *before* `*.bat text eol=crlf`, because gitattributes is last-match-wins and `bin/mkv-*` also matches `bin/mkv-mux.bat`. Wrong order ships batch files with LF endings, which no Linux CI leg will catch.
- **The executable bit** on the shell wrapper: `git update-index --chmod=+x bin/mkv-<name>`. Verify with `git ls-files -s bin/` — shell wrappers must be `100755`, `.bat` files `100644`.

## Configuration (config.yaml)

| Field | Purpose |
|-------|---------|
| `general.mkvmergeExe` | Optional full path to mkvmerge; omit to auto-detect from PATH (with a fallback to the default Windows install location) |
| `general.destinationDir` | Output folder (relative to CWD when script runs) |
| `general.allowedExtensions` | Which file types to treat as main sources |
| `mainSource.videoTrack` | Language and optional title override for the video track |
| `mainSource.audioTracks[]` | List of audio track IDs to include, with language/title/default |
| `mainSource.subtitleTracks[]` | List of subtitle track IDs; `charset` is optional |
| `additionalSources[]` | Extra files (audio, subtitle) to mux in; track ID is always 0 |
| `trackOrder` | Optional `"sourceIndex:trackId"` CSV controlling output track order. Omit it to derive the order from the configured tracks (video, audioTracks in listed order, subtitleTracks, then one entry per additional source). When set explicitly it is validated against the configured tracks and mismatches are warned about, never fatal — mkvmerge itself silently ignores nonexistent IDs |

Omitting `audioTracks` or `subtitleTracks` (or setting them to `[]`) causes no tracks of that type to be copied.

## Platform notes

The scripts are cross-platform: `mkvmerge` and `mkvpropedit` are resolved from PATH, with a fallback to the default Windows install location (`C:\Program Files\MKVToolNix\`). The project originated on Windows, which shows in a few places: the sample `mkvmergeExe` value in `config.example.yaml` uses a Windows path, `fetch_episodes.groovy` strips characters invalid in Windows file names, and `to_utf8.groovy` defaults its source encoding to Windows-1251 (though `--encoding` accepts any charset). CI runs the test suite on Linux.
