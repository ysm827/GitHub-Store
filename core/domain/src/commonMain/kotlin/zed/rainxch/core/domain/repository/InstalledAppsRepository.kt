package zed.rainxch.core.domain.repository

import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.domain.model.GithubAsset
import zed.rainxch.core.domain.model.GithubRelease
import zed.rainxch.core.domain.model.InstalledApp

interface InstalledAppsRepository {
    fun getAllInstalledApps(): Flow<List<InstalledApp>>

    fun getAppsWithUpdates(): Flow<List<InstalledApp>>

    fun getUpdateCount(): Flow<Int>

    suspend fun getAppByPackage(packageName: String): InstalledApp?

    suspend fun getAppByRepoId(repoId: Long): InstalledApp?

    fun getAppByRepoIdAsFlow(repoId: Long): Flow<InstalledApp?>

    suspend fun isAppInstalled(repoId: Long): Boolean

    suspend fun saveInstalledApp(app: InstalledApp)

    suspend fun deleteInstalledApp(packageName: String)

    suspend fun checkForUpdates(packageName: String): Boolean

    suspend fun checkAllForUpdates()

    suspend fun updateAppVersion(
        packageName: String,
        newTag: String,
        newAssetName: String,
        newAssetUrl: String,
        newVersionName: String,
        newVersionCode: Long,
        signingFingerprint: String?,
        isPendingInstall: Boolean = true,
    )

    suspend fun updateApp(app: InstalledApp)

    suspend fun updatePendingStatus(
        packageName: String,
        isPending: Boolean,
    )

    suspend fun setIncludePreReleases(
        packageName: String,
        enabled: Boolean,
    )

    /**
     * Persists per-app monorepo settings: an optional regex applied to asset
     * names and whether the update checker should fall back to older
     * releases when the latest one has no matching asset.
     *
     * Implementations should re-check the app for updates immediately so
     * the UI reflects the new state without a manual refresh.
     */
    suspend fun setAssetFilter(
        packageName: String,
        regex: String?,
        fallbackToOlderReleases: Boolean,
    )

    /**
     * Persists the user's preferred asset variant for [packageName]
     * along with the full multi-layer fingerprint:
     *
     *  - [variant]: legacy substring-tail label, used as the display
     *    name and as a third-tier match in the resolver
     *  - [tokens]: serialized token-set fingerprint (primary identity)
     *  - [glob]: glob-pattern fingerprint (secondary identity)
     *  - [pickedIndex]: zero-based index of the picked asset in the
     *    release's installable-asset list (same-position fallback)
     *  - [siblingCount]: total installable assets in the picked release
     *
     * Always clears the `preferredVariantStale` flag in the same write
     * because the user has just made an explicit choice.
     *
     * Pass `null` for all fields except [packageName] to unpin and fall
     * back to the platform auto-picker — convenient via [clearPreferredVariant].
     *
     * Implementations should re-check the app for updates immediately
     * so the cached `latestAsset*` fields point at the variant the user
     * just selected, without waiting for the next periodic worker.
     */
    suspend fun setPreferredVariant(
        packageName: String,
        variant: String?,
        tokens: String? = null,
        glob: String? = null,
        pickedIndex: Int? = null,
        siblingCount: Int? = null,
    )

    /**
     * Convenience for [setPreferredVariant] that clears every
     * fingerprint layer for [packageName] in a single call. The
     * resolver will fall back to the platform auto-picker on the next
     * update check.
     */
    suspend fun clearPreferredVariant(packageName: String)

    /**
     * Sets (or clears) the path + version + asset name of a
     * downloaded-but-not-yet-installed asset for [packageName].
     *
     * Used by `DefaultDownloadOrchestrator` when an
     * `InstallPolicy.InstallWhileForeground` download completes
     * after the foreground screen has been destroyed — the file is
     * parked, these three columns are set, and the apps list shows
     * a "Ready to install" row. The Details screen also uses
     * [version] + [assetName] to detect "the parked file matches
     * the currently-selected release" and skip re-downloading.
     *
     * Pass `null` for all three to clear (after a successful install
     * or after the user dismissed the row).
     */
    suspend fun setPendingInstallFilePath(
        packageName: String,
        path: String?,
        version: String? = null,
        assetName: String? = null,
    )

    /**
     * Dry-run helper for the per-app advanced settings sheet. Fetches a
     * window of releases for [owner]/[repo] (honouring [includePreReleases])
     * and returns the assets in the most-recent release that match
     * [regex] — or, if [fallbackToOlderReleases] is true and the latest
     * release matches nothing, the assets from the next release that does.
     *
     * Returns an empty list when no matching release is found in the
     * window. Never throws — failures resolve to an empty list and are
     * logged at debug level.
     */
    suspend fun previewMatchingAssets(
        owner: String,
        repo: String,
        regex: String?,
        includePreReleases: Boolean,
        fallbackToOlderReleases: Boolean,
    ): MatchingPreview

    suspend fun <R> executeInTransaction(block: suspend () -> R): R
}

/**
 * Snapshot returned by [InstalledAppsRepository.previewMatchingAssets] for
 * the per-app advanced settings sheet's live preview.
 */
data class MatchingPreview(
    val release: GithubRelease?,
    val matchedAssets: List<GithubAsset>,
    val regexError: String? = null,
)
