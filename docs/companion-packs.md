# Companion Packs

Design + build plan for the timestamp-linked visual companion layer (maps, character sheets,
cast lists) and the share format that moves them between people.

Status: **plan only — nothing implemented.** Written 2026-08-26; audited against the codebase and
revised 2026-08-27 (reveal-cursor semantics resolved, engine moved off `PlayerController`, `fileKey`
corrected, import-reuse claims corrected).

---

## 1. Goal

Turn listening to a book into something you can *see*, without ever being spoiled, and let one
person's work be handed to another for free.

Concretely, three things at once:

1. **A visual layer bound to the audio timeline.** A world map whose character pins move as the
   story moves. A character sheet whose portrait, title, allegiance and status change as you learn
   them. A cast list that grows as people are introduced. All of it driven by playback position.
2. **Spoiler-proof by construction, not by discipline.** The app can only ever render what the
   listener has already reached. Not a filter bolted on top — a property of the data model, so
   there is no code path that *could* leak book 5 while you are in book 2.
3. **Authored once, shared freely.** One person builds the pack while listening; a friend imports
   a file and gets the whole thing, correctly placed on disk, correctly timed against their own
   copy of the audio, and revealed at their own pace from zero.

The thing being built is a **pack**: a portable document of what is true in a story and when you
are allowed to know it, plus the boards that draw it.

### What success looks like

- A listener with a pack open can glance at the player and see "3 things changed" without a popup
  ever interrupting them.
- A friend receives one file, taps import, and is listening with the companion working in under a
  minute — with their own progress at zero and nothing revealed.
- A pack authored against one rip of a book resolves correctly against a different rip, or says
  plainly that its timings are approximate.
- The person who authored it can fix a timing a week later and ship a v2 that does not clobber the
  recipient's own additions.

---

## 2. Non-goals (v1)

- **No server, no accounts, no sync.** The app produces a file and consumes a file. Distribution is
  whatever the user already uses (share sheet, Drive, USB, Telegram).
- **No multi-pack layering.** One active pack per book/series, with a switcher. The model supports
  layering (facts carry provenance) so it is additive later, but the precedence UI — two packs
  disagreeing about a character's allegiance — is a real design problem and nobody has two packs
  for the same book yet.
- **No pack marketplace / discovery.**
- **No auto-generation in v1.** The Gemini + aligned-text seeding path (§13) is the highest-value
  follow-up but is not on the critical path.

---

## 3. Concepts

### Fact

The atom. Everything else is built from it. A fact asserts one field of one entity, and carries
**two independent timestamps**:

- `storyAt` — when it became true *in the story*
- `revealAt` — when the listener is *allowed to know it*

These are usually equal. They differ on every twist: at 9:12:00 you learn the butler was a spy the
whole time — `storyAt = 0`, `revealAt = 9:12:00`. Rendering "the world as of now" filters on
`revealAt`; a chronology view sorts on `storyAt`. Without the split, every reveal forces the author
to lie about the timeline.

### Entity

A named thing with fields: character, place, faction, item, concept. It has no state of its own —
its state at position *t* is the newest fact per field with `revealAt <= t`. A pin that moves is
just a `location` field revised at several anchors. A character sheet that evolves is the same
entity's fields revised. An image appearing for the first time is an entity whose first fact is
anchored there.

### Board

A drawable surface referencing entities: a map with pins, a character sheet layout, a relationship
graph, a cast strip. Boards declare their own presentation mode (§8).

**A pack may hold several maps**, and each one **changes over the story**. Pins already moved,
because a pin's position is a fact and facts are anchored — but the picture underneath them was
fixed, which made a board a snapshot with moving parts on it. `artRevisions` fixes that: each entry
is an image plus the anchor it becomes true at, resolved by the same `AnchorResolver` and filtered
against the same reveal cursor, so a map of the capital *after it burns* cannot be shown to someone
who has not reached that point. `artMedia` remains the one image with no anchor — the state of the
world before anything happens to it.

Several maps and several versions of one map are different things and the format keeps them apart:
a world map and a city map are separate documents (separate boards, separate pins), while the same
map before and after a war is one board with two revisions.

### Pack

The shareable unit: entities + facts + boards + media, scoped to one book or one series.

---

## 4. Data model

All ids are **UUIDs minted at authoring time**. This is the single most important constraint in
the whole design: the moment a fact's identity is a Room autoincrement, editing, updating and
sharing all break simultaneously, and there is no repair path — the author's `id=42` and the
recipient's `id=42` are different facts. Room row ids stay strictly local cache keys and never
appear in a pack file.

```jsonc
// pack.json
{
  "schemaVersion": 1,
  "packId": "b1f0...",             // uuid, never changes across revisions
  "revision": 3,                   // monotonic, bumped by the owner on export
  "scope": "SERIES",               // BOOK | SERIES - fixed at creation
  "title": "Mistborn - Era 1",
  "authorHandle": "hajmo",
  "derivedFrom": null,             // {packId, revision, authorHandle} when forked
  "payload": "DATA_ONLY",          // DATA_ONLY | FULL (FULL = audio ships alongside)

  "members": [                     // exactly one entry for BOOK scope
    {
      "bookRef": "m1",             // local-to-pack short id used by anchors
      "title": "The Final Empire",
      "author": "Brandon Sanderson",
      "seriesOrder": 1,
      "durationMs": 90123000,
      "fileCount": 24,
      "fileKeys": ["a3f...", "..."]
    }
  ],

  "entities": [
    {
      "entityId": "e7...",
      "kind": "CHARACTER",         // CHARACTER | PLACE | FACTION | ITEM | CONCEPT
      "name": "Kelsier",
      "media": "media/kelsier.webp"
    }
  ],

  "facts": [
    {
      "factId": "f2...",
      "entityId": "e7...",
      "field": "allegiance",
      "value": "Survivors",
      "anchor": {
        "bookRef": "m1",
        "fileKey": "a3f...", "offsetMs": 742000,
        "globalMs": 3120000,
        "chapter": 14, "chapterOffsetMs": 200000,
        "quote": "...",
        "ratio": 0.0346
      },
      "storyAt": null,             // null = same as revealAt
      "importance": "MAJOR",       // QUIET | NOTABLE | MAJOR
      "origin": "b1f0...",         // provenance: packId this fact came from (§2 layering, §9.2 overlay)
      "rev": 2                     // revision this fact last changed in - basis for §9.2 conflicts
    }
  ],

  "boards": [
    {
      "boardId": "b9...",
      "kind": "MAP",               // MAP | SHEET | CAST | GRAPH | GLOSSARY
      "presentation": "FULLSCREEN",// FULLSCREEN | SHEET | INLINE
      "title": "Dream Realm",      // shown in the deck's map switcher; a pack may hold several
      "artMedia": "media/map.webp",// the backdrop before any revision — true from t=0
      "artRevisions": [            // later states of the same board, anchored like facts
        {
          "artId": "a4...",
          "media": "media/map_after_the_war.webp",
          "anchor": { "bookRef": "m1", "globalMs": 3120000, "ratio": 0.0346 },
          "label": "After the Throne War"
        }
      ],
      "artTone": "DARK",           // hint so surrounding chrome picks a readable side
      "elements": []               // design-unit canvas, see 4.1
    }
  ]
}
```

