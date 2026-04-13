package zed.rainxch.core.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.data.local.db.entities.InstalledAppEntity

@Dao
interface InstalledAppDao {
    @Query("SELECT * FROM installed_apps ORDER BY installedAt DESC")
    fun getAllInstalledApps(): Flow<List<InstalledAppEntity>>

    @Query("SELECT * FROM installed_apps WHERE isUpdateAvailable = 1 ORDER BY lastCheckedAt DESC")
    fun getAppsWithUpdates(): Flow<List<InstalledAppEntity>>

    @Query("SELECT * FROM installed_apps WHERE packageName = :packageName")
    suspend fun getAppByPackage(packageName: String): InstalledAppEntity?

    @Query("SELECT * FROM installed_apps WHERE repoId = :repoId")
    suspend fun getAppByRepoId(repoId: Long): InstalledAppEntity?

    @Query("SELECT * FROM installed_apps WHERE repoId = :repoId")
    fun getAppByRepoIdAsFlow(repoId: Long): Flow<InstalledAppEntity?>

    @Query("SELECT COUNT(*) FROM installed_apps WHERE isUpdateAvailable = 1")
    fun getUpdateCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: InstalledAppEntity)

    @Update
    suspend fun updateApp(app: InstalledAppEntity)

    @Delete
    suspend fun deleteApp(app: InstalledAppEntity)

    @Query("DELETE FROM installed_apps WHERE packageName = :packageName")
    suspend fun deleteByPackageName(packageName: String)

    @Query(
        """
    UPDATE installed_apps 
    SET isUpdateAvailable = :available, 
        latestVersion = :version,
        latestAssetName = :assetName,
        latestAssetUrl = :assetUrl,
        latestAssetSize = :assetSize,
        releaseNotes = :releaseNotes,
        lastCheckedAt = :timestamp,
        latestVersionName = :latestVersionName,
        latestVersionCode = :latestVersionCode,
        latestReleasePublishedAt = :latestReleasePublishedAt
    WHERE packageName = :packageName
""",
    )
    suspend fun updateVersionInfo(
        packageName: String,
        available: Boolean,
        version: String?,
        assetName: String?,
        assetUrl: String?,
        assetSize: Long?,
        releaseNotes: String?,
        timestamp: Long,
        latestVersionName: String?,
        latestVersionCode: Long?,
        latestReleasePublishedAt: String?,
    )

    @Query("UPDATE installed_apps SET includePreReleases = :enabled WHERE packageName = :packageName")
    suspend fun updateIncludePreReleases(packageName: String, enabled: Boolean)

    @Query(
        """
        UPDATE installed_apps
           SET assetFilterRegex = :regex,
               fallbackToOlderReleases = :fallback
         WHERE packageName = :packageName
        """,
    )
    suspend fun updateAssetFilter(
        packageName: String,
        regex: String?,
        fallback: Boolean,
    )

    /**
     * Sets the user's preferred asset variant along with its multi-layer
     * fingerprint (token set, glob pattern, same-position metadata).
     * Always clears the "stale" flag in the same write because the user
     * has just made an explicit choice — whatever was stored before is
     * no longer stale, even if the new variant is the same value.
     *
     * Pass `null` for [variant] (and the other fingerprint fields) to
     * unpin and fall back to the platform auto-picker.
     */
    @Query(
        """
        UPDATE installed_apps
           SET preferredAssetVariant = :variant,
               preferredAssetTokens = :tokens,
               assetGlobPattern = :glob,
               pickedAssetIndex = :pickedIndex,
               pickedAssetSiblingCount = :siblingCount,
               preferredVariantStale = 0
         WHERE packageName = :packageName
        """,
    )
    suspend fun updatePreferredVariant(
        packageName: String,
        variant: String?,
        tokens: String?,
        glob: String?,
        pickedIndex: Int?,
        siblingCount: Int?,
    )

    /**
     * Sets `preferredVariantStale` for [packageName]. Used by
     * `checkForUpdates` when the persisted variant cannot be matched
     * against the assets in a fresh release.
     */
    @Query(
        """
        UPDATE installed_apps
           SET preferredVariantStale = :stale
         WHERE packageName = :packageName
        """,
    )
    suspend fun updateVariantStaleness(
        packageName: String,
        stale: Boolean,
    )

    @Query("UPDATE installed_apps SET lastCheckedAt = :timestamp WHERE packageName = :packageName")
    suspend fun updateLastChecked(
        packageName: String,
        timestamp: Long,
    )

    /**
     * Sets the path + version + asset name of a
     * downloaded-but-not-yet-installed asset. Pass all `null` to
     * clear (e.g. after the user installs the file).
     *
     * The version + asset name pair is what the Details screen uses
     * to detect "the parked file matches the currently-selected
     * release" and skip the redundant re-download.
     */
    @Query(
        """
        UPDATE installed_apps
           SET pendingInstallFilePath = :path,
               pendingInstallVersion = :version,
               pendingInstallAssetName = :assetName
         WHERE packageName = :packageName
        """,
    )
    suspend fun updatePendingInstallFilePath(
        packageName: String,
        path: String?,
        version: String?,
        assetName: String?,
    )

    /**
     * Atomically clears the "update available" badge and any cached
     * latest-release metadata for [packageName], while bumping
     * `lastCheckedAt`. Used by `checkForUpdates` whenever the current
     * filter / release window yields no match: without this, a user who
     * tightens their asset filter would keep the stale badge and the
     * download button would point at an asset that no longer matches.
     */
    @Query(
        """
        UPDATE installed_apps
           SET isUpdateAvailable = 0,
               latestVersion = NULL,
               latestAssetName = NULL,
               latestAssetUrl = NULL,
               latestAssetSize = NULL,
               latestVersionName = NULL,
               latestVersionCode = NULL,
               latestReleasePublishedAt = NULL,
               releaseNotes = NULL,
               lastCheckedAt = :timestamp
         WHERE packageName = :packageName
        """,
    )
    suspend fun clearUpdateMetadata(
        packageName: String,
        timestamp: Long,
    )
}
