package com.betteraudio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.components.ChangelogBodyView
import com.betteraudio.ui.components.ChangelogEntry
import com.betteraudio.ui.components.parseChangelog

/**
 * Settings → About's changelog: a card per version, newest first. The parsing and the body
 * rendering live in `ui/components/ChangelogMarkdown.kt`, shared with the launch-time update
 * prompt and the Updates section's "latest release" card — the same entry has to look the same
 * on all three.
 */
@Composable
fun ChangelogView(raw: String, modifier: Modifier = Modifier, maxVersions: Int = 6) {
    val versions = remember(raw) { parseChangelog(raw) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        versions.take(maxVersions).forEach { v -> VersionCard(v) }
    }
}

@Composable
private fun VersionCard(v: ChangelogEntry) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary) {
                    Text(
                        v.version,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                if (v.date.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(v.date, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ChangelogBodyView(v.body)
        }
    }
}
