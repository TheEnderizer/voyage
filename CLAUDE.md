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

**Room DB** `betteraudio.db` (`data/db/AppDatabase.kt`), currently **version 6**. Entities: `Book`, `AudioFile`, `PlaybackProgress`, `BookGroup`, `BookGroupMember`, `Chapter`, `Bookmark`, `AudioPreset`. `fallbackToDestructiveMigration()` is the safety net but **explicit migrations must always be written** and registered in both `AppDatabase.kt` and `AppModule.kt`. Existing migrations: `MIGRATION_3_4`, `MIGRATION_4_5`, `MIGRATION_5_6`.

Key fields added in recent migrations:
- `PlaybackProgress.boostDb` — per-book volume boost level
- `PlaybackProgress.eqBandsJson` — per-book EQ (JSON int array of millibel values, null = flat)
- `Book.titleOverride` / `Book.authorOverride` — in-app metadata overrides; scanner never writes these
- `Book.isIgnored` — soft-delete flag; all library queries filter `WHERE isIgnored = 0`
- `AudioPreset.type` — `"SPEED"`, `"BOOST"`, or `"EQ"`; constants on `AudioPreset.Companion`

**Repositories**: `AudiobookRepository` and `BookGroupRepository` are the only things ViewModels/playback should touch — DAOs are never accessed directly outside these.

**`SettingsStore`** wraps DataStore. Every key needs **three things**: a `Flow` property, a `@Volatile current*` snapshot field (for synchronous reads in playback code), and a collector in `init`. Current keys include `LIBRARY_FOLDER`, `SKIP_FORWARD_MS`, `SKIP_BACK_MS`, `DEFAULT_SPEED`, `GEMINI_API_KEY`, `DEFAULT_AUDIO_PRESET_ID`, `SORT_OPTION`/`SORT_DIRECTION`, `LAST_OPEN_BOOK_ID`, `LAST_PLAYED_BOOK_ID`.

`savedFolder` in `HomeViewModel` uses `StateFlow<String?>` with `null` as the initial value (meaning "DataStore not yet loaded"). Code that triggers on folder state must guard against `null` to avoid acting before DataStore emits — `""` means loaded-but-not-set, non-blank means a folder has been chosen.

### Scanner (`data/scanner/`)

`AudioFileScanner` applies a folder-structure heuristic recursively: direct audio files are clustered into books by `clusterBySimilarName` (splits into multiple books only when ≥2 genuine ≥2-file sequences exist). A folder of only sub-folders with >1 child is a series container. When a folder yields multiple books, each book's `folderPath` is a synthetic `"<dir>::<stem>"` key — never do `File(folderPath)` on it without checking for `::`.

`ChapterExtractor` hand-rolls an MP4 `moov/udta/chpl` Nero-atom reader for M4B/M4A. `AutoJoiner` runs only on explicit user scans (`autoJoin = true`) and buckets books by immediate parent directory before merging — books in different subdirectories are never auto-merged.

### Playback (`playback/`)

**`PlaybackService`** — Media3 `MediaSessionService` owning the single `ExoPlayer`. Hosts two `AudioEffect` instances that **must live on the real ExoPlayer audio session** (a remote `MediaController` never receives `onAudioSessionIdChanged`):
- `LoudnessEnhancer` — volume boost via `CMD_SET_BOOST` / `KEY_BOOST_MB` custom `SessionCommand`
- `Equalizer` — 5-band EQ via `CMD_SET_EQ` / `KEY_EQ_BANDS_JSON` custom `SessionCommand` (JSON int array of millibel levels; empty/null = flat)

The `MediaSession` is fed a `ForwardingPlayer` whose next/previous overrides seek by time instead of changing files (headphone/Bluetooth/lock-screen transport = time skip). Keep a direct `exoPlayer` reference for audio effects — do **not** cast `mediaSession.player as ExoPlayer`, it's the `ForwardingPlayer`.

**`PlayerController`** (`@Singleton`) — app-side `MediaController` wrapper. Exposes `PlaybackState` StateFlow. Syncs state in `playerListener` (a `Player.Listener`) and via a **position ticker** — a coroutine on `Dispatchers.Main` that calls `syncState()` every 500 ms while playing. The ticker **must** run on the main thread because `MediaController` is main-thread only; launching it on any other dispatcher causes an immediate crash.

**Per-book audio settings**: `play()` in `PlayerViewModel` restores both `boostDb` and `eqBandsJson` from `PlaybackProgress` every time a book starts. `setEqBands()` persists the new value immediately. This is how EQ and boost stay per-book rather than bleeding across sessions.

