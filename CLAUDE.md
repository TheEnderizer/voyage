# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Better Audio (released as "Voyage") is a native Android audiobook player: it scans a local folder for audio files, groups them into books/series, plays multi-file books as one seamless timeline, tracks per-book resume position, and integrates with the lock screen, a home-screen widget, and (optionally) Gemini AI for auto-generated synopses. Single Gradle module (`app/`), package `com.betteraudio`. Kotlin + Jetpack Compose + Media3 + Room + Hilt, MVVM.

## Build / run / deploy

The custom `gradlew.bat` in this repo is **not** the stock Gradle wrapper script — its box-drawing comment characters get mis-parsed by `cmd.exe`, so `./gradlew` and `gradlew.bat` often fail with `ClassNotFoundException: GradleWrapperMain`. Always invoke the wrapper jar directly:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # bundled JBR 21
$java = "$env:JAVA_HOME\bin\java.exe"
Set-Location 'D:\code\better audio'
& $java "-Dorg.gradle.appname=gradlew" `
    -classpath "D:\code\better audio\gradle\wrapper\gradle-wrapper.jar" `
    org.gradle.wrapper.GradleWrapperMain :app:compileDebugKotlin --console=plain
```

Common tasks: `:app:compileDebugKotlin` (fastest error check), `:app:assembleDebug` (installable APK), `:app:installDebug` (install on connected device). Pipe output to a file (`*> build_out.txt`) — PowerShell mangles the native stderr stream inline.

**Releases**: always build with `:app:assembleDebug` — the release variant produces an unsigned APK that Android rejects. Publish with `gh release create`; `gh` CLI is at `C:\Program Files\GitHub CLI\gh.exe`.

## Branch & release workflow

- All development happens on the **`beta`** branch.
- **`main`** is only updated (merged from beta) when explicitly asked to make a stable release.
- Beta APKs are GitHub pre-releases (`--prerelease` flag); stable APKs are standard releases.
- **Version naming**: beta builds end with `b` (e.g. `1.2.5b`); stable builds do not (e.g. `1.2.3`). The `b` suffix is how the update checker and changelog picker detect the channel at runtime — never omit it on beta builds.
- The update checker (`data/update/UpdateChecker.kt`) reads `installedVersionName()` at runtime: versions ending in `b` query `/releases?per_page=10` and take the first entry where `prerelease == true`; all others query `/releases/latest`. This keeps the two channels from cross-notifying.

## Storage model

Full-filesystem access via `MANAGE_EXTERNAL_STORAGE` ("All files access") and plain `java.io.File` paths everywhere — no SAF / `DocumentFile` / tree URIs. File paths are stored directly in the DB (`Book.folderPath`, `AudioFile.filePath`, `Book.coverArtPath`). Cover art is extracted from audio tags and written next to the audio as `.cover.jpg`; a `.nomedia` file is written to the same folder to hide covers from the phone gallery.

## Architecture

### Data layer (`data/`)

**Room DB** `betteraudio.db` (`data/db/AppDatabase.kt`), currently **version 8**. Entities: `Book`, `AudioFile`, `PlaybackProgress`, `BookGroup`, `BookGroupMember`, `Chapter`, `Bookmark`, `AudioPreset`. `fallbackToDestructiveMigration()` is the safety net but **explicit migrations must always be written** and registered in both `AppDatabase.kt` and `AppModule.kt`. Existing migrations: `MIGRATION_3_4`, `MIGRATION_4_5`, `MIGRATION_5_6`, `MIGRATION_6_7` (`lastPausedAt`), `MIGRATION_7_8` (`coverFxPath`).

Key fields added in recent migrations:
- `PlaybackProgress.boostDb` — per-book volume boost level
- `PlaybackProgress.eqBandsJson` — per-book EQ (JSON int array of millibel values, null = flat)
- `Book.titleOverride` / `Book.authorOverride` — in-app metadata overrides; scanner never writes these
- `Book.isIgnored` — soft-delete flag; all library queries filter `WHERE isIgnored = 0`
- `Book.coverFxPath` — path to the pre-baked cover background (see `CoverEffectBaker` below); null = not yet baked / invalidated by a cover change
- `AudioPreset.type` — `"SPEED"`, `"BOOST"`, or `"EQ"`; constants on `AudioPreset.Companion`

**Repositories**: `AudiobookRepository` and `BookGroupRepository` are the only things ViewModels/playback should touch — DAOs are never accessed directly outside these.

**`SettingsStore`** wraps DataStore. Every key needs **three things**: a `Flow` property, a `@Volatile current*` snapshot field (for synchronous reads in playback code), and a collector in `init`. Current keys include `LIBRARY_FOLDER`, `SKIP_FORWARD_MS`, `SKIP_BACK_MS`, `DEFAULT_SPEED`, `GEMINI_API_KEY`, `DEFAULT_AUDIO_PRESET_ID`, `SORT_OPTION`/`SORT_DIRECTION`, `LAST_OPEN_BOOK_ID`, `LAST_PLAYED_BOOK_ID`.

`savedFolder` in `HomeViewModel` uses `StateFlow<String?>` with `null` as the initial value (meaning "DataStore not yet loaded"). Code that triggers on folder state must guard against `null` to avoid acting before DataStore emits — `""` means loaded-but-not-set, non-blank means a folder has been chosen.

### Scanner (`data/scanner/`)

`AudioFileScanner` applies a folder-structure heuristic recursively. A folder of only sub-folders with >1 child is a series container. For a folder's direct audio files, `groupFilesIntoBooks` decides the split: **embedded ALBUM tags win** — every file tagged with ≥2 distinct albums → one book per album; a single shared album → exactly one book (never shattered); untagged/mixed → fall back to `clusterBySimilarName` (filename-stem heuristic, splits only when ≥2 genuine ≥2-file sequences exist). The album pre-pass costs one extra `MediaMetadataRetriever` ALBUM read per loose file at scan time. When a folder yields multiple books, each book's `folderPath` is a synthetic `"<dir>::<stem-or-album>"` key — never do `File(folderPath)` on it without checking for `::`.

**Chapters**: `ChapterExtractor` hand-rolls an MP4 `moov/udta/chpl` Nero-atom reader for M4B/M4A. `buildChapters` uses embedded markers where present; otherwise one row per file, **except** a chapterless file longer than `SYNTHETIC_CHAPTER_MIN_FILE_MS` (20 min) is sliced into `SYNTHETIC_CHAPTER_INTERVAL_MS` (~10 min) "Chapter N" rows (`source = "synthetic"`) so a single long MP3 still gets a usable TOC. Chapters are only rebuilt when the file set changed or `chapterCount == 0`, so existing books won't gain synthetic chapters until a rescan that changes their files.

`AutoJoiner` runs only on explicit user scans (`autoJoin = true`) and buckets books by immediate parent directory before merging — books in different subdirectories are never auto-merged.

### Playback (`playback/`)

**`PlaybackService`** — Media3 `MediaSessionService` owning the single `ExoPlayer`. Hosts two `AudioEffect` instances that **must live on the real ExoPlayer audio session** (a remote `MediaController` never receives `onAudioSessionIdChanged`):
- `LoudnessEnhancer` — volume boost via `CMD_SET_BOOST` / `KEY_BOOST_MB` custom `SessionCommand`
- `Equalizer` — 5-band EQ via `CMD_SET_EQ` / `KEY_EQ_BANDS_JSON` custom `SessionCommand` (JSON int array of millibel levels; empty/null = flat)

The `MediaSession` is fed a `ForwardingPlayer` whose next/previous overrides seek by time instead of changing files (headphone/Bluetooth/lock-screen transport = time skip). Keep a direct `exoPlayer` reference for audio effects — do **not** cast `mediaSession.player as ExoPlayer`, it's the `ForwardingPlayer`.

**`PlayerController`** (`@Singleton`) — app-side `MediaController` wrapper. Exposes `PlaybackState` StateFlow. Syncs state in `playerListener` (a `Player.Listener`) and via a **position ticker** — a coroutine on `Dispatchers.Main` that calls `syncState()` every 500 ms while playing. The ticker **must** run on the main thread because `MediaController` is main-thread only; launching it on any other dispatcher causes an immediate crash.

**Per-book audio settings**: `play()` in `PlayerViewModel` restores both `boostDb` and `eqBandsJson` from `PlaybackProgress` every time a book starts. `setEqBands()` persists the new value immediately. This is how EQ and boost stay per-book rather than bleeding across sessions.

**Joined groups**: `playBookGroup()` flattens all member files into one `MediaItem` list with `bookId` + `groupId` in `MediaMetadata.extras`. No group-level progress row — resume uses the most-recently-played member's `PlaybackProgress`.

### UI (`ui/`)

**Navigation**: single-Activity (`MainActivity`) with Compose `NavHost`. Routes: `home`, `player/{bookId}`, `search`, `series/{seriesName}`, `settings`, `join_options?bookIds=&groupId=`. The home screen renders its own floating pill nav (no bottom-nav graph). Cold start restores the last-open player screen from `LAST_OPEN_BOOK_ID` via `runBlocking` before composition. A widget tap carries `WidgetRender.EXTRA_OPEN_PLAYER`; `MainActivity` routes to the active book's player on both cold start and `onNewIntent` (warm, via a `playerNavRequest` state).

**Theme**: `VoyageTheme(coverArtPath)` (in `ui/theme/Theme.kt`) wraps the whole `NavHost` in `MainActivity`, so the cover-driven recolor is **app-wide, not player-only**. `MainActivity` feeds it the active book's cover, falling back to the **last-played** book's cover (`LAST_PLAYED_BOOK_ID` → `repository.getBookById`) when nothing is loaded, so the app stays themed while idle. `rememberCoverScheme` extracts accents via `androidx.palette`; `dynamicColor` is off; screens inherit the ambient scheme and do not self-theme. Exception: the full-bleed player and home cover overlays hardcode `Color.White`/`Color.Black` for scrim legibility over arbitrary art — those are intentionally outside the theme tokens.

**Home screen** (`ui/home/`):
- Grid shows `HomeGridItem.SingleBook` and `HomeGridItem.Group` items. Groups come from `BookGroupRepository`; ungrouped books from `getAllBooksWithProgressUngrouped()` (already filtered for `isIgnored = 0`).
- **Library status tabs**: a chip row (All / Listening / Not started / Finished, with live counts). `HomeViewModel` exposes `libraryTab`, `visibleGridItems` (filtered), and `tabCounts`; status is derived from `Book.status`, and a group is classified from its members.
- **Resume card**: when nothing is actively playing but `LAST_PLAYED_BOOK_ID` is set, a `ResumeCard` composable shows the last-played book in the same paused-state UI as `FeaturedNowPlaying`.
- **Long press = select only** (single threshold, `combinedClickable`). The selection bar (`SelectionHeader`) floats as a top overlay (`AnimatedVisibility` aligned `TopCenter`) so it doesn't push the grid down; when exactly one book is selected it shows a `⋮` that opens `BookOptionsSheet` for that book. (The old 1500 ms second-hold gesture was removed.)
- `BookOptionsSheet` provides in-app metadata editing (`titleOverride` / `authorOverride`), series name + position, reading status, online cover search, hide-from-library (`isIgnored`), and permanent delete with optional file removal.
- Scanning has no nav button — it runs on first launch (auto-prompt when `savedFolder` is `""`) and on pull-to-refresh.

**Settings screen** (`ui/settings/`): two-level navigation via `AnimatedContent` (sealed class `SettingsSection`). The **About** section contains both the update checker UI and the per-channel changelog (read from `res/raw/changelog_beta.txt` or `res/raw/changelog_stable.txt` based on version suffix). The `LazyListScope` extension functions receive all their data as parameters — do not call `collectAsStateWithLifecycle()` inside them (not a `@Composable` context).

**Audio settings** (`ui/player/AudioSettingsSheet.kt`): 3-tab sheet (Speed / Boost / EQ). Each tab shows only presets of its own type (`AudioPreset.TYPE_SPEED`, `TYPE_BOOST`, `TYPE_EQ`). `PlayerViewModel` exposes three separate typed `StateFlow`s: `speedPresets`, `boostPresets`, `eqPresets`. Long-press on a preset chip calls `overwritePreset()` which updates the stored value to the current slider position.

**Cover background effect** (`ui/components/ReflectedProgressiveBlurCover.kt` + `data/covers/CoverEffectBaker.kt`): the player and book-info screens share one full-bleed backdrop — the cover with a vertically-flipped reflection below it, a continuous top-to-bottom progressive blur, and a fade to black. `CoverEffectBaker` pre-renders this whole composite to a single WebP in `filesDir/cover_fx/` (a genuine per-row continuous blur via repeated box-blur accumulation) so the UI draws one cached bitmap instead of ~7 live blur passes per frame. `ReflectedProgressiveBlurCover` draws `book.coverFxPath` when present and **live-renders** the layered approximation as a fallback while a bake is missing/in-progress. Baking is lazy: `repository.ensureCoverFx(bookId)` runs from the Player/BookInfo ViewModels' init when a cover exists but `coverFxPath` is null. `updateCoverArt()` sets `coverFxPath = null` to invalidate on any cover change. The 3-dot menus expose "Refresh cover effect" → `regenerateCoverFx()` (force re-bake; also the way to roll out an algorithm change — bump `CoverEffectBaker.VERSION`).

**Player screen** (`ui/player/`): full-bleed layout — the cover fills the screen behind a bottom scrim that holds the controls (chapter context line, title/author, return/confirm jump-history pills, scrubber, transport, secondary row). Skip buttons render the configured seconds from `PlayerViewModel.skipForwardMs`/`skipBackMs` (not a fixed number). Position history stack (`_positionStack`, capped at 20) pushed before bookmark jumps, chapter seeks, and scrubber drags > 5 min. The redesign dropped the inline synopsis + metadata chips from this screen (synopsis still generates and persists; it's destined for a future book-info screen).

### Changelogs

`app/src/main/res/raw/changelog_beta.txt` and `changelog_stable.txt` follow the [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) format — `## [x.y.z] - YYYY-MM-DD` headers with `Added`, `Changed`, `Fixed`, `Security` subsections. Add a new entry at the top for every release.

