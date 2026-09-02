# Reader revival — task brief

Self-contained starting point for this worktree. Nothing here depends on whatever else was being
worked on in the main tree.

## Goal

**Re-enable the EPUB reader and bring it up to the feature level of Readest — except sync.**

"Except sync" is explicit: the listen↔read position bridge and the forced-alignment work
(`data/transcribe/`, `sync/PositionBridge`, `SyncAligner`, the AI listen↔read model card) stay
exactly as they are. Don't extend them, don't wire new UI to them. This branch is about the
*reading* experience.

## Where things stand

The reader is **built but hidden behind one flag**, not deleted:

```kotlin
// app/src/main/java/com/betteraudio/util/FeatureFlags.kt
const val EBOOKS_UI = false
```

Its own doc comment is worth reading — it gates **UI entry points only**. The reader screen, its
nav route, the parser, and everything stored (a connected `Book.ebookPath`, reading positions, the
ebook folder setting) are all live and intact. Flipping the flag restores the entry points with no
migration and no re-scan.

Ten call sites reference it: `MainActivity` (nav pill section), `HomeViewModel` (pinned to AUDIO
while off), both `FloatingNavPill`s, `BookOptionsSheet` (Ebook section), both player screens
("Read from here"), `LibrarySection` (Ebook folder), `AiSection` (the sync model card — leave that
one off, per "except sync").

What already exists:

| Area | Files |
|---|---|
| Parsing | `data/ebook/EpubParser.kt`, `ParagraphExtractor.kt`, `ParagraphCache.kt` |
| Reader UI | `ui/reader/EbookReaderScreen.kt`, `EbookReaderViewModel.kt`, `ReaderWebView.kt`, `ChapterAlignSheet.kt` |
| Nav | route `reader/{bookId}` in `MainActivity` (~line 503) |

`EpubParser` is a hand-rolled, dependency-free EPUB 2/3 reader (container.xml → OPF → nav/NCX)
built on platform APIs. It refuses DRM-encrypted books via `EpubInfo.encrypted`.
`ParagraphExtractor` splits spine XHTML into normalized paragraphs with char offsets — a tolerant
hand-rolled tag scanner, deliberately not an XML parser, because real EPUB XHTML is often
malformed. Rendering currently goes through a WebView (`ReaderWebView.kt`).

## The reference

Readest source: `C:\Users\hajmo\Desktop\readest-main` (note: `readest-main`, no trailing `j`).

It is a **Tauri + pnpm monorepo — TypeScript/React**, so nothing ports directly; features get
reimplemented in Compose. Structure worth knowing:

- `apps/readest-app` — the application
- `packages/foliate-js` — the actual EPUB rendering engine it builds on. Since our reader is
  already WebView-based, this is the most directly relevant package: it's worth deciding early
  whether to adopt foliate-js inside the existing WebView or to keep rendering our own way.

Read it as a **feature and interaction reference**, not a codebase to translate. Check its licence
before lifting any code verbatim.

## Suggested first step

Survey Readest's reader feature set and produce a concrete list to pick from before building
anything — themes, fonts and layout controls, TOC, bookmarks, highlights and annotations, search,
progress/position handling, pagination vs scroll, and so on. Agree the scope, then implement.
Flipping `EBOOKS_UI` to `true` on day one is a reasonable way to see the current state.

## House rules that apply here

From `CLAUDE.md` in this tree — read it properly, but the ones most likely to bite:

- **Build**: `gradlew.bat` is broken (box-drawing chars). Invoke the wrapper jar directly:
  ```bash
  cd "D:/code/better-audio-reader" && export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" && \
  "$JAVA_HOME/bin/java.exe" -Dorg.gradle.appname=gradlew \
    -classpath "D:\code\better-audio-reader\gradle\wrapper\gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain :app:compileDebugKotlin --console=plain
  ```
- **Per-theme UI split**: anything that looks different between Material You and Immersive gets a
  full implementation in each of `ui/material/**` and `ui/immersive/**`, with the file at the
  original path becoming a thin `when (LocalAppTheme.current)` router. Shared sheets stay unsplit
  until they actually diverge.
- **Disk is the source of truth**; Room is a query cache. Anything precious mirrors into the book's
  own `data/` folder through `DiskMirror`.
- **Room migrations** are hand-written and registered in **both** `AppDatabase.kt` and
  `di/AppModule.kt`. A wrong migration crashes on launch — it does not fall back destructively.
- **adb**: not on PATH (`C:\Users\hajmo\AppData\Local\Android\Sdk\platform-tools\adb.exe`), and
  `export MSYS_NO_PATHCONV=1` before any adb call or paths get mangled.
- Deploy with `adb install -r` — never uninstall to force a fresh install.

## Branch / merge

- This worktree is on branch **`reader`**, branched from `beta` at `f1c6d46`.
- Main tree stays at `D:\code\better audio` on `beta` — leave it alone.
- Merge target is **`beta`**, not `main`.
- `local.properties` and `keystore/` are gitignored and were copied in, so debug *and* release
  builds work here. Don't commit either.
