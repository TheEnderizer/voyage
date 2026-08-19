# Voyage motion spec

Every animation in the app, as values you can edit. This is a **filled-in form**, not documentation:
change a number here, tell Claude "apply the spec", and it lands in the Kotlin.

Extracted from the code as of 2026-08-17. Each section names the file that owns the value, so a
change here has exactly one place to go.

Companion file: **`docs/motion-lab.html`** — open it in a browser (no build, no install) to play with
the mini→full player morph live. Its defaults are the values below.

---

## 0. How to read / edit this

Three kinds of timing appear here. They are not interchangeable:

- **spring** — `dampingRatio` / `stiffness`. Interruptible, no fixed duration. Used for every state
  change in the app.
- **driven** — no timing at all. A value mapped from a progress float (a drag, a gesture). The spec
  is the *mapping*: `progress 0→1 ⇒ property a→b`, optionally active over only part of the range.
- **timed** — `duration` + easing curve. Voyage currently uses **none** of these; every animation is
  a spring or driven. Adding one is fine, just say so explicitly.

Spring feel, if you'd rather not think in numbers:

| feel | dampingRatio | | feel | stiffness |
|---|---|---|---|---|
| no bounce, settles flat | 1.0 | | leisurely | 200 |
| barely perceptible overshoot | 0.85 | | normal | 380 |
| visible overshoot | 0.65 | | snappy | 800 |
| springy, 2+ oscillations | 0.5 | | instant | 1600+ |

---

## 1. Motion tokens

The shared vocabulary. Changing a token changes everything that reads it — that's the point, but
check the blast radius before editing here rather than at a call site.

### Material You — `ui/material/motion/VoyageMotion.kt` (`VoyageMotionTokens`)

| token | damping | stiffness | used for |
|---|---|---|---|
| `spatialDefault` | 0.8 | 380 | position/size/shape — the default for anything that moves |
| `spatialFast` | 0.6 | 800 | small, immediate movement (pill slot scale) |
| `spatialSlow` | 0.8 | 200 | large, deliberate movement |
| `effectsDefault` | 1.0 (NoBouncy) | 1600 | alpha/color — never overshoots |
| `effectsFast` | 1.0 | 3800 | near-instant fades |
| `effectsSlow` | 1.0 | 800 | slow fades |
| `press` | 0.6 | 900 | press/release feedback |

### Immersive + shared — `ui/theme/Motion.kt` (`MotionTokens`)

| token | damping | stiffness | used for |
|---|---|---|---|
| `spatialDamping/Stiffness` → `floatSpatial` | 0.82 | 380 | Immersive spatial + **the player sheet in both themes** |
| `effectsDamping/Stiffness` → `floatEffects` | 1.0 (NoBouncy) | 1600 | fades |
| `pressDamping/Stiffness` → `floatPress` | 0.6 | 900 | press feedback |

> The two spatial defaults differ by 0.02 damping (0.80 vs 0.82) for historical reasons. Visually
> indistinguishable; unify them if you ever want one number.

---

## 2. Mini player → full player morph

**The big one.** Owned by `ui/player/PlayerSheet.kt`; the per-element modifiers live in
`ui/player/PlayerMorph.kt`, and Material You's growing background in
`ui/material/motion/ContainerMorph.kt`.

Everything below is **driven** by a single float `dragProgress` (0 = mini bar, 1 = full player).
There is no timeline: a drag writes that float directly, and a settle/tap springs it to 0 or 1.
This is why the whole transition is scrubbable and interruptible at any point.

### 2a. The driver

```
Type:      driven (finger) → spring (settle)
Source:    dragProgress 0→1
Geometry:  travel = windowHeight − 64dp(mini) − (navInset + 20dp)
           landscape: max(travel, windowWidth × 0.55)   ← keeps drag sensitivity physical
Settle spring:   damping 0.82, stiffness 380   (MotionTokens.floatSpatial)
Tap-to-expand:   same spring, animateTo(1f)
Back / collapse: same spring, animateTo(0f)
Predictive back: snapTo (tracks the finger exactly, no spring) until commit/cancel
```

**Settle decision** (`PlayerSheet.settle`):