### 4.1 Board canvas

Board layout reuses the model already proven by the widget maker —
[WidgetDesignDoc.kt](app/src/main/java/com/betteraudio/widget/model/WidgetDesignDoc.kt): a fixed
1000-unit design space, a z-ordered element list, one uniform scale to pixels at render time. A map
board and a character sheet are structurally the same thing as a widget design with different
element kinds, and the editor interactions (drag / resize / rotate) are already built and debugged
in [WidgetEditorViewModel.kt](app/src/main/java/com/betteraudio/ui/widget/WidgetEditorViewModel.kt).

**One deliberate divergence:** `WidgetDesignDoc` stores raw `Long` colors, which is right for a
widget designed against the user's own wallpaper and wrong for a pack that must look correct in
someone else's theme. Pack colors are `{"role": "accent"}` **or** `{"raw": 4294901760}` — semantic
roles resolved against the recipient's theme by default, raw as the escape hatch for "this faction
is *red*".

---

## 5. Anchors and resolution

The audio normally travels with the pack, so the primary anchor is exact. Everything below it is a
fallback for the case where it does not.

| # | Anchor | Survives | Precision |
|---|--------|----------|-----------|
| 1 | `fileKey` + `offsetMs` | folder renames, file re-sorting | exact |
| 2 | `globalMs` (book-wide ms) | identical file set | exact |
| 3 | `chapter` + `chapterOffsetMs` | file re-splits | exact if chapter counts match |
| 4 | `quote` (text anchor) | *a different edition / narrator* | paragraph |
| 5 | `ratio` (fraction of total duration) | anything | ±minutes |

`fileKey` = `sha1(size || first 64 KB || last 64 KB)`. Never hash a whole file — a 900 MB track on
a phone is unacceptable, and the size + head + tail triple is more than adequate to identify a
specific encode.

