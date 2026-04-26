package zed.rainxch.apps.presentation.import

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import zed.rainxch.apps.domain.repository.AppsRepository
import zed.rainxch.apps.presentation.import.model.CandidateUi
import zed.rainxch.apps.presentation.import.model.ImportPhase
import zed.rainxch.apps.presentation.import.model.RepoSuggestionUi
import zed.rainxch.apps.presentation.import.model.SuggestionSource
import zed.rainxch.core.domain.logging.GitHubStoreLogger
import zed.rainxch.core.domain.model.DeviceApp
import zed.rainxch.core.domain.repository.ExternalImportRepository
import zed.rainxch.core.domain.repository.InstalledAppsRepository
import zed.rainxch.core.domain.repository.TelemetryRepository
import zed.rainxch.core.domain.system.ExternalAppCandidate
import zed.rainxch.core.domain.system.ExternalDecisionSnapshot
import zed.rainxch.core.domain.system.InstallerKind
import zed.rainxch.core.domain.system.RepoMatchResult
import zed.rainxch.core.domain.system.RepoMatchSource
import zed.rainxch.core.domain.system.RepoMatchSuggestion
import zed.rainxch.githubstore.core.presentation.res.Res
import zed.rainxch.githubstore.core.presentation.res.external_import_error_link_failed
import zed.rainxch.githubstore.core.presentation.res.external_import_error_link_network
import zed.rainxch.githubstore.core.presentation.res.external_import_error_scan_failed_default
import zed.rainxch.githubstore.core.presentation.res.external_import_installer_browser
import zed.rainxch.githubstore.core.presentation.res.external_import_installer_fdroid
import zed.rainxch.githubstore.core.presentation.res.external_import_installer_obtainium
import zed.rainxch.githubstore.core.presentation.res.external_import_installer_self
import zed.rainxch.githubstore.core.presentation.res.external_import_installer_sideload
import zed.rainxch.githubstore.core.presentation.res.external_import_installer_unknown
import zed.rainxch.githubstore.core.presentation.res.external_import_search_error_default
import zed.rainxch.githubstore.core.presentation.res.external_import_undo_failed
import zed.rainxch.githubstore.core.presentation.res.external_import_undo_linked
import zed.rainxch.githubstore.core.presentation.res.external_import_undo_skipped

