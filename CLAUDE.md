# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Better Audio (released as "Voyage") is a native Android audiobook player: it scans a local folder for audio files, organizes them into books, **series**, and authors, plays multi-file books (and whole series) as one resumable timeline, tracks per-book resume position, and integrates with the lock screen, a home-screen widget, and (optionally) Gemini AI for auto-generated synopses. Single Gradle module (`app/`), package `com.betteraudio`. Kotlin + Jetpack Compose + Media3 + Room + Hilt, MVVM. `minSdk = 26`, `compileSdk`/`targetSdk = 35`.

## Build / run / deploy

The custom `gradlew.bat` is **not** the stock wrapper script — its box-drawing comment characters get mis-parsed by `cmd.exe`, so `./gradlew`/`gradlew.bat` often fail with `ClassNotFoundException: GradleWrapperMain`. Always invoke the wrapper jar directly:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # bundled JBR 21
$java = "$env:JAVA_HOME\bin\java.exe"
Set-Location 'D:\code\better audio'
& $java "-Dorg.gradle.appname=gradlew" `
    -classpath "D:\code\better audio\gradle\wrapper\gradle-wrapper.jar" `
    org.gradle.wrapper.GradleWrapperMain :app:compileDebugKotlin --console=plain
```

From the Bash tool, the equivalent one-liner (used throughout this repo):
```bash
cd "D:/code/better audio" && export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" && \
"$JAVA_HOME/bin/java.exe" -Dorg.gradle.appname=gradlew \
  -classpath "D:\code\better audio\gradle\wrapper\gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain :app:compileDebugKotlin --console=plain