**Duration is deliberately not in the key.** It is decoder-derived, not file-derived:
`METADATA_KEY_DURATION` returns null on very large files (`Mp4Probe.kt:53` — "7 GB / 261 h book:
comes back null without throwing", hence the `Mp4Probe` fallback at `AudioFileScanner.kt:693`), and
a damage-repaired file reports a shorter *playable* duration (`PlayerController.playableDurationMs`).
Two devices holding a byte-identical file would compute different keys, silently demoting the
anchor the plan calls exact — and the failure would be invisible, showing up only as a
`timings approximate` badge the user was told they would never see.

Anchor #4 resolves through the existing alignment work — `SyncAnchor` (DB v14) and the
`mapping.json` produced by the BetterAudioAlign PC tool. It is the only anchor that survives a
*different edition*, which is why it is worth writing even though it is never the fast path.

**Resolution rule:** try in order, stop at the first that resolves; record which one was used on
the resolved fact. Surface a `timings approximate` badge on the board **only** when resolution fell
past #2 — when the audio shipped with the pack, that badge is never seen.

Facts whose `bookRef` matches no book in the library stay **dormant**, never an error. The
companion says so plainly: *"2 books in this pack aren't in your library — 47 items hidden."*

---

## 6. Reveal semantics

### 6.1 The rule

> A fact is revealed **iff** `resolvedRevealMs(fact) <= revealedMs(fact.bookRef)`

`revealedMs` is stored **per book**, never per series. Every series-wide question is derived from
the per-book values.

That handles the **cross-book** cases a single series-wide cursor gets wrong, automatically and with
no user action: reading out of publication order, having book 5 finished while book 2 is open,
abandoning book 3 halfway. Nothing from an unstarted book can leak, ever.

It does **not** by itself handle the **within-book** case — restarting book 2 from zero — because
per-book storage says nothing about whether a cursor can retreat. That is §6.2's rule 3: it can, on
a confirmed seek. The two mechanisms are separate and both are needed.

### 6.2 How it moves

The cursor is **not** a pure high-water mark. It moves in exactly three ways, and no others:

1. **Continuous listening advances it.** Ordinary tick-to-tick playback progress pushes it forward,
   scaled by playback speed (at 2.0× a 500 ms tick is ~1000 ms of book time — use the position
   delta, never the wall-clock delta).
2. **A raw seek never moves it.** Scrub, skip button, chapter jump, lock-screen scrub, Android Auto,
   headset — playback moves, `revealedMs` stays put until listening walks past it again. This kills
   accidental reveals with no user interaction at all.
3. **A confirmed seek moves it, in either direction** (§6.3). This is what makes re-listening work:
   restart book 2 from zero, confirm, and book 2's facts are hidden again.

**Where it lives: `PlaybackService`, not `PlayerController`.** The 500 ms ticker in
[PlayerController.startPositionTicker](app/src/main/java/com/betteraudio/playback/PlayerController.kt:193)
dies with the UI — its own comment says so: *"Periodic persistence lives solely in PlaybackService's
saver (survives the UI dying, and swipe-kill has no `onStop`)."* A user who backs out of the app and
listens for three hours in the car must still accumulate reveal. The cursor therefore rides
`PlaybackService.positionSaverJob`
([PlaybackService.kt:636](app/src/main/java/com/betteraudio/playback/PlaybackService.kt:636)).

**What distinguishes rule 1 from rule 2: `Player.onPositionDiscontinuity` + the existing
[JumpClassifier](app/src/main/java/com/betteraudio/playback/JumpClassifier.kt).** Explicitly *not*
`SkipEvent.source`, which looks right and is not:

- Sub-5-minute seeks write no event at all —
  [PlayerViewModel.kt:845](app/src/main/java/com/betteraudio/ui/player/PlayerViewModel.kt:845) only
  records when `delta > SCRUB_HISTORY_MIN_MS` (5 min). A 4-minute forward scrub would advance the
  cursor: a spoiler leak sitting directly underneath §15 test 3.
- Rows are written asynchronously from the UI layer (`PlayerViewModel.recordSkip`) and pruned at 100
  — a `playback/`-layer ticker reading a table the UI writes racily is both the wrong dependency
  direction and unreliable.
- Seeks from the lock screen, Android Auto, headset, or the `ForwardingPlayer` never reach
  `PlayerViewModel` at all.
- A naive size threshold would false-positive on silence-skip, which `JumpClassifier.kt:16` already
  documents as needing exclusion *by reason, not size*.

### 6.3 Confirming a seek

Rule 3 is one control, working in **both** directions — it is both "catch the companion up after I
deliberately skipped an hour" and "I'm starting this book again, hide it":

> **Set reveal point to here**
> "The companion will show everything up to 4:12:30. This reveals 7 items."
> *(or: "This hides 12 items.")*

- Always confirms, always states the count, in both directions.
- Undo snackbar restores the previous value. Held **in memory** in the ViewModel, not persisted —
  nobody undoes a reveal-point change after an app restart, and a persisted column would be a
  fourth place (`BookDocument`, `BookDataCodec`, `RestoreOps`, export sanitization) for the spoiler
  leak to hide.
- Lives in the companion screen header, not the player overflow. The person who cares is looking at
  the map.

**Offered automatically after a large jump.** When a `JumpClassifier`-confirmed seek exceeds a
threshold (~5 min, matching `SCRUB_HISTORY_MIN_MS`) and lands outside the revealed range, the
companion affordance shows a one-tap offer — *"Jumped ahead. Move the companion with you?"* — that
opens the same confirm. Dismissible, never automatic, never a popup over the player (§8.1). This is
what stops a deliberate skip from silently stranding the companion an hour behind without the user
ever thinking to go look for a button.

For a series pack the companion exposes a **Reveal progress panel**: member books listed with their
state (`not started` / `4:12 of 9:40` / `complete`) and a per-row "reveal fully" and "reset to
start", using the same confirm + undo. This is how a skipped book gets unstuck and how a re-read
gets re-hidden.

### 6.4 Storage

One new column on `playback_progress`
([PlaybackProgress.kt](app/src/main/java/com/betteraudio/data/db/entities/PlaybackProgress.kt)):

- `revealedMs: Long = 0` — **book-global milliseconds**, unlike the file-relative `positionMs` that
  sits beside it in the same table (the global value is derived via `filesBeforeCurrentMs`,
  `PlaybackProgress.kt:47`). State this in the entity's doc comment: two `…Ms` columns in one table
  in two different coordinate systems is exactly how someone later writes
  `progress.revealedMs <= progress.positionMs` and ships a leak.

**There is no existing progress-reset path to inherit from.** `PlaybackProgressDao` has no delete
and no reset; the NOT_STARTED control in `ui/home/BookOptionsSheet.kt:94` writes `Book.status`, a
column on a different table, and leaves `positionMs` untouched. So resetting a book's progress must
explicitly zero `revealedMs` too — a new `resetProgress(bookId)` on the DAO that clears both, used
by the import flow (§10.3) and by any future user-facing reset. Nothing does this for free.

---

## 7. Storage and files

### 7.1 On disk

Follows the existing rule — **disk is the source of truth, Room is a query cache** — and the
existing split between per-book and library-wide data.

```
<bookFolder>/data/companion/<packId>/       # BOOK-scoped
    pack.json
    edits.json                              # local overlay, only if edited (§9)
    media/...

<libraryRoot>/.voyage/companion/<packId>/   # SERIES-scoped
    pack.json
    edits.json
    media/...
```

**The BOOK-scoped path must be slug-namespaced, not a bare `companion/`.**
`BookDataPaths.dataDir(folderKey)` resolves through `containingDir`, which strips the `::` cluster
suffix — so every book in an AUTO multi-book folder shares one `data/`. That is why every other
per-book artifact there is slugged (`docFileName`, `coverBaseName`, `mappingFileName`, all via
`slug()`, whose own comment warns that "two cluster members in one folder would then fight over one
data file"). `companionDir(folderKey)` gets the same treatment:
`data/companion_<slug>/<packId>/`, collapsing to `data/companion/<packId>/` for a non-cluster key.

Series packs go under `.voyage/` because series data is library-wide, and are keyed by **series
name, not series id** — ids are not stable across a reinstall, which is why series covers are
already name-keyed. Attaching a series pack also needs a rule for matching the pack's `title`
("Mistborn - Era 1") against a local `Series.name` ("Mistborn"): fuzzy-match, then **always confirm
with a user pick**. Never auto-attach a series pack on a name guess.

Add `COMPANION_DIR_NAME` to
[VoyageLayout.kt](app/src/main/java/com/betteraudio/data/diskstore/VoyageLayout.kt) and a
`companionDir(folderKey)` to
[BookDataPaths.kt](app/src/main/java/com/betteraudio/data/diskstore/BookDataPaths.kt).

New `CompanionDataStore` alongside `BookDataStore` / `LibraryDataStore` / `WidgetsDataStore`, hooked
into [DiskMirror.kt](app/src/main/java/com/betteraudio/data/diskstore/DiskMirror.kt) like the
others. It dispatches to `Dispatchers.IO` itself — callers are Main-dispatched ViewModel scopes.

### 7.2 In Room

**One thin registry table, no relational fact storage.** A generous series pack — 40 characters ×
20 facts × 10 books — is a few thousand records, kilobytes. Load the resolved doc into memory when
a companion becomes active and filter there; a 500 ms tick over an in-memory sorted list is
nothing. This is the same conclusion the widget maker reached in the v19 rewrite (a JSON design
document, not a grid schema).

DB **v22 → v23**:

```sql
CREATE TABLE companion_packs (
  packId TEXT PRIMARY KEY NOT NULL,
  scope TEXT NOT NULL,            -- BOOK | SERIES
  targetKey TEXT NOT NULL,        -- relPath for BOOK, series name for SERIES
  title TEXT NOT NULL,
  authorHandle TEXT,
  revision INTEGER NOT NULL,
  enabled INTEGER NOT NULL,
  lastSeenRevealMs INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX index_companion_packs_targetKey ON companion_packs(targetKey);

ALTER TABLE playback_progress ADD COLUMN revealedMs INTEGER NOT NULL DEFAULT 0;
```

**`targetKey` is `relPath`, not `folderPath`, and there is no `docPath` column.** `Book.folderPath`
is not stable — `LibraryRestructurer.moveOne` (`:186-205`) hand-rewrites `AudioFile.filePath`,
`Book.folderPath`, `Book.coverArtPath` and `Book.ebookPath` on every move, four repointings each of
which had to be remembered. A fifth table with an unrepointed path column goes stale silently: the
pack directory travels with the folder (it lives under `data/`), but a row pointing at it would not.
`BookDataPaths.relPath` (`:100`) is the portable identity `BackupManager` already relies on for
exactly this reason. The doc path is then derivable from `targetKey` + `scope` + `packId`, so
storing it would be redundant *and* wrong.

Register the migration in **both** `AppDatabase.kt` and `di/AppModule.kt`; verify by building
`:app:kspDebugKotlin` and diffing against the generated `schemas/.../23.json`.

---

## 8. Presentation

Per-board `presentation` mode, author-chosen:

- **FULLSCREEN** — maps. A **persistent overlay, not a nav destination.** The companion affordance
  lives in the player, and the player is not a route — it is a draggable `PlayerSheet` with its own
  nested NavHost drawn *above* the top-level one, so navigating the top-level host to a board route
  would not put the board in front of an expanded sheet. `hideMiniBar` only suppresses the bar while
  collapsed (`PlayerSheet.kt:485`). The project has already hit and solved this twice —
  `SeriesOverlayController`/`SeriesOverlay` and `ChapterOverlay` are persistent overlays precisely
  because a real nav destination tore Home down and broke the cover-morph. The board is the third
  instance of that pattern.
- **SHEET** — character sheets, item cards. A bottom sheet over the player.
- **INLINE** — the cast strip inside the player.

> **Superseded, 2026-08-30.** The three presentations above are no longer three surfaces. The
> companion is now one full-screen **deck** that keeps the player's transport, navigates from a
> single spine (Cast · Map · Timeline · Pack), and edits in place — so FULLSCREEN, SHEET and INLINE
> collapsed into *panes* of one surface rather than a dialog, a bottom sheet and a strip. The
> per-board `presentation` field is still parsed and still describes what a board *is*; it no
> longer decides which window it opens in. Design and rationale:
> **`docs/companion-redesign.html`**. Implementation: `ui/companion/CompanionDeck.kt`.
>
> What did *not* change: the reveal cursor, the spoiler filter, `markSeen`, the digest and the pack
> format. §8.0.1 below is still exactly how a field becomes a badge, a chip or a bond row.

The SHEET presentation used to be **split per theme** (CLAUDE.md's UI split convention):
`ui/companion/CompanionSheet.kt` was a router over `ui/immersive/companion/CompanionSheet.kt` (a
character sheet — crest, stat line, titled sections) and `ui/material/companion/CompanionSheet.kt`
(a flat label/value list). All three are deleted; the deck is one implementation whose per-theme
half is a token file (`ui/companion/CompanionDeckStyle.kt`).

#### 8.0.1 Field-name groups

`PackFact.field` is an open string (§3), and `CompanionEntityState.fields` is one fact per field
name. That is enough for a rich sheet as long as authors agree how to name fields, so a pack marks
a field's section with a **`group:Label`** prefix, interpreted in one place —
`ui/companion/EntitySheetModel.kt`:

| field | renders as |
|---|---|
| `identity:Rank`, `identity:Class`, `identity:Soul cores` | a badge in the stat line |
| `identity:True name` / `Also known as` / `Role` | the subtitle under the name (first one present wins) |
| `identity:<anything else>` | a row in **Details** |
| `power:Flaw`, `power:Aspect` | a row in **Power** — or chips, if every comma-separated part is short *and* capitalised |
| `arsenal:Memories` | chips in **Arsenal**, always |
| `bond:Nephis` | a relationship row in **Bonds**, tappable when that entity is itself revealed |
| `description` | the summary paragraph |
| `location` | nothing — it is map data (§4.1), not text |
| anything else | a row in **Details** |

Two consequences worth stating. **A pack that ignores the convention still renders** — its fields
land in Details, which is why that fallback is a real section rather than a dropped value; this is
what keeps every pack authored before the convention working. And **a multi-value field is one
comma-joined fact, not one fact per item**, so a list that grows over the story (a character's
Memories) is a single field revising itself — which is what the reveal cursor and the digest are
built around — rather than N fields appearing independently.

Chips-vs-prose is decided by capitalisation, not length: `"Fated, Flame of Divinity, Blood Weave"`
is a list of named things, `"Strength, speed, agility, endurance and resilience, all raised at once"`
is a sentence, and both split into parts short enough to fit a pill. Since this power system's
things are proper nouns and prose is not, requiring every part to start capitalised separates them —
and doubles as a rule an author can follow deliberately to pick the rendering.

### 8.1 Passive signalling

**Never a popup.** A fact crossing the reveal cursor lights an indicator on the companion
affordance; it never steals focus, never pauses, never covers the controls. Driving-safe by
default.

Three importance levels, author-set per fact:

| level | effect |
|-------|--------|
| `QUIET` | nothing; it is simply there when you look |
| `NOTABLE` | dot on the companion affordance |
| `MAJOR` | dot, persists until the companion is opened |

Two additions that make passive mode actually work:

- **Receiver-side threshold.** The author's sense of "major" will not match everyone's. One setting:
  *notify me about* → major only / notable+ / never.
- **"Since you last looked" digest.** Passive means the user opens the companion 40 minutes later
  needing to know what changed. `companion_packs.lastSeenRevealMs` drives a diff view that the
  companion opens on — *"3 changes since you last looked"* — instead of dropping them onto a map
  with no idea what moved.

---

## 9. Authoring and editing

### 9.1 Capture now, structure later

The feature's real failure mode is not technical — it is that nobody wants to author while
listening. So:

- **Quick capture** during playback: long-press → drop a pin / add a note. Records the anchor and a
  scrap, nothing more. Two seconds, no typing required.
- **Timeline editor** later: scraps are already sitting at the right positions; the work is turning
  them into entities and facts.
- **Board editor**: the canvas editor from the widget maker, retargeted.

### 9.2 Editing a *received* pack: overlay, not fork

Fork is the tempting simple answer and it is the wrong default — it permanently severs the recipient
from the author's "v3, fixed timings" update, which is exactly the update people will send.

Instead: **the received `pack.json` is immutable.** Local edits live beside it in `edits.json` as an
op list. Render = base, then ops. Nothing ever mutates the base file.

The op vocabulary has to cover **every** document a recipient can touch, including boards — a pin in
the wrong place is the single most likely edit anyone will want, and if the model can't express it
they fork, which is the outcome §9.2 exists to prevent:

| target | ops |
|---|---|
| entity | `addEntity`, `editEntity`, `deleteEntity`, `hideEntity` |
| fact | `addFact`, `editFact(factId, fields)`, `deleteFact(factId)` (tombstone) |
| board | `editBoard(boardId, fields)`, `addElement`, `editElement`, `deleteElement` |

Updating to a new revision swaps the base and re-applies the ops. Conflict detection needs to tell
"upstream also changed this" from "upstream left it alone", which a bare swap cannot do — the old
base is gone. That is what the per-fact `rev` in §4 is for: an op records the `rev` it was authored
against, and a conflict is `op.rev != incoming.rev`. Cheap at P0, expensive to retrofit at P6.

Three conflicts, all with a boring default:

| situation | default | review action |
|---|---|---|
| you edited a fact, upstream also changed it | keep yours | "take theirs" |
| you edited a fact, upstream deleted it | keep yours, orphaned | "drop it" |
| you deleted a fact, upstream changed it | stays deleted | — |

One review screen, listed rows, two buttons each, default *keep mine*. When the list is empty — the
common case — the update applies silently.

**Fork stays available** as an explicit *"Duplicate as my own"*: new `packId`, flattened, stamped
with `derivedFrom` for attribution.

### 9.3 Sharing edits

Because the overlay is separate, *"share my edits only"* is a 4 KB file when the recipient already
has the base pack. This should be the default offer when sharing a pack you do not own.

---

### 9.4 Pregenerating from a wiki

A blank pack asks the listener to type in a cast they can already read about elsewhere. **Cast →
Pregenerate** (also offered on the empty state, where it creates the pack too) drafts one from the
book's Fandom community. Everything it produces is a draft: it lands in the normal editor, every
character is ticked individually before it is written, and every fact can be edited or deleted.

**Spoilers are filtered by citation, not by keyword.** A denylist of scary words ("dies",
"betrays") is both leaky and unprincipled — it cannot say *when* something stops being a spoiler,
which is the only question a reveal cursor asks. Fandom articles answer it directly, because they
cite the chapter for nearly every claim: `<ref>Chapter 172 Memory Market</ref>`, `{{c|Chapter 761}}`,
or a reused `<ref name=":1285" />`. So the rule is arithmetic — **a line whose earliest citation is
past the listener's cutoff is dropped, and a line that survives is anchored at the chapter it
cites**. A seeded pack is spoiler-safe on the same measured basis as a hand-authored one, and it
keeps revealing itself correctly as the listener goes on.

Four consequences worth stating, because each one looks like a bug until you know it is a rule:

- **An uncited line is dropped, not kept.** Being undatable is not the same as being safe. The live
  Shadow Slave article lists `[[Lord of Shadows]]` among Sunny's aliases with no ref at all; letting
  it through on the strength of its *neighbours* being cited handed a chapter-1308 listener the
  biggest reveal in the book. Only parameters that cannot be a spoiler at any point (`gender`,
  `eye_color`, `height` — `SAFE_UNCITED`) skip the gate.
- **Some parameters are a spoiler by their name alone.** No citation redeems `cause_of_death`:
  reading the field name is already the reveal. `vital_status`, `death_chap`, `fate` and friends are
  dropped unconditionally.
- **List fields revise themselves across the story** rather than landing as one fact. Sunny's titles
  arrive as `Sleeper Sunless` (ch 17) → `+ Cockroach` (ch 176) → `+ Awakened Sunless` (ch 355) →
  `+ Ascended` (ch 745) …, so the sheet is right at every position instead of only at the end.
  Capped at six revisions per field.
- **A wiki that cites nothing yields almost nothing.** That is the correct failure mode: empty, not
  wrong.

**Which characters.** A wiki that curates its own `Category:Main Cast` / `Main Characters` is
answering the question directly and is trusted over any ranking we could invent — on the Shadow
Slave wiki that category is exactly the seven characters a reader would name. Otherwise the broad
`Category:Characters` (routinely hundreds) is ranked by **article byte length**, a blunt instrument
that happens to be an excellent one: it orders Sunny (110 KB), Nephis (64 KB), Cassie (27 KB) …
Gantry (1 KB) — the same order a whole-text mention count gives, at no extra request, since
`prop=info` returns every candidate's length in the call that lists them.

**Chapter numbers become audio positions** through `ChapterNumbering`, which reads the story number
out of the book's own chapter titles ("Ep 1296 - Fake It" → 1296) and requires the parsed sequence to
come out strictly increasing before it trusts it — the check that stops a half-understood title
format from producing a plausible, silently-wrong map. Failing that it counts marks in order; and
for a book whose "chapters" are really eight volume-length files it reports `NONE` rather than
pretending, because a mis-mapped anchor puts a genuine spoiler in front of the listener at the wrong
moment. The dialog therefore always shows the cutoff chapter as an **editable, pre-filled** field
rather than deciding it silently.

The pre-fill is the **furthest of the reveal cursor and the saved resume position**. Using
`revealedMs` alone was the first version and was wrong on a real device: it starts at 0 and only
advances through continuous listening (§6), so it reads 0 for anyone who imported a pack, restored
a backup, or never triggered the catch-up — and a cutoff of "chapter 1" makes the seed come back
almost empty (12 facts across two characters, against 48 at chapter 1308), which reads as the
feature being broken rather than as the filter working.

One related trap, fixed alongside it: a pack folder deleted from outside the app leaves an
`enabled` registry row pointing at nothing, and the editor would pick that orphan as "the" pack —
rendering a document-less pack whose owner-only actions were all hidden because the deleted pack
had been *received*. `CompanionAuthorRepository.dropIfOrphaned` prunes such a row on load, which is
safe because the data it referenced is already gone. Seeding is also allowed on a genuinely
received pack now, via the edits overlay (§9.2) rather than a base rewrite — refusing there was
backwards, since a pack someone else started is exactly the one worth topping up.

**Fandom's own discovery APIs are gone** (`/api/v1/Wikis/ByString`, `/api/v1/Search/CrossWiki` both
404 now), so the wiki subdomain is guessed from the book title and confirmed with `siteinfo` — and
is likewise editable in the dialog. Requests need a browser-ish `User-Agent` or Cloudflare serves a
challenge page instead of JSON. Only the **lead section** (`action=parse&section=0`) is ever
fetched; the plot summary below it is nothing but spoilers.

**Attribution.** Fandom text is CC BY-SA. Values are short (300 characters, clipped on an item
boundary) and each seeded entity carries a `source` fact with the article URL, which the character
sheet shows under Details. Re-running the seed tops up an existing cast — entities are matched by
name and identical facts are not restated — so it is safe to run again after listening further.

Code: `companion/fandom/` — `FandomWikitext` (pure, the parser and the gate),
`ChapterNumbering` (pure, chapter ↔ ms), `FandomWikiClient` (network), `FandomSeedService`
(orchestration), `ui/companion/FandomSeedDialog`, and
`CompanionAuthorRepository.addSeededEntities` (one write, one revision — not ninety). The parser is
pinned by `FandomWikitextRealArticleTest` against **unedited** captured articles in
`app/src/test/resources/fandom/`, which is what caught the uncited-alias leak above; synthetic
fixtures had passed.

## 10. Sharing

The app never transmits anything. It produces a file and consumes a file; the user moves it with
whatever they already use.

### 10.1 Export

Two payload modes, one schema, one flag:

- **DATA_ONLY** (default) — pack + cover + fingerprints. Kilobytes to a few MB.
- **FULL** — audio included. Realistically a USB / Drive path; audiobooks are 0.5–5 GB and messaging
  apps cap far below that.

**Container.** A `.voyagepack` is a **plain zip**, stored (not deflated) for audio entries — they
are already compressed, and stored entries let the reader stream without inflating. `java.util.zip`
is already used in the project (`EpubParser`, `VoskModelManager`, `LogEngine`). Layout mirrors §7.1:

```
manifest.json      payload mode, target fingerprints, file list with sizes + fileKeys
pack.json
media/...
audio/...          FULL only
```

`manifest.json` is separate from `pack.json` so the importer can read placement and fingerprints
without inflating the whole archive. **Free-space precheck is 2× the payload**, not 1× — extract to
a temp dir, then move — unless the mover can rename across the same filesystem, which it can when
the temp dir is inside the library root rather than `filesDir`. Put it there for FULL packs.

All disk documents in this project are hand-rolled `org.json`, not kotlinx-serialization
(`BookDataCodec`, `LibraryDataCodec`, `WidgetsDataCodec`, `SettingsDataCodec`), with
`JsonUnknownFields` for forward compatibility. `pack.json` follows suit — which settles §13's
"hand-writable format" question before it is asked, since a hand-rolled `org.json` codec *is* a
documented format a C# writer can target. Note `JsonUnknownFields` is per-`JSONObject`: every nested
pack object (fact, entity, board, element) must opt in individually.

**Sanitize on export.** A book's `data/book.json` carries progress, bookmarks, sessions and skip
events. Handing that over means the recipient's companion is fully unlocked before they press play.
Export strips, by default:

- progress (`positionMs`, `isCompleted`, `lastPlayedMs`)
- `revealedMs` / `prevRevealedMs`  ← **the spoiler leak**
- listening sessions, skip events
- `folderPath` — an absolute path, and for AUTO clusters a synthetic `dir::stem` key that means
  nothing on another device. Rewritten from the semantic manifest on import.

Bookmarks are opt-in via a checkbox rather than stripped silently — an annotated set of bookmarks is
arguably part of the gift.

Because export sanitizes unconditionally, **the incoming file contains no progress at all**, and the
import prompt is *not* "keep mine or take theirs" — there is no theirs. It is only ever asked of a
recipient who already owns the book, and it is asked about **their own** data:

> *You're 6 h into this book. Keep your progress, or start fresh?*

Keep is the default and does nothing. Start fresh calls the new `resetProgress(bookId)` from §6.4,
zeroing `positionMs` **and** `revealedMs` together.

The existing [`ApplyMode`](app/src/main/java/com/betteraudio/data/diskstore/RestoreOps.kt:21) —
note it is a **top-level** enum, `com.betteraudio.data.diskstore.ApplyMode`, not nested in
`RestoreOps` — is deliberately **not** used for this. `MERGE` means "the doc wins when its
`lastPlayedMs` is strictly newer", which is not "keep mine", and its collection handling is
"additive + deduped, *independent of mode*", so it would splice the sender's bookmarks, sessions and
skip events into the recipient's book no matter which mode was chosen. Pack import writes progress
directly rather than routing through restore semantics built for a different problem.

### 10.2 The manifest is semantic, not literal

Never record *"put this at `/Books/Sanderson/Mistborn/Book 1`"*. Record
`{title, author, series, seriesOrder, files:[{name, trackNumber, durationMs, fileKey}]}` and let the
**recipient's** app compute the destination from **their** `ImportStructure`.

A near-miss of that computation exists —
[`LibraryRestructurer.targetFolder(book, structure, root)`](app/src/main/java/com/betteraudio/data/files/LibraryRestructurer.kt:232)
— but it cannot be reused as-is, and budgeting it as "make it public" is wrong:

- It **returns `null` for `ImportStructure.AUTO`** ([:254](app/src/main/java/com/betteraudio/data/files/LibraryRestructurer.kt:254)),
  and AUTO is both a first-class structure and the parse fallback for anything blank or unknown. A
  recipient on AUTO has no computed destination at all. Import needs an explicit AUTO rule —
  `<root>/<sanitized manifest title>/` — which restructure deliberately does not have (it refuses to
  reshape an AUTO library).
- It takes a **persisted `Book` row**, resolves the series via `seriesRepository.getSeriesOnce(book.seriesId)`,
  and derives the leaf from `File(book.folderPath).name`. At import time none of those exist; author,
  series and leaf all come from the manifest.

So: extract a pure `LibraryPaths.targetFolder(title, author, series, structure, root)` that takes
**values, not rows**, make `LibraryRestructurer` call it with values read off the `Book`, and have
import call it with values read off the manifest. That is the only way the two cannot drift.

`LibraryRestructurer.verify(from, to)` ([:215](app/src/main/java/com/betteraudio/data/files/LibraryRestructurer.kt:215))
diffs **two directories** and likewise does not transfer — at import the source is an archive plus a
manifest. Import needs its own `verify(manifestFiles, extractedDir)` comparing name + size against
the manifest's declared entries. And `moveOne` refuses to merge into an existing folder
([:166](app/src/main/java/com/betteraudio/data/files/LibraryRestructurer.kt:166)); import must
decide what a pre-existing target path means — treat it as a match candidate, never overwrite.

### 10.3 Import flow

1. **Receive.** The file arrives as a `content://` URI. This is *not* a new intrusion into the
   all-`java.io.File` model — `BackupManager.kt:114` already streams from a picked document, and
   `SettingsViewModel.kt:718` already holds a persisted SAF grant. Copy that recipe rather than
   inventing one. Two entry points: the app's own picker (reliable, mirrors
   `ui/settings/BackupSection.kt:115`) and the share sheet. For the share sheet, an extension-only
   `.voyagepack` filter will **miss most real deliveries** — Gmail/Drive/Telegram hand over an
   opaque path with `application/octet-stream` — so filter broadly on `application/octet-stream`
   plus `application/zip` and sniff the archive for `manifest.json` after ingest, rejecting quietly
   if absent.
2. **Match.** Fingerprint the pack's members against the library. **If the book is already there,
   never copy audio** — attach the pack and stop. This is the common case for a friend reading the
   same series. Needs an extension to
   [BackupMatcher](app/src/main/java/com/betteraudio/data/backup/BackupMatcher.kt) — its current
   `BookIdentity` is `folderPath` / `relPath` / `title` / `author`, with no durations or file keys.
   Computing `fileKey` for candidates is 2 seeks + 128 KB per file, so **cache it** — add a
   `fileKey` column to `AudioFile`, populated lazily, exactly as `damageRangesJson` caches a derived
   value there today. Without the cache, matching against a large library re-reads every audio file
   on disk on every import.
3. **Place** (FULL only). Compute the target via §10.2, extract to a temp dir **inside the library
   root** (so the commit is a rename, not a second copy), **verify** name + size against the
   manifest, then move into place. Copy → verify → commit, so an interrupted import never
   half-lands. Precheck 2× free space.

   Run it as a **WorkManager job**, not a foreground service. The manifest declares only
   `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and the only service is
   `PlaybackService` with `foregroundServiceType="mediaPlayback"`; a `dataSync` service on
   `targetSdk = 36` would need a new permission, a new type, and is subject to the platform's
   `dataSync` time cap and background-start rules. `AutoBackupWorker` is the existing precedent for
   exactly this shape of work, including the Hilt-managed WorkManager init already wired in the
   manifest.
4. **Register without a full rescan.** `AudioFileScanner` only exposes
   [`scanDirectory(rootPath)`](app/src/main/java/com/betteraudio/data/scanner/AudioFileScanner.kt:69)
   — a full walk — and
   [`importBook`](app/src/main/java/com/betteraudio/data/scanner/AudioFileScanner.kt:389) is
   private. Add a public `importSingleFolder(dir)`.

   It runs inside `DiskMirror.suppressed { }`, whose two existing call sites (the scanner's serial
   loop, `BackupManager`'s single restore) never overlap — so adding a third that *can* overlap a
   library scan required auditing it first. **Done; the audit corrected this plan.** Two findings:

   - The claim that "`suppressDepth` is a shared `AtomicInteger` — whichever block exits first
     un-suppresses the other" was **wrong**. Increment-on-enter/decrement-on-exit is a correct
     nesting counter: suppression lifts only when the last block exits. That property is now
     explicit in `SuppressionGate` and pinned by `SuppressionGateTest`, since a later
     "simplification" to a Boolean flag *would* introduce exactly the described bug.
   - The real defect was elsewhere: **`flushDirty()` ignored suppression entirely**, unlike
     `flushBook`/`flushLibrary` which both check it. Because `dirtyBooks` is shared by every
     caller, any unrelated trigger — a playback pause, or a concurrent bulk apply's own epilogue
     flush — would drain a still-running block's *partially applied* book and mirror that
     half-written state to disk. Disk being the source of truth, a process death in that window
     leaves the half-applied doc as the surviving copy. Fixed: `flushDirty` now defers while any
     block is active, and the outermost `suppressed` exit drains once (`NonCancellable`, so a
     cancelled scan still leaves disk consistent with the Room writes it already committed).
5. **Ask about progress** only if step 2 matched an existing book (§10.1).
6. **Attach the pack** and enable it. Set `revealedMs` to **the recipient's current position in that
   book**, not 0 — the modal case is a friend sending a pack for a book you are already six hours
   into, and §6.2 guarantees a 0 cursor would never catch up on its own. For a book with no
   progress this is 0 anyway, so it is one rule, not two. Offer "start me from zero instead" as a
   one-tap alternative on the attach confirmation.

---

## 11. Integration points

| Area | File | Change |
|---|---|---|
| DB | `data/db/AppDatabase.kt` | v23: `companion_packs`, two `playback_progress` columns; register in `di/AppModule.kt` too |
| Storage | `data/diskstore/CompanionDataStore.kt` | **new** — read/write pack + edits |
| Storage | `data/diskstore/VoyageLayout.kt`, `BookDataPaths.kt` | companion dir derivation |
| Storage | `data/diskstore/DiskMirror.kt` | flush hook for pack writes |
| Reveal | `playback/PlaybackService.kt` | reveal cursor rides `positionSaverJob` — survives the UI dying |
| Reveal | `playback/JumpClassifier.kt` | consumed, not changed — classifies seek vs. continuous |
| Reveal | `data/db/dao/PlaybackProgressDao.kt` | new `resetProgress(bookId)` zeroing position + reveal |
| Anchors | `sync/`, `SyncAnchor` | text-anchor resolution (fallback #4) |
| Canvas | `widget/model/WidgetDesignDoc.kt` | pattern to copy, plus the color-role divergence |
| Editor | `ui/widget/WidgetEditorViewModel.kt` | drag / resize / rotate interactions to retarget |
| Import | `data/scanner/AudioFileScanner.kt` | new public `importSingleFolder(dir)` |
| Import | `data/files/LibraryPaths.kt` | **new** — pure `targetFolder(values…)`, incl. an AUTO rule; `LibraryRestructurer` refactored onto it |
| Import | `data/backup/BackupMatcher.kt` | fingerprint matching (sizes + file keys) |
| Import | `data/db/entities/AudioFile.kt` | new cached `fileKey` column (precedent: `damageRangesJson`) |
| Import | `data/diskstore/DiskMirror.kt` | `flushDirty` defers under suppression; outermost `suppressed` exit drains (`SuppressionGate`) |
| Import | `data/backup/AutoBackupWorker.kt` | precedent to copy for the WorkManager import job |
| UI | `ui/player/PlayerSheet` | inline cast strip, companion affordance + badge, `hideMiniBar` for fullscreen boards |

---

## 12. Build phases

Each phase ends at something demonstrable on the phone.

**The complete schema is frozen at P0** — facts, entities, *boards, elements and the §9.2 op
vocabulary* — even though boards land at P5 and ops at P6. Sharing ships at P4, and once packs are
in the wild there is no recall mechanism (§2: no server, no accounts). `schemaVersion` is a
detector, not a mitigation: it tells a later build that a pack is old, it cannot tell it what the
missing board data should have been. P4 ships packs that populate only the parts that exist; it must
not ship packs whose shape later has to change.

**P0 — Foundation.** Pack doc model + `org.json` codec (UUID ids throughout, `JsonUnknownFields` on
every nested object), `CompanionDataStore`, path derivation, DB v23 migration. No UI. *Done when* a
hand-written `pack.json` dropped into a book folder is parsed and logged at the right positions.

**P1 — Reveal engine.** `revealedMs` column, cursor on `PlaybackService.positionSaverJob` advancing
on `JumpClassifier`-continuous progress only, `resetProgress`, "set reveal point" confirm + in-memory
undo, the post-jump offer, series Reveal progress panel. *Done when* a 4-minute scrub provably does
not move the cursor, the button does in both directions, and the cursor keeps advancing with the app
swiped away.

**P2 — Consumption UI.** Cast strip (INLINE), character sheet (SHEET), importance badge, receiver
threshold setting, "since you last looked" digest. *Done when* a hand-authored pack is genuinely
usable for a full listen.

**P3 — Authoring.** Quick capture during playback, timeline editor, entity / fact CRUD. *Done when* a
pack can be built end-to-end on the phone with no hand-edited JSON.

**P4 — Sharing, DATA_ONLY.** Export with sanitization, `.voyagepack` intent filter, `content://`
ingest, fingerprint match, attach-to-existing-book. *Done when* a pack survives a round trip to a
second device and reveals from zero.

**P5 — Map board.** Canvas editor retarget, pins bound to entities, FULLSCREEN overlay (§8),
`artTone` chrome. Keep the painter a pure `(doc, revealedMs, sizePx) -> Bitmap` the way
`WidgetPainter.paint` already is, so the golden-image test in §15 is available for free. The
showpiece, and roughly half the total UI work — deliberately after the model is proven.

**P6 — Edits overlay.** `edits.json`, op application, revision update + conflict review, "share my
edits only", fork.

**P7 — FULL packs.** Audio payload, semantic placement via the shared `targetFolder`, verified
extraction in a foreground service, `importSingleFolder`, progress-reset prompt.

---

## 13. Later

- **PC authoring in BetterAudioAlign.** Real mouse, real keyboard, real map images. The phone is the
  consumption surface; nobody builds a 40-character pack on it. No format decision is needed —
  §10.1 settles it: every disk document here is hand-rolled `org.json`, which a C# writer can target
  directly.
- **Gemini auto-seed.** For books with linked EPUB text, extract characters and their first-mention
  positions and generate a draft pack with sheets pre-anchored to first appearance. Turns three
  hours of authoring into twenty minutes of curation, and is the difference between a feature two
  people use and one that ships with content.
- **Multi-pack layering** with precedence ordering.
- **Relationship graph board** — edges are facts too (`A -ally-> B` from t1, `-enemy->` from t2).
  Watching alliances flip as you listen is arguably the emotional payoff of the whole system.

---

## 14. Risks

| Risk | Mitigation |
|---|---|
| Authoring effort kills adoption | Quick capture (P3), PC editor and Gemini seeding (§13) |
| Anchors drift on a different rip | Five-level fallback (§5) plus an honest badge. **Bias late:** when resolution is coarse (#5, ±minutes) round the reveal *backwards*, never forwards — for a spoiler feature the error must fall on the safe side |
| Anchor #4 is weaker than it looks | It needs a linked EPUB, a completed alignment, *and* a quote→locator search that does not exist (`data/sync/` has `TextSimilarity`, a scorer, but no text index). Treat #4 as a stretch goal; the realistic chain for a mismatched rip is #2 → #3 → #5 |
| Spoiler leak via shipped progress | Sanitize on export (§10.1); `revealedMs` explicitly on the strip list |
| Multi-GB extraction on a phone | 2× free-space precheck, WorkManager job, temp dir inside the library root, copy → verify → commit |
| Pack schema churn after packs are in the wild | `schemaVersion` from day one; `JsonUnknownFields` tolerance as already used by the disk stores |
| Local ids leaking into pack files | UUIDs at authoring time (§4) — enforce in the codec, not by convention |

---

## 15. Test checklist

Run before any release that ships part of this.

1. Hand-written `pack.json` parses; a malformed pack fails soft (companion absent, no crash).
2. Facts appear exactly at their anchor position during continuous playback.
3. Scrub forward **4 min** (deliberately under the 5-minute skip-event threshold) → nothing new
   revealed. Scrub forward 30 min → nothing new revealed. Scrub back → nothing lost.
4. Skip-button spam forward → nothing new revealed. Lock-screen and Android Auto scrub → same.
5. Play with skip-silence on through a long silent gap → the cursor keeps advancing (silence-skip is
   not misread as a seek).
6. Swipe the app away and keep listening for 10 min → the cursor advanced when the app is reopened.
7. "Set reveal point" forward: confirm dialog states the reveal count; undo restores.
8. "Set reveal point" backward: confirm states the hide count; previously-seen items disappear.
9. Jump forward 2 h → the post-jump offer appears once, is dismissible, and moves the cursor only
   when confirmed.
10. Series pack: book 5 finished, book 2 restarted from 0 **and the reveal reset confirmed** →
    book 2's facts hidden again, book 5's still visible. Without the confirm, book 2's stay visible.
11. Series pack with a member missing from the library → dormant, count shown, no crash.
12. Importance: QUIET shows no badge; MAJOR badge persists until the companion is opened.
13. Receiver threshold set to "major only" → NOTABLE facts produce no badge.
14. "Since you last looked" lists exactly the facts revealed since the last open.
15. **Golden image:** the same board rendered at two different `revealedMs` values produces two
    visibly different bitmaps, and each is byte-stable across runs. Cheap only if the painter stays
    pure (§12 P5) — and it is the only test that proves the feature's actual point, that the map at
    3:00:00 differs from the map at 9:00:00.
16. Export → sanitized: the recipient's progress is 0 and `revealedMs` is 0.
17. Import onto a device that already owns the book → no audio copied, pack attached, and
    `revealedMs` set to **that device's current position**, not 0.
18. Import FULL onto a device without the book → lands at the path the *recipient's*
    `ImportStructure` dictates, not the sender's. Repeat with the recipient on **AUTO**.
19. Import FULL cancelled mid-extract → no partial book in the library, no orphaned temp dir.
20. Import with insufficient free space (2× payload) → refused up front with a clear message.
21. Import while a library scan is running → neither the scan's nor the import's disk writes are
    silently dropped, and no book's `data/book.json` is left half-applied (the `flushDirty`
    suppression fix in §10.3 step 4). Kill the app mid-import and reopen: every book's doc is
    either its pre-import state or its complete post-import state, never a partial merge.
22. Import a `.voyagepack` delivered through Gmail/Drive/Telegram (opaque `content://`,
    `application/octet-stream`) → recognised. A non-pack zip → rejected quietly.
23. The imported book appears in the library without a manual rescan.
24. Two AUTO-cluster books in one folder, each with a pack → no `data/` filename collision.
25. Edited received pack + the author's revision bump → local edits survive; conflict review lists
    only genuine conflicts. Includes a **board** edit (moved pin) surviving the update.
26. Theme switch (light / dark, and each colour source) → board colours using roles follow; raw
    colours do not.
27. Uninstall + reinstall + point at the same library folder → packs and reveal cursors come back.
28. Move a book with `LibraryRestructurer` → its pack is still attached afterwards (the `relPath`
    keying in §7.2).
