# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A Groovy toolkit for automating MKV video file workflows — primarily for TV show episodes. Scripts are run individually from the command line; there is no build step or package manager beyond `@Grab` annotations. A self-contained test harness lives in `src/test/`.

**Documentation layers:** `README.md` is the front gate — setup, the script tour, and a minimal config quick-start only. User-facing depth (output/colour conventions, the check-report reading guide, the full `config.yaml` field reference) lives in `docs/reference.md`; the README links each trimmed topic to its reference section. This file holds implementation internals. When adding documentation, put it in the right layer instead of growing the README.

## Running scripts

All scripts operate on the current working directory — in practice the directory containing the media files. `mux.groovy` reads `config.yaml` from the CWD (per-show config next to the media files) or from an explicit `--config <path>`; there is **no** fallback to a config next to the script. The repo ships `src/config.example.yaml` as a template — it is never auto-loaded, because silently applying a demo config's track selections to an unrelated directory produced confidently wrong output (a wrong `config title` on the check verdict was the giveaway). **Inspection lives in `inspect.groovy`, not in `mux.groovy`**: a config is optional there and muxing always requires one, so the two personalities that used to share a script (config-optional reporting vs config-required muxing) are now separate entry points. A muxing run with no config is a clean exit-2 error pointing at the template, not a stack trace. Only the test suite is run from the repo root:

```bash
groovy src/mux.groovy                                       # Main muxer — reads config.yaml (CWD) or --config PATH
groovy src/mux.groovy --dry-run                             # Print the mkvmerge command per file, run nothing
groovy src/mux.groovy "Show.S01E0[12].mkv"                  # Operate on a subset: file names or globs, repeatable
groovy src/mux.groovy --exclude "*.sample.mkv"              # Skip files matching a pattern, repeatable
groovy src/inspect.groovy                                   # Compare track structure across the batch (default mode)
groovy src/inspect.groovy --identify                        # Print a track table per file
groovy src/inspect.groovy --identify --check                # Both, from one mkvmerge scan
groovy src/fetch_episodes.groovy --show-id 2260 --season 1  # Fetch episode names from TheMovieDB
groovy src/fetch_episodes.groovy --show-id <TMDB URL>       # Id (and season) parsed out of the URL
groovy src/fetch_episodes.groovy --show-id 1920 --season 1 --language ru-RU
groovy src/rename.groovy ["Show Name"] [episodeOffset]      # Batch-rename files (--dry-run to preview)
groovy src/filename_to_title.groovy                         # Set MKV segment title/track name from filename
groovy src/fix_srt.groovy                                   # Validate and reformat SRT files
groovy src/to_utf8.groovy [--encoding CS] [--backup]        # Subtitles → UTF-8 in place (srt/ass/ssa/vtt)
groovy src/find_unused_fonts.groovy                         # Find unused fonts referenced in ASS subtitles
groovy src/propedit.groovy                                  # Batch mkvpropedit — fix properties without remuxing
```

Each script also has a wrapper in `bin/` (`mkv-mux`, `mkv-inspect`, `mkv-fetch-episodes`, `mkv-rename`, `mkv-filename-to-title`, `mkv-propedit`, `mkv-to-utf8`, `mkv-fix-srt`, `mkv-find-unused-fonts`) so it can be invoked from any directory once `bin/` is on `PATH`. Mapping rule: strip the `mkv-` prefix, hyphens become underscores, add `.groovy`.

`fetch_episodes.groovy` reads the API key from `src/apikey.txt` if `--api-key` is not supplied.

`propedit.groovy` is a generic wrapper that runs `mkvpropedit` in a loop over all MKV files in the current directory — it can fix any property (track names, forced/default flags, etc.) without remuxing. All arguments are passed through verbatim, with the file name inserted first; no source editing is needed per task. It deliberately does not use picocli, which would try to parse the passthrough options as its own. `-h`/`--help` is intercepted only when it is the sole argument.

`to_utf8.groovy` converts `.srt`/`.ass`/`.ssa`/`.vtt` **in place**. It skips input that is already UTF-8 — by BOM or by a clean strict UTF-8 decode, which also covers pure ASCII — so re-running over a directory is a no-op rather than a corruption. Decoding uses `CodingErrorAction.REPORT`, because Java's default decoder silently replaces invalid bytes and a wrong `--encoding` would otherwise produce mojibake that looks like success. UTF-16 input is refused outright: every byte of it maps to something in a single-byte charset, so strict decoding alone would not catch it. `.sub` is excluded on purpose — ambiguous between MicroDVD text and binary VobSub. Content is decoded and re-encoded whole, not line by line, so CRLF survives.

**Exit-code discipline:** every batch script — `propedit.groovy`, `to_utf8.groovy`, `filename_to_title.groovy`, `fix_srt.groovy`, `rename.groovy` — exits non-zero if any file failed, so all are usable from a shell script. Environment/setup errors exit 2 before touching anything: an unusable `--encoding` name in `to_utf8.groovy`, a missing mkvpropedit in `propedit.groovy`/`filename_to_title.groovy`. `fetch_episodes.groovy` exits 2 on API-key problems and 3 on network/API failures. `mux.groovy` deliberately keeps its continue-on-error, always-exit-0 behaviour (a test depends on it, and a partially-successful batch mux is a normal outcome there) — the one exception is `--strict`, which exits 2 when the consistency check finds a discrepancy affecting a selected track. `inspect.groovy` writes nothing and **exits 0 whatever it finds** — `--strict` is the only path to a non-zero exit, a `--config` path that does not exist very much included (it warns and carries on; see the inspect section below for why).

## Running tests

```bash
groovy src/test/run_tests.groovy              # Run all 132 tests (~8-9 min; see ROADMAP.md on parallelising it)
groovy src/test/run_tests.groovy --filter 01  # Run a single test by name fragment
groovy src/test/run_tests.groovy --keep       # Preserve src/test/work/ for inspection after run
```

