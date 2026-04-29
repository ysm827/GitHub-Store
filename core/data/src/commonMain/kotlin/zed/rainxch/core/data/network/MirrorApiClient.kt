package zed.rainxch.core.data.network

import zed.rainxch.core.data.dto.MirrorListResponse

class MirrorApiClient(
    private val backendApiClient: BackendApiClient,
) {
    /**
     * Fetches the mirror catalog from the backend. Delegates to
     * [BackendApiClient] — which routes through the discovery proxy
     * scope but never through MirrorRewriteInterceptor (the
     * interceptor lives on the GitHub-bound client only).
     */
    suspend fun fetchList(): Result<MirrorListResponse> =
        backendApiClient.getMirrorList()
}
