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

Last updated: 2026-09-03 · beta `1.14.0b` (75) · DB v26

---

## Built and shipped

Audio player (multi-file books as one timeline, resume, lock screen, sleep timer, damaged-MP3
recovery) · Series and Authors as first-class groupings · audio presets (speed/boost/EQ bundles)
with cascade resolution · library scanner with three import structures · disk-mirror storage
(reinstall-proof; disk is source of truth) · two themes (Material You / Immersive) with the
per-theme UI split · 26 launcher icon variants · home-screen widget maker v2 · Gemini synopses ·
online cover search · haptics vocabulary · in-app updates from GitHub releases · auto-backup.

## Built, in beta, not yet released

- **Native EPUB reader** — own Compose renderer (parser → projection → paginator → page view),
  replaced the WebView. Contents screen, in-book search, chapter-tick scrubber. `1.13.0b`.
- **Reader customization** — 45 settings on a tabbed screen with a live preview: typography,
  page geometry incl. 1/2/auto columns, colour + custom themes + brightness/dimmer, scrolled
  mode, page-turn animations, tap/swipe/volume-key config, header/footer content, auto-scroll,
  auto page-turn, reading ruler, and per-book vs global scope. Not yet tested on device.
- **Companion packs** — pack registry, reveal cursor, companion deck UI, pack import/export.
  Merged 2026-09-03. Working but unreviewed WIP; more changes expected.

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

- Reader tap-to-turn wins the long-press over text selection; real gesture arbitration is unbuilt.
- Paginator never splits a block, so a block taller than the page gets a page to itself.
- `stash@{1}` holds an unmerged chapter-seek fix (19 lines in `PlayerController`).
- `stash@{2}` is a disproven native-demux experiment, kept only as evidence.
