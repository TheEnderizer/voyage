# Reader revival — Readest feature inventory and implementation plan

Companion to `docs/reader-revival.md`. Part A surveys every reader-side function Readest ships.
Part B is what we already have. Part C is the renderer architecture. Part D is the phased plan.
Summary at the end.

**Reference read**: `C:\Users\hajmo\Desktop\readest-main` (Tauri + Next.js monorepo, TypeScript /
React). Surveyed `apps/readest-app/src/app/reader/**` (188 `.ts`/`.tsx` files),
`src/components/settings/**`, and `src/types/{book,settings,annotator}.ts` — the `ViewSettings`
interface at `types/book.ts:466-478` is the authoritative list of every persisted reader option.

**Decisions taken** (see Part C for why):

1. **Render natively in Compose.** No WebView, no JavaScript, no foliate-js. We parse the EPUB and
   lay it out ourselves.
2. **Scope: novel-grade markup, plus tables and floats** — but *staged*, novel-grade first
   (Phase 1), tables and floats second (Phase 6).
3. **No fallback renderer.** Unsupported markup degrades in place rather than routing the book to a
   second engine.
4. **Listen↔read sync stays frozen**, with one named exception (Part D, Phase 1 item 15).

**Scope rule**: anything requiring the Readest account server or a third-party service account is
**Skip**. Anything desktop-shaped (mouse, keyboard chords, window chrome) is **Skip (desktop)**.
Everything else is Port / Adapt / Later.

---

## Part A — Readest reader function inventory

### A1. Rendering engine and pagination

| # | Function | Where in Readest | Verdict |
|---|---|---|---|
| 1 | Paginated (columnised) rendering — real pages, not a scroll | foliate-js `paginator`, `FoliateViewer.tsx` | **Port** — ours to build |
| 2 | Scrolled (continuous) mode, vertical | `BookLayout.scrolled`, `scrolledDirection` | **Port** |
| 3 | Scrolled mode, horizontal | `scrolledDirection: 'horizontal'` | Later |
| 4 | Continuous scroll across section boundaries | `noContinuousScroll` | **Port** |
| 5 | Scroll overlap in px when paging in scrolled mode | `scrollingOverlap` | Adapt |
| 6 | Webtoon mode (gapless vertical image strip) | `webtoonMode` | Skip — comics |
| 7 | Column count cap (1 / 2 / auto) | `maxColumnCount` | **Port** |
| 8 | Max column width / height | `maxInlineSize`, `maxBlockSize` | **Port** |
| 9 | Writing mode: horizontal-tb / horizontal-rl / vertical-rl | `WritingMode`, `vertical` | Later (CJK) |
| 10 | RTL page direction | `rtl` | Later — harder natively than in a browser |
| 11 | Page-turn animation: push / slide / curl | `PageTurnStyle`, `turnBackdrop.ts` | **Port** (push + slide) |
| 12 | Animation on/off master switch | `ViewConfig.animated` | **Port** |
| 13 | E-ink / colour e-ink mode | `isEink`, `isColorEink` | Skip |
| 14 | Fixed-layout formats (PDF, CBZ) | `FIXED_LAYOUT_FORMATS` | Skip — EPUB only |
| 15 | Zoom level for reflowable text | `BookStyle.zoomLevel` | Adapt → folds into font size |
| 16 | Image viewer — tap an image, full-screen, pinch-zoom | `ImageViewer.tsx` | **Port** — easy natively |
| 17 | Table viewer — wide tables in a scrollable overlay | `TableViewer.tsx` | **Port** — pairs with Phase 6 |
| 18 | Allow/deny scripts inside the book | `BookLayout.allowScript` | **N/A** — nothing executes |

### A2. Typography

| # | Function | Where | Verdict |
|---|---|---|---|
| 19 | Default font size (px) | `BookFont.defaultFontSize` | **Port** |
| 20 | Minimum font size floor | `minimumFontSize` | Adapt |
| 21 | Font weight | `fontWeight` | **Port** |
| 22 | Default font family: serif / sans-serif | `defaultFont` | **Port** |
| 23 | Per-role font pickers: serif, sans-serif, monospace | `FontPanel.tsx` | **Port** |
| 24 | CJK default font | `defaultCJKFont` | Later |
| 25 | Custom user fonts (import a font file) | `CustomFonts.tsx` | Later |
| 26 | "Override book font" | `overrideFont` | **N/A** — we always impose our font |
| 27 | Line spacing | `BookStyle.lineHeight` | **Port** |
| 28 | Word spacing | `wordSpacing` | **Port** |
| 29 | Letter spacing | `letterSpacing` | **Port** |
| 30 | Paragraph margin | `paragraphMargin` | **Port** |
| 31 | Text indent (first line) | `textIndent` | **Port** |
| 32 | Full justification | `fullJustification` | **Port** — free via `TextAlign.Justify` (API 26+) |
| 33 | Hyphenation | `hyphenation` | **Port** — free via `TextStyle(hyphens = Hyphens.Auto)` |
| 34 | "Override book layout" / "Use book layout" | `overrideLayout`, `useBookLayout` | **N/A** — see #26 |

### A3. Margins and page geometry

| # | Function | Where | Verdict |
|---|---|---|---|
| 35 | Independent top/bottom/left/right margins in px | `BookLayout.margin*Px` | **Port** |
| 36 | Compact margin set at small viewports | `compactMargin*Px`, `mobileLayout.ts` | **Port** |
| 37 | Gap between columns as a % | `gapPercent` | **Port** |
| 38 | Content insets — notch / nav bar / keyboard | `useContentInsets.ts` | **Port** |
| 39 | Double border frame around the text block | `doubleBorder`, `DoubleBorder.tsx` | Later |
| 40 | Hide scrollbar | `hideScrollbar` | **N/A** in paged mode |

### A4. Colour, themes, background

| # | Function | Where | Verdict |
|---|---|---|---|
| 41 | Light / dark / auto theme mode | `ThemeModeSelector.tsx` | **Port** |
| 42 | Named colour themes (sepia, grey, etc.) | `styles/themes.ts` | **Port** — trivial natively |
| 43 | Custom theme editor | `ThemeEditor.tsx` | Later |
| 44 | "Override book colour" | `overrideColor` | **N/A** — see #26 |
| 45 | Background textures, opacity, size | `BackgroundTextureSelector.tsx` | Later |
| 46 | In-reader screen-brightness slider | `useScreenBrightness.ts` | **Port** |
| 47 | Brightness by vertical edge-swipe | `useBrightnessGesture.ts` | **Port** |
| 48 | Software brightness overlay below the OS floor | `BrightnessOverlay.tsx` | **Port** |
| 49 | Highlight opacity | `highlightOpacity` | Adapt |
| 50 | Syntax highlighting for `<code>` blocks | `codeHighlighting` | Skip |
| 51 | User stylesheet for book content | `userStylesheet` | **N/A** — no CSS engine to inject into |
| 52 | User stylesheet for the reader UI | `userUIStylesheet` | Skip |
| 53 | Invert images in dark mode | `invertImgColorInDark` | **Port** |
| 54 | Contrast adjustment | `BookStyle.contrast` | Later |

### A5. Navigation

| # | Function | Where | Verdict |
|---|---|---|---|
| 55 | Table of contents, nested/hierarchical | `sidebar/TOCView.tsx`, `tocTree.ts` | **Port** (ours is flat today) |
| 56 | TOC sorted by document order vs by page | `ViewConfig.sortedTOC` | Adapt |
| 57 | TOC auto-scrolls to and highlights the current chapter | `useScrollToItem.ts` | **Port** |
| 58 | Next / previous page | `NavigationPanel.tsx` | **Port** |
| 59 | Next / previous section (chapter) | `NavigationPanel.tsx` | **Port** (have it) |
| 60 | Back / forward through jump history | Go Back / Go Forward | **Port** |
| 61 | Jump to page number / percentage input | `PageJumpInput.tsx` | **Port** |
| 62 | Draggable progress bar to scrub the whole book | `ProgressBar.tsx` | **Port** |
| 63 | Sticky progress bar with per-chapter ticks | `StickyProgressBar.tsx` | **Port** |
| 64 | Tap zones to turn pages, configurable dead centre | `useRendererInputListeners.ts` | **Port** |
| 65 | Swap tap sides | `swapClickArea` | **Port** |
| 66 | Full-screen tap area | `fullscreenClickArea` | **Port** |
| 67 | Tap-both-sides / disable tap-to-paginate | `disableClick` | **Port** |
| 68 | Swipe to paginate, and disabling it | `disableSwipe`, `turnGestureArena.ts` | **Port** |
| 69 | Disable double-tap | `disableDoubleClick` | **Port** |
| 70 | Volume keys turn pages | `volumeKeysToFlip`, `usePagination.ts:492` | **Port** — see Phase 3 caveat |
| 71 | On-screen page navigation buttons | `PageNavigationButtons.tsx` | Adapt |
| 72 | Footnote popup — read a note inline | `FootnotePopup.tsx`, `footnoteHeuristics.ts` | **Port** — easy natively |
| 73 | Internal link following (cross-chapter anchors) | foliate-js `view.goTo` | **Port** |
| 74 | External link handling (confirm, then browser) | `FoliateViewer.tsx` | **Port** |
| 75 | Spatial navigation / D-pad focus | `useSpatialNavigation.ts` | Skip (TV) |
| 76 | Keyboard shortcuts + help sheet | `useBookShortcuts.ts` | Skip (desktop) |
| 77 | Command palette | `components/command-palette/` | Skip (desktop) |
| 78 | Mouse-wheel paging, middle-click autoscroll, cursor hide | `wheelGesture.ts` | Skip (desktop) |

