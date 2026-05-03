package zed.rainxch.core.domain.repository

import kotlinx.coroutines.flow.Flow

interface AnnouncementsCacheStore {
    fun getCachedPayload(): Flow<String?>

    suspend fun setCachedPayload(payload: String?)
}
