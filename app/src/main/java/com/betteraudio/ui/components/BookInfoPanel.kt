package com.betteraudio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.ui.player.NoResumeHandoff
import com.betteraudio.ui.player.ResumeHandoff
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.immersive.components.BookInfoPanel as ImmersiveBookInfoPanel
import com.betteraudio.ui.material.components.BookInfoPanel as MaterialBookInfoPanel

/** One book in a joined group — shown in the parts list below the synopsis. */
data class PartItem(val title: String, val durationMs: Long)

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention.
 *
 *  @param chapterStarts absolute chapter starts, for the whole-book track ([BookSegmentTrack]) —
 *         the same divided bar the player draws. Empty degrades to an undivided line.
 *  @param handoff how this panel hands its own elements to an opening player; [NoResumeHandoff]
 *         (the default) is a panel that is never a morph source. */
@Composable
fun BookInfoPanel(
    title: String,
    author: String?,
    narrator: String?,
    seriesLabel: String?,
    status: BookStatus?,
    progressFraction: Float,
    totalMs: Long,
    synopsis: String?,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    parts: List<PartItem>? = null,
    onShowHistory: (() -> Unit)? = null,
    chapterStarts: List<Long> = emptyList(),
    handoff: ResumeHandoff = NoResumeHandoff,
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> ImmersiveBookInfoPanel(
            title, author, narrator, seriesLabel, status, progressFraction, totalMs, synopsis,
            onResume, modifier, parts, onShowHistory, chapterStarts, handoff
        )
        AppTheme.MATERIAL_YOU -> MaterialBookInfoPanel(
            title, author, narrator, seriesLabel, status, progressFraction, totalMs, synopsis,
            onResume, modifier, parts, onShowHistory, chapterStarts, handoff
        )
    }
}
