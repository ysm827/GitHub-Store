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
     * credential.
     *
     * Validation flow:
     *   1. Client-side format check (rejects obvious paste-errors).
     *   2. Network-side check against GitHub's `/user` endpoint — if
     *      GitHub returns 401/403 we reject and do NOT persist. If GitHub
     *      is unreachable (timeout/DNS/block), we persist optimistically.
     *      A bad-but-unreachable token will surface a 401 on the first
     *      real authenticated call, same as any expired token.
     *
     * Use case: users on networks where the browser-side of device flow
     * (reaching `github.com/login/device`) is unreliable — they generate
     * a PAT on a device where GitHub works, paste it here, and skip the
     * browser dance entirely. Unreachable-but-save-anyway is deliberate:
     * the whole reason this feature exists is for users who can't reach
     * GitHub reliably in the moment.
     *
     * @return [Result.success] on persist, [Result.failure] on client-side
     *   format error or GitHub-side 401/403 rejection. On [Result.failure]
     *   the caller should keep the input sheet open so the user can fix
     *   the token.
     */
    suspend fun signInWithPat(token: String): Result<Unit>

    suspend fun registerWebAuth(): Result<WebAuthRegistration>

    suspend fun exchangeWebAuthHandoff(handoffId: String): Result<String>
}

data class WebAuthRegistration(
    val state: String,
    val authUrl: String,
)

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

/**
 * Reason a Personal Access Token was rejected by GitHub. Lives in the
 * domain layer so the VM can pattern-match and map to localized strings
 * without any raw English leaking through from the data layer.
 */
sealed interface RejectedKind {
    /** GitHub returned 401 — token is invalid or has been revoked. */
    data object BadCredentials : RejectedKind

    /** GitHub returned 403 — token lacks required permissions/scopes or is banned. */
    data object InsufficientScope : RejectedKind

    /** Any other non-2xx status that still represents a definitive reject. */
    data class Other(val statusCode: Int) : RejectedKind
}

/**
 * Thrown by `signInWithPat` when GitHub definitively rejects the token
 * (as opposed to "we couldn't reach GitHub to ask"). Carries a typed
 * [kind] so the presentation layer can display a localized error.
 */
class PatRejectedException(val kind: RejectedKind) : Exception("PAT rejected: $kind")
