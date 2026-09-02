package com.betteraudio.ui.reader.render

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.betteraudio.data.ebook.render.BlockAlign
import com.betteraudio.data.ebook.render.BlockKind
import com.betteraudio.data.ebook.render.RenderBlock

/** Base typographic settings a real reader screen will eventually source from `SettingsStore`
 *  (font size %, family, line height, justification, hyphenation — inventory #19–34). This is the
 *  Phase 1 vertical-slice default: one fixed style, matching the plan's "one hard-coded style"
 *  scope for the spike, now reused as the seed for the real per-block style below. */
data class ReaderTypography(
    val baseSizeSp: Float = 18f,
    val fontFamily: androidx.compose.ui.text.font.FontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
    val lineHeightMultiplier: Float = 1.45f,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    // `BasicText` does not consult `LocalContentColor`/M3 theme the way the Material `Text`
    // composable does — its `TextStyle.color` defaults to black regardless of background. Must be
    // supplied explicitly (the reader screen passes `MaterialTheme.colorScheme.onBackground`) or
    // every block renders invisibly on a dark theme. Found on-device, not in review: the first
    // real run of this pipeline through the actual reader screen rendered legible-looking but
    // literally-black text over a black background.
    val color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Black,
)

/** Maps a [RenderBlock]'s kind/heading level/alignment onto a real [TextStyle] — the part of plan
 *  C.2's `StyleResolver` this vertical slice actually implements (presentational HTML + the
 *  `text-align`/`align=` subset [com.betteraudio.data.ebook.render.EpubDocumentParser] already
 *  reads). A full CSS cascade (margins, custom properties, `<style>` blocks) is out of scope here
 *  and remains real Phase 1 follow-up work, not hidden as if already done. */
fun blockTextStyle(block: RenderBlock, base: ReaderTypography): TextStyle {
    val sizeSp = when (block.kind) {
        BlockKind.HEADING -> base.baseSizeSp * headingScale(block.headingLevel)
        else -> base.baseSizeSp
    }
    val align = when (block.align) {
        BlockAlign.CENTER -> TextAlign.Center
        BlockAlign.END -> TextAlign.End
        BlockAlign.START -> TextAlign.Start
        BlockAlign.JUSTIFY -> TextAlign.Justify
        null -> when (block.kind) {
            BlockKind.HEADING -> TextAlign.Start
            else -> if (base.justify) TextAlign.Justify else TextAlign.Start
        }
    }
    val weight = if (block.kind == BlockKind.HEADING) FontWeight.Bold else FontWeight.Normal
    val style = if (block.kind == BlockKind.PREFORMATTED) FontStyle.Normal else FontStyle.Normal
    val family = if (block.kind == BlockKind.PREFORMATTED) androidx.compose.ui.text.font.FontFamily.Monospace else base.fontFamily
    return TextStyle(
        color = base.color,
        fontSize = sizeSp.sp,
        fontFamily = family,
        fontWeight = weight,
        fontStyle = style,
        lineHeight = (sizeSp * base.lineHeightMultiplier).sp,
        textAlign = align,
        hyphens = if (base.hyphenate) androidx.compose.ui.text.style.Hyphens.Auto else androidx.compose.ui.text.style.Hyphens.None,
    )
}

private fun headingScale(level: Int): Float = when (level) {
    1 -> 1.8f; 2 -> 1.5f; 3 -> 1.3f; 4 -> 1.15f; 5 -> 1.05f; 6 -> 1f
    else -> 1.3f
}

/** [RenderBlock.spans] → an [AnnotatedString] with the corresponding character-style ranges — this
 *  is what makes bold/italic/underline/strike/sub/sup/code/link (Phase 1's inline scope) render
 *  through the normal `BasicText` + `TextMeasurer` path instead of a second, hand-built one. */
fun RenderBlock.toAnnotatedString(): AnnotatedString = AnnotatedString.Builder(text).apply {
    for (span in spans) {
        if (span.start >= span.end || span.start < 0 || span.end > text.length) continue
        addStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = when {
                    span.underline && span.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    span.underline -> TextDecoration.Underline
                    span.strike -> TextDecoration.LineThrough
                    else -> null
                },
                baselineShift = when {
                    span.sub -> androidx.compose.ui.text.style.BaselineShift.Subscript
                    span.sup -> androidx.compose.ui.text.style.BaselineShift.Superscript
                    else -> null
                },
                fontSize = if (span.sub || span.sup) 0.75.em else TextUnit.Unspecified,
                fontFamily = if (span.code) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            ),
            span.start, span.end
        )
        if (span.link != null) addStringAnnotation("link", span.link, span.start, span.end)
    }
}.toAnnotatedString()
