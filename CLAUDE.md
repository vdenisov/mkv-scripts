# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A Groovy toolkit for automating MKV video file workflows — primarily for TV show episodes. Scripts are run individually from the command line; there is no build step or package manager beyond `@Grab` annotations. A self-contained test harness lives in `src/test/`.

## Running scripts

All scripts operate on the current working directory — in practice the directory containing the media files. `mkv.groovy` looks for `config.yaml` in the CWD first (per-show config next to the media files), then falls back to the `config.yaml` next to the script itself. Only the test suite is run from the repo root:

```bash
groovy src/mkv.groovy                                          # Main muxer — reads config.yaml (CWD, then script dir)
groovy src/fetch_episodes.groovy --show-id 2260 --season 1    # Fetch episode names from TheMovieDB
groovy src/renamer.groovy "Show Name" [episodeOffset]         # Batch-rename files
groovy src/filename_to_title.groovy                            # Set MKV segment title/track name from filename
groovy src/srtfixer.groovy                                     # Validate and reformat SRT files
groovy src/to_utf8.groovy                                      # Convert SRT from windows-1251 → UTF-8
groovy src/find_unused_fonts.groovy                            # Find unused fonts referenced in ASS subtitles
groovy src/prop.groovy                                         # Batch mkvpropedit — fix properties without remuxing
```

`fetch_episodes.groovy` reads the API key from `src/apikey.txt` if `--api-key` is not supplied.

`prop.groovy` is a generic wrapper that runs `mkvpropedit` in a loop over all MKV files in the current directory — it can fix any property (track names, forced/default flags, etc.) without remuxing. The command line inside the script is expected to be adjusted per task; as committed, it clears the forced flag on the second audio track.

## Running tests

```bash
groovy src/test/run_tests.groovy              # Run all 24 tests
groovy src/test/run_tests.groovy --filter 01  # Run a single test by name fragment
groovy src/test/run_tests.groovy --keep       # Preserve src/test/work/ for inspection after run
```

Tests use `src/test/test.mkv` as the input fixture (1 video, 6 audio, 10 subtitle tracks). The harness stages files into `src/test/work/<case>/`, writes a tailored `config.yaml`, runs `mkv.groovy` as a subprocess, and asserts on the output via `mkvmerge -J`.

## External dependencies (must be installed separately)

- **Groovy 3 or newer** (Java 11+) — the runtime for all scripts; CI tests both Groovy 3 and Groovy 5, plus a weekly leg against the newest MKVToolNix release
- **MKVToolNix** — `mkvmerge` is auto-detected from PATH (optionally overridden via `general.mkvmergeExe` in `config.yaml`); `mkvpropedit` is invoked from PATH by `filename_to_title.groovy` and `prop.groovy`
- JVM library dependencies are declared via `@Grab` annotations inside each script and fetched automatically on first run

## Architecture and workflow

Scripts form a sequential pipeline:

```
TheMovieDB API
  └─ fetch_episodes.groovy → src/episodes.txt
       └─ renamer.groovy   → renamed files (Show - SxxEyy - Title.ext)
            └─ mkv.groovy (+ config.yaml) → mkvmerge → output MKV in destinationDir/
                 └─ post-processing utilities:
                      filename_to_title.groovy  (embed metadata)
                      prop.groovy               (fix track flags)
                      to_utf8.groovy            (encoding fixes)
                      srtfixer.groovy           (subtitle repair)
                      find_unused_fonts.groovy  (font cleanup)
```

## `mkv.groovy` internals

`mkv.groovy` is the core script. It reads `config.yaml` (CWD first, then the script's own directory), discovers all files in the current directory matching `allowedExtensions`, and for each file constructs and executes an `mkvmerge` command.

**Critical pattern:** all helpers in `mkv.groovy` must be closures (`def foo = { ... }`), not methods (`def foo() { ... }`). Groovy methods on a Script class cannot access `def`-declared local variables from the script body — closures can because they capture their enclosing scope. `buildCommandLine` is defined as a closure for this reason.

Lazy GString closures (`${-> fileName}`) are used throughout `buildCommandLine` so that `fileName` and `extension` are evaluated at command execution time, not at closure definition time.

`additionalSources` entries support a `${fileName}` placeholder that resolves to the base filename of the current main source file, enabling per-episode companion files like `${fileName}[Studio].mka`.

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
| `trackOrder` | `"sourceIndex:trackId"` CSV controlling output track order — must be kept in sync manually with the IDs listed above it; mkvmerge silently ignores nonexistent IDs |

Omitting `audioTracks` or `subtitleTracks` (or setting them to `[]`) causes no tracks of that type to be copied.

## Platform notes

The scripts are cross-platform: `mkvmerge` and `mkvpropedit` are resolved from PATH, with a fallback to the default Windows install location (`C:\Program Files\MKVToolNix\`). The project originated on Windows, which shows in a few places: the sample `mkvmergeExe` value in `config.yaml` uses a Windows path, `fetch_episodes.groovy` strips characters invalid in Windows file names, and `to_utf8.groovy` exists specifically for Windows-1251-encoded subtitle sources. CI runs the test suite on Linux.