| condition | result |
|---|---|
| velocity < −1000 px/s (upward fling) | expand |
| velocity ≤ 1000 and progress > 0.5 | expand |
| otherwise | collapse |
| velocity > 1800 **and** progress < 0.15 **and** gesture started settled **and** gesture peak < 0.15 | **close the book** (stop playback, dismiss bar) |

### 2b. Geometry constants

| what | value | file |
|---|---|---|
| mini bar height | 64dp | `PlayerSheet.MINI_HEIGHT_DP` |
| mini bar corner radius | 32dp (pill at 64dp tall) | `PlayerSheet.MINI_BAR_RADIUS` |
| mini bar bottom offset | navInset + 20dp | `PlayerSheet` |
| mini cover corner radius | 12dp | `PlayerSheet.effectiveCoverRadius` |
| cover source when no mini bar | tapped grid card's rect + radius | `CoverBoundsRegistry` |

### 2c. Per-element channels

Each row is one element and the modifier it uses. **All share the same progress, the same linear
curve, the same straight-line path and the same uniform scale** — the recipe is hard-coded into
`morphFrom` / `expandReveal`, so today there is no per-element control at all.

`docs/motion-lab.html` exposes what per-element control *would* look like (see §2d). Anything you
change there beyond `from`/`to` needs the generalisation below before it can ship.

| element | modifier | channel | mapping |
|---|---|---|---|
| **background** (Material You) | `morphingContainer` | shape | mini bar rect+32dp radius → full screen, radius 0 |
| | | color | `surfaceContainerHigh` → `background`, linear in progress |
| **mini bar** (Material You) | — | alpha | stays at 1.0 (physically occluded by the container above it) |
| **mini bar** (Immersive) | — | alpha | `1 − progress × 2.5`, clamped → fully gone by progress 0.4 |
| **cover** | `morphFrom` | scale | uniform, `lerp(srcH/ownH, 1, p)` |
| | | translate | centers matched, `(srcCenter − ownCenter) × (1 − p)` |
| | | alpha | `p / 0.5` clamped → fully opaque at progress 0.5 |
| | | radius | 12dp → dest, **divided by current scale** so on-screen radius is exact |
| **title** | `morphFrom` | scale + translate | from mini title rect, same maths, no fade |
| **transport controls** | `morphFrom` | scale + translate | from mini controls rect |
| **everything else** (top bar, author, progress bar, times, bottom row) | `expandReveal` | alpha | `(p − 0.35) / 0.65` clamped → invisible until 35%, full at 100% |
| | | scale | `0.8 + 0.2p` |
| | | translateY | `14dp × (1 − p)` (`EXPAND_REVEAL_OFFSET`) |
| **floating nav pill** | `graphicsLayer` | translateY | `p × (64 + 12 + 48)dp` — slides out in lockstep |

**Anchor:** the cover is the continuous element. It never fades out and back in — it travels. The
`fadeIn` on it is only because the mini bar keeps drawing its own cover on top during the drag, so
the traveling copy ramps in underneath rather than hard-cutting.

**Must not:** nothing here may drive layout. Every channel is `graphicsLayer` (draw-phase) or a
`drawBehind` path, so no frame triggers remeasure. `containerReveal` deliberately clips instead of
scaling for the same reason — a scaled page would re-wrap its text every frame.

### 2d. Per-element control (proposed — not in the code yet)

`morphFrom` and `expandReveal` would collapse into one data-driven modifier. The lab already speaks
this vocabulary, and its export emits exactly this:

```kotlin
data class ElementMotion(
    val from: Float = 0f,            // slice of sheet progress this element is active over
    val to: Float = 1f,
    val easing: Easing = Easing.Linear,
    val path: MorphPath = MorphPath.Linear,   // Linear | ArcVerticalFirst | ArcHorizontalFirst | Bulge(f)
    val scale: ScaleMode = ScaleMode.UniformByHeight,  // …ByWidth | Independent | None
    val scaleEasing: Easing? = null, // null = follow `easing`; separate = settle-into-place feel
    val anchor: Anchor = Anchor.Center,        // Center | TopLeft
    val rotation: ClosedFloatingPointRange<Float>? = null,
    val alpha: ClosedFloatingPointRange<Float>? = null, // window in RAW progress
    val radius: ClosedRange<Dp>? = null,
)

// One map, read by Modifier.elementMotion(key, source, progress)
val PlayerMorphChoreography: Map<String, ElementMotion> = mapOf(/* … */)
```

