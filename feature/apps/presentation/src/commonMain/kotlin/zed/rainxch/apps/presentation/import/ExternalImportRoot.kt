@file:OptIn(ExperimentalMaterial3Api::class)

package zed.rainxch.apps.presentation.import

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import zed.rainxch.apps.presentation.import.components.CompletionToast
import zed.rainxch.apps.presentation.import.components.ConfettiOverlay
import zed.rainxch.apps.presentation.import.components.EmptyStateScreen
import zed.rainxch.apps.presentation.import.components.ImportProgressScreen
import zed.rainxch.apps.presentation.import.components.PermissionRationaleScreen
import zed.rainxch.apps.presentation.import.components.WizardCardStack
import zed.rainxch.apps.presentation.import.model.ImportPhase
import zed.rainxch.apps.presentation.import.util.LocalReducedMotion
import zed.rainxch.apps.presentation.import.util.rememberSystemReducedMotion
import zed.rainxch.core.presentation.utils.ObserveAsEvents
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.external_import_overflow_more
import zed.rainxch.githubstore.core.presentation.res.external_import_overflow_skip_remaining
import zed.rainxch.githubstore.core.presentation.res.external_import_top_bar_back
import zed.rainxch.githubstore.core.presentation.res.external_import_top_bar_title

@Composable
fun ExternalImportRoot(
    onNavigateBack: () -> Unit,
    onNavigateToDetails: (repoId: Long) -> Unit,
    viewModel: ExternalImportViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confettiTrigger by remember { mutableStateOf(0) }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            ExternalImportEvent.NavigateBack -> onNavigateBack()
            is ExternalImportEvent.NavigateToDetails -> onNavigateToDetails(event.repoId)
            is ExternalImportEvent.ShowError -> {
                scope.launch { snackbarHostState.showSnackbar(event.message) }
            }
            ExternalImportEvent.PlayConfetti -> confettiTrigger++
        }
    }

    LaunchedEffect(Unit) {
        if (state.phase == ImportPhase.Idle) {
            viewModel.onAction(ExternalImportAction.OnStart)
        }
    }

    val reducedMotion = rememberSystemReducedMotion()

    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(Res.string.external_import_top_bar_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.onAction(ExternalImportAction.OnExit) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.external_import_top_bar_back),
                            )
                        }
                    },
                    actions = {
                        if (state.phase == ImportPhase.AwaitingReview && state.cardsRemaining > 1) {
                            var menuOpen by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreVert,
                                        contentDescription = stringResource(Res.string.external_import_overflow_more),
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.external_import_overflow_skip_remaining)) },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.onAction(ExternalImportAction.OnSkipRemaining)
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state.phase) {
                    ImportPhase.Idle, ImportPhase.Scanning, ImportPhase.AutoImporting -> {
                        ImportProgressScreen(
                            phase = state.phase,
                            totalCandidates = state.totalCandidates,
                        )
                    }

                    ImportPhase.RequestingPermission -> {
                        PermissionRationaleScreen(
                            onAction = viewModel::onAction,
                        )
                    }

                    ImportPhase.AwaitingReview -> {
                        val current = state.currentCard
                        if (current == null) {
                            EmptyStateScreen(
                                isPermissionDenied = state.isPermissionDenied,
                                onRequestPermission = {
                                    viewModel.onAction(ExternalImportAction.OnRequestPermission)
                                },
                                onExit = { viewModel.onAction(ExternalImportAction.OnExit) },
                            )
                        } else {
                            WizardCardStack(
                                cards = state.cards,
                                currentIndex = state.currentCardIndex,
                                expanded = state.currentExpanded,
                                searchQuery = state.searchOverrideQuery,
                                searchResults = state.searchOverrideResults,
                                isSearching = state.isSearching,
                                searchError = state.searchError,
                                onExpand = {
                                    viewModel.onAction(ExternalImportAction.OnExpandCurrentCard)
                                },
                                onCollapse = {
                                    viewModel.onAction(ExternalImportAction.OnCollapseCurrentCard)
                                },
                                onPick = { suggestion ->
                                    viewModel.onAction(ExternalImportAction.OnPickSuggestion(suggestion))
                                },
                                onSkip = {
                                    viewModel.onAction(ExternalImportAction.OnSkipCurrentCard)
                                },
                                onLink = {
                                    val preselect = current.preselectedSuggestion
                                    if (preselect != null) {
                                        viewModel.onAction(
                                            ExternalImportAction.OnPickSuggestion(preselect),
                                        )
                                    } else {
                                        viewModel.onAction(ExternalImportAction.OnSkipCurrentCard)
                                    }
                                },
                                onSearchQueryChange = { query ->
                                    viewModel.onAction(
                                        ExternalImportAction.OnSearchOverrideChanged(query),
                                    )
                                },
                                onSearchSubmit = {
                                    viewModel.onAction(ExternalImportAction.OnSearchOverrideSubmit)
                                },
                            )
                        }
                    }

                    ImportPhase.Done -> {
                        val tracked = state.autoImported + state.manuallyLinked
                        if (state.cards.isEmpty() && tracked == 0) {
                            EmptyStateScreen(
                                isPermissionDenied = state.isPermissionDenied,
                                onRequestPermission = {
                                    viewModel.onAction(ExternalImportAction.OnRequestPermission)
                                },
                                onExit = { viewModel.onAction(ExternalImportAction.OnExit) },
                            )
                        } else {
                            CompletionToast(
                                autoImported = state.autoImported,
                                manuallyLinked = state.manuallyLinked,
                                skipped = state.skipped,
                                onExit = { viewModel.onAction(ExternalImportAction.OnExit) },
                            )
                        }
                    }
                }

                // Confetti is gated on PlayConfetti events: each event bumps the trigger
                // and remounts the overlay so its LaunchedEffect re-runs the burst.
                if (state.phase == ImportPhase.Done && confettiTrigger > 0) {
                    androidx.compose.runtime.key(confettiTrigger) {
                        ConfettiOverlay(enabled = true)
                    }
                }
            }
        }
    }
}
