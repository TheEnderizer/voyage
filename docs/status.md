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

Last updated: 2026-09-17 · beta `1.14.0b` (75) · DB v29

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
- **Companion: the fixes from the first end-to-end pass on device.** Driving every companion
  action on an emulator turned up eleven faults; all are fixed, none of them changed the pack
  format. The ones worth knowing about: a pin could not be *tapped* while editing, because
  `detectDragGestures` never reports a gesture that stays inside touch slop — so the selected-pin
  card, and the only "Remove pin" control that exists, were unreachable (selection is a plain
  `clickable` in both modes now). Anything authored while the book was not playing anchored at the
  **reveal cursor** rather than the saved resume position, so a pack edited from Book Info stamped
  everything at 0m and revealed it instantly. The catch-up confirm dialog counted only the base
  document, so on a received pack it said "nothing new" and then revealed the listener's own
  edits anyway. Re-importing a pack you exported yourself attached a second copy of it and
  flipped the original to "received". Seeding a received pack duplicated every character it
  already had (the owned path had always merged by name). Also: the pin card printed raw canvas
  coordinates ("at 464,228"), renaming a character had no affordance at all, the wiki seed's rows
  were only tappable on the checkbox and its chapter field never said it was required, the
  delete-map sentence read "its 0 pins", a fact-less character now says so on its tile, and
  `companion_notify_threshold` — stored since packs landed, never offered — is a row in
  Settings → Library. Verified on an emulator.
- **Forking a pack brings its pictures with it.** "Duplicate as my own" flattened base + edits into
  a new pack id and wrote `pack.json` there, but a pack's `media` paths are pack-relative, so the
  copy kept saying `media/nephis.png` while its own directory held nothing at all — every portrait
  fell back to a letter tile and the map read "No image yet" while still counting its versions.
  `CompanionDataStore.copyBookPackMedia` copies the directory across before the new pack goes live.
  Found by a second end-to-end pass on the emulator; that pass re-confirmed the eleven fixes above.
- **A pack's schedule is visible and editable, and a map pin can be dragged.** Two reports, one
  root: everything a companion pack says is anchored at a point in the book, and that point was
  written once — wherever the listener happened to be standing — and then never shown again.
  - **Every anchor can be moved.** `CompanionAuthorRepository.editFact` and the matching
    `CompanionEditsRepository` op now take an `anchorGlobalMs`, rewriting **both** exact tiers
    (`globalMs` and `ratio`) together so the same entry cannot land in two places depending on
    whose library resolves it; `setBoardArtAnchor` does the same for a map version. Add-fact asks
    for the position instead of assuming the current one.
  - **The Timeline pane grew a "Story schedule"** in edit mode: every fact, every pin placement and
    every map version in one book-ordered list, each row opening the entry behind it. The Cast
    showed what a pack knows and the digest showed what was let through; neither showed *when*, and
    nothing offered to change it. Consumption is untouched — the reader's Timeline is exactly what
    it was.
  - **One editor, reached from wherever the value is** (`EditFactDialog`): a sheet row's new clock
    icon, a stat badge, the summary paragraph, a schedule row, or the pin card. A pin's `location`
    entry hides its value — that value is canvas units, and only its position in the book is of any
    use — so for a pin the dialog is just "when is this character here".
  - **The pin drag was a race, and the pin usually lost it.** It was `Modifier.clickable` in front
    of `detectDragGestures`, which reports nothing until the system touch slop is cleared — so the
    start of every drag went nowhere while the map's own `detectTransformGestures`, which sees the
    same pointer, was free to read those early moves as a pan and take the gesture away. It now
    consumes the pointer on touch-down (`awaitEachGesture`): in edit mode a finger on a pin belongs
    to the pin, the transform handler abandons a consumed gesture, and the marker moves from the
    first millimetre. The tap target also grew to 52dp and is no longer the marker itself — the
    face is 34dp and the caption under it drew outside the pin's bounds, so pressing the name did
    nothing at all. Measured: a plain `adb input swipe` now moves a pin, which before needed eleven
    hand-stepped motion events.
  - Verified end to end on the emulator: an entry moved to 1h25m, a pin's placement moved to 2h10m
    with its coordinates unchanged, and a map version moved to the start — each confirmed in
    `pack.json` on disk.
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