### A6. Position, progress, statistics

| # | Function | Where | Verdict |
|---|---|---|---|
| 79 | Canonical position that survives font/margin/device change | foliate-js CFI, `BookProgress.location` | **Port** — as `(spineIndex, charOffset)`, not CFI |
| 80 | Auto-save reading position, debounced | `useProgressAutoSave.ts` | **Port** (have it) |
| 81 | Progress style: percentage / fraction / reference-page | `progressStyle`, `referencePageCount` | **Port** |
| 82 | Remaining time in chapter and in book | `TimeInfo`, `showRemainingTime` | **Port** |
| 83 | Remaining pages | `showRemainingPages` | **Port** |
| 84 | Section label + page-in-section in the header | `SectionInfo.tsx` | **Port** |
| 85 | Reading-stats tracking (time read, per-session) | `ReadingStatsTracker.tsx` | Later — we have `ListeningSession` |
| 86 | Reading status: unread / reading / finished / abandoned | `ReadingStatus` | Adapt — we have `BookStatus` |
| 87 | Auto-save the book cover on first open | `useAutoSaveBookCover.ts` | Have it (`extractCoverBytes`) |
| 88 | Per-book config **schema version** with deprecation handling | `BOOK_CONFIG_SCHEMA_VERSION = 3`, `types/book.ts:565` | **Port** |

### A7. Selection, annotation, notebook

| # | Function | Where | Verdict |
|---|---|---|---|
| 89 | Text selection with drag handles | `useTextSelector.ts`, `SelectionRangeEditor.tsx` | **Port** — mostly framework-provided, see C.4 |
| 90 | Selection popup toolbar, position-aware | `AnnotationPopup.tsx` | **Port** |
| 91 | Highlight, 5 default colours + custom hex | `HighlightColor`, `HighlightColorsEditor.tsx` | **Port** |
| 92 | Highlight styles: highlight / underline / squiggly | `HighlightStyle` | **Port** |
| 93 | Annotate — highlight plus a typed note | `NoteEditor.tsx` | **Port** |
| 94 | Bookmark the current page (toggle + pull-down) | `BookmarkToggler.tsx`, `bookmarkPullGesture.ts` | **Port** |
| 95 | Bookmark ribbon on the page corner | `Ribbon.tsx` | **Port** |
| 96 | Excerpt — copy a passage into the notebook | `BookNoteType: 'excerpt'` | **Port** |
| 97 | Copy selected text | `AnnotationTools.tsx` | **Port** |
| 98 | Copy a deep link to the selection | `utils/deeplink.ts` | Later |
| 99 | Share selected text | `share` tool | **Port** |
| 100 | Global annotation — same highlight on every occurrence | `BookNote.global` | Later |
| 101 | Instant annotation — one-tap highlight, no popup | `useInstantAnnotation.ts` | Adapt |
| 102 | Quick-action: one tool fires straight on selection | `QuickActionMenu.tsx` | **Port** |
| 103 | Customisable selection toolbar | `AnnotationToolbarCustomizer.tsx` | Later |
| 104 | Notebook panel — all notes for the book, searchable | `notebook/Notebook.tsx` | **Port** |
| 105 | Annotations sidebar grouped by chapter, filterable | `BooknoteView.tsx` | **Port** |
| 106 | Tap an annotation to jump, with a flash highlight | `transientHighlight.ts` | **Port** |
| 107 | Next / previous **annotation** navigation while reading | `useBooknotesNav.ts` | **Port** |
| 108 | Inline note-text editing in place | `useInlineTextEditor.ts` | Adapt |
| 109 | Edit an annotation's range after the fact | `AnnotationRangeEditor.tsx` | Later |
| 110 | Cross-document selection (spanning a section boundary) | `crossDocSelection.ts` | Later |
| 111 | Magnifier loupe while dragging a handle | `MagnifierLoupe.tsx` | Later |
| 112 | Export annotations — Markdown / text / JSON | `ExportMarkdownDialog.tsx` | **Port** (Markdown + JSON) |
| 113 | Import annotations back from JSON | `ImportAnnotationsDialog.tsx` | **Port** |
| 114 | Copy-to-notebook on every copy | `copyToNotebook` | Adapt |
| 115 | Clear all annotations for a book | `AnnotationsToolbar.tsx` | **Port** |

### A8. Search

| # | Function | Where | Verdict |
|---|---|---|---|
| 116 | In-book full-text search | `sidebar/SearchBar.tsx` | **Port** — easy, we own the text |
| 117 | Scope: whole book vs current chapter | `BookSearchConfig.scope` | **Port** |
| 118 | Mode: contains / whole-words / regex / nearby-words | `SearchMode` | **Port** (contains + whole-words) |
| 119 | Match case | `matchCase` | **Port** |
| 120 | Match diacritics | `matchDiacritics` | **Port** |
| 121 | "Within N words" proximity search | `nearbyWords` | Later |
| 122 | Result list with snippet + chapter, tap to jump | `SearchResults.tsx` | **Port** |
| 123 | Next/previous match navigation while reading | `useSearchNav.ts` | **Port** |
| 124 | Search selected text | `search` tool | **Port** |
| 125 | **Library-wide** full-text search across every book | `LibrarySearchConfig`, `types/book.ts:515-556` | Later |

### A9. Reading aids

| # | Function | Where | Verdict |
|---|---|---|---|
| 126 | Auto-scroll with speed control and overlay | `useAutoScroll.ts`, `autoscroller.ts` | **Port** |
| 127 | Auto-scroll speed by drag gesture | `useAutoScrollSpeedGesture.ts` | Adapt |
| 128 | Auto-scroll resumes if closed mid-session | `autoScrollRunning` | **Port** |
| 129 | Auto page-turn (timed page flip) | `useAutoPageTurn.ts` | **Port** |
| 130 | Reading ruler — band tracking N lines, colour/opacity | `ReadingRuler.tsx` | **Port** — trivial natively, we know line boxes |
| 131 | Paragraph mode — one paragraph at a time | `useParagraphMode.ts` | Later |
| 132 | RSVP speed reading — one word at a time | `rsvp/` | Later |
| 133 | Word Lens — inline gloss above hard words | `WordLensPanel.tsx` | Skip (server model) |
| 134 | Keep screen awake | `ControlPanel.tsx` | Have it |
| 135 | Screen orientation lock | `ScreenConfig.screenOrientation` | **Port** |
| 136 | Fullscreen / immersive mode | `ViewMenu.tsx` | **Port** |
| 137 | Proofread rules — regex find/replace on rendered text | `ProofreadRules.tsx` | Later |

### A10. Read-aloud / TTS

| # | Function | Where | Verdict |
|---|---|---|---|
| 138 | TTS read-aloud with rate control | `tts/TTSControl.tsx` | **Port** — Android `TextToSpeech` |
| 139 | Voice picker | `ttsVoice` | **Port** |
| 140 | Sentence gap / paragraph gap pauses | `ttsSentenceGap`, `ttsParagraphGap` | **Port** |
| 141 | Follow-along highlight at word / sentence / paragraph | `TTSHighlightGranularity` | **Port** — easy natively |
| 142 | Configurable TTS highlight colour | `customTtsHighlightColors` | Adapt |
| 143 | TTS mini-player, auto-hiding | `TTSMiniPlayer.tsx` | Adapt — reuse our mini bar |
| 144 | Full TTS player sheet with scrubber and chapter list | `TTSPlayerSheet.tsx` | Adapt |
| 145 | Media-session / lock-screen metadata for TTS | `TTSMediaMetadataMode` | **Port** — not free, see Phase 8 |
| 146 | Sleep-timer countdown label | `useCountdownLabel.ts` | Adapt — we have a sleep timer |
| 147 | Speak the current selection | `tts` annotation tool | **Port** |
| 148 | EPUB 3 Media Overlays — the book's recorded narration | `ttsUseNarration`, `Book.hasNarration` | **Port** |
| 149 | Downloadable neural voices | `useTTSDownloads.ts` | Skip (server) |
| 150 | Audiobook pairing / chapter picker in the reader | `AudiobookPairingDialog.tsx` | **Frozen** — our `ChapterAlignSheet`, see Phase 0 |

### A11. Header, footer, status bar

| # | Function | Where | Verdict |
|---|---|---|---|
| 151 | Show/hide header bar; book title in it | `showHeader`, `HeaderBar.tsx` | **Port** (have it) |
| 152 | Show/hide footer bar | `showFooter`, `FooterBar.tsx` | **Port** |
| 153 | Current time in the footer, 12/24h | `useCurrentTime.ts` | **Port** |
| 154 | Battery status + percentage | `useCurrentBattery.ts` | **Port** |
| 155 | Progress info block in the footer | `StatusInfo.tsx` | **Port** |
| 156 | Chrome auto-hides on page turn / inactivity | `footerBand.ts` | **Port** |
| 157 | Mobile vs desktop footer layouts | `MobileFooterBar.tsx` | Mobile only |
| 158 | Quick panels: font+layout, colour, navigation | `FontLayoutPanel.tsx`, `ColorPanel.tsx` | **Port** — main in-book settings surface |
| 159 | Hint toasts and first-run tips | `HintInfo.tsx`, `PageTurnHint.tsx` | Adapt |

### A12. Book-level and library-level

