# MKV Scripts

[![CI](https://github.com/vdenisov/mkv-scripts/actions/workflows/ci.yml/badge.svg)](https://github.com/vdenisov/mkv-scripts/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Groovy](https://img.shields.io/badge/groovy-3%2B-4298b8.svg)](https://groovy-lang.org/)

A small toolkit of Groovy scripts for remuxing TV shows and movies with
[MKVToolNix](https://mkvtoolnix.download/). It grew out of the repetitive grind of
processing episodes with a large number of tracks: picking the right audio and
subtitle tracks, setting languages, titles and default flags, merging external
dubs and subtitles, and naming everything consistently.

It is deliberately narrow in scope — each script does one thing, operates on the
current directory, and is small enough to read and adapt in a few minutes.

```
mkv-mux --identify        # what tracks does this file have?
mkv-mux --check           # are all the episodes structured the same?
mkv-mux --dry-run         # what would be muxed, exactly?
mkv-mux                   # do it
```

## Prerequisites

- **Java 11+** and **Groovy 3 or newer** — CI runs the test suite on both the
  minimum (Groovy 3 / JDK 11) and a current setup (Groovy 5 / JDK 21), and a
  weekly run additionally tests against the newest MKVToolNix release.
- **MKVToolNix** — `mkvmerge` is auto-detected from `PATH` (with a fallback to the
  default Windows install location); you can also set an explicit path in
  `config.yaml`. `mkvpropedit` (on `PATH`) is needed only for
  `filename_to_title.groovy` and `propedit.groovy`.
- Network access on first run — dependencies are declared via `@Grab` and
  downloaded automatically.
- A [TheMovieDB](https://www.themoviedb.org/) API key — only for
  `fetch_episodes.groovy`.

## Pipeline overview

The scripts form a loose pipeline. Only `mux.groovy` is essential; everything
else is optional tooling around it.

```mermaid
flowchart TD
    TMDB[TheMovieDB API] --> FETCH["fetch_episodes.groovy"]
    FETCH --> EP["episodes.txt"]
    EP --> REN["rename.groovy"]
    REN --> FILES["Show - SxxEyy - Title.ext"]
    FILES --> MKV["mux.groovy"]
    CFG["config.yaml"] --> MKV
    MKV --> MERGE[mkvmerge] --> OUT["muxed MKV in destinationDir"]
    OUT --> POST["post-processing utilities"]
```

## Command wrappers

`bin/` contains a thin wrapper per script — a `.bat` for Windows and an
extension-less shell script for Linux/macOS — so you can run everything from
whatever directory your media files are in, without copying the scripts around.

Add `bin/` to your `PATH` once:

```powershell
# Windows (persists for the current user)
setx PATH "%PATH%;C:\path\to\mkv-scripts\bin"
```

```bash
# Linux / macOS (add to your shell profile)
export PATH="$PATH:/path/to/mkv-scripts/bin"
```

Then, from any directory:

```
mkv-mux                              # instead of: groovy .../src/mux.groovy
mkv-rename "Show Name"
mkv-propedit --edit track:a2 --set flag-forced=0
```

| Command | Script |
|---------|--------|
| `mkv-mux` | `mux.groovy` |
| `mkv-fetch-episodes` | `fetch_episodes.groovy` |
| `mkv-rename` | `rename.groovy` |
| `mkv-filename-to-title` | `filename_to_title.groovy` |
| `mkv-propedit` | `propedit.groovy` |
| `mkv-to-utf8` | `to_utf8.groovy` |
| `mkv-fix-srt` | `fix_srt.groovy` |
| `mkv-find-unused-fonts` | `find_unused_fonts.groovy` |

Each wrapper locates its script relative to its own location, so `bin/` can live
anywhere — but add the directory to `PATH` rather than symlinking individual
wrappers elsewhere, as the shell wrappers do not resolve symlinks.

## Scripts

All scripts operate on the current working directory — run them from the
directory containing your media files (via the `bin/` wrappers above, or
`groovy <path-to-repo>/src/<script>.groovy`). `mux.groovy` reads `config.yaml`
from the current directory — a per-show config dropped next to the media files —
or from an explicit `--config <path>`. The repo ships `src/config.example.yaml`
as a template to copy and edit; it is never loaded automatically. Only the test
suite is run from the repo root:

| Script | Purpose |
|--------|---------|
| `mux.groovy` | The core muxer: builds and runs an `mkvmerge` command for every media file in the current directory, driven by `config.yaml`. `--identify` lists tracks, `--check` compares track structure across the batch, `--dry-run` prints commands without running them, and file names or globs (plus `--exclude`) narrow the batch. |
| `fetch_episodes.groovy` | Fetches episode names for a show/season from TheMovieDB and writes `episodes.txt`. |
| `rename.groovy` | Batch-renames files to `Show - SxxEyy - Title.ext` using `episodes.txt`. |
| `filename_to_title.groovy` | Sets the MKV segment title and video track name to the file name (via `mkvpropedit`). |
| `propedit.groovy` | Batch-runs `mkvpropedit` over every MKV in the current directory, passing your arguments through — fix any property (track names, forced/default flags, …) without a full remux. |
| `to_utf8.groovy` | Converts `.srt`/`.ass`/`.ssa`/`.vtt` subtitles to UTF-8 in place, from `--encoding` (default Windows-1251). Skips files that are already UTF-8; `--backup` keeps the originals. |
| `fix_srt.groovy` | Converts subtitles in a non-standard timing format into valid SRT (writes `<name>.srt.fixed`). |
| `find_unused_fonts.groovy` | Lists font files in `fonts/` that are not referenced by any `.ass` subtitle in the current directory. |

### fetch_episodes.groovy

```
groovy src/fetch_episodes.groovy --show-id 2260 --season 1 [--api-key KEY]
```

If `--api-key` is not supplied, the key is read from `apikey.txt` — the current
directory first, then the copy next to the script, so the key does not have to be
copied into every media directory. Episode names are written to `episodes.txt`,
one per line, with characters invalid in Windows file names stripped, along with
any trailing dots and spaces (which Windows also rejects). Endpoint examples live
in `src/themoviedb.http`.

Failures are reported rather than thrown: a bad key, an unknown show or a
nonexistent season prints TheMovieDB's own message and exits non-zero.
`episodes.txt` is written as UTF-8, so non-Latin titles survive.

### rename.groovy

```
groovy src/rename.groovy "Show Name" [episodeOffset]
groovy src/rename.groovy "Show Name" --dry-run    # preview, rename nothing
```

Renames every media/subtitle file whose name contains an `sXXeYY` pattern to
`Show Name - SXXEYY - <episode title>.<ext>`, taking titles from `episodes.txt`.
A trailing `[suffix]` in the original name (e.g. a dub studio tag) is preserved.
`episodeOffset` (default 1) maps the first line of `episodes.txt` to an episode
number.

The whole batch is checked before anything is renamed. If any file has no
`sXXeYY` pattern, has no matching title in `episodes.txt`, or would overwrite an
existing file or collide with another rename, every problem is listed and
nothing is touched. This matters because renaming removes the `sXXeYY` pattern
that ties a file to its episode, so a rename that failed halfway would have to
be untangled by hand.

`--dry-run` prints the planned `old -> new` pairs and exits.

### mux.groovy

```
groovy src/mux.groovy                          # check, then mux every matching file
groovy src/mux.groovy --identify               # list tracks per file, mux nothing
groovy src/mux.groovy --check                  # compare tracks across files, mux nothing
groovy src/mux.groovy --dry-run                # print the mkvmerge command per file, run nothing
groovy src/mux.groovy "Show.S01E0[12].mkv"     # only the files matching a pattern
groovy src/mux.groovy --exclude "*.sample.mkv" # everything except the files matching a pattern
```

Reads `config.yaml` (see [Configuration](#configuration) below), discovers all
files in the current directory matching `allowedExtensions`, and runs `mkvmerge`
for each one. Output goes to `destinationDir`. If a file fails, the error is
printed and processing continues with the next file.

#### Selecting a subset of the files

Positional arguments narrow the batch to the files you name; `--exclude` drops
files from it. Both accept an exact file name or a glob, both may be repeated,
and both apply in every mode — including `--identify` and `--dry-run`. With no
arguments the behaviour is unchanged: every file in the directory.

```
groovy src/mux.groovy Show.S01E03.mkv                        # one file
groovy src/mux.groovy Show.S01E01.mkv Show.S01E03.mkv        # several files
groovy src/mux.groovy "Show.S01E*.mkv" --exclude "*.sample.mkv"
```

Quote your patterns so the behaviour is identical across shells (the script
expands globs itself). A pattern that exactly names an existing file is taken
literally, so a file called `Odd[1].mkv` selects only itself rather than being
read as a character class. A pattern that matches nothing is reported, so a typo
does not look like a successful run with no work to do.

#### Companion file pre-flight

When `additionalSources` uses the `${fileName}` placeholder, every companion is
checked for existence before anything is muxed. Episodes whose companions are
missing are named and skipped; the rest of the batch is muxed normally.

```
*** 2 file(s) will be skipped: companion files are missing
      ${fileName}[Studio].mka  (missing for 2 file(s))
        Show.S01E13.mkv, Show.S01E14.mkv
```

A dub or subtitle release covering only part of a season is routine. Without the
check that surfaces as an mkvmerge failure partway through a long batch; with it,
the gaps are visible before the first mux and the complete episodes still get
processed. Those episodes would have failed anyway, so nothing is lost by
skipping them — this never aborts the run. If every episode is blocked, the run
says so instead of printing a bare `Done`.

#### Consistency check

`config.yaml` selects tracks by **numeric ID**, which quietly assumes every
episode in the directory has the same track layout. When that breaks — a
translation added mid-season, an old one dropped, a different release group
ordering tracks differently — mkvmerge does not complain; it muxes whatever sits
at that ID, and you discover the wrong dub at playback time.

The consistency check compares track structure across the whole batch and reports
it before muxing. It runs automatically (skip it with `--no-check`), or on its own
with `--check`, which muxes nothing and needs no `config.yaml` — so you can inspect
a season's structure before writing one. `--check` and `--identify` can be combined;
they answer different questions and share one `mkvmerge` scan.

It works in two layers. First it groups files by **track layout** — the type at
each ID. Files that share a layout are the same release and get one table; files
whose layout differs (a different track order, or a track missing) are a different
release and get their own, largest group first. This keeps a shifted-track-order
release from being smeared across the per-ID table, where the same file would
otherwise appear at every shifted ID. Then, within each group, it compares the
**values** at each ID — codec, language, name, default/forced flags — stacking a
row per distinct value and naming the files that carry it:

```
*** Layout 1 (20 files): video, audio, subs
    ID   TYPE   CODEC                LANG  DEF  FOR  NAME
    0    video  AVC/H.264/MPEG-4p10  eng   yes  no   -
    1    audio  AC-3                 eng   yes  no   (no name)
    2    subs   SubRip/SRT           eng   yes  no   English (SDH)
           <- Show.S01E16 - Episode Sixteen.mkv
    2    subs   SubRip/SRT           eng   no   no   English (SDH)

*** Layout 2 (2 files): subs, video, audio
              Show.S01E18 - Episode Eighteen.mkv
           <- Show.S01E20 - Episode Twenty.mkv
    ID   TYPE   CODEC                LANG  DEF  FOR  NAME
    0    subs   SubRip/SRT           eng   yes  no   (no name)
    1    video  AVC/H.264/MPEG-4p10  eng   yes  no   -
    2    audio  AC-3                 eng   yes  no   English (SDH)

*** 1 discrepancy affects a track that config.yaml selects:
      2 files use a different track layout, at selected tracks 0, 1, 2
*** 1 informational (does not affect what gets muxed):
      track 2 (subtitles, config title "Signs") - default differs across 2 groups
```

How to read it:

- **Structural groups, largest first.** Files with a different track order or a
  missing track form their own group, listed once — not once per shifted ID.
- **One row per distinct value, not per file** — a 200-episode batch is as compact
  as a 3-episode one. Every track is listed, so the table doubles as the map you
  check `config.yaml`'s IDs against. The `DEF`/`FOR` columns make a flag-only
  difference legible, and on a terminal the differing cell is highlighted.
- **The `<-` names only the files that deviate**, one per line; the majority is the
  unnamed reference.
- **Blocking vs informational.** A difference only corrupts output when it lands on
  a track the config selects by ID *and* not every track of that type is being
  copied. Everything else — unselected tracks, chapters, a type copied wholesale —
  is reported as informational.

What is compared: per layout, the type at each ID; then per ID, codec, language,
name and default/forced flags, plus chapter presence and genuinely ambiguous
same-language duplicates (two tracks that match on type, language, codec *and*
name, where ID selection cannot tell them apart). What is ignored: the video
track's title, which carries the episode name and differs by design, along with
duration, file size and muxing metadata.

By default the check **warns and continues** — muxing the wrong tracks is
recoverable, the source files survive. Pass `--strict` to abort (exit 2) when any
discrepancy affects a selected track. File lists are truncated to a few names;
`--check-verbose` prints them in full (and, like `--check`, muxes nothing).
Highlighting follows `--color` (`auto` by default: on at a terminal, off when
redirected).

`--identify` prints the track table you need in order to write the config — id,
type, codec, language, default/forced flags and track name for every track of
every matching file:

```
*** Show.S01E01.mkv
  ID   TYPE       CODEC                  LANG  DEF  FOR  NAME
  0    video      AVC/H.264/MPEG-4p10    und   no   no   Video
  1    audio      AAC                    jpn   yes  no   Audio A
  2    audio      AAC                    eng   no   no   Audio B
  4    subtitles  SubRip/SRT             eng   yes  no   Subtitle A
```

`--dry-run` prints the exact command that would be run, which is the quickest
way to check track selection and `${fileName}` companion resolution before
committing to a long mux. Both flags leave the filesystem untouched — not even
`destinationDir` is created.

The printed command is meant for reading, not for pasting: mkvmerge's `(` and
`)` source-grouping tokens are not shell-safe as written.

### propedit.groovy

```
groovy src/propedit.groovy --edit track:a2 --set flag-forced=0
```

Runs `mkvpropedit` against every `.mkv` in the current directory, passing all
arguments through verbatim with the file name inserted first. Anything
`mkvpropedit` accepts works without editing the script:

```
groovy src/propedit.groovy --edit track:s1 --set flag-default=1
groovy src/propedit.groovy --edit info --set title="My Show"
groovy src/propedit.groovy --add-track-statistics-tags
```

Run with no arguments to print usage; nothing is modified. `-h`/`--help` is
handled locally only when it is the sole argument — in any other combination it
is passed through to `mkvpropedit`. Unlike `mux.groovy`, this script exits
non-zero if any file failed, so it can be used from a shell script.

### Post-processing utilities

```
groovy src/filename_to_title.groovy   # segment title + video track name := file name
groovy src/propedit.groovy            # batch mkvpropedit — fix properties without remuxing
groovy src/to_utf8.groovy             # subtitles → UTF-8, in place
groovy src/fix_srt.groovy             # repair non-standard SRT timing/markup
groovy src/find_unused_fonts.groovy   # report unreferenced fonts in fonts/
```

#### to_utf8.groovy

```
groovy src/to_utf8.groovy                          # from windows-1251 (the default)
groovy src/to_utf8.groovy --encoding windows-1250  # from something else
groovy src/to_utf8.groovy --backup                 # keep <name>.orig
groovy src/to_utf8.groovy --dry-run                # report, write nothing
```

Converts `.srt`, `.ass`, `.ssa` and `.vtt` files in the current directory to
UTF-8, **in place**. The source encoding defaults to `windows-1251` and is always
printed, so it is never a silent assumption. The target is always UTF-8 — making
that configurable would defeat the script's purpose.

Pass `--backup` to keep the original as `<name>.orig`, or `--dry-run` to preview.

Three things make it safe to point at a directory more than once:

- **Files that are already UTF-8 are skipped** — detected by BOM or by decoding
  cleanly as UTF-8. Pure ASCII lands here too, correctly: it is already valid
  UTF-8. Double-converting is precisely how a previously fixed file gets ruined.
- **Decoding is strict.** Java's default decoder *replaces* bytes that are invalid
  in the source charset, so a wrong `--encoding` would otherwise succeed quietly
  and produce mojibake. Instead the file is reported and left alone, and the run
  exits non-zero.
- **UTF-16 input is detected and skipped** — otherwise it would decode to
  mojibake that looks valid.

`.sub` is deliberately excluded: the extension is ambiguous between MicroDVD text
and the binary half of a VobSub `.idx`/`.sub` pair, and rewriting the binary one
would destroy it.

Line endings are preserved — the file is decoded and re-encoded whole rather than
line by line, so CRLF subtitles stay CRLF.

## Configuration

`mux.groovy` is driven by a YAML configuration file (`config.yaml` in the media
directory, or `--config <path>`); copy the shipped template
`src/config.example.yaml` as a starting point.

### General settings

```yaml
general:
  destinationDir: "mkv"                                # Output directory
  allowedExtensions: ["mkv", "avi", "mp4"]            # File extensions to process
  # mkvmergeExe is optional: omit it to auto-detect mkvmerge from PATH,
  # or set it to a full path to override.
  mkvmergeExe: "C:\\Program Files\\MKVToolNix\\mkvmerge.exe"
```

### Main source settings

Defines tracks from the primary source file. Track IDs come from `mkvmerge -i`;
track 0 is always the video track.

```yaml
mainSource:
  videoTrack:
    language: "en"                                    # Video track language
    # title will be set to filename by default
    # title: "Custom Video Title"                     # Optional: override video track name

  audioTracks:
    - id: 2                                           # Track ID from mkvmerge -i
      language: "en"                                  # Track language
      title: "English"                                # Track title
      default: true                                   # Is default track (omit = false)
    - id: 1
      language: "ru"
      title: "Russian"
      default: false

  subtitleTracks:
    - id: 6
      language: "en"
      title: "English"
      charset: "UTF-8"                                # Optional: character set override
      default: true

  # additionalOptions:
  #   - "--compression"
  #   - "0:none"
```

- Omitting `audioTracks` entirely (or setting it to `[]`) copies no audio tracks.
- Omitting `subtitleTracks` entirely (or setting it to `[]`) copies no subtitle tracks.
- `charset` is optional; omit it to let mkvmerge use the subtitle file's detected encoding.
- `default` defaults to `false`/`no` when omitted.

### Track order

Controls the order of tracks in the output. Each entry is `sourceIndex:trackId`,
where source 0 is the main source.

**`trackOrder` is optional.** When omitted, it is derived from the tracks you
configured above, in the order you listed them:

1. the video track (`0:0`),
2. `mainSource.audioTracks`, in listed order,
3. `mainSource.subtitleTracks`, in listed order,
4. one entry per `additionalSources` file (`1:0`, `2:0`, …).

For most configs that is exactly what you want, and the derived value is printed
when the script runs. Set `trackOrder` explicitly only to override it:

```yaml
trackOrder: "0:0,0:2,0:1,0:6"
```

An explicit `trackOrder` is checked against the configured tracks, and any
mismatch is reported as a warning — muxing still proceeds. This matters because
mkvmerge itself **silently ignores** entries that match no muxed track, so a
stale ID left behind after editing the track lists would otherwise have no
visible effect at all.

### Additional sources

Include tracks from extra files (audio dubs, external subtitles). Each
additional source must contain exactly one track. Track ID is always 0.

`${fileName}` in the `file` field is replaced at runtime with the base name (no
extension) of the current main source file, enabling per-episode companion
files.

```yaml
additionalSources:
  - file: "${fileName}[Dub Studio].mka"               # ${fileName} = base name of main source
    tracks:
      - language: "en"
        title: "English"
        charset: "UTF-8"                              # Optional: charset for subtitle tracks
        default: false
    # additionalOptions:
    #   - "--compression"
    #   - "0:none"
```

### Example configurations

#### Basic configuration with English audio and subtitles

`trackOrder` is omitted here, so it is derived as `0:0,0:1,0:2`:

```yaml
mainSource:
  videoTrack:
    language: "en"
  audioTracks:
    - id: 1
      language: "en"
      title: "English"
      default: true
  subtitleTracks:
    - id: 2
      language: "en"
      title: "English"
      default: true
```

#### Configuration with multiple audio tracks

```yaml
mainSource:
  videoTrack:
    language: "en"
    title: "Custom Video Title"  # Override default title
  audioTracks:
    - id: 1
      language: "en"
      title: "English"
      default: true
    - id: 2
      language: "ja"
      title: "Japanese"
      default: false
    - id: 3
      language: "fr"
      title: "French"
      default: false
  additionalOptions:
    - "--compression"
    - "0:none"
trackOrder: "0:0,0:1,0:2,0:3"
```

#### Configuration with an external subtitle file

```yaml
mainSource:
  videoTrack:
    language: "en"
  audioTracks:
    - id: 1
      language: "en"
      title: "English"
      default: true
additionalSources:
  - file: "${fileName}.srt"
    tracks:
      - language: "en"
        title: "English"
        charset: "UTF-8"
        default: true
trackOrder: "0:0,0:1,1:0"
```

### Key assumptions

1. The first track in the main source will always contain a video track (track ID 0)
2. Each additional source contains exactly one track (audio or subtitle, never video)
3. Track ID 0 is assumed for all tracks in additional sources

## Tests

Run the test suite from the repo root:

```
groovy src/test/run_tests.groovy
groovy src/test/run_tests.groovy --help
groovy src/test/run_tests.groovy --filter 01 --keep
groovy src/test/run_tests.groovy --mkvmerge-exe /usr/bin/mkvmerge
```

`mkvmerge` is auto-detected from PATH; use `--mkvmerge-exe` to override. Cases
that need `mkvpropedit`, or a bare `groovy` on `PATH` for the wrapper smoke
test, skip themselves with a printed note when those are unavailable.

## License

[MIT](LICENSE)
