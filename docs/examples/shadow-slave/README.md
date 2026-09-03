# Shadow Slave — companion pack

A companion pack for *Shadow Slave* by guiltythree, built from the real files in
`H:\f\novels\guiltythree\Shadow Slave` — the 8-volume audiobook (256h30m), the epub, and the
`mapping.json` produced by an actual on-device alignment run.

## Files

| File | What it is |
|---|---|
| `Shadow Slave.voyagepack` | The importable archive. Put this on the phone. |
| `pack.json` | The pack itself — 38 entities, 269 facts. |
| `manifest.json` | Placement/fingerprint header the importer reads first. |

## Installing

```bash
adb push "docs/examples/shadow-slave/Shadow Slave.voyagepack" /sdcard/Download/
```

**Settings → Library → Import companion pack…**, pick it from Downloads. Then open the book and
use the player's overflow menu → **Companion**.

It targets `Shadow Slave` / `guiltythree`, taken from your own `data/book.json`, so it matches your
library exactly. The manifest also carries the real `fileKey` fingerprints of all eight `.m4a`
files (`sha1(size ‖ first 64 KB ‖ last 64 KB)`), so it identifies your exact rip.

## The cast is cut by measurement, not taste

Whole-book mention counts, from the parsed epub:

| | | | |
|---|---|---|---|
| Sunny 35,494 | Nephis 6,853 | Cassie 4,485 | Rain 2,714 |
| Effie 2,685 | Mordret 2,464 | Jet 2,332 | Kai 2,326 |
| Morgan 1,536 | | | |

…and then a long drop to names that carry one arc and leave. Those nine are in; nobody else is.
Two names that *look* like main characters are deliberately not characters: **Valor** and **Song**
are great Legacy clans, so they are `FACTION` entities, and **Sin** turned out to be `Sin of
Solace`, a Transcendent-rank weapon.

## The character sheets are the book's own runes

This is the part worth looking at. Shadow Slave prints its characters' stats in-story — Sunny
summons "the runes" and reads a status block:

```
Name: Sunless.
True Name: Lost from Light.
Rank: Ascended.
Class: Tyrant.
Shadow Cores: [5/7].
Shadow Fragments: [1448/5000].
Memories: [Silver Bell], [Puppeteer's Shroud], …
```

The generator finds **all 26 of those blocks** in the epub, with their exact paragraph positions,
and turns each into facts anchored at the millisecond that paragraph is narrated. So the sheet does
not describe Sunny — it *is* his sheet, at wherever you have listened to, and it rewrites itself as
you go:

| | 0h17m | 52h59m | 115h05m | 231h28m | 256h29m |
|---|---|---|---|---|---|
| Rank | Aspirant | Awakened | Ascended | Transcendent | **Supreme** |
| Class | — | Monster | Devil | Terror | **Titan** |
| Cores | Dormant | 2/7 | 4/7 | 6/7 | **7/7** |

Aspect goes `Temple Slave` → `Shadow Slave — Divine rank` at 2h40m. Abilities go `Shadow Control` →
`+ Shadow Step` → `+ Shadow Manifestation`. The Flaw (`Clear Conscience — you cannot lie`) arrives
at 3h03m, in the chapter it is revealed. Nephis carries her own sheet: `Changing Star`,
`Light Bringer — Divine rank`, `Pristine Soul — you must suffer to use your power`, ending at
Titan / 7 soul cores. Rain has a third.

**The arsenal fills up one Memory at a time.** Nineteen of Sunny's Memories are placed at the
chapter the Spell first appraises them, so `Memories` grows from `Silver Bell` at 4h18m to 32 items
by 156h. Nine of them are also `ITEM` entities of their own, carrying the Spell's real Rank / Type /
Tier — `Midnight Shard` (Awakened / Weapon / III), `Weaver's Mask` (Divine / Tool / VII),
`Sin of Solace` (Transcendent / Weapon / V), `Crown of Twilight` (Supreme / Tool / I).

