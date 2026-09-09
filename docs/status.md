# Status — what exists right now

**Read this first when picking up work after a context reset.** It answers "has this already
been built?" in one page, so that question never costs a repo-wide search or a guess.

Keep it *short*. This is a ledger of state, not a changelog and not a design doc:

| Question | Where the answer lives |
|---|---|
| What is built, and what is deliberately not? | **this file** |
| How does it work / why is it shaped that way? | `CLAUDE.md`, and the `docs/*.md` it points at |
| What changed in which release? | `app/src/main/res/raw/changelog_beta.txt` |
| Who changed what, when, and why? | `git log` |

Update it in the same commit as the work it describes — a ledger that lags is worse than none,
because it gets trusted. One line per item; if a line needs a paragraph, it belongs in `CLAUDE.md`
and this line should link to it.

Last updated: 2026-09-09 · beta `1.14.0b` (75) · DB v28

---

## Built and shipped

Audio player (multi-file books as one timeline, resume, lock screen, sleep timer, damaged-MP3
recovery) · Series and Authors as first-class groupings · audio presets (speed/boost/EQ bundles)
with cascade resolution · library scanner with three import structures · disk-mirror storage
(reinstall-proof; disk is source of truth) · two themes (Material You / Immersive) with the
per-theme UI split · 26 launcher icon variants · home-screen widget maker v2 · Gemini synopses ·
online cover search · haptics vocabulary · in-app updates from GitHub releases · auto-backup.

## Built, in beta, not yet released

- **Companion packs** — pack registry, reveal cursor, companion deck UI, pack import/export.
  Merged 2026-09-03. Working but unreviewed WIP; more changes expected.
- **Seek bar is a choice in both themes** — the four painted scrubber designs moved out of
  Immersive into `ui/components/ScrubberArt.kt`, joined by `CLASSIC` (the M3 slider Material You
  always drew). One `VoyageScrubber` owns the gesture for all five. Each theme stores its own pick
  (`scrubber_style` / `scrubber_style_material`), so both keep their shipped default.
  All five verified rendering and selectable on an emulator.
- **Visual colour picker** — `ui/components/ColorPickerPanel.kt` (saturation/value field + hue
  rail). Used by Material You's "Custom color" dialog, which was presets-and-hex only, and by the
  Immersive accent card's new "mix your own" tile. Verified on an emulator.
- **One accent for the whole library** — the Cover accent card gained a "Use on every book"
  checkbox backed by the `global_accent` preference; set, it outranks every per-cover pin and both
  automatic pickers, and unticking falls back to the per-cover map (never cleared).
  Not yet tested on device.
- **Library top edge blurs and fades** — the grid runs under the status bar and its top band is
  drawn through `Modifier.topEdgeFade`, so covers dissolve instead of being clipped by a straight
  line. Blur is API 31+; the alpha ramp carries it below that. Verified on an emulator.
- **Map pans and zooms** — `MapPane` pinches, drags and double-taps. The artwork is transformed by
  a `graphicsLayer`; pins are transformed arithmetically (`mapToScreen`/`screenToMap`) so markers
  keep a constant size and tap target at every zoom. Drag, double-tap in/out and the pan clamp
  verified on an emulator; pinch is untested (adb cannot drive multi-touch).
- **Reading settings had no settings on it** — the screen opened and showed only its live preview;
  the scope row, the six tabs and every control were pushed off the bottom. `ReaderPageView`
  inferred "fill the height" from `constraints.hasBoundedHeight`, and a plain `Column` hands its
  non-weighted children a bounded height, so the preview claimed the whole screen. It is now an
  explicit `fillHeight` parameter (true for the reader, false for the preview, which is also
  capped at 260dp and clipped). **This was the actual reported bug.** Verified on an emulator.
- **Reader chrome lockouts fixed** — hardening found while chasing the above, *not* its cause: the
  top bar is the only route to Contents, Reading settings and out of the book, and two settings
  could each remove it permanently. `showHeader = false` hid the bar (now it falls back to a
  floating back/contents/Aa cluster), and `fullscreenTapTurns` made every tap turn a page so the
  chrome toggle was unreachable (a long press on the page now always toggles chrome). Both rescue
  an already-stuck install.
- **Reading-settings preview is lorem ipsum** — the preview shows typographic shape, and readable
  prose invites reading it instead of looking at it.
