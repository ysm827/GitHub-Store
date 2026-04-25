@file:OptIn(ExperimentalMaterial3Api::class)

package zed.rainxch.apps.presentation.import

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import zed.rainxch.apps.presentation.import.components.CompletionToast
import zed.rainxch.apps.presentation.import.components.EmptyStateScreen
import zed.rainxch.apps.presentation.import.components.ImportProgressScreen
import zed.rainxch.apps.presentation.import.components.PermissionRationaleScreen
import zed.rainxch.apps.presentation.import.components.WizardCardStack
import zed.rainxch.apps.presentation.import.model.ImportPhase
import zed.rainxch.core.presentation.utils.ObserveAsEvents

@Composable
fun ExternalImportRoot(
    onNavigateBack: () -> Unit,
    onNavigateToDetails: (repoId: Long) -> Unit,
    viewModel: ExternalImportViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            ExternalImportEvent.NavigateBack -> onNavigateBack()
            is ExternalImportEvent.NavigateToDetails -> onNavigateToDetails(event.repoId)
            is ExternalImportEvent.ShowError -> {
                scope.launch { snackbarHostState.showSnackbar(event.message) }
            }
            ExternalImportEvent.PlayConfetti -> {
                // TODO confetti animation — handled in CompletionToast for now.
            }
        }
    }

    LaunchedEffect(Unit) {
        if (state.phase == ImportPhase.Idle) {
            viewModel.onAction(ExternalImportAction.OnStart)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        // TODO i18n: extract to strings.xml
                        text = "Import installed apps",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.onAction(ExternalImportAction.OnExit) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            // TODO i18n: extract to strings.xml
                            contentDescription = "Back",
                        )
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
                        onContinue = {
                            viewModel.onAction(ExternalImportAction.OnRequestPermission)
                            viewModel.onAction(ExternalImportAction.OnPermissionGranted)
                        },
                        onDeny = { viewModel.onAction(ExternalImportAction.OnPermissionDenied) },
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
        }
    }
}
