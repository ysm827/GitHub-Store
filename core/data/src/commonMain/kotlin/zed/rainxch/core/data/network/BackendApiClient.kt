package zed.rainxch.core.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import zed.rainxch.core.data.dto.BackendRepoResponse
import zed.rainxch.core.data.dto.BackendSearchResponse
import kotlin.coroutines.cancellation.CancellationException

class BackendApiClient {

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 5_000
        }
        defaultRequest {
            url(BASE_URL)
        }
        expectSuccess = false
    }

    suspend fun getCategory(category: String, platform: String): Result<List<BackendRepoResponse>> =
        safeCall {
            val response = httpClient.get("categories/$category/$platform")
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(BackendException("HTTP ${response.status.value}"))
            }
        }

    suspend fun getTopic(bucket: String, platform: String): Result<List<BackendRepoResponse>> =
        safeCall {
            val response = httpClient.get("topics/$bucket/$platform")
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(BackendException("HTTP ${response.status.value}"))
            }
        }

    suspend fun search(
        query: String,
        platform: String? = null,
        sort: String? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): Result<BackendSearchResponse> =
        safeCall {
            val response = httpClient.get("search") {
                parameter("q", query)
                if (platform != null) parameter("platform", platform)
                if (sort != null) parameter("sort", sort)
                parameter("limit", limit)
                parameter("offset", offset)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(BackendException("HTTP ${response.status.value}"))
            }
        }

    private inline fun <T> safeCall(block: () -> Result<T>): Result<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        private const val BASE_URL = "https://api.github-store.org/v1/"
    }
}

class BackendException(message: String) : Exception(message)