**Joined groups**: `playBookGroup()` flattens all member files into one `MediaItem` list with `bookId` + `groupId` in `MediaMetadata.extras`. No group-level progress row — resume uses the most-recently-played member's `PlaybackProgress`.

### UI (`ui/`)

**Navigation**: single-Activity (`MainActivity`) with Compose `NavHost`. Routes: `home`, `player/{bookId}`, `search`, `series/{seriesName}`, `settings`, `join_options?bookIds=&groupId=`. The home screen renders its own floating pill nav (no bottom-nav graph). Cold start restores the last-open player screen from `LAST_OPEN_BOOK_ID` via `runBlocking` before composition.

**Theme**: `BetterAudioTheme(coverArtPath)` recolours the entire app to the currently-playing book's cover via `androidx.palette`. `dynamicColor` is off. Screens inherit the ambient scheme and do not self-theme.

**Home screen** (`ui/home/`):
- Grid shows `HomeGridItem.SingleBook` and `HomeGridItem.Group` items. Groups come from `BookGroupRepository`; ungrouped books from `getAllBooksWithProgressUngrouped()` (already filtered for `isIgnored = 0`).
- **Resume card**: when nothing is actively playing but `LAST_PLAYED_BOOK_ID` is set, a `ResumeCard` composable shows the last-played book in the same paused-state UI as `FeaturedNowPlaying`.
- **Long press**: 500 ms → selection mode (checkbox via `combinedClickable`). 1 500 ms → `BookOptionsSheet` opens (implemented as a second `pointerInput` modifier that measures elapsed time between press and release).
- `BookOptionsSheet` provides in-app metadata editing (`titleOverride` / `authorOverride`), hide-from-library (`isIgnored`), and permanent delete with optional file removal.
- Scanning no longer has a nav button — it runs on first launch (auto-prompt when `savedFolder` is `""`) and on pull-to-refresh.

**Settings screen** (`ui/settings/`): two-level navigation via `AnimatedContent` (sealed class `SettingsSection`). The **About** section contains both the update checker UI and the per-channel changelog (read from `res/raw/changelog_beta.txt` or `res/raw/changelog_stable.txt` based on version suffix). The `LazyListScope` extension functions receive all their data as parameters — do not call `collectAsStateWithLifecycle()` inside them (not a `@Composable` context).

**Audio settings** (`ui/player/AudioSettingsSheet.kt`): 3-tab sheet (Speed / Boost / EQ). Each tab shows only presets of its own type (`AudioPreset.TYPE_SPEED`, `TYPE_BOOST`, `TYPE_EQ`). `PlayerViewModel` exposes three separate typed `StateFlow`s: `speedPresets`, `boostPresets`, `eqPresets`. Long-press on a preset chip calls `overwritePreset()` which updates the stored value to the current slider position.

**Player screen** (`ui/player/`): position history stack (`_positionStack`, capped at 20) pushed before bookmark jumps, chapter seeks, and scrubber drags > 5 min.

### Changelogs

`app/src/main/res/raw/changelog_beta.txt` and `changelog_stable.txt` follow the [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) format — `## [x.y.z] - YYYY-MM-DD` headers with `Added`, `Changed`, `Fixed`, `Security` subsections. Add a new entry at the top for every release.

### Widget & AI

Widgets (`widget/`) — three sizes sharing `BaseNowPlayingWidget` + `WidgetRender`. All dynamic visuals are drawn to bitmaps — `RemoteViews` can't use a Compose theme. `PlaybackService.broadcastWidgetUpdate` fires the update broadcast.

`SynopsisService` calls Gemini AI via the key stored in `SettingsStore` under `GEMINI_API_KEY` (never hardcoded). Triggered from `PlayerViewModel` when `book.synopsis == null` and the key is set.

## Conventions

- All DI is Hilt; new singletons/DAOs go through `di/AppModule`. ViewModels are `@HiltViewModel`.
- Reactive UI: Room/DataStore `Flow` → `stateIn`; one-shot reads use `*Once` repository methods (or `.first()` on a Flow).
- Media3 APIs require `@UnstableApi`; Compose APIs often need `@OptIn(ExperimentalMaterial3Api::class)` — match opt-ins already present on the surrounding function.
- `MutableStateFlow.update { }` requires `import kotlinx.coroutines.flow.update` (not auto-imported).
- `Book.displayTitle` / `Book.displayAuthor` are computed properties that return the override if set, falling back to the scanned value. Use these everywhere in the UI — never `book.title` / `book.author` directly.
- The Gemini API key (`AIzaSy...`) is stored in DataStore under `gemini_api_key` and must never be embedded in code.
