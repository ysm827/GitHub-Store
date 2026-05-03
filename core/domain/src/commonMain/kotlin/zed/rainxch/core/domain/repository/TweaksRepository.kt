package zed.rainxch.core.domain.repository

import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.domain.model.AnnouncementCategory
import zed.rainxch.core.domain.model.AppTheme
import zed.rainxch.core.domain.model.DiscoveryPlatform
import zed.rainxch.core.domain.model.FontTheme
import zed.rainxch.core.domain.model.InstallerType
import zed.rainxch.core.domain.model.TranslationProvider

interface TweaksRepository {
    fun getThemeColor(): Flow<AppTheme>

    suspend fun setThemeColor(theme: AppTheme)

    fun getIsDarkTheme(): Flow<Boolean?>

    suspend fun setDarkTheme(isDarkTheme: Boolean?)

    fun getAmoledTheme(): Flow<Boolean>

    suspend fun setAmoledTheme(enabled: Boolean)

    fun getFontTheme(): Flow<FontTheme>

    suspend fun setFontTheme(fontTheme: FontTheme)

    fun getAutoDetectClipboardLinks(): Flow<Boolean>

    suspend fun setAutoDetectClipboardLinks(enabled: Boolean)

    fun getInstallerType(): Flow<InstallerType>

    suspend fun setInstallerType(type: InstallerType)

    fun getAutoUpdateEnabled(): Flow<Boolean>

    suspend fun setAutoUpdateEnabled(enabled: Boolean)

    fun getUpdateCheckInterval(): Flow<Long>

    suspend fun setUpdateCheckInterval(hours: Long)

    fun getIncludePreReleases(): Flow<Boolean>

    suspend fun setIncludePreReleases(enabled: Boolean)

    fun getLiquidGlassEnabled(): Flow<Boolean>

    suspend fun setLiquidGlassEnabled(enabled: Boolean)

    fun getHideSeenEnabled(): Flow<Boolean>

    suspend fun setHideSeenEnabled(enabled: Boolean)

    fun getDiscoveryPlatforms(): Flow<Set<DiscoveryPlatform>>

    suspend fun setDiscoveryPlatforms(platforms: Set<DiscoveryPlatform>)

    fun getScrollbarEnabled(): Flow<Boolean>

    suspend fun setScrollbarEnabled(enabled: Boolean)

    fun getTelemetryEnabled(): Flow<Boolean>

    suspend fun setTelemetryEnabled(enabled: Boolean)

    fun getTranslationProvider(): Flow<TranslationProvider>

    suspend fun setTranslationProvider(provider: TranslationProvider)

    fun getYoudaoAppKey(): Flow<String>

    suspend fun setYoudaoAppKey(appKey: String)

    fun getYoudaoAppSecret(): Flow<String>

    suspend fun setYoudaoAppSecret(appSecret: String)

    /**
     * Selected UI language as a BCP 47 tag (e.g. `zh-CN`). Emits
     * `null` when the user hasn't picked one — which means "follow
     * whatever the JVM/Android locale is" at app start. `null` is
     * distinct from `""`: the former is the unset state, the latter
     * would be a malformed user choice we don't support.
     */
    fun getAppLanguage(): Flow<String?>

    suspend fun setAppLanguage(tag: String?)

    fun getExternalImportEnabled(): Flow<Boolean>

    suspend fun setExternalImportEnabled(enabled: Boolean)

    fun getExternalMatchSearchEnabled(): Flow<Boolean>

    suspend fun setExternalMatchSearchEnabled(enabled: Boolean)

    fun getExternalImportBannerDismissedAtCount(): Flow<Int>

    suspend fun setExternalImportBannerDismissedAtCount(count: Int)

    /**
     * One-shot flag for the APK Inspect coachmark next to the install
     * button on the details screen. `false` until the user has seen the
     * coachmark at least once; flips permanently to `true` thereafter.
     */
    fun getApkInspectCoachmarkShown(): Flow<Boolean>

    suspend fun setApkInspectCoachmarkShown(shown: Boolean)

    fun getLastSeenWhatsNewVersionCode(): Flow<Int?>

    suspend fun setLastSeenWhatsNewVersionCode(versionCode: Int)

    fun getAnnouncementsDismissedIds(): Flow<Set<String>>

    suspend fun addAnnouncementDismissedId(id: String)

    fun getAnnouncementsAcknowledgedIds(): Flow<Set<String>>

    suspend fun addAnnouncementAcknowledgedId(id: String)

    fun getAnnouncementsMutedCategories(): Flow<Set<AnnouncementCategory>>

    suspend fun setAnnouncementCategoryMuted(category: AnnouncementCategory, muted: Boolean)

    fun getAnnouncementsLastFetchedAt(): Flow<Long>

    suspend fun setAnnouncementsLastFetchedAt(epochMillis: Long)
}