- **Reader gestures turned chapters instead of pages** — `readerGestures` captured `onTurn`
  directly in `pointerInput` blocks keyed only on settings, so it froze the pages list at its
  first-composition value (empty, before pagination). Every forward turn read `target >
  lastIndex(-1)` → next chapter and every backward one `target < 0` → previous chapter, so no tap
  or swipe ever turned a single page. Now read through `rememberUpdatedState`. Verified on an
  emulator: swipes step 1 → 2 → 3.
- **Reader page text was clipped by both bars** — `chromeTop` was `statusBars + 64dp + 20dp`, but
  the reader is fullscreen (so `statusBars` is 0) while `TopAppBar` sizes itself including the
  **display cutout**. On a punch-hole phone ~29dp of every page sat behind the bar, top and bottom,
  and the paginator never moved those lines to the next page — they were simply unseen. Both bars
  now report their own height via `onSizeChanged` and the reservation follows. Verified on an
  emulator (page count shifted as the usable height changed; first and last lines now clear).
- **Widget controls did nothing while the app was closed** — `WidgetIntents` built its control
  PendingIntents with `PendingIntent.getService`, and a plain `startService` aimed at a process
  that is not running is refused outright on API 26+. Nothing reached the app, so there was nothing
  in logcat either. Now `getForegroundService`, with `PlaybackService.promoteForColdWidgetTap`
  posting a placeholder under Media3's own notification id so the mandatory `startForeground`
  always lands and Media3's real notification replaces it. Confirmed on the phone with the app
  force-stopped: `am start-service` → "app is in background uid null";
  `am start-foreground-service` → cold process, book resumed.
- **The left-edge brightness swipe did nothing** — the gesture reported a per-frame delta and the
  reader added it to `prefs.brightness`, which is DataStore-backed and arrives asynchronously: every
  frame read the same pre-drag value, so a whole swipe collapsed into one frame's worth of change.
  Compounding it, brightness shared a `DisposableEffect` key with fullscreen/orientation/keep-awake,
  so each frame also tore down and rebuilt the window state. The gesture now accumulates locally and
  emits an absolute level, and brightness has an effect of its own.
- **Continuous scroll is now genuinely continuous** — scrolled mode used to draw one chapter and
  stop dead at its end, with the footer's chapter arrows the only way on. It now renders a *window*
  of three chapters (previous, current, next), parsed ahead of time by the ViewModel and stacked in
  one `LazyColumn`, so reading past the end of a chapter is reading past the end of a paragraph:
  the next one is already below the last line. Loading on arrival would have fixed the dead end but
  not the seam — the reader would still hit a wall, wait, and land at the top of something new.
  The window rotates as soon as the reader's position crosses into a neighbour, `renderDocCache`
  keeps five parsed chapters so turning round re-parses nothing, and list keys are
  `spineIndex:renderStart` — both because `renderStart` is only unique within a chapter and
  because that is what holds the scroll still while the window rotates underneath. Position
  inverts here: the list owns it and reports `(spineIndex, renderStart)` up, and
  `EbookReaderViewModel.onScrolledTo` decides what it means, including committing a chapter
  crossing without recording a skip (nothing was skipped — it was read).
- **The companion map was cropped** — drawn at `ContentScale.Crop`, so whatever fell outside the
  pane's aspect ratio was cut off the map and unreachable at any zoom. Now `ContentScale.Fit`, with
  pins anchored to the *fitted* rect (`mapToScreen`/`screenToMap` take the letterbox origin) so a
  marker stays on the place it names.
- **Bookmarks and highlights in the EPUB reader** — new `reader_marks` table (DB 27), a ribbon
  toggle and a highlight mode in the reader's top bar, paragraph tints painted by
  `ReaderPageView`/`ReaderScrollView`, and a Marks tab in Contents that jumps to and deletes them.
  Marks anchor to a block's `renderStart`, not a page number, so they survive every reflow; they
  store an extractor fraction as well, which is what lets a mark in an unloaded chapter be
  followed.
