# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Better Audio is a native Android audiobook player: it scans a local folder for audio files, groups them into books/series, plays multi-file books as one seamless timeline, tracks per-book resume position, and integrates with the lock screen, a home-screen widget, and (optionally) the Anthropic API for AI-generated synopses. Single Gradle module (`app/`), package `com.betteraudio`. Kotlin + Jetpack Compose + Media3 + Room + Hilt, MVVM.

## Build / run / deploy

The custom `gradlew.bat` in this repo is **not** the stock Gradle wrapper script — its box-drawing comment characters get mis-parsed by `cmd.exe`, so `./gradlew` and `gradlew.bat` often fail with `ClassNotFoundException: GradleWrapperMain` even though the jar is present. Two reliable ways to build:

**Convenience scripts** (`scripts/`, run from a normal Windows shell where they parse correctly):
- `scripts\build.bat` — assembles the debug APK
- `scripts\deploy-device.bat` — builds, installs over USB, and launches on the connected device
- `scripts\logcat.bat`, `scripts\run-emulator.bat`, `scripts\check-setup.bat`

**Direct invocation of the wrapper jar** (works from any shell, bypasses the broken `.bat`; this is what to use from Claude Code on Windows):
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # bundled JBR 21
$java = "$env:JAVA_HOME\bin\java.exe"
Set-Location 'D:\code\better audio'
& $java "-Dorg.gradle.appname=gradlew" `
    -classpath "D:\code\better audio\gradle\wrapper\gradle-wrapper.jar" `
    org.gradle.wrapper.GradleWrapperMain :app:compileDebugKotlin --console=plain
