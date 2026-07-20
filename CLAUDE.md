# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A Groovy toolkit for automating MKV video file workflows — primarily for TV show episodes. Scripts are run individually from the command line; there is no build step or package manager beyond `@Grab` annotations. A self-contained test harness lives in `src/test/`.

## Running scripts

All scripts operate on the current working directory — in practice the directory containing the media files. `mux.groovy` looks for `config.yaml` in the CWD first (per-show config next to the media files), then falls back to the `config.yaml` next to the script itself. Only the test suite is run from the repo root:

```bash
groovy src/mux.groovy                                       # Main muxer — reads config.yaml (CWD, then script dir)
groovy src/mux.groovy --identify                            # Print a track table per file, mux nothing
groovy src/mux.groovy --dry-run                             # Print the mkvmerge command per file, run nothing
groovy src/mux.groovy "Show.S01E0[12].mkv"                  # Operate on a subset: file names or globs, repeatable
groovy src/mux.groovy --exclude "*.sample.mkv"              # Skip files matching a pattern, repeatable
groovy src/fetch_episodes.groovy --show-id 2260 --season 1  # Fetch episode names from TheMovieDB
groovy src/rename.groovy "Show Name" [episodeOffset]        # Batch-rename files (--dry-run to preview)
groovy src/filename_to_title.groovy                         # Set MKV segment title/track name from filename
groovy src/fix_srt.groovy                                   # Validate and reformat SRT files
groovy src/to_utf8.groovy                                   # Convert SRT from windows-1251 → UTF-8
groovy src/find_unused_fonts.groovy                         # Find unused fonts referenced in ASS subtitles
groovy src/propedit.groovy                                  # Batch mkvpropedit — fix properties without remuxing
```

Each script also has a wrapper in `bin/` (`mkv-mux`, `mkv-fetch-episodes`, `mkv-rename`, `mkv-filename-to-title`, `mkv-propedit`, `mkv-to-utf8`, `mkv-fix-srt`, `mkv-find-unused-fonts`) so it can be invoked from any directory once `bin/` is on `PATH`. Mapping rule: strip the `mkv-` prefix, hyphens become underscores, add `.groovy`.

`fetch_episodes.groovy` reads the API key from `src/apikey.txt` if `--api-key` is not supplied.

`propedit.groovy` is a generic wrapper that runs `mkvpropedit` in a loop over all MKV files in the current directory — it can fix any property (track names, forced/default flags, etc.) without remuxing. All arguments are passed through verbatim, with the file name inserted first; no source editing is needed per task. It deliberately does not use picocli, which would try to parse the passthrough options as its own. `-h`/`--help` is intercepted only when it is the sole argument.

**Exit-code asymmetry:** `propedit.groovy` exits 1 if any file failed, so it is usable from a shell script. `mux.groovy` deliberately keeps its continue-on-error, always-exit-0 behaviour (a test depends on it, and a partially-successful batch mux is a normal outcome there).

## Running tests

```bash
groovy src/test/run_tests.groovy              # Run all 49 tests
groovy src/test/run_tests.groovy --filter 01  # Run a single test by name fragment
groovy src/test/run_tests.groovy --keep       # Preserve src/test/work/ for inspection after run
```

Tests use `src/test/test.mkv` as the input fixture: track 0 video (und), 1–3 audio (jpn/eng/rus), 4–6 subtitles (eng/rus-forced/jpn). The harness stages files into `src/test/work/<case>/`, writes a tailored `config.yaml`, runs the script under test as a subprocess via the `runScript` closure, and asserts on the output via `mkvmerge -J`.

Harness conventions worth knowing before adding a case:

- `cfg(...)` builds a `config.yaml` string from a map; omitting `trackOrder` omits the key entirely, which is how the derivation tests work.
- Tests that need `mkvpropedit` check `mkvpropeditExe` and skip themselves (printing a note) when it is absent, rather than failing.
- The wrapper smoke test needs a bare `groovy` on `PATH`, which the harness itself does not require — it skips when that is unavailable. On Windows the probe must go through `cmd`, since `groovy` is a `.bat` that `ProcessBuilder` cannot launch directly.
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

## `mux.groovy` internals

`mux.groovy` is the core script. It reads `config.yaml` (CWD first, then the script's own directory), discovers all files in the current directory matching `allowedExtensions`, and for each file constructs and executes an `mkvmerge` command.

**Critical pattern:** all helpers in `mux.groovy` must be closures (`def foo = { ... }`), not methods (`def foo() { ... }`). Groovy methods on a Script class cannot access `def`-declared local variables from the script body — closures can because they capture their enclosing scope. `buildCommandLine` is defined as a closure for this reason.

The script uses picocli via `@PicocliScript2` (like `rename.groovy` and `fetch_episodes.groovy`). Annotations and `@Field` declarations must all precede the first script-body statement; the closure pattern above still applies to everything after them. This combination is verified on both Groovy 3/JDK 11 and Groovy 5/JDK 21 — `@GrabConfig(systemClassLoader = true)` now covers snakeyaml as well, which is the part most likely to break on a JDK change.

**The `properties` trap:** when reading `mkvmerge -J` output, the per-track JSON key `properties` must be accessed as `track.get('properties')`. On Groovy 4+ both `track.properties` and `track['properties']` resolve to the bean properties of the map object itself, silently returning the wrong thing. This applies to `identifyFile` in `mux.groovy` as well as the test harness.

**File masks do their own glob expansion.** Positional `FILE` arguments and `--exclude` patterns filter the file list in all modes. Unix shells expand `*.mkv` before Groovy sees it, but `cmd.exe` passes the literal string through, so `compileMasks` expands patterns itself via `FileSystems.getDefault().getPathMatcher("glob:…")`, matching against the bare file name. A pattern that names an existing file is matched literally instead — otherwise a file called `Odd[1].mkv` could not be selected at all, since as a glob that also matches `Odd1.mkv`. Getting this wrong passes a Linux-only CI and fails on Windows, which is why tests 39–45 pass patterns through `ProcessBuilder` (no shell, so nothing pre-expands them). The file list is also sorted by name now, so batches process in a predictable order.

Lazy GString closures (`${-> fileName}`) are used throughout `buildCommandLine` so that `fileName` and `extension` are evaluated at command execution time, not at closure definition time.

`additionalSources` entries support a `${fileName}` placeholder that resolves to the base filename of the current main source file, enabling per-episode companion files like `${fileName}[Studio].mka`.

**Companion pre-flight.** Before muxing (and before `--dry-run` previews, but not `--identify`), every `additionalSources` path is resolved per episode and checked for existence. Episodes with missing companions are reported and dropped from the batch; the rest still mux. This never aborts — those episodes would have failed in mkvmerge anyway, and a partially-released dub is a normal situation. The check runs before the `destinationDir` `mkdirs()`, so a fully-blocked batch leaves no empty output directory behind. `formatFileList` wraps and truncates the name lists (ASCII only — this output reaches Windows consoles on legacy codepages); `--check` in item 3 reuses it.

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

The scripts are cross-platform: `mkvmerge` and `mkvpropedit` are resolved from PATH, with a fallback to the default Windows install location (`C:\Program Files\MKVToolNix\`). The project originated on Windows, which shows in a few places: the sample `mkvmergeExe` value in `config.yaml` uses a Windows path, `fetch_episodes.groovy` strips characters invalid in Windows file names, and `to_utf8.groovy` exists specifically for Windows-1251-encoded subtitle sources. CI runs the test suite on Linux.
