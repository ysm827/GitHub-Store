package zed.rainxch.apps.presentation.import.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.apps.presentation.components.InstalledAppIcon
import zed.rainxch.apps.presentation.import.model.CandidateUi
import zed.rainxch.apps.presentation.import.model.RepoSuggestionUi
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.external_import_card_action_skip
import zed.rainxch.githubstore.core.presentation.res.external_import_card_collapse_label
import zed.rainxch.githubstore.core.presentation.res.external_import_card_expand_label
import zed.rainxch.githubstore.core.presentation.res.external_import_card_installer_chip
import zed.rainxch.githubstore.core.presentation.res.external_import_card_preselect_known
import zed.rainxch.githubstore.core.presentation.res.external_import_card_preselect_unknown

@Composable
fun CandidateCard(
    candidate: CandidateUi,
    expanded: Boolean,
    searchQuery: String,
    searchResults: ImmutableList<RepoSuggestionUi>,
    isSearching: Boolean,
    searchError: String?,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onPick: (RepoSuggestionUi) -> Unit,
    onSkip: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandLabel = stringResource(Res.string.external_import_card_expand_label)
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
            modifier
                .fillMaxWidth()
                .let { base ->
                    if (!expanded) {
                        base.clickable(
                            onClickLabel = expandLabel,
                            role = Role.Button,
                        ) { onExpand() }
                    } else {
                        base
                    }
                },
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CandidateHeader(candidate = candidate)

            if (!expanded) {
                PreselectedRow(suggestion = candidate.preselectedSuggestion)
            } else {
                if (candidate.suggestions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        candidate.suggestions.take(3).forEach { suggestion ->
                            RepoCandidateRow(
                                suggestion = suggestion,
                                onPick = onPick,
                            )
                        }
                    }
                }

                RepoSearchOverride(
                    query = searchQuery,
                    results = searchResults,
                    isSearching = isSearching,
                    searchError = searchError,
                    onQueryChange = onSearchQueryChange,
                    onSubmit = onSearchSubmit,
                    onPick = onPick,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSkip) {
                        Text(stringResource(Res.string.external_import_card_action_skip))
                    }
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(Res.string.external_import_card_collapse_label),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateHeader(candidate: CandidateUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InstalledAppIcon(
            packageName = candidate.packageName,
            appName = candidate.appLabel,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp)),
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = candidate.appLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = candidate.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            InstallerChip(installerLabel = candidate.installerLabel)
        }
    }
}

@Composable
private fun InstallerChip(installerLabel: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.external_import_card_installer_chip, installerLabel),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PreselectedRow(suggestion: RepoSuggestionUi?) {
    val containerColor =
        if (suggestion != null) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (suggestion != null) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (suggestion == null) {
            Text(
                text = stringResource(Res.string.external_import_card_preselect_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        } else {
            val percent = (suggestion.confidence * 100).roundToInt().coerceIn(0, 100)
            Text(
                text =
                    stringResource(
                        Res.string.external_import_card_preselect_known,
                        suggestion.ownerSlashRepo,
                        percent,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}
