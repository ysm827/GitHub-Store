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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import zed.rainxch.apps.presentation.import.model.CandidateUi
import zed.rainxch.apps.presentation.import.model.RepoSuggestionUi
import zed.rainxch.apps.presentation.import.util.LocalReducedMotion
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.external_import_card_action_link
import zed.rainxch.githubstore.core.presentation.res.external_import_card_action_skip
import zed.rainxch.githubstore.core.presentation.res.external_import_card_progress_chip

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
                Text(stringResource(Res.string.external_import_card_action_skip))
            }
            Button(
                onClick = onLink,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(Res.string.external_import_card_action_link))
            }
        }
    }
}

@Composable
private fun ProgressChip(currentIndex: Int, total: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text =
                stringResource(
                    Res.string.external_import_card_progress_chip,
                    currentIndex + 1,
                    total,
                ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun GhostedCard(card: CandidateUi, depth: Int) {
    val (offsetDp, scale, ghostColor) =
        when (depth) {
            1 -> Triple(8.dp, 0.96f, MaterialTheme.colorScheme.surfaceContainerHigh)
            else -> Triple(16.dp, 0.92f, MaterialTheme.colorScheme.surfaceContainer)
        }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = offsetDp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .semantics(mergeDescendants = true) { hideFromAccessibility() },
        shape = RoundedCornerShape(20.dp),
        color = ghostColor,
    ) {
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
    val reducedMotion = LocalReducedMotion.current
    val rotationFactor = if (reducedMotion) 0f else 1f

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
                    rotationZ = (offsetX.value / 60f * rotationFactor).coerceIn(-12f, 12f)
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