```
Swap the task for `:app:assembleDebug` (APK), `:app:installDebug` (install on the connected device), or `:app:compileDebugKotlin` (fastest error-only check). `JAVA_HOME` is not set in the environment by default — always set it. There is no JDK on `PATH`; use the Android Studio JBR above.

Gradle PowerShell note: pipe build output to a file (`*> build_out.txt`) rather than relying on `2>&1`, since the native stream gets mangled/UTF-16-encoded inline.

ADB lives at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. There is no test suite in the project; `testInstrumentationRunner` is declared but no tests exist yet.

## Storage model (important deviation)

This app uses **full-filesystem access** via `MANAGE_EXTERNAL_STORAGE` (“All files access”) and plain `java.io.File` paths everywhere — it does **not** use the Storage Access Framework / `DocumentFile` / SAF tree URIs. File paths are stored directly in the DB (`Book.folderPath`, `AudioFile.filePath`, `Book.coverArtPath`). Cover art extracted from tags is written next to the audio as `.cover.jpg`. Granting all-files access is a first-run requirement surfaced in the home screen and Settings.

## Architecture

### Data layer (`data/`)
- **Room DB** `betteraudio.db` (`data/db/AppDatabase.kt`), entities: `Book`, `AudioFile`, `PlaybackProgress`, `BookGroup`, `BookGroupMember`, `Chapter`. The DB uses `fallbackToDestructiveMigration()` — **there are no migrations**; when you change an entity you must bump `version` in `AppDatabase`, which wipes all user data on next launch (it is re-populated by the launch rescan). Acceptable for this personal/dev app, but call it out when doing it.
- **Repositories** `AudiobookRepository` (books/files/progress) and `BookGroupRepository` (joined groups) are the only things ViewModels/playback should touch — nothing reaches into DAOs directly outside these.
- **`SettingsStore`** wraps DataStore. It exposes `Flow`s for reactive UI **and** `@Volatile current*` snapshot fields (e.g. `currentSkipForwardMs`, `currentDefaultSpeed`, `currentAnthropicApiKey`) that playback code reads **synchronously** without suspending. Keep both in sync when adding a setting.

### Scanner (`data/scanner/`)
Folder-structure heuristic, applied recursively (`AudioFileScanner`): a folder's **direct** audio files are clustered into books by `clusterBySimilarName` (files sharing a name "stem" after stripping leading/trailing sequence numbers group together; the folder only splits into multiple books when there are ≥2 genuine ≥2-file sequences — otherwise it stays one book). A folder of only sub-folders with >1 child is a **series container** (each child book gets `seriesName` = container name and a 1-based `seriesOrder`). When a folder yields multiple books, each book's `folderPath` is a synthetic `"<dir>::<stem>"` key (used only as the scan-dedup identity — nothing does `File(folderPath)` on it). Metadata (duration, album→title, artist→author, composer→narrator, genre, year, embedded cover) comes from `MediaMetadataRetriever`.

**Chapters**: after files are stored, `buildChapters` creates `Chapter` rows — embedded markers parsed from the file by `ChapterExtractor` (a hand-rolled MP4 `moov/udta/chpl` Nero-atom reader for M4B/M4A; no external dep), or, when none are present, one synthetic chapter per file. Chapter positions are stored as `(fileId, startInFileMs)`; absolute timeline positions are derived at playback time from cumulative file offsets, so they stay valid for both standalone books and joined groups.

**Auto-join** (`AutoJoiner`): after an *explicit user* scan only (`scanDirectory(autoJoin = true)`, wired from `HomeViewModel.startScan`; the silent launch rescan passes `false` so split groups aren't recreated), ungrouped books are automatically merged into `BookGroup`s by three signals — matching numbered-title stems ("Mistborn 1/2"), same `seriesName` with a single shared author, or globally sequential track numbering across same-author books.

Re-scan must **not** reset resume position: `importBook` diffs existing vs. new file paths and only rewrites `AudioFile`/`Chapter` rows when the set actually changed (re-inserting files would `SET_NULL` the `PlaybackProgress.currentFileId` FK and lose the user's place). User-edited series info and covers are preserved (`?: existing?.…`). Scanning runs on every app launch (`HomeViewModel.init`) and on manual rescan.

### Playback (`playback/`)
- **`PlaybackService`** — a Media3 `MediaSessionService` (Hilt `@AndroidEntryPoint`) that owns the single `ExoPlayer`. Audio focus and become-noisy handling are enabled here. It resolves deferred URIs in `onAddMediaItems`, saves position synchronously in `onTaskRemoved` (app swiped from recents), and broadcasts now-playing state to the widget.
- **`PlayerController`** (`@Singleton`) — the app-side `MediaController` wrapper that the UI uses. It exposes a single `PlaybackState` `StateFlow` (synced from a `Player.Listener`) and all transport methods. It is connected/disconnected in `MainActivity` lifecycle.
- **Book-level position mapping**: ExoPlayer holds one `MediaItem` per file; `PlayerController` keeps `cumulativeStartsMs` per file so the UI sees one continuous book timeline. `bookSeekTo()` converts an absolute book position back into `(itemIndex, offsetInFile)`. Scrubbers use book-level position when `bookTotalDurationMs > 0`.
- **Joined groups**: `playBookGroup()` flattens every member book's files into one flat `MediaItem` list. Each item carries `bookId` (the member book) and `groupId` in `MediaMetadata.extras`; during group playback `syncState()` rewrites `currentBookId` to whichever member is currently playing so progress is saved against the right book. There is no group-level progress row — resume uses the most-recently-played member's `PlaybackProgress`.
- **Volume boost** via `LoudnessEnhancer` lives in **`PlaybackService`** (on the real ExoPlayer's audio session — a remote `MediaController` never receives `onAudioSessionIdChanged`, which is the trap to avoid). `PlayerController.setVolumeBoost` forwards the gain as a custom `SessionCommand` (`CMD_SET_BOOST`) that the service's `MediaSession.Callback` applies. **Sleep timer** is a coroutine in `PlayerController` that ticks `sleepTimerRemainingMs` into `PlaybackState`.

### UI (`ui/`)
- **Navigation**: single-Activity (`MainActivity`) with Compose `NavHost`. Routes: `home`, `player/{bookId}`, `search`, `series/{seriesName}`, `settings`, `join_options?bookIds=&groupId=`. There is no bottom-nav graph — the home screen renders its own floating pill nav and routes via callbacks.
- **Theme** (`ui/theme/`): custom warm **amber-on-charcoal** identity. `Color.kt` holds tokens, `Theme.kt` builds the dark/light `ColorScheme`s, `Shape.kt` defines large "squircle" radii + a `Pill` shape, `Type.kt` an expressive scale. **`dynamicColor` is off by default** so the brand stays consistent. **App-wide cover theming**: `BetterAudioTheme(coverArtPath = …)` recolours the *entire* app to the currently-playing book — `MainActivity` passes the playing book's cover path (from `playbackState`), `CoverTheme.kt` (`rememberCoverScheme`) extracts accents via `androidx.palette` and tints the brand surfaces toward them, and `rememberAnimatedScheme` animates the transition. Individual screens (incl. `PlayerScreen`) do **not** self-theme — they inherit this ambient scheme.
- **`HomeViewModel`** builds `gridItems: List<HomeGridItem>` (sealed: `SingleBook` / `Group`) from ungrouped books + joined groups, sorted by last-played. The library is a **flat grid** — there is no folder grouping (every ungrouped book is its own card). Long-press drives a selection mode used to create/split/edit joined groups.
- **Chapters**: `PlayerViewModel.chapters` exposes a `ChapterUiState` of `ChapterRow`s (`BookHeader` / `Item` with an absolute timeline position). For a standalone book it's a flat chapter list; while a joined group plays it interleaves per-book headers with that book's chapters in sequence. `ChapterSheet` seeks via `bookSeekTo(absStartMs)`, which works against whichever timeline (book or group) is loaded.
- Sheets/dialogs (`ChapterSheet`, `SleepTimerSheet`, `BookSettingsSheet`, `ScanBottomSheet`, `SortFilterSheet`, `FolderBrowser`) inherit the theme automatically.

### Widget & AI
- **Widgets** (`widget/`) — three home-screen sizes, each its own `AppWidgetProvider` + layout + `xml/*_info.xml` + manifest `<receiver>`: `NowPlayingWidget` (wide ~4×2), `NowPlayingWidgetTall` (vertical ~2×3), `NowPlayingWidgetCompact` (pill ~3×1). They share `BaseNowPlayingWidget` (lifecycle + the `ACTION_UPDATE_WIDGET` broadcast, resolving each provider's own ids via `javaClass`) and `WidgetRender` (constants, `@Volatile lastState`, and all bitmap rendering). `PlaybackService.broadcastWidgetUpdate` fires `WidgetRender.ACTION_UPDATE_WIDGET` (package-scoped) so every size refreshes together; transport buttons are `PendingIntent.getService` to `PlaybackService`. Each widget recolours to the cover: accent via `Palette`, and the dynamic pieces (rounded/pill card background, circular transport buttons, note badge, rounded cover) are drawn to bitmaps in code — `RemoteViews` can't take a Compose theme — and set with `setImageViewBitmap`.
- **`data/synopsis/SynopsisService`** — calls the Anthropic Messages API (OkHttp, model `claude-haiku-4-5-20251001`) using the key from `SettingsStore`; returns null if no key or on error. Triggered from `PlayerViewModel` when a book is first opened, and the result is persisted to `Book.synopsis` so it generates once.

## Conventions
- All DI is Hilt; new singletons/DAOs go through `di/AppModule`. ViewModels are `@HiltViewModel`.
- Reactive UI is Room/DataStore `Flow` → `stateIn`; one-shot reads use the `*Once` repository methods.
- Media3 + the `MediaController`/`MediaSession` boundary mean a lot of stable APIs require `@UnstableApi` / `@OptIn(ExperimentalFoundationApi/Material3Api)` — match the opt-ins already on the surrounding function.