Tests use `src/test/test.mkv` as the input fixture: track 0 video (und), 1–3 audio (jpn/eng/rus), 4–6 subtitles (eng/rus-forced/jpn). The harness stages files into `src/test/work/<case>/`, writes a tailored `config.yaml`, runs the script under test as a subprocess via the `runScript` closure, and asserts on the output via `mkvmerge -J`.

Harness conventions worth knowing before adding a case:

- `cfg(...)` builds a `config.yaml` string from a map; omitting `trackOrder` omits the key entirely, which is how the derivation tests work.
- Tests that need `mkvpropedit` check `mkvpropeditExe` and skip themselves (printing a note) when it is absent, rather than failing.
- The wrapper smoke test needs a bare `groovy` on `PATH`, which the harness itself does not require — it skips when that is unavailable. On Windows the probe must go through `cmd`, since `groovy` is a `.bat` that `ProcessBuilder` cannot launch directly.
- `stageExternalText(workDir, relPath, text)` and `stageExternalTrack(workDir, relPath, type, id)` stage external files into subdirectories of the work dir, and `stageTree(workDir)` assembles the shared multi-directory fixture (three episodes, two dub groups with different coverage, one of them also supplying subtitles from a second category directory — the merge case — plus a suffixed sibling, an unmatched file and an `extras/` stray). These are the first fixtures in the suite that are not flat, and they must be declared **after** `stageInput`, which they call: the closure-capture-at-definition-time rule applies inside the harness exactly as it does inside the scripts.
- The discovery engine is tested **in-process** (`evaluate`d directly, no subprocess) rather than through a report, because the matching rules are what both `inspect.groovy` and `rename.groovy` depend on and asserting on the data structure is far more precise than reading them back out of formatted output.
- `buildVariant(dest, opts)` builds a derivative MKV from `test.mkv` with a chosen track subset and per-id name/language/flag overrides, for the consistency-check fixtures; `writeChapters` emits an OGM-simple chapter file (text, no binary fixture needed). **mkvmerge renumbers surviving tracks from 0 in source order**, so a variant's IDs are not the source IDs *unless all tracks are kept* — keeping audio `[1,2,3]` + subs `[4,5,6]` preserves `0-6`, which is why the split tests do that and drop a track only when they want a genuine absence. `--track-name` targets the *source* track id, so overriding a kept track means naming the id you kept (`2:` not `1:` when you kept track 2) — getting this wrong silently leaves the name unchanged.
- `withStubServer(routes, body)` stands up a JDK `com.sun.net.httpserver.HttpServer` on a random port and serves canned JSON per path. This is how `fetch_episodes.groovy` is tested offline: the script's hidden `--base-url` option points it at the stub. Deterministic and dependency-free, so it runs everywhere including CI.
- The live TheMovieDB contract test (`37_…`) is skip-guarded on a key in `TMDB_API_KEY` or `src/apikey.txt`, following the same pattern as `mkvpropeditExe`. It runs automatically on the dev machine and skips elsewhere — including on PRs from forks, where GitHub withholds secrets by design.
- When a test fails, the harness prints the captured output of the script under test; this is what makes subprocess failures diagnosable in CI logs.
- **Never run two suites at once**, including a `--filter` run alongside a full one. Work directories are `src/test/work/<case>`, keyed by case name only, and `runTest` deletes and recreates that directory as it starts — so a second run wipes the first run's fixture mid-test. The failure surfaces somewhere unrelated to whatever is actually being worked on (a `--filter 38` run against a full run in the background made `38_non_ascii_titles_round_trip` fail with "No episode data", which looks exactly like a rename regression and is not one).

## Episode metadata: `episodes.yaml` and `episodes.txt`

`fetch_episodes.groovy` writes **both**, always, and **neither is sanitized** — names carry the exact TheMovieDB spelling, `:` and `?` included. Stripping happens in `rename.groovy`, at the point a name becomes a file name, because `mux.groovy` needs the original spelling for `${episodeName}` and a name stripped at fetch time could never be recovered.

Migration note worth keeping: `rename.groovy` from v1.3.0 or earlier, fed a `episodes.txt` written by a current `fetch_episodes.groovy`, will put characters into file names that Windows rejects. Only that direction is affected — sanitizing is idempotent, so a current `rename.groovy` handles an older, already-stripped `episodes.txt` correctly.

`episodes.yaml` carries show name, year, season number and name, the fetch locale, and every episode by its **real** `episode_number` (so a gap in a season does not shift everything after it). `episodes.txt` stays the format that can be written by hand — one name per line, matched by line order plus `episodeOffset`. Both `rename.groovy` and `mux.groovy` prefer the yaml and fall back to the txt.

**`episodeOffset` applies to `episodes.txt` alone.** The offset is not a preference: that file has no numbers in it, so the offset is part of *how it is read* — it is the episode number of the first line, which is what makes a partial file (titles for E11–E20) usable. `episodes.yaml` carries real numbers, so there is nothing to shift, and it is ignored there. Test `94b` pins both halves.

Anyone wanting genuine renumbering — anime absolute numbering against a season-split show — needs an explicitly named option for it rather than a second meaning on this one, and it belongs with the `--episode-regex` work parked in `ROADMAP.md`.

`mux.groovy` reads `episodes.txt` as line N = episode N and takes no offset — it is not the script that decides numbering. A trimmed `episodes.txt` therefore misses, and the stage-two pre-flight reports those files as dropped rather than stamping a wrong title into them; `episodes.yaml` is the answer for any season not starting at 1. The alternative — sort the files and match **positionally**, file *i* ↔ line *i*, needing no numbers at all — fails the other way round, silently mis-titling when `episodes.txt` holds a full season but only some of the files are present. The number-based join was chosen because missing loudly beats mis-titling quietly.

**`episodes.yaml` is hand-editable, so every reader guards it** exactly as it guards `config.yaml`: a syntax error, a top-level list, an empty file, or an episode number that is not a number. All four load sites go through `lib/yaml.groovy`'s `loadMapping`, which *classifies* and nothing more; the policy stays with each caller. `mux.groovy` exits 2 on any problem, in either file (a title stamped from metadata it could not read is the same confidently-wrong output as a mux against a config it did not understand); `inspect.groovy` warns and continues, and counts only `config.yaml` into `configProblems` — `--strict` means "treat the findings as a failure", and episode metadata produces no findings, it only decorates the source paths `--identify` resolves. `normalizeYaml` is passed in as `loadMapping`'s `transform` so it runs *inside* the guard, because `episode: "one"` throws there rather than at parse time.