- **Standalone ebook-only rows purged (DB v29).** Removing the reader took `Book.ebookPath` with
  it, and with it the `isEbookOnly` filter that kept `EbookScanner`'s loose-`.epub` rows out of the
  Audio grid — so seven of them surfaced in the library as books with nothing to play (35 → 42).
  `MIGRATION_28_29` deletes rows whose folderPath carries the scanner's synthetic `::epub::`
  marker, matched on that rather than on `fileCount = 0`, which a real audiobook reaches when its
  files go missing. Every child table is deleted explicitly: **Room runs migrations with
  `PRAGMA foreign_keys` off**, so `ON DELETE CASCADE` does not fire in a migration and a bare
  `DELETE FROM books` leaves orphans. The `.epub` files and their `data/epub.<slug>.json` mirrors
  are untouched on disk. Verified on device.

- **The player grows out of the page you opened it from.** Book Info's Resume used to shrink the
  page back onto the grid card and then start the book under a collapsed mini bar — the cover
  travelled away from the finger that tapped it, and you were left where you started. The full
  player now morphs out of the info page's **own** cover, published through a third
  `CoverBoundsRegistry` source (`publishInfoCover`, keyed `"book:<id>"` / `"series:<id>"`) that
  outranks even a live mini bar. The Series page's Play is the same transition from the series
  cover. No new motion code: in Material You the existing `coverCropMorph` re-crops 0.72 → square,
  and in Immersive both screens already draw the cover full-width and top-anchored, so `morphFrom`
  reduces to an identity transform and the cover stays put while the room changes around it.
  Material You's background also grows out of that rect now instead of out of a `Rect.Zero` mini
  bar (which `morphingContainer` read as "no morph" and painted full-screen on frame one). The
  overlay stays mounted until the player has covered it (p ≥ 0.995, `MainActivity`) and the source
  is dropped at that point so a later collapse still travels back to the mini bar. Spec in
  `docs/motion-spec.md` §3b. Not yet verified on device.