- **Sync landed at the top of the chapter for 11% of a book** — `PositionBridge.audioToCharAnchored`
  walks a path made of "rest of the previous anchor's spine item + any spines fully spanned + the
  next anchor's leading chars", then returns how far along that path the position sits. For the
  legs that start at a spine boundary, distance-travelled *is* the char offset; for the first leg
  it is not — that one starts at `prev.charOffset`, and the code returned the bare distance for
  both. So a position in the tail of a chapter resolved to the same distance from that chapter's
  *beginning*. `charToAudioAnchored` had always measured this leg correctly, so the pair were not
  inverses. Measured against Shadow Slave's real `mapping.json` (13,628 anchors, ~1/min, 256 h of
  audio): the bracketing anchors straddle a chapter boundary for 16% of the timeline and hit the
  broken leg for **11%**, displacing the answer by a median of 5,578 characters — most of a chapter.
  The existing round-trip test missed it because it landed in a fully-spanned middle spine, which
  is the branch after this one. `ShadowSlaveMappingDiagnosticTest` (skips itself unless the files
  are present) is what pinned the data down as sound first: 1,811 spine items checked, not one
  anchor offset past `ParagraphExtractor`'s own char count for that item, so the aligner and the
  app agree on the coordinate system and the fault was purely in the conversion.
- **"Connect EPUB…" did nothing outside Home** — `BookOptionsSheet` is one shared sheet opened from
  five places, and its ebook callbacks (`onConnectEpub`/`onDisconnectEpub`/`onOpenReader`) default
  to empty lambdas. Only Home's long-press path passed them, so in the player overflow and in Book
  info the button opened the file picker, took your selection, and dropped it: no connection, no
  log, no error. The connect/disconnect logic moved into `EbookScanner.connect`/`disconnect` (one
  entry point, cache invalidation and logging included) and every book call site now wires it, with
  the DRM/corrupt-EPUB dialog surfaced in the player and Book info as well as Home. `onOpenReader`
  through the player chain grew a `fromSync` flag so Book options' plain "Open reader" doesn't
  trigger the "Read from here" landing flash — only an actual sync jump does.