| # | Function | Where | Verdict |
|---|---|---|---|
| 160 | Per-book vs global view settings, with "apply to all" | `ViewSettingsConfig.isGlobal` | **Port** |
| 161 | Reset a book's settings back to global | `ViewMenu.tsx` Reset | **Port** |
| 162 | Multi-book / parallel read | `useBooksManager.ts` | Skip |
| 163 | Book menu inside the reader | `sidebar/BookMenu.tsx` | **Port** |
| 164 | Share the book | `ViewMenu.tsx` | Adapt |
| 165 | Reload the current page | Reload Page | Adapt (debug) |
| 166 | Dictionary lookup on selection | `services/dictionaries/` | Later |
| 167 | Translate selection / page | `TranslatorPopup.tsx` | Skip (server) |
| 168 | AI assistant over the book | `notebook/AIAssistant.tsx` | Skip (server) |
| 169 | Chinese variant conversion, quote replacement | `BookLanguage`, `simplecc-wasm` | Skip |
| 170 | Readest cloud sync; Readwise; Hardcover; WebDAV; Drive; OneDrive; S3; Audiobookshelf; OPDS; BookOrbit; LocalSend | `hooks/use*Sync.ts`, `settings/integrations/` | **Skip — server-dependent** |
| 171 | **KOReader (KOSync) progress sync** | `useKOSync.ts`, `KOSyncForm.tsx`, `types/kosync.ts` | **Later — genuinely feasible, see C.6** |
| 172 | Formats beyond EPUB: PDF, MOBI, AZW3, CBZ, FB2, TXT, MD | `BookFormat` | Skip for v1 |

### Inventory totals

172 rows. Counting the verdict column literally:

| Verdict | Rows |
|---|---|
| Port | 104 |
| Adapt | 18 |
| Later | 23 |
| Skip | 17 rows — but #170 and #172 each bundle many items (11 services, 7 formats) |
| N/A under native rendering | 6 |
| Have it / Frozen / Mobile-only | 4 |

