package zed.rainxch.core.domain.repository

import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.domain.model.MirrorConfig
import zed.rainxch.core.domain.model.MirrorPreference

interface MirrorRepository {
    /**
     * Emits the cached catalog immediately, then fresh entries on each
     * successful refresh. Falls back to the bundled list when the cache
     * is empty and the backend is unreachable.
     */
    fun observeCatalog(): Flow<List<MirrorConfig>>

    /** Forces a backend fetch ignoring the 24h cache. */
    suspend fun refreshCatalog(): Result<Unit>

    fun observePreference(): Flow<MirrorPreference>

    suspend fun setPreference(pref: MirrorPreference)

    /**
     * Emits a one-shot notice when the user's previously-selected mirror
     * disappears from a freshly-fetched catalog and the repository
     * auto-falls-back to Direct. UI surfaces a toast.
     */
    fun observeRemovedNotices(): Flow<MirrorRemoved>

    suspend fun snoozeAutoSuggest(forMs: Long)

    suspend fun dismissAutoSuggestPermanently()
}

data class MirrorRemoved(
    val displayName: String,
)