```

Common tasks: `:app:compileDebugKotlin` (fastest error check), `:app:assembleDebug` (installable APK at `app/build/outputs/apk/debug/app-debug.apk`), `:app:installDebug` (install on connected device). There is **no test suite**.

`adb` is not on PATH; it lives at `C:\Users\hajmo\AppData\Local\Android\Sdk\platform-tools\adb.exe`. The dev device may be attached over USB or wireless debugging; if `adb devices` is empty, ask the user to reconnect. Its idle screen-off relocks the phone during slow screenshot loops — extend with `adb shell settings put system screen_off_timeout 600000`.

## Releases & channels

- **Beta APKs** are GitHub **pre-releases** (`--prerelease`, `--target beta`); **stable APKs** are standard releases (`--latest`, `--target main`). Both are built with `:app:assembleDebug` — the `release` variant is unsigned and Android rejects it. Attach the APK renamed `Voyage-<version>.apk`. `gh` is at `C:\Program Files\GitHub CLI\gh.exe`; repo is `TheEnderizer/voyage`.
- **Version naming decides the channel at runtime**: beta `versionName` ends in `b` (e.g. `1.7.1b`); stable does not (e.g. `1.7`). Never omit the `b` on a beta build. Bump `versionCode` on every release. As of this writing: stable = **1.7** (code 38), beta = **1.7.1b** (code 39).
- **`data/update/UpdateChecker.kt`** reads `installedVersionName()` from the PackageManager at runtime. A `b` version → beta channel (only prereleases); otherwise → stable. It fetches `/releases?per_page=30` and selects the **highest semantic version** for that channel that has an APK asset — NOT the first entry. This is deliberate: GitHub orders `/releases` by `created_at` (the tag's commit date, not publish date or version), so a release cut from an older tag sorts low; the old "take first prerelease" logic silently missed newer releases. When cutting a release, create a **fresh tag on the current HEAD** so its `created_at` is now.
- Branch workflow: develop on **`beta`**; **`main`** is fast-forwarded/merged from beta only when making a stable release. (Both currently point at the same v1.7 commit.) `changelog_beta.txt` / `changelog_stable.txt` (in `res/raw/`) are shown in Settings → About, picked by the version suffix; add a top entry per release, Keep-a-Changelog format.

## Storage model

Full-filesystem access via `MANAGE_EXTERNAL_STORAGE` and plain `java.io.File` paths — no SAF/`DocumentFile`/tree URIs. Paths are stored in the DB (`Book.folderPath`, `AudioFile.filePath`, `Book.coverArtPath`). Cover art is written **next to the audio** as a hidden file (scanner writes `.cover.jpg`; online-search covers write `.cover_<ts>.jpg` into the book's own folder so they travel with the files), plus a `.nomedia` to hide it from the gallery. `LibraryRestructurer` relies on this (it moves whole folders and rewrites the DB paths). Only online covers for **series/author** (which have no single folder) go to internal `filesDir/covers/`.

## Architecture

### Data layer (`data/`)

**Room DB** `betteraudio.db` (`data/db/AppDatabase.kt`), **version 12**. `fallbackToDestructiveMigration()` is the safety net but **write explicit migrations** and register them in both `AppDatabase.kt` and `di/AppModule.kt`. A present-but-wrong migration crashes on launch (Room validates the resulting schema); it does NOT fall back destructively. To verify a hand-written migration offline: temporarily set `exportSchema = true` + `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`, build `:app:kspDebugKotlin`, and diff your `CREATE TABLE`s against the generated `schemas/.../<v>.json` (Room compares columns by name/type/nullability, not ordinal, so an `ALTER TABLE ADD COLUMN` at the end is fine).

Entities: `Book`, `AudioFile`, `PlaybackProgress`, `Chapter`, `Bookmark`, `AudioPreset`, `ListeningSession`, `SkipEvent`, `Series`, `AuthorMeta`, and the now-inert `BookGroup`/`BookGroupMember`.

**First-class Series (the core of the current design):**
- `Series` — id, name, `author?`/`narrator?` and nullable cascade defaults (`playbackSpeed?`, `boostDb?`, `eqBandsJson?`, `skipSilenceEnabled?`), plus its own `coverArtPath?`/`coverFxPath?`/`description?`. `AuthorMeta` — keyed by author name, holds a per-author `coverArtPath` (authors are a lightweight cover-only grouping).
- `Book.seriesId` (indexed, no hard FK; integrity managed in the repo) is the source of truth for membership; `seriesName`/`seriesOrder` are kept as a denormalized cache. `Book.groupId`/`manualGrouping` are **dead columns** — the old join-group feature is retired (`AutoJoiner` deleted; `BookGroup*`/`BookGroupRepository`/`ui/join/*` are inert leftovers, and the migration nulls out `groupId` and seeds `series` from distinct `seriesName`). Room still lists the `BookGroup` entities, so the empty group tables can't be dropped until those entities are removed.
- `SeriesRepository` owns series CRUD, membership (`getOrCreateSeriesByName`, add/remove/reorder via `seriesOrder`), cover/cascade setters, and `getAudioFilesForBooks` (flatten for playback). `AudiobookRepository` + `BookGroupRepository` are otherwise the only DAO gateways; DAOs aren't touched directly elsewhere.

**Cascade resolution** — `playback/AudioCascade.kt` resolves a member book's effective audio: a per-book value that differs from neutral (speed ≠ 1.0, boost ≠ 0, non-null EQ, skip-silence on) wins; else the series default; else the **global default preset**; else the scalar fallback. This is a deliberate heuristic that avoids an override-flag migration. Series **author/narrator** are propagated onto the member books (written to `authorOverride`/`narrator`) when Series options are saved, so they show everywhere — and grouping/queries use the **effective** author (`displayAuthor` = `authorOverride ?: author`; some queries use `COALESCE(authorOverride, author)`), not the raw scanned author.

**Audio presets** (`AudioPreset`) are unified **bundles** (speed + boost + EQ together); the `type` column is inert legacy. Exactly one preset can be `isDefault` — it is the **global default** fed into `AudioCascade` at play time (in `PlayerViewModel.play()`, `SeriesPlayer`, and `HomeViewModel.playResumeBook`), so it applies to every book unless the book overrides it. Managed in Settings → Audio presets (full CRUD) and the player's `AudioSettingsSheet` (save/apply the whole bundle). There is no longer a standalone "default speed" setting — it lives in the default preset.

**`SettingsStore`** wraps DataStore. Each key needs a `Flow`, (usually) a `@Volatile current*` snapshot for synchronous playback reads, a collector in `init`, and a setter. Keys include `LIBRARY_FOLDER`, `IMPORT_STRUCTURE` (`""` = not chosen → first-run prompt), `APP_THEME` (`""` = not chosen → first-launch theme prompt) / `THEME_COLOR_SOURCE`, `HOME_VIEW_MODE`, `PLAYER_SHOW_SERIES_COVER`, `SKIPPED_UPDATE_VERSION`, skip/rewind/skip-silence config, `DEFAULT_AUDIO_PRESET_ID`, `WIDGET_DEFAULT_COVER_PATH`, `LAST_OPEN_BOOK_ID`, `LAST_PLAYED_BOOK_ID`, `GEMINI_API_KEY`.

### Scanner (`data/scanner/`)

`AudioFileScanner.scanDirectory` dispatches on the user-selected `ImportStructure` (read via `settings.importStructure.first()`):
- **`AUTO`** — the heuristic `scanFolder`: embedded ALBUM tags win (≥2 distinct albums → one book each; one shared album → one book), else `clusterBySimilarName` (filename-stem clustering, splits only on ≥2 genuine ≥2-file sequences), plus single-file volume splitting and disc-split-folder merging. Multi-book folders get a synthetic `"<dir>::<stem-or-album>"` `folderPath` — never `File()` it without checking for `::`.
- **`AUTHOR_SERIES_BOOK`** (`root/author/[series/]book/files`) and **`AUTHOR_DASH_SERIES_BOOK`** (`root/(author - series)/book/files`) — explicit structured walkers; every audio-bearing folder is exactly one book, its files are the chapters (no tag splitting), and it resolves/creates a `Series` for `seriesId`.

Every scan also runs **`reconcileAgainstDisk`**: missing files are dropped (chapters rebuilt), and a book whose files are all gone is hidden (`isIgnored = true`, progress kept) rather than deleted. Guarded so a revoked permission can't mass-hide the library. **`LibraryRestructurer`** (`data/files/`) is the inverse: it moves each book folder to the target computed from the chosen structure + effective author/series, via **copy → verify (file set + sizes) → update DB paths → delete original** (so an interrupted move never loses data), then removes now-empty folders. Chapters: `ChapterExtractor` hand-rolls an MP4 `moov/udta/chpl` Nero-atom reader; `buildChapters` uses embedded markers or one row per file.

### Playback (`playback/`)

**`PlaybackService`** — Media3 `MediaSessionService` owning the single `ExoPlayer`, with a `LoudnessEnhancer` + `Equalizer` that must live on the real ExoPlayer audio session (custom `SessionCommand`s `CMD_SET_BOOST`/`CMD_SET_EQ`). The `MediaSession` is fed a `ForwardingPlayer` whose next/previous seek by time; keep the direct `exoPlayer` ref for effects (do not cast `mediaSession.player`).

**`PlayerController`** (`@Singleton`) — app-side `MediaController` wrapper exposing `PlaybackState`. Syncs in `playerListener` + a 500 ms **position ticker that must run on `Dispatchers.Main`** (MediaController is main-thread-only). `playBook(..., seriesId, seriesBookIds)` carries series-continuation context; on `STATE_ENDED` it marks the book finished and, if in a series, invokes `onSeriesBookEnded` and emits `seriesAdvanced`.

**Series playback is the normal book player, one book at a time** (`playback/SeriesPlayer.kt`, a `@Singleton`): it plays the resume/tapped member as a plain book with the series context; when a book ends, `onSeriesBookEnded` loads and plays the next member, and `PlayerSheet` follows into it (`controller.follow(bookId)` re-targets the open player via the `seriesAdvanced` flow). So there is **one player UI** for books and series — the old flattened-`playBookGroup` path is unused for series. `playSeriesBookAt` jumps to a chapter in another member book (from the whole-series chapter list). `AudioCascade` is applied at play time in both `SeriesPlayer` and `PlayerViewModel.play()`.

### UI (`ui/`)

Single-Activity (`MainActivity`) with a Compose `NavHost`. Routes: `home`, `settings`, `search`, `series/{seriesId}` (Long), `author/{authorName}`; the full player lives in a persistent draggable **`PlayerSheet`** (its own nested NavHost, route `player?bookId=&groupId=`). Cold start restores the expanded player from `LAST_OPEN_BOOK_ID`, or — if the app was closed with only the mini bar showing — restores the collapsed mini bar from `LAST_PLAYED_BOOK_ID` (`PlayerSheetController.restore`, loads paused). The mini bar is hidden on the `settings` route (`PlayerSheet(hideMiniBar=…)`), and a firm downward fling on it calls `PlayerController.stop()` to close the book. The stale `join_options` route/`ui/join` is dead.

- **Theme** — two looks chosen on first launch (and in Settings → Theme), driven by `AppTheme` + `LocalAppTheme` (`ui/theme/AppTheme.kt`): **Material You** (opaque tonal M3; colors from the system wallpaper on API 31+, or the cover, per `ThemeColorSource`) and **Immersive** (the blurred full-screen cover behind translucent screens, with all text accent-tinted). `VoyageTheme(appTheme, colorSource, coverArtPath)` in `MainActivity` applies it; screens branch via the `immersive()` / `appSurfaceColor()` / `appCardColor()` / `scrimTextColor()` helpers (don't add per-screen theme params). When `PLAYER_SHOW_SERIES_COVER` is on and the playing book is in a series, the theme (and player backdrop) use the **series** cover.
- **Home** (`ui/home/`) — a **Books / Series / Authors** view switch (`HomeViewMode`, persisted). `HomeViewModel.buildGridItems` groups `HomeGridItem.{SingleBook,SeriesItem,AuthorItem}` per mode; per-view covers resolve `Series.coverArtPath` / `AuthorMeta.coverArtPath` / book cover. **Multi-select**: long-press any card (books, series, authors — mixed) toggles a typed `SelKey` selection; the selection bar offers Delete (series/authors cascade to all member books, with a delete-files toggle), Add-to-series (1 series + books → adds them and opens the series manager), and a single-selection ⋮ overflow (cover search + book options). A book card's play button starts playback in place (`playResumeBook`) without opening the full player. Status tabs (All/Listening/Not started/Finished) + resume card as before.
- **Series screen** (`ui/series/SeriesDetailScreen`, keyed by `seriesId`) — a manager: Play series, add/remove/reorder/rename, and **Series options** (`SeriesOptionsSheet`: speed/boost/skip-silence/author/narrator). Tapping a member plays the series from that book (`openPlayer` event → `sheetController.open(bookId)`). `ui/author/AuthorDetailScreen` lists a (effective) author's books.
- **Player** (`ui/player/`) — full-bleed cover backdrop (`ReflectedProgressiveBlurCover` + baked `CoverEffectBaker` WebP in `filesDir/cover_fx/`). Overflow menu has a book↔series **cover toggle** when the book is in a series. The chapter list (`ChapterSheet`/`ChapterOverlay`) spans **all member books** when a series is playing (headers per book); tapping a chapter in another book switches to it.
- **Updates** — `ui/update/UpdateGateViewModel` checks once per launch and shows `UpdateAvailableScreen` (Install / Skip; Skip records `SKIPPED_UPDATE_VERSION` so only that version is suppressed). The manual checker in Settings → About is separate.
- Cascade-of-data caveat: because grouping/queries are override-aware, changing an author/series in the app is reflected live; a `Book.author`-based grouping would not be. Use `displayTitle`/`displayAuthor`, never raw `title`/`author`.

### App icon

Adaptive icon (`mipmap-anydpi-v26/ic_launcher*.xml`): navy gradient `@drawable/ic_launcher_background` + a raster foreground `@mipmap/ic_launcher_fg` (the sailboat/book, extracted from the source art and centered on navy at 5 densities). Regenerate the foregrounds with PIL if the art changes. `minSdk 26` means the density-specific legacy vector `ic_launcher.xml`s are never used.

### Widget & AI

Widgets (`widget/`) draw all visuals to bitmaps (RemoteViews can't use a Compose theme); `PlaybackService.broadcastWidgetUpdate` fires updates, and a widget tap deep-links to the active player. When nothing is playing, `WidgetRender.decodeCover` falls back to the user's default cover at `filesDir/widget_default_cover.jpg` (set in Settings → Widget; `WidgetRender.refresh` re-renders after a change) before the built-in placeholder. `SynopsisService` calls Gemini via the DataStore-stored `GEMINI_API_KEY` (never hardcode the `AIzaSy...` key). `CoverSearchService` searches online covers (Google Books → OpenLibrary) and writes book covers into the book's folder.

`SettingsScreen` sections are a `SettingsSection` sealed class dispatched in one `AnimatedContent` `when` (Root, Theme, Library, Playback, Presets, Widget, AI, Updates, About, Diagnostics) — each is a `LazyListScope` extension; adding a section means updating the sealed class, the nav row, the title `when`, and the content `when` together.

## Conventions

- Hilt for all DI; new singletons/DAOs go through `di/AppModule`; ViewModels are `@HiltViewModel`.
- Room/DataStore `Flow` → `stateIn` for reactive UI; one-shot reads via `*Once` repo methods or `.first()`.
- Media3 APIs need `@UnstableApi`; experimental flow operators (`flatMapLatest`) need `@OptIn(ExperimentalCoroutinesApi::class)`; `MutableStateFlow.update {}` needs `import kotlinx.coroutines.flow.update`.
- `LazyListScope` extension "section" functions in `SettingsScreen` receive data as params — don't `collectAsStateWithLifecycle()` in the extension body (do it inside an `item {}`, which is `@Composable`).
