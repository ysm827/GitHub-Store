package zed.rainxch.auth.data.repository

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import zed.rainxch.auth.data.network.BackendHttpException
import zed.rainxch.auth.data.network.GitHubAuthApi
import zed.rainxch.auth.domain.repository.AuthPath
import zed.rainxch.auth.domain.repository.AuthenticationRepository
import zed.rainxch.auth.domain.repository.DeviceFlowStart
import zed.rainxch.auth.domain.repository.DevicePollResult
import zed.rainxch.auth.domain.repository.PollOutcome
import zed.rainxch.core.data.data_source.TokenStore
import zed.rainxch.core.data.dto.GithubDeviceTokenSuccessDto
import zed.rainxch.core.data.mappers.toData
import zed.rainxch.core.data.mappers.toDomain
import zed.rainxch.core.data.network.BACKEND_ORIGIN
import zed.rainxch.core.domain.logging.GitHubStoreLogger
import zed.rainxch.core.domain.model.GithubDeviceStart
import zed.rainxch.core.domain.model.GithubDeviceTokenSuccess
import zed.rainxch.feature.auth.data.BuildKonfig
import java.util.concurrent.TimeoutException

class AuthenticationRepositoryImpl(
    private val tokenStore: TokenStore,
    private val logger: GitHubStoreLogger,
) : AuthenticationRepository {
    override val accessTokenFlow: Flow<String?>
        get() = tokenStore.tokenFlow().map { it?.accessToken }

    override suspend fun startDeviceFlow(): DeviceFlowStart =
        withContext(Dispatchers.IO) {
            val clientId = BuildKonfig.GITHUB_CLIENT_ID
            require(clientId.isNotBlank()) {
                "Missing GitHub CLIENT_ID. Add GITHUB_CLIENT_ID to local.properties."
            }

            try {
                val dto = GitHubAuthApi.startDeviceFlowViaBackend(BACKEND_ORIGIN)
                logger.debug("✅ Device flow started via Backend. User code: ${dto.userCode}")
                DeviceFlowStart(dto.toDomain(), AuthPath.Backend)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e.isAuthInfrastructureError()) {
                    logger.debug(
                        "Backend device/start failed (${e::class.simpleName}: ${e.message}) — falling back to Direct",
                    )
                    try {
                        val dto = GitHubAuthApi.startDeviceFlowDirect(clientId)
                        logger.debug("✅ Device flow started via Direct. User code: ${dto.userCode}")
                        DeviceFlowStart(dto.toDomain(), AuthPath.Direct)
                    } catch (inner: CancellationException) {
                        throw inner
                    } catch (inner: Throwable) {
                        logger.debug("❌ Direct device/start also failed: ${inner.message}")
                        throw Exception(
                            "Failed to start GitHub authentication. " +
                                "Please check your internet connection and try again.",
                            inner,
                        )
                    }
                } else {
                    logger.debug("❌ Backend device/start returned non-infra error: ${e.message}")
                    throw Exception(
                        "Failed to start GitHub authentication. " +
                            "Please check your internet connection and try again.",
                        e,
                    )
                }
            }
        }

    override suspend fun awaitDeviceToken(start: GithubDeviceStart): GithubDeviceTokenSuccess =
        withContext(Dispatchers.IO) {
            val clientId = BuildKonfig.GITHUB_CLIENT_ID
            val timeoutMs = start.expiresInSec * 1000L
            val startTime = System.currentTimeMillis()

            val initialJitter = (0..2000).random().toLong()
            delay(initialJitter)

            var pollingInterval = (start.intervalSec.coerceAtLeast(5)) * 1000L
            var consecutiveNetworkErrors = 0
            var consecutiveUnknownErrors = 0
            var slowDownCount = 0

            logger.debug("⏱️ Polling started. Timeout: ${start.expiresInSec}s, Interval: ${start.intervalSec}s")

            while (isActive) {
                if (System.currentTimeMillis() - startTime >= timeoutMs) {
                    throw TimeoutException(
                        "Authentication timed out after ${start.expiresInSec} seconds. Please try again.",
                    )
                }

                try {
                    val res = GitHubAuthApi.pollDeviceTokenDirect(clientId, start.deviceCode)
                    val success = res.getOrNull()?.toDomain()

                    if (success != null) {
                        logger.debug("✅ Token received! Saving...")

                        saveTokenWithVerification(success)

                        logger.debug("✅ Token saved and verified successfully!")
                        return@withContext success
                    }

                    val error = res.exceptionOrNull()
                    val errorMsg = (error?.message ?: "").lowercase()

                    when {
                        "authorization_pending" in errorMsg -> {
                            consecutiveNetworkErrors = 0
                            consecutiveUnknownErrors = 0
                            if (slowDownCount > 0) slowDownCount--

                            logger.debug("📡 Waiting for user authorization...")
                            delay(pollingInterval + (0..1000).random())
                        }

                        "slow_down" in errorMsg -> {
                            consecutiveNetworkErrors = 0
                            consecutiveUnknownErrors = 0
                            slowDownCount++
                            pollingInterval += 5000

                            logger.debug("⚠️ Rate limited. New interval: ${pollingInterval}ms (slowdown #$slowDownCount)")

                            if (slowDownCount > 10) {
                                throw Exception(
                                    "GitHub is experiencing high traffic. Please wait a few minutes and try again.",
                                )
                            }

                            delay(pollingInterval + (0..3000).random())
                        }

                        "access_denied" in errorMsg -> {
                            throw Exception(
                                "Authentication was denied. Please try again if this was a mistake.",
                            )
                        }

                        "expired_token" in errorMsg ||
                            "expired_device_code" in errorMsg ||
                            "token_expired" in errorMsg -> {
                            throw Exception(
                                "Authorization code expired. Please try again.",
                            )
                        }

                        "bad_verification_code" in errorMsg ||
                            "incorrect_device_code" in errorMsg -> {
                            throw Exception(
                                "Invalid verification code. Please restart authentication.",
                            )
                        }

                        isNetworkError(errorMsg) -> {
                            consecutiveNetworkErrors++
                            consecutiveUnknownErrors = 0

                            logger.debug("⚠️ Network error ($consecutiveNetworkErrors/8): $errorMsg")

                            if (consecutiveNetworkErrors >= 8) {
                                throw Exception(
                                    "Network connection is unstable. Please check your connection and try again.",
                                )
                            }

                            val backoff =
                                minOf(
                                    pollingInterval * (1 + consecutiveNetworkErrors),
                                    30_000L,
                                )
                            delay(backoff)
                        }

                        else -> {
                            consecutiveUnknownErrors++
                            logger.debug("⚠️ Unknown error ($consecutiveUnknownErrors/5): $errorMsg")

                            if (consecutiveUnknownErrors >= 5) {
                                throw Exception(
                                    "Authentication failed: ${error?.message ?: "Unknown error"}",
                                )
                            }

                            val backoff =
                                minOf(
                                    pollingInterval * (1 + consecutiveUnknownErrors / 2),
                                    20_000L,
                                )
                            delay(backoff)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TimeoutException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveUnknownErrors++
                    logger.debug("❌ Unexpected error ($consecutiveUnknownErrors/5): ${e.message}")

                    if (consecutiveUnknownErrors >= 5) {
                        throw Exception(
                            "Authentication failed after multiple errors: ${e.message}",
                            e,
                        )
                    }

                    delay(minOf(pollingInterval * 2, 15_000L))
                }
            }

            throw CancellationException("Authentication was cancelled")
        }

    private suspend fun saveTokenWithVerification(token: GithubDeviceTokenSuccess) {
        repeat(5) { attempt ->
            try {
                tokenStore.save(token.toData())

                delay(100)
                val saved = tokenStore.currentToken()

                if (saved?.accessToken == token.accessToken) {
                    return
                } else {
                    logger.debug("⚠️ Token verification failed (attempt ${attempt + 1}/5)")
                    if (attempt == 4) {
                        throw Exception("Token was not persisted correctly after 5 attempts")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug("⚠️ Token save failed (attempt ${attempt + 1}/5): ${e.message}")
                if (attempt == 4) {
                    throw Exception("Failed to save authentication token: ${e.message}", e)
                }
                delay(500L * (attempt + 1))
            }
        }
    }

    override suspend fun pollDeviceTokenOnce(
        deviceCode: String,
        path: AuthPath,
    ): PollOutcome =
        withContext(Dispatchers.IO) {
            val clientId = BuildKonfig.GITHUB_CLIENT_ID

            val primaryResult =
                when (path) {
                    AuthPath.Backend -> GitHubAuthApi.pollDeviceTokenViaBackend(BACKEND_ORIGIN, deviceCode)
                    AuthPath.Direct -> GitHubAuthApi.pollDeviceTokenDirect(clientId, deviceCode)
                }

            val primaryError = primaryResult.exceptionOrNull()
            if (path == AuthPath.Backend && primaryError != null && primaryError.isAuthInfrastructureError()) {
                logger.debug(
                    "Backend poll infra error (${primaryError::class.simpleName}: " +
                        "${primaryError.message}) — escalating to Direct for rest of session",
                )
                val fallbackResult = GitHubAuthApi.pollDeviceTokenDirect(clientId, deviceCode)
                return@withContext PollOutcome(interpretPollResult(fallbackResult), AuthPath.Direct)
            }

            PollOutcome(interpretPollResult(primaryResult), path)
        }

    private suspend fun interpretPollResult(
        res: Result<GithubDeviceTokenSuccessDto>,
    ): DevicePollResult {
        val success = res.getOrNull()?.toDomain()

        if (success != null) {
            logger.debug("✅ Single poll: Token received! Saving...")
            saveTokenWithVerification(success)
            return DevicePollResult.Success(success)
        }

        val error = res.exceptionOrNull()
        val errorMsg = (error?.message ?: "").lowercase()

        return when {
            "slow_down" in errorMsg -> {
                logger.debug("⚠️ GitHub says slow down")
                DevicePollResult.SlowDown
            }

            "authorization_pending" in errorMsg -> {
                DevicePollResult.Pending
            }

            "access_denied" in errorMsg -> {
                DevicePollResult.Failed(
                    Exception("Authentication was denied. Please try again if this was a mistake."),
                )
            }

            "expired_token" in errorMsg ||
                "expired_device_code" in errorMsg ||
                "token_expired" in errorMsg -> {
                DevicePollResult.Failed(
                    Exception("Authorization code expired. Please try again."),
                )
            }

            "bad_verification_code" in errorMsg ||
                "incorrect_device_code" in errorMsg -> {
                DevicePollResult.Failed(
                    Exception("Invalid verification code. Please restart authentication."),
                )
            }

            else -> {
                logger.debug("❌ Single poll unrecognized error — surfacing as Failed: $errorMsg")
                DevicePollResult.Failed(
                    error ?: Exception("Authentication failed: unknown error"),
                )
            }
        }
    }

    private fun Throwable.isAuthInfrastructureError(): Boolean =
        when (this) {
            is HttpRequestTimeoutException,
            is SocketTimeoutException,
            is ConnectTimeoutException,
            -> true
            is BackendHttpException -> statusCode in 500..599
            else -> isNetworkError((message ?: "").lowercase())
        }

    private fun isNetworkError(errorMsg: String): Boolean =
        errorMsg.contains("unable to resolve") ||
            errorMsg.contains("no address") ||
            errorMsg.contains("failed to connect") ||
            errorMsg.contains("connection refused") ||
            errorMsg.contains("network is unreachable") ||
            errorMsg.contains("timeout") ||
            errorMsg.contains("timed out") ||
            errorMsg.contains("connection reset") ||
            errorMsg.contains("broken pipe") ||
            errorMsg.contains("host unreachable") ||
            errorMsg.contains("network error")
}