Current behaviour expressed in it, for reference:

| element | equivalent |
|---|---|
| cover | `ElementMotion(alpha = 0f..0.5f, radius = 12.dp..24.dp)` |
| title / transport | `ElementMotion()` |
| reveal elements | `ElementMotion(alpha = 0.35f..1f)` with source = own rect + 14dp down, scaled 0.8 |

That last row is the useful simplification: `expandReveal` isn't a second mechanism, it's an ordinary
morph whose source rect is synthesised from the destination. One model covers both.

---

## 3. Book Info / Series page (grid card → page)

`ui/components/InfoPageScaffold.kt` + `ui/player/PlayerMorph.kt`.

```
Type:   spring (driven during a predictive-back gesture)
Open spring:   Material You  0.8 / 380  (spatialDefault)
               Immersive     0.85 / 380
Close spring:  Material You  0.8 / 380
               Immersive     0.9 / 400
```

| element | modifier | mapping |
|---|---|---|
| whole page | `containerReveal` | clip window per-edge lerps card rect → full screen; radius card → 0dp |
| cover | `morphFrom` (Immersive) / `coverCropMorph` (Material You) | card rect → cover slot; Material You drives real layout size so the crop re-resolves (0.72 card → square) with no aspect pop |
| page contents | `expandReveal` | same 0.35 / 0.8 / 14dp as above |

At progress 0 the window is *exactly* the card's rect and radius, with the same decoded bitmap —
so frame one is pixel-identical to the grid.

---

## 4. Nav pill

`ui/{material,immersive}/components/FloatingNavPill.kt`.

| element | type | mapping |
|---|---|---|
| selection indicator x/width | spring | Material `spatialDefault` 0.8/380 · Immersive 0.8/380 (hand-written) |
| selected slot icon scale | spring | 1.0 → 1.12 · Material `spatialFast` 0.6/800 · Immersive default spring |
| view-cycle slot appear/disappear | `expandHorizontally + fadeIn` / `shrinkHorizontally + fadeOut` | default specs |
| whole pill hide | driven | `translationY = expandProgress × (64 + 12 + 48)dp` |

Indicator is drawn in `drawBehind` (Material) rather than laid out, so animating it never remeasures.

---

## 5. Screen transitions (NavHost)

### Material You — `ui/material/MaterialMotion.kt`

| transition | alpha | scale | spring |
|---|---|---|---|
| enter | fadeIn | scaleIn from 0.92 | fade: effects 1.0/1600 · scale: spatial 0.82/380 |
| exit | fadeOut | scaleOut to 0.96 | same |
| popEnter | fadeIn | scaleIn from 0.94 | same |
| popExit | fadeOut | scaleOut to 0.90 | same |

### Immersive — `ui/immersive/ImmersiveMotion.kt`

Same as above plus a vertical slide of **4% of the screen height** on enter and popExit, and
different scale values: enter 0.92, exit 0.96, popEnter 0.96, popExit 0.92.

---

## 6. Press feedback

`ui/theme/Motion.kt` — `Modifier.pressScale`.

```
Type:    spring
Moves:   scale 1.0 → 0.96 on press, springs back on release
Spring:  damping 0.6, stiffness 900   (pronounced overshoot on release)
```

---

## 7. Not yet extracted

Honest gaps — say the word and I'll fill these in the same format:

- Home grid item enter/exit and the multi-select selection bar
- Sheet (bottom sheet) enter/exit specs across `BookOptionsSheet` / `ChapterSheet` / `AudioSettingsSheet`
- Settings section `AnimatedContent` transition
- Widget editor canvas pan/zoom and selection overlay
- `FrostedOverlay` / `ScrimControls` fades

---

## 8. Player morph v2 — IMPLEMENTED 2026-08-19

Four edits, tuned in `motion-lab.html`. Scope: **Material You portrait only** — Immersive and both
landscape layouts are untouched and still use `morphFrom`/`expandReveal`.

Lives in `ui/player/ElementMotion.kt` (`PlayerChoreography` holds the values below). The blockers
listed at the end of this section were all resolved during the port; they are kept as a record of
what the change required.

