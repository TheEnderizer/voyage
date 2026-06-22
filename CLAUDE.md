# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Better Audio (released as "Voyage") is a native Android audiobook player: it scans a local folder for audio files, groups them into books/series, plays multi-file books as one seamless timeline, tracks per-book resume position, and integrates with the lock screen, a home-screen widget, and (optionally) Gemini AI for auto-generated synopses. Single Gradle module (`app/`), package `com.betteraudio`. Kotlin + Jetpack Compose + Media3 + Room + Hilt, MVVM.

## Build / run / deploy

The custom `gradlew.bat` in this repo is **not** the stock Gradle wrapper script — its box-drawing comment characters get mis-parsed by `cmd.exe`, so `./gradlew` and `gradlew.bat` often fail with `ClassNotFoundException: GradleWrapperMain`. Always invoke the wrapper jar directly from Claude Code on Windows:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # bundled JBR 21
$java = "$env:JAVA_HOME\bin\java.exe"
Set-Location 'D:\code\better audio'
& $java "-Dorg.gradle.appname=gradlew" `
    -classpath "D:\code\better audio\gradle\wrapper\gradle-wrapper.jar" `
    org.gradle.wrapper.GradleWrapperMain :app:compileDebugKotlin --console=plain
```

Common tasks: `:app:compileDebugKotlin` (fastest error check), `:app:assembleDebug` (installable APK), `:app:installDebug` (install on connected device). Pipe output to a file (`*> build_out.txt`) — PowerShell mangles the native stderr stream inline.

Convenience scripts in `scripts/` (`build.bat`, `deploy-device.bat`, etc.) work from a normal Windows shell. ADB lives at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. There is no test suite.

**Releases**: build with `:app:assembleDebug` (the release variant produces an unsigned APK that Android rejects). Use `gh release create` to publish to GitHub; `gh` CLI is at `C:\Program Files\GitHub CLI\gh.exe`.

## Storage model

Full-filesystem access via `MANAGE_EXTERNAL_STORAGE` ("All files access") and plain `java.io.File` paths everywhere — no SAF / `DocumentFile` / tree URIs. File paths are stored directly in the DB (`Book.folderPath`, `AudioFile.filePath`, `Book.coverArtPath`). Cover art extracted from tags is written next to the audio as `.cover.jpg`.

## Architecture

### Data layer (`data/`)

**Room DB** `betteraudio.db` (`data/db/AppDatabase.kt`), currently **version 5**. Entities: `Book`, `AudioFile`, `PlaybackProgress`, `BookGroup`, `BookGroupMember`, `Chapter`, `Bookmark`, `AudioPreset`. `fallbackToDestructiveMigration()` is the safety net but **explicit migrations exist and must be written** — `MIGRATION_3_4` (added bookmarks table) and `MIGRATION_4_5` (added `boostDb` column on `PlaybackProgress`, added `audio_presets` table). Register new migrations in both `AppDatabase.kt` and `AppModule.kt`.

**Repositories**: `AudiobookRepository` (books/files/progress/chapters/bookmarks/audio-presets) and `BookGroupRepository` (joined groups) are the only things ViewModels/playback should touch. DAOs are never accessed directly outside these.

**`SettingsStore`** wraps DataStore. It exposes `Flow`s for reactive UI **and** `@Volatile current*` snapshot fields that playback code reads synchronously without suspending. Every new key needs **both**: the `Flow` property, the `@Volatile` field, and a collector in `init`. Current keys: `LIBRARY_FOLDER`, `SKIP_FORWARD_MS`, `SKIP_BACK_MS`, `DEFAULT_SPEED`, `GEMINI_API_KEY`, `DEFAULT_AUDIO_PRESET_ID`, `SORT_OPTION`/`SORT_DIRECTION` (persisted library sort), `LAST_OPEN_BOOK_ID` (the book whose player was last open, used to restore that screen on cold start).

### Scanner (`data/scanner/`)

`AudioFileScanner` applies a folder-structure heuristic recursively: a folder's direct audio files are clustered into books by `clusterBySimilarName` (files sharing a name stem after stripping sequence numbers; only splits into multiple books when ≥2 genuine ≥2-file sequences exist). A folder of only sub-folders with >1 child is a series container. When a folder yields multiple books, each book's `folderPath` is a synthetic `"<dir>::<stem>"` key — nothing should do `File(folderPath)` on it. Metadata comes from `MediaMetadataRetriever`.

**Chapters**: `ChapterExtractor` hand-rolls an MP4 `moov/udta/chpl` Nero-atom reader for M4B/M4A (no external dep). Positions stored as `(fileId, startInFileMs)`; absolute timeline positions are derived at playback time from cumulative file offsets.

**AutoJoiner** (`AutoJoiner.run(rootPath: String)`): runs only on explicit user scans (`autoJoin = true`). Before applying the three merge passes (numbered-title stems, same series + author, sequential tracks), it **buckets ungrouped books by their immediate parent directory** (`File(folderPath).parentFile?.absolutePath`) — books in different subdirectories are never auto-merged with each other.

Re-scan preserves resume position: `importBook` diffs existing vs. new file paths and only rewrites `AudioFile`/`Chapter` rows when the set actually changed.

### Playback (`playback/`)

**`PlaybackService`** — Media3 `MediaSessionService` owning the single `ExoPlayer`. Hosts two `AudioEffect` instances that **must live on the real ExoPlayer audio session** (a remote `MediaController` never receives `onAudioSessionIdChanged`):
- `LoudnessEnhancer` — volume boost, applied via `CMD_SET_BOOST` / `KEY_BOOST_MB` custom `SessionCommand`
- `Equalizer` — 5-band EQ, applied via `CMD_SET_EQ` / `KEY_EQ_BANDS_JSON` custom `SessionCommand` (JSON int array of millibel levels; empty/null = flat/bypass)

Both are attached in `attachLoudnessEnhancer(sid)` / `attachEqualizer(sid)`, called eagerly from `onCreate` and reactively from `onAudioSessionIdChanged`.

**External transport = time skip**: the `MediaSession` is fed a `ForwardingPlayer` (not the raw ExoPlayer) whose `seekToNext/Previous` + `seekToNext/PreviousMediaItem` + `seekForward/Back` are overridden to seek by `settings.currentSkipForwardMs/BackMs` instead of changing files. This makes headphone/Bluetooth/lock-screen/notification next-previous behave as time skips. The raw `ExoPlayer` is kept in `exoPlayer` for audio-session/effects (do **not** do `mediaSession.player as ExoPlayer` — it's the ForwardingPlayer). In-app "next/previous part" buttons call `seekTo(index, 0)` (via `PlayerController.nextFile/prevFile`), which bypasses the override and still changes files.

**`PlayerController`** (`@Singleton`) — app-side `MediaController` wrapper. Exposes `PlaybackState` StateFlow. Key methods: `setVolumeBoost(db)`, `setEqBands(bandsJson)`, `bookSeekTo(bookPositionMs)`. Connected/disconnected in `MainActivity` lifecycle.

**Book-level position mapping**: `cumulativeStartsMs` per file; `bookSeekTo()` converts absolute book-ms back to `(itemIndex, offsetInFile)`. Scrubbers use book-level position when `bookTotalDurationMs > 0`.

**Per-book boost**: `PlaybackProgress.boostDb` stores each book's boost level. `PlayerViewModel.play()` calls `playerController.setVolumeBoost(progress?.boostDb ?: 0)` immediately after starting playback.

**Joined groups**: `playBookGroup()` flattens all member files into one `MediaItem` list. Each item carries `bookId` + `groupId` in `MediaMetadata.extras`; `syncState()` tracks the currently-playing member so progress saves to the right book. No group-level progress row — resume uses the most-recently-played member's `PlaybackProgress`.

### UI (`ui/`)

**Navigation**: single-Activity (`MainActivity`) with Compose `NavHost`. Routes: `home`, `player/{bookId}`, `search`, `series/{seriesName}`, `settings`, `join_options?bookIds=&groupId=`. No bottom-nav graph — the home screen renders its own floating pill nav. On cold start, `MainActivity` restores the last-open player screen from `LAST_OPEN_BOOK_ID` (captured via `runBlocking` before composition so the route-tracking effect can't clobber it first); the route is tracked and persisted on every navigation.

**Theme**: custom warm amber-on-charcoal. `dynamicColor` is off. `BetterAudioTheme(coverArtPath)` recolours the entire app to the currently-playing book's cover — `CoverTheme.kt` extracts accents via `androidx.palette` and animates transitions. Individual screens inherit the ambient scheme; they do not self-theme.

**Settings screen** (`ui/settings/`) uses two-level navigation with `AnimatedContent` (no new nav routes). `SettingsViewModel` holds `_section: MutableStateFlow<SettingsSection>` (sealed class: `Root`, `Library`, `Playback`, `AI`, `Updates`, `About`). Each section is a `LazyListScope` extension function. `BackHandler` returns to Root.

**Player screen** (`ui/player/`):
- **Position history stack**: `PlayerViewModel._positionStack: MutableStateFlow<List<Long>>` (capped at 20). Pushed before bookmark jumps, chapter seeks, and scrubber drags > 5 min. Auto-commits after 10 min of continuous playback. UI shows Return (with `DropdownMenu` for multi-entry stack) + Confirm buttons when stack is non-empty.
- **Audio settings**: Tune button opens `AudioSettingsSheet` — a 3-tab `ModalBottomSheet` (Speed / Volume Boost / Equalizer). EQ has 5 bands at ±1500 millibels. `AudioPreset` entities persist named speed+boost+EQ combos; `PlayerViewModel` exposes `audioPresets: StateFlow<List<AudioPreset>>`, `saveAudioPreset`, `loadAudioPreset`, `deleteAudioPreset`, `setAsDefaultPreset`.
- Speed and volume boost sliders are **not** on the main player screen — they live in `AudioSettingsSheet`.

**JoinOptions** (`ui/join/`): `MetadataSource` enum (`FIRST_BOOK` / `MANUAL`) controls whether name + cover auto-populate from the first book. Switches to `MANUAL` on any user edit; re-syncs if book order changes while in `FIRST_BOOK` mode.

### Widget & AI

**Widgets** (`widget/`) — three sizes sharing `BaseNowPlayingWidget` + `WidgetRender`. All dynamic visuals (cover, transport buttons, backgrounds) are drawn to bitmaps in code — `RemoteViews` can't take a Compose theme. `PlaybackService.broadcastWidgetUpdate` fires the update broadcast.

**`SynopsisService`** — calls Gemini AI using the key stored in `SettingsStore` under `GEMINI_API_KEY`. Triggered from `PlayerViewModel` when `book.synopsis == null` and the key is set. Result persisted to `Book.synopsis`.

## Conventions

- All DI is Hilt; new singletons/DAOs go through `di/AppModule`. ViewModels are `@HiltViewModel`.
- Reactive UI: Room/DataStore `Flow` → `stateIn`; one-shot reads use `*Once` repository methods.
- Media3 APIs require `@UnstableApi`; Compose APIs often need `@OptIn(ExperimentalMaterial3Api::class)` — match opt-ins already present on the surrounding function.
- `MutableStateFlow.update { }` requires `import kotlinx.coroutines.flow.update` (not auto-imported by the IDE in all cases).
- The Gemini API key is never embedded in code — only read from `SettingsStore`.
