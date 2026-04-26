package zed.rainxch.apps.presentation.import

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
import zed.rainxch.core.domain.repository.TelemetryRepository
import zed.rainxch.core.domain.system.ExternalAppCandidate
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

class ExternalImportViewModel(
    private val externalImportRepository: ExternalImportRepository,
    private val appsRepository: AppsRepository,
    private val telemetry: TelemetryRepository,
    private val logger: GitHubStoreLogger,
) : ViewModel() {
    private var candidatesByPackage: Map<String, ExternalAppCandidate> = emptyMap()
    private var hasStarted = false
    private var scanJob: Job? = null
    private var searchJob: Job? = null

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

            ExternalImportAction.OnSkipCurrentCard -> skipCurrent(neverAsk = false)

            ExternalImportAction.OnSkipForever -> skipCurrent(neverAsk = true)

            ExternalImportAction.OnSkipRemaining -> skipRemaining()

            is ExternalImportAction.OnPickSuggestion -> pickSuggestion(action.suggestion)

            ExternalImportAction.OnExpandCurrentCard -> {
                _state.update { it.copy(currentExpanded = true) }
            }

            ExternalImportAction.OnCollapseCurrentCard -> {
                _state.update { it.copy(currentExpanded = false) }
            }

            is ExternalImportAction.OnSearchOverrideChanged -> {
                // Explicit submit only: typing alone never fires a request,
                // both because the existing UX expects an Enter/icon tap and
                // to avoid hammering the rate-limited backend search.
                _state.update { it.copy(searchOverrideQuery = action.query) }
            }

            ExternalImportAction.OnSearchOverrideSubmit -> submitSearchOverride()

            ExternalImportAction.OnUndoLast -> Unit

            ExternalImportAction.OnExit -> {
                viewModelScope.launch {
                    _events.send(ExternalImportEvent.NavigateBack)
                }
            }

            ExternalImportAction.OnDismissCompletionToast -> {
                _state.update { it.copy(showCompletionToast = false) }
            }
        }
    }

    private fun startScanIfIdle(force: Boolean = false) {
        if (!force && _state.value.phase != ImportPhase.Idle) return
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            try {
                _state.update { it.copy(phase = ImportPhase.Scanning, errorMessage = null) }

                // runFullScan must precede pendingCandidatesFlow().first(): the in-memory candidate
                // snapshot is process-scoped and empty on cold start, so without a scan first the
                // wizard would render zero cards even with PENDING_REVIEW rows in the DAO.
                externalImportRepository.runFullScan()

                val candidates = externalImportRepository.pendingCandidatesFlow().first()
                candidatesByPackage = candidates.associateBy { it.packageName }

                _state.update {
                    it.copy(
                        phase = ImportPhase.AutoImporting,
                        totalCandidates = candidates.size,
                    )
                }

                val matches = externalImportRepository.resolveMatches(candidates)
                val autoLinked = autoMaterialize(matches)
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

                if (cards.isEmpty()) {
                    _state.update {
                        it.copy(
                            phase = ImportPhase.Done,
                            cards = persistentListOf(),
                            currentCardIndex = 0,
                            autoImported = autoLinked.size,
                            showCompletionToast = true,
                        )
                    }
                    _events.send(ExternalImportEvent.PlayConfetti)
                } else {
                    _state.update {
                        it.copy(
                            phase = ImportPhase.AwaitingReview,
                            cards = cards,
                            currentCardIndex = 0,
                            currentExpanded = false,
                            autoImported = autoLinked.size,
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

    private fun submitSearchOverride() {
        val query = _state.value.searchOverrideQuery.trim()
        if (query.isEmpty()) {
            searchJob?.cancel()
            _state.update {
                it.copy(
                    isSearching = false,
                    searchError = null,
                    searchOverrideResults = persistentListOf(),
                )
            }
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
                        it.copy(
                            isSearching = false,
                            searchError = null,
                            searchOverrideResults =
                                suggestions.map { s -> s.toUi() }.toImmutableList(),
                        )
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    logger.error("Search override failed for '$query': ${e.message}")
                    val fallback = getString(Res.string.external_import_search_error_default)
                    _state.update {
                        it.copy(
                            isSearching = false,
                            searchError = e.message ?: fallback,
                            searchOverrideResults = persistentListOf(),
                        )
                    }
                },
            )
        }
    }

    private fun skipCurrent(neverAsk: Boolean) {
        val current = _state.value.currentCard ?: return
        viewModelScope.launch {
            try {
                externalImportRepository.skipPackage(current.packageName, neverAsk = neverAsk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Skip failed for ${current.packageName}: ${e.message}")
            }
            runCatching {
                telemetry.importSkipped(
                    countBucket = "1-2",
                    persisted = if (neverAsk) "forever" else "7day",
                )
            }
            advanceAfter { it.copy(skipped = it.skipped + 1) }
        }
    }

    private fun pickSuggestion(suggestion: RepoSuggestionUi) {
        val current = _state.value.currentCard ?: return
        val preselected = current.preselectedSuggestion
        val source = if (suggestion == preselected) "preselected" else "alternative"
        val candidate = candidatesByPackage[current.packageName]

        viewModelScope.launch {
            if (candidate == null) {
                logger.error("Cannot materialize ${current.packageName}: candidate missing from snapshot")
                _events.send(
                    ExternalImportEvent.ShowError(
                        getString(Res.string.external_import_error_link_failed),
                    ),
                )
                return@launch
            }

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
            advanceAfter { it.copy(manuallyLinked = it.manuallyLinked + 1) }
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
        val remaining = current.cards.drop(current.currentCardIndex)
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

            _state.update {
                it.copy(
                    currentCardIndex = it.cards.size,
                    currentExpanded = false,
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
        val repoInfo =
            try {
                appsRepository.fetchRepoInfo(owner, repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("fetchRepoInfo($owner/$repo) failed: ${e.message}")
                null
            }
        if (repoInfo == null) {
            logger.warn("Skipping link for ${candidate.packageName}: repo $owner/$repo not found")
            return false
        }

        val deviceApp = candidate.toDeviceApp()
        try {
            appsRepository.linkAppToRepo(deviceApp, repoInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("linkAppToRepo failed for ${candidate.packageName}: ${e.message}")
            return false
        }

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

    private suspend fun advanceAfter(transform: (ExternalImportState) -> ExternalImportState) {
        val nextIndex = _state.value.currentCardIndex + 1
        val total = _state.value.cards.size
        val done = nextIndex >= total

        _state.update { current ->
            val tallied = transform(current).copy(currentExpanded = false)
            if (done) {
                tallied.copy(
                    phase = ImportPhase.Done,
                    showCompletionToast = true,
                )
            } else {
                tallied.copy(currentCardIndex = nextIndex)
            }
        }

        if (done) {
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

    companion object {
        private const val AUTO_LINK_THRESHOLD = 0.85
        private const val PRESELECT_MIN = 0.5
        private const val PRESELECT_MAX = 0.85
    }
}
