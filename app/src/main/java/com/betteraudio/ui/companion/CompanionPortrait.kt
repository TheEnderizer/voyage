package com.betteraudio.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import java.io.File
import kotlin.math.abs

/**
 * A character's face, everywhere one is drawn — the cast grid, a character header, a bond row, a
 * map pin. One composable so those four never drift apart, and so the fallback ladder is decided
 * in exactly one place.
 *
 * ### The ladder
 *
 * 1. **Pack art** — [PackEntity.media], a path *relative to the pack directory* (`media/sunny.webp`).
 *    Resolved against [packDir], which [CompanionViewModel] publishes alongside the entities.
 * 2. **Crest** — a generated monogram disc. Not a placeholder for a missing image: it is the
 *    rendering a pack with no art gets, and it is meant to look deliberate. Everything that made
 *    the old Immersive sheet's crest work is kept — the gradient tinted from the *surface* rather
 *    than from ink, and a per-entity accent that is a rotation of the cover's own primary.
 *
 * There is deliberately no third "seeded art" rung in the UI: a portrait pulled from a wiki during
 * pregeneration is written into `media/` and stored on the entity, so by the time it reaches here
 * it *is* rung 1. The ladder is about what exists on disk, not about where it came from.
 *
 * ### Why the accent is derived and not chosen
 *
 * Law 03 — colour is borrowed, never chosen. [entityAccent] rotates the cover-derived `primary` by
 * a stable hash of the name, so the same character keeps the same crest across sessions and across
 * books while every crest stays inside the cover's own family. The spread is narrow (±40°, pulled
 * back toward the primary) so a cast of nine reads as one palette rather than a paint chart.
 */
@Composable
fun CompanionPortrait(
    entity: PackEntity,
    size: Dp,
    packDir: String?,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    ringed: Boolean = deckPortraitRinged()
) {
    val shape = deckPortraitShape(size)
    val accent = entityAccent(entity.name)
    val alpha = if (dim) 0.45f else 1f
    // Tinted from the deck's own surface rather than from ink: a fixed near-black disc reads as a
    // hole punched in a light scheme and disappears entirely in a dark one.
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val art = remember(entity.media, packDir) {
        val rel = entity.media?.takeIf { it.isNotBlank() } ?: return@remember null
        val dir = packDir ?: return@remember null
        File(dir, rel).takeIf { it.isFile }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        lerp(base, accent, 0.42f).copy(alpha = alpha),
                        lerp(base, accent, 0.14f).copy(alpha = alpha)
                    )
                )
            )
            .then(
                if (ringed) Modifier.border(1.5.dp, accent.copy(alpha = 0.75f * alpha), shape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                // Crop: portrait slots are a fixed square and a portrait's subject is centred.
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(shape)
            )
        } else {
            Text(
                text = monogram(entity.name, entity.kind),
                // Scaled off the slot rather than picked from the type scale: this composable is
                // asked for anything from a 26dp map pin to an 88dp character header, and a fixed
                // titleMedium is illegible at one end and lost in whitespace at the other.
                fontSize = (size.value * 0.38f).sp,
                lineHeight = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
        }
    }
}

/** Overload for the common case where the caller holds a resolved state rather than a raw entity. */
@Composable
fun CompanionPortrait(
    state: com.betteraudio.companion.CompanionEntityState,
    size: Dp,
    packDir: String?,
    modifier: Modifier = Modifier,
    dim: Boolean = false
) = CompanionPortrait(state.entity, size, packDir, modifier, dim)

/**
 * A portrait for a name the deck knows only as text — a `bond:` target that is not (yet) a
 * revealed entity, so there is no [PackEntity] to hand over. Renders the crest half of the ladder
 * only, which is correct: an unrevealed character has no art to show, and inventing one would leak
 * that they exist.
 */
@Composable
fun CompanionNamePortrait(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    dim: Boolean = false
) = CompanionPortrait(
    entity = PackEntity(entityId = name, kind = EntityKind.CHARACTER, name = name, media = null),
    size = size,
    packDir = null,
    modifier = modifier,
    dim = dim
)

/**
 * Per-entity accent: a hue rotation of the cover-derived `primary`, stable for a given name.
 *
 * Kept identical to the rotation the old Immersive companion sheet used, so a pack a listener has
 * been reading keeps the same colour per character across this redesign.
 */
@Composable
fun entityAccent(name: String): Color {
    val primary = MaterialTheme.colorScheme.primary
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(primary.toArgb(), hsv)
    val spread = ((abs(name.hashCode()) % 81) - 40).toFloat()
    hsv[0] = ((hsv[0] + spread) % 360f + 360f) % 360f
    hsv[1] = (hsv[1] * 0.9f).coerceIn(0f, 1f)
    return lerp(Color(android.graphics.Color.HSVToColor(hsv)), primary, 0.25f)
}

/**
 * "The Forgotten Shore" → "FS"; "Sunny" → "S".
 *
 * Articles are dropped so half the places on a map do not all render as "T", and characters take
 * one letter while everything else takes two — a cast is scanned by first name, a gazetteer is not.
 */
fun monogram(name: String, kind: EntityKind): String {
    val words = name.split(' ')
        .filter { it.isNotBlank() && it.lowercase() !in setOf("the", "a", "an", "of") }
    return when {
        words.isEmpty() -> name.take(1).uppercase()
        kind == EntityKind.CHARACTER -> words.first().take(1).uppercase()
        else -> words.take(2).joinToString("") { it.take(1).uppercase() }
    }
}

/**
 * Portrait silhouette, per theme — the single most visible difference between the two decks.
 *
 * Immersive draws a disc with an accent ring: the theme is built out of pills and circles over
 * artwork, and a ring is how it says "this is a thing, and this is whose thing it is". Material
 * You draws a rounded square with no ring, because M3 already carries identity in shape and a
 * coloured hairline around a tonal container reads as a state, not an identity. Radius scales with
 * the slot so a 26dp map pin and an 88dp header have the same *proportion*, not the same corner.
 */
@Composable
fun deckPortraitShape(size: Dp): Shape =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE) CircleShape
    else RoundedCornerShape((size.value * 0.22f).dp)

@Composable
fun deckPortraitRinged(): Boolean = LocalAppTheme.current == AppTheme.IMMERSIVE