Charset contract: the yaml is read and written with an **explicit UTF-8** both ways (machine-written, so it is a fixed contract), while `episodes.txt` keeps its asymmetry, below. `episodes.yaml` is dumped with `allowUnicode = true`, without which snakeyaml escapes every Cyrillic character as `\uXXXX` — valid YAML, unreadable for exactly the titles the feature exists to preserve.

**Groovy's File I/O is charset-asymmetric**, which drives the `episodes.txt` contract: the no-arg *reader* (`readLines()`, `getText()`) runs `CharsetToolkit` auto-detection and picks UTF-8 for UTF-8 content, but the no-arg *writer* (`withWriter {}`) uses the platform default and silently writes `?` for every unmappable character. So `fetch_episodes.groovy` writes with an explicit `'UTF-8'` (lossy otherwise on a non-UTF-8 default), while `rename.groovy` reads with no charset on purpose — forcing UTF-8 there would break a hand-assembled `episodes.txt` and gain nothing over auto-detection. Verified by probe under `-Dfile.encoding=ISO-8859-1`; note that reproducing this needs `-Dgroovy.source.encoding=UTF-8` too, or Groovy compiles the script's own string literals with the hostile charset and every result is self-consistently wrong.

## External dependencies (must be installed separately)

- **Groovy 3 or newer** (Java 11+) — the runtime for all scripts; CI tests both Groovy 3 and Groovy 5, plus a weekly leg against the newest MKVToolNix release and a weekly-only job for the live TheMovieDB contract test (reads a `TMDB_API_KEY` repo secret; never runs on push or PR, so network flakiness cannot redden the badge)
- **MKVToolNix** — `mkvmerge` is auto-detected from PATH (optionally overridden via `general.mkvmergeExe` in `config.yaml`); `mkvpropedit` is invoked from PATH by `filename_to_title.groovy` and `propedit.groovy`
- JVM library dependencies are declared via `@Grab` annotations inside each script and fetched automatically on first run

## Architecture and workflow

Scripts form a sequential pipeline:

```
TheMovieDB API
  └─ fetch_episodes.groovy → episodes.yaml + episodes.txt
       └─ rename.groovy   → renamed files (Show - SxxEyy - Title.ext)
            ├─ inspect.groovy (reports only; what you read to write config.yaml)
            └─ mux.groovy (+ config.yaml, + episodes.yaml for ${...} titles)
                 → mkvmerge → output MKV in destinationDir/
                 └─ post-processing utilities:
                      filename_to_title.groovy  (embed metadata)
                      propedit.groovy               (fix track flags)
                      to_utf8.groovy            (encoding fixes)
                      fix_srt.groovy           (subtitle repair)
                      find_unused_fonts.groovy  (font cleanup)
```

### The manual workflow this automates

The scripts are an incremental automation of a process the author has been doing by hand for years. Knowing the whole of it explains design decisions that look arbitrary from the code alone, and shows which steps are still manual — that is where the remaining work is (see `ROADMAP.md`).

| # | Manual step | Automated by |
|---|---|---|
| 1 | Review the main files' tracks (mkvmerge, or MediaInfo GUI) | `inspect.groovy --identify` |
| 2 | Decide which internal and external tracks to keep | `config.yaml` (a human decision; the tool only records it) |
| 3 | Copy everything into one working directory, **flat** | nothing yet — see recursion in `ROADMAP.md` |
| 4 | Bulk-rename every file to bare `SxxEyy[suffix].ext` | nothing yet — Total Commander's counter + substring rename, or by hand |
| 5 | Fetch episode titles, then rename **all** files (mains *and* companions) | `fetch_episodes.groovy` → `rename.groovy` |
| 6 | Separate files into groups of identical track structure (usually subdirectories) | `inspect.groovy` *reports* the groups; the split is still manual |
| 7 | Mux each group with its own track settings | `mux.groovy`, one `config.yaml` per group — originally the mkvmerge command line edited inside the script itself, per group |

Consequences worth knowing before changing anything:

- **Step 4 is why `rename.groovy` does not need to parse exotic names.** By the time it runs, every file is already `SxxEyy[suffix].ext`. A release like `[Salender-Raws] Hellsing OVA - 05 (...)` becomes `S01E05[Salender-Raws].mka` first — which is why the `s(\d\d)\.?e(\d\d)` regex being strict has never been a problem in practice, and why automating step 4 (not loosening step 5) is the right fix.
- **Step 5 renames companions too**, which is why `rename.groovy`'s extension list covers `.mka`/`.ass`/`.srt`/`.idx`/`.sub` and why `parseSuffix` preserves a trailing `[...]`. The suffix is what identifies which dub or subtitle group a companion belongs to, and it has to survive the rename.
- **That pairing is what makes `${fileName}[Studio].mka` the canonical `additionalSources` pattern**: after step 5 a companion's name is the main file's name plus the suffix, so the placeholder resolves exactly. It is a consequence of steps 3-5, not a convention chosen for its own sake.
- **Step 7's "one config per group" is why the config is per-directory**, next to the media rather than next to the script.
- **The config system exists because in-place editing was destructive.** Step 7 was originally done by editing the mkvmerge command line inside the script for each group. The script was not under version control at the time, so a botched edit destroyed the working invocation with nothing to recover it from — which happened more than once, and is what motivated lifting track selection out into `config.yaml`. Worth knowing before proposing anything that moves configuration back into the scripts.
- **Step 2's "no one size fits all" is why this is editable scripts plus config files rather than a GUI**: adapting the tool to the files is usually cheaper than adapting the files to the tool — though sometimes (step 4) adapting the files is unavoidable.

## Shared helper files: `src/lib/`