- **The companion map is redesigned per theme, and adding a pin works.** Three things:
  - *The map is the pane.* The board chip strip, the four editor icons, the Versions strip and its
    explanatory sentence all moved onto the artwork as floating chrome — a plate carrying the
    board's name, pin count and switcher, a rail of authoring actions, and versions/zoom chips —
    which is worth roughly 150dp of map on a phone. Versions and the map switcher became dialogs.
  - *The two looks now differ in structure, not just fill* (`mapIsFullBleed`): Material You
    contains the map in a `surfaceContainer` card at the app's own 28dp cover radius with the
    artwork fitted inside it; Immersive lets the map **be** the deck's ground, edge to edge, with
    the header, spine and dock floating on it (`PortraitDeck`/`LandscapeDeck`'s `overlaid` branch,
    which measures the floating bands and hands their heights back as the pane's content padding).
    The letterbox around a fitted map is filled by a blurred, dimmed copy of the map itself, and
    the chrome is kept readable by two `mapVeil` gradients — eased with smootherstep rather than
    linear, because a linear ramp's slope discontinuity at alpha 0 is visible as a line across the
    artwork. Pins take theme fills instead of the hardcoded white-on-black they had.
  - *Adding a pin did nothing at all.* `addPinFor` wrote a `PIN` element and no `location` fact,
    and `PackBoardResolver.resolvePins` returns nothing for an element whose entity has no resolved
    location — so the character was silently invisible, with no marker, no error and nothing in the
    log. It also targeted `mapBoards.first()` rather than the map on screen, so on a multi-map pack
    pins landed on the wrong board. A pin now lands at the centre of the current view with a
    "drag into place" hint, unless the character already has a recorded location (in which case it
    appears there instead, and asks for nothing).
  Not yet verified on device.

- **Every destructive companion action asks first.** One `ConfirmDialog` behind seven callers —
  delete map, remove pin, delete map version, remove character, delete entry, delete note, and
  "take theirs" (which drops one of your own edits). There is no undo anywhere in the pack model,
  so none of them should have been one tap. Adding, renaming and dragging are deliberately *not*
  confirmed: a pin dragged wrong is fixed by dragging it again. Not yet verified on device.

- **The app's typeface is a setting.** Settings → Theme → Typeface: Poppins, or a `.ttf`/`.otf`/
  `.ttc` from the phone. The file is copied into `<libraryRoot>/.voyage/fonts/` (falling back to
  `filesDir/fonts/` before a library folder is chosen), because the picker hands back a
  `content://` grant that stops resolving between launches — and because a font in `.voyage/`
  survives a reinstall alongside the `custom_font_path` the settings mirror records. Validation is
  a real `Typeface.createFromFile` on the written copy, with the previous font restored if Android
  refuses the new one. `voyageTypography(family)` replaced the `Typography` constant so the ramp is
  stated once and the face is the only thing a preference changes. Not yet verified on device.

- **Resume becomes the play button.** The Book Info → player transition is a per-element
  choreography rather than a cover morph with everything else cross-fading: the Resume pill is
  redrawn into the 72dp play button while the transport slides out from under it and the utility
  rail rises from the display edge; the title, author and whole-book track descend into their player
  positions; the synopsis, status chip and percentage row fade out; the chapter scrubber and times
  fade in. Values in `ResumeChoreography` (`ui/player/ElementMotion.kt`), modifiers in
  `ui/player/ResumeMorph.kt`, page half in `ui/components/BookInfoPanel.kt`, table in
  docs/motion-spec.md §3c. Both portrait players; landscape keeps the old behaviour. Not yet
  verified on device.
- **The whole-book track is one widget in three places.** `ui/components/BookSegmentTrack.kt` — the
  Immersive player's old private `BookTickTrack`, promoted. Book Info drew a flat percentage line
  and the two players drew different things; all three now draw the same divided track (a tick per
  chapter, played dimmed, current chapter lit), which is both the better picture and the thing that
  lets the bar travel between the page and the player. Material You's "Book … % ⌄" row keeps its
  label and expander; only its bar changed. Not yet verified on device.
- **Fixed: opening the player from Book Info re-navigated the player route at the end of the
  morph.** `PlayerSheet`'s nested navigation was keyed on the whole `PlayerTarget`, and
  `clearCoverSource()` (which runs at p ≥ 0.995) replaces that object — so landing the animation
  cleared the back stack, built a fresh `PlayerViewModel` and re-applied `startPlaying`. Keyed on
  the route string now. **A separate report — "sometimes Resume has to be pressed twice" — is not
  yet reproduced**; `PlayerSheetController.open()` and the expand effect carry temporary
  `VoyageMorph` log lines for it. Remove those once it is pinned down.

- **Fixed: the resume leg flashed the un-scrimmed cover over the page.** The immersive player
  draws a SHARP copy of the cover over its baked backdrop, opaque until p = 0.55, so the eye has a
  solid object to follow out of the mini bar. It is top-anchored at the cover's own full aspect and
  carries no scrim, while both Book Info and the finished player show the BAKED composite —
  scrimmed, its sharp half a quarter of the way down. On the resume leg the cover morph is an
  identity, so that sharp copy doesn't travel: it slammed over the page at full opacity the instant
  Resume was pressed, the scrim disappeared and the artwork's lit extent jumped downward, then the
  whole thing settled back as it faded out over 0.55–0.90. **That flash is what "the page expands
  from the top-left corner" was.** It is now simply not drawn when `resumeActive` — the page and the
  player already draw the same backdrop, so there is nothing to cross-fade. Found by driving the
  phone over adb with `animator_duration_scale` at 40–100× and stepping the transition frame by
  frame; the same trick is the fastest way to look at any of this motion work again.
- **`ResumeChoreography.LEAVE_TO` 0.16 → 0.30.** The page's synopsis, chip and percentage row were
  gone within two frames of the press, which read as a cut rather than a fade.
- **Every NavHost passes `sizeTransform = { null }`.** Navigation's `AnimatedContent` otherwise
  animates the container's size between destinations and clips to it. Every route in this app is
  full-screen so it has nothing to express. This was investigated as the cause of the corner wipe
  and was NOT it (see above) — kept because the animation is pure cost either way. It is also NOT
  sufficient to stop a navigate from clipping its destination; see "a NavHost navigate clipped the
  player" below, which is why the player's nested NavHost no longer navigates at all.
- **Immersive has no bounce.** Its sheet spring was the shared `MotionTokens.floatSpatial`
  (damping 0.82 — "slight overshoot"), and the info-page open/close were 0.85/0.9; all three are
  critically damped now, with the stiffness raised so arrival stays as quick. Material You's
  deliberate rebound is untouched.

- **Fixed: the resume morph only ran when the player was ALREADY open.** `startPlaying` was part of
  the nested player route (`player?bookId=X&startPlaying=Y`), which made "restore this book paused"
  and "resume this book" two different destinations for the same player. Pressing Resume on a book
  the sheet was already holding therefore tore the player down and rebuilt it -- fresh ViewModel,
  fresh layout -- on exactly the frame the expand started. Every morph modifier then ran against a
  player that did not exist yet: every `own` rect was `Rect.Zero`, so each took its "nothing to
  travel from" branch, while the page had already handed its title, track and Resume button over at
  p > 0.02. The button vanished into a gap and the finished player popped in halfway through. Open
  the same book twice in a row and the route did not change, nothing was rebuilt, and the
  choreography ran perfectly -- which is exactly the "works when the player is already open"
  symptom. The route is now the book and only the book; `startPlaying` is an ACTION, delivered as a
  parameter read live off the target, and both players key their auto-play effect on it so a
  restore that is then resumed flips it in place. Belt and braces: `PlayerExpandTransition` carries
  a `playerReady` flag the portrait players set once their play button has real bounds, and the
  sheet holds an expand up to 250 ms waiting for it. NOT yet verified on device.
- **Fixed: the play button clipped its own travelling contents.** `clip(Pill)` on the 72dp box
  clips that box's CONTENT -- and on the resume leg the content is exactly what has to sit outside
  it, since the glyph and the word start over the page's Resume button. Both were invisible until
  the pill had shrunk enough to contain them: the label never faded anywhere and the glyph popped
  in at full size halfway through. Not clipped on this leg. The glyph now also SCALES (22dp ->
  40dp) rather than only translating, and holds the play triangle until the transition lands
  instead of becoming a pause bar mid-flight.

- **Fixed: the whole choreography ran against parked coordinates.** While the sheet is collapsed,
  the full player is pushed one screen height down so the app below stays interactive. Every morph
  modifier records its own rect with `onGloballyPositioned { positionInRoot() }`, and that offset is
  part of what it records. A placement lambda that reads a new value re-runs the placement; it does
  NOT reliably re-fire those callbacks. So while parked, every element believed it lived ~3168 px
  below where it draws, and each one travelled from a screen height off the top: nothing was visible
  for the first two thirds of the gesture, then everything arrived at once. The parked state is a
  COMPOSITION state now (`forceUnpark`), because swapping the modifier chain forces the relayout
  that a changed offset value does not. The expand effect unparks FIRST, lets two frames land, and
  only then animates progress. Measured on the phone: `own=5059` at every sample before the fix,
  `own=1891` from the first animated frame after it.

- **Fixed: a NavHost navigate clipped the player to a window opening downward.** This was the second
  half of "the icons disappear and then appear in a swipe, as if a rectangle is expanding", and it is
  a DIFFERENT fault from the corner wipe above. A NavHost renders its destinations inside an
  `AnimatedContent`. On the frame a navigate lands, that `AnimatedContent` clips the incoming
  destination to a window that opens downward from the top, while the content inside it is already
  laid out at full size. Measured on the emulator, Resume leg, the destination's own `boundsInRoot()`
  against its measured height of 3168:

  | progress | measured height | clipped to |
  |---|---|---|
  | 0.00 | 3168 | 0..791 |
  | 0.31 | 3168 | 0..1683 |
  | 0.73 | 3168 | 0..2546 |
  | 1.00 | 3168 | 0..3168 |

  The play button sits at 2652..2868, so it was cut away entirely until p ~ 0.77 and then appeared to
  grow downward. The transport and the utility row were never late; they were clipped. The Box
  wrapping that NavHost measured 0..3168 unclipped throughout, so the clip came from strictly between
  the two. **`sizeTransform = { null }` and `EnterTransition.None` do not remove it** -- this is
  structural, not another transition argument, which is why an earlier attempt from that direction
  failed. The mini-bar leg never showed the fault because its NavHost settled on the player route
  long before: there the same probe fires once, unclipped. Only the Resume leg navigates on the very
  frame the sheet expands, which is exactly the "it only goes wrong when the mini player is closed"
  report. **The nested NavHost now starts on the player route and never navigates.** `target != null`
  already guards that block, so `playerRoute` is always real and the old `blank` start destination
  existed only to navigate away from. `key(playerRoute)` gives a book switch what
  `popUpTo(0) { inclusive = true }` used to give it -- a fresh controller, back stack and ViewModel --
  by rebuilding the NavHost instead of animating between two destinations. Verified on the emulator:
  after the fix the destination reports `b=0..3168` on every frame of both legs, and a book switch
  with the player live still rebuilds cleanly.

- **Settings collapses to its answers.** The reported problem was "too long, hard to navigate", and
  the root was not the cause: it is nine rows and half a screen of empty space. "Look and feel" was
  about **seven screens** of unbroken scroll, because every option was a full-width card carrying a
  title AND a two-line description, drawn permanently expanded, for choices made once and never
  revisited. `CollapsingGroup`/`CollapsingCard` (`ui/settings/SettingsComponents.kt`) collapse each
  group to `title — current value`; the descriptions still exist and appear when the group opens,
  which is the only moment they earn their space. Look and feel is now **one screen** in both
  themes, with room to spare. Open/closed lives on the ViewModel (`openGroup`/`toggleGroup`) because
  only one group may be open at a time — that is what stops a section growing back into the wall it
  replaced — and `navigateTo` clears it so arriving anywhere starts from the summary.
  - **Icons are unchanged, by instruction.** The nine root glyphs are the ones `RootSection.kt`
    always had. The disclosure chevron is `ExpandMore`/`ExpandLess`, already used in three places
    each, so no new glyph entered the app. Down means "opens here"; the existing sideways
    `ArrowForwardIos` still means "opens another screen".
  - **A switch keeps its control in the collapsed header** (`trailing` slot): for a toggle the
    control IS the answer, so hiding it behind a disclosure would cost a tap to reach something that
    was already one tap away. The paragraph is what goes inside.
  - **The root states its settings instead of only forwarding to them** — `NavRow` gained an
    optional `value` ("30s / 15s", "11 books", "1.14.0b"), and three bands label runs of the
    existing order. Row order and icons are untouched; Diagnostics shows no value rather than an
    invented one.
  - **Hidden groups are left out of the list, not emitted and hidden.** An `item { }` holding an
    invisible child still takes the LazyColumn's spacing; with every group one row tall, three such
    slots read as a dead band above App icon in Material You. The theme conditions are hoisted to
    `LazyListScope` (`if (appTheme == …) item { … }`). Nothing needed the cross-fade — a theme
    switch rebuilds the screen anyway.
  - Settings is deliberately NOT split per theme: `settingsCardColor()` already resolves per theme
    and the two looks differ in fill/radius/type, not structure — splitting it would be splitting
    ahead of need, which CLAUDE.md warns against.
  - **Not yet done:** search over settings, and the same conversion for Library (11 groups) and
    Playback (7). Verified on the emulator in both themes; not yet seen on the phone.

- **Playback collapses too; Library gets bands instead, on purpose.** Playback's seven groups became
  eight `CollapsingCard`s under three bands (the first card packed Skip forward, Skip back and the
  preset link behind one heading — split, because a collapsed group states its own answer and one
  heading cannot state three). Library did NOT get the same treatment: its rows are `SettingsCard`
  **actions** — Grant, Browse, Rescan, Reset — not settings with values, and collapsing an action
  costs a tap to reach something that was already one tap away. It gets three band headers and
  nothing else.
- **Settings search.** `ui/settings/SettingsSearch.kt` holds a hand-written index of every group and
  section, each with the words someone would actually type ("vibration" finds Haptics, "brightness"
  finds Backdrop darkening). Ranked so a title that starts with the query beats one that merely
  contains it, which beats a keyword hit — otherwise "cover" puts Colour source above Cover accent
  on declaration order alone. A result carries its path ("Look and feel › Seek bar") and opens that
  section **with the group already open** (`openSearchResult`, which sets `openGroup` after
  `navigateTo` because navigateTo deliberately clears it).
  - **Results are filtered by what actually exists right now.** Each entry carries a `visible(theme,
    colorSource)` predicate mirroring `themeSection`'s own conditions. Without it, searching "cover"
    in Material You offered "Mini player cover" (Immersive-only) and "Cover accent" (needs the cover
    colour source) — both dead ends that read as the setting having moved. Change the predicates and
    the section conditions together.
  - The index is hand-written because the sections are `LazyListScope` extensions whose groups exist
    only while composing; there is nothing to enumerate at rest.
- **Fixed: the player's preset menu could never be opened.** `PresetChip` in `AudioSettingsSheet`
  has a DropdownMenu holding Load / Set as default / Delete, but nothing ever set `showMenu = true`
  — the chip's tap loaded the preset and its long press overwrote it outright. So Delete and
  Set-as-default were unreachable from the player. Long press now opens the menu, and Overwrite
  moved into it: it is a destructive unconfirmed write, and a long press is far too easy to hit by
  accident for that. **Note:** the reported "can't delete presets" could NOT be reproduced in
  Settings → Audio presets on a clean install — deleting a plain preset and a starred global-default
  one both worked — so this may or may not be the same fault.
- **Aurora scrubber is thicker**: hairline 1dp → 1.8dp, played wash 2.5dp → 4dp (by request; at 1dp
  the unplayed half vanished against a bright cover and the track looked like it began at the
  playhead).

- **Settings search moved into the app bar.** It was a permanent field above the list; it is now an
  icon to the right of the title, and tapping it turns the title itself into the field (title slot +
  `actions` slot, `SettingsTopBarTitle`/`SettingsSearchAction`, shared by both theme variants since
  only the bar's colours differ). A field parked above the list cost a row of height on every visit
  for something wanted occasionally and competed with the first band for the eye. Opening it focuses
  the field, Back leaves search before it leaves the screen, and `navigateTo` closes it — a query is
  a way of getting somewhere, so arriving ends it. Offered on the root only (and in Material You's
  two-pane landscape, where the root list is always on screen).
- **The mini player now leaves instead of blinking out.** A fling-down close called
  `playerController.stop()` and `controller.clear()` on the same frame, so the bar was simply absent
  on the next one. It now falls by its own height plus its bottom padding while fading, then stops
  playback — `dismissAnim` driven through the `graphicsLayer` the bar already had, so it costs no
  layout passes (C3-5, same reason `miniBarLift` translates rather than pads). The fade runs 1.6×
  the travel so it is invisible before it reaches the bottom: still faintly visible at the edge
  reads as sliding behind the display rather than being dismissed. `tween`, not a spring — a spring's
  settle implies arriving somewhere, and this is leaving.

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
