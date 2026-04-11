package zed.rainxch.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ExportedApp(
    val packageName: String,
    val repoOwner: String,
    val repoName: String,
    val repoUrl: String,
    // Monorepo tracking (added in export schema v2). Defaults keep
    // old v1 JSON files decoding without changes.
    val assetFilterRegex: String? = null,
    val fallbackToOlderReleases: Boolean = false,
    // Preferred-variant tracking (added in export schema v3). Defaults
    // keep older exports decoding without changes.
    val preferredAssetVariant: String? = null,
    // Multi-layer variant fingerprint (added in export schema v4):
    // serialized token set, glob pattern, and same-position fallback
    // metadata. All optional so older v1/v2/v3 exports still decode.
    val preferredAssetTokens: String? = null,
    val assetGlobPattern: String? = null,
    val pickedAssetIndex: Int? = null,
    val pickedAssetSiblingCount: Int? = null,
)

@Serializable
data class ExportedAppList(
    /**
     * Export schema version.
     *  - v2: added [ExportedApp.assetFilterRegex] / [ExportedApp.fallbackToOlderReleases]
     *  - v3: added [ExportedApp.preferredAssetVariant]
     *  - v4: added [ExportedApp.preferredAssetTokens] / [ExportedApp.assetGlobPattern]
     *        / [ExportedApp.pickedAssetIndex] / [ExportedApp.pickedAssetSiblingCount]
     *
     * All older versions still decode correctly because the new fields
     * have safe defaults.
     */
    val version: Int = 4,
    val exportedAt: Long = 0L,
    val apps: List<ExportedApp> = emptyList(),
)