`src/lib/` holds the seven files that are not standalone scripts — `output.groovy`, `tools.groovy`, `episodes.groovy`, `check.groovy`, `subst.groovy`, `discovery.groovy`, `yaml.groovy`. They sit in their own directory purely so `src/` lists the nine things you can *run*; the loading mechanism is unchanged and does not care where they live. All are loaded at runtime by explicit path, resolved from the calling script's own location (never the CWD, which is the media directory):

```groovy
def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def ui = evaluate(new File(scriptDir, 'lib/output.groovy'))(colorMode)   // 'auto' in scripts without --color
def findMkvTool = evaluate(new File(scriptDir, 'lib/tools.groovy'))
def episodes = evaluate(new File(scriptDir, 'lib/episodes.groovy'))
def check = evaluate(new File(scriptDir, 'lib/check.groovy'))([ui: ui, mkvmergeExe: exe, parseJson: {...}])
def subst = evaluate(new File(scriptDir, 'lib/subst.groovy'))([ui: ui, episodes: episodes, episodeData: data])
def discovery = evaluate(new File(scriptDir, 'lib/discovery.groovy'))(episodes)
def loadYaml = evaluate(new File(scriptDir, 'lib/yaml.groovy'))({ String t -> new Yaml().load(t) })
```

**Never reference these as classes** (`Output.foo()` style). Groovy's implicit sibling-class resolution is CWD- and invocation-dependent — that failure mode is why earlier attempts at shared helpers in this project were abandoned. Explicit `evaluate()` by absolute path (the same script-location resolution `fetch_episodes.groovy` uses for `apikey.txt`) has no such dependence and works identically under the `bin/` wrappers, foreign CWDs, and both CI legs. All have **no `@Grab` and no imports**, so loading them never touches the network or the caller's classloader setup (`@GrabConfig(systemClassLoader = true)` is unaffected), and each file's last expression is its exported value: a factory closure in `output.groovy` (call it with the color mode, get a map of helpers), the `findMkvTool` closure itself in `tools.groovy`, a map of closures in `episodes.groovy`, a factory-taking-dependencies in `check.groovy` and `subst.groovy`, a factory taking the one injected parser in `yaml.groovy`. The test harness loads them via its `repoRoot`. Adding a helper means creating it in `src/lib/` — anything directly under `src/` is expected to be runnable and gets a `bin/` wrapper.

**The no-imports rule is what shapes every helper's API.** Anything the JDK does not import by default — snakeyaml, `groovy.json`, commons-io — has to be injected or avoided, so the seam always lands in the same place: the *caller* does the I/O and parsing, the *helper* owns the semantics. `episodes.groovy`'s `normalizeYaml` takes an already-parsed map; `check.groovy` takes a `parseJson` closure and the `mkvmergeExe` path; `subst.groovy` takes the already-loaded episode data and splits file names with plain string ops rather than `FilenameUtils`. Do not "fix" any of this by adding a `@Grab`.

`check.groovy` owns probing and the whole consistency report: `probeFile` (one `mkvmerge -J`, with the `container.recognized`/`supported` guards), `trackSignature`/`SIG_KEYS`, `groupTracks`, `findDuplicates`, `layoutKey`, `formatFileList`, `printMinority`, and `runConsistencyCheck(files, infos, opts)` → the blocking count. Config knowledge stays outside: `makeSelection(config)` turns a parsed config into the `selectedIds` / `configTitleFor` / `isBlocking` closures that `opts` carries, and `opts.verbose` is the `--check-verbose` modifier. **The NAME column has one glyph for absent and one for not-compared.** `-` means nothing is there (an unnamed track, a video row whose files carry no title); it replaced `(no name)`, which was longer in a column that competes for width. The video track's `name` is still nulled in `trackSignature` so it cannot enter a group key — its title carries the episode name and would report every file as deviant — but the raw value now rides alongside as `videoName`, which `SIG_KEYS` does not include and only the renderer reads. A video row stands for every file in its group, so `videoNameFor` prints the shared title when they agree and `(per file)` when they do not, rather than picking one file's arbitrarily. Showing it never makes it a finding. **A `guessed` flag rides alongside the same way** — set by `externalSlotsFor` on an external slot whose language was inferred from a folder rather than read from the file, outside `SIG_KEYS` so it cannot enter a group key, read only by `langCell` to gray a `rus?` cell exactly as `--identify` does. A differing language still wins the cell (yellow diff-highlight over gray); in practice a guess never varies within a slot, since every file there shares one extension and one folder guess.

**Every group names its files**, via `opts.membershipFor` — a caller-supplied closure that turns a group's files into a short string, or null to fall back to a plain file list. The largest group used to go unnamed on the grounds that the majority is the norm; that was right when the report only hunted outliers and wrong once the groups became "your muxing passes", where the first is as much an answer as the rest. No `<-` marker on it: that means "these rows deviate", a different question.

**Slots, not just tracks.** `groupSlots(infos, slotsOf)` is the grouping engine; `groupTracks` runs it over `info.tracks` (numeric IDs) and `groupExternals` over `info.externals` — an optional map the *caller* attaches, keyed by whatever identity it has, with signatures of the same shape plus `label` and `slot` for display. `check.groovy` never learns what an external file is; `mux.groovy` sets none, so every external path is inert there and its pre-flight output is byte-identical. `layoutKey` is the internal `id:type` sequence **plus the sorted set of external slot keys** — a set, because external files have no order — which is what makes the group count answer "how many muxing passes".

