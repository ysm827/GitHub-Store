package zed.rainxch.auth.domain.repository

import kotlinx.coroutines.flow.Flow
import zed.rainxch.core.domain.model.GithubDeviceStart
import zed.rainxch.core.domain.model.GithubDeviceTokenSuccess

interface AuthenticationRepository {
    val accessTokenFlow: Flow<String?>

    suspend fun startDeviceFlow(): DeviceFlowStart

    suspend fun awaitDeviceToken(start: GithubDeviceStart): GithubDeviceTokenSuccess

    suspend fun pollDeviceTokenOnce(
        deviceCode: String,
        path: AuthPath,
    ): PollOutcome

    /**
     * Saves a user-supplied Personal Access Token as the active auth
     * credential. No network validation at save time — an invalid or
     * revoked token surfaces as a 401 on the first authenticated API
     * call, identical to how expired device-flow tokens behave.
     *
     * Use case: users on networks where the browser-side of device flow
     * (reaching `github.com/login/device`) is unreliable — they generate
     * a PAT on a device where GitHub works, paste it here, and skip the
     * browser dance entirely.
     */
    suspend fun signInWithPat(token: String): Result<Unit>
}

enum class AuthPath { Backend, Direct }

data class DeviceFlowStart(
    val start: GithubDeviceStart,
    val path: AuthPath,
)

data class PollOutcome(
    val result: DevicePollResult,
    val path: AuthPath,
)

sealed interface DevicePollResult {
    data class Success(val token: GithubDeviceTokenSuccess) : DevicePollResult

    data object Pending : DevicePollResult

    data object SlowDown : DevicePollResult

    data class Failed(val error: Throwable) : DevicePollResult
}
