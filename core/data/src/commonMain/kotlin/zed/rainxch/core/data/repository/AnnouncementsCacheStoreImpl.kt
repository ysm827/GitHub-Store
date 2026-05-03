package zed.rainxch.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import zed.rainxch.core.domain.repository.AnnouncementsCacheStore

class AnnouncementsCacheStoreImpl(
    private val preferences: DataStore<Preferences>,
) : AnnouncementsCacheStore {
    override fun getCachedPayload(): Flow<String?> =
        preferences.data.map { prefs -> prefs[CACHED_PAYLOAD_KEY] }

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