### Driver

```
damping 0.82 → 0.60      (stiffness 380 unchanged)
⇒ ~9.4% overshoot, ~583 ms settle
bounce mode: GROUP       gain 1.0
```

All bounce comes from this one spring — no per-element springs. Progress overshoots past 1.0, and
that overshoot is applied as **one rigid transform over the four elements that morph out of the mini
player** (cover, title, author, play button): a single uniform scale about their shared resting
centre, measured at **(180, 340)** in a 360×760 window.

Measured at peak overshoot (progress 1.09), every group member scales by an identical **1.09** —
cover 240→262dp, title 280→305dp, play 72→78.5dp — and each pushes outward from the shared centre in
proportion to its offset from it. Elements outside the group (chapter pill, seek bar, times,
secondary row, spawned transport buttons) scale by exactly 1.0 and do not bounce.

The rejected alternative (`bounceMode = perElement`) let every element overshoot along its own travel
vector, so they scattered to different centres — cover drifting to x=192, play to x=168 — which read
as fifteen independent objects rather than one.

**None of this is visible until the clamps are relaxed** (see blockers below).

### Choreography

| progress | event |
|---|---|
| 0.00 | cover, title, author, play button start travelling |
| 0.27 | **next chapter** appears — play button has swept over it |
| 0.55 | **skip forward** appears — play button covers 70% of it |
| 0.55–0.90 | top bar → seek bar → times → secondary row, staggered, no shared crossfade |
| 0.80 / 0.84 | **skip back / prev chapter** slide out from under the resting play button |
| 0.88–1.00 | **chapter pill** drops out from behind the cover, clipped by the cover's bottom edge |
| 1.00 → 1.09 → 1.00 | overshoot and settle |

### Element changes

| element | before | after |
|---|---|---|
| play/pause | straight-line `morphFrom` | `path = ArcVerticalFirst(bias 0.55)` — rises first, then sweeps left along the row |
| skip fwd, next ch | `expandReveal` fade | `Spawn.UncoveredBy("play", coverage)` — 0.70 / 0.40 |
| skip back, prev ch | `expandReveal` fade | `Spawn.SlideOutFrom("play")`, slices 0.80→1 and 0.84→1 |
| chapter pill | `expandReveal` fade | `Spawn.DropFromBehind("cover")`, slice 0.88→1, decelerate |
| author | `expandReveal` fade | `morphFrom(miniAuthor)` — travels, like the title |
| top bar / seek bar / times / secondary | one shared fade at 0.35 | staggered slices, each with a narrow 0.07-wide alpha window |
| mini bar | title only | title + author line (new `miniAuthor` bounds published for the morph) |

### Blockers — none of this ships without these

1. **`morphFrom` and `expandReveal` clamp progress to 1.0** (`if (p >= 1f) { scale = 1f; … }`,
   `p.coerceIn(0f, 1f)`). With a bouncy driver they would hard-stop at the resting position and the
   overshoot would be invisible. They must extrapolate above 1 while still clamping below 0 — the
   closing spring undershoots, and negative progress would fling elements past the mini bar.
2. **Play/pause needs `Modifier.zIndex`** above the other four transport buttons. Composition order
   currently draws skip-forward and next-chapter *on top* of it, so nothing would be hidden.
3. **A group-overshoot transform.** The four group members need a shared modifier that applies
   `scale = 1 + (progress − 1) × gain` about the group's union centre, on top of each element's own
   morph. Cheap — it composes into the same `graphicsLayer` each element already has — but it needs
   the group's resting bounds, which are known at layout time.
4. **`ElementMotion` + `Modifier.elementMotion`** (see §2d) plus three new spawn rules:
   `UncoveredBy`, `SlideOutFrom`, `DropFromBehind` (the last needs a clip against another element's
   live bounds).
5. **Mini bar author line** — a real layout change in `MiniPlayerBar`, publishing an
   `onAuthorBounds` callback alongside the existing cover/title/controls ones, and a new
   `miniAuthor` field on `PlayerExpandTransition`.

### Measured constraint worth keeping

Next-chapter **can never be fully covered** by the play button — it is still scaling up when it
sweeps that far right. Ceiling is 47% at arc bias 0.55, 63% at 0.85. Its threshold is set to 40%.