`discovery.groovy` owns external-file discovery — the matching rules `inspect.groovy` reports and `rename.groovy` renames by, which is exactly why they live in one place: a drifted copy would rename files that inspection never showed. Exports `walkTree(root, excludedCanonicalPaths)` (one recursive walk, dot-dirs and the caller's exclusions skipped, visited-canonical-path set against junction loops), `discoverCompanions(mains, treeEntries, opts)` → `[variants, unmatched, extras]`, the extension tables (`COMPANION_EXTENSIONS`, `PROBE_EXTENSIONS`, `CODEC_BY_EXTENSION`, `typeClassOf`), `guessLanguage` and `trimForDisplay`. Episode ranges are **not** here — that is `formatRanges` in `episodes.groovy`, where the episode semantics live. **It probes nothing** — matching is pure name work, which is what keeps `--check` coverage free on a network share.

Language spellings are **derived from CLDR at load time** rather than typed out: for each of a curated list of codes, `Locale` supplies the three-letter code, the English name and the native name, which is what makes `Русский` and `日本語` work as folder names. The list is curated because obscure codes collide with English words (`new` is Newari, `sun` Sundanese), and `AMBIGUOUS_CODES` withholds the bare two-letter form of the ones that are ordinary words themselves — `No subs` must not read as Norwegian, `UK BluRay` must not read as Ukrainian, `el`/`et`/`da` must not fire on a Romance-language title. Only the two-letter form is withheld: the three-letter code and both names still match, so an entry costs almost nothing while a false positive is a wrong language in a report used to write a config. **`AMBIGUOUS_CODES` is scoped to `LANGUAGE_CODES`** — it is consulted only inside that loop, so an entry for a code that is never guessed at is dead weight reading as protection it does not give; add the two together. `BIBLIOGRAPHIC` is scoped the same way, and carries only codes whose /B form actually differs from `getISO3Language` (Serbian's are both `srp`). Matching is whole-word over Unicode letters, with multi-word names tried as runs after single words.

**CLDR gives the citation form only**, which is the guesser's real limit — not the token list. A native name is supplied as masculine nominative singular (`русский`), so a folder whose adjective agrees with its noun does not match: `Русская озвучка` (feminine, agreeing with *озвучка*) and `Русские субтитры` (plural) are both Russian and both miss. Every language with adjective agreement — Polish, Czech, Ukrainian, the rest of Slavic — has the same shape. The result is a `-` in the LANG column rather than a wrong answer, which is the intended failure: the guess is a convenience, and declining to guess costs the reader nothing they had, while a confident wrong `rus` in a report used to write a config costs them a season. Stem matching (`русск-` + any ending) would catch it and is deliberately not done — it reintroduces exactly the substring false positives the whole-word rule exists to prevent.

Three rules in it are load-bearing. **Longest-prefix-wins** on tier 1, or `Show - S01E01 - Title 2.srt` gets read as `…Title.mkv` plus the suffix `" 2"` whenever both files exist. **Tier 2 (episode number) requires a unique claimant** — two main files for one episode makes the match ambiguous, and ambiguous means unmatched, never guessed. **Variant identity is `(leaf directory, raw suffix)`** with same-named leaves merged, which is what turns `Rus sound/[MC-Ent]` + `Rus subs/[MC-Ent]` into one dub group; the merge is undone automatically when one episode would end up with two files of the same kind from different directories. Suffixes are matched **raw** and only trimmed for display — trimming for identity would fuse `[x]` and `(x)`, and renaming needs the original bytes.

`yaml.groovy` owns one thing: `loadMapping(file, opts)` → `[value, problem]`, the four-way-shared "read this file, get a mapping or a named reason why not" behind `config.yaml` and `episodes.yaml` in both scripts. **It classifies, it does not decide** — `problem` is a bare uncapitalised fragment the caller finishes in its own words and prints through its own `ui.error`/`ui.warn`, because what to do about an unusable file is exactly what differs between muxing and reporting. `opts.charset` exists so `episodes.yaml` can pass explicit UTF-8 while `config.yaml` keeps Groovy's auto-detection; **do not unify those** (see the charset contract below). `opts.transform` runs inside the guard, which is the only reason `normalizeYaml` can throw safely.

`subst.groovy` owns the substitution engine: `FILE_VARS`/`TRACK_VARS`, `substitute`, the memoized `fileVarsFor`, `trackVarsFor`, `friendlyCodec` and the codec tables, the JDK/CLDR language-name lookups, `collectTemplateFields(config)` and the fatal stage-one `validateTemplates` (which returns which variables the config actually uses, so everything derived from them stays gated). Probing stays with the caller — `trackVarsFor` takes an already-probed track — because the probe caches live in the scripts.

`episodes.groovy` also owns `batchLabels`/`formatRanges` and the `membershipLabel` that composes them, the **display-only** numbering behind those membership lines. The composition lives there rather than in each caller because `inspect.groovy`'s report and `mux.groovy`'s pre-flight are the same report and must render a group identically; each script keeps only the two-line `membershipFor` that splits base names (commons-io is the caller's) and passes `ui.pluralize` in. `batchLabels` prefers `SxxEyy` and otherwise anchors on the batch's longest common prefix **with trailing digits trimmed** — the trim is what stops 10-19 collapsing to 0-9 (their common prefix ends mid-number) and what preserves 01-09's padding, and the anchoring is what makes bare numbers safe at all, since `1080p` and `x264` sit inside the shared prefix. It is deliberately **not** `parseSeasonEpisode`: it is batch-relative, so it cannot answer "which episode is this file" for a single file, and nothing that renames or resolves `${episodeNum}` may use it. Real numbering for unrenamed releases is the opt-in job in `ROADMAP.md`.

`episodes.groovy` owns the episode-metadata *shape*, not its I/O: `parseSeasonEpisode` (the shared `s(\d\d)\.?e(\d\d)` regex, the join key between a file and its episode — `rename.groovy` delegates to it rather than keeping its own copy), `parseCanonicalName` (the inverse of rename's `Show - SxxEyy - Title[suffix]` output), `sanitizeForFilename`, `indexFromLines`, and `normalizeYaml`. **`normalizeYaml` takes an already-parsed map, never a file** — the no-imports rule means snakeyaml cannot be imported here, so callers own the reading and parsing and this file owns the semantics. That seam is deliberate; do not "fix" it by adding a `@Grab`.

`output.groovy` owns the palette — red 31 errors/failure summaries, green 32 success (`*** Done`, clean summaries, `[PASS]`), yellow 33 warnings + the check report's differing-cell highlight, cyan 36 section/file/table-column headers, gray 90 de-emphasis of the check report's file-evidence lists (never an accent) — plus the gating (`always`, or `auto` on a terminal without `NO_COLOR`; anything else is `never`) and the shared message forms: `error(msg)`/`warn(msg)` print `*** Error: `/`*** Warning: ` to **stderr**; `header`/`success` print to stdout; `progress(label, total[, opts])` is the probing meter; `plural(n, noun[, plural])` and `pluralize(n, noun[, plural])` live here too. **Counts are always spelled out properly — no `file(s)` anywhere.** Every count these scripts print knows its own number, so the form-letter `(s)` buys nothing and costs readability; the optional third argument covers what does not simply take an `-s` (`plural(n, 'discrepancy', 'discrepancies')`), and `pluralize` returns the noun alone for a sentence that has already printed the count. The terminal probe calls `Console.isTerminal()` where it exists (JDK 22+ returns a Console even when piped), falling back to the plain null-check on JDK 11/21. It gates **two** things now: colour, and which of `progress`'s renderings is used — an in-place `` bar on a terminal, appended dots anywhere else, because `` erases nothing in a file or a pipe and every frame would be retained (the harness captures through a pipe, so the smear would land in assertions). The bar is ASCII for the same Windows-codepage reason the file lists are, and dots are emitted per slice of the total rather than per file so a 200-file batch cannot wrap the terminal. `opts.interactive` overrides the probe, which is the only way the bar path is testable at all.

**The colour invariant: escapes wrap whole lines or whole pre-padded table cells, never a fragment inside a phrase.** The suite asserts with substring `contains(...)` on captured output, so a mid-phrase escape breaks tests (and reads as flicker anyway); padding before colour is what keeps the check table aligned. The one sanctioned two-segment line is `printMinority`'s marker row: the `<-` stays default-foreground while the file name after it is gray — still whole segments, and the pinned substrings (`<-`, the file name) each stay contiguous. Tests run the scripts through a pipe, so `auto` colour is off in every test — only the `--color always` cases (82–84) ever see escapes.

Placement follows the closure-scoping rule below: in `mux.groovy` the `ui` load is the first script-body statement, because closures capture it at definition time. In `propedit.groovy` the load sits after the usage/early-return block so a bare `-h` stays instant.

## `mux.groovy` internals

`mux.groovy` is the core script. It reads `config.yaml` from the CWD (or `--config <path>`), discovers all files in the current directory matching `allowedExtensions`, and for each file constructs and executes an `mkvmerge` command.

**Critical pattern:** all helpers in `mux.groovy` must be closures (`def foo = { ... }`), not methods (`def foo() { ... }`). Groovy methods on a Script class cannot access `def`-declared local variables from the script body — closures can because they capture their enclosing scope. `buildCommandLine` is defined as a closure for this reason.

The script uses picocli via `@PicocliScript2` (like `rename.groovy` and `fetch_episodes.groovy`). Annotations and `@Field` declarations must all precede the first script-body statement; the closure pattern above still applies to everything after them. This combination is verified on both Groovy 3/JDK 11 and Groovy 5/JDK 21 — `@GrabConfig(systemClassLoader = true)` now covers snakeyaml as well, which is the part most likely to break on a JDK change.

**The `properties` trap:** when reading `mkvmerge -J` output, the per-track JSON key `properties` must be accessed as `track.get('properties')`. On Groovy 4+ both `track.properties` and `track['properties']` resolve to the bean properties of the map object itself, silently returning the wrong thing. This applies to `identifyFile` in `inspect.groovy`, `trackSignature`/`probeFile` in `check.groovy`, `friendlyCodec` in `subst.groovy`, and the test harness.

**`formatFileList` must be declared before every closure that calls it.** A closure captures a script-body `def` local through its enclosing scope at *definition* time, so a helper closure has to already exist when the calling closure is created, not merely when it runs. `formatFileList` now lives in `check.groovy` and is aliased right after the engine load, because the substitution stage-two pre-flight and the companion pre-flight both call it. This is the same closure-scoping rule as the "helpers must be closures" note above, one step further: order matters among the closures too.

**File masks do their own glob expansion**, identically in `mux.groovy` and `inspect.groovy` (`compileMasks` is duplicated deliberately — it is ten lines of CLI plumbing, not domain logic). Positional `FILE` arguments and `--exclude` patterns filter the file list in every mode. Unix shells expand `*.mkv` before Groovy sees it, but `cmd.exe` passes the literal string through, so `compileMasks` expands patterns itself via `FileSystems.getDefault().getPathMatcher("glob:…")`, matching against the bare file name. A pattern that names an existing file is matched literally instead — otherwise a file called `Odd[1].mkv` could not be selected at all, since as a glob that also matches `Odd1.mkv`. Getting this wrong passes a Linux-only CI and fails on Windows, which is why tests 39–45 pass patterns through `ProcessBuilder` (no shell, so nothing pre-expands them). The file list is also sorted by name now, so batches process in a predictable order.

Lazy GString closures (`${-> fileName}`) are used throughout `buildCommandLine` so that `fileName` and `extension` are evaluated at command execution time, not at closure definition time.

**Substitution variables.** Every templated config value (`general.title`, the three kinds of track `title`, and `additionalSources[].file`) goes through one engine, including the `${fileName}` placeholder in companion paths. Keep it that way: resolution done inline at a use site is a second implementation that will drift from the validated variable set.

Placement: the `subst.groovy` load sits after the `check.groovy` load in both scripts, because `${codec}` resolution needs `probeFile`. `probedInfos` (the probe cache) is declared up there too, far above the loop that fills it, for the same closure-capture-at-definition-time reason. In `mux.groovy` the engine is bound to `substEngine`, **not** `subst`, because `buildCommandLine` already declares a local `subst` closure and a script-level `def subst` makes that a duplicate-variable compile error — the same class of collision as `probedInfos` not being called `infos`.

Two-stage validation, and the split matters:

- **Stage one is config-static and fatal (exit 2)**, running in every mode before anything is probed. An unknown variable name, a track variable in a file-scope field, a malformed body (`${var:modifier}` — caught by scanning with a deliberately looser `\$\{[^}]*\}` so it cannot survive as a literal), or a `language` with no display name. A typo would otherwise be stamped into the track names of a whole season.
- **Stage two is per-file and drops** (`--strict` aborts): a valid variable with no data for one episode. That is data-shaped — TheMovieDB missing episode 25, a stray un-renamed file — and matches the companion pre-flight's philosophy, which it sits directly beside and shares `formatFileList` with. Both run before `mkdirs()`.

Resolution is **eager** inside `buildCommandLine`, which now takes the `File`: the closure is already re-invoked per file after `fileName` is set, so no lazy-GString machinery is needed for substituted values. The output path and main-source path keep their `${-> fileName}` lazies untouched.

`${languageName}`/`${languageNative}` come from the JDK's CLDR data via `Locale`, keyed by both the two-letter codes config.yaml uses and the three-letter codes Matroska carries, plus the ~20 ISO 639-2/B bibliographic codes that differ from /T (`ger`, `fre`, `dut`, …). Native names are upper-cased with the locale's own rules, since many languages spell their own name in lower case (`русский` → `Русский`).

**`${codec}` keys on `properties.codec_id`, not on mkvmerge's `codec` display string.** The display string is not stable across versions — mkvmerge v99 reports `AVC/H.264/MPEG-4p10` where older releases report the components in the opposite order — and CI runs both a distro mkvtoolnix and the newest release, so a display-keyed map would resolve differently per leg. Second tier is a small display-string map, needed because a raw non-Matroska companion has **no `codec_id` at all** (a bare `.ass` probes with an empty `codec_id` and only `SubStationAlpha`); third tier is the raw display string, so unmapped codecs degrade rather than break. Probing for `${codec}` is gated on the config actually using it and reuses `probedInfos`, so a run without it costs no extra subprocesses.

The check prints templates **unresolved** on purpose (`configTitleFor`): the report is batch-level and cannot resolve a per-file variable, and its job there is to identify which config entry a finding refers to.

## `inspect.groovy` internals

Everything that reports rather than muxes. It reuses `check.groovy` (probing, the report), `subst.groovy` (resolving configured source paths per episode) and `output.groovy`, and owns `identifyFile` plus the mode plumbing. The same closure rules apply as in `mux.groovy`.

**Mode selection:** `wantCheck = checkOnly || checkVerbose || !identifyOnly` — a bare run checks, `--identify` alone identifies, naming both runs both off one scan. There is no "`--check-verbose` implies `--check`" special case any more; verbose is simply a report modifier here, since nothing else could happen instead.

**A config is optional throughout, and nothing about one can stop the script.** `config` may be `null`: `allowedExtensions` falls back to `DEFAULT_EXTENSIONS`, `mkvmergeExe` to PATH auto-detect, `makeSelection(null)` yields empty selections, and every other access is null-safe (`config?.…`). Missing, empty, malformed YAML, parsed-but-not-a-mapping, or valid with a template typo — each is counted into `configProblems`, reported through `ui.warn`, and the run continues. **`--strict` is the only path to a non-zero exit** (`blockingCount > 0 || configProblems > 0`), because it is the caller saying "treat what you found as a failure".

That rule is load-bearing, not politeness: a stale `config.yaml` in the directory must not stand between the user and the track table, least of all when reading that table is how they would fix the config. It is the same principle as an unreadable media file being a line in the report rather than an abort. `mux.groovy` keeps the opposite policy for the same reason it always had — it cannot mux against a config it did not understand — so `validateTemplates` now only *diagnoses* and returns a `problems` count, and each caller decides: mux exits 2, inspect warns.

**`--identify` also lists configured sources.** When a config is available it resolves each `additionalSources[].file` for that episode through the substitution engine and prints the resolved path plus a probed sub-table. Never fatal: a source that is not there prints `(not found)`, because `--identify` describes what exists rather than asserting it. The LANG cell falls back to `-` since raw `.ass`/`.srt` files carry no language at all.

**External-file discovery** runs once per process (`runDiscovery`, memoized) over every media file in the directory — **pre-mask on purpose**: matching against the masked list would dump the rest of the season's dubs into "unmatched", the opposite of what a mask asks for. What the masks narrow is what gets *displayed*. `destinationDir` is excluded by canonical path, because muxed output carries its sources' base names and would otherwise come back as an external file of itself.

Probing discovered files is gated on `PROBE_EXTENSIONS` and cached by absolute path (`probeExternal`). Probed values win **field by field**: an `.mka` with a language but no track name shows the real language and an empty name, never a guessed one — except that **`und` counts as missing**, since Matroska has no other spelling for "untagged" and untagged dubs are the common case here. A guessed language renders as `rus?` in a **whole pre-padded cell** wrapped in `ui.gray` — the sanctioned form of the colour invariant, and the tests pin the bare `rus?` substring on piped output. The `--check` report grays it too, via the `guessed` flag carried on the slot signature into `check.groovy`'s `langCell` (test 127 pins the escape under `--color always`); the two views and the palette doc now agree. Unprobed files report track id **0**, which is what mkvmerge calls their single track and therefore what an `additionalSources` entry must name; dashing it out cost the reader the one number they came for.

Per-episode blocks are grouped **by variant, not by file**: a merged variant contributing a dub and a subtitle file to the same episode is one block with `(.mka, .ass)` in its header. Everything on the page — main tracks, configured sources, discovered files — shares one column grid at a two-space indent, so the columns line up straight down the report instead of each block starting a little table of its own.

**External files are slots in the layout, not a section of their own.** `externalSlotsFor` turns each episode's discovered files into the `externals` map `check.groovy` groups by, keyed `label/typeClass/extension`. The extension is in the key because one variant can hand a single episode two files of the same kind (`.ass` *and* `.srt` for E01, only `.ass` for E02): keyed on the kind alone they collide, the second silently overwrites the first, and the two episodes come out as one pass while the legend correctly says one of them holds two files. It also separates a variant that switched format mid-season, which is a real second pass. This replaced a separate coverage report that listed per-variant episode ranges: the ranges were unions, and the question — how many muxing passes, which files in each — needs the per-episode intersection, which the reader was left to compute. Grouping does it for them. External differences are never blocking (nothing selects an external file by ID) but they do split groups, which is the point.

**Consistency check.** Compares track structure across the whole batch. One `mkvmerge -J` per file via `probeFile`, shared by `--identify` and the check so combining them does not double the subprocesses (`identifyFile` takes a probed record rather than shelling out itself). A live `*** Reading N files...` tick prints during probing, since that is seconds of silence on a slow share. The header is `opts.headerLabel`: `Consistency check` here, `Pre-flight check` from `mux.groovy`.

The report works in **two layers**, which is the key design decision and came out of a real mixed-layout season (some episodes had the subtitle track first). First it groups files by **layout** (`layoutKey` — the type at each ID), printed largest group first as `*** Layout N (K files): <type sequence>` (the header appears only when there is more than one layout). A per-ID comparison alone put the same shifted file at three different IDs and was unreadable. Then, within each group, `groupTracks` compares **values** per ID — codec, language, name, default, forced (`SIG_KEYS`); the video track's name is nulled *at construction* in `trackSignature` so it can never leak into a group key. Grouping ranks by population and never anchors on the first file. Because the value table runs per layout, every ID has one type there, so the `(absent)`/`tg.missing` branches are dead for that set (a missing track is a whole different layout) — kept only as defensive guards.

Output specifics that were hard-won in review: the differing cell is highlighted (`hl`/`cell`, ANSI yellow) under `--color`, with the gating shared via `output.groovy` (`auto` = on only on a real terminal, so piped/CI/test output is plain and never breaks an assertion); the NAME column is the last column and dynamically sized to the longest name (`nameWidth`, clamped 12–60), so no trailing padding and no clipping; there is no FILES count column — the `<-` lists (one file name per line via `formatFileList`) carry that. In the largest group the strict-majority row is the unnamed reference and only minorities are named; an outlier group has no reference (all files named — a uniform one lists them above its table, a split one names each value's files). `formatFileList` must clamp `take` to `names.size()` because `--check-verbose` passes `limit = Integer.MAX_VALUE` and `take(MAX)` allocates a 2-billion-element array (OOM).

`findDuplicates` flags only genuinely ambiguous same-language tracks (type+language+codec+name all equal). Classification: a layout outlier is blocking when it changes the type at a selected ID or drops one; a per-ID value discrepancy is blocking via `isBlocking` (selected ID *and* `copiesAllOfType` false). `--strict` exits 2 on any blocking finding; default warns and continues.

**`mkvmerge -J` exits 0 on a file it cannot read** — it signals failure through `container.recognized`/`container.supported`, not the exit code. `probeFile` checks those; relying on the exit code alone would let a corrupt file into the comparison as a file with zero tracks, which reads as "every track is absent here" and poisons the whole report.

**Companion pre-flight.** Before muxing (and before `--dry-run` previews), every `additionalSources` path is resolved per episode and checked for existence. Episodes with missing companions are reported and dropped from the batch; the rest still mux. This never aborts — those episodes would have failed in mkvmerge anyway, and a partially-released dub is a normal situation. The check runs before the `destinationDir` `mkdirs()`, so a fully-blocked batch leaves no empty output directory behind. `formatFileList` (one file name per line, truncated past a limit, ASCII only — this output reaches Windows consoles on legacy codepages) lives in `check.groovy` and is shared with the report.

## `rename.groovy` and `--external`

`--external` renames the external files that belong to each episode along with it, wherever they live. Off by default.

The order is what makes it work: **discovery runs before the plan is built**, since it needs only the file list, and its answer decides which files the ordinary pass must leave alone. A tier-1 match in the media directory itself is claimed by the external rule rather than the legacy one, which is strictly better there too — `parseSuffix` keeps only a trailing `[...]`, so `Show.S01E01.rus.srt` would lose its `.rus` and collide with its own English sibling.

The new name is the main file's new base name plus **the file's own suffix, verbatim**, and its own extension; the directory never changes, because the directory *is* the variant's identity. No normalising, no suffix derived from the folder name — that belongs with the step-4 normalisation work in `ROADMAP.md`, and mixing it in here would make `--dry-run` output much harder to trust. Tier-2 (episode-number) matches are reported and skipped: with no name relation there is no "same suffix" to preserve.

Two invariants had to stretch across the tree rather than the directory: the duplicate-detection key is now `(parent directory, new name)` — two files in different directories renaming to the same name is not a collision — and the refuse-all-on-any-problem rule now covers external targets too, so one taken name anywhere aborts the whole batch, main files included. Plan entries carry `relPath` when they are external, which is what the dry-run and the per-file headers print: the path says which directory's copy is being renamed, while the target stays a bare name.

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
| `general.title` | Optional segment (container) title, templated. Omit for the current default, the file name. Distinct from `mainSource.videoTrack.title` — each defaults to the file name independently |
| `general.allowedExtensions` | Which file types to treat as main sources |
| `mainSource.videoTrack` | Language and optional title override for the video track |
| `mainSource.audioTracks[]` | List of audio track IDs to include, with language/title/default |
| `mainSource.subtitleTracks[]` | List of subtitle track IDs; `charset` is optional |
| `additionalSources[]` | Extra files (audio, subtitle) to mux in; track ID is always 0 |
| `trackOrder` | Optional `"sourceIndex:trackId"` CSV controlling output track order. Omit it to derive the order from the configured tracks (video, audioTracks in listed order, subtitleTracks, then one entry per additional source). When set explicitly it is validated against the configured tracks and mismatches are warned about, never fatal — mkvmerge itself silently ignores nonexistent IDs |

Omitting `audioTracks` or `subtitleTracks` (or setting them to `[]`) causes no tracks of that type to be copied.

## Platform notes

The scripts are cross-platform: `mkvmerge` and `mkvpropedit` are resolved from PATH, with a fallback to the default Windows install location (`C:\Program Files\MKVToolNix\`). The project originated on Windows, which shows in a few places: the sample `mkvmergeExe` value in `config.example.yaml` uses a Windows path, `fetch_episodes.groovy` strips characters invalid in Windows file names, and `to_utf8.groovy` defaults its source encoding to Windows-1251 (though `--encoding` accepts any charset). CI runs the test suite on Linux.
