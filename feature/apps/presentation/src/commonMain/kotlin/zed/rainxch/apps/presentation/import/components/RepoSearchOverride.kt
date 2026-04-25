package zed.rainxch.apps.presentation.import.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import zed.rainxch.apps.presentation.import.model.RepoSuggestionUi

@Composable
fun RepoSearchOverride(
    query: String,
    results: ImmutableList<RepoSuggestionUi>,
    isSearching: Boolean,
    searchError: String?,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onPick: (RepoSuggestionUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        // TODO i18n: extract to strings.xml
                        text = "Not the right repo? Search…",
                    )
                },
                trailingIcon = {
                    IconButton(onClick = onSubmit) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            // TODO i18n: extract to strings.xml
                            contentDescription = "Search GitHub",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            )

            if (isSearching) {
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 56.dp)
                            .size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        if (!searchError.isNullOrBlank()) {
            Text(
                text = searchError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (results.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                results.forEach { suggestion ->
                    RepoCandidateRow(
                        suggestion = suggestion,
                        onPick = onPick,
                    )
                }
            }
        } else if (query.isNotBlank() && !isSearching) {
            Text(
                // TODO i18n: extract to strings.xml
                text = "No matches",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