### Widget & AI

Widgets (`widget/`) — three sizes sharing `BaseNowPlayingWidget` + `WidgetRender`. All dynamic visuals are drawn to bitmaps — `RemoteViews` can't use a Compose theme. `PlaybackService.broadcastWidgetUpdate` fires the update broadcast. The container tap (`WidgetCommon.openAppIntent`) sets `EXTRA_OPEN_PLAYER` to deep-link to the active player (handled in `MainActivity`). Widgets are **not** Compose-themed, so the app-wide cover recolor does not reach them.

`SynopsisService` calls Gemini AI via the key stored in `SettingsStore` under `GEMINI_API_KEY` (never hardcoded). Triggered from `PlayerViewModel` when `book.synopsis == null` and the key is set.

`CoverSearchService` (`data/covers/`) searches book cover art online — Google Books API first (no key), OpenLibrary fallback — and downloads the chosen image to internal storage with a timestamped filename (so Coil reloads it). Driven from `HomeViewModel` (`searchCovers` / `setBookCoverFromUrl`) via `CoverSearchSheet`.

## Conventions

- All DI is Hilt; new singletons/DAOs go through `di/AppModule`. ViewModels are `@HiltViewModel`.
- Reactive UI: Room/DataStore `Flow` → `stateIn`; one-shot reads use `*Once` repository methods (or `.first()` on a Flow).
- Media3 APIs require `@UnstableApi`; Compose APIs often need `@OptIn(ExperimentalMaterial3Api::class)` — match opt-ins already present on the surrounding function.
- `MutableStateFlow.update { }` requires `import kotlinx.coroutines.flow.update` (not auto-imported).
- `Book.displayTitle` / `Book.displayAuthor` are computed properties that return the override if set, falling back to the scanned value. Use these everywhere in the UI — never `book.title` / `book.author` directly.
- The Gemini API key (`AIzaSy...`) is stored in DataStore under `gemini_api_key` and must never be embedded in code.
