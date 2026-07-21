# Reference

The details behind the [README](../README.md): output conventions, how to read
the consistency check report, and the full `config.yaml` reference. This
document is about *using* the tools; implementation internals (shared helpers,
the test harness) live in the repo's `CLAUDE.md`.

## Console output

All scripts share one output convention, implemented in `src/output.groovy`.
Every colour has a single meaning, the same in every script:

| Colour | Meaning |
|--------|---------|
| red    | errors and failure summaries |
| green  | success: `*** Done`, clean batch summaries |
| yellow | warnings, and the differing-cell highlight in the `--check` tables |
| cyan   | section, per-file and table column headers — for finding your place in long scrollback |
| gray   | de-emphasis: the file-evidence lists in the `--check` report, so the table rows stand out (the `<-` marker keeps the default colour) |

Colour is applied only when writing to a terminal. `mux.groovy`,
`rename.groovy`, `fetch_episodes.groovy`, `to_utf8.groovy` and `fix_srt.groovy`
take `--color auto|always|never` (default `auto`). The
[`NO_COLOR`](https://no-color.org/) environment variable disables
auto-detection; an explicit `--color always` wins over it. `propedit.groovy`
deliberately has no options of its own — every argument is passed through to
`mkvpropedit` — so it follows auto-detection and `NO_COLOR` only.
`find_unused_fonts.groovy` prints bare file names meant for piping and is never
coloured.

Errors and warnings go to **stderr**, progress and summaries to **stdout**, so
either can be redirected without losing the other. Every batch script ends with
a one-line summary (`*** 4 converted, 2 skipped, 1 failed`) and exits non-zero
if anything failed. The exception is `mux.groovy`, which continues on errors
and always exits 0 — a partially-successful batch mux is a normal outcome —
except under `--strict`, which exits 2 on a blocking discrepancy.

## Reading the consistency check report

The check works in two layers. First it groups files by **track layout** — the
type at each ID. Files that share a layout are the same release and get one
table; files whose layout differs (a different track order, or a track missing)
are a different release and get their own, largest group first. This keeps a
shifted-track-order release from being smeared across the per-ID table, where
the same file would otherwise appear at every shifted ID. Then, within each
group, it compares the **values** at each ID — codec, language, name,
default/forced flags — stacking a row per distinct value and naming the files
that carry it:

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
  unnamed reference. The file lists are gray so the table rows stand out.
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
Highlighting follows `--color` — see [Console output](#console-output).

## to_utf8.groovy safety

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
  # title is optional: the segment (container) title, defaulting to the file
  # name. Separate from the video track name in mainSource below.
  title: "${showName} - S${seasonNum}E${episodeNum} - ${episodeName}"
```

### Main source settings

Defines tracks from the primary source file. Track IDs come from `mkvmerge -i`;
track 0 is always the video track.

```yaml
mainSource:
  videoTrack:
    language: "en"                                    # Video track language
    # title will be set to filename by default
    # title: "Original Japanese"                      # Optional: override video track name

  audioTracks:
    - id: 2                                           # Track ID from mkvmerge -i
      language: "en"                                  # Track language
      title: "English"                                # Track title
      default: true                                   # Is default track (omit = false)
    - id: 1
      language: "ru"
      title: "${languageNative} ${codec}"             # -> "Русский DTS"
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
- `general.title` and `videoTrack.title` are separate fields: the first is the
  segment (container) title, the second the video track's own name. Each falls
  back to the file name on its own.

### Substitution variables

Every `title`, and every `additionalSources` file path, is a template. See
[Substitution variables](../README.md#substitution-variables) in the README for
the full tables; in short:

- **File scope**, valid in any of those fields: `fileName`, `extension`,
  `showName`, `seasonNum`, `episodeNum`, `episodeName`, `seasonName`, `showYear`.
  Values come from `episodes.yaml` (or `episodes.txt`) in the media directory,
  falling back to a canonical `Show - SxxEyy - Title` file name.
- **Track scope**, valid only in a track's `title`: `language`, `languageName`
  (`Russian`), `languageNative` (`Русский`), `codec` (`DTS`, `SRT`, `H.264`).

An unknown name, or a track variable used outside a track title, is reported and
exits 2 before anything is probed or muxed. A known variable with no data for a
given episode drops that episode from the batch and muxes the rest; `--strict`
turns that into an abort. There is no escape syntax, so a literal `${...}`
cannot currently be written into a title.

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

The `file` field takes the file-scope substitution variables above, resolved per
episode. `${fileName}` — the base name, no extension, of the current main source
— is the usual one, but a companion kept in a per-studio folder works the same
way: `"Rus sound/[Studio]/${fileName}.mka"`. A track's `title` here also takes
the track-scope variables, describing the companion's own language and codec.

`mkv-mux --identify` lists each configured companion with the path its pattern
resolved to for that episode, which is the quickest way to check one.

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
