package zed.rainxch.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import zed.rainxch.core.domain.repository.DeviceIdentityRepository

@OptIn(ExperimentalUuidApi::class)
class DeviceIdentityRepositoryImpl(
    private val preferences: DataStore<Preferences>,
) : DeviceIdentityRepository {

    // Serialises the read-check-generate-write sequence so two concurrent
    // first callers can't each mint a different UUID and race to persist
    // it. DataStore's `edit` alone is atomic per-write but doesn't cover
    // the read-then-conditionally-write pattern we need here.
    private val deviceIdMutex = Mutex()

    override suspend fun getDeviceId(): String =
        deviceIdMutex.withLock {
            val existing = preferences.data.first()[DEVICE_ID_KEY]
            if (!existing.isNullOrBlank()) return existing

            val generated = Uuid.random().toString()
            preferences.edit { it[DEVICE_ID_KEY] = generated }
            generated
        }

    override suspend fun resetDeviceId(): String =
        deviceIdMutex.withLock {
            val next = Uuid.random().toString()
            preferences.edit { it[DEVICE_ID_KEY] = next }
            next
        }

    private companion object {
        private val DEVICE_ID_KEY = stringPreferencesKey("anonymous_device_id")
    }
}
