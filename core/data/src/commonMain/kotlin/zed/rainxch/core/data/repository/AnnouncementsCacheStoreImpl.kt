package zed.rainxch.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import zed.rainxch.core.domain.repository.AnnouncementsCacheStore

class AnnouncementsCacheStoreImpl(
    private val preferences: DataStore<Preferences>,
) : AnnouncementsCacheStore {
    private val logger = Logger.withTag("AnnouncementsCacheStore")

    override fun getCachedPayload(): Flow<String?> =
        preferences.data
            .catch { error ->
                if (error is CancellationException) throw error
                logger.w("Cache read failed: ${error.message}")
                emit(emptyPreferences())
            }
            .map { prefs -> prefs[CACHED_PAYLOAD_KEY] }

    override suspend fun setCachedPayload(payload: String?) {
        preferences.edit { prefs ->
            if (payload == null) {
                prefs.remove(CACHED_PAYLOAD_KEY)
            } else {
                prefs[CACHED_PAYLOAD_KEY] = payload
            }
        }
    }

    private companion object {
        val CACHED_PAYLOAD_KEY = stringPreferencesKey("announcements_cached_payload")
    }
}