**Port + Adapt = 122 functions to build.** Six items (#18, #26, #34, #40, #44, #51) are
*deleted* rather than deferred: they exist only to negotiate with a browser's CSS cascade, and we
don't have one.

---

## Part B — What we already have

`FeatureFlags.EBOOKS_UI` is already `true` in this worktree (uncommitted), and `EBOOK_SYNC_UI` has
been added and set to `false`.

> ⚠️ **`EBOOK_SYNC_UI` currently gates nothing in the reader.** Its KDoc
> (`util/FeatureFlags.kt:22-27`) claims it covers the reader's "Improve sync" / "Align chapters" /
> "Import mapping" affordances. It does not. Its only reference in the codebase is
> `ui/settings/AiSection.kt:139`. `ui/reader/EbookReaderScreen.kt` contains **zero** `FeatureFlags`
> references, so with `EBOOKS_UI = true` the whole frozen sync surface is live: "Align chapters"
> (`:184`), "Improve sync (on device)" (`:190` — triggers the Vosk model download at
> `EbookReaderViewModel.kt:470`), "Import Mapping Data" (`:204`), the sync status chips
> (`:255-283`), and the download dialog (`:287-313`). `EbookReaderViewModel.kt:121-132` also
> subscribes to `syncAligner.progress` and calls `modelManager.refreshState()` on every open.
> **Phase 0 item 1.**

| Piece | File | Fate under native rendering |
|---|---|---|
| EPUB parse (container → OPF → nav/NCX) | `data/ebook/EpubParser.kt` (305) | **Keep** — zip access, metadata, cover, spine |
| Paragraph extraction | `data/ebook/ParagraphExtractor.kt` (151) | **Keep, frozen** — the sync path depends on its char offsets |
| Paragraph cache | `data/ebook/ParagraphCache.kt` | Keep |
| Rendering | `ui/reader/ReaderWebView.kt` (166) | **Delete** — replaced entirely |
| Reader screen | `ui/reader/EbookReaderScreen.kt` (412) | Rewrite; strip the frozen sync surface first |
| Reader VM | `ui/reader/EbookReaderViewModel.kt` (537) | Rewrite around the new pipeline |
| Position storage | `PlaybackProgress.textSpineIndex/textFraction/textOverallFraction/lastMode` | Extend — Room **and** disk (`BookDocument.ProgressEntry`, `:104-107`) |
| Library integration | `data/scanner/EbookScanner.kt`, `HomeSection.EBOOKS` | Keep; fix the attach path (Phase 4) |
| Bookmarks | `Bookmark` entity | **Audio-only** (`fileId`, `positionInFileMs`) — needs a separate table |
| Theme awareness in the reader | — | **None.** No `LocalAppTheme` in `ui/reader/`, no `reader` package under `ui/material/` or `ui/immersive/` |

Room is at **version 22** (`AppDatabase.kt:78`); schemas 19–22 are committed.

---

## Part C — Renderer architecture

### C.1 — Why native, and what it costs

A JavaScript-off WebView can be told where to go but cannot be asked where it is. It reports a
scroll offset and a content height — a ratio of a *rendered* layout. There is no API to ask "which
paragraph is at the top of the screen", and no way to learn where the user selected text. That one
missing question is what makes stable highlights, precise bookmarks, accurate progress,
TTS follow-along and the reading ruler impossible. Rewriting HTML on the way out (which we already
do, `ReaderWebView.injectCss:104-111`) gets us CSS, anchors and even `<mark>` highlights — but
information only flows *into* the page, never back.

The honest case for native rendering is therefore:

- **Position introspection.** We know where every glyph is because we put it there. Everything in
  A6, A7, A8 and #141 follows from that.
- **Testability.** Every stage is a pure function of its input; the WebView made all of it
  untestable.
- **No third-party build chain.** foliate-js ships raw ES modules consumed through Readest's own
  bundler (`next.config.mjs:98`, `transpilePackages`), which would mean adding a Node bundler to a
  Gradle-only repo.

**Two arguments I made for native rendering earlier were weaker than I stated, and are withdrawn:**

- *"foliate's iframes are not sandboxed and cannot be."* The first half is true of Readest's code;
  the second is not. `sandbox="allow-same-origin"` **without** `allow-scripts` grants the parent
  same-origin DOM and `Range` access — everything CFI needs — while blocking book-supplied
  JavaScript. The `iframe.contentWindow?.eval(...)` at `FoliateViewer.tsx:487-500` is
  `evalInlineScripts`, Readest's *opt-in* `allowScript` feature (inventory #18), not proof that
  script execution is structurally required. The security cost of the foliate path was real but
  smaller than I claimed.
- *"No third-party licence to clear."* foliate-js is MIT. That was never a cost.

Native rendering is still the right call on the first three grounds. It should not be defended on
the fourth and fifth.

**What it costs, stated plainly:**

- **We are writing a layout engine.** Not a full one, but a real one. KOReader's crengine — a
  mature C++ engine — added float support and margin collapsing in
  [PR #299](https://github.com/koreader/crengine/pull/299): ten commits, four documented
  limitations plus two edge cases still open at merge, shipped behind four render modes.
- **Complex books will render imperfectly.** Novels are fine. Textbooks and heavily-styled books
  will be approximations.
- **Float support has no Android API** (C.5) — the single largest technical obstacle here.
- **It is the longest path to a first readable screen.** Phase 0's spike exists to de-risk that.

### C.2 — The pipeline

```
EPUB zip → EpubParser (exists)              : zip access, OPF, TOC, cover
         → EpubDocumentParser               : XHTML → box tree; emits BOTH char streams (C.3)
         → StyleResolver                    : our stylesheet + a CSS subset → computed style
         → BlockLayout                      : block stacking, margins, float geometry
         → Paginator                        : blocks → pages
         → ReaderPage (Compose)             : per-block BasicText nodes in a custom Layout (C.4)
```

Every stage above the Compose layer is a pure function of its input plus style and viewport, which
is what makes the whole thing unit-testable.

### C.3 — Two char streams, and the projection between them ⚠️

**This section replaces an earlier claim that was simply false.** The previous draft said every
position would be "a character offset into the source XHTML text… exactly what `ParagraphExtractor`
already produces." Those are two different things, and only the second describes the code.

`ParagraphExtractor.flush()` (`ParagraphExtractor.kt:82`) runs every block through
`TextSimilarity.normalize`, which is (`sync/TextSimilarity.kt:13-18`):

```kotlin
private val PUNCT_REGEX = Regex("""[^a-z0-9\s]""")
fun normalize(s: String): String =
    PUNCT_REGEX.replace(s.lowercase(), " ").let { WHITESPACE_REGEX.replace(it, " ") }.trim()
```

The character class is `a-z0-9\s` and it runs **after** `.lowercase()`. So the extractor's stream is
lowercased, punctuation-free and **ASCII-only**: every `é`, `ü`, `ñ`, `—`, `"`, `'` and every
Cyrillic, Greek or CJK character becomes a space and then collapses. The `&mdash;`/`&ldquo;`
entities that `decodeEntities` (`:120-124`) carefully decodes are stripped back out one line later.
On top of that:

- `:84` — `if (normalized.length < 2) return` **drops** any block normalising to under two
  characters. That text contributes nothing and is unaddressable.
- `:85` — paragraphs joined by exactly one space regardless of source.
- `:63-66` — `BLOCK_TAGS` omits `th`, `caption`, `table`, `tr`, `section`, `article`, `figure`,
  `ul`, `ol`; text in those merges into neighbours.
- `:70` — hardcoded `Charsets.UTF_8`, ignoring the XML prolog.
- `:95` — `break` on an unmatched `<` silently discards the rest of the document.

A renderer must draw `é`, `"`, capital letters and one-character paragraphs. **It therefore cannot
produce offsets identical to this stream.** The invariant as previously written was not risky, it
was impossible.

**The correct model is two coordinate systems and an explicit projection.**

| Stream | Content | Used for |
|---|---|---|
| **Render stream** | The real text: full Unicode, punctuation, case, every block | Reading position, bookmarks, highlights, search, TTS cursor, footnotes — **everything the reader owns** |
| **Extractor stream** | `ParagraphExtractor`'s normalised ASCII stream | The frozen sync path only — `PositionBridge`, `SyncAligner`, `SyncAnchor.charOffset` |

**How the projection is built — and how it must *not* be.** The obvious design is to have
`EpubDocumentParser` emit both streams from one pass. That is wrong, and would be the most dangerous
kind of wrong: for the extractor stream to be usable at the sync seam it must be **byte-identical**
to what `ParagraphExtractor` produced when `SyncAligner` wrote the `SyncAnchor.charOffset` rows
already on disk. Re-deriving it means reproducing, exactly:

- flush on both open **and** close of every `BLOCK_TAGS` element, including every `<div>` (`:109`)
- the `< 2` drop (`:84`), which depends on those exact boundaries
- the `SKIP_CONTENT` depth rules with the self-closing check (`:103-106`), where `title` also
  matches an inline SVG `<title>`
- the 13-entity table (`:120-124`) — so `&auml;` stays six literal characters and normalises to
  `auml`, **four** extractor characters where the render stream has one (`ä`)
- `Charsets.UTF_8` regardless of the XML prolog (`:70`)
- `break` on an unmatched `<` (`:95`), truncating the extractor stream while the render stream
  continues

A structurally correct box-tree parser differs on nearly all of these *by construction* — it needs
`table`, `section`, `figure`, `th`, `caption`, `ul`, `ol` as blocks, must decode the full entity set,
and must honour the prolog encoding.

**So: call `ParagraphExtractor.extract()` unchanged** (through `ParagraphCache`, which already does
exactly this) and build the projection by **aligning its `Paragraph.normalizedText` runs against the
render stream's text runs**. Cheap, and guaranteed consistent with anchors already on disk.

The projection `renderOffset → extractorOffset` is monotone and many-to-one (several render
characters map to one extractor character; dropped blocks map to the nearest surviving boundary).
**The inverse** is the smallest render offset whose forward projection is ≥ the target — deliberately
paragraph-granular, so opening from audio lands at a paragraph start rather than mid-paragraph. That
is a visible change from today's fractional scroll restore and is an accepted trade, not a surprise.

**When alignment fails** — a truncated tail, an encoding mismatch, entity divergence — fall back to
`charOffsetForFraction` rather than emitting a confidently wrong offset. The corpus test catches
divergence in the lab; the app needs an answer in the field.

Three consequences:

1. **Reader positions live in the render stream**, so they are well-defined for every language.
   The earlier design would have made positions degenerate for French, German, Russian or Chinese
   books, whose extractor streams are nearly empty and whose paragraphs are largely dropped by the
   `< 2` rule. Nothing in the previous draft noticed this.
2. **Sync accuracy for non-English books is unchanged** — already limited, since the ASR model is
   English-only.
3. **The Phase 1 item 8 test asserts projection correctness, not equality**: projecting a render
   offset must land in the same paragraph the extractor reports. Asserting equality would fail on
   book one. **It must also bound intra-paragraph drift** — an `&auml;`-class divergence keeps you in
   the right paragraph while putting the offset arbitrarily far off *within* it, and
   `PositionBridge.charToAudio` interpolates within a spine item, so intra-paragraph error goes
   straight into the audio seek.

**Unit mismatch to watch**: `EbookReaderViewModel.kt:457` calls
`PositionBridge.charToAudio(spineIndex, charOffset, paras.totalChars, …)`, where `totalChars` is the
*extractor* stream's length, and persisted `SyncAnchor.charOffset` rows are in extractor
coordinates. Handing those a render offset would be a silent unit error. The projection must exist
before the sync boundary is rewired, not after.

**The empty-extractor case.** `audioPositionToLocator` (`:438`) and `locatorToAudio` (`:449-450`)
both branch on `paras == null || paras.totalChars == 0` and fall back to a `TextLocator(spineIndex,
fraction)`. That branch fires for image-only chapters and — per the analysis above — for many
non-English chapters whose blocks are all dropped by the `< 2` rule. When there are no extractor
coordinates there is no fraction to project either, so **derive it from the render offset over the
render stream's length**. Name it explicitly; it is not a rare path.

`ParagraphExtractor` and `TextSimilarity` are **not modified** — they are frozen-sync inputs, and
their normalisation is deliberate (it exists so ebook text and ASR output normalise identically).

### C.4 — Composables with a custom layout, not a bare Canvas

An earlier draft claimed the choice was between `Text` in a `LazyColumn` and drawing everything to
a `Canvas`, and that paged reading forced the Canvas. That was a false binary.

There is a third option, and it is the right one: **own the pagination and block layout, then place
per-block `BasicText` nodes inside a page-sized container via a custom `Layout`.** Pagination is
orthogonal to whether glyphs reach the screen through `drawText` or through a text layout node.

**Plain `Layout`, not `SubcomposeLayout`.** `Paginator` decides a page's contents *before*
composition — that is the whole point of C.2 — so there is nothing to subcompose during measure.
`SubcomposeLayout` would be slower, cannot supply intrinsics (which C.5's tables need), and
complicates semantics merging.

| | Canvas | Composable blocks + custom `Layout` |
|---|---|---|
| Real pages | ✅ | ✅ |
| Selection | hand-built (weeks) | `SelectionContainer` — offsets **are** public, see below |
| Accessibility | hand-built, one semantics node | per-block semantics + `GetTextLayoutResult`, near-free |
| Justification | assumed hand-built — **wrong**, see below | `TextAlign.Justify` |
| Hyphenation | hand-built | `Hyphens.Auto` |
| Block split across a page | free (draw a line subrange) | **needs a mechanism** — see below |
| Text measured once per page | ✅ | ❌ twice — see below |
| Float wrap | possible | **not possible** — see C.5 |

**Selection offsets are available through public API** — verified against the Compose BOM this
project pins (`2026.06.00`, `libs.versions.toml:8`) by inspecting the resolved `foundation` artifact:

```
SelectionContainer(Modifier, Selection, Function1<Selection, Unit>, content)   // public overload
Selection.getStart()/getEnd() -> Selection.AnchorInfo
Selection.AnchorInfo.getOffset(): int      // character offset within the selectable
Selection.AnchorInfo.getSelectableId(): long
```

So `onSelectionChange` hands back start/end as `(selectableId, offset)` pairs — exactly what
mapping to render-stream offsets needs. Two caveats that are real: `selectableId` is assigned by the
registrar in composition order, so the block↔id mapping must be established rather than assumed
(**verify in the Phase 0 spike**); and `SelectionContainer` only spans one composition subtree, so
**a selection cannot straddle a page break** if only the current page's blocks are composed. That is
a normal reader expectation and needs a decision in Phase 4 — compose an off-screen neighbour page,
or accept per-page selection.

**Splitting a block across a page boundary** has no free mechanism here, and it happens constantly
in a novel. `BasicText` has no `startLine`, and `maxLines` truncates only from the top. Three
options: never split a block (leaves pages half-empty — unacceptable); split the text and emit two
`BasicText`s (the second half re-breaks independently, so the render can disagree with what
`Paginator` computed, and justification turns the first half's last line flush-right); or **render
the full block in a clipping container at a negative Y offset** — visually correct, one layout, but
the semantics node then claims the whole paragraph on *both* pages and selection hit-testing extends
into the clipped region. **Take the third with explicit `semantics` and hit-bounds overrides**, and
write it into Phase 1 item 6 rather than discovering it there.

**Text is measured twice.** `Paginator` runs off the main thread and needs each block's height, so
it lays the block out via `TextMeasurer`. `BasicText` then lays out *again* in the composition
measure pass — it owns its own `ParagraphLayoutCache` and does not consult our `TextMeasurer`'s
cache. Two full text layouts per block, per page. The mitigations are: accept it (measure it in the
spike), or hand `Paginator`'s `TextLayoutResult`s to `drawText` for text blocks, which reintroduces
Canvas for those blocks along with their selection and semantics costs. **This is the central
tension of this decision and the Phase 0 gate must measure it.**

**Correction on justification**: `TextMeasurer.measure()` takes a `TextStyle`, honours
`TextAlign.Justify` in the returned `TextLayoutResult`, and is backed by `StaticLayout`'s
`JUSTIFICATION_MODE_INTER_WORD` — available from **API 26**, exactly this app's `minSdk`. Budgeting
bespoke inter-word slack arithmetic was an error. The same applies to hyphenation via
`TextStyle(hyphens = Hyphens.Auto)`, which uses the platform's own dictionaries.

**So: Phases 1–5 run on the composable path.** Only floats force manual glyph placement, and that is
Phase 6. Taking on hand-built selection and accessibility from Phase 1 to enable a capability we
don't intend to build for months would have been the wrong trade — but note that Phase 6 brings a
chunk of that cost back (C.5).

### C.5 — Floats are the real obstacle, and they change the model

`float: left/right` with text wrapping means **line width varies within a single paragraph**.
Android's text stack (`Paragraph` → `AndroidParagraph` → `android.text.StaticLayout`) measures a
paragraph into **one rectangle of fixed width**. There is no exclusion-rectangle API (unlike iOS's
`NSTextContainer.exclusionPaths`), no ragged-width line breaker, and no way to resume a
half-laid-out paragraph at a different width.

Doing floats properly means, **for the affected paragraphs only**, abandoning whole-paragraph
measurement for per-word measurement and manual placement — which costs cross-run shaping, kerning,
StaticLayout's line-breaking quality, and correct bidi reordering.

Three consequences the plan must carry:

1. **The manual path reaches further than "the paragraph next to the image".** A float's influence
   runs until `clear`, or until its bottom edge is passed. A tall floated figure at the top of a
   section affects *every block beside it* — often most of a page. The rule is **every block whose
   vertical extent intersects an active float rectangle**, which is a much larger set than
   "adjacent".
2. **Manually placed text loses two things the framework was giving us, and one abstraction does not
   fix both.** The mitigation named in Phase 4 — a single interface over "where is character N on
   screen" — is a *geometry* query. It correctly serves the reading ruler, search highlighting and
   highlight painting. It does **not** serve:
   - **Selection.** Canvas-drawn text does not participate in `SelectionRegistrar`, and
     `SelectionContainer` has no extension point for manually placed glyphs. On a page containing
     one float-affected paragraph, selection either dies for that paragraph or the hand-built
     selection path C.4 avoided has to be built after all.
   - **Accessibility.** No `BasicText` node means no `Text` semantics and no `GetTextLayoutResult`,
     so Phase 3's accessibility work must be redone for the manual path.
   **Phase 6 therefore contains a second implementation of selection and accessibility**, and must
   be budgeted as such rather than as one rework line.
3. **The cheaper alternative is already licensed by decision #3** ("unsupported markup degrades in
   place"): render float-affected blocks full-width and ignore the float. One layout path, no second
   selection or semantics implementation, and consistent with the no-fallback-renderer stance. The
   cost is that figures sit above their text instead of beside it — which is what most mobile
   readers do anyway at phone widths. **This should be the Phase 6 default, with true float wrapping
   as an explicit opt-in only if the degraded version proves unacceptable in the corpus.**

This is the largest technical risk in the plan and the reason floats are staged late.

### C.6 — KOReader (KOSync) interop: feasible, but not cheap

KOSync is **not** a Readest service — it is KOReader's own protocol, with a free public server and a
self-hostable one, so it is not excluded by the no-server rule the way #170 is. Readest implements
it (`useKOSync.ts`, `KOSyncForm.tsx`, settings at `types/settings.ts:51`).

Two things are needed:

1. **A matching document hash.** `KOSyncChecksumMethod = 'binary' | 'filename'`
   (`types/settings.ts:51`); `KOSyncForm.tsx:242` exposes only `binary`. A partial MD5 sampled at
   fixed offsets — straightforward.
2. **A compatible xpointer.** This is where an earlier draft was materially wrong. It is *not*
   "the same shape as our anchor":
   - crengine's `text().42` is an offset **inside one text node**, not into a spine-wide stream.
     Converting needs per-text-node character accounting plus crengine's own whitespace rules.
   - The path step is `tag[nth-of-type among effective siblings]`, not "index within parent"
     (`utils/xcfi.ts:481-520`).
   - Readest's converter is **806 lines** (`utils/xcfi.ts`) and needs `cfi-inert`/`cfi-skip`
     transparency rules (`:440-505`) purely to emulate crengine's wrapper-less DOM.
   - The real failure mode is far worse than off-by-one: `xcfi.ts:692-703` documents that crengine
     "does **NOT** number its DocFragments in strict bijection with" foliate's sections — linked
     and embedded files and fragment splitting cause drift — citing a real case of
     `DocFragment[326]` resolving to section **274**, fixed with a size-based drift heuristic
     (`:742-760`).
   - KOReader also versions its DOM per book; that, not a numbering bug, is why xpointers break
     across engine changes, and it is what koreader#5117's "use legacy rendering for previously
     opened books to not mess with bookmarks" is actually about.

`xpointer0`/`xpointer1` on `BookNote` belong to the **notes** sync path
(`useBookOrbitNotesSync.ts:75-76`); KOSync progress uses a single `config.xpointer`
(`useKOSync.ts:122-143`).

**Verdict: feasible later, but a real project — an xpointer converter, a drift heuristic, the HTTP
protocol and a settings page.** Phase 10.

**What to do now:** one constraint, and it genuinely is nearly free — have `EpubDocumentParser`
retain **source element order, tag name, and nth-of-type index among siblings**, plus the char
offset of every element `id`. That is needed for fragment anchors anyway (Phase 1 item 9), so
KOSync rides along on work already required.

### C.7 — Accessibility

On the composable path this is far cheaper than a Canvas would have made it: each block is a real
layout node with real bounds, so per-block `semantics` and TalkBack traversal come mostly free.
What still needs doing deliberately:

- `GetTextLayoutResult` on each block, without which TalkBack has no character/word/line
  granularity — the standard reading-navigation gestures.
- `traversalIndex` so reading order follows document order, not layout order.
- Continuous reading across a page turn.
- Honouring `Density.fontScale` and system display size — a renderer using px margins (#35) and px
  font sizes (#19) ignores both unless handled.

Phase 4, verified with TalkBack actually on.

### C.8 — Performance and memory

- Layout runs off the main thread and must be cancellable when the user flips fast. **`TextMeasurer`
  is not documented as thread-safe** (its internal layout cache is unsynchronised) and font
  resolution for non-system fonts is asynchronous. Whether it can be driven safely off the main
  thread is a Phase 0 gate question, not a Phase 1 discovery.
- Cache the **pagination result** — which blocks land on which page, and their measured heights —
  keyed by `(spineIndex, styleHash, viewportSize)`; invalidate on style or size change. Note this
  saves the `Paginator` pass, **not** `BasicText`'s own layout, which reruns on every composition
  (C.4's double measure).
- Images decode lazily and downsample to the viewport.
- A single enormous paragraph is not interruptible mid-`StaticLayout`; pathological spine items need
  a measured answer, not an assumption.

---

## Part D — Implementation plan

House rules that shape every phase: **disk is the source of truth**, so every storage change needs a
`BookDocument` field, `BookDataCodec` support, a `CURRENT_VERSION` bump (`BookDocument.kt:150`) and a
`RestoreOps` merge rule — the Room migration is only half the job. Room migrations are hand-written
and registered in **both** `AppDatabase.kt` and `di/AppModule.kt`; a wrong one crashes on launch and
does *not* fall back destructively. **Migration numbers are fixed here: Phase 1 = 22→23,
Phase 4 = 23→24.**

Two conventions the reader's new UI must follow: Material components build their own `ripple()` and
ignore `LocalIndication`, so every new control uses the `Haptic*` wrappers from
`ui/haptics/HapticControls.kt`; and new `SettingsStore` values follow the existing recipe (`Flow` +
`@Volatile current*` snapshot + collector in `init` + setter).

On theming: CLAUDE.md documents both halves of the split rule and the reader is squarely in the
second — *"when two screens are copies of each other, share the frame instead of the router"*
(`CLAUDE.md:94`, the `InfoPageScaffold` worked example). The rendered page is theme-agnostic; only
chrome fills and tints differ, which `appSheetColor()`/`appDialogColor()` already resolve inline
against `LocalAppTheme`. **One `ReaderChromeScaffold` owning behaviour, routing to a thin per-theme
frame for the look.**

### Phase 0 — Unblock, and prove the approach works

1. **Gate the frozen sync surface.** Wrap `EbookReaderScreen.kt:184`, `:190`, `:204`, `:255-283`
   and the sync dialog at **`:287-313`** (the whole `if (showSyncDialog)` block — gating only its
   middle leaves a titled `AlertDialog` with no body or buttons) in `FeatureFlags.EBOOK_SYNC_UI`,
   and make `EbookReaderViewModel.kt:121-132` skip the aligner and model subscriptions when it is
   off. Correct the flag's KDoc, which claims coverage it does not have. **Precondition for
   committing `EBOOKS_UI = true` at all.**
2. **Make zip access thread-safe.** `EpubParser.kt:31` holds one unsynchronised `ZipFile`;
   `readEntry:99`; `close:134`, called from `EbookReaderViewModel.onCleared:535` with no
   coordination against in-flight reads. Background layout plus lazy image decode will race it. Add
   a read lock (or per-request `ZipFile`) and a close guard.
3. **🔬 The spike — this is the real Phase 0.** One throwaway screen: take a real EPUB chapter,
   parse → layout → paginate → per-block `BasicText` in a custom `Layout`. Novel-grade markup, one
   hard-coded style. Measure on the dev device and **write the numbers down**:
   - **time to first page** on open (the number that actually blocks the user), not whole-chapter
     layout time
   - frame time on page turn
   - peak memory on a pathological spine item (a 500KB single-file chapter)
   - **whether `TextMeasurer` can be driven safely off the main thread** (C.8) — a gate question
   - **the cost of the double measure** (C.4): `Paginator`'s `TextMeasurer` pass plus `BasicText`'s
     own layout, per block per page. The other gate question.
   - `TextAlign.Justify` and `Hyphens.Auto` behaviour at API 26 — **and hyphenation's layout cost
     on/off**, since Android's hyphenator is measurably expensive at normal frequency on older API
     levels and #33 now promises it as shipped
   - **that `Selection.AnchorInfo.selectableId` maps predictably to our blocks** (C.4)
   - how the text *looks* beside the current WebView, side by side
   **Decision gate**: if time-to-first-page exceeds ~150ms, page turns drop frames, or the text
   looks visibly worse than the WebView, stop and re-open the renderer choice before Phase 1. This
   off-ramp is meant to be used, not waved through.

**Exit test**: with `EBOOK_SYNC_UI = false`, no sync affordance is reachable and no Vosk state is
queried on open. The spike renders a real chapter with the measurements recorded.

### Phase 1 — The document pipeline and the anchor model (novel-grade)

Position storage is folded in here rather than deferred: Phase 1 deletes `ReaderWebView`, and with
it `onScrollChanged:133-138` → `onScrollFraction:304` → `persist:329`, the entire position-saving
path. Splitting the replacement into a later phase would leave Phase 1 unable to save a position at
all, which its own exit test requires.

4. **`EpubDocumentParser`** — XHTML → box tree, tolerant of malformed markup (same reason
   `ParagraphExtractor` is a hand-rolled scanner). Emits **both char streams and the offset map**
   (C.3). Retains source element order, tag, nth-of-type index, and the char offset of every element
   `id` (needed for fragment anchors below, and it keeps the KOSync door open at no extra cost).
   Blocks: paragraph, h1–h6, blockquote, ol/ul, li, pre, block image, div, page-break. Inline: bold,
   italic, underline, strike, small, sub/sup, code, link, inline image, br, span.
5. **`StyleResolver`** — our stylesheet plus a **minimal CSS subset** from the book: enough to see
   `display`, `float`, `clear`, `text-align`, `margin`, `text-indent`, `font-style`, `font-weight`.
   Everything else ignored deliberately. Plus presentational HTML (`align=`, `<b>`, `<i>`,
   `<center>`).
6. **`BlockLayout` + `Paginator`** — block stacking, margins, page breaking with widow/orphan
   avoidance, never splitting a line. **Blocks that must split across a page use the
   clip-and-offset mechanism from C.4, with explicit `semantics` and hit-bounds overrides** so a
   split paragraph is not announced twice. Justification and hyphenation come from `TextStyle`, not
   from us (C.4).
7. **`ReaderPage`** — per-block `BasicText` in a custom `Layout`, with the page cache from C.8.
8. **The projection (C.3)** and its test: projecting a render offset lands in the same paragraph
   `ParagraphExtractor` reports, over a corpus of real books including at least one non-English and
   one heavily-punctuated title.
9. **Fragment anchors and the TOC tree — data only; the views are Phase 3.**
   `EpubParser.stripFragment:287` discards `#id` at both `:197` and `:237`, so today every TOC entry
   inside one spine item collapses to the same target. Phase 1 produces the *data*: `href#id`
   resolved to a render-stream char offset, and a nested `TocNode` tree. `SpineItem.title` must keep
   its current values exactly — `ChapterMatcher.autoMatch(List<AudioChapterSpan>, List<SpineItem>)`
   (`sync/ChapterMatcher.kt:18`) consumes it — so the tree is purely additive. Unit-test with nav-doc
   and NCX fixtures. The consumers (TOC view, footnote popup, internal links) are Phase 3 items 21
   and 25.
11. **Room 22 → 23**: `textCharOffset: Int?` on `PlaybackProgress` (render-stream coordinates).
    **And the disk half**: `BookDocument.ProgressEntry.textCharOffset` beside the four existing text
    fields (`BookDocument.kt:104-107`), `BookDataCodec` encode/decode, `CURRENT_VERSION` bump
    (`:150`), `RestoreOps` merge rule, round-trip test in `BookDataCodecTest.kt`.
12. **Migrate existing positions** — on first open of a book with `textSpineIndex`/`textFraction`
    but no `textCharOffset`, convert once via `SpineParagraphs.charOffsetForFraction`
    (`ParagraphExtractor.kt:44`) and project into render coordinates. Keep the legacy columns:
    `textOverallFraction` still drives the home grid bar.
13. **Recompute `textOverallFraction`** from the char offset — its current writer, `persist()`
    (`:329-332`), takes a scroll fraction that no longer exists. Assert the home grid bar still moves.
14. **`skip_events` text fractions.** `recordTextSkip` (`:407-419`) writes
    `fromSpineIndex`/`fromFraction`/`toSpineIndex`/`toFraction` on every chapter and TOC jump; the
    columns exist in Room (`SkipEvent.kt:45-48`), on disk (`BookDocument.kt:142-145`,
    `BookDataCodec.kt:134`/`:254`, `BookDataStore.kt:185`) and in backup
    (`BackupManager.kt:314-316`). Either derive the fraction from the char offset on write, or add
    char-offset columns with the full disk half. Decide and do it here — this is unbudgeted work the
    previous draft missed entirely.
15. **The named sync exception.** `listenFromHere()` (`:365`) reads `liveScrollFraction` (`:105`),
    written only by the deleted callback; `load()` (`:184-191`) derives the opening position from
    audio via `audioPositionToLocator()` (`:423`) on every open of every audio-linked book. Both now
    go through the C.3 projection into extractor coordinates — the units `PositionBridge.charToAudio`
    (`:457`) and `SyncAnchor.charOffset` already use. **Nothing inside `PositionBridge`,
    `SyncAligner` or `ChapterMatcher` changes**, and accuracy improves (a real offset instead of
    `charOffsetForFraction`'s documented approximation). This is new code on the frozen seam and is
    named, not smuggled.

**Tests, not prose**: every stage is pure. Golden-file tests on box trees and page breaks; a
property test that pagination never drops or duplicates a character.

**Exit test**: read three structurally different real books cover to cover, comparing against the
WebView rendering, with positions saving and restoring correctly. A checked-in golden-image corpus,
not a one-time eyeball. **Plus a legacy-migration test** — a book carrying only
`textSpineIndex`/`textFraction` opens where it left off. That path composes
`charOffsetForFraction`'s documented approximation with the projection's inverse and is the riskiest
position path in the plan; it needs its own assertion, not coverage by implication.

> **Regression to carry or fix**: the current reader has a working font-size control
> (`FontSizeDialog`, `EbookReaderScreen.kt:388-407`; `setFontSize`, `EbookReaderViewModel.kt:292`;
> persisted as `SettingsStore.readerFontSize`, `:263`). Typography is Phase 2, so shipping Milestone
> A as-is removes a feature users have today. Either pull a minimal font-size control into Phase 1
> or list it in the milestone's test checklist as a known temporary regression — otherwise it comes
> back as a bug report.

**🚩 Milestone A — shippable.** Reader replaced, positions stable, "Listen from here" working. Merge
to `beta` behind `EBOOKS_UI`, numbered user test checklist, changelog entry.

### Phase 2 — Settings, typography, themes

16. `ReaderSettings` (~40 fields). **Storage shape: a JSON document column**, following the
    codebase's own precedent — `WidgetDesign.documentJson` + `WidgetDesignCodec`, which versions and
    migrates in place without a Room migration per field. Per-book row plus a global default in
    `SettingsStore`; `isGlobal`, "apply to all", "reset to global" (#160, #161); schema version and
    deprecation path (#88). Codec round-trip test.
17. Typography (#19–33) and margins (#35–38) — these feed `StyleResolver` and `TextStyle` directly.
18. Themes (#41, #42, #53): app theme tokens plus reading presets (sepia, grey, high-contrast).
19. Footer quick panels (#158) — font+layout, colour, navigation — on the shared
    `ReaderChromeScaffold`, using `Haptic*` controls.
20. Header/footer status (#151–156).

**Exit test**: a **sampled-pixel check per theme preset using non-default themes.** "It reflows and
the position holds" would pass even if every theme silently resolved to the app default.

### Phase 3 — Navigation, input, accessibility

21. Nested TOC view with current-chapter tracking (#55, #57).
22. Page turn: tap zones, swipe, push/slide, swap sides, full-screen tap, disable switches
    (#64–69, #11, #12).
23. **Volume-key paging (#70).** Readest's guard is `if (!viewSettings?.volumeKeysToFlip ||
    ttsPlaying) return` (`usePagination.ts:492`), and the comment at `:485-487` explains it:
    interception is acquired only while the setting is on and TTS isn't playing, so the user keeps
    volume control during read-aloud. That is a considered design, not an oversight. Ours has the
    extra constraint that this app owns a media session, so keys must be claimed only while the
    reader is foregrounded and released otherwise. Decide deliberately, expose a setting.
24. Progress bar and sticky bar with chapter ticks (#62, #63), page jump (#61), back/forward
    history (#60).
25. Footnote popup (#72), internal and external links (#73, #74) — both on Phase 1 item 9.
26. Brightness slider, edge-swipe, software overlay (#46–48).
27. Orientation lock (#135), fullscreen (#136), image viewer (#16).
28. **Accessibility (C.7)** — per-block semantics, `GetTextLayoutResult`, `traversalIndex`,
    continuous reading across page turns, `fontScale` honoured. Verify with TalkBack on.

**Exit test**: a full book read end to end using only gestures, on both themes, with audio playing
for part of it (exercises #23). Then the same book navigated entirely with TalkBack.

**🚩 Milestone B — shippable.** A complete reading experience. Merge to `beta`, test checklist,
changelog.

### Phase 4 — Selection and annotations

29. **Selection** via `SelectionContainer` over the block nodes (C.4) — far smaller than the
    hand-built version the earlier draft budgeted, and the offsets come back through public API
    (`Selection.AnchorInfo.getOffset()`/`getSelectableId()`). What remains ours: establishing the
    `selectableId` ↔ block mapping, projecting into render-stream offsets, the popup toolbar's
    placement (#89, #90), and **deciding the cross-page case** — `SelectionContainer` spans one
    composition subtree, so a selection cannot straddle a page break unless the neighbouring page is
    composed off-screen. Pick: compose a neighbour, or accept per-page selection and say so in the
    UI.
30. **Room 23 → 24**:
    - `ReadingBookmark` — `bookId`, `spineIndex`, `charOffset`, `sectionHref`, `label`, `createdAt`
    - `Annotation` — `bookId`, `type` (highlight/note/excerpt), `spineIndex`, `charStart`, `charEnd`,
      `text`, `note`, `color`, `style`, `createdAt`, `updatedAt`
    Plus the disk half for both: `BookDocument` sections beside the audio `bookmarks` (`:49`), codec,
    `CURRENT_VERSION` bump, `RestoreOps`, round-trip tests.
31. **Fix `attachEpubToBook` in this phase.** `EbookScanner.kt:84-98` carries over only progress
    (`mergeStandaloneEbookProgress`, `:94`) then deletes the standalone row (`:95`). Once annotations
    are keyed by `bookId`, connecting an epub to its audiobook — a normal flow from
    `BookOptionsSheet` — silently destroys every highlight made before. Merge annotations and
    bookmarks too.
32. Highlight colours and styles (#91, #92), notes (#93), inline editing (#108), quick action (#102).
33. Bookmarks: toggle, pull-down gesture, corner ribbon (#94, #95).
34. Notes sidebar grouped by chapter, filterable, tap-to-jump with flash, next/previous annotation
    navigation (#104–107, #115).
35. Copy / share / excerpt (#96, #97, #99).
36. Export to Markdown and JSON, import from JSON (#112, #113) — pure, so test the round trip.

**Design note**: selection painting, the reading ruler and search highlighting must go through one
abstraction over "where is character N on screen", because Phase 6 introduces a second layout path
that has no block nodes (C.5).

**Exit test**: highlight a passage, close, change font size, reopen — the highlight is on the same
words. Then connect the epub to an audiobook and confirm it survives.

### Phase 5 — Search

37. In-book search over the **render stream** (#116) — not the extractor stream, which is lowercased
    and diacritic-stripped and therefore cannot support match-case (#119) or match-diacritics (#120).
    Results are render-stream offsets.
38. Options: scope, contains/whole-words, match case, match diacritics (#117–120). Pure — test it.
39. Result list with snippets, tap to jump, next/previous while reading (#122–124).

**Exit test**: search a 600-page EPUB; results land on the right words; no ANR; cancelling
mid-search leaves no orphaned work.

**🚩 Milestone C — shippable.** Annotations and search. Merge, checklist, changelog.

### Phase 6 — Tables and floats (stage 2 of the scope decision)

The deliberately deferred half of the markup scope. Read C.5 before starting: **floats have no
Android API and this phase introduces a second layout path.**

40. **Tables** — `<table>` parsing, column width resolution, cell layout, borders, spanning. Cell
    *content* stays on the composable path, but auto table layout needs each cell's intrinsic
    min/max width, which is another measurement pass per cell — and is a second reason the container
    is a plain `Layout` and not `SubcomposeLayout`, which cannot supply intrinsics at all. Wide
    tables that cannot fit get the scrollable overlay (#17).
41. **Floats, degraded first (C.5 consequence 3).** Ship `float`/`clear` as **full-width blocks that
    ignore the float** — one layout path, no second selection or accessibility implementation, and
    what most mobile readers do at phone widths anyway. Evaluate against the corpus before going
    further.
42. **True float wrapping — only if item 41 proves unacceptable.** Per-word measurement and manual
    placement for **every block whose vertical extent intersects an active float rectangle** (not
    merely "adjacent" — a float's influence runs until `clear` or until its bottom is passed, which
    on a tall figure is most of a page). Budget this as a **second implementation of selection and
    accessibility**, not a rework line: manually placed text does not participate in
    `SelectionRegistrar` and publishes no `Text` semantics, so the Phase 4 "where is character N"
    abstraction — a geometry query — covers the ruler, search and highlight painting but neither of
    those two. Re-read C.5 consequence 2 before starting.
43. Margin collapsing, which floats interact with and which crengine's PR #299 lists as a source of
    its remaining open cases. Document the edge cases we accept rather than pretending there are
    none.

**Exit test**: a corpus of non-fiction EPUBs with real tables and floated figures, side by side
against the WebView. Write down what still looks wrong.

### Phase 7 — Reading aids

44. Auto-scroll with speed control and overlay, resume-on-reopen (#126–128).
45. Auto page-turn (#129).
46. Reading ruler (#130).
47. Hint toasts and first-run tips (#159).

### Phase 8 — Read-aloud — spike first

48. **Spike before implementing.** The playback stack cannot accept TTS as a source as it stands.
    `PlayerController` (1444 lines) is built around `MediaItem` with `mediaId = AudioFile.id` —
    `stop()` reads it (`:631`), `bookSeekTo` matches on it (`:770-780`), `saveCurrentProgress`
    early-returns without it (`:847-850`, `:865-868`). `PlaybackService` owns the single `ExoPlayer`
    plus a `LoudnessEnhancer`/`Equalizer` bound to its audio session, and per CLAUDE.md the widget
    re-renders **only** from `PlaybackService.pushWidgetState()`. `PlaybackProgress.currentFileId` is
    an FK to `audio_files`. `TextToSpeech` is not a Media3 `Player`. Two routes:
    - **(a) `synthesizeToFile` per chunk fed to ExoPlayer** — one player, one session, widget intact;
      but synthesis latency, temp-file lifecycle, and utterance callbacks decoupled from playback
      position, which item 50 needs.
    - **(b) a second `MediaSession` behind a custom `SimpleBasePlayer`** — clean for TTS, but
      contends for the lock screen and Bluetooth and leaves the **widget stale**.
    Either way `SleepTimerEngine`, `HeadsetGestureMapper`, `BtAutoResumeWatcher` and the
    `STATE_ENDED` → mark-finished/series-advance path key off the ExoPlayer and do **not** work
    unchanged. Time-box it, pick a route, re-scope.
49. TTS: play/pause, rate, voice picker, sentence/paragraph gaps (#138–140, #147).
50. Follow-along highlight, switchable granularity (#141, #142).
51. Media-session integration per the spike (#143–146). **#143 note**: `MainActivity.kt:627` sets
    `hideMiniBar = currentRoute != "home"`, deliberately hiding the mini bar on the reader route.
    Reusing it in the reader means changing that rule.
52. EPUB 3 Media Overlays (#148). Overlay audio lives inside the zip, so there are no `AudioFile`
    rows and `currentFileId` has nothing to point at. Add `hasNarration` to `Book`, detected at scan
    time like `ebookSpineCount`.

**Exit test**: start TTS, lock the phone, control it from the lock screen **and the home-screen
widget**, let the sleep timer end it. The widget catches a route-(b) shortcut.

**Out of scope**, per `reader-revival.md`: the listen↔read position bridge, forced alignment,
`SyncAligner`, `PositionBridge`, the AI model card. `EBOOK_SYNC_UI` stays `false`.

**🚩 Milestone D — shippable.** Read-aloud. Merge, checklist, changelog.

### Phase 9 — Deferred list

53. **KOSync (#171, C.6)** — xpointer converter, drift heuristic, document hash, HTTP protocol,
    settings page. A real project, not a bolt-on.
54. Custom themes (#43), textures (#45), double border (#39), contrast (#54).
55. Reading statistics (#85), library-wide search (#125).
56. Paragraph mode (#131), RSVP (#132), proofread rules (#137), dictionary (#166).
57. Custom fonts (#25), horizontal scroll (#3), writing modes (#9), CJK font (#24), RTL (#10).
58. Global annotations (#100), range editing (#109), cross-doc selection (#110), loupe (#111),
    toolbar customisation (#103), deep links (#98), proximity search (#121).

### Release procedure (applies at every 🚩 milestone)

59. **Check the installed build's signature first.** CLAUDE.md warns the release keystore's
    signature differs from every previously-shipped debug APK, so `adb install -r` of an
    `assembleRelease` build onto a phone carrying a debug build fails with
    `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Never uninstall to force it — ask first.
60. Build `:app:assembleRelease`; archive `mapping.txt` as `mapping-<versionCode>.txt` before the
    next build overwrites it.
61. Bump `versionCode`; `versionName` **must** end in `b` for a beta (currently `1.12.1b`, code 72 —
    CLAUDE.md's "stable 1.7 / beta 1.7.1b" line is stale and should be fixed separately).
62. Numbered test checklist covering every feature in that milestone, tested by the user, before the
    changelog is finalised.
63. Changelog in `res/raw/changelog_beta.txt` — short summary first, detail after.
64. Fresh tag on HEAD (`UpdateChecker` picks the highest semver, and GitHub derives `created_at` from
    the tag's commit date). Merge target is **`beta`**, not `main`.

---

## Part E — Risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| **Two char streams conflated** | The extractor stream is lowercased, punctuation-free and ASCII-only; treating it as the render stream silently corrupts every position | C.3's explicit projection; the Phase 1 item 8 corpus test asserts projection, not equality |
| **Non-English books** | Their extractor stream is nearly empty and most paragraphs are dropped by the `< 2` rule | Reader positions live in the render stream; only sync accuracy degrades, as it already does |
| **Floats have no Android API** | `StaticLayout` measures one fixed-width rectangle; no exclusion rects, no ragged-width breaker | C.5: ship degraded (full-width, float ignored) first; true wrapping only if the corpus demands it |
| **True float wrapping needs a second selection + accessibility implementation** | Manually placed text joins no `SelectionRegistrar` and publishes no `Text` semantics; the "where is character N" abstraction is geometry only and covers neither | Phase 6 item 42, budgeted as a second implementation — or avoided entirely by item 41 |
| **Splitting a block across a page** | `BasicText` has no `startLine`; happens constantly in a novel | C.4's clip-and-offset, with explicit semantics and hit-bounds overrides (Phase 1 item 6) |
| **Text measured twice per block per page** | `Paginator`'s `TextMeasurer` pass plus `BasicText`'s own layout; caches are not shared | Phase 0 gate measures it; fallback is `drawText` for text blocks, at the cost of their selection and semantics |
| **The projection re-deriving `ParagraphExtractor`'s normalisation** | Must be byte-identical to what wrote the `SyncAnchor` rows on disk; a structurally correct parser differs by construction | C.3: call `ParagraphExtractor.extract()` unchanged and align, never re-implement. Runtime fallback to `charOffsetForFraction` when alignment fails |
| **Intra-paragraph offset drift** | `PositionBridge.charToAudio` interpolates within a spine item, so "right paragraph" is not enough | Phase 1 item 8 bounds intra-paragraph error, not just paragraph identity |
| The frozen sync surface ships with `EBOOKS_UI = true` | The flag meant to prevent it gates nothing in the reader | Phase 0 item 1, including the full `:287-313` dialog |
| Layout performance / `TextMeasurer` off-thread | Not documented as thread-safe; async font resolution | Phase 0 gate measures it explicitly, before Phase 1 |
| Rendering fidelity on complex books | We are writing a layout engine | Staged scope; golden-image corpus as the exit test |
| Accessibility of a custom-laid-out page | TalkBack needs granularity, traversal order, fontScale | Phase 3 item 28, verified with TalkBack on |
| Two migrations claiming one number | A wrong migration crashes on launch; no destructive fallback | Fixed in Part D: Phase 1 = 22→23, Phase 4 = 23→24 |
| Room-only storage changes | Disk is the source of truth; a restore overwrites new data with legacy | Every storage item does the `BookDocument` + codec + version + `RestoreOps` half |
| **`skip_events` text fractions** | Written on every chapter jump, across Room, disk and backup; the fraction ceases to exist | Phase 1 item 14 |
| **Fragment anchors** | Without `#id` offsets the nested TOC collapses, and footnotes and internal links can't work | Phase 1 item 9 |
| Annotations lost on "Connect EPUB" | Silent data loss on a normal user flow | Phase 4 item 31, with the entities |
| Volume-key paging vs the media session | Breaks volume control app-wide, or dies during read-along | Phase 3 item 23 |
| Phase 8 read as a cheap win | It is a re-architecture of the playback source | Spike first |
| Per-theme split by reflex | ~15 panels × 2 files is the drift failure `InfoPageScaffold` prevents | Shared `ReaderChromeScaffold` |
| Zip access under concurrent layout and image decode | `ZipFile` is not safe for concurrent use; close races in-flight reads | Phase 0 item 2 |
| **No incremental release** | Phase 1 alone is weeks before anything looks like a reader | Four 🚩 milestones, each merged to `beta` with its own checklist |
| KOSync DocFragment drift (if built) | Documented drift of 52 sections, not off-by-one | C.6; validate against real KOReader output before shipping #171 |
| Exit tests passing on a collapsed implementation | Every theme resolving to the default still "reflows correctly" | Pixel checks per preset; same-paragraph assertions; unit tests on every pure stage |

---

## Summary

Readest's reader is **172 distinct functions**. Removing everything that needs its server, everything
desktop-shaped, and everything outside EPUB leaves **122 to build**. Seven more are *deleted* rather
than deferred — "override book font", "override book colour", "allow scripts", user stylesheets —
because they exist only to negotiate with a browser's CSS cascade, and we won't have one.

**The renderer is native Compose.** A JavaScript-off WebView can be told where to go but never asked
where it is, and that missing question is what makes stable highlights, precise bookmarks, accurate
progress and TTS follow-along impossible. Rendering natively removes it, gives us a pipeline that is
pure and testable end to end, and avoids adding a Node bundler to a Gradle-only repo. Two arguments
I previously made for it are withdrawn as overstated: foliate's iframes *could* have been sandboxed
with `allow-same-origin` and no `allow-scripts`, and foliate-js is MIT, so there was never a licence
to clear. The decision stands on the first three grounds, not those.

Three things in the previous draft were wrong and are now fixed.

**First, and most seriously: the central invariant was impossible.** That draft said every position
would be a character offset into the source text, "exactly what `ParagraphExtractor` already
produces." It isn't. `TextSimilarity.normalize` lowercases, then strips everything outside
`[a-z0-9\s]` — so the extractor's stream has no punctuation, no capitals and **no non-ASCII
characters at all**, and `ParagraphExtractor.kt:84` silently drops any block under two characters.
A renderer that must draw `é` and `"` cannot match those offsets. Worse, anchoring the reader to
that stream would have made positions degenerate for French, German, Russian or Chinese books, whose
normalised text is nearly empty. The fix is two coordinate systems: the reader owns a **render
stream** (real text, every language) and the frozen sync path keeps the **extractor stream**, with a
monotone projection between them. Critically, that projection is built by **calling
`ParagraphExtractor` unchanged and aligning its output against the render stream** — not by
re-deriving its normalisation inside the new parser. The extractor stream has to stay byte-identical
to what wrote the `SyncAnchor` rows already on disk, and a structurally correct box-tree parser
differs from it by construction (it needs `table` and `figure` as blocks, must decode the full
entity set, must honour the prolog encoding). The Phase 1 test asserts the projection lands in the
right paragraph *and bounds the error within it* — `PositionBridge` interpolates inside a spine
item, so paragraph identity alone is not enough.

**Second, "Canvas is forced" was a false binary.** There is a third option: own the pagination and
block layout, then place per-block `BasicText` nodes in a custom `Layout`. Pages *and*
`SelectionContainer`, per-block semantics, `TextAlign.Justify` and `Hyphens.Auto` — all of which the
Canvas route would have made us hand-build. Justification in particular was budgeted as bespoke
arithmetic when it is a `TextStyle` flag backed by `StaticLayout`'s inter-word justification, which
has shipped since API 26 — this app's exact `minSdk`. Phases 1–5 now run on the composable path.

Accessibility genuinely does shrink from weeks to days there. Selection shrinks too, but a
third-pass audit claimed the offsets weren't reachable through public API, so I checked the resolved
`foundation` artifact for the Compose BOM this project pins: `SelectionContainer(Modifier, Selection,
(Selection) -> Unit, content)` is public, and `Selection.AnchorInfo` publicly exposes `getOffset()`
and `getSelectableId()`. The offsets are reachable. Two real caveats survive and are now in the plan
— the `selectableId` ↔ block mapping has to be established rather than assumed, and a selection
cannot straddle a page break unless the neighbouring page is composed.

The composable path also carries two costs the draft that introduced it did not see, both now
written in: **a block that spans a page boundary** has no free mechanism (`BasicText` has no
`startLine`), so split blocks render clipped at a negative offset with explicit semantics and
hit-bounds overrides; and **text is measured twice** — once by `Paginator` off the main thread, again
by `BasicText` during composition, with no shared cache. Both are Phase 0 gate questions now.

**Third, floats are the real obstacle and they were unexamined.** `StaticLayout` measures a
paragraph into one fixed-width rectangle; Android has no exclusion-rectangle API and no ragged-width
line breaker. Real float wrapping means per-word measurement and manual placement, which costs
shaping quality and bidi correctness, and produces text with no block nodes — so it partially
invalidates the selection, semantics, ruler and search work built in Phases 3–5 — and the "where is
character N" abstraction Phase 4 designs is a *geometry* query, so it rescues the ruler, search and
highlight painting but neither selection nor accessibility. True float wrapping is therefore a second
implementation of both.

Which is why Phase 6 now **ships floats degraded first**: render float-affected blocks full-width and
ignore the float — one layout path, no second implementation, and what most mobile readers do at
phone widths anyway. True wrapping becomes an explicit opt-in only if the corpus proves the degraded
version unacceptable. Decision #3 already licensed exactly this ("unsupported markup degrades in
place"); the earlier draft simply hadn't applied it to the one case where it pays most.

Ten phases with **four shippable milestones** rather than one release at the end: the pipeline and
anchors (A), settings and themes, navigation and accessibility (B), annotations and search (C),
tables and floats, reading aids, read-aloud (D), then deferred work. Phase 0 is a spike with a real
decision gate — time to first page, frame time on page turn, whether `TextMeasurer` is safe off the
main thread — and it is meant to be usable as an off-ramp.

**On KOReader sync**: yes, later, but an earlier draft called it nearly free and that was wrong.
crengine's xpointer offsets are per-text-node, its path steps are nth-of-type, and its
`DocFragment` numbering drifts from spine order — Readest documents a real case of `DocFragment[326]`
resolving to section 274, and its converter is 806 lines with a size-based drift heuristic. It is a
Phase 9 project. The only thing needed now is that `EpubDocumentParser` retain element order,
tag, nth-of-type index and `id` offsets — which fragment anchors require anyway, so KOSync rides
along free.

Listen↔read sync stays frozen, with one exception named openly: `listenFromHere()` and the
audio-derived opening position move from scroll fractions to projected char offsets. Nothing inside
`PositionBridge`, `SyncAligner` or `ChapterMatcher` changes, and the result is more accurate than
`charOffsetForFraction`'s documented approximation — but it is new code on the frozen seam and
should be an explicit decision, not something found in a diff.

Merge target is `beta`.