- **"Listen from here" / "Read from here" now show you where they landed** — both used to move the
  book silently, leaving you to work out which paragraph the jump had chosen. The reader now flashes
  that paragraph: a faint halo in the app's accent colour, up in 200 ms and gone over the next
  750 ms (`ParagraphGlow` + `ParagraphFlash`, drawn by `BlockText` as inflated rounded rects behind
  the text rather than a `background()`, so it can spill past the paragraph's box). The target is
  addressed like a mark — spine index + `renderStart` — so it means the same thing in paged and
  continuous mode. Paged mode is already on the right page (`preparePages` picks it); continuous
  mode is placed by the scroll anchor, with a visibility check that only scrolls if the paragraph
  genuinely isn't on screen. "Read from here" carries the intent through the nav route
  (`reader/{bookId}?flash=true`, set only by the player sheet) since the jump is already persisted
  by the time the reader exists; opening the reader any other way gets no flash. Also fixed on the
  way: `liveRenderOffset` stayed 0 from load until the first page turn, so "Listen from here"
  pressed right after opening a part-read book reported the top of the chapter rather than the page
  on screen.
- **The reader render spike is gone** — `ui/reader/spike/`, its nav route and the 🔬 button in the
  reader's top bar. It was a Phase 0 measurement harness, marked throwaway in three places since
  it landed.

- **A finished book never stayed finished** — one defect behind three separate reports: a book
  resumed at its last file rather than its beginning, never moved itself to the Finished shelf, and
  reopened an old chapter. `PlayerController`'s `STATE_ENDED` handler marks the book complete, then
  the stop/flush position save that follows a moment later ran
  `PlaybackProgressDao.updatePosition`, whose `isCompleted = 0` (plus the repository's
  `status = IN_PROGRESS` beside it) exists to un-finish a book on resume — and undid the completion
  it was racing. `AudiobookRepository.updatePosition` now keeps both when the write lands within
  `COMPLETION_TAIL_MS` of the book's end, via a second DAO query that leaves the flag alone.
  Position, not ordering: the two writers are unordered, so neither can evaluate a rule about which
  came first. A genuine resume is unaffected — `resolveStart` sends a finished book to file 0 /
  position 0. Verified on device: playing a book to its end leaves status=FINISHED and
  isCompleted=true with the position at the end, and both survive the stop-flush save.
- **The companion deck let taps through to the player** — its root `Box` covered the player without
  intercepting anything, so a tap on the dock band around the transport buttons worked the player's
  bookmark row underneath. The container now consumes whole gestures its own children did not want.
  Verified on device.
- **Return/Confirm pills armed twice** — tap a chapter, then scrub inside the chapter you landed in,
  and `PlayerViewModel.pushPosition` stacked two anchors: confirming one revealed another, and the
  second Return carried on back across the chapter boundary, so a scrub within a chapter looked
  like it had changed chapters. Pushes inside `PUSH_COALESCE_MS` now keep the older anchor — the
  position the listener was actually at before they started navigating. Verified on device: five
  chapter jumps in a row now leave one anchor and no dropdown caret.
- **Library top edge no longer blurs** — `Modifier.topEdgeFade` kept its alpha ramp and lost the
  four-band progressive blur, by request: covers now dissolve into the wallpaper instead of
  smearing into it. Takes four layer replays per frame off a scrolling grid and removes the API 31
  split the blur half had. Verified on device.

- **The jump-restore Dismiss button rendered vertically** — one letter per line. Immersive's
  restore row was a plain `Row`, and "Playback jumped — tap to go back" beside "Dismiss" is wider
  than the screen, so the second pill was squeezed to its minimum and its label wrapped per
  character. Now a `FlowRow`, matching the Material theme's `JumpRestorePill`, which had already
  chosen one for exactly this reason; `ScrimPill`'s label is also `maxLines = 1` + ellipsis, so no
  future squeeze can stack letters again. Pre-existing, but newly reachable: the restore offer is
  armed by `AudioCascade.resolveStart`'s isCompleted branch, which nothing reached while completion
  was being wiped on every save. Verified on device.

- **The EPUB reader is gone.** Removed wholesale on 2026-09-09, to be rebuilt from scratch: the
  reader UI and its Compose renderer, the EPUB parser, `EbookScanner` and the `Book.ebookPath`
  link, the listen↔read sync stack (`sync/`, `data/sync/`, `data/transcribe/`, Vosk and its JNA
  dependency), `ReaderPrefs` and all 45 reading settings, the Ebooks nav slot, "Read from here",
  Book options → Ebook, and Settings → Ebook folder. ~6,800 lines across five packages, plus the
  call sites in ~30 more. DB **v28** drops `reader_marks`, `sync_anchors`, three columns from
  `books` and five from `playback_progress`; both tables are rebuilt rather than altered because
  `DROP COLUMN` needs a newer SQLite than minSdk 26 ships. Not yet verified on device.
  - **What survives on purpose:** `data/book.json`'s `ebook` object and the five text-position
    keys are omitted from `BookDataCodec`'s known-key sets, so `captureUnknown` round-trips them
    verbatim — an install from before the removal keeps its recorded epub connection and reading
    position on disk, untouched, for the rebuild. `BookDataCodecTest` pins that.
    `docs/reader-features-and-plan.md` and `docs/reader-revival.md` are kept as the rebuild's
    input; they describe code that no longer exists.
  - **Side effect worth knowing:** the text→audio resume bridge went with it, so
    `AudioCascade.resolveStart` now runs for every book. That closes the open bug where a finished
    book with an epub attached resumed at the reading position instead of restarting.

## Deliberately not done

- **Listen↔read sync is frozen.** The alignment/Vosk machinery exists and works, but its UI is
  gated off behind `FeatureFlags.EBOOK_SYNC_UI = false`. Do not extend or wire it up without
  being asked — "except sync" was the explicit scope of the reader work.
- **No server-dependent reader features** — cloud sync, Readwise/Hardcover/WebDAV/Drive/S3,
  translation, AI-over-the-book, dictionary lookup, downloadable neural voices.
- **EPUB only** — no PDF, MOBI, AZW3, CBZ, FB2.
- **Reader annotations, highlights, bookmarks and TTS** are surveyed but not built. The full
  Readest feature inventory with a port/adapt/skip verdict per item is Part A of
  `docs/reader-features-and-plan.md` — check there before designing any reader feature.

## Known open items

- `stash@{1}` holds an unmerged chapter-seek fix (19 lines in `PlayerController`).
- `stash@{2}` is a disproven native-demux experiment, kept only as evidence.
- **Skip silence is reported unreliable and unintuitive** and is still open on the board. The
  machinery reads correct — `LiveSilenceSkippingProcessor` stays in the sink chain, the per-book
  toggle and the global tuning both take effect live — so the report needs a concrete repro before
  anything is changed. The intuitiveness half is real and unaddressed: "Sensitivity" is a raw PCM
  threshold shown as a bare number between 256 and 4096, and the toggle being per-book while the
  tuning is global is nowhere explained.
