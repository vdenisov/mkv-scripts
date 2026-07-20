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
groovy src/fetch_episodes.groovy --show-id 2260 --season 1  # Fetch episode names from TheMovieDB
groovy src/rename.groovy "Show Name" [episodeOffset]        # Batch-rename files
groovy src/filename_to_title.groovy                         # Set MKV segment title/track name from filename
groovy src/fix_srt.groovy                                   # Validate and reformat SRT files
groovy src/to_utf8.groovy                                   # Convert SRT from windows-1251 → UTF-8
groovy src/find_unused_fonts.groovy                         # Find unused fonts referenced in ASS subtitles
groovy src/propedit.groovy                                  # Batch mkvpropedit — fix properties without remuxing
```

Each script also has a wrapper in `bin/` (`mkv-mux`, `mkv-fetch-episodes`, `mkv-rename`, `mkv-filename-to-title`, `mkv-propedit`, `mkv-to-utf8`, `mkv-fix-srt`, `mkv-find-unused-fonts`) so it can be invoked from any directory once `bin/` is on `PATH`. Mapping rule: strip the `mkv-` prefix, hyphens become underscores, add `.groovy`.

`fetch_episodes.groovy` reads the API key from `src/apikey.txt` if `--api-key` is not supplied.

`propedit.groovy` is a generic wrapper that runs `mkvpropedit` in a loop over all MKV files in the current directory — it can fix any property (track names, forced/default flags, etc.) without remuxing. The command line inside the script is expected to be adjusted per task; as committed, it clears the forced flag on the second audio track.

## Running tests

```bash
groovy src/test/run_tests.groovy              # Run all 26 tests
groovy src/test/run_tests.groovy --filter 01  # Run a single test by name fragment
groovy src/test/run_tests.groovy --keep       # Preserve src/test/work/ for inspection after run
```

Tests use `src/test/test.mkv` as the input fixture (1 video, 6 audio, 10 subtitle tracks). The harness stages files into `src/test/work/<case>/`, writes a tailored `config.yaml`, runs `mux.groovy` as a subprocess, and asserts on the output via `mkvmerge -J`.

## External dependencies (must be installed separately)

- **Groovy 3 or newer** (Java 11+) — the runtime for all scripts; CI tests both Groovy 3 and Groovy 5, plus a weekly leg against the newest MKVToolNix release
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

Lazy GString closures (`${-> fileName}`) are used throughout `buildCommandLine` so that `fileName` and `extension` are evaluated at command execution time, not at closure definition time.

`additionalSources` entries support a `${fileName}` placeholder that resolves to the base filename of the current main source file, enabling per-episode companion files like `${fileName}[Studio].mka`.

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