class ExternalImportViewModel(
    private val externalImportRepository: ExternalImportRepository,
    private val appsRepository: AppsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val telemetry: TelemetryRepository,
    private val logger: GitHubStoreLogger,
) : ViewModel() {
    private var candidatesByPackage: Map<String, ExternalAppCandidate> = emptyMap()
    // Cached so OnAutoSummaryUndoAll can re-build cards for previously
    // auto-linked packages without round-tripping resolveMatches() (which
    // would issue another network call and could return different matches).
    private var lastResolvedMatches: List<RepoMatchResult> = emptyList()
    private var hasStarted = false
    private var scanJob: Job? = null
    private var searchJob: Job? = null
    private var pendingUndo: PendingUndo? = null

    private val debug get() = logger.withTag("E1Debug")

    private val _state = MutableStateFlow(ExternalImportState())
    val state =
        _state
            .onStart {
                if (!hasStarted) {
                    hasStarted = true
                    startScanIfIdle()
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = ExternalImportState(),
            )

    private val _events = Channel<ExternalImportEvent>()
    val events = _events.receiveAsFlow()

    fun onAction(action: ExternalImportAction) {
        when (action) {
            ExternalImportAction.OnStart -> {
                if (_state.value.phase == ImportPhase.Idle) startScanIfIdle()
            }

            ExternalImportAction.OnRequestPermission -> {
                _state.update { it.copy(phase = ImportPhase.RequestingPermission) }
                viewModelScope.launch { runCatching { telemetry.importPermissionRequested() } }
            }

            is ExternalImportAction.OnPermissionGranted -> {
                _state.update { it.copy(isPermissionDenied = false) }
                emitPermissionOutcome(granted = true, sdkInt = action.sdkInt)
                startScanIfIdle(force = true)
            }

            is ExternalImportAction.OnPermissionDenied -> {
                _state.update { it.copy(isPermissionDenied = true) }
                emitPermissionOutcome(granted = false, sdkInt = action.sdkInt)
                startScanIfIdle(force = true)
            }

            is ExternalImportAction.OnSkipCard -> skipPackage(action.packageName, neverAsk = false)

            is ExternalImportAction.OnSkipForever -> skipPackage(action.packageName, neverAsk = true)

            ExternalImportAction.OnSkipRemaining -> skipRemaining()

            is ExternalImportAction.OnPickSuggestion ->
                pickSuggestion(action.packageName, action.suggestion)

            is ExternalImportAction.OnLinkCard -> linkCardWithPreselected(action.packageName)

            is ExternalImportAction.OnToggleCardExpanded -> toggleCardExpanded(action.packageName)

            is ExternalImportAction.OnSearchOverrideChanged -> {
                // Explicit submit only — we never auto-fire on keystrokes.
                _state.update {
                    if (it.activeSearchPackage != action.packageName) {
                        // Switched cards: drop stale results from the previous card.
                        it.copy(
                            activeSearchPackage = action.packageName,
                            searchQuery = action.query,
                            searchResults = persistentListOf(),
                            isSearching = false,
                            searchError = null,
                        )
                    } else {
                        it.copy(searchQuery = action.query)
                    }
                }
            }

            is ExternalImportAction.OnSearchOverrideSubmit -> submitSearchOverride(action.packageName)

            ExternalImportAction.OnUndoLast -> undoLast()

            ExternalImportAction.OnExit -> {
                viewModelScope.launch {
                    _events.send(ExternalImportEvent.NavigateBack)
                }
            }

            ExternalImportAction.OnDismissCompletionToast -> {
                _state.update { it.copy(showCompletionToast = false) }
            }

            ExternalImportAction.OnAutoSummaryContinue -> autoSummaryContinue()

            ExternalImportAction.OnAutoSummaryUndoAll -> autoSummaryUndoAll()

            ExternalImportAction.OnAddManually -> {
                viewModelScope.launch {
                    _events.send(ExternalImportEvent.NavigateBackAndOpenManualLink)
                }
            }
        }
    }

    private fun toggleCardExpanded(packageName: String) {
        _state.update { current ->
            val nextSet =
                if (packageName in current.expandedPackages) {
                    current.expandedPackages.toPersistentSet().remove(packageName)
                } else {
                    current.expandedPackages.toPersistentSet().add(packageName)
                }
            // Clear cross-card search results when collapsing the active card so
            // they don't bleed into the next card the user expands.
            val keepSearch = current.activeSearchPackage == packageName && packageName in nextSet
            current.copy(
                expandedPackages = nextSet,
                activeSearchPackage = if (keepSearch) current.activeSearchPackage else null,
                searchQuery = if (keepSearch) current.searchQuery else "",
                searchResults = if (keepSearch) current.searchResults else persistentListOf(),
                isSearching = if (keepSearch) current.isSearching else false,
                searchError = if (keepSearch) current.searchError else null,
            )
        }
    }

    private fun startScanIfIdle(force: Boolean = false) {
        if (!force && _state.value.phase != ImportPhase.Idle) return
        if (scanJob?.isActive == true) return
        debug.info("VM startScanIfIdle force=$force")
        scanJob = viewModelScope.launch {
            try {
                _state.update { it.copy(phase = ImportPhase.Scanning, errorMessage = null) }

                externalImportRepository.runFullScan()

                val candidates = externalImportRepository.pendingCandidatesFlow().first()
                candidatesByPackage = candidates.associateBy { it.packageName }
                debug.info("VM after pendingCandidatesFlow.first(): ${candidates.size} candidates")

                _state.update {
                    it.copy(
                        phase = ImportPhase.AutoImporting,
                        totalCandidates = candidates.size,
                    )
                }

                val matches = externalImportRepository.resolveMatches(candidates)
                lastResolvedMatches = matches
                debug.info("VM resolveMatches returned ${matches.size} results")
                val autoLinked = autoMaterialize(matches)
                debug.info("VM autoMaterialize linked=${autoLinked.size} pkgs=$autoLinked")
                val autoLinkedPackages = autoLinked.toSet()

                val reviewCandidates =
                    candidates.filter { it.packageName !in autoLinkedPackages }
                val reviewMatchesByPkg =
                    matches.associateBy { it.packageName }

                val cards =
                    reviewCandidates
                        .mapNotNull { candidate ->
                            val match = reviewMatchesByPkg[candidate.packageName]
                            buildCard(candidate, match)
                        }.toImmutableList()

                if (autoLinked.isNotEmpty()) {
                    // Stop on the summary screen so the user sees what auto-linked
                    // and can undo before we cascade into the review wizard.
                    val autoLinkedLabels = autoLinked.mapNotNull { pkg ->
                        candidatesByPackage[pkg]?.appLabel
                    }
                    _state.update {
                        it.copy(
                            phase = ImportPhase.AutoImportSummary,
                            cards = cards,
                            autoImported = autoLinked.size,
                            autoLinkedPackages = autoLinked.toPersistentList(),
                            autoLinkedLabels = autoLinkedLabels.toPersistentList(),
                        )
                    }
                } else if (cards.isEmpty()) {
                    _state.update {
                        it.copy(
                            phase = ImportPhase.Done,
                            cards = persistentListOf(),
                            autoImported = 0,
                            showCompletionToast = true,
                        )
                    }
                    _events.send(ExternalImportEvent.PlayConfetti)
                } else {
                    _state.update {
                        it.copy(
                            phase = ImportPhase.AwaitingReview,
                            cards = cards,
                            autoImported = 0,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("External import scan failed: ${e.message}")
                _state.update {
                    it.copy(
                        phase = ImportPhase.Idle,
                        errorMessage = e.message,
                    )
                }
                _events.send(
                    ExternalImportEvent.ShowError(
                        e.message ?: getString(Res.string.external_import_error_scan_failed_default),
                    ),
                )
            }
        }
    }

    private suspend fun buildCard(
        candidate: ExternalAppCandidate,
        match: RepoMatchResult?,
    ): CandidateUi? {
        val suggestionsDomain = match?.suggestions.orEmpty()
        val top = suggestionsDomain.maxByOrNull { it.confidence }
        val preselected =
            if (top != null && top.confidence in PRESELECT_MIN..PRESELECT_MAX) top.toUi() else null

        return CandidateUi(
            packageName = candidate.packageName,
            appLabel = candidate.appLabel,
            versionName = candidate.versionName,
            installerLabel = candidate.installerKind.toUiLabel(),
            suggestions = suggestionsDomain.take(3).map { it.toUi() }.toImmutableList(),
            preselectedSuggestion = preselected,
        )
    }

    private fun submitSearchOverride(packageName: String) {
        val current = _state.value
        // Search submit applies to whichever card was last typed in. If the
        // active package and the submitted package mismatch, we still honour
        // the submit using the active query — this only happens if the user
        // taps the icon in a different card before the keystrokes registered,
        // which the UI prevents via per-card query binding.
        if (current.activeSearchPackage != packageName) return

        val query = current.searchQuery.trim()
        if (query.isEmpty()) {
            searchJob?.cancel()
            _state.update {
                it.copy(
                    isSearching = false,
                    searchError = null,
                    searchResults = persistentListOf(),
                )
            }
            return
        }

        // Fast-path: a github.com/owner/repo URL bypasses the search API and
        // surfaces a single MANUAL suggestion that the user can tap to link.
        // This unblocks users with rate-limited search and matches Obtainium's
        // "paste a URL" mental model.
        parseGithubRepoUrl(query)?.let { (owner, repo) ->
            searchJob?.cancel()
            _state.update {
                it.copy(
                    isSearching = false,
                    searchError = null,
                    searchResults = persistentListOf(
                        RepoSuggestionUi(
                            owner = owner,
                            repo = repo,
                            confidence = 1.0,
                            source = SuggestionSource.MANUAL,
                            stars = null,
                            description = null,
                        ),
                    ),
                )
            }
            viewModelScope.launch { runCatching { telemetry.importSearchOverrideUsed() } }
            return
        }

        searchJob?.cancel()
        _state.update { it.copy(isSearching = true, searchError = null) }
        viewModelScope.launch { runCatching { telemetry.importSearchOverrideUsed() } }
        searchJob = viewModelScope.launch {
            val result = runCatching { externalImportRepository.searchRepos(query) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    Result.failure(e)
                }

            result.fold(
                onSuccess = { suggestions ->
                    if (suggestions.isEmpty()) {
                        runCatching { telemetry.importSearchOverrideNoResults() }
                    }
                    _state.update {
                        // Stale-completion guard: if the user collapsed or switched cards
                        // while the request was in flight, drop the response on the floor
                        // so old results never bleed into a newly-active card.
                        if (it.activeSearchPackage != packageName) it
                        else it.copy(
                            isSearching = false,
                            searchError = null,
                            searchResults =
                                suggestions.map { s -> s.toUi() }.toImmutableList(),
                        )
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    logger.error("Search override failed for '$query': ${e.message}")
                    val fallback = getString(Res.string.external_import_search_error_default)
                    _state.update {
                        if (it.activeSearchPackage != packageName) it
                        else it.copy(
                            isSearching = false,
                            searchError = e.message ?: fallback,
                            searchResults = persistentListOf(),
                        )
                    }
                },
            )
        }
    }

    private fun skipPackage(packageName: String, neverAsk: Boolean) {
        val card = _state.value.cards.firstOrNull { it.packageName == packageName } ?: return
        viewModelScope.launch {
            val snapshot = runCatching {
                externalImportRepository.snapshotDecision(packageName)
            }.getOrNull()
            val hadInstalledRow = installedAppsRepository.getAppByPackage(packageName) != null

            try {
                externalImportRepository.skipPackage(packageName, neverAsk = neverAsk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Skip failed for $packageName: ${e.message}")
            }
            runCatching {
                telemetry.importSkipped(
                    countBucket = "1-2",
                    persisted = if (neverAsk) "forever" else "7day",
                )
            }
            removeCardFromState(packageName) { it.copy(skipped = it.skipped + 1) }

            pendingUndo = PendingUndo(
                card = card,
                snapshot = snapshot,
                hadInstalledAppRowBefore = hadInstalledRow,
                kind = PendingUndo.Kind.Skip,
            )
            _events.send(
                ExternalImportEvent.ShowUndoSnackbar(
                    getString(Res.string.external_import_undo_skipped, card.appLabel),
                ),
            )
        }
    }

    private fun pickSuggestion(packageName: String, suggestion: RepoSuggestionUi) {
        val card = _state.value.cards.firstOrNull { it.packageName == packageName } ?: return
        val preselected = card.preselectedSuggestion
        val source = if (suggestion == preselected) "preselected" else "alternative"
        val candidate = candidatesByPackage[packageName]

        viewModelScope.launch {
            if (candidate == null) {
                logger.error("Cannot materialize $packageName: candidate missing from snapshot")
                _events.send(
                    ExternalImportEvent.ShowError(
                        getString(Res.string.external_import_error_link_failed),
                    ),
                )
                return@launch
            }

            val snapshot = runCatching {
                externalImportRepository.snapshotDecision(packageName)
            }.getOrNull()
            val hadInstalledRow = installedAppsRepository.getAppByPackage(packageName) != null

            val materialized = materializeAndMark(candidate, suggestion.owner, suggestion.repo, source)
            if (!materialized) {
                _events.send(
                    ExternalImportEvent.ShowError(
                        getString(Res.string.external_import_error_link_network),
                    ),
                )
                return@launch
            }
            runCatching {
                telemetry.importManuallyLinked(countBucket = "1-2", source = source)
            }
            removeCardFromState(packageName) { it.copy(manuallyLinked = it.manuallyLinked + 1) }

            pendingUndo = PendingUndo(
                card = card,
                snapshot = snapshot,
                hadInstalledAppRowBefore = hadInstalledRow,
                kind = PendingUndo.Kind.Link,
            )
            _events.send(
                ExternalImportEvent.ShowUndoSnackbar(
                    getString(Res.string.external_import_undo_linked, card.appLabel),
                ),
            )
        }
    }

    private fun linkCardWithPreselected(packageName: String) {
        val card = _state.value.cards.firstOrNull { it.packageName == packageName } ?: return
        val preselect = card.preselectedSuggestion
        if (preselect != null) {
            pickSuggestion(packageName, preselect)
        } else {
            // No preselection means there's nothing to confidently link to. The
            // list-mode UI hides the link CTA in this case, but defensively
            // surface the expand affordance instead of silently dropping.
            toggleCardExpanded(packageName)
        }
    }

    private fun undoLast() {
        val undo = pendingUndo ?: return
        pendingUndo = null

        viewModelScope.launch {
            try {
                if (undo.kind == PendingUndo.Kind.Link && !undo.hadInstalledAppRowBefore) {
                    // The link materialized a new installed_apps row; remove it
                    // before restoring the link table state so getAppByPackage
                    // observers see the rollback in one shot.
                    runCatching {
                        installedAppsRepository.deleteInstalledApp(undo.packageName)
                    }
                }

                if (undo.snapshot != null) {
                    externalImportRepository.restoreDecision(undo.snapshot)
                } else {
                    // No prior row existed (first-ever scan + decision). Drop
                    // the new link entirely so the candidate becomes pending
                    // again on the next scan.
                    runCatching { externalImportRepository.unlink(undo.packageName) }
                }

                // Re-insert the original card at the top so the user can retry
                // immediately. We use the card we cached on the snackbar token —
                // re-running resolveMatches would issue a network call and risks
                // returning different suggestions than the user just saw.
                _state.update { current ->
                    if (current.cards.any { it.packageName == undo.packageName }) {
                        current
                    } else {
                        val tally = when (undo.kind) {
                            PendingUndo.Kind.Link ->
                                current.copy(
                                    manuallyLinked = (current.manuallyLinked - 1).coerceAtLeast(0),
                                )
                            PendingUndo.Kind.Skip ->
                                current.copy(
                                    skipped = (current.skipped - 1).coerceAtLeast(0),
                                )
                        }
                        tally.copy(
                            cards = (listOf(undo.card) + current.cards).toImmutableList(),
                            phase = ImportPhase.AwaitingReview,
                            showCompletionToast = false,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Undo failed for ${undo.packageName}: ${e.message}")
                _events.send(
                    ExternalImportEvent.ShowError(
                        getString(Res.string.external_import_undo_failed),
                    ),
                )
            }
        }
    }

    private fun autoSummaryContinue() {
        val current = _state.value
        if (current.phase != ImportPhase.AutoImportSummary) return
        if (current.cards.isNotEmpty()) {
            _state.update { it.copy(phase = ImportPhase.AwaitingReview) }
        } else {
            _state.update {
                it.copy(
                    phase = ImportPhase.Done,
                    showCompletionToast = true,
                )
            }
            viewModelScope.launch { _events.send(ExternalImportEvent.PlayConfetti) }
        }
    }

    private fun autoSummaryUndoAll() {
        val current = _state.value
        if (current.phase != ImportPhase.AutoImportSummary) return
        val packages = current.autoLinkedPackages.toList()
        debug.info("VM autoSummaryUndoAll packages=${packages.size}")
        if (packages.isEmpty()) {
            _state.update { it.copy(phase = ImportPhase.AwaitingReview) }
            return
        }

        viewModelScope.launch {
            // Roll each auto-linked package back to PENDING_REVIEW. Snapshot →
            // restoreDecision matches the per-row undo path, so the audit trail
            // and DAO state mirror what the user would see after a fresh scan
            // with no auto-link applied.
            packages.forEach { pkg ->
                val snapshot = runCatching {
                    externalImportRepository.snapshotDecision(pkg)
                }.getOrNull()
                runCatching { installedAppsRepository.deleteInstalledApp(pkg) }
                if (snapshot != null) {
                    runCatching { externalImportRepository.restoreDecision(snapshot) }
                } else {
                    runCatching { externalImportRepository.unlink(pkg) }
                }
            }

            // Bulk-undo invalidates the single-row undo token: the user cleared
            // the auto-link wave wholesale, so a stale "Undo" snackbar from a
            // pre-summary action would now point at a row we just restored.
            pendingUndo = null

            val matchesByPkg = lastResolvedMatches.associateBy { it.packageName }
            val restoredCards = packages.mapNotNull { pkg ->
                val candidate = candidatesByPackage[pkg] ?: return@mapNotNull null
                buildCard(candidate, matchesByPkg[pkg])
            }

            _state.update { state ->
                val merged = (restoredCards + state.cards)
                    .distinctBy { it.packageName }
                    .toImmutableList()
                state.copy(
                    phase = if (merged.isEmpty()) ImportPhase.Done else ImportPhase.AwaitingReview,
                    cards = merged,
                    autoImported = 0,
                    autoLinkedPackages = persistentListOf(),
                    autoLinkedLabels = persistentListOf(),
                    showCompletionToast = merged.isEmpty(),
                )
            }
            if (_state.value.cards.isEmpty()) {
                _events.send(ExternalImportEvent.PlayConfetti)
            }
        }
    }

    private fun emitPermissionOutcome(granted: Boolean, sdkInt: Int?) {
        viewModelScope.launch {
            runCatching {
                telemetry.importPermissionOutcome(
                    granted = granted,
                    sdkIntBucket = bucketSdkInt(sdkInt),
                )
            }
        }
    }

    private fun bucketSdkInt(sdkInt: Int?): String =
        when {
            sdkInt == null -> "unknown"
            sdkInt in 26..29 -> "26-29"
            sdkInt in 30..32 -> "30-32"
            sdkInt >= 33 -> "33+"
            else -> "unknown"
        }

    private fun bucketCount(count: Int): String =
        when {
            count <= 0 -> "0"
            count in 1..2 -> "1-2"
            count in 3..9 -> "3-9"
            count in 10..49 -> "10-49"
            else -> "50+"
        }

    private fun skipRemaining() {
        val current = _state.value
        if (current.phase != ImportPhase.AwaitingReview) return
        val remaining = current.cards
        if (remaining.isEmpty()) return

        viewModelScope.launch {
            remaining.forEach { card ->
                try {
                    externalImportRepository.skipPackage(card.packageName, neverAsk = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Skip-remaining failed for ${card.packageName}: ${e.message}")
                }
            }

            runCatching {
                telemetry.importSkipped(
                    countBucket = bucketCount(remaining.size),
                    persisted = "7day",
                )
            }

            // Skip-remaining is intentionally not undoable — bulk skip clears
            // the wizard and triggers the completion screen, and a single
            // snackbar isn't a sensible affordance for "undo seven things".
            pendingUndo = null

            _state.update {
                it.copy(
                    cards = persistentListOf(),
                    expandedPackages = persistentSetOf(),
                    activeSearchPackage = null,
                    searchQuery = "",
                    searchResults = persistentListOf(),
                    isSearching = false,
                    searchError = null,
                    phase = ImportPhase.Done,
                    skipped = it.skipped + remaining.size,
                    showCompletionToast = true,
                )
            }
            _events.send(ExternalImportEvent.PlayConfetti)
        }
    }

    private suspend fun autoMaterialize(matches: List<RepoMatchResult>): List<String> {
        val linked = mutableListOf<String>()
        matches.forEach { result ->
            val top = result.topSuggestion ?: return@forEach
            if (top.confidence < AUTO_LINK_THRESHOLD) return@forEach
            val candidate = candidatesByPackage[result.packageName] ?: return@forEach

            val ok =
                materializeAndMark(
                    candidate = candidate,
                    owner = top.owner,
                    repo = top.repo,
                    source = "auto-${top.source.name.lowercase()}",
                )
            if (ok) linked += result.packageName
        }
        return linked
    }

    private suspend fun materializeAndMark(
        candidate: ExternalAppCandidate,
        owner: String,
        repo: String,
        source: String,
    ): Boolean {
        debug.info("VM materializeAndMark pkg=${candidate.packageName} repo=$owner/$repo source=$source")
        val repoInfo =
            try {
                appsRepository.fetchRepoInfo(owner, repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("fetchRepoInfo($owner/$repo) failed: ${e.message}")
                debug.error("VM materializeAndMark fetchRepoInfo failed pkg=${candidate.packageName}", e)
                null
            }
        if (repoInfo == null) {
            logger.warn("Skipping link for ${candidate.packageName}: repo $owner/$repo not found")
            debug.info("VM materializeAndMark FAILED pkg=${candidate.packageName} repo=$owner/$repo NOT FOUND")
            return false
        }

        val deviceApp = candidate.toDeviceApp()
        try {
            appsRepository.linkAppToRepo(deviceApp, repoInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("linkAppToRepo failed for ${candidate.packageName}: ${e.message}")
            debug.error("VM materializeAndMark linkAppToRepo failed pkg=${candidate.packageName}", e)
            return false
        }
        debug.info("VM materializeAndMark SUCCESS pkg=${candidate.packageName}")

        val linkResult =
            try {
                externalImportRepository.linkManually(
                    packageName = candidate.packageName,
                    owner = owner,
                    repo = repo,
                    source = source,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        if (linkResult.isFailure) {
            logger.error(
                "external_links upsert failed for ${candidate.packageName}: " +
                    "${linkResult.exceptionOrNull()?.message}",
            )
            // installed_apps row is already written; the audit trail is
            // ahead but recoverable on the next scan via mergeCandidate.
        }
        return true
    }

    private fun ExternalAppCandidate.toDeviceApp(): DeviceApp =
        DeviceApp(
            packageName = packageName,
            appName = appLabel,
            versionName = versionName,
            versionCode = versionCode,
            signingFingerprint = signingFingerprint,
        )

    private suspend fun removeCardFromState(
        packageName: String,
        tally: (ExternalImportState) -> ExternalImportState,
    ) {
        // _state.update may invoke the lambda multiple times under contention;
        // never assign captured vars from inside it. Read the post-update
        // state to decide whether to fire the completion event.
        _state.update { current ->
            val newCards = current.cards.filterNot { it.packageName == packageName }.toImmutableList()
            val tallied = tally(current).copy(
                cards = newCards,
                expandedPackages = current.expandedPackages.toPersistentSet().remove(packageName),
                activeSearchPackage = if (current.activeSearchPackage == packageName) null else current.activeSearchPackage,
                searchQuery = if (current.activeSearchPackage == packageName) "" else current.searchQuery,
                searchResults = if (current.activeSearchPackage == packageName) persistentListOf() else current.searchResults,
                isSearching = if (current.activeSearchPackage == packageName) false else current.isSearching,
                searchError = if (current.activeSearchPackage == packageName) null else current.searchError,
            )
            if (newCards.isEmpty()) {
                tallied.copy(
                    phase = ImportPhase.Done,
                    showCompletionToast = true,
                )
            } else {
                tallied
            }
        }
        if (_state.value.cards.isEmpty()) {
            _events.send(ExternalImportEvent.PlayConfetti)
        }
    }

    private fun RepoMatchSuggestion.toUi(): RepoSuggestionUi =
        RepoSuggestionUi(
            owner = owner,
            repo = repo,
            confidence = confidence,
            source =
                when (source) {
                    RepoMatchSource.MANIFEST -> SuggestionSource.MANIFEST
                    RepoMatchSource.SEARCH -> SuggestionSource.SEARCH
                    RepoMatchSource.FINGERPRINT -> SuggestionSource.FINGERPRINT
                    RepoMatchSource.MANUAL -> SuggestionSource.MANUAL
                },
            stars = stars,
            description = description,
        )

    private suspend fun InstallerKind.toUiLabel(): String =
        when (this) {
            InstallerKind.STORE_OBTAINIUM -> getString(Res.string.external_import_installer_obtainium)
            InstallerKind.STORE_FDROID -> getString(Res.string.external_import_installer_fdroid)
            InstallerKind.BROWSER -> getString(Res.string.external_import_installer_browser)
            InstallerKind.SIDELOAD -> getString(Res.string.external_import_installer_sideload)
            InstallerKind.GITHUB_STORE_SELF -> getString(Res.string.external_import_installer_self)
            InstallerKind.UNKNOWN -> getString(Res.string.external_import_installer_unknown)
            else -> getString(Res.string.external_import_installer_unknown)
        }

    private data class PendingUndo(
        val card: CandidateUi,
        val snapshot: ExternalDecisionSnapshot?,
        val hadInstalledAppRowBefore: Boolean,
        val kind: Kind,
    ) {
        val packageName: String get() = card.packageName
        val appLabel: String get() = card.appLabel

        enum class Kind { Skip, Link }
    }

    companion object {
        private const val AUTO_LINK_THRESHOLD = 0.85
        private const val PRESELECT_MIN = 0.5
        private const val PRESELECT_MAX = 0.85
    }
}

private fun parseGithubRepoUrl(input: String): Pair<String, String>? {
    val trimmed = input.trim().removeSuffix("/")
    if (trimmed.isEmpty()) return null
    // Accept both bare host references and full https URLs. Anything else
    // (search keywords, partial slugs without owner) falls through to the
    // backend search path.
    val withoutScheme = trimmed
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
    if (!withoutScheme.startsWith("github.com/", ignoreCase = true)) return null
    val path = withoutScheme.substring("github.com/".length)
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    val owner = parts[0]
    val repo = parts[1].substringBefore('?').substringBefore('#')
    if (!isValidGithubSegment(owner) || !isValidGithubSegment(repo)) return null
    return owner to repo
}

private fun isValidGithubSegment(s: String): Boolean =
    s.isNotEmpty() &&
        s.length <= 100 &&
        s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
