package zed.rainxch.apps.presentation.import.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import zed.rainxch.apps.presentation.import.model.CandidateUi
import zed.rainxch.apps.presentation.import.model.RepoSuggestionUi

@Composable
fun WizardCardStack(
    cards: ImmutableList<CandidateUi>,
    currentIndex: Int,
    expanded: Boolean,
    searchQuery: String,
    searchResults: ImmutableList<RepoSuggestionUi>,
    isSearching: Boolean,
    searchError: String?,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onPick: (RepoSuggestionUi) -> Unit,
    onSkip: () -> Unit,
    onLink: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = cards.getOrNull(currentIndex) ?: return
    val next = cards.getOrNull(currentIndex + 1)
    val afterNext = cards.getOrNull(currentIndex + 2)

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProgressChip(
            currentIndex = currentIndex,
            total = cards.size,
        )

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.TopCenter,
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }

                if (afterNext != null) {
                    GhostedCard(card = afterNext, depth = 2)
                }
                if (next != null) {
                    GhostedCard(card = next, depth = 1)
                }

                FrontCard(
                    candidate = current,
                    expanded = expanded,
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    isSearching = isSearching,
                    searchError = searchError,
                    parentWidthPx = maxWidthPx,
                    cardKey = currentIndex,
                    onExpand = onExpand,
                    onCollapse = onCollapse,
                    onPick = onPick,
                    onSkip = onSkip,
                    onLink = onLink,
                    onSearchQueryChange = onSearchQueryChange,
                    onSearchSubmit = onSearchSubmit,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f),
            ) {
                // TODO i18n: extract to strings.xml
                Text("Skip")
            }
            Button(
                onClick = onLink,
                modifier = Modifier.weight(1f),
            ) {
                // TODO i18n: extract to strings.xml
                Text("Link")
            }
        }
    }
}

@Composable
private fun ProgressChip(currentIndex: Int, total: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            // TODO i18n: extract to strings.xml
            text = "Card ${currentIndex + 1} of $total",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun GhostedCard(card: CandidateUi, depth: Int) {
    val (offsetDp, alpha, scale) =
        when (depth) {
            1 -> Triple(8.dp, 0.7f, 0.96f)
            else -> Triple(16.dp, 0.5f, 0.92f)
        }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = offsetDp)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                },
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        // Stub content: just the app label so the ghost reads as a card behind the front one.
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = card.appLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun FrontCard(
    candidate: CandidateUi,
    expanded: Boolean,
    searchQuery: String,
    searchResults: ImmutableList<RepoSuggestionUi>,
    isSearching: Boolean,
    searchError: String?,
    parentWidthPx: Float,
    cardKey: Int,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onPick: (RepoSuggestionUi) -> Unit,
    onSkip: () -> Unit,
    onLink: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
) {
    val offsetX = remember(cardKey) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val swipeThreshold = parentWidthPx * 0.25f

    // Reset offset whenever a new card slides into the front position.
    LaunchedEffect(cardKey) {
        offsetX.snapTo(0f)
    }

    val draggable =
        rememberDraggableState { delta ->
            scope.launch { offsetX.snapTo(offsetX.value + delta) }
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offsetX.value
                    rotationZ = (offsetX.value / 60f).coerceIn(-12f, 12f)
                }
                .draggable(
                    state = draggable,
                    orientation = Orientation.Horizontal,
                    enabled = !expanded,
                    onDragStopped = {
                        when {
                            offsetX.value > swipeThreshold -> {
                                offsetX.animateTo(parentWidthPx, tween(200))
                                onLink()
                            }
                            offsetX.value < -swipeThreshold -> {
                                offsetX.animateTo(-parentWidthPx, tween(200))
                                onSkip()
                            }
                            else -> offsetX.animateTo(0f, tween(180))
                        }
                    },
                ),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp,
    ) {
        CandidateCard(
            candidate = candidate,
            expanded = expanded,
            searchQuery = searchQuery,
            searchResults = searchResults,
            isSearching = isSearching,
            searchError = searchError,
            onExpand = onExpand,
            onCollapse = onCollapse,
            onPick = onPick,
            onSkip = onSkip,
            onSearchQueryChange = onSearchQueryChange,
            onSearchSubmit = onSearchSubmit,
        )
    }

}
