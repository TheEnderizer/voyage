package com.betteraudio.ui.reader.render

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.betteraudio.data.ebook.render.BlockAlign
import com.betteraudio.data.ebook.render.BlockKind
import com.betteraudio.data.ebook.render.RenderBlock
import com.betteraudio.data.settings.ReaderPrefs

/**
 * The resolved typographic settings one page render uses — the projection of [ReaderPrefs]'
 * text-shaping half onto something the renderer and the [com.betteraudio.data.ebook.render.
 * Paginator]'s measurer both take (inventory #19–34). Built once per settings change by
 * [typographyFrom] rather than re-derived at each block, since it feeds `remember` keys.
 */
data class ReaderTypography(
    val baseSizeSp: Float = 18f,
    val fontFamily: androidx.compose.ui.text.font.FontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
    val bodyWeight: FontWeight = FontWeight.Normal,
    val headingWeight: FontWeight = FontWeight.Bold,
    val lineHeightMultiplier: Float = 1.45f,
    val wordSpacingEm: Float = 0f,
    val letterSpacingEm: Float = 0f,
    /** Paragraph gap as a multiple of the body size — the same value the paginator adds per block,
     *  so a page that measures as full renders as full. */
    val paragraphSpacingEm: Float = 0.65f,
    val textIndentEm: Float = 0f,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    // `BasicText` does not consult `LocalContentColor`/M3 theme the way the Material `Text`
    // composable does — its `TextStyle.color` defaults to black regardless of background. Must be
    // supplied explicitly (the reader screen passes the page palette's foreground) or every block
    // renders invisibly on a dark theme. Found on-device, not in review: the first real run of
    // this pipeline through the actual reader screen rendered legible-looking but literally-black
    // text over a black background.
    val color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Black,
)

/** One place that turns stored settings into a render-ready [ReaderTypography], so the reader page
 *  and the settings screen's live preview cannot drift apart. */
fun typographyFrom(prefs: ReaderPrefs, color: androidx.compose.ui.graphics.Color): ReaderTypography =
    ReaderTypography(
        baseSizeSp = prefs.effectiveBodySizeSp(),
        fontFamily = ReaderFontFamilyChoice.fromName(prefs.fontFamily).family,
        bodyWeight = prefs.bodyFontWeight(),
        headingWeight = prefs.headingFontWeight(),
        lineHeightMultiplier = prefs.lineHeight,
        wordSpacingEm = prefs.wordSpacingEm,
        letterSpacingEm = prefs.letterSpacingEm,
        paragraphSpacingEm = prefs.paragraphSpacingEm,
        textIndentEm = prefs.textIndentEm,
        justify = prefs.justify,
        hyphenate = prefs.hyphenate,
        color = color,
    )

/** Maps a [RenderBlock]'s kind/heading level/alignment onto a real [TextStyle] — the part of plan
 *  C.2's `StyleResolver` this slice implements (presentational HTML + the `text-align`/`align=`
 *  subset [com.betteraudio.data.ebook.render.EpubDocumentParser] already reads). A full CSS
 *  cascade (custom properties, `<style>` blocks) remains out of scope. */
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
    val weight = if (block.kind == BlockKind.HEADING) base.headingWeight else base.bodyWeight
    val family = if (block.kind == BlockKind.PREFORMATTED) androidx.compose.ui.text.font.FontFamily.Monospace else base.fontFamily
    // First-line indent applies to running prose only — indenting a heading or a centred line is
    // never what the setting means, and on a centred block it visibly shifts the text off-centre.
    val indentable = block.kind == BlockKind.PARAGRAPH && (align == TextAlign.Justify || align == TextAlign.Start)
    val indent = if (base.textIndentEm > 0f && indentable) {
        TextIndent(firstLine = (base.textIndentEm * sizeSp).sp)
    } else TextIndent.None
    return TextStyle(
        color = base.color,
        fontSize = sizeSp.sp,
        fontFamily = family,
        fontWeight = weight,
        fontStyle = FontStyle.Normal,
        lineHeight = (sizeSp * base.lineHeightMultiplier).sp,
        letterSpacing = if (base.letterSpacingEm != 0f) base.letterSpacingEm.em else TextUnit.Unspecified,
        textAlign = align,
        textIndent = indent,
        hyphens = if (base.hyphenate) androidx.compose.ui.text.style.Hyphens.Auto else androidx.compose.ui.text.style.Hyphens.None,
    )
}

private fun headingScale(level: Int): Float = when (level) {
    1 -> 1.8f; 2 -> 1.5f; 3 -> 1.3f; 4 -> 1.15f; 5 -> 1.05f; 6 -> 1f
    else -> 1.3f
}

/** The gap below a block, in sp-equivalent units, driven by the paragraph-spacing setting (#30).
 *  Headings get half again so a section break still reads as one. Shared by the renderer and the
 *  paginator's measurer — if these ever disagree, pages silently overflow or under-fill. */
fun blockSpacingSp(block: RenderBlock, base: ReaderTypography): Float {
    val gap = base.baseSizeSp * base.paragraphSpacingEm
    return if (block.kind == BlockKind.HEADING) gap * 1.5f else gap
}

/**
 * [RenderBlock.spans] → an [AnnotatedString] with the corresponding character-style ranges — this
 * is what makes bold/italic/underline/strike/sub/sup/code/link (Phase 1's inline scope) render
 * through the normal `BasicText` + `TextMeasurer` path instead of a second, hand-built one.
 *
 * [wordSpacingEm] (#28) is applied by widening the space characters themselves: Compose's
 * `TextStyle` has no word-spacing property (Android's `Paint.wordSpacing` is not surfaced), but
 * letter-spacing scoped to each space glyph produces exactly the same result and stays on the
 * normal styled-text path.
 */
fun RenderBlock.toAnnotatedString(wordSpacingEm: Float = 0f): AnnotatedString = AnnotatedString.Builder(text).apply {
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
    if (wordSpacingEm != 0f) {
        val extra = SpanStyle(letterSpacing = wordSpacingEm.em)
        var i = text.indexOf(' ')
        while (i >= 0) {
            addStyle(extra, i, i + 1)
            i = text.indexOf(' ', i + 1)
        }
    }
}.toAnnotatedString()