**Bonds are their own fields, and they revise.** Sunny → Nephis reads "a stranger he is quietly
measuring for a fight he would lose" at 4h16m and "bound to each other by more than choice at this
point" at 183h24m. Nineteen bonds across the cast, both directions where it earns it — and in the
Immersive sheet each one is tappable, so the Bonds section doubles as the relationship graph.

## How the fields are named

Fields carry a `group:Label` prefix that tells the character sheet which section they belong to —
see `EntitySheetModel`. `identity:Rank` is a badge in the stat line, `power:Flaw` is a row,
`arsenal:Memories` is chips, `bond:Nephis` is a tappable relationship. Bare fields (`description`,
`introduced`, `location`, `volume`) keep their old meaning, and anything unrecognised lands in
**Details** rather than being dropped.

## The positions are measured, not guessed

1. The epub is parsed with a **byte-faithful port of the app's own `ParagraphExtractor`** — same
   block tags, same entity decoding, same normalization, same `charStart` arithmetic.
2. That port was validated against your `mapping.json`: **all 13,628 anchors resolve to the same
   `paragraphIndex`**, 100%, so the text coordinates line up exactly with what the app produced.
3. Text positions become audio milliseconds by interpolating between those real anchors
   (0 ms error when re-evaluated at the anchor points themselves).

Facts carry **both** `globalMs` (exact, used) and `ratio` (a fallback for anyone with a different
rip — `AnchorResolver` prefers `globalMs`, so it never degrades for you).

The data cross-checks itself in a satisfying way: Effie's first mention lands in the chapter
*titled* "Effie", Weaver's in "Weaver's Eye", and Mordret's in "Prince of Nothing" — which is also
the title of Volume 3.

## Accuracy, stated plainly

**Ranks, classes, cores, aspects, flaws, attributes, memories, tiers and positions are the book's.
Descriptions and bonds are mine.** No prose from the book is copied into the pack — every
description is paraphrased and deliberately short.

Three things the generator does on purpose, each of which would otherwise read as a bug:

- **Unchanged repeats are collapsed.** The runes reprint the whole sheet every time they are
  summoned, so `True name` alone would land ~20 identical facts on Sunny. A field only carries a
  fact where it actually *changed* — otherwise the reveal digest would fire a badge for a sheet
  that reads the same as it did an hour ago.
- **The book's own elisions are dropped.** Chapter 1236 prints `Memories: [Silver Bell],
  [Puppeteer's Shroud], [Midnight Shard]...` — the real list is 30-odd. Taken at face value the
  arsenal would appear to *shrink* as you listen, so a list value ending in an ellipsis does not
  revise the field.
- **The arsenal is filtered against the chapter-1026 sheet.** Sunny reads the runes of plenty of
  Memories that are not his (Nephis's `Nameless Sun`, Morgan's `Blood Arrow`); a naive running list
  would hand him other people's weapons. The cost is that Memories he acquired and lost before
  ch 1026 are absent.

Rune blocks whose text survived the epub badly (a replacement character where an em-dash was, a
truncated value) are dropped rather than shown as garbage.

The map's **coordinates are invented** — there is no real map image here, so the six places are
laid out plausibly rather than accurately. Swap in real coordinates by editing the `location`
facts.

## Rebuilding

`pack.json` is generated, not hand-written, by a two-stage pipeline in this session's scratchpad:

| script | what it does |
|---|---|
| `ssparse.py` | the `ParagraphExtractor`/`EpubParser` port |
| `ss_extract.py` | pulls the 26 rune blocks, memory appraisals and aspect/flaw blocks out of the epub, each with its `(spine, charStart)` |
| `ssgen2.py` | anchors all of it to real audio ms and writes `pack.json` |

To hand-edit instead, change `pack.json` directly and rebuild the archive:

```bash
cd "docs/examples/shadow-slave" && "$JAVA_HOME/bin/jar.exe" --create --no-manifest --file "Shadow Slave.voyagepack" manifest.json pack.json
```

`manifest.json` must stay the **first** entry — `CompanionImportService.peekManifest` stops at the
first one it finds, which keeps the FULL-vs-DATA_ONLY branch cheap on a large archive.

Re-importing with a **higher `revision`** (currently 3) takes the §9.2 update path — base swap plus
conflict review — rather than attaching a second copy.
